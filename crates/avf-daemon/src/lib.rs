#[cfg(feature = "native-protocol")]
mod git;
#[cfg(feature = "native-protocol")]
mod lsp;
mod ports;
mod probe;
pub mod tmpdir;
mod pairing;
mod tls;
mod pty;
#[cfg(feature = "native-protocol")]
mod rpc_stdio;
#[cfg(feature = "native-protocol")]
mod search;
#[cfg(feature = "native-protocol")]
#[allow(dead_code)]
mod tools;
mod vfs;

use avf_protocol::{
    decode, encode, FsEventKind, MultiplexedPacket, PtyPayload, RejectReason, SystemMessage,
    VfsErrorCode, VfsMessage, FEATURE_PROBE_COMMANDS, MAX_CHUNK_SIZE, MAX_FRAME_SIZE,
    PROTOCOL_VERSION,
};
#[cfg(feature = "native-protocol")]
use avf_protocol::{GitMessage, RpcKind, RpcMessage, SearchMessage};
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    http::{header::ORIGIN, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use futures_util::{SinkExt, StreamExt};
#[cfg(feature = "native-protocol")]
use lsp::LspRouter;
use notify::{
    event::{ModifyKind, RenameMode},
    Event, EventKind, RecursiveMode, Watcher,
};
use ports::PortScanner;
use pty::{PtyEvent, PtyManager};
use serde::Serialize;
#[cfg(feature = "native-protocol")]
use std::hash::{Hash, Hasher};
use std::{
    collections::{HashMap, HashSet},
    env,
    error::Error,
    io,
    net::SocketAddr,
    path::{Path, PathBuf},
    process::Stdio,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};
use subtle::ConstantTimeEq;
use tokio::{
    net::TcpListener,
    sync::{mpsc, oneshot, Mutex},
    time::{interval, timeout},
};
use tracing::{debug, error, info, warn};
use vfs::{Vfs, VfsFailure};

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const OUTBOUND_CAPACITY: usize = 128;
const WATCH_EVENT_CAPACITY: usize = 128;
const WATCH_DEBOUNCE: Duration = Duration::from_millis(150);
const MAX_PENDING_WRITES: usize = 8;
#[cfg(feature = "native-protocol")]
const MAX_ACTIVE_SEARCHES: usize = 8;
#[cfg(feature = "native-protocol")]
const MAX_ACTIVE_GIT_OPERATIONS: usize = 8;

#[cfg(feature = "native-protocol")]
type GitPromptAnswers = Arc<Mutex<HashMap<(u64, u64), oneshot::Sender<String>>>>;

#[cfg(feature = "native-protocol")]
struct GitForwarder {
    task: tokio::task::JoinHandle<()>,
}

#[cfg(all(feature = "native-protocol", unix))]
pub fn git_askpass_helper() -> Result<(), String> {
    git::run_askpass_helper()
}

/// `avf-daemon pair [--minutes N] [--remote HOST[:PORT]]`: issues a one-time code. With
/// `--remote` (a TLS host on a LAN) it prints a connection string carrying the certificate
/// fingerprint for the app's "Pair with a computer".
pub fn print_pairing_code(args: &[String]) -> Result<(), String> {
    const USAGE: &str = "usage: avf-daemon pair [--minutes N] [--remote HOST[:PORT]]";
    let mut minutes = pairing::PAIRING_TTL.as_secs() / 60;
    let mut remote: Option<String> = None;
    let mut rest = args.iter();
    while let Some(flag) = rest.next() {
        let value = rest.next().ok_or(USAGE)?;
        match flag.as_str() {
            "--minutes" => {
                minutes = value
                    .parse::<u64>()
                    .ok()
                    .filter(|minutes| (1..=60).contains(minutes))
                    .ok_or("--minutes must be between 1 and 60")?
            }
            "--remote" => remote = Some(value.clone()),
            _ => return Err(USAGE.to_owned()),
        }
    }
    let path = pairing::pairing_path()?;
    let code = pairing::create(&path, Duration::from_secs(minutes * 60))?;
    println!();
    match remote {
        None => {
            println!("    Pairing code:  {code}");
            println!();
            println!("In AVF Studio, open Connect and choose \"Set up or pair\". The code works once,");
        }
        Some(host) => {
            let (host, port) = match host.rsplit_once(':').filter(|(name, _)| !name.contains(':') || name.ends_with(']')) {
                Some((name, port)) => (name.trim_matches(['[', ']']).to_owned(), port.parse::<u16>().map_err(|_| "invalid port in --remote")?),
                None => (host.trim_matches(['[', ']']).to_owned(), 19870),
            };
            let material = tls::load_or_create(&pairing::config_dir()?)?;
            println!("    {}", tls::connection_string(&host, port, &material.fingerprint, &code));
            println!();
            println!("In AVF Studio, open Connect, choose \"Set up or pair\" and paste this under");
            println!("\"Pair with a computer\". The code in it works once,");
        }
    }
    println!("for {minutes} minutes, and allows {} wrong attempts.", pairing::MAX_ATTEMPTS);
    Ok(())
}

#[derive(Clone)]
pub struct AppConfig {
    pub bind_addr: SocketAddr,
    pub token: String,
    pub allowed_origins: HashSet<String>,
    pub workspace_roots: Vec<PathBuf>,
    pub lsp_hibernation_secs: u64,
    /// Where `avf-daemon pair` leaves the one-time pairing code; None disables pairing.
    pub pairing_file: Option<PathBuf>,
    /// Serve TLS with the self-signed certificate in the config directory (`AVF_TLS=1`).
    /// Required for any non-loopback bind address.
    pub tls: bool,
}

impl AppConfig {
    pub fn load() -> Result<Self, Box<dyn Error>> {
        let token = match env::var("AVF_TOKEN") {
            Ok(value) => value,
            Err(env::VarError::NotPresent) => read_token_file()?,
            Err(error) => return Err(Box::new(error)),
        };
        if !is_valid_token(&token) {
            return Err("AVF_TOKEN must be exactly 64 hexadecimal characters (256 bits)".into());
        }

        let roots = env::var_os("AVF_WORKSPACE_ROOTS")
            .map(|value| env::split_paths(&value).collect::<Vec<_>>())
            .filter(|roots| !roots.is_empty())
            .ok_or("AVF_WORKSPACE_ROOTS must contain one or more project directories")?;
        for root in &roots {
            if !root.is_absolute() || !root.is_dir() {
                return Err(format!(
                    "workspace root must be an absolute directory: {}",
                    root.display()
                )
                .into());
            }
        }

        let bind_addr: SocketAddr = env::var("AVF_BIND_ADDR")
            .unwrap_or_else(|_| "127.0.0.1:19860".to_owned())
            .parse()?;
        let tls = env::var("AVF_TLS").is_ok_and(|value| value == "1" || value.eq_ignore_ascii_case("true"));
        if !bind_addr.ip().is_loopback() && !tls {
            return Err("AVF_BIND_ADDR may only use a non-loopback address with AVF_TLS=1".into());
        }
        let allowed_origins = match env::var("AVF_ALLOWED_ORIGINS") {
            Ok(value) => value
                .split(',')
                .map(str::trim)
                .filter(|origin| !origin.is_empty())
                .map(str::to_owned)
                .collect(),
            Err(env::VarError::NotPresent) => default_origins(),
            Err(error) => return Err(Box::new(error)),
        };
        if allowed_origins.is_empty() {
            return Err("AVF_ALLOWED_ORIGINS cannot be empty".into());
        }
        // A reattach after every client detached starts a fresh server, so an idle
        // server only costs guest memory; stop it shortly after the last client leaves.
        let lsp_hibernation_secs = match env::var("AVF_LSP_HIBERNATION_SECS") {
            Ok(value) => value.parse::<u64>()?,
            Err(env::VarError::NotPresent) => 60,
            Err(error) => return Err(Box::new(error)),
        };

        Ok(Self {
            bind_addr,
            token,
            allowed_origins,
            workspace_roots: roots,
            lsp_hibernation_secs,
            pairing_file: pairing::pairing_path().ok(),
            tls,
        })
    }
}

fn read_token_file() -> Result<String, Box<dyn Error>> {
    let home = env::var_os("HOME").ok_or("HOME is not set and AVF_TOKEN is missing")?;
    let path = PathBuf::from(home)
        .join(".config")
        .join("avf-studio")
        .join("token");
    let metadata = std::fs::symlink_metadata(&path)?;
    if !metadata.file_type().is_file() {
        return Err(format!("token path must be a regular file: {}", path.display()).into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o077 != 0 {
            return Err(format!(
                "token file permissions must be 0600 or stricter: {}",
                path.display()
            )
            .into());
        }
    }
    let token = std::fs::read_to_string(&path)?;
    Ok(token.trim().to_owned())
}

fn is_valid_token(token: &str) -> bool {
    token.len() == 64 && token.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn default_origins() -> HashSet<String> {
    let mut origins = HashSet::from([
        "http://tauri.localhost".to_owned(),
        "tauri://localhost".to_owned(),
        "avf-native-android://localhost".to_owned(),
    ]);
    if cfg!(debug_assertions) {
        origins.insert("http://localhost:5173".to_owned());
    }
    origins
}

#[derive(Clone)]
struct AppState {
    config: Arc<AppConfig>,
    vfs: Vfs,
    pty: PtyManager,
    #[cfg(feature = "native-protocol")]
    lsp: LspRouter,
    #[cfg(feature = "native-protocol")]
    installed_tools: Arc<HashMap<String, tools::ToolManifest>>,
    #[cfg(feature = "native-protocol")]
    tool_registry_home: Option<PathBuf>,
    ports: PortScanner,
    telemetry: Arc<Telemetry>,
}

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
    protocol_version: u32,
}

pub async fn serve(config: AppConfig) -> io::Result<()> {
    if !config.bind_addr.ip().is_loopback() && !config.tls {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "AVF Studio daemon may only bind beyond loopback with TLS",
        ));
    }
    if !is_valid_token(&config.token) || config.allowed_origins.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "daemon configuration has an invalid token or empty Origin allowlist",
        ));
    }
    if config.workspace_roots.is_empty()
        || config
            .workspace_roots
            .iter()
            .any(|root| !root.is_absolute() || !root.is_dir())
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "daemon configuration must contain absolute workspace directories",
        ));
    }
    if config.tls {
        let material = pairing::config_dir()
            .and_then(|dir| tls::load_or_create(&dir))
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        let _ = rustls::crypto::ring::default_provider().install_default();
        let tls = axum_server::tls_rustls::RustlsConfig::from_pem(material.cert_pem.into_bytes(), material.key_pem.into_bytes()).await?;
        info!(fingerprint = %material.fingerprint, "serving TLS");
        let bind_addr = config.bind_addr;
        let app = app(config)?;
        return axum_server::bind_rustls(bind_addr, tls).serve(app.into_make_service()).await;
    }
    let listener = TcpListener::bind(config.bind_addr).await?;
    let app = app(config)?;
    axum::serve(listener, app).await
}

fn app(config: AppConfig) -> io::Result<Router> {
    let vfs = Vfs::new(&config.workspace_roots)?;
    let ports = PortScanner::default();
    ports.start();
    #[cfg(feature = "native-protocol")]
    let tool_registry_home = Some(
        tools::default_home().map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?,
    );
    let state = AppState {
        #[cfg(feature = "native-protocol")]
        lsp: LspRouter::new(Duration::from_secs(config.lsp_hibernation_secs)),
        #[cfg(feature = "native-protocol")]
        installed_tools: Arc::new(HashMap::new()),
        #[cfg(feature = "native-protocol")]
        tool_registry_home,
        config: Arc::new(config),
        vfs,
        pty: PtyManager::default(),
        ports,
        telemetry: Arc::new(Telemetry::default()),
    };
    Ok(Router::new()
        .route("/health", get(health))
        .route("/ws", get(websocket))
        .with_state(state))
}

async fn health() -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        protocol_version: PROTOCOL_VERSION,
    })
}

async fn websocket(
    State(state): State<AppState>,
    headers: HeaderMap,
    upgrade: WebSocketUpgrade,
) -> Response {
    let origin = headers
        .get(ORIGIN)
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default();
    if !state.config.allowed_origins.contains(origin) {
        return StatusCode::FORBIDDEN.into_response();
    }
    upgrade
        .max_message_size(MAX_FRAME_SIZE)
        .max_frame_size(MAX_FRAME_SIZE)
        .on_upgrade(move |socket| handle_socket(socket, state))
}

/// Trades a one-time pairing code for the session token, then closes the connection.
async fn pair_client(socket: &mut WebSocket, state: &AppState, protocol_version: u32, code: &str) {
    if protocol_version != PROTOCOL_VERSION {
        reject(
            socket,
            RejectReason::VersionMismatch {
                daemon_version: PROTOCOL_VERSION,
            },
            "protocol version mismatch",
        )
        .await;
        return;
    }
    let Some(path) = state.config.pairing_file.clone() else {
        reject(socket, RejectReason::PairingUnavailable, "pairing is disabled").await;
        return;
    };
    let code = code.to_owned();
    let outcome = tokio::task::spawn_blocking(move || pairing::redeem(&path, &code))
        .await
        .unwrap_or_else(|error| Err(pairing::PairingError::Io(error.to_string())));
    match outcome {
        Ok(()) => {
            info!("a client paired with a one-time code");
            let _ = send_packet(
                socket,
                &MultiplexedPacket::System(SystemMessage::Paired {
                    token: state.config.token.clone(),
                }),
            )
            .await;
            let _ = socket.send(Message::Close(None)).await;
        }
        Err(pairing::PairingError::WrongCode { attempts_left }) => {
            reject(socket, RejectReason::PairingFailed { attempts_left }, "wrong pairing code").await;
        }
        Err(pairing::PairingError::Unavailable) => {
            reject(socket, RejectReason::PairingUnavailable, "no pairing code is active").await;
        }
        Err(pairing::PairingError::Io(error)) => {
            error!(%error, "pairing record could not be read");
            reject(socket, RejectReason::PairingUnavailable, "pairing is unavailable").await;
        }
    }
}

async fn handle_socket(mut socket: WebSocket, state: AppState) {
    let first_message = match timeout(HANDSHAKE_TIMEOUT, socket.recv()).await {
        Err(_) => {
            reject(
                &mut socket,
                RejectReason::HandshakeTimeout,
                "authentication handshake timed out",
            )
            .await;
            return;
        }
        Ok(None) => return,
        Ok(Some(Err(error))) => {
            debug!(%error, "websocket ended during authentication handshake");
            return;
        }
        Ok(Some(Ok(message))) => message,
    };
    let Message::Binary(bytes) = first_message else {
        send_protocol_error(
            &mut socket,
            "the first WebSocket frame must be binary Hello",
        )
        .await;
        return;
    };
    let packet = match decode(&bytes) {
        Ok(packet) => packet,
        Err(error) => {
            send_protocol_error(&mut socket, &format!("invalid MessagePack Hello: {error}")).await;
            return;
        }
    };
    let (protocol_version, auth_token) = match packet {
        MultiplexedPacket::System(SystemMessage::Hello {
            protocol_version,
            auth_token,
            ..
        }) => (protocol_version, auth_token),
        MultiplexedPacket::System(SystemMessage::Pair {
            protocol_version, code, ..
        }) => {
            pair_client(&mut socket, &state, protocol_version, &code).await;
            return;
        }
        _ => {
            send_protocol_error(&mut socket, "Hello must be the first packet").await;
            return;
        }
    };

    if !is_valid_token(&auth_token)
        || state
            .config
            .token
            .as_bytes()
            .ct_eq(auth_token.as_bytes())
            .unwrap_u8()
            != 1
    {
        reject(&mut socket, RejectReason::InvalidToken, "invalid token").await;
        return;
    }
    if protocol_version != PROTOCOL_VERSION {
        reject(
            &mut socket,
            RejectReason::VersionMismatch {
                daemon_version: PROTOCOL_VERSION,
            },
            "protocol version mismatch",
        )
        .await;
        return;
    }
    if send_packet(
        &mut socket,
        &MultiplexedPacket::System(SystemMessage::Welcome {
            protocol_version: PROTOCOL_VERSION,
            daemon_version: env!("CARGO_PKG_VERSION").to_owned(),
            pty_sessions: state.pty.sessions().await,
            features: vec![FEATURE_PROBE_COMMANDS.to_owned()],
        }),
    )
    .await
    .is_err()
    {
        return;
    }

    let (mut sender, mut receiver) = socket.split();
    let (outbound, mut outgoing) = mpsc::channel::<OutboundFrame>(OUTBOUND_CAPACITY);
    let mut writer = tokio::spawn(async move {
        while let Some(frame) = outgoing.recv().await {
            let closing = matches!(&frame, OutboundFrame::Control(Message::Close(_)));
            let message = match frame {
                OutboundFrame::Packet(packet) => {
                    let bytes = encode(&packet);
                    if bytes.len() > MAX_FRAME_SIZE {
                        error!("outbound packet exceeded the 32 MiB protocol limit");
                        break;
                    }
                    Message::Binary(bytes.into())
                }
                OutboundFrame::Control(message) => message,
            };
            if sender.send(message).await.is_err() {
                break;
            }
            if closing {
                break;
            }
        }
    });
    #[cfg(feature = "native-protocol")]
    let (search_messages_tx, mut search_messages_rx) = mpsc::channel::<SearchMessage>(128);
    #[cfg(feature = "native-protocol")]
    let search_stream_outbound = outbound.clone();
    #[cfg(feature = "native-protocol")]
    let mut search_stream_task = tokio::spawn(async move {
        while let Some(message) = search_messages_rx.recv().await {
            if send_outbound(&search_stream_outbound, MultiplexedPacket::Search(message))
                .await
                .is_err()
            {
                break;
            }
        }
    });
    let mut port_events = state.ports.subscribe();
    #[cfg(feature = "native-protocol")]
    let mut rpc_forwarders = HashMap::new();
    #[cfg(feature = "native-protocol")]
    let mut search_forwarders = HashMap::<u64, tokio::task::JoinHandle<()>>::new();
    #[cfg(feature = "native-protocol")]
    let mut git_forwarders = HashMap::<u64, GitForwarder>::new();
    #[cfg(feature = "native-protocol")]
    let git_prompt_answers = Arc::new(Mutex::new(HashMap::new()));
    let mut watches = HashMap::<String, WatchHandle>::new();
    let mut pty_forwarders = HashMap::new();
    let mut pending_writes = HashMap::<String, vfs::WriteStage>::new();
    for event in state.ports.current().await {
        if send_outbound(&outbound, MultiplexedPacket::System(event))
            .await
            .is_err()
        {
            break;
        }
    }
    loop {
        tokio::select! {
            result = &mut writer => {
                if let Err(error) = result {
                    debug!(%error, "websocket writer task failed");
                }
                break;
            }
            incoming = receiver.next() => {
                let Some(incoming) = incoming else { break };
                let message = match incoming {
                    Ok(message) => message,
                    Err(error) => {
                        debug!(%error, "websocket read failed");
                        break;
                    }
                };
                match message {
                    Message::Binary(bytes) => {
                        let packet = match decode(&bytes) {
                            Ok(packet) => packet,
                            Err(error) => {
                                let _ = send_protocol_error_outbound(
                                    &outbound,
                                    &format!("invalid MessagePack packet: {error}"),
                                ).await;
                                break;
                            }
                        };
                        if process_packet(
                            packet,
                            &state,
                            &outbound,
                            &mut watches,
                            &mut pty_forwarders,
                            &mut pending_writes,
                            #[cfg(feature = "native-protocol")]
                            &mut rpc_forwarders,
                            #[cfg(feature = "native-protocol")]
                            &mut search_forwarders,
                            #[cfg(feature = "native-protocol")]
                            &search_messages_tx,
                            #[cfg(feature = "native-protocol")]
                            &mut git_forwarders,
                            #[cfg(feature = "native-protocol")]
                            &git_prompt_answers,
                        )
                        .await
                        .is_err()
                        {
                            break;
                        }
                    }
                    Message::Text(_) => {
                        let _ = send_protocol_error_outbound(
                            &outbound,
                            "text WebSocket frames are not supported",
                        ).await;
                        break;
                    }
                    Message::Ping(payload) => {
                        if outbound.send(OutboundFrame::Control(Message::Pong(payload))).await.is_err() {
                            break;
                        }
                    }
                    Message::Pong(_) => {}
                    Message::Close(_) => break,
                }
            }
            event = port_events.recv() => {
                match event {
                    Ok(event) => {
                        if send_outbound(&outbound, MultiplexedPacket::System(event)).await.is_err() {
                            break;
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                        warn!(skipped, "websocket missed guest port events");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        }
    }
    drop(watches);
    for (session_id, (subscriber_id, task)) in pty_forwarders.drain() {
        task.abort();
        state.pty.detach(session_id, subscriber_id).await;
    }
    #[cfg(feature = "native-protocol")]
    for (_, forwarder) in rpc_forwarders.drain() {
        forwarder.task.abort();
        if let RpcRoute::Lsp {
            session_key,
            generation,
            ..
        } = forwarder.route
        {
            state.lsp.detach(&session_key, generation).await;
        }
    }
    #[cfg(feature = "native-protocol")]
    for (_, task) in search_forwarders.drain() {
        task.abort();
    }
    #[cfg(feature = "native-protocol")]
    for (_, forwarder) in git_forwarders.drain() {
        forwarder.task.abort();
    }
    #[cfg(feature = "native-protocol")]
    git_prompt_answers.lock().await.clear();
    #[cfg(feature = "native-protocol")]
    {
        drop(search_messages_tx);
        if timeout(Duration::from_secs(1), &mut search_stream_task)
            .await
            .is_err()
        {
            search_stream_task.abort();
        }
    }
    drop(outbound);
    if let Err(error) = timeout(Duration::from_secs(1), &mut writer).await {
        warn!(%error, "websocket writer did not stop promptly");
    }
}

#[derive(Debug)]
enum OutboundFrame {
    Packet(MultiplexedPacket),
    Control(Message),
}

#[allow(clippy::too_many_arguments)]
async fn process_packet(
    packet: MultiplexedPacket,
    state: &AppState,
    outbound: &mpsc::Sender<OutboundFrame>,
    watches: &mut HashMap<String, WatchHandle>,
    pty_forwarders: &mut HashMap<uuid::Uuid, (u64, tokio::task::JoinHandle<()>)>,
    pending_writes: &mut HashMap<String, vfs::WriteStage>,
    #[cfg(feature = "native-protocol")] rpc_forwarders: &mut HashMap<uuid::Uuid, RpcForwarder>,
    #[cfg(feature = "native-protocol")] search_forwarders: &mut HashMap<
        u64,
        tokio::task::JoinHandle<()>,
    >,
    #[cfg(feature = "native-protocol")] search_messages_tx: &mpsc::Sender<SearchMessage>,
    #[cfg(feature = "native-protocol")] git_forwarders: &mut HashMap<u64, GitForwarder>,
    #[cfg(feature = "native-protocol")] git_prompt_answers: &GitPromptAnswers,
) -> Result<(), ()> {
    match packet {
        MultiplexedPacket::System(SystemMessage::Ping { timestamp }) => {
            match state.telemetry.sample().await {
                Ok(sample) => {
                    send_outbound(
                        outbound,
                        MultiplexedPacket::System(SystemMessage::Pong {
                            timestamp,
                            cpu_usage: sample.cpu_usage,
                            mem_used_mb: sample.mem_used_mb,
                            mem_total_mb: sample.mem_total_mb,
                            mem_free_mb: sample.mem_free_mb,
                            mem_cached_mb: sample.mem_cached_mb,
                            mem_balloon_mb: sample.mem_balloon_mb,
                            disk_problem: sample.disk_problem,
                            disk_used_mb: sample.disk_used_mb,
                            disk_total_mb: sample.disk_total_mb,
                        }),
                    )
                    .await
                }
                Err(error) => {
                    error!(%error, "system telemetry sampling failed");
                    let _ =
                        send_protocol_error_outbound(outbound, "system telemetry is unavailable")
                            .await;
                    Err(())
                }
            }
        }
        MultiplexedPacket::System(SystemMessage::ProbeCommands { request_id, names }) => {
            let home = env::var_os("HOME").map(PathBuf::from);
            let path = env::var_os("PATH");
            let commands = tokio::task::spawn_blocking(move || {
                probe::probe_commands(home.as_deref(), path.as_deref(), &names)
            })
            .await
            .unwrap_or_default();
            send_outbound(
                outbound,
                MultiplexedPacket::System(SystemMessage::CommandsProbed {
                    request_id,
                    commands,
                }),
            )
            .await
        }
        MultiplexedPacket::Vfs(VfsMessage::ListDirReq { req_id, path }) => {
            let response = match state.vfs.list_dir(path).await {
                Ok(entries) => VfsMessage::ListDirRes { req_id, entries },
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::StatReq { req_id, path }) => {
            let response = match state.vfs.stat(path).await {
                Ok(entry) => VfsMessage::StatRes { req_id, entry },
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::ReadFileReq {
            req_id,
            path,
            offset,
            len,
        }) => {
            let response = match state.vfs.read_file_range(path, offset, len).await {
                Ok((content, eof, modified_ts, size)) => VfsMessage::ReadFileRes {
                    req_id,
                    content,
                    eof,
                    modified_ts,
                    size,
                },
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::WriteFileReq {
            req_id,
            path,
            content,
            expected_modified_ts,
            truncate,
            offset,
        }) => {
            let response = if content.len() > MAX_CHUNK_SIZE {
                VfsMessage::VfsError {
                    req_id,
                    code: VfsErrorCode::TooLarge,
                    message: "write chunk exceeds the 4 MiB limit".into(),
                }
            } else if truncate {
                if offset != 0 {
                    VfsMessage::VfsError {
                        req_id,
                        code: VfsErrorCode::InvalidPath,
                        message: "a new chunked write must start at offset zero".into(),
                    }
                } else if !pending_writes.contains_key(&path)
                    && pending_writes.len() >= MAX_PENDING_WRITES
                {
                    VfsMessage::VfsError {
                        req_id,
                        code: VfsErrorCode::TooLarge,
                        message: format!(
                            "connection has reached the {MAX_PENDING_WRITES} pending-write limit"
                        ),
                    }
                } else {
                    match state
                        .vfs
                        .begin_write_stage(path.clone(), expected_modified_ts)
                        .await
                    {
                        Ok(mut stage) => {
                            match state
                                .vfs
                                .append_write_stage(&mut stage, offset, content)
                                .await
                            {
                                Ok(()) => {
                                    pending_writes.insert(path, stage);
                                    VfsMessage::Ack {
                                        req_id,
                                        modified_ts: None,
                                        trash_id: None,
                                    }
                                }
                                Err(error) => vfs_error(req_id, error),
                            }
                        }
                        Err(error) => vfs_error(req_id, error),
                    }
                }
            } else {
                if expected_modified_ts.is_some() {
                    VfsMessage::VfsError {
                        req_id,
                        code: VfsErrorCode::Conflict,
                        message: "expected_modified_ts is only valid on the first write chunk"
                            .into(),
                    }
                } else {
                    let result = match pending_writes.get_mut(&path) {
                        Some(stage) => state.vfs.append_write_stage(stage, offset, content).await,
                        None => Err(VfsFailure {
                            code: VfsErrorCode::InvalidPath,
                            message: "chunked write must begin with truncate=true at offset zero"
                                .into(),
                        }),
                    };
                    match result {
                        Ok(()) => VfsMessage::Ack {
                            req_id,
                            modified_ts: None,
                            trash_id: None,
                        },
                        Err(error) => {
                            if error.code != VfsErrorCode::Conflict {
                                pending_writes.remove(&path);
                            }
                            vfs_error(req_id, error)
                        }
                    }
                }
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::FlushReq { req_id, path }) => {
            let response = match pending_writes.remove(&path) {
                Some(stage) => match state.vfs.flush_write_stage(stage).await {
                    Ok(modified_ts) => VfsMessage::Ack {
                        req_id,
                        modified_ts: Some(modified_ts),
                        trash_id: None,
                    },
                    Err(error) => vfs_error(req_id, error),
                },
                None => VfsMessage::VfsError {
                    req_id,
                    code: VfsErrorCode::NotFound,
                    message: "no pending chunked write exists for this path".into(),
                },
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::CreateReq { req_id, path, kind }) => {
            let response = match state.vfs.create(path, kind).await {
                Ok(()) => VfsMessage::Ack {
                    req_id,
                    modified_ts: None,
                    trash_id: None,
                },
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::DeleteReq {
            req_id,
            path,
            recursive,
            to_trash,
        }) => {
            let response = match state.vfs.delete(path, recursive, to_trash).await {
                Ok(trash_id) => VfsMessage::Ack {
                    req_id,
                    modified_ts: None,
                    trash_id,
                },
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::RenameReq {
            req_id,
            from,
            to,
            overwrite,
        }) => {
            let response = match state.vfs.rename(from, to, overwrite).await {
                Ok(()) => VfsMessage::Ack {
                    req_id,
                    modified_ts: None,
                    trash_id: None,
                },
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::CopyReq {
            req_id,
            from,
            to,
            overwrite,
        }) => {
            let response = match state.vfs.copy(from, to, overwrite).await {
                Ok(()) => VfsMessage::Ack {
                    req_id,
                    modified_ts: None,
                    trash_id: None,
                },
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::RootsReq { req_id }) => {
            send_outbound(
                outbound,
                MultiplexedPacket::Vfs(VfsMessage::RootsRes {
                    req_id,
                    roots: state.vfs.roots().await,
                }),
            )
            .await
        }
        MultiplexedPacket::Vfs(VfsMessage::RestoreReq { req_id, trash_id }) => {
            let response = match state.vfs.restore(trash_id).await {
                Ok(()) => VfsMessage::Ack {
                    req_id,
                    modified_ts: None,
                    trash_id: None,
                },
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::WatchReq {
            req_id,
            path,
            recursive,
        }) => match start_watch(&state.vfs, &path, recursive, outbound.clone()).await {
            Ok((key, watch)) => {
                watches.insert(key, watch);
                send_outbound(
                    outbound,
                    MultiplexedPacket::Vfs(VfsMessage::WatchRes { req_id }),
                )
                .await
            }
            Err(error) => {
                send_outbound(outbound, MultiplexedPacket::Vfs(vfs_error(req_id, error))).await
            }
        },
        MultiplexedPacket::Vfs(VfsMessage::UnwatchReq { req_id, path }) => {
            let response = match state.vfs.resolve(&path) {
                Ok(resolved) => {
                    let key = resolved.absolute.to_string_lossy().into_owned();
                    watches.remove(&key);
                    VfsMessage::WatchRes { req_id }
                }
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Pty { session_id, data } => match data {
            PtyPayload::Spawn {
                shell,
                cwd,
                cols,
                rows,
            } => match state.pty.spawn(session_id, shell, cwd, cols, rows).await {
                Ok((_info, pid)) => {
                    send_pty_payload(outbound, session_id, PtyPayload::Spawned { pid }).await?;
                    attach_pty(session_id, state, outbound, pty_forwarders).await
                }
                Err(message) => {
                    send_pty_payload(outbound, session_id, PtyPayload::Error { message }).await
                }
            },
            PtyPayload::Input(data) => match state.pty.input(session_id, data).await {
                Ok(()) => Ok(()),
                Err(message) => {
                    send_pty_payload(outbound, session_id, PtyPayload::Error { message }).await
                }
            },
            PtyPayload::Resize { cols, rows } => {
                match state.pty.resize(session_id, cols, rows).await {
                    Ok(()) => Ok(()),
                    Err(message) => {
                        send_pty_payload(outbound, session_id, PtyPayload::Error { message }).await
                    }
                }
            }
            PtyPayload::Reattach => attach_pty(session_id, state, outbound, pty_forwarders).await,
            PtyPayload::Kill => match state.pty.kill(session_id).await {
                Ok(()) => Ok(()),
                Err(message) => {
                    send_pty_payload(outbound, session_id, PtyPayload::Error { message }).await
                }
            },
            PtyPayload::Spawned { .. }
            | PtyPayload::Output(_)
            | PtyPayload::Exit { .. }
            | PtyPayload::Error { .. } => {
                send_pty_payload(
                    outbound,
                    session_id,
                    PtyPayload::Error {
                        message: "client sent a daemon-only PTY message".to_owned(),
                    },
                )
                .await
            }
        },
        #[cfg(feature = "native-protocol")]
        MultiplexedPacket::Rpc(message) => {
            process_rpc_message(message, state, outbound, rpc_forwarders).await
        }
        #[cfg(feature = "native-protocol")]
        MultiplexedPacket::Search(message) => {
            process_search_message(
                message,
                state,
                outbound,
                search_forwarders,
                search_messages_tx,
            )
            .await
        }
        #[cfg(feature = "native-protocol")]
        MultiplexedPacket::Git(message) => {
            process_git_message(message, state, outbound, git_forwarders, git_prompt_answers).await
        }
        _ => {
            warn!("client sent a packet type not supported by the daemon");
            let _ = send_protocol_error_outbound(outbound, "packet type is not supported").await;
            Err(())
        }
    }
}

#[cfg(feature = "native-protocol")]
struct RpcForwarder {
    route: RpcRoute,
    task: tokio::task::JoinHandle<()>,
}

#[cfg(feature = "native-protocol")]
enum RpcRoute {
    Lsp {
        session_key: String,
        generation: u64,
    },
    Direct {
        kind: RpcKind,
        input: mpsc::Sender<String>,
    },
}

#[cfg(feature = "native-protocol")]
async fn process_rpc_message(
    message: RpcMessage,
    state: &AppState,
    outbound: &mpsc::Sender<OutboundFrame>,
    forwarders: &mut HashMap<uuid::Uuid, RpcForwarder>,
) -> Result<(), ()> {
    match message {
        RpcMessage::Start {
            bridge_id,
            tool,
            kind,
            args,
            cwd,
            env,
        } => {
            if forwarders.contains_key(&bridge_id) {
                return send_rpc(
                    outbound,
                    RpcMessage::Error {
                        bridge_id,
                        message: "bridge id is already active on this connection".into(),
                    },
                )
                .await;
            }
            let unsupported = (kind == RpcKind::Custom)
                .then(|| "custom RPC bridges are not implemented".to_owned());
            if let Some(message) = unsupported {
                return send_rpc(outbound, RpcMessage::Error { bridge_id, message }).await;
            }
            let installed_tools = if let Some(home) = &state.tool_registry_home {
                let home = home.clone();
                match tokio::task::spawn_blocking(move || tools::installed_tool_map(&home)).await {
                    Ok(Ok(tools)) => tools,
                    Ok(Err(message)) => {
                        return send_rpc(outbound, RpcMessage::Error { bridge_id, message }).await;
                    }
                    Err(error) => {
                        return send_rpc(
                            outbound,
                            RpcMessage::Error {
                                bridge_id,
                                message: format!("could not load guest tool registry: {error}"),
                            },
                        )
                        .await;
                    }
                }
            } else {
                state.installed_tools.as_ref().clone()
            };
            let Some(manifest) = installed_tools.get(&tool) else {
                return send_rpc(
                    outbound,
                    RpcMessage::Error {
                        bridge_id,
                        message: format!("tool '{tool}' is not installed in the guest registry"),
                    },
                )
                .await;
            };
            let (cwd, env) =
                match validate_rpc_launch_options(&state.vfs, manifest, &args, cwd, env) {
                    Ok(options) => options,
                    Err(message) => {
                        return send_rpc(outbound, RpcMessage::Error { bridge_id, message }).await;
                    }
                };
            if kind == RpcKind::Lsp {
                if manifest.kind != "lsp" || !state.lsp.command_matches(&tool, &manifest.command) {
                    return send_rpc(
                        outbound,
                        RpcMessage::Error {
                            bridge_id,
                            message: format!(
                                "tool '{tool}' is not registered as a supported LSP server"
                            ),
                        },
                    )
                    .await;
                }
                let session_key = lsp_session_key(&tool, &args, &cwd, &env);
                let attachment = match state
                    .lsp
                    .attach_with(&tool, &session_key, args, cwd, env)
                    .await
                {
                    Ok(attachment) => attachment,
                    Err(message) => {
                        return send_rpc(outbound, RpcMessage::Error { bridge_id, message }).await;
                    }
                };
                let generation = attachment.generation;
                if send_rpc(
                    outbound,
                    RpcMessage::Started {
                        bridge_id,
                        pid: attachment.pid,
                    },
                )
                .await
                .is_err()
                {
                    state.lsp.detach(&session_key, generation).await;
                    return Err(());
                }
                for body in attachment.history {
                    if send_rpc(outbound, RpcMessage::Message { bridge_id, body })
                        .await
                        .is_err()
                    {
                        state.lsp.detach(&session_key, generation).await;
                        return Err(());
                    }
                }

                let mut receiver = attachment.receiver;
                let task_outbound = outbound.clone();
                let task = tokio::spawn(async move {
                    loop {
                        match receiver.recv().await {
                            Ok(body) => {
                                if send_rpc(&task_outbound, RpcMessage::Message { bridge_id, body })
                                    .await
                                    .is_err()
                                {
                                    break;
                                }
                            }
                            Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                                warn!(%bridge_id, skipped, "websocket missed RPC bridge messages");
                            }
                            Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                                let _ = send_rpc(
                                    &task_outbound,
                                    RpcMessage::Exited {
                                        bridge_id,
                                        code: None,
                                    },
                                )
                                .await;
                                break;
                            }
                        }
                    }
                });
                forwarders.insert(
                    bridge_id,
                    RpcForwarder {
                        route: RpcRoute::Lsp {
                            session_key,
                            generation,
                        },
                        task,
                    },
                );
                return Ok(());
            }

            let expected_manifest_kind = match kind {
                RpcKind::Dap => "dap",
                RpcKind::Acp => "acp-agent",
                RpcKind::Kernel => "kernel",
                RpcKind::Lsp | RpcKind::Custom => unreachable!("handled above"),
            };
            if manifest.kind != expected_manifest_kind {
                return send_rpc(
                    outbound,
                    RpcMessage::Error {
                        bridge_id,
                        message: format!("tool '{tool}' is not registered for {kind:?} bridges"),
                    },
                )
                .await;
            }
            let (pid, input, task, start_gate) = match start_direct_rpc_bridge(
                &manifest.command,
                &args,
                cwd,
                env,
                kind,
                bridge_id,
                outbound,
            )
            .await
            {
                Ok(bridge) => bridge,
                Err(message) => {
                    return send_rpc(outbound, RpcMessage::Error { bridge_id, message }).await;
                }
            };
            if send_rpc(outbound, RpcMessage::Started { bridge_id, pid })
                .await
                .is_err()
            {
                task.abort();
                return Err(());
            }
            let _ = start_gate.send(());
            forwarders.insert(
                bridge_id,
                RpcForwarder {
                    route: RpcRoute::Direct { kind, input },
                    task,
                },
            );
            Ok(())
        }
        RpcMessage::Message { bridge_id, body } => {
            let Some(forwarder) = forwarders.get(&bridge_id) else {
                return send_rpc(
                    outbound,
                    RpcMessage::Error {
                        bridge_id,
                        message: "RPC bridge is not active".into(),
                    },
                )
                .await;
            };
            let result = match &forwarder.route {
                RpcRoute::Lsp { session_key, .. } => state.lsp.send(session_key, body).await,
                RpcRoute::Direct { kind, input } => {
                    rpc_stdio::validate(*kind, &body).and_then(|()| {
                        input.try_send(body).map_err(|error| match error {
                            mpsc::error::TrySendError::Full(_) => {
                                "RPC bridge input queue is full".into()
                            }
                            mpsc::error::TrySendError::Closed(_) => "RPC bridge has exited".into(),
                        })
                    })
                }
            };
            match result {
                Ok(()) => Ok(()),
                Err(message) => send_rpc(outbound, RpcMessage::Error { bridge_id, message }).await,
            }
        }
        RpcMessage::Stop { bridge_id } => match forwarders.remove(&bridge_id) {
            Some(forwarder) => {
                forwarder.task.abort();
                if let RpcRoute::Lsp {
                    session_key,
                    generation,
                    ..
                } = forwarder.route
                {
                    state.lsp.stop(&session_key, generation).await;
                }
                send_rpc(
                    outbound,
                    RpcMessage::Exited {
                        bridge_id,
                        code: None,
                    },
                )
                .await
            }
            None => {
                send_rpc(
                    outbound,
                    RpcMessage::Error {
                        bridge_id,
                        message: "RPC bridge is not active".into(),
                    },
                )
                .await
            }
        },
        RpcMessage::Started { bridge_id, .. }
        | RpcMessage::Exited { bridge_id, .. }
        | RpcMessage::Error { bridge_id, .. } => {
            send_rpc(
                outbound,
                RpcMessage::Error {
                    bridge_id,
                    message: "client sent a daemon-only RPC message".into(),
                },
            )
            .await
        }
    }
}

#[cfg(feature = "native-protocol")]
async fn start_direct_rpc_bridge(
    command: &[String],
    extra_args: &[String],
    cwd: Option<PathBuf>,
    env: Vec<(String, String)>,
    kind: RpcKind,
    bridge_id: uuid::Uuid,
    outbound: &mpsc::Sender<OutboundFrame>,
) -> Result<
    (
        u32,
        mpsc::Sender<String>,
        tokio::task::JoinHandle<()>,
        oneshot::Sender<()>,
    ),
    String,
> {
    let Some((program, command_args)) = command.split_first() else {
        return Err("tool manifest has an empty command".into());
    };
    let mut process_command = tokio::process::Command::new(program);
    process_command
        .args(command_args)
        .args(extra_args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    if let Some(cwd) = cwd {
        process_command.current_dir(cwd);
    }
    let mut child = process_command
        .envs(env)
        .spawn()
        .map_err(|error| format!("could not start RPC tool {program}: {error}"))?;
    let pid = child
        .id()
        .ok_or_else(|| format!("RPC tool {program} has no process id"))?;
    let stdin = child
        .stdin
        .take()
        .ok_or_else(|| format!("RPC tool {program} has no stdin pipe"))?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| format!("RPC tool {program} has no stdout pipe"))?;
    let (input, mut input_rx) = mpsc::channel::<String>(32);
    let (start_tx, start_rx) = oneshot::channel();
    let task_outbound = outbound.clone();
    let task = tokio::spawn(async move {
        if start_rx.await.is_err() {
            return;
        }
        let writer_outbound = task_outbound.clone();
        let writer = tokio::spawn(async move {
            let mut stdin = stdin;
            while let Some(body) = input_rx.recv().await {
                if let Err(error) = rpc_stdio::write(kind, &mut stdin, &body).await {
                    let _ = send_rpc(
                        &writer_outbound,
                        RpcMessage::Error {
                            bridge_id,
                            message: format!("RPC bridge write failed: {error}"),
                        },
                    )
                    .await;
                    break;
                }
            }
        });
        let reader_outbound = task_outbound.clone();
        let reader = tokio::spawn(async move {
            let mut stdout = tokio::io::BufReader::new(stdout);
            loop {
                match rpc_stdio::read(kind, &mut stdout).await {
                    Ok(Some(body)) => {
                        if send_rpc(&reader_outbound, RpcMessage::Message { bridge_id, body })
                            .await
                            .is_err()
                        {
                            break;
                        }
                    }
                    Ok(None) => break,
                    Err(error) => {
                        let _ = send_rpc(
                            &reader_outbound,
                            RpcMessage::Error {
                                bridge_id,
                                message: format!("RPC bridge read failed: {error}"),
                            },
                        )
                        .await;
                        break;
                    }
                }
            }
        });
        let exit = child.wait().await;
        writer.abort();
        let _ = reader.await;
        let code = match exit {
            Ok(status) => status.code(),
            Err(error) => {
                let _ = send_rpc(
                    &task_outbound,
                    RpcMessage::Error {
                        bridge_id,
                        message: format!("RPC tool wait failed: {error}"),
                    },
                )
                .await;
                None
            }
        };
        let _ = send_rpc(&task_outbound, RpcMessage::Exited { bridge_id, code }).await;
    });
    Ok((pid, input, task, start_tx))
}

#[cfg(feature = "native-protocol")]
async fn send_rpc(outbound: &mpsc::Sender<OutboundFrame>, message: RpcMessage) -> Result<(), ()> {
    send_outbound(outbound, MultiplexedPacket::Rpc(message)).await
}

#[cfg(feature = "native-protocol")]
async fn process_search_message(
    message: SearchMessage,
    state: &AppState,
    outbound: &mpsc::Sender<OutboundFrame>,
    tasks: &mut HashMap<u64, tokio::task::JoinHandle<()>>,
    search_messages: &mpsc::Sender<SearchMessage>,
) -> Result<(), ()> {
    match message {
        start @ SearchMessage::StartReq { req_id, .. } => {
            tasks.retain(|_, task| !task.is_finished());
            if tasks.contains_key(&req_id) {
                return send_outbound(
                    outbound,
                    MultiplexedPacket::Search(SearchMessage::Error {
                        req_id,
                        message: "search request id is already active".into(),
                    }),
                )
                .await;
            }
            if tasks.len() >= MAX_ACTIVE_SEARCHES {
                return send_outbound(
                    outbound,
                    MultiplexedPacket::Search(SearchMessage::Error {
                        req_id,
                        message: format!(
                            "at most {MAX_ACTIVE_SEARCHES} search requests may run concurrently"
                        ),
                    }),
                )
                .await;
            }
            match search::start(state.vfs.clone(), start, search_messages.clone()) {
                Ok((task_id, task)) => {
                    tasks.insert(task_id, task);
                    Ok(())
                }
                Err(message) => {
                    send_outbound(
                        outbound,
                        MultiplexedPacket::Search(SearchMessage::Error { req_id, message }),
                    )
                    .await
                }
            }
        }
        SearchMessage::Cancel { req_id } => match tasks.remove(&req_id) {
            Some(task) => {
                task.abort();
                send_outbound(
                    outbound,
                    MultiplexedPacket::Search(SearchMessage::Done {
                        req_id,
                        total: 0,
                        truncated: true,
                        elapsed_ms: 0,
                    }),
                )
                .await
            }
            None => {
                send_outbound(
                    outbound,
                    MultiplexedPacket::Search(SearchMessage::Error {
                        req_id,
                        message: "search request is not active".into(),
                    }),
                )
                .await
            }
        },
        SearchMessage::Match { req_id, .. }
        | SearchMessage::Done { req_id, .. }
        | SearchMessage::Error { req_id, .. } => {
            send_outbound(
                outbound,
                MultiplexedPacket::Search(SearchMessage::Error {
                    req_id,
                    message: "client sent a daemon-only search message".into(),
                }),
            )
            .await
        }
    }
}

#[cfg(feature = "native-protocol")]
async fn process_git_message(
    message: GitMessage,
    state: &AppState,
    outbound: &mpsc::Sender<OutboundFrame>,
    forwarders: &mut HashMap<u64, GitForwarder>,
    prompt_answers: &GitPromptAnswers,
) -> Result<(), ()> {
    match message {
        GitMessage::Req { req_id, repo, op } => {
            forwarders.retain(|_, forwarder| !forwarder.task.is_finished());
            if forwarders.contains_key(&req_id) {
                return send_outbound(
                    outbound,
                    MultiplexedPacket::Git(GitMessage::Error {
                        req_id,
                        message: "Git request ID is already active".into(),
                    }),
                )
                .await;
            }
            if forwarders.len() >= MAX_ACTIVE_GIT_OPERATIONS {
                return send_outbound(
                    outbound,
                    MultiplexedPacket::Git(GitMessage::Error {
                        req_id,
                        message: "too many active Git operations".into(),
                    }),
                )
                .await;
            }

            let network_operation = matches!(
                &op,
                avf_protocol::GitOp::Fetch
                    | avf_protocol::GitOp::Pull { .. }
                    | avf_protocol::GitOp::Push { .. }
                    | avf_protocol::GitOp::Clone { .. }
            );
            let state_vfs = state.vfs.clone();
            let repository = repo.clone();
            let outbound = outbound.clone();
            let answers = prompt_answers.clone();
            let task = tokio::spawn(async move {
                let (prompt_tx, mut prompt_rx) = mpsc::channel(4);
                if network_operation
                    && send_outbound(
                        &outbound,
                        MultiplexedPacket::Git(GitMessage::Progress {
                            req_id,
                            phase: "starting".into(),
                            percent: Some(0),
                            detail: "Starting Git network operation".into(),
                        }),
                    )
                    .await
                    .is_err()
                {
                    return;
                }

                let operation = async {
                    if network_operation {
                        git::execute_network(&state_vfs, &repository, op, prompt_tx).await
                    } else {
                        git::execute(&state_vfs, &repository, op).await
                    }
                };
                tokio::pin!(operation);
                let result = loop {
                    tokio::select! {
                        result = &mut operation => break result,
                        prompt = prompt_rx.recv(), if network_operation => {
                            let Some(prompt) = prompt else { continue };
                            let prompt_id = prompt.prompt_id;
                            answers
                                .lock()
                                .await
                                .insert((req_id, prompt_id), prompt.answer);
                            if send_outbound(
                                &outbound,
                                MultiplexedPacket::Git(GitMessage::Prompt {
                                    req_id,
                                    prompt_id,
                                    text: prompt.text,
                                    secret: prompt.secret,
                                }),
                            )
                            .await
                            .is_err()
                            {
                                return;
                            }
                        }
                    }
                };

                answers
                    .lock()
                    .await
                    .retain(|(active_req_id, _), _| *active_req_id != req_id);
                let result = match result {
                    Ok(result) => {
                        if let avf_protocol::GitResult::Status { entries, .. } = &result {
                            if let Err(error) =
                                state_vfs.cache_git_status(&repository, entries).await
                            {
                                let _ = send_outbound(
                                    &outbound,
                                    MultiplexedPacket::Git(GitMessage::Error {
                                        req_id,
                                        message: format!(
                                            "Git status succeeded but its VFS metadata could not be refreshed: {}",
                                            error.message
                                        ),
                                    }),
                                )
                                .await;
                                return;
                            }
                        }
                        if network_operation {
                            let _ = send_outbound(
                                &outbound,
                                MultiplexedPacket::Git(GitMessage::Progress {
                                    req_id,
                                    phase: "complete".into(),
                                    percent: Some(100),
                                    detail: "Git network operation complete".into(),
                                }),
                            )
                            .await;
                        }
                        GitMessage::Res { req_id, result }
                    }
                    Err(message) => GitMessage::Error { req_id, message },
                };
                let _ = send_outbound(&outbound, MultiplexedPacket::Git(result)).await;
            });
            forwarders.insert(req_id, GitForwarder { task });
            Ok(())
        }
        GitMessage::PromptAnswer {
            req_id,
            prompt_id,
            answer,
        } => {
            if answer.len() > 64 * 1024
                || answer
                    .bytes()
                    .any(|byte| matches!(byte, b'\0' | b'\r' | b'\n'))
            {
                return send_outbound(
                    outbound,
                    MultiplexedPacket::Git(GitMessage::Error {
                        req_id,
                        message: "Git credential answer is invalid or too long".into(),
                    }),
                )
                .await;
            }
            let sender = prompt_answers.lock().await.remove(&(req_id, prompt_id));
            match sender {
                Some(sender) => {
                    if sender.send(answer).is_ok() {
                        Ok(())
                    } else {
                        send_outbound(
                            outbound,
                            MultiplexedPacket::Git(GitMessage::Error {
                                req_id,
                                message: "Git prompt is no longer active".into(),
                            }),
                        )
                        .await
                    }
                }
                None => {
                    send_outbound(
                        outbound,
                        MultiplexedPacket::Git(GitMessage::Error {
                            req_id,
                            message: "Git prompt is no longer active".into(),
                        }),
                    )
                    .await
                }
            }
        }
        GitMessage::Res { req_id, .. }
        | GitMessage::Progress { req_id, .. }
        | GitMessage::Prompt { req_id, .. }
        | GitMessage::Error { req_id, .. } => {
            send_outbound(
                outbound,
                MultiplexedPacket::Git(GitMessage::Error {
                    req_id,
                    message: "client sent a daemon-only Git message".into(),
                }),
            )
            .await
        }
    }
}

async fn send_pty_payload(
    outbound: &mpsc::Sender<OutboundFrame>,
    session_id: uuid::Uuid,
    data: PtyPayload,
) -> Result<(), ()> {
    send_outbound(outbound, MultiplexedPacket::Pty { session_id, data }).await
}

async fn attach_pty(
    session_id: uuid::Uuid,
    state: &AppState,
    outbound: &mpsc::Sender<OutboundFrame>,
    forwarders: &mut HashMap<uuid::Uuid, (u64, tokio::task::JoinHandle<()>)>,
) -> Result<(), ()> {
    if let Some((subscriber_id, task)) = forwarders.remove(&session_id) {
        task.abort();
        state.pty.detach(session_id, subscriber_id).await;
    }

    let subscription = match state.pty.attach(session_id).await {
        Ok(subscription) => subscription,
        Err(message) => {
            return send_pty_payload(outbound, session_id, PtyPayload::Error { message }).await;
        }
    };
    let subscriber_id = subscription.id;
    let mut receiver = subscription.receiver;
    let pty = state.pty.clone();
    let outbound = outbound.clone();
    let task = tokio::spawn(async move {
        let mut exited = false;
        while let Some(event) = receiver.recv().await {
            let payload = match event {
                PtyEvent::Output(data) => PtyPayload::Output(data),
                PtyEvent::Error(message) => PtyPayload::Error { message },
                PtyEvent::Exit(code) => {
                    exited = true;
                    PtyPayload::Exit { code }
                }
            };
            if send_pty_payload(&outbound, session_id, payload)
                .await
                .is_err()
                || exited
            {
                break;
            }
        }
        if !exited {
            let _ = send_pty_payload(
                &outbound,
                session_id,
                PtyPayload::Error {
                    message: "terminal output stream stopped; reattach to recover buffered output"
                        .to_owned(),
                },
            )
            .await;
        }
        pty.detach(session_id, subscriber_id).await;
    });
    forwarders.insert(session_id, (subscriber_id, task));
    Ok(())
}

fn vfs_error(req_id: u64, error: VfsFailure) -> VfsMessage {
    VfsMessage::VfsError {
        req_id,
        code: error.code,
        message: error.message,
    }
}

#[cfg(feature = "native-protocol")]
type RpcLaunchOptions = (Option<PathBuf>, Vec<(String, String)>);

#[cfg(feature = "native-protocol")]
fn validate_rpc_launch_options(
    vfs: &Vfs,
    manifest: &tools::ToolManifest,
    args: &[String],
    cwd: Option<String>,
    env: Vec<(String, String)>,
) -> Result<RpcLaunchOptions, String> {
    const MAX_ARGS: usize = 128;
    const MAX_ARGUMENT_BYTES: usize = 16 * 1024;
    const MAX_ENV_VARIABLES: usize = 32;
    const MAX_ENV_VALUE_BYTES: usize = 64 * 1024;

    let argument_size = args.iter().try_fold(0usize, |size, argument| {
        if argument.contains('\0') {
            return None;
        }
        size.checked_add(argument.len())
    });
    if args.len() > MAX_ARGS || argument_size.is_none_or(|size| size > MAX_ARGUMENT_BYTES) {
        return Err("RPC launch arguments exceed the permitted limits".into());
    }

    let cwd = cwd
        .map(|cwd| -> Result<PathBuf, String> {
            let resolved = vfs.resolve(&cwd).map_err(|error| {
                format!(
                    "RPC working directory is outside the VFS: {}",
                    error.message
                )
            })?;
            let canonical = std::fs::canonicalize(&resolved.absolute)
                .map_err(|error| format!("cannot resolve RPC working directory: {error}"))?;
            if canonical != resolved.absolute || !canonical.is_dir() {
                return Err(
                    "RPC working directory must be an existing, non-symlink directory".into(),
                );
            }
            Ok(canonical)
        })
        .transpose()?;

    if env.len() > MAX_ENV_VARIABLES {
        return Err(format!(
            "at most {MAX_ENV_VARIABLES} RPC environment variables are allowed"
        ));
    }
    let mut seen = HashSet::with_capacity(env.len());
    for (name, value) in &env {
        if !manifest.env_allowlist.iter().any(|allowed| allowed == name) {
            return Err(format!(
                "environment variable '{name}' is not allowed by tool '{}'",
                manifest.name
            ));
        }
        if !seen.insert(name) {
            return Err(format!("duplicate RPC environment variable '{name}'"));
        }
        if value.len() > MAX_ENV_VALUE_BYTES || value.contains('\0') {
            return Err(format!(
                "invalid value for RPC environment variable '{name}'"
            ));
        }
    }
    Ok((cwd, env))
}

#[cfg(feature = "native-protocol")]
fn lsp_session_key(
    tool: &str,
    args: &[String],
    cwd: &Option<PathBuf>,
    env: &[(String, String)],
) -> String {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    tool.hash(&mut hasher);
    args.hash(&mut hasher);
    cwd.hash(&mut hasher);
    env.hash(&mut hasher);
    format!("{tool}:{:016x}", hasher.finish())
}

async fn send_outbound(
    outbound: &mpsc::Sender<OutboundFrame>,
    packet: MultiplexedPacket,
) -> Result<(), ()> {
    let packet = if encode(&packet).len() <= MAX_FRAME_SIZE {
        packet
    } else {
        match packet {
            MultiplexedPacket::Vfs(VfsMessage::ListDirRes { req_id, .. }) => {
                MultiplexedPacket::Vfs(VfsMessage::VfsError {
                    req_id,
                    code: VfsErrorCode::TooLarge,
                    message: "directory listing exceeds the 32 MiB frame limit".to_owned(),
                })
            }
            MultiplexedPacket::Vfs(VfsMessage::FsEvent { paths, .. }) => {
                MultiplexedPacket::Vfs(VfsMessage::FsEvent {
                    kind: FsEventKind::Rescan,
                    paths: paths.into_iter().take(1).collect(),
                })
            }
            _ => MultiplexedPacket::System(SystemMessage::ProtocolError {
                message: "outbound packet exceeds the 32 MiB frame limit".to_owned(),
            }),
        }
    };
    outbound
        .send(OutboundFrame::Packet(packet))
        .await
        .map_err(|_| ())
}

async fn send_protocol_error_outbound(
    outbound: &mpsc::Sender<OutboundFrame>,
    message: &str,
) -> Result<(), ()> {
    send_outbound(
        outbound,
        MultiplexedPacket::System(SystemMessage::ProtocolError {
            message: message.to_owned(),
        }),
    )
    .await?;
    outbound
        .send(OutboundFrame::Control(Message::Close(None)))
        .await
        .map_err(|_| ())
}

async fn reject(socket: &mut WebSocket, reason: RejectReason, message: &str) {
    let _ = send_packet(
        socket,
        &MultiplexedPacket::System(SystemMessage::Rejected { reason }),
    )
    .await;
    let _ = socket.send(Message::Close(None)).await;
    debug!(%message, "websocket handshake rejected");
}

async fn send_protocol_error(socket: &mut WebSocket, message: &str) {
    let _ = send_packet(
        socket,
        &MultiplexedPacket::System(SystemMessage::ProtocolError {
            message: message.to_owned(),
        }),
    )
    .await;
    let _ = socket.send(Message::Close(None)).await;
}

async fn send_packet(
    socket: &mut WebSocket,
    packet: &MultiplexedPacket,
) -> Result<(), axum::Error> {
    socket.send(Message::Binary(encode(packet).into())).await
}

struct WatchHandle {
    _watcher: notify::RecommendedWatcher,
    _cancel: oneshot::Sender<()>,
}

async fn start_watch(
    vfs: &Vfs,
    requested: &str,
    recursive: bool,
    outbound: mpsc::Sender<OutboundFrame>,
) -> Result<(String, WatchHandle), VfsFailure> {
    let (key, path) = vfs.validate_watch(requested).await?;
    let (event_sender, event_receiver) = mpsc::channel(WATCH_EVENT_CAPACITY);
    let overflow = Arc::new(AtomicBool::new(false));
    let callback_overflow = Arc::clone(&overflow);
    let mut watcher = notify::recommended_watcher(move |event| {
        if event_sender.try_send(event).is_err() {
            callback_overflow.store(true, Ordering::Release);
        }
    })
    .map_err(|error| VfsFailure {
        code: VfsErrorCode::Io,
        message: error.to_string(),
    })?;
    watcher
        .watch(
            &path,
            if recursive {
                RecursiveMode::Recursive
            } else {
                RecursiveMode::NonRecursive
            },
        )
        .map_err(|error| VfsFailure {
            code: VfsErrorCode::Io,
            message: error.to_string(),
        })?;
    let (cancel_sender, cancel_receiver) = oneshot::channel();
    tokio::spawn(watch_events(
        vfs.clone(),
        path.to_string_lossy().into_owned(),
        event_receiver,
        overflow,
        outbound,
        cancel_receiver,
    ));
    Ok((
        key,
        WatchHandle {
            _watcher: watcher,
            _cancel: cancel_sender,
        },
    ))
}

async fn watch_events(
    vfs: Vfs,
    watch_path: String,
    mut events: mpsc::Receiver<notify::Result<Event>>,
    overflow: Arc<AtomicBool>,
    outbound: mpsc::Sender<OutboundFrame>,
    mut cancel: oneshot::Receiver<()>,
) {
    #[cfg(not(feature = "native-protocol"))]
    let _ = &vfs;
    let root = PathBuf::from(&watch_path);
    let mut debounce = interval(WATCH_DEBOUNCE);
    debounce.tick().await;
    let mut pending = HashMap::<FsEventKind, HashSet<String>>::new();
    let mut pending_renames = Vec::<Vec<String>>::new();
    loop {
        tokio::select! {
            _ = &mut cancel => break,
            _ = debounce.tick() => {
                if overflow.swap(false, Ordering::AcqRel) {
                    pending.clear();
                    pending_renames.clear();
                    #[cfg(feature = "native-protocol")]
                    refresh_watched_git_status(&vfs, std::slice::from_ref(&watch_path)).await;
                    if send_watch_event(&outbound, FsEventKind::Rescan, vec![watch_path.clone()]).await.is_err() {
                        break;
                    }
                } else {
                    for (kind, paths) in pending.drain() {
                        let mut paths = paths.into_iter().collect::<Vec<_>>();
                        paths.sort();
                        #[cfg(feature = "native-protocol")]
                        refresh_watched_git_status(&vfs, &paths).await;
                        if send_watch_event(&outbound, kind, paths).await.is_err() {
                            return;
                        }
                    }
                    for paths in pending_renames.drain(..) {
                        #[cfg(feature = "native-protocol")]
                        refresh_watched_git_status(&vfs, &paths).await;
                        if send_watch_event(&outbound, FsEventKind::Rename, paths).await.is_err() {
                            return;
                        }
                    }
                }
            }
            event = events.recv() => {
                let Some(event) = event else { break };
                match event {
                    Ok(event) => {
                        if let Some(kind) = event_kind(&event) {
                            let has_relevant_path = event.paths.iter().any(|path| {
                                path.starts_with(&root) && !is_ignored(path)
                            });
                            let mut paths = Vec::new();
                            for path in event.paths.into_iter().filter(|path| {
                                path.starts_with(&root) && !is_ignored(path)
                            }) {
                                if let Some(path) = path.to_str() {
                                    paths.push(path.to_owned());
                                } else {
                                    overflow.store(true, Ordering::Release);
                                }
                            }
                            if kind == FsEventKind::Rename {
                                if paths.len() >= 2 {
                                    pending_renames.push(paths.into_iter().take(2).collect());
                                } else if has_relevant_path {
                                    overflow.store(true, Ordering::Release);
                                }
                            } else if !paths.is_empty() {
                                pending.entry(kind).or_default().extend(paths);
                            }
                        }
                    }
                    Err(error) => {
                        warn!(%error, "file watcher reported an error; requesting a rescan");
                        overflow.store(true, Ordering::Release);
                    }
                }
            }
        }
    }
}

#[cfg(feature = "native-protocol")]
async fn refresh_watched_git_status(vfs: &Vfs, paths: &[String]) {
    if let Err(error) = git::refresh_status_for_paths(vfs, paths).await {
        warn!(%error, "could not refresh Git file metadata after filesystem event");
    }
}

async fn send_watch_event(
    outbound: &mpsc::Sender<OutboundFrame>,
    kind: FsEventKind,
    paths: Vec<String>,
) -> Result<(), ()> {
    send_outbound(
        outbound,
        MultiplexedPacket::Vfs(VfsMessage::FsEvent { kind, paths }),
    )
    .await
}

fn event_kind(event: &Event) -> Option<FsEventKind> {
    match event.kind {
        EventKind::Create(_) => Some(FsEventKind::Create),
        EventKind::Remove(_) => Some(FsEventKind::Remove),
        EventKind::Modify(ModifyKind::Name(RenameMode::Both)) if event.paths.len() >= 2 => {
            Some(FsEventKind::Rename)
        }
        EventKind::Modify(_) => Some(FsEventKind::Modify),
        _ => None,
    }
}

fn is_ignored(path: &Path) -> bool {
    let mut previous_was_git = false;
    for component in path.components() {
        let name = component.as_os_str().to_string_lossy();
        if matches!(name.as_ref(), "node_modules" | "target" | ".venv") {
            return true;
        }
        if previous_was_git && name == "objects" {
            return true;
        }
        previous_was_git = name == ".git";
    }
    false
}

#[derive(Default)]
struct Telemetry {
    previous_cpu: Mutex<Option<(u64, u64)>>,
}

struct SystemSample {
    cpu_usage: f32,
    mem_used_mb: u64,
    mem_total_mb: u64,
    mem_free_mb: u64,
    mem_cached_mb: u64,
    mem_balloon_mb: u64,
    disk_problem: Option<String>,
    disk_used_mb: u64,
    disk_total_mb: u64,
}

impl Telemetry {
    async fn sample(&self) -> io::Result<SystemSample> {
        let stat = tokio::fs::read_to_string("/proc/stat").await?;
        let meminfo = tokio::fs::read_to_string("/proc/meminfo").await?;
        let balloon_pages = tokio::fs::read_to_string("/proc/vmstat")
            .await
            .map(|vmstat| balloon_pages(&vmstat))
            .unwrap_or(0);
        let (total, idle) = parse_cpu_stat(&stat).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "missing aggregate CPU row")
        })?;
        let memory = parse_meminfo(&meminfo).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "missing memory totals in /proc/meminfo",
            )
        })?;
        let cpu_usage = {
            let mut previous = self.previous_cpu.lock().await;
            previous
                .replace((total, idle))
                .and_then(|(old_total, old_idle)| {
                    let total_delta = total.saturating_sub(old_total);
                    let idle_delta = idle.saturating_sub(old_idle);
                    (total_delta > 0).then(|| {
                        (total_delta.saturating_sub(idle_delta) as f32 / total_delta as f32 * 100.0)
                            .clamp(0.0, 100.0)
                    })
                })
                .unwrap_or(0.0)
        };
        let mebibyte = 1024 * 1024;
        let disk_usage = storage_usage_mb(&env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("/")));
        Ok(SystemSample {
            cpu_usage,
            mem_used_mb: memory.total.saturating_sub(memory.available) / mebibyte,
            mem_total_mb: memory.total / mebibyte,
            mem_free_mb: memory.free / mebibyte,
            mem_cached_mb: memory.cached / mebibyte,
            // Pages are 4 KiB on the AVF guest; vmstat counts them, not bytes.
            mem_balloon_mb: balloon_pages * 4096 / mebibyte,
            disk_problem: disk_problem().await,
            disk_used_mb: disk_usage.0,
            disk_total_mb: disk_usage.1,
        })
    }
}

fn parse_cpu_stat(contents: &str) -> Option<(u64, u64)> {
    let line = contents.lines().find(|line| line.starts_with("cpu "))?;
    let values = line
        .split_whitespace()
        .skip(1)
        .map(str::parse::<u64>)
        .collect::<Result<Vec<_>, _>>()
        .ok()?;
    let total = values.iter().copied().sum();
    let idle = values.get(3).copied().unwrap_or(0) + values.get(4).copied().unwrap_or(0);
    Some((total, idle))
}

/// Byte counts from /proc/meminfo.
#[derive(Debug, PartialEq)]
struct MemInfo {
    total: u64,
    available: u64,
    free: u64,
    /// Page cache plus buffers, minus shared memory (tmpfs), i.e. what drop_caches can release.
    cached: u64,
}

fn parse_meminfo(contents: &str) -> Option<MemInfo> {
    let mut fields = HashMap::new();
    for line in contents.lines() {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        if let Some(value) = value.split_whitespace().next().and_then(|value| value.parse::<u64>().ok()) {
            fields.insert(name.trim(), value);
        }
    }
    let kib = |name: &str| fields.get(name).and_then(|value| value.checked_mul(1024));
    let total = kib("MemTotal")?;
    let free = kib("MemFree").unwrap_or(0).min(total);
    let available = kib("MemAvailable").or(kib("MemFree"))?.min(total);
    let cached = (kib("Cached").unwrap_or(0) + kib("Buffers").unwrap_or(0)).saturating_sub(kib("Shmem").unwrap_or(0));
    Some(MemInfo { total, available, free, cached })
}

/// (used, total) MB of the filesystem holding [path]; used counts blocks not free to anyone, as df does.
#[cfg(unix)]
fn storage_usage_mb(path: &Path) -> (u64, u64) {
    use std::os::unix::ffi::OsStrExt;
    let Ok(c_path) = std::ffi::CString::new(path.as_os_str().as_bytes()) else {
        return (0, 0);
    };
    let mut stats = std::mem::MaybeUninit::<libc::statvfs>::zeroed();
    // SAFETY: c_path is NUL-terminated and stats is a valid out-pointer for statvfs.
    if unsafe { libc::statvfs(c_path.as_ptr(), stats.as_mut_ptr()) } != 0 {
        return (0, 0);
    }
    // SAFETY: statvfs returned 0, so it filled the struct.
    let stats = unsafe { stats.assume_init() };
    let fragment = stats.f_frsize as u64;
    let total = stats.f_blocks as u64 * fragment;
    let free = stats.f_bfree as u64 * fragment;
    (total.saturating_sub(free) / (1024 * 1024), total / (1024 * 1024))
}

#[cfg(not(unix))]
fn storage_usage_mb(_path: &Path) -> (u64, u64) {
    (0, 0)
}

/// Whether the guest's disk is in trouble: the root filesystem remounted read-only, or ext4 logged
/// an error since this boot (`/sys/fs/ext4/<dev>/last_error_time`; fsck clears older ones).
async fn disk_problem() -> Option<String> {
    let mounts = tokio::fs::read_to_string("/proc/mounts").await.ok()?;
    let boot_time = tokio::fs::read_to_string("/proc/stat")
        .await
        .ok()
        .and_then(|stat| boot_time(&stat))
        .unwrap_or(0);
    let mut errors = Vec::new();
    if let Ok(mut devices) = tokio::fs::read_dir("/sys/fs/ext4").await {
        while let Ok(Some(device)) = devices.next_entry().await {
            let read = |name: &str| tokio::fs::read_to_string(device.path().join(name));
            let last = read("last_error_time").await.ok().and_then(|value| value.trim().parse::<u64>().ok());
            let function = read("last_error_func").await.unwrap_or_default();
            errors.push((device.file_name().to_string_lossy().into_owned(), last.unwrap_or(0), function.trim().to_owned()));
        }
    }
    describe_disk_problem(&mounts, boot_time, &errors)
}

fn boot_time(stat: &str) -> Option<u64> {
    stat.lines().find_map(|line| line.strip_prefix("btime ")?.trim().parse().ok())
}

/// [errors] holds (device, last error time, function) per ext4 device.
fn describe_disk_problem(mounts: &str, boot_time: u64, errors: &[(String, u64, String)]) -> Option<String> {
    let root_read_only = mounts.lines().any(|line| {
        let fields: Vec<&str> = line.split_whitespace().collect();
        fields.get(1) == Some(&"/") && fields.get(3).is_some_and(|options| options.split(',').any(|option| option == "ro"))
    });
    if root_read_only {
        return Some("The guest's disk went read-only after an error".to_owned());
    }
    errors
        .iter()
        .find(|(_, time, _)| *time > 0 && *time >= boot_time)
        .map(|(device, _, function)| format!("The guest's disk ({device}) reported an error in {function}"))
}

/// Pages currently held by the virtio balloon (inflated minus deflated), from /proc/vmstat.
fn balloon_pages(vmstat: &str) -> u64 {
    let count = |name: &str| {
        vmstat
            .lines()
            .find_map(|line| line.strip_prefix(name)?.strip_prefix(' ')?.trim().parse::<u64>().ok())
            .unwrap_or(0)
    };
    count("balloon_inflate").saturating_sub(count("balloon_deflate"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use avf_protocol::{decode, encode, MultiplexedPacket, PtyPayload, SystemMessage, VfsMessage};
    #[cfg(feature = "native-protocol")]
    use avf_protocol::{GitMessage, GitOp, RpcKind, RpcMessage, SearchMessage};
    use axum::http::HeaderValue;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_tungstenite::tungstenite::{
        client::IntoClientRequest, http::header::ORIGIN as WS_ORIGIN,
        protocol::Message as WsMessage,
    };
    use tokio_tungstenite::WebSocketStream;

    async fn next_vfs_response<S>(socket: &mut WebSocketStream<S>, req_id: u64) -> VfsMessage
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        timeout(Duration::from_secs(3), async {
            loop {
                let Some(Ok(WsMessage::Binary(bytes))) = socket.next().await else {
                    continue;
                };
                let MultiplexedPacket::Vfs(message) = decode(&bytes).unwrap() else {
                    continue;
                };
                let received_id = match &message {
                    VfsMessage::ListDirRes { req_id, .. }
                    | VfsMessage::StatRes { req_id, .. }
                    | VfsMessage::ReadFileRes { req_id, .. }
                    | VfsMessage::Ack { req_id, .. }
                    | VfsMessage::RootsRes { req_id, .. }
                    | VfsMessage::WatchRes { req_id }
                    | VfsMessage::VfsError { req_id, .. } => *req_id,
                    VfsMessage::FsEvent { .. }
                    | VfsMessage::ListDirReq { .. }
                    | VfsMessage::StatReq { .. }
                    | VfsMessage::ReadFileReq { .. }
                    | VfsMessage::WriteFileReq { .. }
                    | VfsMessage::FlushReq { .. }
                    | VfsMessage::CreateReq { .. }
                    | VfsMessage::DeleteReq { .. }
                    | VfsMessage::RenameReq { .. }
                    | VfsMessage::CopyReq { .. }
                    | VfsMessage::RootsReq { .. }
                    | VfsMessage::RestoreReq { .. }
                    | VfsMessage::WatchReq { .. }
                    | VfsMessage::UnwatchReq { .. } => continue,
                };
                if received_id == req_id {
                    break message;
                }
            }
        })
        .await
        .expect("timed out waiting for VFS response")
    }

    #[cfg(feature = "native-protocol")]
    async fn next_search_message<S>(socket: &mut WebSocketStream<S>, req_id: u64) -> SearchMessage
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        timeout(Duration::from_secs(3), async {
            loop {
                let Some(Ok(WsMessage::Binary(bytes))) = socket.next().await else {
                    continue;
                };
                let MultiplexedPacket::Search(message) = decode(&bytes).unwrap() else {
                    continue;
                };
                let received_id = match &message {
                    SearchMessage::StartReq { req_id, .. }
                    | SearchMessage::Cancel { req_id }
                    | SearchMessage::Match { req_id, .. }
                    | SearchMessage::Done { req_id, .. }
                    | SearchMessage::Error { req_id, .. } => *req_id,
                };
                if received_id == req_id {
                    break message;
                }
            }
        })
        .await
        .expect("timed out waiting for search response")
    }

    #[cfg(feature = "native-protocol")]
    async fn next_git_message<S>(socket: &mut WebSocketStream<S>, req_id: u64) -> GitMessage
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        timeout(Duration::from_secs(5), async {
            loop {
                let Some(Ok(WsMessage::Binary(bytes))) = socket.next().await else {
                    continue;
                };
                let MultiplexedPacket::Git(message) = decode(&bytes).unwrap() else {
                    continue;
                };
                let received_id = match &message {
                    GitMessage::Req { req_id, .. }
                    | GitMessage::Res { req_id, .. }
                    | GitMessage::Progress { req_id, .. }
                    | GitMessage::Prompt { req_id, .. }
                    | GitMessage::PromptAnswer { req_id, .. }
                    | GitMessage::Error { req_id, .. } => *req_id,
                };
                if received_id == req_id {
                    break message;
                }
            }
        })
        .await
        .expect("timed out waiting for Git response")
    }

    #[test]
    fn parses_cpu_and_memory_proc_data() {
        assert_eq!(
            parse_cpu_stat("cpu 10 2 3 40 5 1 1 0 0 0\n"),
            Some((62, 45))
        );
        assert_eq!(
            parse_meminfo("MemTotal: 2048 kB\nMemAvailable: 1024 kB\n"),
            Some(MemInfo { total: 2 * 1024 * 1024, available: 1024 * 1024, free: 0, cached: 0 })
        );
        assert_eq!(
            parse_meminfo(
                "MemTotal: 4000 kB\nMemFree: 1000 kB\nMemAvailable: 3000 kB\nBuffers: 100 kB\nCached: 1500 kB\nShmem: 200 kB\n"
            ),
            Some(MemInfo { total: 4000 * 1024, available: 3000 * 1024, free: 1000 * 1024, cached: 1400 * 1024 })
        );
    }

    #[test]
    fn disk_problems_are_read_only_roots_or_errors_since_boot() {
        let healthy = "/dev/root / ext4 rw,relatime,discard 0 0\n";
        let read_only = "/dev/root / ext4 ro,relatime 0 0\n";
        let before_boot = [("vda1".to_owned(), 100, "htree_dirblock_to_tree".to_owned())];
        let since_boot = [("vda1".to_owned(), 500, "htree_dirblock_to_tree".to_owned())];
        assert_eq!(describe_disk_problem(healthy, 200, &before_boot), None);
        assert!(describe_disk_problem(healthy, 200, &since_boot).unwrap().contains("htree_dirblock_to_tree"));
        assert!(describe_disk_problem(read_only, 200, &[]).unwrap().contains("read-only"));
        assert_eq!(boot_time("cpu 1 2\nbtime 1792850000\n"), Some(1792850000));
    }

    #[cfg(unix)]
    #[test]
    fn storage_usage_reads_the_filesystem() {
        let (used, total) = storage_usage_mb(Path::new("/"));
        assert!(total > 0 && used <= total);
        assert_eq!(storage_usage_mb(Path::new("/no/such/path")), (0, 0));
    }

    #[test]
    fn balloon_pages_are_inflated_minus_deflated() {
        // Recorded on the AVF guest with a 1 GiB balloon.
        let vmstat = "nr_free_pages 526245\nballoon_inflate 471859\nballoon_deflate 209715\nballoon_migrate 49187\n";
        assert_eq!(balloon_pages(vmstat) * 4096 / (1024 * 1024), 1024);
        assert_eq!(balloon_pages("nr_free_pages 1\n"), 0);
    }

    #[test]
    fn ignores_heavy_directories_in_watch_events() {
        assert!(is_ignored(Path::new("/project/target/debug")));
        assert!(is_ignored(Path::new("/project/.git/objects/ab")));
        assert!(is_ignored(Path::new("/project/node_modules/pkg")));
        assert!(!is_ignored(Path::new("/project/src/main.rs")));
    }

    #[tokio::test]
    async fn health_returns_protocol_version_without_authentication() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        std::fs::create_dir(&project).unwrap();
        let config = AppConfig {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            token: "a".repeat(64),
            allowed_origins: HashSet::new(),
            workspace_roots: vec![project],
            lsp_hibernation_secs: 30 * 60,
            pairing_file: None,
            tls: false,
        };
        let listener = TcpListener::bind(config.bind_addr).await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(listener, app(config).unwrap()).await.unwrap();
        });
        let mut client = tokio::net::TcpStream::connect(address).await.unwrap();
        client
            .write_all(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            .await
            .unwrap();
        let mut response = Vec::new();
        client.read_to_end(&mut response).await.unwrap();
        let response = String::from_utf8(response).unwrap();
        assert!(response.contains("200 OK"));
        assert!(response.contains(&format!(
            r#"{{"status":"ok","protocol_version":{PROTOCOL_VERSION}}}"#
        )));
        server.abort();
    }

    #[cfg(feature = "native-protocol")]
    #[tokio::test]
    async fn rpc_lsp_bridge_starts_forwards_and_stops() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        std::fs::create_dir(&project).unwrap();
        let state = AppState {
            config: Arc::new(AppConfig {
                bind_addr: "127.0.0.1:0".parse().unwrap(),
                token: "a".repeat(64),
                allowed_origins: HashSet::new(),
                workspace_roots: vec![project.clone()],
                lsp_hibernation_secs: 30 * 60,
                pairing_file: None,
            tls: false,
            }),
            vfs: Vfs::new(std::slice::from_ref(&project)).unwrap(),
            pty: PtyManager::default(),
            lsp: LspRouter::test_router(Duration::from_secs(30), "test", "cat", Vec::new()),
            installed_tools: Arc::new(HashMap::from([
                (
                    "test".to_owned(),
                    test_tool_manifest("test", "lsp", vec!["cat".into()]),
                ),
                (
                    "test-dap".to_owned(),
                    test_tool_manifest("test-dap", "dap", vec!["cat".into()]),
                ),
                (
                    "test-acp".to_owned(),
                    test_tool_manifest("test-acp", "acp-agent", vec!["cat".into()]),
                ),
            ])),
            tool_registry_home: None,
            ports: PortScanner::default(),
            telemetry: Arc::new(Telemetry::default()),
        };
        let (outbound, mut received) = mpsc::channel(8);
        let bridge_id = uuid::Uuid::new_v4();
        let mut forwarders = HashMap::new();

        process_rpc_message(
            RpcMessage::Start {
                bridge_id,
                tool: "test".into(),
                kind: RpcKind::Lsp,
                args: Vec::new(),
                cwd: Some(project.to_string_lossy().into_owned()),
                env: vec![("TEST_ALLOWED".into(), "value".into())],
            },
            &state,
            &outbound,
            &mut forwarders,
        )
        .await
        .unwrap();
        assert!(matches!(
            received.recv().await.unwrap(),
            OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Started {
                bridge_id: received_id,
                pid,
            })) if received_id == bridge_id && pid > 0
        ));

        let body = r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"#;
        process_rpc_message(
            RpcMessage::Message {
                bridge_id,
                body: body.into(),
            },
            &state,
            &outbound,
            &mut forwarders,
        )
        .await
        .unwrap();
        let response = timeout(Duration::from_secs(1), received.recv())
            .await
            .unwrap()
            .unwrap();
        assert!(
            matches!(
                &response,
                OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Message {
                    bridge_id: received_id,
                    body: received_body,
                })) if *received_id == bridge_id && received_body == body
            ),
            "unexpected LSP response: {response:?}"
        );

        process_rpc_message(
            RpcMessage::Message {
                bridge_id,
                body: "not JSON-RPC".into(),
            },
            &state,
            &outbound,
            &mut forwarders,
        )
        .await
        .unwrap();
        assert!(matches!(
            received.recv().await.unwrap(),
            OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Error {
                bridge_id: received_id,
                ..
            })) if received_id == bridge_id
        ));

        process_rpc_message(
            RpcMessage::Stop { bridge_id },
            &state,
            &outbound,
            &mut forwarders,
        )
        .await
        .unwrap();
        assert!(matches!(
            received.recv().await.unwrap(),
            OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Exited {
                bridge_id: received_id,
                code: None,
            })) if received_id == bridge_id
        ));

        for (tool, kind, args, cwd, env, body) in [
            (
                "test-dap",
                RpcKind::Dap,
                vec!["-".into()],
                Some(project.to_string_lossy().into_owned()),
                vec![("TEST_ALLOWED".into(), "value".into())],
                r#"{"seq":1,"type":"request","command":"initialize"}"#,
            ),
            (
                "test-acp",
                RpcKind::Acp,
                Vec::new(),
                None,
                Vec::new(),
                r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"#,
            ),
        ] {
            let bridge_id = uuid::Uuid::new_v4();
            process_rpc_message(
                RpcMessage::Start {
                    bridge_id,
                    tool: tool.into(),
                    kind,
                    args,
                    cwd,
                    env,
                },
                &state,
                &outbound,
                &mut forwarders,
            )
            .await
            .unwrap();
            assert!(matches!(
                received.recv().await.unwrap(),
                OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Started {
                    bridge_id: received_id,
                    pid,
                })) if received_id == bridge_id && pid > 0
            ));
            process_rpc_message(
                RpcMessage::Message {
                    bridge_id,
                    body: body.into(),
                },
                &state,
                &outbound,
                &mut forwarders,
            )
            .await
            .unwrap();
            let response = timeout(Duration::from_secs(1), received.recv())
                .await
                .unwrap()
                .unwrap();
            assert!(
                matches!(
                    &response,
                    OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Message {
                        bridge_id: received_id,
                        body: received_body,
                    })) if *received_id == bridge_id && received_body == body
                ),
                "unexpected direct RPC response: {response:?}"
            );
            process_rpc_message(
                RpcMessage::Stop { bridge_id },
                &state,
                &outbound,
                &mut forwarders,
            )
            .await
            .unwrap();
            assert!(matches!(
                received.recv().await.unwrap(),
                OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Exited {
                    bridge_id: received_id,
                    code: None,
                })) if received_id == bridge_id
            ));
        }

        let rejected_id = uuid::Uuid::new_v4();
        process_rpc_message(
            RpcMessage::Start {
                bridge_id: rejected_id,
                tool: "test".into(),
                kind: RpcKind::Dap,
                args: Vec::new(),
                cwd: None,
                env: Vec::new(),
            },
            &state,
            &outbound,
            &mut forwarders,
        )
        .await
        .unwrap();
        assert!(matches!(
            received.recv().await.unwrap(),
            OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Error {
                bridge_id: received_id,
                ..
            })) if received_id == rejected_id
        ));

        let unregistered_id = uuid::Uuid::new_v4();
        process_rpc_message(
            RpcMessage::Start {
                bridge_id: unregistered_id,
                tool: "/bin/sh".into(),
                kind: RpcKind::Lsp,
                args: Vec::new(),
                cwd: None,
                env: Vec::new(),
            },
            &state,
            &outbound,
            &mut forwarders,
        )
        .await
        .unwrap();
        assert!(matches!(
            received.recv().await.unwrap(),
            OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Error {
                bridge_id: received_id,
                ..
            })) if received_id == unregistered_id
        ));

        let options_id = uuid::Uuid::new_v4();
        process_rpc_message(
            RpcMessage::Start {
                bridge_id: options_id,
                tool: "test".into(),
                kind: RpcKind::Lsp,
                args: Vec::new(),
                cwd: None,
                env: vec![("NOT_ALLOWED".into(), "secret".into())],
            },
            &state,
            &outbound,
            &mut forwarders,
        )
        .await
        .unwrap();
        assert!(matches!(
            received.recv().await.unwrap(),
            OutboundFrame::Packet(MultiplexedPacket::Rpc(RpcMessage::Error {
                bridge_id: received_id,
                ..
            })) if received_id == options_id
        ));
    }

    #[cfg(feature = "native-protocol")]
    fn test_tool_manifest(name: &str, kind: &str, command: Vec<String>) -> tools::ToolManifest {
        tools::ToolManifest {
            name: name.into(),
            version: "1".into(),
            kind: kind.into(),
            languages: Vec::new(),
            command,
            install: tools::InstallSpec {
                apt: Some(vec!["test".into()]),
                uv: None,
                npm: None,
                cargo: None,
                download: None,
                script: None,
            },
            settings_section: name.into(),
            env_allowlist: vec!["TEST_ALLOWED".into()],
            initialization_options: serde_json::json!({}),
        }
    }

    #[tokio::test]
    async fn websocket_authenticates_before_serving_vfs_requests() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        std::fs::create_dir(&project).unwrap();
        std::fs::write(project.join("main.rs"), "fn main() {}").unwrap();
        let outside = temp.path().join("secret.txt");
        std::fs::write(&outside, "secret").unwrap();
        let config = AppConfig {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            token: "a".repeat(64),
            allowed_origins: HashSet::from(["http://localhost:5173".to_owned()]),
            workspace_roots: vec![project.clone()],
            lsp_hibernation_secs: 30 * 60,
            pairing_file: None,
            tls: false,
        };
        let listener = TcpListener::bind(config.bind_addr).await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(listener, app(config).unwrap()).await.unwrap();
        });

        let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
        request
            .headers_mut()
            .insert(WS_ORIGIN, HeaderValue::from_static("http://localhost:5173"));
        let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::System(SystemMessage::Hello {
                    protocol_version: PROTOCOL_VERSION,
                    auth_token: "a".repeat(64),
                    client_name: "test".into(),
                }))
                .into(),
            ))
            .await
            .unwrap();
        let welcome = socket.next().await.unwrap().unwrap();
        let WsMessage::Binary(bytes) = welcome else {
            panic!("expected binary Welcome");
        };
        assert!(matches!(
            decode(&bytes).unwrap(),
            MultiplexedPacket::System(SystemMessage::Welcome { .. })
        ));

        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::Vfs(VfsMessage::ReadFileReq {
                    req_id: 1,
                    path: outside.to_string_lossy().into_owned(),
                    offset: 0,
                    len: 64,
                }))
                .into(),
            ))
            .await
            .unwrap();
        assert!(matches!(
            next_vfs_response(&mut socket, 1).await,
            VfsMessage::VfsError {
                req_id: 1,
                code: VfsErrorCode::PermissionDenied,
                ..
            }
        ));

        let target = project.join("chunked.txt");
        let target = target.to_string_lossy().into_owned();
        for (req_id, offset, truncate, content) in [
            (2, 0, true, b"chunk-".to_vec()),
            (3, 6, false, b"write".to_vec()),
        ] {
            socket
                .send(WsMessage::Binary(
                    encode(&MultiplexedPacket::Vfs(VfsMessage::WriteFileReq {
                        req_id,
                        path: target.clone(),
                        content,
                        expected_modified_ts: None,
                        truncate,
                        offset,
                    }))
                    .into(),
                ))
                .await
                .unwrap();
            assert!(matches!(
                next_vfs_response(&mut socket, req_id).await,
                VfsMessage::Ack {
                    req_id: received_id,
                    modified_ts: None,
                    trash_id: None,
                } if received_id == req_id
            ));
        }
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::Vfs(VfsMessage::FlushReq {
                    req_id: 4,
                    path: target.clone(),
                }))
                .into(),
            ))
            .await
            .unwrap();
        assert!(matches!(
            next_vfs_response(&mut socket, 4).await,
            VfsMessage::Ack {
                req_id: 4,
                modified_ts: Some(_),
                trash_id: None,
            }
        ));
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::Vfs(VfsMessage::ReadFileReq {
                    req_id: 5,
                    path: target.clone(),
                    offset: 0,
                    len: 32,
                }))
                .into(),
            ))
            .await
            .unwrap();
        match next_vfs_response(&mut socket, 5).await {
            VfsMessage::ReadFileRes {
                content, eof, size, ..
            } => {
                assert_eq!(content, b"chunk-write");
                assert!(eof);
                assert_eq!(size, 11);
            }
            response => panic!("expected VFS read response, got {response:?}"),
        }

        #[cfg(feature = "native-protocol")]
        {
            socket
                .send(WsMessage::Binary(
                    encode(&MultiplexedPacket::Search(SearchMessage::StartReq {
                        req_id: 6,
                        roots: vec![project.to_string_lossy().into_owned()],
                        query: "chunk-write".into(),
                        regex: false,
                        case_sensitive: true,
                        whole_word: false,
                        include: Vec::new(),
                        exclude: Vec::new(),
                        max_results: 10,
                        context_lines: 0,
                    }))
                    .into(),
                ))
                .await
                .unwrap();
            loop {
                match next_search_message(&mut socket, 6).await {
                    SearchMessage::Match {
                        path,
                        preview,
                        line,
                        ..
                    } => {
                        assert_eq!(path, target);
                        assert_eq!(line, 1);
                        assert!(preview.contains("chunk-write"));
                    }
                    SearchMessage::Done {
                        total, truncated, ..
                    } => {
                        assert_eq!(total, 1);
                        assert!(!truncated);
                        break;
                    }
                    SearchMessage::Error { message, .. } => {
                        panic!("unexpected search failure: {message}")
                    }
                    _ => unreachable!("client-only search message received from daemon"),
                }
            }

            for (req_id, op) in [(7, GitOp::Init), (8, GitOp::Status)] {
                socket
                    .send(WsMessage::Binary(
                        encode(&MultiplexedPacket::Git(GitMessage::Req {
                            req_id,
                            repo: project.to_string_lossy().into_owned(),
                            op,
                        }))
                        .into(),
                    ))
                    .await
                    .unwrap();
                match next_git_message(&mut socket, req_id).await {
                    GitMessage::Res { result, .. } if req_id == 7 => {
                        assert!(matches!(result, avf_protocol::GitResult::Success));
                    }
                    GitMessage::Res { result, .. } if req_id == 8 => {
                        assert!(matches!(result, avf_protocol::GitResult::Status { .. }));
                    }
                    GitMessage::Error { message, .. } => panic!("Git request failed: {message}"),
                    other => panic!("unexpected Git response: {other:?}"),
                }
            }

            let git = |args: &[&str]| {
                let output = std::process::Command::new("git")
                    .arg("-C")
                    .arg(&project)
                    .args(args)
                    .output()
                    .unwrap();
                assert!(
                    output.status.success(),
                    "Git setup command failed: {}",
                    String::from_utf8_lossy(&output.stderr)
                );
            };
            git(&["config", "user.name", "Integration Test"]);
            git(&["config", "user.email", "integration@example.invalid"]);
            git(&["add", "main.rs"]);
            git(&["commit", "--quiet", "-m", "initial"]);
            let bare_remote = temp.path().join("origin.git");
            let output = std::process::Command::new("git")
                .args(["init", "--bare", "--quiet"])
                .arg(&bare_remote)
                .output()
                .unwrap();
            assert!(output.status.success());
            git(&["remote", "add", "origin", bare_remote.to_str().unwrap()]);

            for (req_id, op) in [
                (
                    10,
                    GitOp::Push {
                        set_upstream: true,
                        force_with_lease: false,
                    },
                ),
                (11, GitOp::Fetch),
            ] {
                socket
                    .send(WsMessage::Binary(
                        encode(&MultiplexedPacket::Git(GitMessage::Req {
                            req_id,
                            repo: project.to_string_lossy().into_owned(),
                            op,
                        }))
                        .into(),
                    ))
                    .await
                    .unwrap();
                let mut saw_progress = false;
                loop {
                    match next_git_message(&mut socket, req_id).await {
                        GitMessage::Progress { .. } => saw_progress = true,
                        GitMessage::Res { result, .. } => {
                            assert!(matches!(result, avf_protocol::GitResult::Success));
                            assert!(saw_progress);
                            break;
                        }
                        GitMessage::Error { message, .. } => {
                            panic!("Git network request failed: {message}")
                        }
                        other => panic!("unexpected Git network response: {other:?}"),
                    }
                }
            }

            socket
                .send(WsMessage::Binary(
                    encode(&MultiplexedPacket::Vfs(VfsMessage::StatReq {
                        req_id: 9,
                        path: target.clone(),
                    }))
                    .into(),
                ))
                .await
                .unwrap();
            match next_vfs_response(&mut socket, 9).await {
                VfsMessage::StatRes { entry, .. } => {
                    assert_eq!(
                        entry.git_status,
                        Some(avf_protocol::GitFileStatus::Untracked)
                    );
                }
                response => panic!("expected VFS stat response, got {response:?}"),
            }
        }

        socket.close(None).await.unwrap();
        server.abort();
    }

    #[tokio::test]
    async fn websocket_pty_survives_disconnect_and_reattaches_with_history() {
        use tokio::time::{sleep, timeout, Duration};

        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        std::fs::create_dir(&project).unwrap();
        let config = AppConfig {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            token: "a".repeat(64),
            allowed_origins: HashSet::from(["http://localhost:5173".to_owned()]),
            workspace_roots: vec![project.clone()],
            lsp_hibernation_secs: 30 * 60,
            pairing_file: None,
            tls: false,
        };
        let listener = TcpListener::bind(config.bind_addr).await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(listener, app(config).unwrap()).await.unwrap();
        });
        let session_id = uuid::Uuid::new_v4();

        let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
        request
            .headers_mut()
            .insert(WS_ORIGIN, HeaderValue::from_static("http://localhost:5173"));
        let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::System(SystemMessage::Hello {
                    protocol_version: PROTOCOL_VERSION,
                    auth_token: "a".repeat(64),
                    client_name: "pty-test".into(),
                }))
                .into(),
            ))
            .await
            .unwrap();
        let welcome = timeout(Duration::from_secs(3), socket.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        let WsMessage::Binary(bytes) = welcome else {
            panic!("expected binary Welcome");
        };
        assert!(matches!(
            decode(&bytes).unwrap(),
            MultiplexedPacket::System(SystemMessage::Welcome { pty_sessions, .. })
                if pty_sessions.is_empty()
        ));

        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::Pty {
                    session_id,
                    data: PtyPayload::Spawn {
                        shell: Some("/bin/sh".to_owned()),
                        cwd: Some(project.to_string_lossy().into_owned()),
                        cols: 80,
                        rows: 24,
                    },
                })
                .into(),
            ))
            .await
            .unwrap();
        loop {
            let response = timeout(Duration::from_secs(5), socket.next())
                .await
                .unwrap()
                .unwrap()
                .unwrap();
            let WsMessage::Binary(bytes) = response else {
                panic!("expected binary PTY response");
            };
            match decode(&bytes).unwrap() {
                MultiplexedPacket::Pty {
                    session_id: received_id,
                    data: PtyPayload::Spawned { .. },
                } if received_id == session_id => break,
                MultiplexedPacket::Pty {
                    data: PtyPayload::Error { message },
                    ..
                } => panic!("PTY spawn failed: {message}"),
                _ => {}
            }
        }
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::Pty {
                    session_id,
                    data: PtyPayload::Input(b"printf 'ws-reattach-ready\\n'\n".to_vec()),
                })
                .into(),
            ))
            .await
            .unwrap();
        let mut first_output = Vec::new();
        timeout(Duration::from_secs(5), async {
            loop {
                let response = socket.next().await.unwrap().unwrap();
                let WsMessage::Binary(bytes) = response else {
                    continue;
                };
                if let MultiplexedPacket::Pty {
                    data: PtyPayload::Output(bytes),
                    ..
                } = decode(&bytes).unwrap()
                {
                    first_output.extend(bytes);
                    if first_output
                        .windows(b"ws-reattach-ready".len())
                        .any(|window| window == b"ws-reattach-ready")
                    {
                        break;
                    }
                }
            }
        })
        .await
        .expect("timed out waiting for shell output");

        socket.close(None).await.unwrap();
        sleep(Duration::from_millis(50)).await;

        let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
        request
            .headers_mut()
            .insert(WS_ORIGIN, HeaderValue::from_static("http://localhost:5173"));
        let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::System(SystemMessage::Hello {
                    protocol_version: PROTOCOL_VERSION,
                    auth_token: "a".repeat(64),
                    client_name: "pty-reconnect-test".into(),
                }))
                .into(),
            ))
            .await
            .unwrap();
        let welcome = timeout(Duration::from_secs(3), socket.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        let WsMessage::Binary(bytes) = welcome else {
            panic!("expected binary Welcome");
        };
        assert!(matches!(
            decode(&bytes).unwrap(),
            MultiplexedPacket::System(SystemMessage::Welcome { pty_sessions, .. })
                if pty_sessions.iter().any(|info| info.session_id == session_id)
        ));

        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::Pty {
                    session_id,
                    data: PtyPayload::Reattach,
                })
                .into(),
            ))
            .await
            .unwrap();
        timeout(Duration::from_secs(3), async {
            loop {
                let frame = socket.next().await.unwrap().unwrap();
                let WsMessage::Binary(bytes) = frame else {
                    continue;
                };
                if matches!(
                    decode(&bytes).unwrap(),
                    MultiplexedPacket::Pty {
                        session_id: received_id,
                        data: PtyPayload::Output(output),
                    } if received_id == session_id
                        && output.windows(b"ws-reattach-ready".len())
                            .any(|window| window == b"ws-reattach-ready")
                ) {
                    break;
                }
            }
        })
        .await
        .expect("timed out waiting for PTY history");

        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::Pty {
                    session_id,
                    data: PtyPayload::Kill,
                })
                .into(),
            ))
            .await
            .unwrap();
        timeout(Duration::from_secs(5), async {
            loop {
                let response = socket.next().await.unwrap().unwrap();
                let WsMessage::Binary(bytes) = response else {
                    continue;
                };
                if matches!(
                    decode(&bytes).unwrap(),
                    MultiplexedPacket::Pty {
                        session_id: received_id,
                        data: PtyPayload::Exit { .. },
                    } if received_id == session_id
                ) {
                    break;
                }
            }
        })
        .await
        .expect("PTY process did not report exit after Kill");
        server.abort();
    }

    #[tokio::test]
    async fn pairing_code_trades_for_the_token_once() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        std::fs::create_dir(&project).unwrap();
        let pairing_file = temp.path().join("pairing.json");
        let code = pairing::create(&pairing_file, pairing::PAIRING_TTL).unwrap();
        let config = AppConfig {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            token: "c".repeat(64),
            allowed_origins: HashSet::from(["avf-native-android://localhost".to_owned()]),
            workspace_roots: vec![project],
            lsp_hibernation_secs: 30 * 60,
            pairing_file: Some(pairing_file),
            tls: false,
        };
        let listener = TcpListener::bind(config.bind_addr).await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(listener, app(config).unwrap()).await.unwrap();
        });
        let exchange = |first: SystemMessage| async move {
            let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
            request
                .headers_mut()
                .insert(WS_ORIGIN, HeaderValue::from_static("avf-native-android://localhost"));
            let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
            socket
                .send(WsMessage::Binary(encode(&MultiplexedPacket::System(first)).into()))
                .await
                .unwrap();
            let WsMessage::Binary(bytes) = socket.next().await.unwrap().unwrap() else {
                panic!("expected a binary reply");
            };
            decode(&bytes).unwrap()
        };
        let pair = |code: &str| SystemMessage::Pair {
            protocol_version: PROTOCOL_VERSION,
            code: code.to_owned(),
            client_name: "test".into(),
        };

        assert!(matches!(
            exchange(pair("AAAA-AAAA")).await,
            MultiplexedPacket::System(SystemMessage::Rejected {
                reason: RejectReason::PairingFailed { attempts_left: 4 }
            })
        ));
        let MultiplexedPacket::System(SystemMessage::Paired { token }) = exchange(pair(&code)).await else {
            panic!("the right code should pair");
        };
        assert_eq!(token, "c".repeat(64));
        assert!(matches!(
            exchange(pair(&code)).await,
            MultiplexedPacket::System(SystemMessage::Rejected {
                reason: RejectReason::PairingUnavailable
            })
        ));
        assert!(matches!(
            exchange(SystemMessage::Hello {
                protocol_version: PROTOCOL_VERSION,
                auth_token: token,
                client_name: "test".into(),
            })
            .await,
            MultiplexedPacket::System(SystemMessage::Welcome { .. })
        ));
        server.abort();
    }

    #[tokio::test]
    async fn websocket_rejects_bad_token_and_disallowed_origin() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        std::fs::create_dir(&project).unwrap();
        let config = AppConfig {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            token: "a".repeat(64),
            allowed_origins: HashSet::from([
                "http://localhost:5173".to_owned(),
                "avf-native-android://localhost".to_owned(),
            ]),
            workspace_roots: vec![project],
            lsp_hibernation_secs: 30 * 60,
            pairing_file: None,
            tls: false,
        };
        let listener = TcpListener::bind(config.bind_addr).await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(listener, app(config).unwrap()).await.unwrap();
        });

        let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
        request
            .headers_mut()
            .insert(WS_ORIGIN, HeaderValue::from_static("http://evil.example"));
        assert!(tokio_tungstenite::connect_async(request).await.is_err());

        let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
        request
            .headers_mut()
            .insert(WS_ORIGIN, HeaderValue::from_static("http://localhost:5173"));
        let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::System(SystemMessage::Hello {
                    protocol_version: PROTOCOL_VERSION,
                    auth_token: "b".repeat(64),
                    client_name: "test".into(),
                }))
                .into(),
            ))
            .await
            .unwrap();
        let response = socket.next().await.unwrap().unwrap();
        let WsMessage::Binary(bytes) = response else {
            panic!("expected binary rejection");
        };
        assert!(matches!(
            decode(&bytes).unwrap(),
            MultiplexedPacket::System(SystemMessage::Rejected {
                reason: RejectReason::InvalidToken
            })
        ));

        let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
        request
            .headers_mut()
            .insert(WS_ORIGIN, HeaderValue::from_static("http://localhost:5173"));
        let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::System(SystemMessage::Ping {
                    timestamp: 1,
                }))
                .into(),
            ))
            .await
            .unwrap();
        let response = socket.next().await.unwrap().unwrap();
        let WsMessage::Binary(bytes) = response else {
            panic!("expected a protocol error for an out-of-order Hello");
        };
        assert!(matches!(
            decode(&bytes).unwrap(),
            MultiplexedPacket::System(SystemMessage::ProtocolError { .. })
        ));

        let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
        request
            .headers_mut()
            .insert(WS_ORIGIN, HeaderValue::from_static("http://localhost:5173"));
        let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::System(SystemMessage::Hello {
                    protocol_version: PROTOCOL_VERSION + 1,
                    auth_token: "a".repeat(64),
                    client_name: "test".into(),
                }))
                .into(),
            ))
            .await
            .unwrap();
        let response = socket.next().await.unwrap().unwrap();
        let WsMessage::Binary(bytes) = response else {
            panic!("expected a protocol version rejection");
        };
        assert!(matches!(
            decode(&bytes).unwrap(),
            MultiplexedPacket::System(SystemMessage::Rejected {
                reason: RejectReason::VersionMismatch { .. }
            })
        ));

        let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
        request.headers_mut().insert(
            WS_ORIGIN,
            HeaderValue::from_static("avf-native-android://localhost"),
        );
        let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
        socket
            .send(WsMessage::Binary(
                encode(&MultiplexedPacket::System(SystemMessage::Hello {
                    protocol_version: PROTOCOL_VERSION,
                    auth_token: "a".repeat(64),
                    client_name: "native-android-test".into(),
                }))
                .into(),
            ))
            .await
            .unwrap();
        let response = socket.next().await.unwrap().unwrap();
        let WsMessage::Binary(bytes) = response else {
            panic!("expected binary Welcome");
        };
        assert!(matches!(
            decode(&bytes).unwrap(),
            MultiplexedPacket::System(SystemMessage::Welcome { .. })
        ));

        server.abort();
    }

    #[tokio::test]
    async fn watcher_reports_changes_and_stops_when_unwatched() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        std::fs::create_dir(&project).unwrap();
        let vfs = Vfs::new(std::slice::from_ref(&project)).unwrap();
        let (outbound, mut outgoing) = mpsc::channel(OUTBOUND_CAPACITY);
        let (key, watch) = start_watch(&vfs, &project.to_string_lossy(), true, outbound.clone())
            .await
            .unwrap();

        tokio::fs::write(project.join("created.txt"), b"content")
            .await
            .unwrap();
        let event = timeout(Duration::from_secs(3), outgoing.recv())
            .await
            .unwrap()
            .unwrap();
        assert!(matches!(
            event,
            OutboundFrame::Packet(MultiplexedPacket::Vfs(VfsMessage::FsEvent {
                kind: FsEventKind::Create | FsEventKind::Modify,
                paths,
            })) if paths.iter().any(|path| path.ends_with("created.txt"))
        ));

        drop(watch);
        tokio::time::sleep(WATCH_DEBOUNCE * 2).await;
        while outgoing.try_recv().is_ok() {}
        tokio::fs::write(project.join("after-unwatch.txt"), b"content")
            .await
            .unwrap();
        assert!(timeout(WATCH_DEBOUNCE * 4, outgoing.recv()).await.is_err());
        assert!(key.ends_with("project"));
    }

    #[tokio::test]
    async fn watcher_overflow_requests_a_rescan() {
        let temp = tempfile::tempdir().unwrap();
        let workspace = temp.path().join("workspace");
        std::fs::create_dir(&workspace).unwrap();
        let vfs = Vfs::new(std::slice::from_ref(&workspace)).unwrap();
        let watch_path = workspace.to_string_lossy().into_owned();
        let (outbound, mut outgoing) = mpsc::channel(OUTBOUND_CAPACITY);
        let (_events_sender, events_receiver) = mpsc::channel(WATCH_EVENT_CAPACITY);
        let overflow = Arc::new(AtomicBool::new(true));
        let (cancel_sender, cancel_receiver) = oneshot::channel();
        let task = tokio::spawn(watch_events(
            vfs,
            watch_path.clone(),
            events_receiver,
            overflow,
            outbound,
            cancel_receiver,
        ));

        let event = timeout(Duration::from_secs(1), outgoing.recv())
            .await
            .unwrap()
            .unwrap();
        assert!(matches!(
            event,
            OutboundFrame::Packet(MultiplexedPacket::Vfs(VfsMessage::FsEvent {
                kind: FsEventKind::Rescan,
                paths,
            })) if paths == vec![watch_path]
        ));
        let _ = cancel_sender.send(());
        task.await.unwrap();
    }
}
