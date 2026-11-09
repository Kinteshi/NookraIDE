use avf_protocol::PtySessionInfo;
use portable_pty::{native_pty_system, Child, CommandBuilder, MasterPty, PtySize};
use std::path::Path;
use std::{
    collections::{HashMap, HashSet, VecDeque},
    io::{self, Read, Write},
    path::PathBuf,
    sync::{Arc, Mutex},
};
use tokio::sync::{mpsc, RwLock};
use tracing::{debug, error};
use uuid::Uuid;

const RING_BUFFER_CAPACITY: usize = 64 * 1024;
const OUTPUT_CHUNK_SIZE: usize = 4096;
const PTY_READ_CAPACITY: usize = 16;
const SUBSCRIBER_CAPACITY: usize = 64;

type PtyChild = Box<dyn Child + Send + Sync>;

#[derive(Clone, Default)]
pub struct PtyManager {
    state: Arc<RwLock<ManagerState>>,
}

#[derive(Default)]
struct ManagerState {
    sessions: HashMap<Uuid, Arc<PtySession>>,
    spawning: HashSet<Uuid>,
}

struct PtySession {
    session_id: Uuid,
    info: RwLock<PtySessionInfo>,
    process_id: u32,
    io: Mutex<PtyIo>,
    child: Mutex<PtyChild>,
    state: tokio::sync::Mutex<SessionState>,
}

struct PtyIo {
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
}

#[derive(Default)]
struct SessionState {
    output: VecDeque<u8>,
    subscribers: HashMap<u64, mpsc::Sender<PtyEvent>>,
    next_subscriber_id: u64,
    exit_code: Option<Option<i32>>,
}

#[derive(Debug)]
pub enum PtyEvent {
    Output(Vec<u8>),
    Error(String),
    Exit(Option<i32>),
}

pub struct PtySubscription {
    pub id: u64,
    pub receiver: mpsc::Receiver<PtyEvent>,
}

impl PtyManager {
    #[cfg(test)]
    pub async fn spawn(
        &self,
        session_id: Uuid,
        shell: Option<String>,
        cwd: Option<String>,
        cols: u16,
        rows: u16,
    ) -> Result<(PtySessionInfo, u32), String> {
        self.spawn_with(session_id, shell, Vec::new(), false, cwd, cols, rows)
            .await
    }

    /// Spawns [shell] with [args]; with [integration], bash and zsh start with shell
    /// integration (OSC 133 prompt/command/exit marks, see `shell/`).
    #[allow(clippy::too_many_arguments)]
    pub async fn spawn_with(
        &self,
        session_id: Uuid,
        shell: Option<String>,
        args: Vec<String>,
        integration: bool,
        cwd: Option<String>,
        cols: u16,
        rows: u16,
    ) -> Result<(PtySessionInfo, u32), String> {
        if cols == 0 || rows == 0 {
            return Err("terminal dimensions must be greater than zero".to_owned());
        }

        {
            let mut state = self.state.write().await;
            if state.sessions.contains_key(&session_id) || !state.spawning.insert(session_id) {
                return Err("terminal session ID already exists".to_owned());
            }
        }

        let created =
            tokio::task::spawn_blocking(move || {
                create_session(session_id, shell, args, integration, cwd, cols, rows)
            })
            .await;

        let (session, reader) = match created {
            Ok(Ok(created)) => created,
            Ok(Err(error)) => {
                self.state.write().await.spawning.remove(&session_id);
                return Err(error);
            }
            Err(error) => {
                self.state.write().await.spawning.remove(&session_id);
                return Err(format!("PTY startup task failed: {error}"));
            }
        };
        let process_id = session.process_id;

        {
            let mut state = self.state.write().await;
            state.spawning.remove(&session_id);
            state.sessions.insert(session_id, Arc::clone(&session));
        }

        let manager = self.clone();
        tokio::spawn(run_output(manager, session.clone(), reader));
        let info = session.info.read().await.clone();
        Ok((info, process_id))
    }

    pub async fn attach(&self, session_id: Uuid) -> Result<PtySubscription, String> {
        let session = self.session(session_id).await?;
        let mut state = session.state.lock().await;
        let id = state.next_subscriber_id;
        state.next_subscriber_id = state.next_subscriber_id.wrapping_add(1);

        let (sender, receiver) = mpsc::channel(SUBSCRIBER_CAPACITY);
        if !state.output.is_empty() {
            let history = state.output.iter().copied().collect();
            sender
                .try_send(PtyEvent::Output(history))
                .map_err(|error| format!("could not queue terminal history: {error}"))?;
        }
        if let Some(exit_code) = state.exit_code {
            let _ = sender.try_send(PtyEvent::Exit(exit_code));
        } else {
            state.subscribers.insert(id, sender);
        }
        Ok(PtySubscription { id, receiver })
    }

    pub async fn detach(&self, session_id: Uuid, subscriber_id: u64) {
        if let Ok(session) = self.session(session_id).await {
            session
                .state
                .lock()
                .await
                .subscribers
                .remove(&subscriber_id);
        }
    }

    pub async fn input(&self, session_id: Uuid, data: Vec<u8>) -> Result<(), String> {
        let session = self.session(session_id).await?;
        tokio::task::spawn_blocking(move || {
            let mut io = session
                .io
                .lock()
                .map_err(|_| "PTY I/O lock is poisoned".to_owned())?;
            io.writer
                .write_all(&data)
                .map_err(|error| format!("could not write to terminal: {error}"))
        })
        .await
        .map_err(|error| format!("PTY input task failed: {error}"))?
    }

    pub async fn resize(&self, session_id: Uuid, cols: u16, rows: u16) -> Result<(), String> {
        if cols == 0 || rows == 0 {
            return Err("terminal dimensions must be greater than zero".to_owned());
        }
        let session = self.session(session_id).await?;
        let session_for_resize = Arc::clone(&session);
        tokio::task::spawn_blocking(move || {
            let io = session_for_resize
                .io
                .lock()
                .map_err(|_| "PTY I/O lock is poisoned".to_owned())?;
            io.master
                .resize(PtySize {
                    rows,
                    cols,
                    pixel_width: 0,
                    pixel_height: 0,
                })
                .map_err(|error| format!("could not resize terminal: {error}"))
        })
        .await
        .map_err(|error| format!("PTY resize task failed: {error}"))??;
        let mut info = session.info.write().await;
        info.cols = cols;
        info.rows = rows;
        Ok(())
    }

    pub async fn kill(&self, session_id: Uuid) -> Result<(), String> {
        let session = self.session(session_id).await?;
        tokio::task::spawn_blocking(move || {
            let mut child = session
                .child
                .lock()
                .map_err(|_| "PTY child lock is poisoned".to_owned())?;
            child
                .kill()
                .map_err(|error| format!("could not terminate terminal process: {error}"))
        })
        .await
        .map_err(|error| format!("PTY kill task failed: {error}"))?
    }

    pub async fn sessions(&self) -> Vec<PtySessionInfo> {
        let sessions = self
            .state
            .read()
            .await
            .sessions
            .values()
            .cloned()
            .collect::<Vec<_>>();
        let mut info = Vec::with_capacity(sessions.len());
        for session in sessions {
            info.push(session.info.read().await.clone());
        }
        info
    }

    async fn session(&self, session_id: Uuid) -> Result<Arc<PtySession>, String> {
        self.state
            .read()
            .await
            .sessions
            .get(&session_id)
            .cloned()
            .ok_or_else(|| "unknown terminal session".to_owned())
    }

    async fn remove_if_current(&self, session_id: Uuid, expected: &Arc<PtySession>) {
        let mut state = self.state.write().await;
        if state
            .sessions
            .get(&session_id)
            .is_some_and(|session| Arc::ptr_eq(session, expected))
        {
            state.sessions.remove(&session_id);
        }
    }
}

const BASH_INTEGRATION: &str = include_str!("shell/integration.bash");
const ZSH_ENV: &str = include_str!("shell/zshenv.zsh");
const ZSH_RC: &str = include_str!("shell/zshrc.zsh");

/// Writes the shell integration scripts (once per daemon version) and returns their folder.
fn integration_dir() -> Result<PathBuf, String> {
    let base = std::env::var_os("HOME")
        .map(|home| PathBuf::from(home).join(".cache"))
        .unwrap_or_else(std::env::temp_dir);
    let dir = base.join("quirecode").join("shell-integration");
    let zsh = dir.join("zsh");
    std::fs::create_dir_all(&zsh)
        .map_err(|error| format!("could not create {}: {error}", zsh.display()))?;
    for (path, content) in [
        (dir.join("integration.bash"), BASH_INTEGRATION),
        (zsh.join(".zshenv"), ZSH_ENV),
        (zsh.join(".zshrc"), ZSH_RC),
    ] {
        if std::fs::read_to_string(&path).ok().as_deref() != Some(content) {
            std::fs::write(&path, content)
                .map_err(|error| format!("could not write {}: {error}", path.display()))?;
        }
    }
    Ok(dir)
}

fn create_session(
    session_id: Uuid,
    shell: Option<String>,
    args: Vec<String>,
    integration: bool,
    cwd: Option<String>,
    cols: u16,
    rows: u16,
) -> Result<(Arc<PtySession>, Box<dyn Read + Send>), String> {
    let shell = shell
        .filter(|value| !value.is_empty())
        .or_else(|| {
            std::env::var("SHELL")
                .ok()
                .filter(|value| !value.is_empty())
        })
        .unwrap_or_else(|| "/bin/bash".to_owned());
    if shell.contains('\0') {
        return Err("shell path contains a NUL byte".to_owned());
    }
    let cwd = match cwd {
        Some(cwd) if !cwd.is_empty() => PathBuf::from(cwd),
        _ => std::env::current_dir()
            .map_err(|error| format!("could not determine default terminal directory: {error}"))?,
    };
    if !cwd.is_dir() {
        return Err(format!(
            "terminal working directory is not a directory: {}",
            cwd.display()
        ));
    }

    let pty_system = native_pty_system();
    let pair = pty_system
        .openpty(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|error| format!("could not create PTY: {error}"))?;

    let reader = pair
        .master
        .try_clone_reader()
        .map_err(|error| format!("could not open PTY reader: {error}"))?;
    let writer = pair
        .master
        .take_writer()
        .map_err(|error| format!("could not open PTY writer: {error}"))?;

    if args.iter().any(|arg| arg.contains('\0')) {
        return Err("shell arguments contain a NUL byte".to_owned());
    }
    let mut command = CommandBuilder::new(shell.clone());
    let program = Path::new(&shell)
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default();
    // Shell integration only for a plain interactive bash or zsh (a profile's own arguments,
    // tmux say, start something else).
    let integrated = integration && args.is_empty() && matches!(program.as_str(), "bash" | "zsh");
    if integrated {
        match integration_dir() {
            Ok(dir) if program == "bash" => {
                command.arg("--init-file");
                command.arg(dir.join("integration.bash"));
            }
            Ok(dir) => {
                let user = std::env::var("ZDOTDIR")
                    .ok()
                    .filter(|value| !value.is_empty())
                    .or_else(|| std::env::var("HOME").ok())
                    .unwrap_or_default();
                command.env("__AVF_USER_ZDOTDIR", user);
                command.env("ZDOTDIR", dir.join("zsh"));
            }
            Err(message) => debug!(%session_id, message, "no shell integration"),
        }
    }
    command.args(&args);
    command.cwd(&cwd);
    command.env("TERM", "xterm-256color");
    command.env("COLORTERM", "truecolor");
    let mut child = pair
        .slave
        .spawn_command(command)
        .map_err(|error| format!("could not start shell: {error}"))?;
    let process_id = match child.process_id() {
        Some(process_id) => process_id,
        None => {
            let _ = child.kill();
            return Err("could not determine terminal process ID".to_owned());
        }
    };
    drop(pair.slave);

    let info = PtySessionInfo {
        session_id,
        shell,
        cwd: cwd.to_string_lossy().into_owned(),
        cols,
        rows,
    };
    let session = Arc::new(PtySession {
        session_id,
        info: RwLock::new(info),
        process_id,
        io: Mutex::new(PtyIo {
            master: pair.master,
            writer,
        }),
        child: Mutex::new(child),
        state: tokio::sync::Mutex::new(SessionState {
            next_subscriber_id: 1,
            ..SessionState::default()
        }),
    });
    debug!(%session_id, process_id, "started PTY session");
    Ok((session, reader))
}

async fn run_output(
    manager: PtyManager,
    session: Arc<PtySession>,
    mut reader: Box<dyn Read + Send>,
) {
    let (output_sender, mut output_receiver) = mpsc::channel(PTY_READ_CAPACITY);
    let reader_task = tokio::task::spawn_blocking(move || {
        let mut buffer = vec![0; OUTPUT_CHUNK_SIZE];
        loop {
            match reader.read(&mut buffer) {
                Ok(0) => break,
                Ok(read) => {
                    if output_sender
                        .blocking_send(buffer[..read].to_vec())
                        .is_err()
                    {
                        break;
                    }
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                // Linux PTYs report EIO when the final slave descriptor closes.
                Err(error) if error.raw_os_error() == Some(5) => break,
                Err(error) => {
                    return Some(format!("PTY output reader failed: {error}"));
                }
            }
        }
        None
    });

    while let Some(output) = output_receiver.recv().await {
        let mut state = session.state.lock().await;
        append_to_ring(&mut state.output, &output);
        let mut disconnected = Vec::new();
        for (subscriber_id, sender) in &state.subscribers {
            if sender.try_send(PtyEvent::Output(output.clone())).is_err() {
                disconnected.push(*subscriber_id);
            }
        }
        for subscriber_id in disconnected {
            state.subscribers.remove(&subscriber_id);
        }
    }

    let reader_error = match reader_task.await {
        Ok(error) => error,
        Err(error) => Some(format!("PTY output reader task failed: {error}")),
    };
    if let Some(error) = &reader_error {
        error!(%error, "stopping PTY after output reader failure");
        let session_to_kill = Arc::clone(&session);
        if let Err(error) = tokio::task::spawn_blocking(move || {
            let mut child = session_to_kill
                .child
                .lock()
                .map_err(|_| "PTY child lock is poisoned".to_owned())?;
            child
                .kill()
                .map_err(|error| format!("could not stop PTY after reader failure: {error}"))
        })
        .await
        .map_err(|error| format!("PTY cleanup task failed: {error}"))
        .and_then(|result| result)
        {
            error!(%error, "failed to stop PTY after reader failure");
        }
    }
    let session_for_wait = Arc::clone(&session);
    let exit_code = match tokio::task::spawn_blocking(move || {
        let mut child = session_for_wait
            .child
            .lock()
            .map_err(|_| "PTY child lock is poisoned".to_owned())?;
        child
            .wait()
            .map(|status| {
                if status.signal().is_none() {
                    i32::try_from(status.exit_code()).ok()
                } else {
                    None
                }
            })
            .map_err(|error| format!("could not wait for terminal process: {error}"))
    })
    .await
    {
        Ok(Ok(code)) => code,
        Ok(Err(error)) => {
            error!(%error, "PTY process wait failed");
            None
        }
        Err(error) => {
            error!(%error, "PTY process wait task failed");
            None
        }
    };

    {
        let mut state = session.state.lock().await;
        state.exit_code = Some(exit_code);
        for (_, sender) in state.subscribers.drain() {
            if let Some(message) = &reader_error {
                let _ = sender.try_send(PtyEvent::Error(message.clone()));
            }
            let _ = sender.try_send(PtyEvent::Exit(exit_code));
        }
    }
    manager
        .remove_if_current(session.session_id, &session)
        .await;
}

fn append_to_ring(ring: &mut VecDeque<u8>, output: &[u8]) {
    if output.len() >= RING_BUFFER_CAPACITY {
        ring.clear();
        ring.extend(&output[output.len() - RING_BUFFER_CAPACITY..]);
        return;
    }
    let overflow = ring
        .len()
        .saturating_add(output.len())
        .saturating_sub(RING_BUFFER_CAPACITY);
    ring.drain(..overflow);
    ring.extend(output);
}

impl Drop for PtySession {
    fn drop(&mut self) {
        match self.child.lock() {
            Ok(mut child) => {
                if let Err(error) = child.kill() {
                    debug!(%error, session_id = %self.session_id, "PTY child already stopped");
                }
            }
            Err(_) => {
                error!(session_id = %self.session_id, "PTY child lock poisoned during shutdown")
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::time::{timeout, Duration};

    #[test]
    fn ring_buffer_keeps_only_the_last_64_kib() {
        let mut ring = VecDeque::new();
        let first = vec![b'a'; RING_BUFFER_CAPACITY - 3];
        append_to_ring(&mut ring, &first);
        append_to_ring(&mut ring, b"bcdef");

        assert_eq!(ring.len(), RING_BUFFER_CAPACITY);
        assert_eq!(&ring.iter().take(3).copied().collect::<Vec<_>>(), b"aaa");
        assert_eq!(
            &ring.iter().rev().take(5).copied().collect::<Vec<_>>(),
            b"fedcb"
        );
    }

    #[tokio::test]
    async fn session_can_spawn_receive_input_resize_and_exit() {
        let manager = PtyManager::default();
        let session_id = Uuid::new_v4();
        let (info, _) = manager
            .spawn(
                session_id,
                Some("/bin/sh".to_owned()),
                Some(std::env::temp_dir().to_string_lossy().into_owned()),
                80,
                24,
            )
            .await
            .unwrap();
        assert_eq!(info.session_id, session_id);

        let mut subscription = manager.attach(session_id).await.unwrap();
        manager.resize(session_id, 100, 30).await.unwrap();
        assert!(manager
            .sessions()
            .await
            .iter()
            .any(|info| info.session_id == session_id && info.cols == 100 && info.rows == 30));
        manager
            .input(session_id, b"printf 'pty-ready\\n'\n".to_vec())
            .await
            .unwrap();

        let mut output = Vec::new();
        timeout(Duration::from_secs(5), async {
            loop {
                match subscription.receiver.recv().await {
                    Some(PtyEvent::Output(bytes)) => {
                        output.extend(bytes);
                        if output
                            .windows(b"pty-ready".len())
                            .any(|w| w == b"pty-ready")
                        {
                            break;
                        }
                    }
                    Some(PtyEvent::Error(message)) => panic!("PTY reader failed: {message}"),
                    Some(PtyEvent::Exit(_)) => panic!("shell exited before command output"),
                    None => panic!("PTY output stream closed unexpectedly"),
                }
            }
        })
        .await
        .expect("timed out waiting for shell output");

        manager.kill(session_id).await.unwrap();
        timeout(Duration::from_secs(5), async {
            loop {
                if !manager
                    .sessions()
                    .await
                    .iter()
                    .any(|item| item.session_id == session_id)
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("PTY session did not exit after kill");
    }

    #[tokio::test]
    async fn bash_integration_marks_prompts_commands_and_exit_status() {
        if !Path::new("/bin/bash").exists() {
            return;
        }
        let manager = PtyManager::default();
        let session_id = Uuid::new_v4();
        manager
            .spawn_with(
                session_id,
                Some("/bin/bash".to_owned()),
                Vec::new(),
                true,
                Some(std::env::temp_dir().to_string_lossy().into_owned()),
                80,
                24,
            )
            .await
            .unwrap();
        let mut subscription = manager.attach(session_id).await.unwrap();
        manager
            .input(session_id, b"false\nprintf 'marks-%s\\n' done\n".to_vec())
            .await
            .unwrap();
        let mut output = Vec::new();
        let contains = |output: &[u8], needle: &[u8]| output.windows(needle.len()).any(|w| w == needle);
        timeout(Duration::from_secs(10), async {
            loop {
                match subscription.receiver.recv().await {
                    Some(PtyEvent::Output(bytes)) => {
                        output.extend(bytes);
                        if contains(&output, b"marks-done") && contains(&output, b"\x1b]133;D;0\x07") {
                            break;
                        }
                    }
                    Some(PtyEvent::Exit(_)) | None => panic!("bash ended: {}", String::from_utf8_lossy(&output)),
                    Some(PtyEvent::Error(message)) => panic!("PTY reader failed: {message}"),
                }
            }
        })
        .await
        .unwrap_or_else(|_| panic!("no marks in: {}", String::from_utf8_lossy(&output)));
        assert!(contains(&output, b"\x1b]133;A\x07"));
        assert!(contains(&output, b"\x1b]133;C\x07\x1b]633;E;false\x07"));
        assert!(contains(&output, b"\x1b]133;D;1\x07"));
        manager.kill(session_id).await.unwrap();
    }

    #[tokio::test]
    async fn reattach_replays_recent_output_before_live_output() {
        let manager = PtyManager::default();
        let session_id = Uuid::new_v4();
        manager
            .spawn(
                session_id,
                Some("/bin/sh".to_owned()),
                Some(std::env::temp_dir().to_string_lossy().into_owned()),
                80,
                24,
            )
            .await
            .unwrap();

        let mut initial = manager.attach(session_id).await.unwrap();
        manager
            .input(session_id, b"printf 'reattach-check\\n'\n".to_vec())
            .await
            .unwrap();
        let mut output = Vec::new();
        timeout(Duration::from_secs(5), async {
            loop {
                if let Some(PtyEvent::Output(bytes)) = initial.receiver.recv().await {
                    output.extend(bytes);
                    if output
                        .windows(b"reattach-check".len())
                        .any(|window| window == b"reattach-check")
                    {
                        break;
                    }
                }
            }
        })
        .await
        .expect("timed out waiting for first PTY output");
        manager.detach(session_id, initial.id).await;

        let mut reattached = manager.attach(session_id).await.unwrap();
        let history = timeout(Duration::from_secs(2), reattached.receiver.recv())
            .await
            .expect("timed out waiting for PTY history")
            .expect("PTY history was not replayed");
        assert!(
            matches!(history, PtyEvent::Output(bytes) if bytes.windows(b"reattach-check".len()).any(|w| w == b"reattach-check"))
        );

        manager.kill(session_id).await.unwrap();
    }
}
