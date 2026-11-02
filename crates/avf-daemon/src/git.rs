use avf_protocol::{
    GitBranch, GitCommit, GitFileStatus, GitOp, GitRemote, GitResult, GitStatusEntry, StashOp,
};
use std::{
    fs,
    io::{self, Read, Write},
    path::{Component, Path, PathBuf},
    process::{Child, Command, ExitStatus, Stdio},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc,
    },
    thread,
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::UnixListener,
    sync::{mpsc, oneshot},
};
use uuid::Uuid;

use crate::vfs::Vfs;

const COMMAND_TIMEOUT: Duration = Duration::from_secs(12);
const NETWORK_COMMAND_TIMEOUT: Duration = Duration::from_secs(120);
const PROMPT_TIMEOUT: Duration = Duration::from_secs(120);
const OUTPUT_LIMIT: usize = 2 * 1024 * 1024;
const MAX_PATHS: usize = 256;
const MAX_PATH_LENGTH: usize = 4096;
const MAX_COMMIT_MESSAGE_LENGTH: usize = 64 * 1024;
const MAX_PATCH_LENGTH: usize = 4 * 1024 * 1024;
static PATCH_SEQUENCE: AtomicU64 = AtomicU64::new(0);
const MAX_ASKPASS_PROMPT: usize = 4096;
const MAX_ASKPASS_ANSWER: usize = 64 * 1024;
const MAX_CREDENTIAL_HELPERS: usize = 16;

#[derive(Debug)]
pub(crate) struct GitPrompt {
    pub prompt_id: u64,
    pub text: String,
    pub secret: bool,
    pub answer: oneshot::Sender<String>,
}

#[derive(Clone)]
struct Repo {
    absolute: PathBuf,
}

struct CommandOutput {
    status: ExitStatus,
    stdout: Vec<u8>,
    stderr: Vec<u8>,
}

impl Repo {
    fn relative_path(&self, vfs: &Vfs, input: &str) -> Result<String, String> {
        if input.is_empty()
            || input.len() > MAX_PATH_LENGTH
            || input.contains('\0')
            || input.starts_with('-')
        {
            return Err(format!("invalid Git path: {input:?}"));
        }

        let requested = Path::new(input);
        let absolute = if requested.is_absolute() {
            input.to_owned()
        } else {
            if requested.components().any(|component| {
                !matches!(component, Component::Normal(_))
                    || component.as_os_str().to_string_lossy().starts_with('-')
            }) {
                return Err(format!(
                    "Git paths must be repo-relative normalized paths: {input:?}"
                ));
            }
            self.absolute
                .join(requested)
                .to_str()
                .ok_or_else(|| "Git path is not valid UTF-8".to_owned())?
                .to_owned()
        };

        let resolved = vfs
            .resolve(&absolute)
            .map_err(|error| format!("path is not allowed by VFS: {}", error.message))?;
        let relative = resolved
            .absolute
            .strip_prefix(&self.absolute)
            .map_err(|_| format!("Git path is outside the repository: {input:?}"))?;
        if relative.as_os_str().is_empty()
            || relative.components().any(|component| {
                !matches!(component, Component::Normal(_))
                    || component.as_os_str().to_string_lossy().starts_with('-')
            })
        {
            return Err(format!("invalid Git path: {input:?}"));
        }

        // Git does not follow a final symlink when operating on a path, but it
        // does traverse parent directories. Resolve the parent to ensure no
        // such traversal can escape this worktree.
        let parent = resolved
            .absolute
            .parent()
            .ok_or_else(|| format!("invalid Git path: {input:?}"))?;
        let existing_parent = nearest_existing_directory(parent)?;
        if !existing_parent.starts_with(&self.absolute) {
            return Err(format!("Git path is outside the repository: {input:?}"));
        }

        relative
            .to_str()
            .map(str::to_owned)
            .ok_or_else(|| "Git path is not valid UTF-8".to_owned())
    }
}

pub(crate) async fn execute(vfs: &Vfs, repo: &str, op: GitOp) -> Result<GitResult, String> {
    let repo = resolve_repo(vfs, repo)?;

    if let GitOp::Resolve { path, content } = op {
        let relative = repo.relative_path(vfs, &path)?;
        let repo_for_check = repo.clone();
        run_blocking(move || ensure_repository(&repo_for_check)).await??;
        let absolute_file = repo.absolute.join(&relative);
        let requested = absolute_file
            .to_str()
            .ok_or_else(|| "Git path is not valid UTF-8".to_owned())?
            .to_owned();
        vfs.write_file(requested, content, None)
            .await
            .map_err(|error| format!("could not write resolved file: {}", error.message))?;

        return run_blocking(move || {
            git_success(&repo, &["add", "--", &relative], "stage resolved file")
        })
        .await?;
    }

    let vfs = (*vfs).clone();
    run_blocking(move || execute_blocking(vfs, repo, op)).await?
}

pub(crate) async fn execute_network(
    vfs: &Vfs,
    repo_path: &str,
    op: GitOp,
    prompts: mpsc::Sender<GitPrompt>,
) -> Result<GitResult, String> {
    let (repo, args, operation) = match op {
        GitOp::Fetch => {
            let repo = resolve_repo(vfs, repo_path)?;
            ensure_repo_async(repo.clone()).await?;
            (repo, vec!["fetch".into(), "--progress".into()], "fetch")
        }
        GitOp::Pull { rebase } => {
            let repo = resolve_repo(vfs, repo_path)?;
            ensure_repo_async(repo.clone()).await?;
            let mut args = vec!["pull".into(), "--progress".into()];
            if rebase {
                args.push("--rebase".into());
            }
            (repo, args, "pull")
        }
        GitOp::Push {
            set_upstream,
            force_with_lease,
        } => {
            let repo = resolve_repo(vfs, repo_path)?;
            ensure_repo_async(repo.clone()).await?;
            let force_with_lease = set_upstream && force_with_lease;
            let push_args = if set_upstream {
                let push_repo = repo.clone();
                run_blocking(move || build_push_args(&push_repo, force_with_lease)).await??
            } else {
                let mut args = vec!["push".into(), "--progress".into()];
                if force_with_lease {
                    args.push("--force-with-lease".into());
                }
                args
            };
            (repo, push_args, "push")
        }
        GitOp::Clone { url, dest } => {
            validate_remote_url(&url)?;
            let (repo, name) = clone_destination(vfs, repo_path, &dest)?;
            (
                repo,
                vec!["clone".into(), "--progress".into(), "--".into(), url, name],
                "clone",
            )
        }
        _ => return Err("interactive runner only accepts network Git operations".to_owned()),
    };

    let socket_directory = AskpassDirectory(askpass_directory()?);
    let credential_helpers = run_blocking(collect_global_credential_helpers).await??;
    let socket_path = socket_directory.0.join("s");
    let listener = UnixListener::bind(&socket_path)
        .map_err(|error| format!("could not start Git credential prompt listener: {error}"))?;
    let executable = std::env::current_exe()
        .map_err(|error| format!("could not locate the daemon askpass helper: {error}"))?;
    let askpass_executable = socket_directory.0.join("askpass");
    std::os::unix::fs::symlink(&executable, &askpass_executable)
        .map_err(|error| format!("could not prepare the Git askpass helper: {error}"))?;
    let worker_repo = repo.clone();
    let worker_args = args.clone();
    let worker_socket = socket_path.clone();
    let worker_askpass = askpass_executable.clone();
    let worker_helpers = credential_helpers;
    let cancelled = Arc::new(AtomicBool::new(false));
    let _cancellation_guard = NetworkCancellation(cancelled.clone());
    let worker = tokio::task::spawn_blocking(move || {
        run_network_git(
            &worker_repo,
            &worker_args,
            &worker_askpass,
            &worker_socket,
            &worker_helpers,
            operation,
            &cancelled,
        )
    });
    tokio::pin!(worker);

    let result = loop {
        tokio::select! {
            worker_result = &mut worker => {
                break worker_result.map_err(|error| format!("Git worker failed: {error}"))?;
            }
            accepted = listener.accept() => {
                let (mut stream, _) = accepted
                    .map_err(|error| format!("Git credential prompt listener failed: {error}"))?;
                let prompt_text = read_askpass_frame(&mut stream, MAX_ASKPASS_PROMPT).await?;
                let secret = is_secret_prompt(&prompt_text);
                let text = sanitize_prompt(&prompt_text);
                let prompt_id = NEXT_PROMPT_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                let (answer_tx, answer_rx) = oneshot::channel();
                prompts
                    .send(GitPrompt {
                        prompt_id,
                        secret,
                        text,
                        answer: answer_tx,
                    })
                    .await
                    .map_err(|_| "Git prompt receiver is unavailable".to_owned())?;
                let answer = tokio::time::timeout(PROMPT_TIMEOUT, answer_rx)
                    .await
                    .map_err(|_| "Git credential prompt timed out".to_owned())?
                    .map_err(|_| "Git credential prompt was cancelled".to_owned())?;
                if answer.len() > MAX_ASKPASS_ANSWER
                    || answer.bytes().any(|byte| matches!(byte, b'\0' | b'\r' | b'\n'))
                {
                    return Err("Git credential answer is invalid or too long".to_owned());
                }
                write_askpass_frame(&mut stream, answer.as_bytes()).await?;
            }
        }
    };

    match result {
        Ok(output) if output.status.success() => Ok(GitResult::Success),
        Ok(output) => Err(network_command_failure(operation, &output)),
        Err(error) => Err(error),
    }
}

pub(crate) fn run_askpass_helper() -> Result<(), String> {
    use std::{env, os::unix::net::UnixStream};

    let socket_path = env::var_os("AVF_GIT_ASKPASS_SOCKET")
        .ok_or_else(|| "askpass helper is missing its private socket".to_owned())?;
    let prompt = env::args_os()
        .skip(1)
        .last()
        .ok_or_else(|| "askpass helper received no prompt".to_owned())?;
    let prompt = prompt
        .into_string()
        .map_err(|_| "Git credential prompt is not valid UTF-8".to_owned())?;
    if prompt.len() > MAX_ASKPASS_PROMPT {
        return Err("Git credential prompt is too long".to_owned());
    }

    let mut stream = UnixStream::connect(socket_path)
        .map_err(|error| format!("could not connect to the Git prompt listener: {error}"))?;
    write_sync_askpass_frame(&mut stream, prompt.as_bytes())?;
    let answer = read_sync_askpass_frame(&mut stream, MAX_ASKPASS_ANSWER)?;
    if answer.is_empty() {
        return Err("Git credential prompt was cancelled".to_owned());
    }
    io::stdout()
        .write_all(&answer)
        .and_then(|_| io::stdout().write_all(b"\n"))
        .map_err(|error| format!("could not return the Git credential answer: {error}"))
}

static NEXT_PROMPT_ID: AtomicU64 = AtomicU64::new(1);

struct AskpassDirectory(PathBuf);

impl Drop for AskpassDirectory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

struct NetworkCancellation(Arc<AtomicBool>);

impl Drop for NetworkCancellation {
    fn drop(&mut self) {
        self.0.store(true, Ordering::Relaxed);
    }
}

async fn ensure_repo_async(repo: Repo) -> Result<(), String> {
    run_blocking(move || ensure_repository(&repo)).await?
}

fn askpass_directory() -> Result<PathBuf, String> {
    use std::os::unix::fs::PermissionsExt;

    let directory = std::env::temp_dir().join(format!("avf-ga-{}", Uuid::new_v4().simple()));
    fs::create_dir(&directory)
        .map_err(|error| format!("could not create private Git prompt directory: {error}"))?;
    if let Err(error) = fs::set_permissions(&directory, fs::Permissions::from_mode(0o700)) {
        let _ = fs::remove_dir(&directory);
        return Err(format!(
            "could not secure private Git prompt directory: {error}"
        ));
    }
    Ok(directory)
}

fn clone_destination(
    vfs: &Vfs,
    parent_path: &str,
    destination: &str,
) -> Result<(Repo, String), String> {
    let parent = resolve_repo(vfs, parent_path)?;
    let destination = Path::new(destination);
    let name = destination
        .file_name()
        .filter(|_| destination.components().count() == 1)
        .and_then(|name| name.to_str())
        .ok_or_else(|| "clone destination must be a single directory name".to_owned())?;
    if name.is_empty() || name.starts_with('-') || name.len() > MAX_PATH_LENGTH {
        return Err("clone destination directory name is invalid".to_owned());
    }
    let target = parent.absolute.join(name);
    match fs::symlink_metadata(&target) {
        Ok(_) => return Err("clone destination already exists".to_owned()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(format!("could not inspect clone destination: {error}")),
    }
    if target.parent() != Some(parent.absolute.as_path()) {
        return Err("clone destination must be inside its selected workspace directory".to_owned());
    }
    Ok((parent, name.to_owned()))
}

fn validate_remote_url(url: &str) -> Result<(), String> {
    if url.is_empty()
        || url.len() > MAX_PATH_LENGTH
        || url.starts_with('-')
        || url.bytes().any(|byte| byte.is_ascii_control())
        || url.contains('?')
        || url.contains('#')
    {
        return Err("Git remote URL is invalid or too long".to_owned());
    }
    let valid_scheme = ["https://", "ssh://", "git://"]
        .iter()
        .any(|scheme| url.starts_with(scheme));
    let scp_style = !url.contains("://")
        && url
            .split_once(':')
            .is_some_and(|(host, path)| !host.is_empty() && !path.is_empty());
    if !valid_scheme && !scp_style {
        return Err("Git remote URL must use HTTPS, SSH, or the Git protocol".to_owned());
    }
    if let Some((_, authority)) = url.split_once("://") {
        let authority = authority.split('/').next().unwrap_or_default();
        if authority.contains('@') {
            let is_ssh_username = url.starts_with("ssh://")
                && authority
                    .split_once('@')
                    .is_some_and(|(userinfo, _)| !userinfo.contains(':'));
            if !is_ssh_username {
                return Err("Git remote URLs must not embed credentials".to_owned());
            }
        }
    }
    if url.starts_with("ext::") {
        return Err("Git remote helper URLs are not allowed".to_owned());
    }
    Ok(())
}

fn collect_global_credential_helpers() -> Result<Vec<String>, String> {
    let mut command = Command::new("git");
    command
        .args([
            "config",
            "--global",
            "--null",
            "--get-all",
            "credential.helper",
        ])
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env_remove("GIT_CONFIG_COUNT")
        .env_remove("GIT_CONFIG_PARAMETERS")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let output = wait_bounded(command.spawn().map_err(|error| {
        format!("could not inspect configured Git credential helpers: {error}")
    })?)
    .map_err(|error| format!("could not inspect configured Git credential helpers: {error}"))?;
    if output.status.code() == Some(1) && output.stdout.is_empty() {
        return Ok(Vec::new());
    }
    if !output.status.success() || !output.stderr.is_empty() {
        return Err("could not inspect configured Git credential helpers".to_owned());
    }
    if output.stdout.len() > 64 * 1024 {
        return Err("configured Git credential helpers exceed the size limit".to_owned());
    }
    let helpers = output
        .stdout
        .split(|byte| *byte == 0)
        .filter(|value| !value.is_empty())
        .map(|value| {
            String::from_utf8(value.to_vec())
                .map_err(|error| format!("Git credential helper is not valid UTF-8: {error}"))
        })
        .collect::<Result<Vec<_>, _>>()?;
    if helpers.len() > MAX_CREDENTIAL_HELPERS
        || helpers
            .iter()
            .any(|helper| helper.len() > MAX_ASKPASS_ANSWER)
    {
        return Err("configured Git credential helpers exceed the allowed limits".to_owned());
    }
    Ok(helpers)
}

fn build_push_args(repo: &Repo, force_with_lease: bool) -> Result<Vec<String>, String> {
    let branch_output = run_git(repo, &["symbolic-ref", "--quiet", "--short", "HEAD"])?;
    let branch = success_text(branch_output, "current branch")?
        .trim()
        .to_owned();
    if branch.is_empty() || branch.starts_with('-') {
        return Err("Git push requires a named current branch".to_owned());
    }
    let remote_key = format!("branch.{branch}.remote");
    let configured_remote = run_git(repo, &["config", "--get", &remote_key])?;
    let remote = if configured_remote.status.success() {
        success_text(configured_remote, "branch remote")?
            .trim()
            .to_owned()
    } else {
        let remote_output = run_git(repo, &["remote"])?;
        let remotes = success_text(remote_output, "remote list")?;
        let names = remotes.lines().collect::<Vec<_>>();
        if names.contains(&"origin") {
            "origin".to_owned()
        } else if let [only_remote] = names.as_slice() {
            (*only_remote).to_owned()
        } else {
            return Err(
                "Git push cannot choose a remote; configure an upstream or add an origin remote"
                    .to_owned(),
            );
        }
    };
    if remote.is_empty() || remote.contains('\0') {
        return Err("Git push remote name is invalid".to_owned());
    }

    let mut args = vec!["push".into(), "--progress".into(), "--set-upstream".into()];
    if force_with_lease {
        args.push("--force-with-lease".into());
    }
    args.extend(["--".into(), remote, branch]);
    Ok(args)
}

fn run_network_git(
    repo: &Repo,
    args: &[String],
    askpass_executable: &Path,
    socket_path: &Path,
    credential_helpers: &[String],
    operation: &str,
    cancelled: &AtomicBool,
) -> Result<CommandOutput, String> {
    let owned_args = args.iter().map(String::as_str).collect::<Vec<_>>();
    let helper_args = credential_helpers
        .iter()
        .map(|helper| format!("credential.helper={helper}"))
        .collect::<Vec<_>>();
    let mut args = git_base_args();
    args.extend(["-c", "core.sshCommand=ssh", "-c", "credential.helper="]);
    for helper in &helper_args {
        args.extend(["-c", helper.as_str()]);
    }
    args.extend(owned_args);
    let mut command = Command::new("git");
    command
        .arg("-C")
        .arg(&repo.absolute)
        .arg("--literal-pathspecs")
        .args(args)
        .current_dir(&repo.absolute)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_PAGER", "cat")
        .env("GIT_EDITOR", "true")
        .env("GIT_SEQUENCE_EDITOR", "true")
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_ATTR_NOSYSTEM", "1")
        .env("GIT_ASKPASS", askpass_executable)
        .env("SSH_ASKPASS", askpass_executable)
        .env("SSH_ASKPASS_REQUIRE", "force")
        .env("DISPLAY", "avf-studio")
        .env("AVF_GIT_ASKPASS_SOCKET", socket_path)
        .env("GIT_OPTIONAL_LOCKS", "0")
        .env_remove("GIT_DIR")
        .env_remove("GIT_WORK_TREE")
        .env_remove("GIT_INDEX_FILE")
        .env_remove("GIT_COMMON_DIR")
        .env_remove("GIT_OBJECT_DIRECTORY")
        .env_remove("GIT_ALTERNATE_OBJECT_DIRECTORIES")
        .env_remove("GIT_CEILING_DIRECTORIES")
        .env_remove("GIT_DISCOVERY_ACROSS_FILESYSTEM")
        .env_remove("GIT_EXTERNAL_DIFF")
        .env_remove("GIT_DIFF_OPTS")
        .env_remove("GIT_EXEC_PATH")
        .env_remove("GIT_TEMPLATE_DIR")
        .env_remove("GIT_SSH")
        .env_remove("GIT_SSH_COMMAND")
        .env_remove("GIT_PROXY_COMMAND")
        .env_remove("GIT_NAMESPACE")
        .env_remove("GIT_TRACE")
        .env_remove("GIT_TRACE_SETUP")
        .env_remove("GIT_TRACE_PACKET")
        .env_remove("GIT_TRACE_PERFORMANCE")
        .env_remove("GIT_TRACE2")
        .env_remove("GIT_TRACE2_EVENT")
        .env_remove("GIT_TRACE2_PERF")
        .env_remove("GIT_TRACE_CURL")
        .env_remove("GIT_CONFIG_PARAMETERS")
        .env_remove("GIT_CONFIG_COUNT");
    let output = wait_bounded_with_cancel(
        command
            .spawn()
            .map_err(|error| format!("could not start Git {operation}: {error}"))?,
        NETWORK_COMMAND_TIMEOUT,
        cancelled,
    )?;
    if !output.status.success() {
        return Ok(output);
    }
    ensure_output_complete(&output, operation)?;
    Ok(output)
}

fn network_command_failure(operation: &str, output: &CommandOutput) -> String {
    format!(
        "Git {operation} failed ({})",
        output.status.code().map_or_else(
            || "terminated by signal".to_owned(),
            |code| code.to_string()
        )
    )
}

fn is_secret_prompt(prompt: &str) -> bool {
    let prompt = prompt.to_ascii_lowercase();
    [
        "password",
        "passphrase",
        "token",
        "verification code",
        "otp",
    ]
    .iter()
    .any(|needle| prompt.contains(needle))
}

fn sanitize_prompt(prompt: &str) -> String {
    let prompt = prompt
        .chars()
        .filter(|character| !character.is_control())
        .collect::<String>();
    if let Some(separator) = prompt.find("://") {
        let scheme_start = prompt[..separator]
            .rfind(|character: char| {
                !(character.is_ascii_alphanumeric() || matches!(character, '+' | '-' | '.'))
            })
            .map_or(0, |index| index + 1);
        let scheme = &prompt[scheme_start..separator];
        let remainder = &prompt[separator + 3..];
        let authority = remainder
            .split(['/', '\'', '"', ' '])
            .next()
            .unwrap_or_default();
        if authority.contains('@') || remainder.contains('?') {
            return format!("Git credential prompt for {scheme} remote");
        }
    }
    prompt
}

async fn read_askpass_frame(
    stream: &mut tokio::net::UnixStream,
    max_len: usize,
) -> Result<String, String> {
    let mut length = [0_u8; 4];
    stream
        .read_exact(&mut length)
        .await
        .map_err(|error| format!("could not read Git prompt length: {error}"))?;
    let length = u32::from_be_bytes(length) as usize;
    if length > max_len {
        return Err("Git askpass frame exceeds its size limit".to_owned());
    }
    let mut frame = vec![0; length];
    stream
        .read_exact(&mut frame)
        .await
        .map_err(|error| format!("could not read Git prompt: {error}"))?;
    String::from_utf8(frame).map_err(|error| format!("Git prompt is not valid UTF-8: {error}"))
}

async fn write_askpass_frame(
    stream: &mut tokio::net::UnixStream,
    frame: &[u8],
) -> Result<(), String> {
    let length = u32::try_from(frame.len()).map_err(|error| error.to_string())?;
    stream
        .write_all(&length.to_be_bytes())
        .await
        .map_err(|error| format!("could not return Git credential answer: {error}"))?;
    stream
        .write_all(frame)
        .await
        .map_err(|error| format!("could not return Git credential answer: {error}"))
}

fn write_sync_askpass_frame(
    stream: &mut std::os::unix::net::UnixStream,
    frame: &[u8],
) -> Result<(), String> {
    let length = u32::try_from(frame.len()).map_err(|error| error.to_string())?;
    stream
        .write_all(&length.to_be_bytes())
        .and_then(|_| stream.write_all(frame))
        .map_err(|error| format!("could not send Git prompt: {error}"))
}

fn read_sync_askpass_frame(
    stream: &mut std::os::unix::net::UnixStream,
    max_len: usize,
) -> Result<Vec<u8>, String> {
    let mut length = [0_u8; 4];
    stream
        .read_exact(&mut length)
        .map_err(|error| format!("could not read Git answer length: {error}"))?;
    let length = u32::from_be_bytes(length) as usize;
    if length > max_len {
        return Err("Git answer exceeds its size limit".to_owned());
    }
    let mut frame = vec![0; length];
    stream
        .read_exact(&mut frame)
        .map_err(|error| format!("could not read Git answer: {error}"))?;
    Ok(frame)
}

pub(crate) async fn refresh_status_for_paths(vfs: &Vfs, paths: &[String]) -> Result<(), String> {
    let mut repositories = std::collections::HashSet::new();
    for path in paths {
        let resolved = vfs.resolve(path).map_err(|error| {
            format!(
                "cannot refresh Git metadata for '{path}': {}",
                error.message
            )
        })?;
        let root = vfs
            .workspace_root(path)
            .map_err(|error| format!("cannot resolve Git metadata root: {}", error.message))?;
        let is_directory = fs::symlink_metadata(&resolved.absolute)
            .ok()
            .is_some_and(|metadata| metadata.is_dir() && !metadata.file_type().is_symlink());
        let mut candidate = if is_directory {
            resolved.absolute
        } else {
            resolved.absolute.parent().unwrap_or(&root).to_path_buf()
        };
        while candidate.starts_with(&root) {
            let marker = candidate.join(".git");
            if fs::symlink_metadata(&marker)
                .ok()
                .is_some_and(|metadata| !metadata.file_type().is_symlink())
            {
                repositories.insert(candidate);
                break;
            }
            if candidate == root || !candidate.pop() {
                break;
            }
        }
    }

    let mut failures = Vec::new();
    for repository in repositories {
        let repository = repository.to_string_lossy().into_owned();
        if let Err(error) = vfs.cache_git_status(&repository, &[]).await {
            failures.push(format!(
                "cannot clear cached Git metadata for '{repository}': {}",
                error.message
            ));
            continue;
        }
        match execute(vfs, &repository, GitOp::Status).await {
            Ok(GitResult::Status { entries, .. }) => {
                if let Err(error) = vfs.cache_git_status(&repository, &entries).await {
                    failures.push(format!(
                        "cannot refresh cached Git metadata for '{repository}': {}",
                        error.message
                    ));
                }
            }
            Ok(_) => failures.push(format!(
                "Git status returned an unexpected result for '{repository}'"
            )),
            Err(error) => failures.push(error),
        }
    }
    if failures.is_empty() {
        Ok(())
    } else {
        Err(failures.join("; "))
    }
}

fn resolve_repo(vfs: &Vfs, requested: &str) -> Result<Repo, String> {
    let resolved = vfs
        .resolve(requested)
        .map_err(|error| format!("repository path is not allowed by VFS: {}", error.message))?;
    let absolute = fs::canonicalize(&resolved.absolute)
        .map_err(|error| format!("cannot open repository directory: {error}"))?;
    if absolute != resolved.absolute || !absolute.is_dir() {
        return Err("repository must be an existing, non-symlink directory".to_owned());
    }
    Ok(Repo { absolute })
}

async fn run_blocking<T: Send + 'static>(
    operation: impl FnOnce() -> T + Send + 'static,
) -> Result<T, String> {
    tokio::task::spawn_blocking(operation)
        .await
        .map_err(|error| format!("Git worker failed: {error}"))
}

fn execute_blocking(vfs: Vfs, repo: Repo, op: GitOp) -> Result<GitResult, String> {
    if !matches!(&op, GitOp::Init) {
        ensure_repository(&repo)?;
    }
    match op {
        GitOp::Status => status(&repo),
        GitOp::DiffFile { path, staged } => {
            let path = repo.relative_path(&vfs, &path)?;
            diff(&repo, &path, staged)
        }
        GitOp::Stage(paths) => {
            let paths = repo.relative_paths(&vfs, &paths)?;
            git_success(&repo, &with_paths(&["add"], &paths), "stage files")
        }
        GitOp::Unstage(paths) => {
            let paths = repo.relative_paths(&vfs, &paths)?;
            // `restore --staged` needs HEAD; before the first commit, unstaging means dropping the
            // files from the index (they stay on disk).
            let has_head = run_git(&repo, &["rev-parse", "--verify", "--quiet", "HEAD"])
                .is_ok_and(|output| output.status.success());
            let prefix: &[&str] = if has_head {
                &["restore", "--staged"]
            } else {
                &["rm", "--cached", "--quiet", "-r"]
            };
            git_success(&repo, &with_paths(prefix, &paths), "unstage files")
        }
        GitOp::Discard(paths) => {
            let paths = repo.relative_paths(&vfs, &paths)?;
            discard(&repo, &paths)
        }
        GitOp::Commit {
            message,
            amend,
            signoff,
        } => commit(&repo, &message, amend, signoff),
        GitOp::Branches => branches(&repo),
        GitOp::Checkout { name, create } => checkout(&repo, &name, create),
        GitOp::Log { rev, limit, path } => {
            let path = path
                .as_deref()
                .map(|path| repo.relative_path(&vfs, path))
                .transpose()?;
            log(&repo, rev.as_deref(), limit, path.as_deref())
        }
        GitOp::Show { rev, path } => {
            let path = repo.relative_path(&vfs, &path)?;
            show(&repo, &rev, &path)
        }
        GitOp::Fetch | GitOp::Pull { .. } | GitOp::Push { .. } | GitOp::Clone { .. } => {
            Err("network Git operations must use the interactive Git runner".to_owned())
        }
        GitOp::Stash { op } => stash(&repo, op),
        GitOp::Blame { path } => {
            let path = repo.relative_path(&vfs, &path)?;
            let output = run_git(&repo, &["blame", "--line-porcelain", "--", &path])?;
            Ok(GitResult::Text(success_text(output, "blame")?))
        }
        GitOp::Remotes => remotes(&repo),
        GitOp::Init => {
            git_success(&repo, &["init", "--quiet"], "initialize repository")?;
            Ok(GitResult::Success)
        }
        GitOp::MergeStatus => merge_status(&repo),
        GitOp::ApplyPatch {
            patch,
            cached,
            reverse,
        } => apply_patch(&repo, &patch, cached, reverse),
        GitOp::ShowCommit { rev } => show_commit(&repo, &rev),
        GitOp::Resolve { .. } => unreachable!("Resolve is handled before entering the worker"),
    }
}

impl Repo {
    fn relative_paths(&self, vfs: &Vfs, inputs: &[String]) -> Result<Vec<String>, String> {
        if inputs.is_empty() {
            return Err("at least one file path is required".to_owned());
        }
        if inputs.len() > MAX_PATHS {
            return Err(format!("at most {MAX_PATHS} paths may be changed at once"));
        }
        inputs
            .iter()
            .map(|input| self.relative_path(vfs, input))
            .collect()
    }
}

fn nearest_existing_directory(path: &Path) -> Result<PathBuf, String> {
    let mut candidate = path;
    loop {
        match fs::canonicalize(candidate) {
            Ok(canonical) if canonical.is_dir() => return Ok(canonical),
            Ok(_) => {
                return Err(format!(
                    "Git path parent is not a directory: {}",
                    candidate.display()
                ))
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                candidate = candidate
                    .parent()
                    .ok_or_else(|| format!("cannot resolve Git path parent: {}", path.display()))?;
            }
            Err(error) => {
                return Err(format!(
                    "cannot resolve Git path parent {}: {error}",
                    candidate.display()
                ))
            }
        }
    }
}

fn git_args<'a>(args: &'a [&'a str]) -> Vec<&'a str> {
    let mut result = git_base_args();
    result.extend_from_slice(args);
    result
}

fn git_base_args<'a>() -> Vec<&'a str> {
    vec![
        "--no-pager",
        "-c",
        "core.hooksPath=/dev/null",
        "-c",
        "core.fsmonitor=false",
        "-c",
        "core.attributesFile=/dev/null",
        "-c",
        "commit.gpgsign=false",
    ]
}

fn run_git(repo: &Repo, args: &[&str]) -> Result<CommandOutput, String> {
    run_git_pathspecs(repo, args, true)
}

/// `literal_pathspecs` off only for commands that take no paths from the app: `git stash -u`
/// cleans the untracked files it stashed with a `:/` pathspec, which literal pathspecs turn into
/// a file name (the files then stay, and popping the stash fails on them).
fn run_git_pathspecs(repo: &Repo, args: &[&str], literal_pathspecs: bool) -> Result<CommandOutput, String> {
    let args = git_args(args);
    let mut command = Command::new("git");
    command.arg("-C").arg(&repo.absolute);
    if literal_pathspecs {
        command.arg("--literal-pathspecs");
    }
    command
        .args(args)
        .current_dir(&repo.absolute)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_PAGER", "cat")
        .env("GIT_EDITOR", "true")
        .env("GIT_SEQUENCE_EDITOR", "true")
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_ATTR_NOSYSTEM", "1")
        .env("GIT_ASKPASS", "/bin/false")
        .env("SSH_ASKPASS", "/bin/false")
        .env("GIT_OPTIONAL_LOCKS", "0")
        .env_remove("GIT_DIR")
        .env_remove("GIT_WORK_TREE")
        .env_remove("GIT_INDEX_FILE")
        .env_remove("GIT_COMMON_DIR")
        .env_remove("GIT_OBJECT_DIRECTORY")
        .env_remove("GIT_ALTERNATE_OBJECT_DIRECTORIES")
        .env_remove("GIT_CEILING_DIRECTORIES")
        .env_remove("GIT_DISCOVERY_ACROSS_FILESYSTEM")
        .env_remove("GIT_EXTERNAL_DIFF")
        .env_remove("GIT_DIFF_OPTS")
        .env_remove("GIT_EXEC_PATH")
        .env_remove("GIT_TEMPLATE_DIR")
        .env_remove("GIT_SSH")
        .env_remove("GIT_SSH_COMMAND")
        .env_remove("GIT_PROXY_COMMAND")
        .env_remove("GIT_NAMESPACE")
        .env_remove("GIT_TRACE")
        .env_remove("GIT_TRACE_SETUP")
        .env_remove("GIT_TRACE_PACKET")
        .env_remove("GIT_TRACE_PERFORMANCE")
        .env_remove("GIT_TRACE2")
        .env_remove("GIT_TRACE2_EVENT")
        .env_remove("GIT_TRACE2_PERF")
        .env_remove("GIT_TRACE_CURL")
        .env_remove("GIT_CONFIG_PARAMETERS")
        .env_remove("GIT_CONFIG_COUNT");

    let child = command
        .spawn()
        .map_err(|error| format!("could not start Git: {error}"))?;
    wait_bounded(child)
}

fn wait_bounded(child: Child) -> Result<CommandOutput, String> {
    wait_bounded_with_timeout(child, COMMAND_TIMEOUT)
}

fn wait_bounded_with_timeout(
    child: Child,
    command_timeout: Duration,
) -> Result<CommandOutput, String> {
    wait_bounded_inner(child, command_timeout, None)
}

fn wait_bounded_with_cancel(
    child: Child,
    command_timeout: Duration,
    cancelled: &AtomicBool,
) -> Result<CommandOutput, String> {
    wait_bounded_inner(child, command_timeout, Some(cancelled))
}

fn wait_bounded_inner(
    mut child: Child,
    command_timeout: Duration,
    cancelled: Option<&AtomicBool>,
) -> Result<CommandOutput, String> {
    let stdout = child.stdout.take().ok_or("Git stdout pipe unavailable")?;
    let stderr = child.stderr.take().ok_or("Git stderr pipe unavailable")?;
    let stdout_reader = bounded_reader(stdout);
    let stderr_reader = bounded_reader(stderr);
    let start = Instant::now();

    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if cancelled.is_some_and(|flag| flag.load(Ordering::Relaxed)) => {
                let _ = child.kill();
                let _ = child.wait();
                let _ = stdout_reader.join();
                let _ = stderr_reader.join();
                return Err("Git command was cancelled".to_owned());
            }
            Ok(None) if start.elapsed() < command_timeout => {
                thread::sleep(Duration::from_millis(10));
            }
            Ok(None) => {
                let _ = child.kill();
                let _ = child.wait();
                let _ = stdout_reader.join();
                let _ = stderr_reader.join();
                return Err(format!(
                    "Git command exceeded the {} second time limit",
                    command_timeout.as_secs()
                ));
            }
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                let _ = stdout_reader.join();
                let _ = stderr_reader.join();
                return Err(format!("could not wait for Git: {error}"));
            }
        }
    };

    let stdout = stdout_reader
        .join()
        .map_err(|_| "Git stdout reader failed".to_owned())?
        .map_err(|error| format!("could not read Git output: {error}"))?;
    let stderr = stderr_reader
        .join()
        .map_err(|_| "Git stderr reader failed".to_owned())?
        .map_err(|error| format!("could not read Git error output: {error}"))?;
    Ok(CommandOutput {
        status,
        stdout,
        stderr,
    })
}

fn bounded_reader(
    mut reader: impl Read + Send + 'static,
) -> thread::JoinHandle<io::Result<Vec<u8>>> {
    thread::spawn(move || {
        let mut captured = Vec::new();
        let mut buffer = [0_u8; 8192];
        loop {
            let count = reader.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            let remaining = OUTPUT_LIMIT.saturating_sub(captured.len());
            captured.extend_from_slice(&buffer[..count.min(remaining)]);
        }
        Ok(captured)
    })
}

fn success_text(output: CommandOutput, operation: &str) -> Result<String, String> {
    if !output.status.success() {
        return Err(command_failure(operation, &output));
    }
    ensure_output_complete(&output, operation)?;
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

fn command_failure(operation: &str, output: &CommandOutput) -> String {
    let detail = String::from_utf8_lossy(&output.stderr).trim().to_owned();
    let detail = if detail.is_empty() {
        String::from_utf8_lossy(&output.stdout).trim().to_owned()
    } else {
        detail
    };
    let detail = if detail.is_empty() {
        "no diagnostic output".to_owned()
    } else {
        detail
    };
    format!(
        "Git {operation} failed ({}): {detail}",
        output.status.code().map_or_else(
            || "terminated by signal".to_owned(),
            |code| code.to_string()
        )
    )
}

fn git_success(repo: &Repo, args: &[&str], operation: &str) -> Result<GitResult, String> {
    let output = run_git(repo, args)?;
    if !output.status.success() {
        return Err(command_failure(operation, &output));
    }
    ensure_output_complete(&output, operation)?;
    Ok(GitResult::Success)
}

fn ensure_output_complete(output: &CommandOutput, operation: &str) -> Result<(), String> {
    if output.stdout.len() == OUTPUT_LIMIT || output.stderr.len() == OUTPUT_LIMIT {
        return Err(format!(
            "Git {operation} output exceeded the {} MiB limit",
            OUTPUT_LIMIT / (1024 * 1024)
        ));
    }
    Ok(())
}

fn ensure_repository(repo: &Repo) -> Result<(), String> {
    let top = run_git(repo, &["rev-parse", "--show-toplevel"])?;
    let top = success_text(top, "repository lookup")?;
    let top = fs::canonicalize(top.trim())
        .map_err(|error| format!("cannot resolve Git worktree root: {error}"))?;
    if top != repo.absolute {
        return Err(
            "requested directory is inside another repository; select its repository root"
                .to_owned(),
        );
    }

    let git_dir = run_git(repo, &["rev-parse", "--absolute-git-dir"])?;
    let git_dir = success_text(git_dir, "repository lookup")?;
    let git_dir = fs::canonicalize(git_dir.trim())
        .map_err(|error| format!("cannot resolve Git metadata directory: {error}"))?;
    if !git_dir.starts_with(&repo.absolute) {
        return Err("Git metadata is outside this repository; linked or external worktrees are not supported".to_owned());
    }

    let filters = run_git(
        repo,
        &[
            "config",
            "--local",
            "--null",
            "--get-regexp",
            r"^filter\..*\.(clean|smudge|process)$",
        ],
    )?;
    if filters.status.success() && !filters.stdout.is_empty() {
        return Err("this repository configures external Git filters, which the safe Git service will not execute; remove or review those filter commands before continuing".to_owned());
    }
    if !filters.status.success() && filters.status.code() != Some(1) {
        return Err(command_failure("repository filter inspection", &filters));
    }
    Ok(())
}

fn status(repo: &Repo) -> Result<GitResult, String> {
    let output = run_git(
        repo,
        &[
            "status",
            "--porcelain=v2",
            "-z",
            "--branch",
            "--untracked-files=all",
        ],
    )?;
    if !output.status.success() {
        return Err(command_failure("status", &output));
    }
    ensure_output_complete(&output, "status")?;
    let mut branch = None;
    let mut upstream = None;
    let mut ahead = 0;
    let mut behind = 0;
    let mut entries = Vec::new();
    let mut records = output.stdout.split(|byte| *byte == 0).peekable();
    while let Some(record) = records.next() {
        let record = trim_record(record);
        if record.is_empty() {
            continue;
        }
        let text = String::from_utf8_lossy(record);
        if let Some(value) = text.strip_prefix("# branch.head ") {
            if value != "(detached)" && value != "(unknown)" {
                branch = Some(value.to_owned());
            }
        } else if let Some(value) = text.strip_prefix("# branch.upstream ") {
            upstream = Some(value.to_owned());
        } else if let Some(value) = text.strip_prefix("# branch.ab ") {
            let mut values = value.split_ascii_whitespace();
            ahead = values
                .next()
                .and_then(|value| value.strip_prefix('+'))
                .and_then(|value| value.parse().ok())
                .unwrap_or(0);
            behind = values
                .next()
                .and_then(|value| value.strip_prefix('-'))
                .and_then(|value| value.parse().ok())
                .unwrap_or(0);
        } else if let Some(entry) = parse_status_entry(record, &mut records)? {
            entries.push(entry);
        }
    }
    Ok(GitResult::Status {
        branch,
        upstream,
        ahead,
        behind,
        entries,
    })
}

fn trim_record(record: &[u8]) -> &[u8] {
    record.strip_prefix(b"\n").unwrap_or(record)
}

fn parse_status_entry<'a>(
    record: &'a [u8],
    records: &mut impl Iterator<Item = &'a [u8]>,
) -> Result<Option<GitStatusEntry>, String> {
    let text = String::from_utf8_lossy(record);
    if text.starts_with("1 ") {
        let fields = text.splitn(9, ' ').collect::<Vec<_>>();
        if fields.len() != 9 {
            return Err("malformed Git porcelain v2 ordinary record".to_owned());
        }
        let xy = fields[1];
        return Ok(Some(status_entry(fields[8], None, xy)));
    }
    if text.starts_with("2 ") {
        let fields = text.splitn(10, ' ').collect::<Vec<_>>();
        if fields.len() != 10 {
            return Err("malformed Git porcelain v2 rename record".to_owned());
        }
        let original = records
            .next()
            .map(trim_record)
            .map(|path| String::from_utf8_lossy(path).into_owned());
        return Ok(Some(status_entry(fields[9], original, fields[1])));
    }
    if text.starts_with("u ") {
        let fields = text.splitn(11, ' ').collect::<Vec<_>>();
        if fields.len() != 11 {
            return Err("malformed Git porcelain v2 unmerged record".to_owned());
        }
        return Ok(Some(status_entry(fields[10], None, fields[1])));
    }
    if let Some(path) = text.strip_prefix("? ") {
        return Ok(Some(status_entry(path, None, "??")));
    }
    Ok(None)
}

fn status_entry(path: &str, original_path: Option<String>, xy: &str) -> GitStatusEntry {
    let mut chars = xy.chars();
    let index_status = chars.next().unwrap_or(' ').to_string();
    let worktree_status = chars.next().unwrap_or(' ').to_string();
    let status = if index_status == "U"
        || worktree_status == "U"
        || (index_status == "A" && worktree_status == "A")
        || (index_status == "D" && worktree_status == "D")
    {
        GitFileStatus::Conflicted
    } else if xy == "??" {
        GitFileStatus::Untracked
    } else if index_status == "R" || worktree_status == "R" {
        GitFileStatus::Renamed
    } else if index_status == "C" || worktree_status == "C" {
        GitFileStatus::Copied
    } else if index_status == "A" {
        GitFileStatus::Added
    } else if index_status == "D" || worktree_status == "D" {
        GitFileStatus::Deleted
    } else {
        GitFileStatus::Modified
    };
    GitStatusEntry {
        path: path.to_owned(),
        original_path,
        index_status,
        worktree_status,
        status,
    }
}

fn diff(repo: &Repo, path: &str, staged: bool) -> Result<GitResult, String> {
    let mut args = vec![
        "diff",
        "--no-ext-diff",
        "--no-textconv",
        "--no-color",
        "--binary",
    ];
    if staged {
        args.push("--cached");
    }
    args.extend(["--", path]);
    let output = run_git(repo, &args)?;
    let patch = success_text(output, "diff")?;
    Ok(GitResult::Diff {
        path: path.to_owned(),
        staged,
        patch,
    })
}

fn with_paths<'a>(prefix: &[&'a str], paths: &'a [String]) -> Vec<&'a str> {
    let mut args = Vec::with_capacity(prefix.len() + paths.len() + 1);
    args.extend_from_slice(prefix);
    args.push("--");
    args.extend(paths.iter().map(String::as_str));
    args
}

fn discard(repo: &Repo, paths: &[String]) -> Result<GitResult, String> {
    let mut tracked = Vec::new();
    let mut untracked = Vec::new();
    for path in paths {
        let check = run_git(repo, &["ls-files", "--error-unmatch", "-z", "--", path])?;
        if check.status.success() {
            ensure_output_complete(&check, "tracked path lookup")?;
            tracked.push(path.clone());
            continue;
        }
        if check.status.code() != Some(1) {
            return Err(command_failure("tracked path lookup", &check));
        }

        let absolute = repo.absolute.join(path);
        match fs::symlink_metadata(&absolute) {
            Ok(metadata) if metadata.is_dir() => {
                return Err(format!(
                    "discard only accepts files, not directories: {path:?}"
                ))
            }
            Ok(_) => untracked.push(path.clone()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(format!("cannot inspect {path:?}: {error}")),
        }
    }
    if !tracked.is_empty() {
        let args = with_paths(&["restore", "--worktree"], &tracked);
        let output = run_git(repo, &args)?;
        if !output.status.success() {
            return Err(command_failure("discard", &output));
        }
    }
    for path in untracked {
        let output = run_git(repo, &["clean", "--force", "--", &path])?;
        if !output.status.success() {
            return Err(command_failure("discard untracked file", &output));
        }
        ensure_output_complete(&output, "discard untracked file")?;
    }
    Ok(GitResult::Success)
}

fn commit(repo: &Repo, message: &str, amend: bool, signoff: bool) -> Result<GitResult, String> {
    if message.len() > MAX_COMMIT_MESSAGE_LENGTH || message.contains('\0') {
        return Err("commit message is invalid or exceeds 64 KiB".to_owned());
    }
    let mut args = vec!["commit", "--no-gpg-sign", "--no-verify", "-m", message];
    if amend {
        args.push("--amend");
    }
    if signoff {
        args.push("--signoff");
    }
    let output = run_git(repo, &args)?;
    if !output.status.success() {
        return Err(command_failure("commit", &output));
    }
    ensure_output_complete(&output, "commit")?;
    Ok(GitResult::Text(
        String::from_utf8_lossy(&output.stdout).into_owned(),
    ))
}

fn branches(repo: &Repo) -> Result<GitResult, String> {
    let output = run_git(
        repo,
        &[
            "for-each-ref",
            "--format=%(refname:short)%00%(HEAD)%00%(upstream:short)",
            "refs/heads",
        ],
    )?;
    let text = success_text(output, "branch list")?;
    let branches = text
        .lines()
        .filter_map(|line| {
            let mut fields = line.split('\0');
            let name = fields.next()?.trim();
            if name.is_empty() {
                return None;
            }
            let current = fields.next().unwrap_or("").trim() == "*";
            let upstream = fields.next().unwrap_or("").trim();
            Some(GitBranch {
                name: name.to_owned(),
                is_current: current,
                upstream: (!upstream.is_empty()).then(|| upstream.to_owned()),
            })
        })
        .collect();
    Ok(GitResult::Branches(branches))
}

fn checkout(repo: &Repo, name: &str, create: bool) -> Result<GitResult, String> {
    if name.is_empty() || name.starts_with('-') || name.contains('\0') || name.len() > 255 {
        return Err("invalid branch name".to_owned());
    }
    let validation = run_git(repo, &["check-ref-format", "--branch", name])?;
    if !validation.status.success() {
        return Err("invalid branch name".to_owned());
    }
    if create {
        git_success(
            repo,
            &["switch", "--create", name],
            "create and checkout branch",
        )
    } else {
        git_success(repo, &["switch", "--", name], "checkout branch")
    }
}

fn validate_revision(rev: &str) -> Result<(), String> {
    if rev.is_empty() || rev.starts_with('-') || rev.contains('\0') || rev.len() > 1024 {
        return Err("invalid Git revision".to_owned());
    }
    Ok(())
}

fn log(
    repo: &Repo,
    rev: Option<&str>,
    limit: u32,
    path: Option<&str>,
) -> Result<GitResult, String> {
    if !(1..=1000).contains(&limit) {
        return Err("Git log limit must be between 1 and 1000".to_owned());
    }
    if let Some(rev) = rev {
        validate_revision(rev)?;
    }
    let limit = limit.to_string();
    let mut args = vec![
        "log",
        "--no-show-signature",
        "--no-ext-diff",
        "--format=%H%x1f%P%x1f%an%x1f%at%x1f%s",
        "-n",
        &limit,
    ];
    if let Some(rev) = rev {
        args.extend(["--end-of-options", rev]);
    }
    if let Some(path) = path {
        args.extend(["--", path]);
    }
    let output = run_git(repo, &args)?;
    let text = success_text(output, "log")?;
    let mut commits = Vec::new();
    for line in text.lines().filter(|line| !line.is_empty()) {
        let fields = line.splitn(5, '\u{1f}').collect::<Vec<_>>();
        if fields.len() != 5 {
            return Err("malformed Git log output".to_owned());
        }
        let timestamp = fields[3]
            .parse::<i64>()
            .map_err(|_| "malformed timestamp in Git log output".to_owned())?;
        commits.push(GitCommit {
            hash: fields[0].to_owned(),
            parents: fields[1]
                .split_ascii_whitespace()
                .map(str::to_owned)
                .collect(),
            author: fields[2].to_owned(),
            timestamp,
            subject: fields[4].to_owned(),
        });
    }
    Ok(GitResult::Commits(commits))
}

fn show(repo: &Repo, rev: &str, path: &str) -> Result<GitResult, String> {
    validate_revision(rev)?;
    let output = run_git(
        repo,
        &[
            "show",
            "--no-ext-diff",
            "--no-color",
            "--format=fuller",
            "--end-of-options",
            rev,
            "--",
            path,
        ],
    )?;
    Ok(GitResult::Text(success_text(output, "show")?))
}

/// `git apply` of a patch the app cut from a file's diff (one hunk). Git refuses paths outside the
/// worktree and patches that don't apply; the patch goes through a file in the Git directory.
fn apply_patch(repo: &Repo, patch: &str, cached: bool, reverse: bool) -> Result<GitResult, String> {
    if patch.is_empty() || patch.len() > MAX_PATCH_LENGTH || patch.contains('\0') {
        return Err("the patch is empty, too large or not text".to_owned());
    }
    let git_dir = run_git(repo, &["rev-parse", "--absolute-git-dir"])?;
    let git_dir = PathBuf::from(success_text(git_dir, "repository lookup")?.trim());
    let file = git_dir.join(format!(
        "avf-apply-{}-{}.patch",
        std::process::id(),
        PATCH_SEQUENCE.fetch_add(1, Ordering::Relaxed)
    ));
    fs::write(&file, patch).map_err(|error| format!("cannot write the patch: {error}"))?;
    let file_arg = file.to_string_lossy().into_owned();
    let mut args = vec!["apply", "--whitespace=nowarn"];
    if cached {
        args.push("--cached");
    }
    if reverse {
        args.push("--reverse");
    }
    args.push(&file_arg);
    let result = git_success(repo, &args, "apply the change");
    let _ = fs::remove_file(&file);
    result
}

fn show_commit(repo: &Repo, rev: &str) -> Result<GitResult, String> {
    validate_revision(rev)?;
    let output = run_git(
        repo,
        &[
            "show",
            "--no-ext-diff",
            "--no-color",
            "--format=fuller",
            "--stat",
            "--patch",
            "--end-of-options",
            rev,
        ],
    )?;
    Ok(GitResult::Text(success_text(output, "show")?))
}

fn stash(repo: &Repo, op: StashOp) -> Result<GitResult, String> {
    let output = match op {
        StashOp::List => run_git(repo, &["stash", "list"])?,
        StashOp::Push { include_untracked } => {
            let mut args = vec!["stash", "push"];
            if include_untracked {
                args.push("--include-untracked");
            }
            run_git_pathspecs(repo, &args, false)?
        }
        StashOp::Pop { index } => run_stash_index(repo, "pop", index)?,
        StashOp::Apply { index } => run_stash_index(repo, "apply", index)?,
        StashOp::Drop { index } => run_stash_index(repo, "drop", index)?,
    };
    if !output.status.success() {
        return Err(command_failure("stash", &output));
    }
    ensure_output_complete(&output, "stash")?;
    if matches!(op, StashOp::List) {
        Ok(GitResult::Text(
            String::from_utf8_lossy(&output.stdout).into_owned(),
        ))
    } else {
        Ok(GitResult::Success)
    }
}

fn run_stash_index(repo: &Repo, action: &str, index: usize) -> Result<CommandOutput, String> {
    if index > 100_000 {
        return Err("stash index is out of range".to_owned());
    }
    let reference = format!("stash@{{{index}}}");
    run_git_pathspecs(repo, &["stash", action, &reference], false)
}

fn remotes(repo: &Repo) -> Result<GitResult, String> {
    let output = run_git(repo, &["remote", "--verbose"])?;
    let text = success_text(output, "remote list")?;
    let mut remotes = Vec::<GitRemote>::new();
    for line in text.lines() {
        let Some((name, rest)) = line.split_once('\t') else {
            continue;
        };
        let (url, kind) = rest.rsplit_once(" (").unwrap_or((rest, ""));
        let url = Some(url.to_owned());
        let Some(remote) = remotes.iter_mut().find(|remote| remote.name == name) else {
            remotes.push(GitRemote {
                name: name.to_owned(),
                fetch_url: (kind == "fetch)").then(|| url.clone()).flatten(),
                push_url: (kind == "push)").then(|| url.clone()).flatten(),
            });
            continue;
        };
        if kind == "fetch)" {
            remote.fetch_url = url;
        } else if kind == "push)" {
            remote.push_url = url;
        }
    }
    Ok(GitResult::Remotes(remotes))
}

fn merge_status(repo: &Repo) -> Result<GitResult, String> {
    let output = run_git(repo, &["ls-files", "--unmerged", "-z"])?;
    if !output.status.success() {
        return Err(command_failure("merge status", &output));
    }
    ensure_output_complete(&output, "merge status")?;
    let mut paths = Vec::new();
    for record in output
        .stdout
        .split(|byte| *byte == 0)
        .filter(|path| !path.is_empty())
    {
        let text = String::from_utf8_lossy(record);
        if let Some((_, path)) = text.split_once('\t') {
            let path = path.to_owned();
            if !paths.contains(&path) {
                paths.push(path);
            }
        }
    }
    Ok(GitResult::MergeStatus {
        has_conflicts: !paths.is_empty(),
        conflicted_paths: paths,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEST_DIR: AtomicU64 = AtomicU64::new(0);

    #[test]
    fn network_urls_are_restricted_to_safe_remote_schemes() {
        assert!(validate_remote_url("https://example.test/repo.git").is_ok());
        assert!(validate_remote_url("ssh://git@example.test/repo.git").is_ok());
        assert!(validate_remote_url("git@example.test:repo.git").is_ok());
        assert!(validate_remote_url("file:///etc/passwd").is_err());
        assert!(validate_remote_url("ext::sh -c id").is_err());
        assert!(validate_remote_url("https://user:password@example.test/repo.git").is_err());
        assert!(validate_remote_url("-upload-pack=evil").is_err());
    }

    #[test]
    fn prompt_secret_detection_does_not_expose_answer_data() {
        assert!(is_secret_prompt("Password for 'https://example.test':"));
        assert!(is_secret_prompt("Enter passphrase for key"));
        assert!(!is_secret_prompt("Username for 'https://example.test':"));
        assert_eq!(
            sanitize_prompt("Password for 'https://user:token@example.test/repo':"),
            "Git credential prompt for https remote"
        );
        assert_eq!(
            sanitize_prompt("Username for 'https://example.test/repo':\u{1b}[2J"),
            "Username for 'https://example.test/repo':[2J"
        );
    }

    struct TestRepo {
        _workspace: PathBuf,
        root: PathBuf,
        vfs: Vfs,
    }

    struct ChildOnDrop(Child);

    impl Drop for ChildOnDrop {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }

    impl TestRepo {
        fn new() -> Self {
            let workspace = std::env::current_dir()
                .expect("current directory")
                .join(format!(
                    ".git-service-test-{}-{}",
                    std::process::id(),
                    NEXT_TEST_DIR.fetch_add(1, Ordering::Relaxed)
                ));
            fs::create_dir(&workspace).expect("test workspace");
            let root = workspace.join("repo");
            fs::create_dir(&root).expect("repository directory");
            let vfs = Vfs::new(std::slice::from_ref(&workspace)).expect("VFS");
            let repo = Self {
                _workspace: workspace,
                root,
                vfs,
            };
            repo.git(&["init", "--quiet"]);
            repo.git(&["config", "user.name", "Unit Test"]);
            repo.git(&["config", "user.email", "unit@example.invalid"]);
            repo
        }

        fn git(&self, args: &[&str]) {
            let status = Command::new("git")
                .args([
                    "-c",
                    "core.hooksPath=/dev/null",
                    "-c",
                    "commit.gpgsign=false",
                ])
                .arg("-C")
                .arg(&self.root)
                .args(args)
                .env("GIT_CONFIG_NOSYSTEM", "1")
                .env("GIT_CONFIG_GLOBAL", "/dev/null")
                .status()
                .expect("run test Git command");
            assert!(status.success(), "test Git command failed: {args:?}");
        }

        fn git_output(&self, args: &[&str]) -> String {
            let output = Command::new("git")
                .arg("-C")
                .arg(&self.root)
                .args(args)
                .env("GIT_CONFIG_NOSYSTEM", "1")
                .env("GIT_CONFIG_GLOBAL", "/dev/null")
                .output()
                .expect("run test Git command");
            assert!(output.status.success(), "test Git command failed: {args:?}");
            String::from_utf8_lossy(&output.stdout).into_owned()
        }

        fn path(&self) -> String {
            self.root.to_str().expect("UTF-8 test path").to_owned()
        }
    }

    impl Drop for TestRepo {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self._workspace);
        }
    }

    #[tokio::test]
    async fn status_reports_modified_and_untracked_files() {
        let repo = TestRepo::new();
        fs::write(repo.root.join("tracked.txt"), "before\n").unwrap();
        repo.git(&["add", "tracked.txt"]);
        repo.git(&["commit", "--quiet", "-m", "initial"]);
        fs::write(repo.root.join("tracked.txt"), "after\n").unwrap();
        fs::write(repo.root.join("new.txt"), "new\n").unwrap();

        let result = execute(&repo.vfs, &repo.path(), GitOp::Status)
            .await
            .unwrap();
        let GitResult::Status {
            entries, branch, ..
        } = result
        else {
            panic!("expected typed status");
        };
        assert!(branch.is_some());
        assert!(entries.iter().any(|entry| {
            entry.path == "tracked.txt" && entry.status == GitFileStatus::Modified
        }));
        assert!(entries
            .iter()
            .any(|entry| { entry.path == "new.txt" && entry.status == GitFileStatus::Untracked }));
    }

    #[tokio::test]
    async fn unstaging_works_before_the_first_commit() {
        let repo = TestRepo::new();
        fs::write(repo.root.join("new.txt"), "new\n").unwrap();
        let file = repo.root.join("new.txt").to_str().unwrap().to_owned();
        execute(&repo.vfs, &repo.path(), GitOp::Stage(vec![file.clone()]))
            .await
            .unwrap();
        execute(&repo.vfs, &repo.path(), GitOp::Unstage(vec![file]))
            .await
            .unwrap();

        let GitResult::Status {
            entries, branch, ..
        } = execute(&repo.vfs, &repo.path(), GitOp::Status)
            .await
            .unwrap()
        else {
            panic!("expected typed status");
        };
        assert!(branch.is_some(), "an unborn branch still has a name");
        assert!(entries
            .iter()
            .any(|entry| entry.path == "new.txt" && entry.status == GitFileStatus::Untracked));
        assert!(repo.root.join("new.txt").exists());
    }

    #[tokio::test]
    async fn fetch_and_push_use_the_bounded_network_runner() {
        let repo = TestRepo::new();
        let bare_remote = repo._workspace.join("origin.git");
        repo.git(&["init", "--bare", bare_remote.to_str().unwrap()]);
        fs::write(repo.root.join("tracked.txt"), "first\n").unwrap();
        repo.git(&["add", "tracked.txt"]);
        repo.git(&["commit", "--quiet", "-m", "initial"]);
        repo.git(&["remote", "add", "origin", bare_remote.to_str().unwrap()]);
        let (prompt_tx, _prompt_rx) = mpsc::channel(1);
        assert!(matches!(
            execute_network(
                &repo.vfs,
                &repo.path(),
                GitOp::Push {
                    set_upstream: true,
                    force_with_lease: false,
                },
                prompt_tx
            )
            .await
            .unwrap(),
            GitResult::Success
        ));

        fs::write(repo.root.join("tracked.txt"), "second\n").unwrap();
        repo.git(&["commit", "--quiet", "-am", "update"]);
        let (prompt_tx, _prompt_rx) = mpsc::channel(1);
        assert!(matches!(
            execute_network(
                &repo.vfs,
                &repo.path(),
                GitOp::Push {
                    set_upstream: false,
                    force_with_lease: false,
                },
                prompt_tx
            )
            .await
            .unwrap(),
            GitResult::Success
        ));

        let (prompt_tx, _prompt_rx) = mpsc::channel(1);
        assert!(matches!(
            execute_network(&repo.vfs, &repo.path(), GitOp::Fetch, prompt_tx)
                .await
                .unwrap(),
            GitResult::Success
        ));
    }

    #[tokio::test]
    async fn clone_is_confined_to_a_new_workspace_child_directory() {
        use std::net::{TcpListener as StdTcpListener, TcpStream};

        let repo = TestRepo::new();
        fs::write(repo.root.join("tracked.txt"), "clone me\n").unwrap();
        repo.git(&["add", "tracked.txt"]);
        repo.git(&["commit", "--quiet", "-m", "initial"]);
        let bare_remote = repo._workspace.join("origin.git");
        repo.git(&["init", "--bare", bare_remote.to_str().unwrap()]);
        repo.git(&["push", "--quiet", bare_remote.to_str().unwrap(), "HEAD"]);
        let listener = StdTcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);

        let mut daemon = ChildOnDrop(
            Command::new("git")
                .args([
                    "daemon",
                    "--reuseaddr",
                    "--export-all",
                    &format!("--base-path={}", repo._workspace.display()),
                    "--listen=127.0.0.1",
                    &format!("--port={port}"),
                ])
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap(),
        );
        let address = ("127.0.0.1", port);
        let started = (0..100).any(|_| {
            if TcpStream::connect(address).is_ok() {
                true
            } else {
                thread::sleep(Duration::from_millis(10));
                false
            }
        });
        assert!(started, "git daemon did not start");

        let (prompt_tx, _prompt_rx) = mpsc::channel(1);
        let url = format!("git://127.0.0.1:{port}/origin.git");
        let result = execute_network(
            &repo.vfs,
            repo._workspace.to_str().unwrap(),
            GitOp::Clone {
                url,
                dest: "clone".into(),
            },
            prompt_tx,
        )
        .await;
        let _ = daemon.0.kill();
        let _ = daemon.0.wait();
        result.unwrap();
        assert_eq!(
            fs::read(repo._workspace.join("clone/tracked.txt")).unwrap(),
            b"clone me\n"
        );
    }

    #[tokio::test]
    async fn watcher_status_refresh_updates_vfs_file_metadata() {
        let repo = TestRepo::new();
        let file = repo.root.join("new.txt");
        fs::write(&file, "new\n").unwrap();
        refresh_status_for_paths(&repo.vfs, &[file.to_string_lossy().into_owned()])
            .await
            .unwrap();
        let entry = repo
            .vfs
            .stat(file.to_string_lossy().into_owned())
            .await
            .unwrap();
        assert_eq!(entry.git_status, Some(GitFileStatus::Untracked));
    }

    #[tokio::test]
    async fn diff_returns_patch_for_confined_file() {
        let repo = TestRepo::new();
        fs::write(repo.root.join("file.txt"), "one\n").unwrap();
        repo.git(&["add", "file.txt"]);
        repo.git(&["commit", "--quiet", "-m", "initial"]);
        fs::write(repo.root.join("file.txt"), "two\n").unwrap();

        let result = execute(
            &repo.vfs,
            &repo.path(),
            GitOp::DiffFile {
                path: "file.txt".into(),
                staged: false,
            },
        )
        .await
        .unwrap();
        let GitResult::Diff { path, patch, .. } = result else {
            panic!("expected diff");
        };
        assert_eq!(path, "file.txt");
        assert!(patch.contains("-one"));
        assert!(patch.contains("+two"));
    }

    #[tokio::test]
    async fn applies_one_hunk_to_the_index_and_reverts_another() {
        let repo = TestRepo::new();
        let lines = (1..=30).map(|n| format!("line {n}\n")).collect::<String>();
        fs::write(repo.root.join("file.txt"), &lines).unwrap();
        repo.git(&["add", "file.txt"]);
        repo.git(&["commit", "--quiet", "-m", "initial"]);
        fs::write(
            repo.root.join("file.txt"),
            lines.replace("line 2\n", "line two\n").replace("line 28\n", "line twenty-eight\n"),
        )
        .unwrap();
        let GitResult::Diff { patch, .. } = execute(
            &repo.vfs,
            &repo.path(),
            GitOp::DiffFile { path: "file.txt".into(), staged: false },
        )
        .await
        .unwrap() else {
            panic!("expected diff");
        };
        // The file's header and its first hunk only.
        let second = patch.match_indices("\n@@").nth(1).unwrap().0;
        let first_hunk = format!("{}\n", &patch[..second]);
        execute(
            &repo.vfs,
            &repo.path(),
            GitOp::ApplyPatch { patch: first_hunk, cached: true, reverse: false },
        )
        .await
        .unwrap();
        let staged = repo.git_output(&["diff", "--cached"]);
        assert!(staged.contains("+line two") && !staged.contains("twenty-eight"), "{staged}");
        // Discard the second hunk from the working tree.
        let header_end = patch.find("\n@@").unwrap() + 1;
        let second_hunk = format!("{}{}", &patch[..header_end], &patch[second + 1..]);
        execute(
            &repo.vfs,
            &repo.path(),
            GitOp::ApplyPatch { patch: second_hunk, cached: false, reverse: true },
        )
        .await
        .unwrap();
        let file = fs::read_to_string(repo.root.join("file.txt")).unwrap();
        assert!(file.contains("line two") && file.contains("line 28\n"), "{file}");
        let GitResult::Text(shown) = execute(&repo.vfs, &repo.path(), GitOp::ShowCommit { rev: "HEAD".into() })
            .await
            .unwrap() else {
            panic!("expected text");
        };
        assert!(shown.contains("initial") && shown.contains("+line 1"), "{shown}");
        assert!(execute(&repo.vfs, &repo.path(), GitOp::ShowCommit { rev: "--all".into() }).await.is_err());
    }

    #[tokio::test]
    async fn stash_with_untracked_files_pops_back() {
        let repo = TestRepo::new();
        fs::write(repo.root.join("file.txt"), "one\n").unwrap();
        repo.git(&["add", "file.txt"]);
        repo.git(&["commit", "--quiet", "-m", "initial"]);
        fs::write(repo.root.join("file.txt"), "two\n").unwrap();
        fs::create_dir(repo.root.join(".vscode")).unwrap();
        fs::write(repo.root.join(".vscode/new.txt"), "new\n").unwrap();
        execute(&repo.vfs, &repo.path(), GitOp::Stash { op: StashOp::Push { include_untracked: true } })
            .await
            .unwrap();
        assert!(!repo.root.join(".vscode/new.txt").exists());
        assert_eq!(fs::read_to_string(repo.root.join("file.txt")).unwrap(), "one\n");
        execute(&repo.vfs, &repo.path(), GitOp::Stash { op: StashOp::Pop { index: 0 } })
            .await
            .unwrap();
        assert!(repo.root.join(".vscode/new.txt").exists());
        assert_eq!(fs::read_to_string(repo.root.join("file.txt")).unwrap(), "two\n");
    }

    #[tokio::test]
    async fn branches_log_and_checkout_use_typed_results() {
        let repo = TestRepo::new();
        fs::write(repo.root.join("file.txt"), "content\n").unwrap();
        repo.git(&["add", "file.txt"]);
        repo.git(&["commit", "--quiet", "-m", "initial subject"]);

        let GitResult::Branches(branches) = execute(&repo.vfs, &repo.path(), GitOp::Branches)
            .await
            .unwrap()
        else {
            panic!("expected branches");
        };
        assert!(branches.iter().any(|branch| branch.is_current));

        let GitResult::Commits(commits) = execute(
            &repo.vfs,
            &repo.path(),
            GitOp::Log {
                rev: None,
                limit: 10,
                path: None,
            },
        )
        .await
        .unwrap() else {
            panic!("expected commits");
        };
        assert_eq!(commits[0].subject, "initial subject");

        execute(
            &repo.vfs,
            &repo.path(),
            GitOp::Checkout {
                name: "feature-safe".into(),
                create: true,
            },
        )
        .await
        .unwrap();
        let GitResult::Branches(branches) = execute(&repo.vfs, &repo.path(), GitOp::Branches)
            .await
            .unwrap()
        else {
            panic!("expected branches");
        };
        assert!(branches
            .iter()
            .any(|branch| branch.name == "feature-safe" && branch.is_current));
    }

    #[tokio::test]
    async fn rejects_escaping_paths_and_symlinked_parent_escape() {
        let repo = TestRepo::new();
        let outside = repo._workspace.join("outside.txt");
        fs::write(&outside, "secret\n").unwrap();
        let traversal = execute(
            &repo.vfs,
            &repo.path(),
            GitOp::Stage(vec!["../outside.txt".into()]),
        )
        .await;
        assert!(traversal.unwrap_err().contains("normalized"));

        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(&repo._workspace, repo.root.join("external")).unwrap();
            let escaped = execute(
                &repo.vfs,
                &repo.path(),
                GitOp::Stage(vec!["external/outside.txt".into()]),
            )
            .await;
            assert!(escaped.unwrap_err().contains("outside the repository"));
        }
    }

    #[tokio::test]
    async fn rejects_option_like_paths_and_never_interprets_shell_metacharacters() {
        let repo = TestRepo::new();
        let invalid = execute(&repo.vfs, &repo.path(), GitOp::Stage(vec!["--all".into()])).await;
        assert!(invalid.unwrap_err().contains("invalid Git path"));

        let tricky_name = "safe;touch PWNED.txt";
        fs::write(repo.root.join(tricky_name), "content\n").unwrap();
        execute(
            &repo.vfs,
            &repo.path(),
            GitOp::Stage(vec![tricky_name.into()]),
        )
        .await
        .unwrap();
        assert!(!repo.root.join("PWNED.txt").exists());
        let staged = Command::new("git")
            .arg("-C")
            .arg(&repo.root)
            .args(["diff", "--cached", "--name-only"])
            .output()
            .unwrap();
        assert!(String::from_utf8_lossy(&staged.stdout).contains(tricky_name));
    }

    #[tokio::test]
    async fn refuses_repositories_with_executable_filters() {
        let repo = TestRepo::new();
        repo.git(&["config", "filter.guard.clean", "touch FILTER_WAS_RUN"]);
        fs::write(repo.root.join(".gitattributes"), "*.txt filter=guard\n").unwrap();
        fs::write(repo.root.join("input.txt"), "content\n").unwrap();

        let error = execute(
            &repo.vfs,
            &repo.path(),
            GitOp::Stage(vec!["input.txt".into()]),
        )
        .await
        .unwrap_err();
        assert!(error.contains("external Git filters"));
        assert!(!repo.root.join("FILTER_WAS_RUN").exists());
    }
}
