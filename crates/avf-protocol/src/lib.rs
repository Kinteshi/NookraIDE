use serde::{Deserialize, Serialize};
use std::io::Cursor;
use uuid::Uuid;

pub const PROTOCOL_VERSION: u32 = 2;
pub const MAX_FRAME_SIZE: usize = 32 * 1024 * 1024;
pub const MAX_FILE_SIZE: usize = 16 * 1024 * 1024;
pub const MAX_CHUNK_SIZE: usize = 4 * 1024 * 1024;
pub const MAX_PAGED_FILE_SIZE: usize = 256 * 1024 * 1024;

pub type DecodeError = rmp_serde::decode::Error;

pub fn encode(packet: &MultiplexedPacket) -> Vec<u8> {
    let mut bytes = Vec::new();
    let mut serializer = rmp_serde::Serializer::new(&mut bytes)
        .with_struct_map()
        .with_human_readable();
    packet
        .serialize(&mut serializer)
        .expect("serializing protocol values to MessagePack cannot fail");
    bytes
}

pub fn decode(bytes: &[u8]) -> Result<MultiplexedPacket, DecodeError> {
    if bytes.len() > MAX_FRAME_SIZE {
        return Err(DecodeError::Syntax(
            "packet exceeds the 32 MiB protocol limit".to_owned(),
        ));
    }
    let mut cursor = Cursor::new(bytes);
    let packet = {
        let mut deserializer = rmp_serde::Deserializer::new(&mut cursor).with_human_readable();
        MultiplexedPacket::deserialize(&mut deserializer)?
    };
    if cursor.position() != bytes.len() as u64 {
        return Err(DecodeError::Syntax(
            "packet contains trailing MessagePack data".to_owned(),
        ));
    }
    Ok(packet)
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum MultiplexedPacket {
    System(SystemMessage),
    Vfs(VfsMessage),
    Pty { session_id: Uuid, data: PtyPayload },
    Rpc(RpcMessage),
    Search(SearchMessage),
    Git(GitMessage),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RpcKind {
    Lsp,
    Dap,
    Acp,
    /// Newline-delimited JSON-RPC like `Acp`, for Jupyter kernel bridges.
    Kernel,
    Custom,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum RpcMessage {
    Start {
        bridge_id: Uuid,
        tool: String,
        kind: RpcKind,
        args: Vec<String>,
        cwd: Option<String>,
        env: Vec<(String, String)>,
    },
    Started {
        bridge_id: Uuid,
        pid: u32,
    },
    Message {
        bridge_id: Uuid,
        body: String,
    },
    Stop {
        bridge_id: Uuid,
    },
    Exited {
        bridge_id: Uuid,
        code: Option<i32>,
    },
    Error {
        bridge_id: Uuid,
        message: String,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum SystemMessage {
    Hello {
        protocol_version: u32,
        auth_token: String,
        client_name: String,
    },
    Welcome {
        protocol_version: u32,
        daemon_version: String,
        pty_sessions: Vec<PtySessionInfo>,
    },
    Rejected {
        reason: RejectReason,
    },
    /// First packet instead of Hello: trade a one-time pairing code for the session token.
    Pair {
        protocol_version: u32,
        code: String,
        client_name: String,
    },
    /// Reply to Pair; the daemon closes the connection and the client reconnects with Hello.
    Paired {
        token: String,
    },
    Ping {
        timestamp: u64,
    },
    Pong {
        timestamp: u64,
        cpu_usage: f32,
        mem_used_mb: u64,
        mem_total_mb: u64,
    },
    PortOpened {
        port: u16,
        protocol: PortProtocol,
        process_name: Option<String>,
    },
    PortClosed {
        port: u16,
        protocol: PortProtocol,
    },
    ProtocolError {
        message: String,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum RejectReason {
    InvalidToken,
    /// No pairing code is active (never issued, expired, or out of attempts).
    PairingUnavailable,
    PairingFailed { attempts_left: u32 },
    VersionMismatch { daemon_version: u32 },
    HandshakeTimeout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum PortProtocol {
    Tcp,
    Tcp6,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PtySessionInfo {
    pub session_id: Uuid,
    pub shell: String,
    pub cwd: String,
    pub cols: u16,
    pub rows: u16,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum PtyPayload {
    Spawn {
        shell: Option<String>,
        cwd: Option<String>,
        cols: u16,
        rows: u16,
    },
    Input(#[serde(with = "serde_bytes")] Vec<u8>),
    Resize {
        cols: u16,
        rows: u16,
    },
    Reattach,
    Kill,
    Spawned {
        pid: u32,
    },
    Output(#[serde(with = "serde_bytes")] Vec<u8>),
    Exit {
        code: Option<i32>,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum VfsMessage {
    ListDirReq {
        req_id: u64,
        path: String,
    },
    StatReq {
        req_id: u64,
        path: String,
    },
    ReadFileReq {
        req_id: u64,
        path: String,
        offset: u64,
        len: u32,
    },
    WriteFileReq {
        req_id: u64,
        path: String,
        #[serde(with = "serde_bytes")]
        content: Vec<u8>,
        expected_modified_ts: Option<u64>,
        truncate: bool,
        offset: u64,
    },
    FlushReq {
        req_id: u64,
        path: String,
    },
    CreateReq {
        req_id: u64,
        path: String,
        kind: EntryKind,
    },
    DeleteReq {
        req_id: u64,
        path: String,
        recursive: bool,
        to_trash: bool,
    },
    RenameReq {
        req_id: u64,
        from: String,
        to: String,
        overwrite: bool,
    },
    CopyReq {
        req_id: u64,
        from: String,
        to: String,
        overwrite: bool,
    },
    RootsReq {
        req_id: u64,
    },
    RestoreReq {
        req_id: u64,
        trash_id: Uuid,
    },
    WatchReq {
        req_id: u64,
        path: String,
        recursive: bool,
    },
    UnwatchReq {
        req_id: u64,
        path: String,
    },
    ListDirRes {
        req_id: u64,
        entries: Vec<FileEntry>,
    },
    StatRes {
        req_id: u64,
        entry: FileEntry,
    },
    ReadFileRes {
        req_id: u64,
        #[serde(with = "serde_bytes")]
        content: Vec<u8>,
        eof: bool,
        modified_ts: u64,
        size: u64,
    },
    Ack {
        req_id: u64,
        modified_ts: Option<u64>,
        trash_id: Option<Uuid>,
    },
    RootsRes {
        req_id: u64,
        roots: Vec<String>,
    },
    WatchRes {
        req_id: u64,
    },
    VfsError {
        req_id: u64,
        code: VfsErrorCode,
        message: String,
    },
    FsEvent {
        kind: FsEventKind,
        paths: Vec<String>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum EntryKind {
    File,
    Dir,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum VfsErrorCode {
    NotFound,
    PermissionDenied,
    AlreadyExists,
    IsADirectory,
    NotADirectory,
    TooLarge,
    Conflict,
    InvalidPath,
    Io,
    Unsupported,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum FsEventKind {
    Create,
    Modify,
    Remove,
    Rename,
    Rescan,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub is_symlink: bool,
    pub size: u64,
    pub modified_ts: u64,
    pub mode: u32,
    pub is_hidden: bool,
    pub git_status: Option<GitFileStatus>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum GitFileStatus {
    Untracked,
    Added,
    Modified,
    Deleted,
    Renamed,
    Copied,
    Conflicted,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum SearchMessage {
    StartReq {
        req_id: u64,
        roots: Vec<String>,
        query: String,
        regex: bool,
        case_sensitive: bool,
        whole_word: bool,
        include: Vec<String>,
        exclude: Vec<String>,
        max_results: u32,
        context_lines: u8,
    },
    Cancel {
        req_id: u64,
    },
    Match {
        req_id: u64,
        path: String,
        line: u32,
        col: u32,
        len: u32,
        preview: String,
        context_before: Vec<String>,
        context_after: Vec<String>,
    },
    Done {
        req_id: u64,
        total: u32,
        truncated: bool,
        elapsed_ms: u32,
    },
    Error {
        req_id: u64,
        message: String,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum GitMessage {
    Req {
        req_id: u64,
        repo: String,
        op: GitOp,
    },
    Res {
        req_id: u64,
        result: GitResult,
    },
    Progress {
        req_id: u64,
        phase: String,
        percent: Option<u8>,
        detail: String,
    },
    Prompt {
        req_id: u64,
        prompt_id: u64,
        text: String,
        secret: bool,
    },
    PromptAnswer {
        req_id: u64,
        prompt_id: u64,
        answer: String,
    },
    Error {
        req_id: u64,
        message: String,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum GitOp {
    Status,
    DiffFile {
        path: String,
        staged: bool,
    },
    Stage(Vec<String>),
    Unstage(Vec<String>),
    Discard(Vec<String>),
    Commit {
        message: String,
        amend: bool,
        signoff: bool,
    },
    Branches,
    Checkout {
        name: String,
        create: bool,
    },
    Log {
        rev: Option<String>,
        limit: u32,
        path: Option<String>,
    },
    Show {
        rev: String,
        path: String,
    },
    Fetch,
    Pull {
        rebase: bool,
    },
    Push {
        set_upstream: bool,
        force_with_lease: bool,
    },
    Stash {
        op: StashOp,
    },
    Blame {
        path: String,
    },
    Remotes,
    Init,
    Clone {
        url: String,
        dest: String,
    },
    MergeStatus,
    Resolve {
        path: String,
        content: Vec<u8>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum StashOp {
    List,
    Push { include_untracked: bool },
    Pop { index: usize },
    Apply { index: usize },
    Drop { index: usize },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum GitResult {
    Success,
    Status {
        branch: Option<String>,
        upstream: Option<String>,
        ahead: u32,
        behind: u32,
        entries: Vec<GitStatusEntry>,
    },
    Diff {
        path: String,
        staged: bool,
        patch: String,
    },
    Branches(Vec<GitBranch>),
    Commits(Vec<GitCommit>),
    Text(String),
    Remotes(Vec<GitRemote>),
    MergeStatus {
        has_conflicts: bool,
        conflicted_paths: Vec<String>,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GitStatusEntry {
    pub path: String,
    pub original_path: Option<String>,
    pub index_status: String,
    pub worktree_status: String,
    pub status: GitFileStatus,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GitBranch {
    pub name: String,
    pub is_current: bool,
    pub upstream: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GitCommit {
    pub hash: String,
    pub parents: Vec<String>,
    pub author: String,
    pub timestamp: i64,
    pub subject: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GitRemote {
    pub name: String,
    pub fetch_url: Option<String>,
    pub push_url: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn packet_variants() -> Vec<MultiplexedPacket> {
        let id = Uuid::new_v4();
        let entry = FileEntry {
            name: "src".into(),
            path: "/workspace/src".into(),
            is_dir: true,
            is_symlink: false,
            size: 12,
            modified_ts: 42,
            mode: 0o755,
            is_hidden: false,
            git_status: Some(GitFileStatus::Modified),
        };
        let mut packets = vec![
            MultiplexedPacket::System(SystemMessage::Hello {
                protocol_version: PROTOCOL_VERSION,
                auth_token: "a".repeat(64),
                client_name: "test".into(),
            }),
            MultiplexedPacket::System(SystemMessage::Welcome {
                protocol_version: PROTOCOL_VERSION,
                daemon_version: "0.1.0".into(),
                pty_sessions: vec![PtySessionInfo {
                    session_id: id,
                    shell: "/bin/sh".into(),
                    cwd: "/workspace".into(),
                    cols: 80,
                    rows: 24,
                }],
            }),
            MultiplexedPacket::System(SystemMessage::Welcome {
                protocol_version: PROTOCOL_VERSION,
                daemon_version: "0.1.0".into(),
                pty_sessions: Vec::new(),
            }),
            MultiplexedPacket::System(SystemMessage::Rejected {
                reason: RejectReason::InvalidToken,
            }),
            MultiplexedPacket::System(SystemMessage::Rejected {
                reason: RejectReason::VersionMismatch { daemon_version: 2 },
            }),
            MultiplexedPacket::System(SystemMessage::Rejected {
                reason: RejectReason::HandshakeTimeout,
            }),
            MultiplexedPacket::System(SystemMessage::Ping { timestamp: 42 }),
            MultiplexedPacket::System(SystemMessage::Pong {
                timestamp: 42,
                cpu_usage: 12.5,
                mem_used_mb: 100,
                mem_total_mb: 256,
            }),
            MultiplexedPacket::System(SystemMessage::PortOpened {
                port: 8000,
                protocol: PortProtocol::Tcp,
                process_name: Some("python".into()),
            }),
            MultiplexedPacket::System(SystemMessage::PortOpened {
                port: 8001,
                protocol: PortProtocol::Tcp6,
                process_name: None,
            }),
            MultiplexedPacket::System(SystemMessage::PortClosed {
                port: 8000,
                protocol: PortProtocol::Tcp6,
            }),
            MultiplexedPacket::System(SystemMessage::ProtocolError {
                message: "invalid packet".into(),
            }),
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Spawn {
                    shell: Some("/bin/bash".into()),
                    cwd: Some("/workspace".into()),
                    cols: 80,
                    rows: 24,
                },
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Spawn {
                    shell: None,
                    cwd: None,
                    cols: 80,
                    rows: 24,
                },
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Input(vec![0, 1, 255]),
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Resize {
                    cols: 100,
                    rows: 40,
                },
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Reattach,
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Kill,
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Spawned { pid: 1234 },
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Output(vec![0, 1, 255]),
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Exit { code: Some(1) },
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Exit { code: None },
            },
            MultiplexedPacket::Pty {
                session_id: id,
                data: PtyPayload::Error {
                    message: "failed".into(),
                },
            },
            MultiplexedPacket::Rpc(RpcMessage::Start {
                bridge_id: id,
                tool: "basedpyright".into(),
                kind: RpcKind::Lsp,
                args: vec!["--stdio".into()],
                cwd: Some("/workspace".into()),
                env: vec![("TOKEN".into(), "secret".into())],
            }),
            MultiplexedPacket::Rpc(RpcMessage::Started {
                bridge_id: id,
                pid: 1234,
            }),
            MultiplexedPacket::Rpc(RpcMessage::Message {
                bridge_id: id,
                body: r#"{"jsonrpc":"2.0"}"#.into(),
            }),
            MultiplexedPacket::Rpc(RpcMessage::Stop { bridge_id: id }),
            MultiplexedPacket::Rpc(RpcMessage::Exited {
                bridge_id: id,
                code: Some(0),
            }),
            MultiplexedPacket::Rpc(RpcMessage::Exited {
                bridge_id: id,
                code: None,
            }),
            MultiplexedPacket::Rpc(RpcMessage::Error {
                bridge_id: id,
                message: "failed".into(),
            }),
            MultiplexedPacket::Vfs(VfsMessage::ListDirReq {
                req_id: 1,
                path: "/workspace".into(),
            }),
            MultiplexedPacket::Vfs(VfsMessage::StatReq {
                req_id: 11,
                path: "/workspace/a".into(),
            }),
            MultiplexedPacket::Vfs(VfsMessage::ReadFileReq {
                req_id: 2,
                path: "/workspace/a".into(),
                offset: 4,
                len: 1024,
            }),
            MultiplexedPacket::Vfs(VfsMessage::WriteFileReq {
                req_id: 3,
                path: "/workspace/a".into(),
                content: vec![1, 2, 3],
                expected_modified_ts: Some(42),
                truncate: true,
                offset: 0,
            }),
            MultiplexedPacket::Vfs(VfsMessage::WriteFileReq {
                req_id: 10,
                path: "/workspace/new".into(),
                content: Vec::new(),
                expected_modified_ts: None,
                truncate: false,
                offset: 3,
            }),
            MultiplexedPacket::Vfs(VfsMessage::FlushReq {
                req_id: 12,
                path: "/workspace/a".into(),
            }),
            MultiplexedPacket::Vfs(VfsMessage::CreateReq {
                req_id: 13,
                path: "/workspace/new-dir".into(),
                kind: EntryKind::Dir,
            }),
            MultiplexedPacket::Vfs(VfsMessage::CreateReq {
                req_id: 54,
                path: "/workspace/new-file".into(),
                kind: EntryKind::File,
            }),
            MultiplexedPacket::Vfs(VfsMessage::DeleteReq {
                req_id: 14,
                path: "/workspace/old".into(),
                recursive: true,
                to_trash: true,
            }),
            MultiplexedPacket::Vfs(VfsMessage::RenameReq {
                req_id: 15,
                from: "/workspace/a".into(),
                to: "/workspace/b".into(),
                overwrite: false,
            }),
            MultiplexedPacket::Vfs(VfsMessage::CopyReq {
                req_id: 16,
                from: "/workspace/a".into(),
                to: "/workspace/b".into(),
                overwrite: true,
            }),
            MultiplexedPacket::Vfs(VfsMessage::RootsReq { req_id: 17 }),
            MultiplexedPacket::Vfs(VfsMessage::RestoreReq {
                req_id: 18,
                trash_id: id,
            }),
            MultiplexedPacket::Vfs(VfsMessage::WatchReq {
                req_id: 4,
                path: "/workspace".into(),
                recursive: true,
            }),
            MultiplexedPacket::Vfs(VfsMessage::UnwatchReq {
                req_id: 5,
                path: "/workspace".into(),
            }),
            MultiplexedPacket::Vfs(VfsMessage::ListDirRes {
                req_id: 6,
                entries: vec![entry.clone()],
            }),
            MultiplexedPacket::Vfs(VfsMessage::ReadFileRes {
                req_id: 7,
                content: vec![1, 2],
                eof: true,
                modified_ts: 42,
                size: 2,
            }),
            MultiplexedPacket::Vfs(VfsMessage::StatRes {
                req_id: 8,
                entry: entry.clone(),
            }),
            MultiplexedPacket::Vfs(VfsMessage::Ack {
                req_id: 19,
                modified_ts: Some(42),
                trash_id: None,
            }),
            MultiplexedPacket::Vfs(VfsMessage::Ack {
                req_id: 20,
                modified_ts: None,
                trash_id: Some(id),
            }),
            MultiplexedPacket::Vfs(VfsMessage::RootsRes {
                req_id: 21,
                roots: vec!["/workspace".into()],
            }),
            MultiplexedPacket::Vfs(VfsMessage::WatchRes { req_id: 9 }),
            MultiplexedPacket::Vfs(VfsMessage::FsEvent {
                kind: FsEventKind::Create,
                paths: vec!["/workspace/new".into()],
            }),
        ];

        for (index, code) in [
            VfsErrorCode::NotFound,
            VfsErrorCode::PermissionDenied,
            VfsErrorCode::AlreadyExists,
            VfsErrorCode::IsADirectory,
            VfsErrorCode::NotADirectory,
            VfsErrorCode::TooLarge,
            VfsErrorCode::Conflict,
            VfsErrorCode::InvalidPath,
            VfsErrorCode::Io,
            VfsErrorCode::Unsupported,
        ]
        .into_iter()
        .enumerate()
        {
            packets.push(MultiplexedPacket::Vfs(VfsMessage::VfsError {
                req_id: index as u64,
                code,
                message: "error".into(),
            }));
        }
        for kind in [
            FsEventKind::Create,
            FsEventKind::Modify,
            FsEventKind::Remove,
            FsEventKind::Rename,
            FsEventKind::Rescan,
        ] {
            packets.push(MultiplexedPacket::Vfs(VfsMessage::FsEvent {
                kind,
                paths: vec!["/workspace/a".into(), "/workspace/b".into()],
            }));
        }
        for status in [
            GitFileStatus::Untracked,
            GitFileStatus::Added,
            GitFileStatus::Modified,
            GitFileStatus::Deleted,
            GitFileStatus::Renamed,
            GitFileStatus::Copied,
            GitFileStatus::Conflicted,
        ] {
            packets.push(MultiplexedPacket::Git(GitMessage::Res {
                req_id: 30,
                result: GitResult::Status {
                    branch: Some("main".into()),
                    upstream: Some("origin/main".into()),
                    ahead: 1,
                    behind: 2,
                    entries: vec![GitStatusEntry {
                        path: "src/lib.rs".into(),
                        original_path: None,
                        index_status: "M".into(),
                        worktree_status: " ".into(),
                        status,
                    }],
                },
            }));
        }
        packets.extend([
            MultiplexedPacket::Search(SearchMessage::StartReq {
                req_id: 40,
                roots: vec!["/workspace".into()],
                query: "needle".into(),
                regex: false,
                case_sensitive: false,
                whole_word: true,
                include: vec!["*.rs".into()],
                exclude: vec!["target".into()],
                max_results: 1000,
                context_lines: 2,
            }),
            MultiplexedPacket::Search(SearchMessage::Cancel { req_id: 40 }),
            MultiplexedPacket::Search(SearchMessage::Match {
                req_id: 40,
                path: "/workspace/src/lib.rs".into(),
                line: 12,
                col: 3,
                len: 6,
                preview: "a needle b".into(),
                context_before: vec!["before".into()],
                context_after: vec!["after".into()],
            }),
            MultiplexedPacket::Search(SearchMessage::Done {
                req_id: 40,
                total: 1,
                truncated: false,
                elapsed_ms: 12,
            }),
            MultiplexedPacket::Search(SearchMessage::Error {
                req_id: 40,
                message: "search failed".into(),
            }),
            MultiplexedPacket::Git(GitMessage::Req {
                req_id: 50,
                repo: "/workspace".into(),
                op: GitOp::Push {
                    set_upstream: true,
                    force_with_lease: false,
                },
            }),
            MultiplexedPacket::Git(GitMessage::Progress {
                req_id: 50,
                phase: "fetch".into(),
                percent: Some(10),
                detail: "fetching".into(),
            }),
            MultiplexedPacket::Git(GitMessage::Prompt {
                req_id: 50,
                prompt_id: 1,
                text: "Passphrase".into(),
                secret: true,
            }),
            MultiplexedPacket::Git(GitMessage::PromptAnswer {
                req_id: 50,
                prompt_id: 1,
                answer: "secret".into(),
            }),
            MultiplexedPacket::Git(GitMessage::Error {
                req_id: 50,
                message: "git failed".into(),
            }),
        ]);
        for kind in [RpcKind::Dap, RpcKind::Acp, RpcKind::Kernel, RpcKind::Custom] {
            packets.push(MultiplexedPacket::Rpc(RpcMessage::Start {
                bridge_id: id,
                tool: "tool".into(),
                kind,
                args: Vec::new(),
                cwd: None,
                env: Vec::new(),
            }));
        }
        for op in [
            GitOp::Status,
            GitOp::DiffFile {
                path: "file".into(),
                staged: true,
            },
            GitOp::Stage(vec!["file".into()]),
            GitOp::Unstage(vec!["file".into()]),
            GitOp::Discard(vec!["file".into()]),
            GitOp::Commit {
                message: "message".into(),
                amend: true,
                signoff: false,
            },
            GitOp::Branches,
            GitOp::Checkout {
                name: "branch".into(),
                create: true,
            },
            GitOp::Log {
                rev: Some("HEAD".into()),
                limit: 5,
                path: Some("file".into()),
            },
            GitOp::Show {
                rev: "HEAD".into(),
                path: "file".into(),
            },
            GitOp::Fetch,
            GitOp::Pull { rebase: true },
            GitOp::Push {
                set_upstream: true,
                force_with_lease: true,
            },
            GitOp::Blame {
                path: "file".into(),
            },
            GitOp::Remotes,
            GitOp::Init,
            GitOp::Clone {
                url: "https://example.invalid/repo".into(),
                dest: "repo".into(),
            },
            GitOp::MergeStatus,
            GitOp::Resolve {
                path: "file".into(),
                content: vec![0, 255],
            },
        ] {
            packets.push(MultiplexedPacket::Git(GitMessage::Req {
                req_id: 51,
                repo: "/workspace".into(),
                op,
            }));
        }
        for op in [
            StashOp::List,
            StashOp::Push {
                include_untracked: true,
            },
            StashOp::Pop { index: 0 },
            StashOp::Apply { index: 1 },
            StashOp::Drop { index: 2 },
        ] {
            packets.push(MultiplexedPacket::Git(GitMessage::Req {
                req_id: 52,
                repo: "/workspace".into(),
                op: GitOp::Stash { op },
            }));
        }
        packets.extend([
            MultiplexedPacket::Git(GitMessage::Res {
                req_id: 53,
                result: GitResult::Success,
            }),
            MultiplexedPacket::Git(GitMessage::Res {
                req_id: 53,
                result: GitResult::Diff {
                    path: "file".into(),
                    staged: false,
                    patch: "patch".into(),
                },
            }),
            MultiplexedPacket::Git(GitMessage::Res {
                req_id: 53,
                result: GitResult::Branches(vec![GitBranch {
                    name: "main".into(),
                    is_current: true,
                    upstream: Some("origin/main".into()),
                }]),
            }),
            MultiplexedPacket::Git(GitMessage::Res {
                req_id: 53,
                result: GitResult::Commits(vec![GitCommit {
                    hash: "abc123".into(),
                    parents: vec!["parent".into()],
                    author: "author".into(),
                    timestamp: 1,
                    subject: "subject".into(),
                }]),
            }),
            MultiplexedPacket::Git(GitMessage::Res {
                req_id: 53,
                result: GitResult::Text("text".into()),
            }),
            MultiplexedPacket::Git(GitMessage::Res {
                req_id: 53,
                result: GitResult::Remotes(vec![GitRemote {
                    name: "origin".into(),
                    fetch_url: Some("url".into()),
                    push_url: Some("url".into()),
                }]),
            }),
            MultiplexedPacket::Git(GitMessage::Res {
                req_id: 53,
                result: GitResult::MergeStatus {
                    has_conflicts: true,
                    conflicted_paths: vec!["file".into()],
                },
            }),
        ]);
        packets
    }

    #[test]
    fn every_packet_variant_round_trips() {
        for packet in packet_variants() {
            assert_eq!(decode(&encode(&packet)).unwrap(), packet);
        }
    }

    #[test]
    fn byte_fields_use_messagepack_binary_and_uuid_uses_string() {
        let id = Uuid::new_v4();
        let packet = MultiplexedPacket::Pty {
            session_id: id,
            data: PtyPayload::Input(vec![0, 1, 255]),
        };
        let bytes = encode(&packet);
        assert!(bytes.windows(3).any(|window| window == [0xc4, 3, 0]));
        assert!(bytes
            .windows(id.to_string().len())
            .any(|window| window == id.to_string().as_bytes()));
        assert_eq!(decode(&bytes).unwrap(), packet);
    }

    #[test]
    fn decode_rejects_malformed_and_truncated_data() {
        assert!(decode(&[]).is_err());
        assert!(decode(&[0x81, 0xa6, b'S']).is_err());
    }

    #[test]
    fn decode_rejects_multiple_packets_in_one_frame() {
        let packet = encode(&MultiplexedPacket::System(SystemMessage::Ping {
            timestamp: 1,
        }));
        let mut frame = packet.clone();
        frame.extend(packet);
        assert!(decode(&frame).is_err());
    }

    #[test]
    fn decode_rejects_frames_over_the_protocol_limit() {
        assert!(decode(&vec![0; MAX_FRAME_SIZE + 1]).is_err());
    }
}
