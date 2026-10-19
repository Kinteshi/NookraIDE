use crate::vfs::Vfs;
use avf_protocol::SearchMessage;
use std::{
    collections::VecDeque,
    io,
    path::{Path, PathBuf},
    process::Stdio,
    time::Instant,
};
use tokio::{
    io::{AsyncBufRead, AsyncBufReadExt, BufReader},
    process::Command,
    sync::mpsc,
    task::JoinHandle,
};

const MAX_RESULTS: u32 = 100_000;
const MAX_JSON_EVENT_BYTES: usize = 1024 * 1024;
const MAX_PREVIEW_CHARS: usize = 2_048;
const MAX_CONTEXT_CHARS: usize = 2_048;
const MAX_QUERY_BYTES: usize = 16 * 1024;
const MAX_GLOB_BYTES: usize = 4 * 1024;
const MAX_GLOBS_PER_KIND: usize = 128;
const MAX_RIPGREP_COLUMNS: usize = 8_192;
const MAX_CONTEXT_LINES: u8 = 64;

/// Validate the request synchronously, then return a cancellable streaming task.
///
/// The caller should forward an `Error` message to the peer if this function
/// returns `Err`. Aborting or dropping the returned task cancels ripgrep; the
/// child is configured with `kill_on_drop`.
pub(crate) fn start(
    vfs: Vfs,
    request: SearchMessage,
    outbound: mpsc::Sender<SearchMessage>,
) -> Result<(u64, JoinHandle<()>), String> {
    let config = SearchConfig::new(&vfs, request)?;
    let req_id = config.req_id;
    let task = tokio::spawn(run_search(config, outbound));
    Ok((req_id, task))
}

struct SearchConfig {
    req_id: u64,
    roots: Vec<PathBuf>,
    query: String,
    regex: bool,
    case_sensitive: bool,
    whole_word: bool,
    include: Vec<String>,
    exclude: Vec<String>,
    max_results: u32,
    context_lines: u8,
}

impl SearchConfig {
    fn new(vfs: &Vfs, request: SearchMessage) -> Result<Self, String> {
        let SearchMessage::StartReq {
            req_id,
            roots,
            query,
            regex,
            case_sensitive,
            whole_word,
            include,
            exclude,
            max_results,
            context_lines,
        } = request
        else {
            return Err("Search start requires a StartReq message.".to_owned());
        };

        if roots.is_empty() {
            return Err("At least one search root is required.".to_owned());
        }
        if query.is_empty() || query.len() > MAX_QUERY_BYTES || query.contains('\0') {
            return Err(format!(
                "Search query must be non-empty and at most {MAX_QUERY_BYTES} bytes."
            ));
        }
        if max_results == 0 || max_results > MAX_RESULTS {
            return Err(format!("max_results must be between 1 and {MAX_RESULTS}."));
        }
        if context_lines > MAX_CONTEXT_LINES {
            return Err(format!(
                "context_lines must not exceed {MAX_CONTEXT_LINES}."
            ));
        }
        validate_globs("include", &include)?;
        validate_globs("exclude", &exclude)?;

        let mut validated_roots = Vec::with_capacity(roots.len());
        for requested in roots {
            let resolved = vfs.resolve(&requested).map_err(|failure| {
                format!("Invalid search root '{requested}': {}", failure.message)
            })?;
            let metadata = std::fs::metadata(&resolved.absolute).map_err(|error| {
                format!(
                    "Cannot access search root '{}': {error}",
                    resolved.absolute.display()
                )
            })?;
            if !metadata.is_dir() {
                return Err(format!(
                    "Search root is not a directory: {}",
                    resolved.absolute.display()
                ));
            }

            // Do not let a lexically in-root path that traverses a symlink turn
            // into a ripgrep root outside the configured workspace.
            let canonical = std::fs::canonicalize(&resolved.absolute).map_err(|error| {
                format!(
                    "Cannot canonicalize search root '{}': {error}",
                    resolved.absolute.display()
                )
            })?;
            if canonical != resolved.absolute {
                return Err(format!(
                    "Search roots may not contain symlinks: {}",
                    resolved.absolute.display()
                ));
            }
            validated_roots.push(canonical);
        }
        validated_roots.sort();
        validated_roots.dedup();

        Ok(Self {
            req_id,
            roots: validated_roots,
            query,
            regex,
            case_sensitive,
            whole_word,
            include,
            exclude,
            max_results,
            context_lines,
        })
    }
}

fn validate_globs(kind: &str, globs: &[String]) -> Result<(), String> {
    if globs.len() > MAX_GLOBS_PER_KIND {
        return Err(format!(
            "At most {MAX_GLOBS_PER_KIND} {kind} globs are allowed."
        ));
    }
    for glob in globs {
        if glob.is_empty()
            || glob.len() > MAX_GLOB_BYTES
            || glob.contains('\0')
            || glob.starts_with('!')
        {
            return Err(format!(
                "Invalid {kind} glob; globs must be non-empty, at most {MAX_GLOB_BYTES} bytes, and must not start with '!'."
            ));
        }
    }
    Ok(())
}

fn make_command(config: &SearchConfig) -> Command {
    let mut command = Command::new("rg");
    command
        .arg("--json")
        .arg("--line-number")
        .arg("--column")
        .arg("--no-heading")
        .arg("--color=never")
        .arg("--no-follow")
        .arg(format!("--max-columns={MAX_RIPGREP_COLUMNS}"))
        .arg("--max-columns-preview");

    if config.regex {
        command.arg("--regexp");
    } else {
        command.arg("--fixed-strings");
    }
    command.arg(&config.query);
    if config.case_sensitive {
        command.arg("--case-sensitive");
    } else {
        command.arg("--ignore-case");
    }
    if config.whole_word {
        command.arg("--word-regexp");
    }
    if config.context_lines > 0 {
        command
            .arg("--context")
            .arg(config.context_lines.to_string());
    }
    for glob in &config.include {
        command.arg("--glob").arg(glob);
    }
    for glob in &config.exclude {
        command.arg("--glob").arg(format!("!{glob}"));
    }
    command.arg("--");
    for root in &config.roots {
        command.arg(root);
    }

    command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    command
}

async fn run_search(config: SearchConfig, outbound: mpsc::Sender<SearchMessage>) {
    let started = Instant::now();
    let req_id = config.req_id;
    let result = run_ripgrep(&config, &outbound).await;

    match result {
        Ok((total, truncated)) => {
            let _ = outbound
                .send(SearchMessage::Done {
                    req_id,
                    total,
                    truncated,
                    elapsed_ms: elapsed_ms(started),
                })
                .await;
        }
        Err(message) => {
            let _ = outbound
                .send(SearchMessage::Error { req_id, message })
                .await;
        }
    }
}

async fn run_ripgrep(
    config: &SearchConfig,
    outbound: &mpsc::Sender<SearchMessage>,
) -> Result<(u32, bool), String> {
    let mut child = make_command(config)
        .spawn()
        .map_err(|error| format!("Could not start ripgrep: {error}"))?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| "Could not capture ripgrep output.".to_owned())?;
    let mut reader = BufReader::new(stdout);
    let mut line = Vec::with_capacity(4096);
    let mut stream = MatchStream::new(config.context_lines as usize);
    let mut total = 0u32;
    let mut seen = 0u32;
    let mut truncated = false;

    loop {
        let has_line = read_bounded_line(&mut reader, &mut line)
            .await
            .map_err(|error| format!("Could not read ripgrep output: {error}"))?;
        if !has_line {
            break;
        }
        let event: serde_json::Value = serde_json::from_slice(&line)
            .map_err(|error| format!("Could not parse ripgrep JSON output: {error}"))?;
        line.clear();

        let Some(kind) = event.get("type").and_then(serde_json::Value::as_str) else {
            continue;
        };
        if kind == "match" {
            if seen == config.max_results {
                truncated = true;
                break;
            }
            seen += 1;
        }

        let finalized = stream.consume(&event, config)?;
        for message in finalized {
            if !send_match(outbound, message, &mut total).await {
                let _ = child.start_kill();
                let _ = child.wait().await;
                return Ok((total, false));
            }
        }
    }

    if truncated {
        let _ = child.start_kill();
    }
    for message in stream.finish() {
        if !send_match(outbound, message, &mut total).await {
            let _ = child.start_kill();
            let _ = child.wait().await;
            return Ok((total, truncated));
        }
    }

    let status = child
        .wait()
        .await
        .map_err(|error| format!("Could not wait for ripgrep: {error}"))?;
    if !status.success() && !truncated && status.code() != Some(1) {
        return Err(match status.code() {
            Some(code) => format!("Ripgrep exited with status {code}."),
            None => "Ripgrep terminated unexpectedly.".to_owned(),
        });
    }
    Ok((total, truncated))
}

async fn send_match(
    outbound: &mpsc::Sender<SearchMessage>,
    message: PendingMatch,
    total: &mut u32,
) -> bool {
    if outbound
        .send(SearchMessage::Match {
            req_id: message.req_id,
            path: message.path,
            line: message.line,
            col: message.col,
            len: message.len,
            preview: message.preview,
            context_before: message.context_before,
            context_after: message.context_after,
        })
        .await
        .is_err()
    {
        return false;
    }
    *total = total.saturating_add(1);
    true
}

struct ContextLine {
    number: u32,
    text: String,
}

struct PendingMatch {
    req_id: u64,
    path: String,
    line: u32,
    col: u32,
    len: u32,
    preview: String,
    context_before: Vec<String>,
    context_after: Vec<String>,
}

struct MatchStream {
    req_id: u64,
    context_limit: usize,
    path: Option<String>,
    before: VecDeque<ContextLine>,
    pending: VecDeque<PendingMatch>,
}

impl MatchStream {
    fn new(context_limit: usize) -> Self {
        Self {
            req_id: 0,
            context_limit,
            path: None,
            before: VecDeque::new(),
            pending: VecDeque::new(),
        }
    }

    fn consume(
        &mut self,
        event: &serde_json::Value,
        config: &SearchConfig,
    ) -> Result<Vec<PendingMatch>, String> {
        let kind = event
            .get("type")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default();
        if !matches!(kind, "match" | "context") {
            return Ok(Vec::new());
        }
        let data = event
            .get("data")
            .ok_or_else(|| "Ripgrep event is missing data.".to_owned())?;
        let Some(path) = event_path(data) else {
            return Ok(Vec::new());
        };
        let path = path.to_owned();
        if !path_is_in_roots(&path, &config.roots) {
            return Err(format!(
                "Ripgrep returned a path outside the configured roots: {path}"
            ));
        }

        let mut output = Vec::new();
        if self.path.as_deref().is_some_and(|current| current != path) {
            output.extend(self.flush_pending());
            self.before.clear();
        }
        self.path = Some(path.clone());
        self.req_id = config.req_id;

        let line_number = data
            .get("line_number")
            .and_then(serde_json::Value::as_u64)
            .and_then(|number| u32::try_from(number).ok())
            .ok_or_else(|| "Ripgrep event has an invalid line number.".to_owned())?;
        let text = event_line(data);

        if kind == "context" {
            for pending in &mut self.pending {
                if line_number > pending.line
                    && line_number.saturating_sub(pending.line) <= self.context_limit as u32
                {
                    pending.context_after.push(text.clone());
                }
            }
            if self.context_limit > 0 {
                self.before.push_back(ContextLine {
                    number: line_number,
                    text,
                });
                while self.before.len() > self.context_limit {
                    self.before.pop_front();
                }
            }
        } else {
            let submatch = data
                .get("submatches")
                .and_then(serde_json::Value::as_array)
                .and_then(|matches| matches.first());
            let start = submatch
                .and_then(|item| item.get("start"))
                .and_then(serde_json::Value::as_u64)
                .unwrap_or(0);
            let end = submatch
                .and_then(|item| item.get("end"))
                .and_then(serde_json::Value::as_u64)
                .unwrap_or(start);
            let before = self
                .before
                .iter()
                .filter(|item| {
                    item.number < line_number
                        && line_number.saturating_sub(item.number) <= self.context_limit as u32
                })
                .map(|item| item.text.clone())
                .collect();
            self.pending.push_back(PendingMatch {
                req_id: config.req_id,
                path,
                line: line_number,
                col: start.saturating_add(1).min(u32::MAX as u64) as u32,
                len: end.saturating_sub(start).min(u32::MAX as u64) as u32,
                preview: truncate_chars(&text, MAX_PREVIEW_CHARS),
                context_before: before,
                context_after: Vec::new(),
            });
        }

        while self.pending.front().is_some_and(|pending| {
            self.context_limit == 0
                || line_number.saturating_sub(pending.line) >= self.context_limit as u32
        }) {
            if let Some(pending) = self.pending.pop_front() {
                output.push(pending);
            }
        }
        Ok(output)
    }

    fn finish(&mut self) -> Vec<PendingMatch> {
        self.flush_pending()
    }

    fn flush_pending(&mut self) -> Vec<PendingMatch> {
        self.pending.drain(..).collect()
    }
}

fn event_path(data: &serde_json::Value) -> Option<&str> {
    data.get("path")?.get("text")?.as_str()
}

fn event_line(data: &serde_json::Value) -> String {
    let line = data
        .get("lines")
        .and_then(|lines| lines.get("text"))
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default()
        .trim_end_matches(['\n', '\r']);
    truncate_chars(line, MAX_CONTEXT_CHARS)
}

fn path_is_in_roots(path: &str, roots: &[PathBuf]) -> bool {
    let path = Path::new(path);
    path.is_absolute() && roots.iter().any(|root| path.starts_with(root))
}

fn truncate_chars(text: &str, max_chars: usize) -> String {
    if text.chars().count() <= max_chars {
        return text.to_owned();
    }
    let mut result = text.chars().take(max_chars).collect::<String>();
    result.push('…');
    result
}

async fn read_bounded_line<R>(reader: &mut R, line: &mut Vec<u8>) -> io::Result<bool>
where
    R: AsyncBufRead + Unpin,
{
    loop {
        let (count, newline) = {
            let buffer = reader.fill_buf().await?;
            if buffer.is_empty() {
                return if line.is_empty() { Ok(false) } else { Ok(true) };
            }
            match buffer.iter().position(|byte| *byte == b'\n') {
                Some(index) => (index + 1, true),
                None => (buffer.len(), false),
            }
        };
        if line.len().saturating_add(count) > MAX_JSON_EVENT_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "ripgrep JSON event exceeds the configured size limit",
            ));
        }
        {
            let buffer = reader.fill_buf().await?;
            line.extend_from_slice(&buffer[..count]);
        }
        reader.consume(count);
        if newline {
            if line.last() == Some(&b'\n') {
                line.pop();
            }
            return Ok(true);
        }
    }
}

fn elapsed_ms(started: Instant) -> u32 {
    started.elapsed().as_millis().min(u32::MAX as u128) as u32
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;
    use tokio::time::{timeout, Duration};

    fn request(root: &Path, max_results: u32, context_lines: u8) -> SearchMessage {
        SearchMessage::StartReq {
            req_id: 42,
            roots: vec![root.to_string_lossy().into_owned()],
            query: "needle".to_owned(),
            regex: false,
            case_sensitive: true,
            whole_word: false,
            include: vec![],
            exclude: vec![],
            max_results,
            context_lines,
        }
    }

    #[test]
    fn rejects_roots_outside_vfs_and_invalid_limits() {
        let workspace = tempdir().unwrap();
        let outside = tempdir().unwrap();
        let vfs = Vfs::new(&[workspace.path().to_path_buf()]).unwrap();
        assert!(SearchConfig::new(&vfs, request(outside.path(), 5, 0)).is_err());
        assert!(SearchConfig::new(&vfs, request(workspace.path(), 0, 0)).is_err());
        assert!(SearchConfig::new(&vfs, request(workspace.path(), MAX_RESULTS + 1, 0)).is_err());
    }

    #[test]
    fn rejects_symlink_search_roots() {
        let workspace = tempdir().unwrap();
        let outside = tempdir().unwrap();
        let link = workspace.path().join("linked");
        #[cfg(unix)]
        std::os::unix::fs::symlink(outside.path(), &link).unwrap();
        #[cfg(windows)]
        std::os::windows::fs::symlink_dir(outside.path(), &link).unwrap();
        let vfs = Vfs::new(&[workspace.path().to_path_buf()]).unwrap();
        assert!(SearchConfig::new(&vfs, request(&link, 5, 0)).is_err());
    }

    #[test]
    fn rejects_invalid_globs() {
        assert!(validate_globs("include", &["!secret".to_owned()]).is_err());
        assert!(validate_globs("exclude", &["bad\0glob".to_owned()]).is_err());
        assert!(validate_globs("include", &["".to_owned()]).is_err());
    }

    #[test]
    fn parses_json_match_and_context_events() {
        let mut stream = MatchStream::new(1);
        let config = SearchConfig {
            req_id: 42,
            roots: vec![PathBuf::from("/workspace")],
            query: "needle".into(),
            regex: false,
            case_sensitive: true,
            whole_word: false,
            include: vec![],
            exclude: vec![],
            max_results: 10,
            context_lines: 1,
        };
        let context = serde_json::json!({
            "type": "context",
            "data": {"path": {"text": "/workspace/file.rs"}, "line_number": 2, "lines": {"text": "before\n"}}
        });
        assert!(stream.consume(&context, &config).unwrap().is_empty());
        let matched = serde_json::json!({
            "type": "match",
            "data": {
                "path": {"text": "/workspace/file.rs"},
                "line_number": 3,
                "lines": {"text": "needle line\n"},
                "submatches": [{"start": 0, "end": 6}]
            }
        });
        assert!(stream.consume(&matched, &config).unwrap().is_empty());
        let after = serde_json::json!({
            "type": "context",
            "data": {"path": {"text": "/workspace/file.rs"}, "line_number": 4, "lines": {"text": "after\n"}}
        });
        let result = stream.consume(&after, &config).unwrap();
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].line, 3);
        assert_eq!(result[0].col, 1);
        assert_eq!(result[0].len, 6);
        assert_eq!(result[0].preview, "needle line");
        assert_eq!(result[0].context_before, ["before"]);
        assert_eq!(result[0].context_after, ["after"]);
    }

    #[tokio::test]
    async fn streams_results_and_can_be_cancelled_when_rg_is_available() {
        if tokio::process::Command::new("rg")
            .arg("--version")
            .output()
            .await
            .is_err()
        {
            return;
        }
        let workspace = tempdir().unwrap();
        let file = workspace.path().join("sample.txt");
        tokio::fs::write(&file, "before\nneedle after\nafter\n")
            .await
            .unwrap();
        let vfs = Vfs::new(&[workspace.path().to_path_buf()]).unwrap();
        let (sender, mut receiver) = mpsc::channel(8);
        let (_, task) = start(vfs.clone(), request(workspace.path(), 5, 1), sender).unwrap();
        let mut messages = Vec::new();
        loop {
            let message = timeout(Duration::from_secs(5), receiver.recv())
                .await
                .unwrap()
                .unwrap();
            let done = matches!(message, SearchMessage::Done { .. });
            messages.push(message);
            if done {
                break;
            }
        }
        task.await.unwrap();
        assert!(matches!(
            messages.first(),
            Some(SearchMessage::Match {
                path,
                line: 2,
                context_before,
                context_after,
                ..
            }) if path == file.to_str().unwrap()
                && context_before == &vec!["before".to_owned()]
                && context_after == &vec!["after".to_owned()]
        ));

        tokio::fs::write(&file, "needle\n".repeat(1_000))
            .await
            .unwrap();
        let (sender, mut receiver) = mpsc::channel(1);
        let (_, task) = start(vfs, request(workspace.path(), 5, 0), sender).unwrap();
        timeout(Duration::from_secs(5), receiver.recv())
            .await
            .unwrap()
            .unwrap();
        task.abort();
        assert!(task.await.unwrap_err().is_cancelled());
    }

    #[tokio::test]
    async fn reports_truncation_when_result_limit_is_reached() {
        if tokio::process::Command::new("rg")
            .arg("--version")
            .output()
            .await
            .is_err()
        {
            return;
        }
        let workspace = tempdir().unwrap();
        tokio::fs::write(workspace.path().join("matches.txt"), "needle\nneedle\n")
            .await
            .unwrap();
        let vfs = Vfs::new(&[workspace.path().to_path_buf()]).unwrap();
        let (sender, mut receiver) = mpsc::channel(8);
        let (_, task) = start(vfs, request(workspace.path(), 1, 0), sender).unwrap();
        let mut matches = 0;
        let done = loop {
            match timeout(Duration::from_secs(5), receiver.recv())
                .await
                .unwrap()
                .unwrap()
            {
                SearchMessage::Match { .. } => matches += 1,
                SearchMessage::Done {
                    total, truncated, ..
                } => break (total, truncated),
                SearchMessage::Error { message, .. } => panic!("search failed: {message}"),
                _ => unreachable!("client-only search message received from daemon"),
            }
        };
        task.await.unwrap();
        assert_eq!(matches, 1);
        assert_eq!(done, (1, true));
    }
}
