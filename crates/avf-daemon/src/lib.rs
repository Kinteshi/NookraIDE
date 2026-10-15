mod pty;
mod vfs;

use avf_protocol::{
    decode, encode, FsEventKind, MultiplexedPacket, PtyPayload, RejectReason, SystemMessage,
    VfsErrorCode, VfsMessage, MAX_FRAME_SIZE, PROTOCOL_VERSION,
};
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
use notify::{
    event::{ModifyKind, RenameMode},
    Event, EventKind, RecursiveMode, Watcher,
};
use pty::{PtyEvent, PtyManager};
use serde::Serialize;
use std::{
    collections::{HashMap, HashSet},
    env,
    error::Error,
    io,
    net::SocketAddr,
    path::{Path, PathBuf},
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
use tracing::{debug, error, warn};
use vfs::{Vfs, VfsFailure};

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const OUTBOUND_CAPACITY: usize = 128;
const WATCH_EVENT_CAPACITY: usize = 128;
const WATCH_DEBOUNCE: Duration = Duration::from_millis(150);

#[derive(Clone)]
pub struct AppConfig {
    pub bind_addr: SocketAddr,
    pub token: String,
    pub allowed_origins: HashSet<String>,
    pub workspace_roots: Vec<PathBuf>,
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
        if !bind_addr.ip().is_loopback() {
            return Err("AVF_BIND_ADDR must use a loopback address".into());
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

        Ok(Self {
            bind_addr,
            token,
            allowed_origins,
            workspace_roots: roots,
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
    telemetry: Arc<Telemetry>,
}

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
    protocol_version: u32,
}

pub async fn serve(config: AppConfig) -> io::Result<()> {
    if !config.bind_addr.ip().is_loopback() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "AVF Studio daemon may only bind to a loopback address",
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
    let listener = TcpListener::bind(config.bind_addr).await?;
    let app = app(config)?;
    axum::serve(listener, app).await
}

fn app(config: AppConfig) -> io::Result<Router> {
    let vfs = Vfs::new(&config.workspace_roots)?;
    let state = AppState {
        config: Arc::new(config),
        vfs,
        pty: PtyManager::default(),
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
    let MultiplexedPacket::System(SystemMessage::Hello {
        protocol_version,
        auth_token,
        ..
    }) = packet
    else {
        send_protocol_error(&mut socket, "Hello must be the first packet").await;
        return;
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
    let mut watches = HashMap::<String, WatchHandle>::new();
    let mut pty_forwarders = HashMap::new();
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
        }
    }
    drop(watches);
    for (session_id, (subscriber_id, task)) in pty_forwarders.drain() {
        task.abort();
        state.pty.detach(session_id, subscriber_id).await;
    }
    drop(outbound);
    if let Err(error) = timeout(Duration::from_secs(1), &mut writer).await {
        warn!(%error, "websocket writer did not stop promptly");
    }
}

enum OutboundFrame {
    Packet(MultiplexedPacket),
    Control(Message),
}

async fn process_packet(
    packet: MultiplexedPacket,
    state: &AppState,
    outbound: &mpsc::Sender<OutboundFrame>,
    watches: &mut HashMap<String, WatchHandle>,
    pty_forwarders: &mut HashMap<uuid::Uuid, (u64, tokio::task::JoinHandle<()>)>,
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
        MultiplexedPacket::Vfs(VfsMessage::ListDirReq { req_id, path }) => {
            let response = match state.vfs.list_dir(path).await {
                Ok(entries) => VfsMessage::ListDirRes { req_id, entries },
                Err(error) => vfs_error(req_id, error),
            };
            send_outbound(outbound, MultiplexedPacket::Vfs(response)).await
        }
        MultiplexedPacket::Vfs(VfsMessage::ReadFileReq { req_id, path }) => {
            let response = match state.vfs.read_file(path).await {
                Ok((content, modified_ts)) => VfsMessage::ReadFileRes {
                    req_id,
                    content,
                    modified_ts,
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
        }) => {
            let response = match state
                .vfs
                .write_file(path, content, expected_modified_ts)
                .await
            {
                Ok(modified_ts) => VfsMessage::WriteFileRes {
                    req_id,
                    modified_ts,
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
        _ => {
            warn!("client sent a packet type not supported by the daemon");
            let _ = send_protocol_error_outbound(outbound, "packet type is not supported").await;
            Err(())
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
    watch_path: String,
    mut events: mpsc::Receiver<notify::Result<Event>>,
    overflow: Arc<AtomicBool>,
    outbound: mpsc::Sender<OutboundFrame>,
    mut cancel: oneshot::Receiver<()>,
) {
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
                    if send_watch_event(&outbound, FsEventKind::Rescan, vec![watch_path.clone()]).await.is_err() {
                        break;
                    }
                } else {
                    for (kind, paths) in pending.drain() {
                        let mut paths = paths.into_iter().collect::<Vec<_>>();
                        paths.sort();
                        if send_watch_event(&outbound, kind, paths).await.is_err() {
                            return;
                        }
                    }
                    for paths in pending_renames.drain(..) {
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
}

impl Telemetry {
    async fn sample(&self) -> io::Result<SystemSample> {
        let stat = tokio::fs::read_to_string("/proc/stat").await?;
        let meminfo = tokio::fs::read_to_string("/proc/meminfo").await?;
        let (total, idle) = parse_cpu_stat(&stat).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "missing aggregate CPU row")
        })?;
        let (mem_total, mem_available) = parse_meminfo(&meminfo).ok_or_else(|| {
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
        Ok(SystemSample {
            cpu_usage,
            mem_used_mb: mem_total.saturating_sub(mem_available) / mebibyte,
            mem_total_mb: mem_total / mebibyte,
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

fn parse_meminfo(contents: &str) -> Option<(u64, u64)> {
    let mut total_kib = None;
    let mut available_kib = None;
    let mut free_kib = None;
    for line in contents.lines() {
        let (name, value) = line.split_once(':')?;
        let value = value.split_whitespace().next()?.parse::<u64>().ok()?;
        match name {
            "MemTotal" => total_kib = Some(value),
            "MemAvailable" => available_kib = Some(value),
            "MemFree" => free_kib = Some(value),
            _ => {}
        }
    }
    let total = total_kib?.checked_mul(1024)?;
    let available = available_kib.or(free_kib)?.checked_mul(1024)?;
    Some((total, available.min(total)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use avf_protocol::{decode, encode, MultiplexedPacket, PtyPayload, SystemMessage, VfsMessage};
    use axum::http::HeaderValue;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_tungstenite::tungstenite::{
        client::IntoClientRequest, http::header::ORIGIN as WS_ORIGIN,
        protocol::Message as WsMessage,
    };

    #[test]
    fn parses_cpu_and_memory_proc_data() {
        assert_eq!(
            parse_cpu_stat("cpu 10 2 3 40 5 1 1 0 0 0\n"),
            Some((62, 45))
        );
        assert_eq!(
            parse_meminfo("MemTotal: 2048 kB\nMemAvailable: 1024 kB\n"),
            Some((2 * 1024 * 1024, 1024 * 1024))
        );
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
        assert!(response.contains(r#"{"status":"ok","protocol_version":1}"#));
        server.abort();
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
                }))
                .into(),
            ))
            .await
            .unwrap();
        let response = socket.next().await.unwrap().unwrap();
        let WsMessage::Binary(bytes) = response else {
            panic!("expected binary VFS response");
        };
        assert!(matches!(
            decode(&bytes).unwrap(),
            MultiplexedPacket::Vfs(VfsMessage::VfsError {
                req_id: 1,
                code: VfsErrorCode::PermissionDenied,
                ..
            })
        ));

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
        let history = timeout(Duration::from_secs(3), socket.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        let WsMessage::Binary(bytes) = history else {
            panic!("expected binary PTY history");
        };
        assert!(matches!(
            decode(&bytes).unwrap(),
            MultiplexedPacket::Pty {
                session_id: received_id,
                data: PtyPayload::Output(output),
            } if received_id == session_id
                && output.windows(b"ws-reattach-ready".len())
                    .any(|window| window == b"ws-reattach-ready")
        ));

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
    async fn websocket_rejects_bad_token_and_disallowed_origin() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        std::fs::create_dir(&project).unwrap();
        let config = AppConfig {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            token: "a".repeat(64),
            allowed_origins: HashSet::from(["http://localhost:5173".to_owned()]),
            workspace_roots: vec![project],
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
        let (outbound, mut outgoing) = mpsc::channel(OUTBOUND_CAPACITY);
        let (_events_sender, events_receiver) = mpsc::channel(WATCH_EVENT_CAPACITY);
        let overflow = Arc::new(AtomicBool::new(true));
        let (cancel_sender, cancel_receiver) = oneshot::channel();
        let task = tokio::spawn(watch_events(
            "/workspace".to_owned(),
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
            })) if paths == vec!["/workspace"]
        ));
        let _ = cancel_sender.send(());
        task.await.unwrap();
    }
}
