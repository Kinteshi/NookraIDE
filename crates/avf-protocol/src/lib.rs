use serde::{Deserialize, Serialize};
use std::io::Cursor;
use uuid::Uuid;

pub const PROTOCOL_VERSION: u32 = 1;
pub const MAX_FRAME_SIZE: usize = 32 * 1024 * 1024;
pub const MAX_FILE_SIZE: usize = 16 * 1024 * 1024;

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
    Lsp { server_id: String, json_rpc: String },
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
    VersionMismatch { daemon_version: u32 },
    HandshakeTimeout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
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
    ReadFileReq {
        req_id: u64,
        path: String,
    },
    WriteFileReq {
        req_id: u64,
        path: String,
        #[serde(with = "serde_bytes")]
        content: Vec<u8>,
        expected_modified_ts: Option<u64>,
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
    ReadFileRes {
        req_id: u64,
        #[serde(with = "serde_bytes")]
        content: Vec<u8>,
        modified_ts: u64,
    },
    WriteFileRes {
        req_id: u64,
        modified_ts: u64,
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
            MultiplexedPacket::Lsp {
                server_id: "rust-analyzer".into(),
                json_rpc: r#"{"jsonrpc":"2.0"}"#.into(),
            },
            MultiplexedPacket::Vfs(VfsMessage::ListDirReq {
                req_id: 1,
                path: "/workspace".into(),
            }),
            MultiplexedPacket::Vfs(VfsMessage::ReadFileReq {
                req_id: 2,
                path: "/workspace/a".into(),
            }),
            MultiplexedPacket::Vfs(VfsMessage::WriteFileReq {
                req_id: 3,
                path: "/workspace/a".into(),
                content: vec![1, 2, 3],
                expected_modified_ts: Some(42),
            }),
            MultiplexedPacket::Vfs(VfsMessage::WriteFileReq {
                req_id: 10,
                path: "/workspace/new".into(),
                content: Vec::new(),
                expected_modified_ts: None,
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
                modified_ts: 42,
            }),
            MultiplexedPacket::Vfs(VfsMessage::WriteFileRes {
                req_id: 8,
                modified_ts: 42,
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
