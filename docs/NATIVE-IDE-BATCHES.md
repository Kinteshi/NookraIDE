# Native IDE (Track B): work batches

This is the plan of record for Track B. Each batch ends in one commit on
`agents/reset-and-implement-native-android-ide`, and this file is updated in that commit.
Device procedures, build commands and the details of finished work are in
[NATIVE-IDE-HANDOFF.md](NATIVE-IDE-HANDOFF.md).

Status values: **done** (committed), **in progress**, **planned**.

| # | Batch | Status | Commit |
|---|-------|--------|--------|
| 1 | Workbench rewrite: Material You, terminal, Jupyter, Copilot | done | `1f6c146` |
| 2 | Keyboard routing, file operations, notebook keys, comments | done | `e35af13` |
| 3 | Notebook arrow navigation; Copilot chat references | done (tablet check pending) | `c3903da` |
| 4 | Setup from the app, pairing code, reset detection, resume session | done (tablet check pending) | `a01e688` |
| 5 | Search across files | done (tablet check pending) | `526768d` |
| 6 | Git | done (tablet check pending) | `2566e2e` |
| 7 | Debugging (DAP with debugpy) | done (tablet check pending) | `2746b65` |
| 8 | Daemon hosted on Linux and Windows (WSL2) machines, with a connection guide | done (host install not run) | see `git log` |

## 1. Workbench rewrite (done)
Material You shell, settings page, VS Code shortcuts, ConnectBot terminal over daemon PTYs,
Jupyter notebooks through `avf-jupyter-bridge`, Copilot inline suggestions and ACP chat,
ty + ruff language servers. Details: handoff, sections "Done" and "Verified on the tablet".

## 2. Keyboard routing and files (done)
* Shortcuts route at the window level (`KeyRouter`, before the IME, independent of focus).
* Terminal Ctrl+letter goes to the PTY as a control byte.
* Notebook cell keys are keybindings; focus follows the selected cell, also after a kernel starts.
* New File / New Folder / Rename / Delete in the Explorer; Ctrl+N untitled files and Save As
  with guest paths; names never get an assumed extension.
* Removed unimplemented bindings; added F8/Shift+F8, Ctrl+U, Shift+Alt+A, multi-line Ctrl+/.
* Guest: Miniconda in `~/miniconda3`, ipykernel in base.

## 3. Notebook arrows and chat references (done; checked on the tablet)
* **Bug:** arrow keys inside a cell jump to other cells instead of moving the cursor. Up/Down
  must move line by line (visual lines when wrapped) and only leave the cell from its first or
  last line; Left/Right must never move focus out of a cell.
* **Chat references:** attach the active file, the selection (or current notebook cell), or any
  workspace file to a Copilot chat message. Files are ACP `resource_link` blocks; excerpts are
  embedded `resource` blocks when the agent supports `embeddedContext`, else fenced snippets.
  Chips above the input; the Explorer menu has "Add to Copilot chat"; Ctrl+Alt+L adds the selection.
* Accept: unit tests for the prompt blocks; on the tablet, a wrapped multi-line cell navigates
  line by line; attached chips appear and are sent.
* Implemented: Up/Down use the text layout's visual rows (`visualRow`), Left/Right are consumed
  at the cell edges. Chat: `ChatReference` + `acpPromptBlocks`, attach menu and chips in
  `CopilotChatPane`, Quick Open "attach" mode, Explorer item, commands
  `github.copilot.chat.attachFile` / `attachSelection` (Ctrl+Alt+L).
* Still to check on the tablet (it went offline): the arrows test notebook
  `~/Projects/avf-tests/arrows.ipynb` (a wrapped cell) and sending a message with chips.

## 4. Setup and host reset (done; tablet check pending)
* The APK ships the arm64 daemon and the installer; "Set up guest" writes them to shared
  Downloads and shows one command to run in Linux Terminal (copy + open Terminal buttons).
* Pairing: the installer prints a short one-time code (expires, limited attempts); the app sends
  it in a `Pair` handshake and receives the token, so nobody types 64 hex characters.
* Reset detection: refused connections or `InvalidToken` offer "Set up again".
* Resume: reconnect on launch with the saved profile; reopen the tabs and Explorer folder.
* Accept: a reset guest is back in use with the command plus the code; app restart reconnects and
  restores tabs.
* Implemented:
  * Daemon: `pairing.rs` (code record with SHA-256, 10-minute expiry, 5 attempts, 0600, in
    `~/.config/avf-native/pairing.json` or `%APPDATA%\avf-native`), `avf-daemon pair [--minutes N]`,
    protocol `SystemMessage::Pair`/`Paired`, `RejectReason::PairingUnavailable`/`PairingFailed`.
    Tests: 4 unit + 1 end-to-end over WebSocket.
  * Installer: finds the daemon next to itself and ends with `avf-native-daemon pair`.
  * App: Gradle task `guestBundle` packs `target/aarch64-unknown-linux-gnu/release/avf-daemon` and
    the installer into `assets/guest/` (build the daemon with `scripts/build-guest-bundle.sh`).
    `GuestSetup.kt` exports them to `Download/avf-studio/setup-<time>/` and builds the command;
    the "Set up the Linux guest" dialog has Copy, Open Linux Terminal and the pairing code field.
    `GuestRejectedException` separates a rejected token (opens setup with an explanation) from an
    unreachable guest (banner: Open Terminal, Retry, Set up again).
  * Resume: settings `avf.connection.autoConnect` and `avf.session.restore` (both on) reconnect
    at launch and reopen the saved tabs and Explorer folder.
* Still to check on the tablet: the full flow on a reset guest (or with `avf-native-daemon pair`
  on the current one), the banner when Linux Terminal is closed, and restart → reconnect → tabs.

## 5. Search (done; checked on the tablet)
The daemon already implements `SearchMessage` (`search.rs`). Build the app codec, a Search side
view (query, regex/case/word, include/exclude globs), streamed results grouped by file, opening
at the match, and Ctrl+Shift+F.
* Implemented: ripgrep 15.2.0 joins the tool catalog (pinned per-arch downloads; catalog
  artifacts may now override `archive_member`) and the installer. App: `SearchQuery`/`SearchEvent`,
  codec (UTF-8 byte columns converted to chars), `GuestVfsClient.search`/`cancelSearch`, the
  Search view (debounced as you type, toggles, globs, grouped results, highlight) and rail entry.
* On the current tablet guest run `avf-native-daemon tools install ripgrep` (the daemon there
  predates the catalog entry, so reinstall the daemon first via "Set up or pair…").

## 6. Git (done; checked on the tablet)
The daemon already implements `GitMessage`/`GitOp` (`git.rs`). Build a Source Control view: status
with stage/unstage/discard, commit message and commit, branch picker and checkout, pull/push/fetch
with credential prompts (askpass), a diff view, and gutter change markers. Ctrl+Shift+G.
* Implemented: `GitModels.kt` (operations, results, events; externally tagged MessagePack),
  `GuestVfsClient.git`/`answerGitPrompt`, Source Control view (`SourceControlPane.kt`): branch
  button → branch picker with "create branch", Publish/Sync, Pull/Push/Fetch/Clone menu, commit box
  (Ctrl+Enter; "Commit all" when nothing is staged), staged and changed sections with stage,
  unstage and confirmed discard, diff dialog; credential prompt dialog; Init/Clone when the
  workspace isn't a repository; rail badge with the change count; `git.*` palette commands.
  Status refreshes on connect, after saves and after each operation.
* Wire compatibility is pinned both ways: the app's `WireFixturesTest` and the protocol's
  `app_encoded_requests_decode` / `daemon_encoded_replies_are_stable` share hex fixtures
  (Git, search, pairing).
* The installer installs git with `sudo -n apt-get` when it is missing.
* Not done: editor gutter change markers, a log/history view, stash and merge-conflict tools.

## 7. Debugging (done; checked on the tablet)
DAP over the daemon's `Dap` bridge with debugpy (in the tool catalog): launch the active Python
file, breakpoints in the gutter (F9), continue/step/stop (F5, F10, F11, Shift+F11, Shift+F5), call
stack, variables, debug console. Restore the F9 binding when it works.
* Adapter: `guest/tools/avf-debugpy.py`, a `uv run --script` wrapper pinning debugpy 1.8.22 (no
  apt/sudo); catalog tool `debugpy` now installs it as `avf-debugpy`. Verified on this machine with
  a scripted DAP session (initialize → launch → initialized → setBreakpoints →
  configurationDone → stopped → stackTrace/scopes/variables/evaluate → continue → terminated).
  debugpy answers `launch` only after `configurationDone`; the adapter needs its bridge stopped.
* App: `debug/DapClient.kt` (seq/request_seq, events, refuses reverse requests),
  `debug/DebugModels.kt` (+ tests from recorded debugpy payloads), controller session logic,
  Debug panel tab (`DebugPanel.kt`: controls, call stack, lazy variables tree, console with
  evaluate), gutter breakpoints (tap the line numbers or F9; red dots via `LineSideIcon`), paused
  line highlight (`LineBackground`), breakpoints persisted. Interpreter: `python.defaultInterpreterPath`,
  chosen with "Python: Select Interpreter" from the Jupyter bridge's discovery (which now reports
  each kernel's interpreter); F5 asks the first time.
* Not done: breakpoints don't move with edits; conditional breakpoints, watch expressions,
  `integratedTerminal` console (stdin for `input()`), launch.json configurations.

## 8. Daemon on Linux and Windows hosts (done; host install not run)
* Portability: the Git askpass helper (Unix sockets), home and token paths, default shell, tool
  catalog artifacts per OS, file permissions.
* Remote access: loopback stays the default. Opting in to a LAN address requires TLS with a
  self-signed certificate; the pairing string carries host, port, certificate fingerprint and the
  one-time code, and the app pins the fingerprint.
* Installers: a Linux systemd user unit and a Windows logon task; release builds for
  x86_64/aarch64 Linux and x86_64 Windows.
* A guide: install, pair, connect over LAN or VPN, firewall notes.
* Implemented:
  * Daemon `tls.rs`: self-signed certificate (rcgen, ring) in `~/.config/avf-native/tls/`, served
    with axum-server/rustls when `AVF_TLS=1`; non-loopback binds require it.
    `avf-daemon pair --remote HOST[:PORT]` prints `avf://HOST:PORT?fp=<sha256>&code=<code>`.
    Checked locally: the served certificate matches the printed fingerprint; `/health` over TLS.
  * App: `PinnedTls.kt` (connection-string parser, a trust manager that accepts only the pinned
    certificate), profiles store the pin, "Pair with a computer" in the setup dialog. The opt-in
    `RemoteHostIntegrationTest` (env `AVF_TEST_CONNECTION`) passed against a live TLS daemon:
    pairing, pinned connect and roots, and a wrong fingerprint refused.
  * Installer `--remote [--port] [--address]` (TLS unit on `0.0.0.0`, lingering, prints the
    connection string); `scripts/install-host-daemon-wsl.ps1` for Windows (WSL2 + systemd,
    firewall, Hyper-V rule or port proxy, logon task); guide `docs/HOSTING-THE-DAEMON.md`.
* Decision: Windows through WSL2, not a native port (POSIX paths in daemon and app, Unix-socket
  askpass, Linux tool artifacts). A native Windows daemon would be its own batch.
* Not run: the remote installer and the PowerShell script on a real host (they change services
  and firewall rules); the PowerShell script was only parse-checked.

## 9. Guest resources, reconnect, setup on the tablet (done; checked on the tablet)
* Status-bar chip "Guest x GB · y%" (what the guest costs the tablet: total − free − balloon) and
  a panel: programs / cache / free / returned-to-Android bar, ten-minute graph, the tools the app
  runs in the guest, "Stop idle tools". Also "Guest: Show Memory and CPU" in the palette, since
  touch taps on the bottom 44 px land in Android's gesture zone on the tablet.
* Daemon `Pong` adds `mem_free_mb`, `mem_cached_mb`, `mem_balloon_mb` (`/proc/vmstat`
  balloon_inflate − balloon_deflate); older daemons fall back to used memory.
* Reconnect: backoff (2 s … 60 s, ten tries) after a lost connection, after "Open Terminal", and
  on returning to the app after an "unreachable" failure. "Open Terminal" uses the launcher intent
  (brings the running Terminal forward) and needs a `<queries>` entry for package visibility.
* Daemon tool listing skips `.download-*` staging directories (Copilot reported "Invalid tool
  name" while the installer was downloading).
* Checked on the tablet: unreachable banner → Open Terminal → Retry; the full setup flow (copy,
  paste in Terminal, pairing code, connect) twice; the panel against the new daemon.
* Removed: dropping the guest's page cache. Root units (`drop_caches` + `compact_memory` every
  10 minutes and on request) corrupted file pages on the AVF guest: programs crashed with SIGSEGV
  and SIGILL, ext4 reported I/O errors and remounted read-only. Reproduced on the second attempt
  with Terminal in the background; a single `drop_caches` or `compact_memory` alone did not show
  it. After a VM restart fsck found nothing to fix and `dpkg --verify` was clean. The installer
  now removes those units. Don't bring back cache trimming without a reproducer outside the
  user's guest.
* Tablet round (same day), all on the device: notebook Up/Down by visual row in a wrapped cell and
  leaving only from the first/last row, Left/Right staying in the cell; search and opening a
  result; Git view, stage/unstage; a debug session (breakpoint by tapping the gutter, interpreter
  picker, stack, locals, continue, F9, exit code); chat chips for the active file and Ctrl+Alt+L;
  automatic reconnect after a daemon restart; tabs restored on launch.
* Fixed in that round: unstaging failed before the first commit (`restore --staged` needs HEAD;
  now `rm --cached`); the branch name was squeezed to "…" in the Source Control header (Publish is
  an icon now); the debug console split `print("a", b)` across lines; Copilot retries once.

## 10. Guest safety, editing keys, notebooks, plotly (code done; tablet check partly pending)
* Guest memory corruption, third time, without any cache trimming: about 30 s after Linux
  Terminal deflated the balloon while the tablet was out of memory (lmkd "low watermark is
  breached"), ext4 read directory blocks that failed checksum and remounted read-only. After a
  restart, fsck had nothing to fix. Suspected: AVF/crosvm under host memory pressure (balloon
  and free-page reporting). Unconfirmed; no public report found.
  * The user chose "disable the balloon too", but unbinding `virtio_balloon` makes crosvm reset
    and Android stops the VM (tried once; the unit file was lost in the crash, so no boot loop).
    The installer instead adds `avf-page-reporting.service`, which sets `page_reporting_order=10`
    (the most the kernel accepts) so only whole free 4 MiB blocks are reported.
* sudo in the app's terminal: the daemon unit no longer sets `NoNewPrivileges` (sudo refuses
  to run under it). Checked on the guest: `NoNewPrivileges=no`.
* Tab in the editor: VS Code behaviour (spaces to the next tab stop, or indent the selected lines),
  from `editor.tabSize` / `editor.insertSpaces`. sora's `indentSelection()` did nothing without a
  selection.
* Notebook cells (Compose text fields, not the code editor):
  * Tab/Shift+Tab indent and outdent; Enter keeps the indentation (one level more after `:`).
  * Up/Down keep the horizontal position when moving between cells. Checked on the tablet with
    the `AVFNB` cursor log (`setprop log.tag.AVFNB DEBUG`): every press across a wrapped cell and
    a three-line cell, both ways, and Left/Right at the edges.
  * Copilot: cells are mirrored to Copilot as one `# %%` Python document
    (`<notebook>.avf-copilot.py`); the suggestion is mapped back to the focused cell and drawn as
    ghost text. Tab accepts, Esc dismisses. `setprop log.tag.AVFCOP DEBUG` logs every request and
    reply (main editor too).
* Kernels: the bridge resolves a kernelspec's bare `python` (ipykernel's own `python3` spec) to
  its environment's interpreter; under `uv run` it pointed at the bridge's Python, so notebooks
  naming `python3` (as Copilot writes them) failed to start. If the notebook's own kernel fails,
  the picker opens. Dismissing the picker no longer leaves runs pending, which had stopped the
  picker from ever opening again.
* Plotly: kernels start with `PLOTLY_RENDERER=plotly_mimetype` (unless set), and cells draw
  `application/vnd.plotly.v1+json` in a sandboxed WebView with plotly.js 4.1.1 (what plotly.py 7.1
  bundles) from cdn.plot.ly; the first plot needs a connection.
* Status bar: padded above Android's bottom gesture zone, where taps were lost.
* Not checked on the tablet yet (wireless debugging dropped): editor Tab, cell Tab/Enter, notebook
  Copilot, plotly, the kernel fallback, the status bar padding, the installer's reporting unit.

## Backlog
* Finish Copilot chat sign-in on the tablet (needs the user's approval on GitHub).
* Stop the Copilot language server (~280 MB) while signed out.
* Re-measure the app's memory.
* Copilot chat replies on the tablet (attachments work; chat needs the user's sign-in).
* Copilot sometimes exits right after a daemon restart ("Guest tool exited with code unknown");
  seen once in two tries, cause unknown. The app retries once after 3 s.
* Notebook gaps: HTML outputs, kernel completion UI.
* Customisable keybindings (`keybindings.json`), TypeScript 7 measurement, compact layout check.
* Remove the `AVFKEY` routing log once the Book Cover keyboard is confirmed.
