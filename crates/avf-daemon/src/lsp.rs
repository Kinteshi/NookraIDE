use std::{
    collections::{HashMap, VecDeque},
    io,
    process::Stdio,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Weak,
    },
    time::Duration,
};

use serde_json::Value;
use tokio::{
    io::{AsyncBufRead, AsyncBufReadExt, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader},
    process::{Child, ChildStdin, Command},
    sync::{broadcast, mpsc, watch, Mutex},
    time::{sleep, Instant},
};
use tracing::{debug, info, warn};

const MAX_HEADER_SIZE: usize = 8 * 1024;
const MAX_MESSAGE_SIZE: usize = 16 * 1024 * 1024;
const MAX_HISTORY_SIZE: usize = 1024 * 1024;
const OUTPUT_CAPACITY: usize = 128;
const INPUT_CAPACITY: usize = 32;

#[derive(Clone, PartialEq, Eq)]
struct ServerCommand {
    program: String,
    args: Vec<String>,
}

#[derive(Clone, PartialEq, Eq)]
struct LaunchConfig {
    command: ServerCommand,
    cwd: Option<std::path::PathBuf>,
    env: Vec<(String, String)>,
}

struct OutputHistory {
    messages: VecDeque<String>,
    bytes: usize,
}

impl OutputHistory {
    fn new() -> Self {
        Self {
            messages: VecDeque::new(),
            bytes: 0,
        }
    }

    fn push(&mut self, message: String) {
        let size = message.len();
        if size > MAX_HISTORY_SIZE {
            return;
        }
        while self.bytes + size > MAX_HISTORY_SIZE {
            if let Some(removed) = self.messages.pop_front() {
                self.bytes -= removed.len();
            } else {
                break;
            }
        }
        self.bytes += size;
        self.messages.push_back(message);
    }
}

struct LspSession {
    generation: u64,
    pid: u32,
    launch: LaunchConfig,
    command_tx: mpsc::Sender<String>,
    client_count_tx: watch::Sender<usize>,
    output_tx: broadcast::Sender<String>,
    history: Arc<Mutex<OutputHistory>>,
    clients: usize,
}

pub(crate) struct LspAttachment {
    pub generation: u64,
    pub pid: u32,
    pub history: Vec<String>,
    pub receiver: broadcast::Receiver<String>,
}

#[derive(Clone)]
pub(crate) struct LspRouter {
    sessions: Arc<Mutex<HashMap<String, LspSession>>>,
    servers: Arc<HashMap<String, ServerCommand>>,
    hibernation: Duration,
    next_generation: Arc<AtomicU64>,
}

impl LspRouter {
    pub(crate) fn new(hibernation: Duration) -> Self {
        Self::with_servers(hibernation, default_servers())
    }

    fn with_servers(hibernation: Duration, servers: HashMap<String, ServerCommand>) -> Self {
        Self {
            sessions: Arc::new(Mutex::new(HashMap::new())),
            servers: Arc::new(servers),
            hibernation,
            next_generation: Arc::new(AtomicU64::new(1)),
        }
    }

    #[cfg(test)]
    pub(crate) fn test_router(
        hibernation: Duration,
        tool: &str,
        program: &str,
        args: Vec<String>,
    ) -> Self {
        Self::with_servers(
            hibernation,
            HashMap::from([(
                tool.to_owned(),
                ServerCommand {
                    program: program.to_owned(),
                    args,
                },
            )]),
        )
    }

    #[cfg(test)]
    pub(crate) async fn attach(&self, server_id: &str) -> Result<LspAttachment, String> {
        self.attach_with(server_id, server_id, Vec::new(), None, Vec::new())
            .await
    }

    pub(crate) async fn attach_with(
        &self,
        server_id: &str,
        session_key: &str,
        extra_args: Vec<String>,
        cwd: Option<std::path::PathBuf>,
        env: Vec<(String, String)>,
    ) -> Result<LspAttachment, String> {
        let mut command = self
            .servers
            .get(server_id)
            .cloned()
            .ok_or_else(|| format!("unsupported language server: {server_id}"))?;
        command.args.extend(extra_args);
        let launch = LaunchConfig { command, cwd, env };
        let session_key = session_key.to_owned();
        let mut sessions = self.sessions.lock().await;

        if sessions
            .get(&session_key)
            .is_some_and(|session| session.clients == 0)
        {
            sessions.remove(&session_key);
        }

        if sessions
            .get(&session_key)
            .is_some_and(|session| session.command_tx.is_closed())
        {
            sessions.remove(&session_key);
        }

        if let Some(session) = sessions.get(&session_key) {
            if session.launch != launch {
                return Err("RPC launch identity collision".into());
            }
        }

        if !sessions.contains_key(&session_key) {
            let generation = self.next_generation.fetch_add(1, Ordering::Relaxed);
            let mut command_builder = Command::new(&launch.command.program);
            command_builder
                .args(&launch.command.args)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .kill_on_drop(true);
            if let Some(cwd) = &launch.cwd {
                command_builder.current_dir(cwd);
            }
            command_builder.envs(launch.env.iter().map(|(name, value)| (name, value)));
            let mut child = command_builder.spawn().map_err(|error| {
                format!(
                    "could not start language server {server_id} ({}): {error}",
                    launch.command.program
                )
            })?;
            let pid = child
                .id()
                .ok_or_else(|| format!("language server {server_id} has no process id"))?;
            let stdin = child
                .stdin
                .take()
                .ok_or_else(|| format!("language server {server_id} has no stdin pipe"))?;
            let stdout = child
                .stdout
                .take()
                .ok_or_else(|| format!("language server {server_id} has no stdout pipe"))?;
            let stderr = child
                .stderr
                .take()
                .ok_or_else(|| format!("language server {server_id} has no stderr pipe"))?;

            let (command_tx, command_rx) = mpsc::channel(INPUT_CAPACITY);
            let (client_count_tx, client_count_rx) = watch::channel(1);
            let (output_tx, _) = broadcast::channel(OUTPUT_CAPACITY);
            let history = Arc::new(Mutex::new(OutputHistory::new()));
            let process_server_id = session_key.clone();
            let process_history = history.clone();
            let process_output = output_tx.clone();
            let stderr_server_id = server_id.to_owned();
            let hibernation = self.hibernation;
            let stderr_tail = Arc::new(std::sync::Mutex::new(VecDeque::new()));
            let process_stderr_tail = stderr_tail.clone();

            tokio::spawn(async move {
                read_server_output(
                    process_server_id,
                    BufReader::new(stdout),
                    process_history,
                    process_output,
                )
                .await;
            });
            tokio::spawn(async move {
                let mut lines = BufReader::new(stderr).lines();
                loop {
                    match lines.next_line().await {
                        Ok(Some(line)) => {
                            debug!(server = %stderr_server_id, %line, "language server stderr");
                            let mut tail = process_stderr_tail.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
                            if tail.len() == STDERR_TAIL_LINES {
                                tail.pop_front();
                            }
                            tail.push_back(line);
                        }
                        Ok(None) => break,
                        Err(error) => {
                            warn!(server = %stderr_server_id, %error, "failed reading language server stderr");
                            break;
                        }
                    }
                }
            });
            tokio::spawn(run_server(
                child,
                stdin,
                command_rx,
                client_count_rx,
                hibernation,
                ServerCleanup {
                    sessions: Arc::downgrade(&self.sessions),
                    server_id: session_key.clone(),
                    generation,
                    stderr_tail,
                },
            ));
            sessions.insert(
                session_key.clone(),
                LspSession {
                    generation,
                    pid,
                    launch,
                    command_tx,
                    client_count_tx,
                    output_tx,
                    history,
                    clients: 1,
                },
            );
        } else if let Some(session) = sessions.get_mut(&session_key) {
            session.clients += 1;
            session.client_count_tx.send_replace(session.clients);
        }

        let session = sessions
            .get(&session_key)
            .expect("LSP session was inserted before returning");
        let history_guard = session.history.lock().await;
        let history = history_guard.messages.iter().cloned().collect();
        let receiver = session.output_tx.subscribe();
        drop(history_guard);
        Ok(LspAttachment {
            generation: session.generation,
            pid: session.pid,
            history,
            receiver,
        })
    }

    pub(crate) fn command_matches(&self, server_id: &str, command: &[String]) -> bool {
        self.servers.get(server_id).is_some_and(|server| {
            command.first() == Some(&server.program) && command[1..] == server.args
        })
    }

    pub(crate) async fn send(&self, server_id: &str, json_rpc: String) -> Result<(), String> {
        validate_json_rpc(&json_rpc).map_err(|error| error.to_string())?;
        let sessions = self.sessions.lock().await;
        let session = sessions
            .get(server_id)
            .ok_or_else(|| format!("language server {server_id} is not attached"))?;
        session
            .command_tx
            .try_send(json_rpc)
            .map_err(|error| match error {
                mpsc::error::TrySendError::Full(_) => {
                    format!("language server {server_id} input queue is full")
                }
                mpsc::error::TrySendError::Closed(_) => {
                    format!("language server {server_id} has exited")
                }
            })
    }

    pub(crate) async fn detach(&self, server_id: &str, generation: u64) {
        let mut sessions = self.sessions.lock().await;
        let Some(session) = sessions.get_mut(server_id) else {
            return;
        };
        if session.generation != generation {
            return;
        }
        session.clients = session.clients.saturating_sub(1);
        session.client_count_tx.send_replace(session.clients);
    }

    pub(crate) async fn stop(&self, server_id: &str, generation: u64) {
        let mut sessions = self.sessions.lock().await;
        let Some(session) = sessions.get_mut(server_id) else {
            return;
        };
        if session.generation != generation {
            return;
        }
        if session.clients > 1 {
            session.clients -= 1;
            session.client_count_tx.send_replace(session.clients);
        } else {
            sessions.remove(server_id);
        }
    }

    #[cfg(test)]
    async fn has_session(&self, server_id: &str) -> bool {
        self.sessions.lock().await.contains_key(server_id)
    }
}

fn default_servers() -> HashMap<String, ServerCommand> {
    [
        (
            "basedpyright".to_owned(),
            ServerCommand {
                program: "basedpyright-langserver".to_owned(),
                args: vec!["--stdio".to_owned()],
            },
        ),
        (
            "ty".to_owned(),
            ServerCommand {
                program: "ty".to_owned(),
                args: vec!["server".to_owned()],
            },
        ),
        (
            "copilot-language-server".to_owned(),
            ServerCommand {
                program: "copilot-language-server".to_owned(),
                args: vec!["--stdio".to_owned()],
            },
        ),
        (
            "ruff".to_owned(),
            ServerCommand {
                program: "ruff".to_owned(),
                args: vec!["server".to_owned()],
            },
        ),
        (
            "clangd".to_owned(),
            ServerCommand {
                program: "clangd".to_owned(),
                args: Vec::new(),
            },
        ),
        (
            "rust-analyzer".to_owned(),
            ServerCommand {
                program: "rust-analyzer".to_owned(),
                args: Vec::new(),
            },
        ),
        (
            "vtsls".to_owned(),
            ServerCommand {
                program: "vtsls".to_owned(),
                args: vec!["--stdio".to_owned()],
            },
        ),
        (
            "typescript".to_owned(),
            ServerCommand {
                program: "tsgo".to_owned(),
                args: vec!["--lsp".to_owned(), "--stdio".to_owned()],
            },
        ),
    ]
    .into()
}

/// Stderr lines kept per server, logged when it exits so an unexpected exit leaves its cause.
const STDERR_TAIL_LINES: usize = 20;

struct ServerCleanup {
    sessions: Weak<Mutex<HashMap<String, LspSession>>>,
    server_id: String,
    generation: u64,
    stderr_tail: Arc<std::sync::Mutex<VecDeque<String>>>,
}

impl ServerCleanup {
    fn stderr_tail(&self) -> String {
        let tail = self.stderr_tail.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        tail.iter().cloned().collect::<Vec<_>>().join(" | ")
    }
}

async fn run_server(
    mut child: Child,
    mut stdin: ChildStdin,
    mut commands: mpsc::Receiver<String>,
    mut client_count: watch::Receiver<usize>,
    hibernation: Duration,
    cleanup: ServerCleanup,
) {
    let mut connected_clients = *client_count.borrow();
    let mut idle_timer = Box::pin(sleep(hibernation));
    loop {
        enum Event {
            Exit(std::io::Result<std::process::ExitStatus>),
            Command(Option<String>),
            ClientCount(Result<(), watch::error::RecvError>),
            Hibernated,
        }

        let event = tokio::select! {
            status = child.wait() => Event::Exit(status),
            command = commands.recv() => Event::Command(command),
            changed = client_count.changed() => Event::ClientCount(changed),
            _ = &mut idle_timer, if connected_clients == 0 => Event::Hibernated,
        };
        match event {
            Event::Exit(Ok(status)) => {
                // Info, not debug: exits the daemon did not ask for are otherwise invisible.
                info!(server = %cleanup.server_id, %status, stderr = %cleanup.stderr_tail(), "language server exited");
                break;
            }
            Event::Exit(Err(error)) => {
                warn!(server = %cleanup.server_id, %error, "failed waiting for language server");
                break;
            }
            Event::Command(Some(message)) => {
                if let Err(error) = write_lsp_message(&mut stdin, &message).await {
                    warn!(server = %cleanup.server_id, %error, "failed writing to language server");
                    let _ = child.start_kill();
                    break;
                }
            }
            Event::Command(None) | Event::ClientCount(Err(_)) => {
                info!(server = %cleanup.server_id, "stopping language server: its session ended");
                let _ = child.start_kill();
                break;
            }
            Event::ClientCount(Ok(())) => {
                connected_clients = *client_count.borrow();
                if connected_clients == 0 {
                    idle_timer.as_mut().reset(Instant::now() + hibernation);
                }
            }
            Event::Hibernated => {
                info!(server = %cleanup.server_id, "stopping language server: idle with no clients");
                let _ = child.start_kill();
                break;
            }
        }
    }
    if let Some(sessions) = cleanup.sessions.upgrade() {
        let mut sessions = sessions.lock().await;
        if sessions
            .get(&cleanup.server_id)
            .is_some_and(|session| session.generation == cleanup.generation)
        {
            sessions.remove(&cleanup.server_id);
        }
    }
}

async fn read_server_output<R>(
    server_id: String,
    mut reader: R,
    history: Arc<Mutex<OutputHistory>>,
    output: broadcast::Sender<String>,
) where
    R: AsyncBufRead + Unpin,
{
    loop {
        match read_lsp_message(&mut reader).await {
            Ok(Some(message)) => {
                history.lock().await.push(message.clone());
                let _ = output.send(message);
            }
            Ok(None) => break,
            Err(error) => {
                warn!(server = %server_id, %error, "invalid language server output");
                break;
            }
        }
    }
}

pub(crate) async fn read_lsp_message<R>(reader: &mut R) -> io::Result<Option<String>>
where
    R: AsyncBufRead + Unpin,
{
    let Some(message) = read_content_length_message(reader).await? else {
        return Ok(None);
    };
    validate_json_rpc(&message)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
    Ok(Some(message))
}

pub(crate) async fn read_content_length_message<R>(reader: &mut R) -> io::Result<Option<String>>
where
    R: AsyncBufRead + Unpin,
{
    let mut header_bytes = 0usize;
    let mut content_length = None;
    loop {
        let mut line = String::new();
        let read = reader.read_line(&mut line).await?;
        if read == 0 {
            if header_bytes == 0 {
                return Ok(None);
            }
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "language server closed during an LSP header",
            ));
        }
        header_bytes += read;
        if header_bytes > MAX_HEADER_SIZE {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "language server LSP header is too large",
            ));
        }
        if line == "\r\n" || line == "\n" {
            break;
        }
        let (name, value) = line
            .trim_end_matches(['\r', '\n'])
            .split_once(':')
            .ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    "malformed language server LSP header",
                )
            })?;
        if name.eq_ignore_ascii_case("content-length") {
            if content_length.is_some() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "duplicate Content-Length header",
                ));
            }
            content_length = Some(value.trim().parse::<usize>().map_err(|_| {
                io::Error::new(io::ErrorKind::InvalidData, "invalid Content-Length header")
            })?);
        }
    }
    let content_length = content_length.ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "language server omitted Content-Length",
        )
    })?;
    if content_length == 0 || content_length > MAX_MESSAGE_SIZE {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "language server LSP message size is outside the allowed range",
        ));
    }
    let mut bytes = vec![0; content_length];
    reader.read_exact(&mut bytes).await?;
    String::from_utf8(bytes)
        .map(Some)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

pub(crate) async fn write_lsp_message<W>(writer: &mut W, json_rpc: &str) -> io::Result<()>
where
    W: AsyncWrite + Unpin,
{
    validate_json_rpc(json_rpc)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let bytes = json_rpc.as_bytes();
    writer
        .write_all(format!("Content-Length: {}\r\n\r\n", bytes.len()).as_bytes())
        .await?;
    writer.write_all(bytes).await?;
    writer.flush().await
}

fn validate_json_rpc(message: &str) -> Result<(), &'static str> {
    if message.len() > MAX_MESSAGE_SIZE {
        return Err("LSP JSON-RPC message is too large");
    }
    let value: Value =
        serde_json::from_str(message).map_err(|_| "LSP payload is not valid JSON")?;
    if value.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
        return Err("LSP payload must be a JSON-RPC 2.0 object");
    }
    if !value.is_object() {
        return Err("LSP JSON-RPC payload must be an object");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncReadExt;

    #[test]
    fn ruff_server_command_is_registered_exactly() {
        let router = LspRouter::new(Duration::from_secs(1));

        assert!(router.command_matches("ruff", &["ruff".into(), "server".into()]));
        assert!(!router.command_matches("ruff", &["ruff".into(), "--server".into()]));
    }

    #[tokio::test]
    async fn reads_content_length_frames_and_multibyte_json() {
        let first = r#"{"jsonrpc":"2.0","method":"window/logMessage","params":{"message":"café"}}"#;
        let second = r#"{"jsonrpc":"2.0","id":1,"result":null}"#;
        let framed = format!(
            "Content-Length: {}\r\n\r\n{}Content-Length: {}\r\n\r\n{}",
            first.len(),
            first,
            second.len(),
            second
        );
        let mut reader = BufReader::new(framed.as_bytes());
        assert_eq!(
            read_lsp_message(&mut reader).await.unwrap().as_deref(),
            Some(first)
        );
        assert_eq!(
            read_lsp_message(&mut reader).await.unwrap().as_deref(),
            Some(second)
        );
        assert!(read_lsp_message(&mut reader).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn router_restarts_lsp_session_after_all_clients_detach() {
        let router = LspRouter::with_servers(
            Duration::from_secs(2),
            HashMap::from([(
                "test".to_owned(),
                ServerCommand {
                    program: "cat".to_owned(),
                    args: Vec::new(),
                },
            )]),
        );
        let attachment = router.attach("test").await.unwrap();
        assert_ne!(attachment.pid, 0);
        let mut receiver = attachment.receiver;
        let request = r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"#;
        router.send("test", request.to_owned()).await.unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(1), receiver.recv())
                .await
                .unwrap()
                .unwrap(),
            request
        );
        router.detach("test", attachment.generation).await;
        assert!(router.has_session("test").await);

        let reattached = router.attach("test").await.unwrap();
        assert_ne!(reattached.pid, attachment.pid);
        assert!(reattached.history.is_empty());
        router.stop("test", reattached.generation).await;
        assert!(!router.has_session("test").await);
    }

    #[tokio::test]
    async fn rejects_oversized_and_duplicate_headers() {
        let mut too_large =
            format!("Content-Length: {}\r\n\r\n", MAX_MESSAGE_SIZE + 1).into_bytes();
        assert_eq!(
            read_lsp_message(&mut BufReader::new(too_large.as_slice()))
                .await
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidData
        );
        too_large.clear();
        let duplicate = b"Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}";
        assert_eq!(
            read_lsp_message(&mut BufReader::new(&duplicate[..]))
                .await
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidData
        );
    }

    #[tokio::test]
    async fn writer_counts_utf8_bytes_not_characters() {
        let (mut writer, mut reader) = tokio::io::duplex(1024);
        let message = r#"{"jsonrpc":"2.0","method":"x","params":{"name":"café"}}"#;
        write_lsp_message(&mut writer, message).await.unwrap();
        drop(writer);
        let mut framed = Vec::new();
        reader.read_to_end(&mut framed).await.unwrap();
        let framed = String::from_utf8(framed).unwrap();
        let (header, body) = framed.split_once("\r\n\r\n").unwrap();
        assert!(header.ends_with(&message.len().to_string()));
        assert_eq!(body, message);
    }
}
