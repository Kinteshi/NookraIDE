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
* Checked on the tablet (round 2): the installer adds the reporting unit (`page_reporting_order`
  reads 10 after a reboot) and bridge-2; `sudo whoami` in the app terminal prints root; editor Tab
  (Col 1→5, `ab` then Tab → Col 9), Tab/Shift+Tab on a two-line selection; the status bar sits
  above the gesture zone; Copilot in the main editor (AVFCOP log: request, reply, ghost text).
* Fixed in round 2:
  * Enter in the editor didn't indent (the TextMate grammar has no indentation rules): it now
    keeps the line's indentation, one level more after `:` or an open bracket, unless the
    completion popup is open.
  * The editor crashed (sora `LineBreakLayout.afterInsert`, ArrayIndexOutOfBounds) when the
    keyboard committed text right after select-all + delete: every recomposition re-applied the
    text size and tab width, and sora rebuilds and re-measures its layout in the background even
    for unchanged values. Settings are now applied only when they differ.
  * The draft cache wasn't flushed before its rename; after the tablet rebooted mid-write a draft
    came back empty and opening failed ("Value of type String cannot be converted to
    JSONObject"). Drafts are fsynced now, and an unreadable draft is dropped instead of failing.
  * After that reboot the app's settings were back to defaults (theme) and the open tabs were
    gone, which is consistent with the same unflushed writes.
* Round 3:
  * The user felt the tablet restarted too soon after the reporting change. The installer now
    removes `avf-page-reporting.service` and restores order 9 (done by hand on the guest too).
    Restart history (`persist.sys.boot.reason.history`): 10-23 18:09 and 23:02, 10-24 11:28,
    11:50 and ~12:25. The three today each came within two minutes of an `adb install` while the
    VM, the guest tools and the app were running (load average ~23); installs (dex2oat) under that
    pressure are the likelier trigger. Packages and app data written just before were lost
    (the app was missing after two of the restarts; settings were reset).
  * New strategy: detect instead of tune. Pong carries `disk_problem` (root mounted read-only, or
    an ext4 `last_error_time` since boot), and the app shows a banner with App info (Force stop
    restarts the guest).
  * Checked on the tablet: auto-reconnect after Open Terminal from the banner; the daemon swap
    from the app terminal via `systemd-run`; no crash on select-all, delete and typing; Enter
    indents after `def …:` (4) and `if …:` (8).
  * Found: Copilot ghost text after an auto-indented Enter is drawn about 8 columns right of the
    caret. The AVFCOP reply log now includes the range and text to find out why; not fixed yet.
  * A terminal whose shell died with the daemon now says so instead of "unknown terminal session".
* Round 4 (port 41991):
  * Checked on the tablet: Tab/Shift+Tab (+4/−4) and Enter (after `if x:` → 4 spaces) in cells;
    Copilot in a cell (mirror request/reply mapped, ghost text, Tab accepts); a notebook naming
    `python3` starts that kernel now (bridge-2); the picker switches to `Python (fourteen)`; a
    plotly bar chart renders interactively in the cell.
  * Fixed: Copilot ghost text after Enter. The log showed the request at column 12, not 8: sora's
    `commitText` applied its own auto-indent on top of ours (now `commitText(text, false)`), and
    Copilot's reply replaced columns 0–12 with 8 spaces + code, which the ghost drew whole. When
    only indentation is typed, the ghost now shows the code part (`ghostAfterTyped`).
  * Fixed: Up/Down in cells now go by visual row toward a sticky column kept across short lines,
    empty cells and Markdown cells (before, the column fell to 0 after an empty cell). Cells
    without a layout yet fall back to logical lines.
  * Fixed: opening the kernel picker no longer shows "No kernel" while one runs.
  * Fixed: unsaved notebook edits had no draft (only Save updated the document text), so a
    restart lost them; cell edits now write a draft after 500 ms, and a restored draft shows as
    unsaved.
  * The tablet restarted again (~12:55) with no install in progress, while the VM and the app
    were running; load sits around 23 even before the VM starts. Next: read the restart reason
    from `adb shell dumpsys dropbox` (SYSTEM_RESTART, watchdog, kernel panic entries).
  * Not yet checked on the tablet: the ghost fix, the sticky column, notebook drafts.
* Round 5 (10-27, port 36033):
  * Restart reasons: `persist.sys.boot.reason.history` only says "reboot" (10-24 11:28, 11:50,
    12:22, 12:54), then "cold,powerkey" (10-27 15:00, before this session). The dropbox kept only
    today's entries; its SYSTEM_LAST_KMSG is the new boot's log (`reset_reason = 9`, Samsung code,
    meaning unknown). No crash or watchdog record survived, so the cause is still unproven.
  * The guest was reset and set up again between sessions (`~/Projects` holds only the user's
    `main.ipynb`; Copilot is signed out). Test files go in `~/Projects/avf-tests/` and are removed
    at the end.
  * Left/Right focus loss: not reproduced on the installed build with plain, Shift and Ctrl arrows
    at both cell edges, Shift/Ctrl+Up/Down, or a 60 ms burst crossing cells. Guard added anyway:
    a cell consumes any arrow or Tab its text field leaves unhandled (`onKeyEvent`), so Compose
    focus navigation can't move focus out of the notebook.
  * Settings for this batch's features: `editor.autoIndent` (Auto-indent; "none" turns it off),
    `avf.copilot.notebooks`, `avf.notebook.plotly` (Draw Plotly figures; off shows the text
    fallback), `avf.statusBar.guestResources`. The disk-problem banner has no toggle (data safety).
  * Reproduced focus loss on the new build: Down from the end of `a = 1` through the Markdown and
    empty cells reached cell 3 at column 5 (sticky column works), then the next Down did nothing
    and the Git button in the activity bar had focus. Likely the entered cell's single
    `requestFocus()` ran before it could take focus. It now retries for up to 10 frames, and
    focus gains/losses are logged under AVFNB.
  * Checked after the fix: the same Down/Up walk hands focus cell to cell (`focus=false` then
    `focus=true`) with the sticky column (5 → 13 → empty → 5 → 11 → 16 and back); a 60 ms burst of
    19 keys mixing crossings and Left/Right kept focus in the cells throughout.
  * Checked: a notebook edit survives a force-stop (draft restored: `a = 1 + 2`); the four new
    Settings rows are present.
  * Found and fixed: closing a tab with unsaved changes (X or Ctrl+W) closed it silently, keeping
    a stale draft. It now asks Save / Don't save / Cancel as VS Code does; Save closes once the
    save (or the offline queue) went through, Don't save drops the draft. "Close other editors"
    still closes without asking. Checked on the tablet: Ctrl+W on the unsaved scratch file shows
    the prompt; Cancel keeps the tab.
  * Three installs this round, each with the app backgrounded first, `sync`, and a minute's wait:
    no restart. Test folder `~/Projects/avf-tests` removed; the app is left installed.
  * Not checked this round: Copilot ghost text after Enter (Copilot is signed out in the reset
    guest).
* Round 6 (10-27): the user still sees the notebook arrow bug and suspects the Book Cover
  keyboard. Added `AVFKEYS` logging (every hardware key at the window: key code, scan code,
  meta, device and its name, source, whether the router took it) and per-cell key/unhandled logs
  under AVFNB, to compare that keyboard with the test HID keyboard while the user reproduces it.
  * Guest storage: Pong carries `disk_used_mb`/`disk_total_mb` (statvfs of the home filesystem);
    the status chip shows used/total GB and the resources panel a storage bar. The existing
    status-bar toggle covers it (renamed "Guest resources in the status bar"). Checked on the
    tablet: "Guest 1,1 GB · 0% · 2/431 GB".
  * Root cause of the notebook arrow bug, from the user's recording: the Book Cover Keyboard
    (EF-DX935) reports `KEYBOARD | DPAD` (source 0x301); the test HID keyboard is keyboard only
    (0x101), which is why it never reproduced. Compose text fields send arrows from D-pad devices
    to focus navigation (`interceptDPadAndMoveFocus`): Right in the middle of a cell moved focus to
    the terminal panel's + button. Up/Down and edge Left/Right worked only because the cell's own
    handler takes them first. Fix: MainActivity re-issues arrows from such keyboards as virtual
    keyboard arrows (`asPlainKeyboardArrow`), which fixes every Compose text field (cells, search,
    palette, chat), not just notebooks. Confirmed by the user on the tablet ("beautifully
    fixed"); the second recording shows Right moving the caret (19 → 20) with focus kept.
* All batch 10 items are checked on the tablet (rounds 3–6), except the Copilot ghost text after
  Enter, which waits for Copilot sign-in in the reset guest.

## 11. Copilot, auto-save, notebooks, cleanups (10-27; paused by the user, mostly checked)
* Copilot: model picker for chat (and inline completions if the server allows it); one GitHub
  sign-in for inline suggestions and chat (research first: which tokens each accepts), and Git
  credentials in the same pass if feasible; else listed under "Mapped for later". Check the ghost
  text fix now that the user signed in again. Stop the language server while signed out.
* Auto-save: `files.autoSave` off / afterDelay / onFocusChange / onWindowChange, picker in
  Settings.
* Notebooks: HTML outputs (pandas tables) with a Settings toggle; kernel completions in cells
  (Ctrl+Space, list) if time allows.
* Cleanups: drop the `AVFKEY` routing log, "Close other editors" asks about unsaved changes,
  memory probes (app PSS, guest tools).
* Later (user): new AI assistants (Claude Code, Gemini).
* Done (code, unit tests; tablet check pending):
  * `files.autoSave` is a mode: Off / After delay / Focus change / Leaving app (segmented picker).
    After delay now covers notebooks too; focus change saves on leaving the editor or notebook
    (focus area change), on switching tabs (activePath setter), and on leaving the app; leaving
    app saves all on ON_STOP. Scratch files are never auto-saved.
  * Close Other Editors goes through the same Save / Don't save / Cancel prompt, one unsaved tab
    at a time; Cancel stops.
  * The `AVFKEY` routing log is gone (`AVFKEYS` covers it, gated by setprop).
* Research (probe of the guest, read-only, no token values printed):
  * Chat agent (Copilot CLI 1.0.104, ACP v1): `session/new` returns `configOptions` (select):
    `mode` (Agent / Plan / Autopilot), `model` (here only "Auto"), `allow_all` (tool approvals),
    plus the older `models`/`modes`. ACP v1 changes them with `session/set_config_option`
    {sessionId, configId, value} and returns the updated options; clients should prefer
    configOptions over modes. `authMethods` is only `copilot-login` (run `copilot login`).
  * Auth stores: the language server keeps its login in `~/.config/github-copilot/auth.db`;
    `~/.copilot/config.json` is empty; no `gh`, no git credential helper.
  * Copilot CLI token order (GitHub Docs): COPILOT_GITHUB_TOKEN, GH_TOKEN, GITHUB_TOKEN, stored
    login, then `gh auth token`; OAuth `gho_` and fine-grained PATs work, classic PATs don't.
  * Plan from that: one "Sign in to GitHub" through `gh auth login` covers chat (CLI falls back to
    gh's token) and Git (`gh auth setup-git`, user.name/email from the account). The inline
    language server documents no way to take a token, so it keeps its own sign-in: two flows
    instead of three. Sharing one token with the language server is listed for later.
* Chat option picker (code, unit test; tablet check pending): `AcpClient` reads `configOptions`
  (select type; duplicate choices folded), sets them with `session/set_config_option`, follows
  `config_option_update`/`current_mode_update`, and clears them when the agent stops. The chat
  pane shows a chip per option (Model, Mode, Allow All) above the input; before any session the
  chips show the remembered choice and a tap opens a session to list them. Model and mode are
  remembered and re-applied to new sessions (Autopilot is not); "Allow all" asks first and is
  never remembered. No Settings row: the list comes from the agent, the chips are the picker.
* GitHub sign-in (code, unit test; tablet check pending): "Sign in to GitHub" (chat pane, Settings,
  palette `github.signIn`) runs `GITHUB_SIGN_IN_COMMAND` in a terminal: installs `gh` 2.102.0
  (catalog, pinned with GitHub's checksums, installed on first use only), `gh auth login --web`
  with GH_BROWSER=true (the app answers gh's "Press Enter" and shows the code as a chat card),
  `gh auth setup-git`, then Git user.name/email from the account when unset (noreply address).
  Copilot chat uses gh's token through its documented fallback. `copilot login` stays as the
  palette command "Copilot: Sign In to Chat with copilot login".
  * Tablet: `gh` installed through the daemon, but `gh auth login` failed ("error connecting to
    github.com"): Go's resolver timed out (`lookup github.com: i/o timeout`). The guest image
    ships `/etc/resolv.conf` from its build machine (nameserver 169.254.169.254, Google Cloud
    search domains), which doesn't answer; glibc works through systemd-resolved (nss `resolve`,
    a second link with 10.16.230.177), but Go reads the file directly. Not EDNS0
    (`GODEBUG=netedns0=0`), post-quantum TLS (`tlsmlkem=0`), HTTP/2, or parallel A/AAAA
    (`single-request`, tested with the file restored afterwards).
  * Fix, with the user's OK: `/etc/resolv.conf` → `/run/systemd/resolve/stub-resolv.conf`
    (backup `/etc/resolv.conf.avf-backup`); gh then gets a device code at once. The installer
    does the same on AVF guests with systemd-resolved running and a plain resolv.conf. The app
    also answers gh's "Authenticate Git with your GitHub credentials?" with its default (Yes).
  * The reset guest had no git ("unable to find git executable"): the sign-in now installs it
    with apt when missing, and skips `gh auth login` when gh already has a login.
  * Checked on the tablet: the user approved the code once; the rerun installed git and printed
    "Signed in to GitHub as Kinteshi-dev; Copilot chat and Git use this account" (exit 0). Chat
    then answered a prompt. Chips: Mode / Model / Allow All load on tap; switching Mode to Plan
    and back works (agent confirms). Copilot CLI 1.0.104 lists only "Auto" as a model through
    ACP, before and after sign-in, so the Model picker has one choice for now.
  * HTML outputs checked: the user's `df.head()` renders as a styled table.
  * Git after the sign-in: `credential.https://github.com.helper` = `gh auth git-credential`,
    user.name Kinteshi-dev, noreply email; gh scopes gist, read:org, repo, workflow.
* Memory probe (10-27 18:5x): app PSS 251 MB at rest → 320 MB with two HTML outputs and chat
  open (native +42 MB, graphics +19 MB: about 35 MB per WebView); swap 81 MB → 0 after use.
  Guest: copilot-language-server 257 MB, Copilot CLI (chat) 245 MB, ty 20 MB, ruff 15 MB,
  weston 82 MB. Later: draw HTML tables without one WebView each (many tables add up).
* Seen: after gh/apt exit, their cursor-position replies (`;1R;120R…`) land on the shell
  prompt; harmless, cleared by Ctrl+C. The "terminal ended" notice prints twice per lost tab.
* Checked on the tablet: Copilot ghost text after Enter (request at 2:8, reply replaces 0–8 with
  8 spaces + `return n`, ghost drawn at the caret, Tab accepts); Auto save "Focus change" saved
  the test file on switching tabs (then set back to Off, the user's setting); the stale-draft
  conflict dialog appeared for an old test draft and "Load guest copy" resolved it.
* Found: the reset guest has no conda; the kernel picker offered only "Install ipykernel in Python
  (system)", which fails on Debian 13 (PEP 668, externally managed). For system Python the app now
  offers "Create ~/.venv with ipykernel" (installs python3-venv if needed; the bridge discovers
  ~/.venv as kernel `avf--venv`).
* Paused here at the user's request (10-27 ~19:00). Open for the next pass:
  * Kernel completions untested on the tablet: the guest has no kernel; the user will decide on
    creating `~/.venv` (the picker's new option) in a follow-up.
  * Bug: the app's terminal emulator types `;1R;120R;56R` (cursor-position replies) into the
    shell when a terminal tab is switched to or tapped.
  * Bug: "this terminal ended…" prints twice per lost tab.
  * The Auto save picker cuts "Focus change" to "Focus" at tablet width.
  * HTML outputs cost ~35 MB per WebView; draw tables natively or share one WebView.
  * Ctrl+Z didn't undo text typed through the keyboard IME path (seen once on a scratch file);
    check sora's undo grouping for commitText.
  * Copilot CLI offers only "Auto" as a model over ACP; recheck with newer CLI versions.
  * Test files removed (`~/Projects/avf-tests`); the app is left installed.
* Copilot memory: when the language server reports "not signed in", the app remembers it and
  stops the server after 30 s (~280 MB in the guest); later launches don't start it, the status
  bar says "Copilot · Signed out", and tapping it starts the server and signs in.
* Notebook HTML outputs: `text/html` renders in a WebView with theme-following styles (tables
  readable in dark mode), JavaScript and network loads off; outputs containing `<script>` show
  their text form instead. Setting `avf.notebook.htmlOutputs` ("HTML outputs"). Gap: script-based
  outputs (widgets, bokeh, altair) still don't render.
* Kernel completions in cells: Ctrl+Space, or Tab right after a name or a dot (Jupyter's rule,
  setting `avf.notebook.tabCompletion`), asks the running kernel (`complete` now returns
  cursor_start/end); a non-focusable popup under the cell lists matches, typing narrows them,
  Up/Down choose, Enter/Tab accept, Esc closes. Without a kernel, Tab indents.

## 12. Guided dependency setup, open tests and bugs (10-27 22:45–23:30; done, mostly checked)
Progress (unit tests pass; checked on the tablet where noted):
* Daemon: Welcome now lists `features` (`probe_commands`); new `ProbeCommands` /
  `CommandsProbed` say which commands the guest has (daemon PATH plus ~/.local/bin and the
  conda installers' default prefixes; bare names only, 32 per request). Older daemons close the
  connection on an unknown packet, so the app only asks when Welcome lists the feature.
* uv joins the tool catalog (0.13.0, same pins as the installer), so the app can install it with
  `avf-native-daemon tools install uv`.
* App: `GuestDependency` (Git, GitHub CLI, uv, Conda via Miniforge 26.7.2-0 checksum-verified,
  Jupyter bridge, ripgrep). The app checks on connect; `offerInstall` shows what is installed,
  from where, and the exact command, then runs it in a visible terminal and checks again.
  * Kernel picker: "Install Conda (Miniforge) for conda environments" when conda is missing; a
    missing Jupyter bridge offers its install instead of a status line. "Create ~/.venv" uses
    uv when present (no python3-venv needed); pip installs fall back to `uv pip`.
  * Python interpreter picker: "Create ~/.venv with ipykernel" when there is none, and
    Miniforge when conda is missing.
  * Source Control: "Git isn't installed in the guest" with Install Git / Check again.
  * Search: an Install button when ripgrep is missing.
  * Settings → Guest tools: each tool with its path or "not installed" and an Install button;
    "Check guest tools again". No on/off toggle: prompts appear only where a feature already
    failed, and the list in Settings is the picker.
  * GitHub CLI: the sign-in command already installs gh (and git) itself.
  * Keyring: not offered. A secret service in the headless guest needs gnome-keyring plus a
    D-Bus session and an unlock on every boot; gh's plain-text file is mode 0600 in a
    single-user VM whose disk lives in app-private storage. Revisit if a tool needs one.
* Checked on the tablet (23:00–23:07): the new daemon installed from Linux Terminal (token
  kept); Settings → Guest tools found every tool, including the user's own ~/miniconda3. With
  `rg` moved aside, Search said "needs ripgrep" with Install; the confirmation showed the
  catalog command; the terminal installed it and exited 0; the app checked again and re-ran
  the search (14 results). The kernel picker listed miniconda3 and its `fourteen` env as
  "Install ipykernel" rows and no Miniforge row (conda present); closed without choosing.
  * Fixed from that: ipykernel goes into conda environments with `conda install -p` (not pip);
    the Conda row no longer says "Miniforge, ~/miniforge3" for another conda; the dialog only
    mentions sudo when the command uses it.
* Terminal fixes: "terminal ended" prints once per lost terminal (each key or resize sent to a
  dead shell used to print it again); the Auto save choices get the row's full width (two-line
  labels if needed) instead of cutting "Focus change".
* Fixed and checked on the tablet: the terminal typing `;1R` (cursor-position answers) into the
  shell. Cause: every reconnect reattaches the guest's shells, and the daemon replays their
  recent output; old queries in it (gh and others ask for the cursor position) were answered
  again into the shell. For 3 s after a reattach the app now drops the emulator's automatic
  answers (cursor position, status, device attributes, OSC colours), never keys. Reproduced with
  `printf '\033[6n'` plus an app relaunch (a fresh `;1R` at the prompt), gone after the fix
  over two relaunches.
* Ctrl+Z after IME-typed text: works on the tablet now (typed "hello world" through the IME into
  an untitled file; Ctrl+Z removed " world", then the rest); the earlier miss didn't repeat.
  Found alongside: undoing back to the saved text still asked "Save changes?"; documents now
  remember the saved text and editing back to it clears the unsaved mark, as in VS Code.
* Checked on the tablet: Close Other Editors closes clean tabs and asks for each unsaved one in
  turn (Untitled-1, then Untitled-2; "Don't save" moves on); an untitled file undone back to
  empty closed without asking. The chat option menus open aligned under their chips (Mode,
  Model, Allow All); the model list still has only "Auto".
  * Fixed from that: before the agent started, the Mode chip said "Autopilot" (the name was saved
    even though Autopilot is never re-applied); names are now kept only with a value that will be
    re-applied.
* Not testable now: the Copilot server staying stopped while signed out (the user is signed in).
* Python interpreter picker on the tablet: miniconda3, `fourteen`, system, daemon PATH, then
  "Create ~/.venv with ipykernel"; no Miniforge row (conda present). Closed without choosing.
  Its status line kept saying "Looking for Python environments…"; it now says "Choose a Python
  interpreter" (code only; the tablet build predates this one-line change).
* Still open after batch 12:
  * Kernel completions on the tablet: needs ipykernel in one of the user's conda envs (the
    picker offers it with `conda install -p`); not installed without the user.
  * Copilot server staying stopped while signed out.
  * Untested paths of the setup: Source Control's "Install Git" (git is present), Miniforge's
    install (conda is present), "Create ~/.venv" via uv.
  * Copilot CLI model list ("Auto" only) with a newer CLI.
  * The user's guest prompt starts with `port CONDA_SHLVL=0port PATH` in both Linux Terminal
    and the app; most likely from the guest's shell startup files (conda init), not checked.
* HTML outputs: cells are a lazy list, so only on-screen outputs hold a WebView; kept as is.
* Copilot CLI over ACP still offers only "Auto" on 1.0.103; 1.0.105 came out hours ago, so the
  catalog stays on 1.0.103 for now (recheck later).
* Connecting after a guest boot: Linux Terminal didn't report port 19870 until it was
  force-stopped and relaunched (then `reportVmActivePorts: [19870]` after 16 s).
* Tablet restart at 19:22 (`rescueparty_by_bootchecker`) after SystemUI crashed in its GC
  (19:19 tombstone); nothing from this work was running. The adb pairing was lost and redone.

Registered list (10-27):
* **Guided dependency setup (user's priority; large).** The IDE relies on guest tools that a
  fresh or reset guest may lack. Detect what's missing and offer to install it where it's
  needed, instead of failing:
  * Python environments (conda or uv, venv, ipykernel) in the kernel picker and the Python
    interpreter / environment picker. Today a reset guest only gets "Create ~/.venv with
    ipykernel" (batch 11) and conda is not offered.
  * git in the Source Control view (the GitHub sign-in installs it, but the Git view itself
    should offer it); the GitHub CLI where sign-in is offered.
  * A keyring / secret service where tools want one (e.g. gh warns "Authentication
    credentials saved in plain text").
  * Others found along the way (clipboard utilities gh looks for, python3-venv, ...).
  Each prompt says what will be installed and how (apt, uv, the daemon's tool catalog), runs in
  a visible terminal, and rechecks afterwards. Decide per dependency whether it belongs in the
  installer, an on-demand prompt, or both; keep app scope minimal.
* Tablet tests still open: kernel completions (needs a kernel; tie in with the item above),
  Close Other Editors' unsaved-changes prompt, the Copilot server staying stopped while signed
  out, the option menus' alignment fix.
* Open bugs from batch 11: terminal types `;1R;120R;56R` into the shell when a tab is switched
  to or tapped; "terminal ended" notice prints twice; Auto save picker truncates "Focus change";
  one WebView per HTML output (~35 MB each); Ctrl+Z after IME-typed text (check sora undo);
  Copilot CLI lists only "Auto" over ACP.

## 13. Material polish, ended terminals, forwarding hint (10-28 00:15–00:45; done, checked)
* Ended terminals: when the active terminal has ended, its area becomes a focus target (a tap,
  watched in the Initial pointer pass so scrolling still works, takes the keyboard from the
  editor) and a tonal bar says "This terminal ended (exit code N)" with "New terminal".
  Checked: after `exit`, with welcome.py focused, a tap on the ended terminal and a key went
  nowhere (welcome.py unchanged).
* Forwarding hint: two connection refusals in a row switch the banner to "Still can't reach the
  guest daemon. If Linux Terminal is already open, it may have stopped forwarding the
  connection: Force stop it in App info (this restarts the guest), then open it again." with a
  tonal App info button; cleared on connect. Checked by stopping the daemon with a 45 s restart
  timer: the hint appeared after two refusals and the app reconnected by itself (next backoff
  step, ~2 min) and cleared the banner.
* Material pass: one ProblemBanner for the connection and disk banners (leading icon, large
  shape, actions wrap below the text when narrow); the install dialog gets the dialog icon, a
  filled Install button and the command in a surfaceContainerHighest code block; picker setup
  rows get leading icons (+ to create, download to install); Settings → Guest tools uses a
  tonal Install button. The rest of the chrome already used Material 3 parts (filter chips,
  tonal containers, M3 dialogs, segmented buttons) and keeps its slim AMOLED look.
* Found on the way: interpreter picker labels were cut at the first " (" ("Python 3
  (ipykernel)" showed as "Python"); now at the last.

## 12b. Guest disk space and kernel completions (10-27 23:40 – 10-28 00:10; done, checked)
* **"No space left on device" in the guest when installing libraries (user): investigated and
  fixed 10-27 23:50.** The disk was fine (6.2 of 432 GB, inodes 1%, no ext4 errors). /tmp is a
  2 GB tmpfs in the guest's 3.9 GB of RAM, TMPDIR was unset, and the failing command was
  `pip install jupyter plotly pandas torchvision` in the user's `fourteen` env: PyPI's
  Linux aarch64 torch wheels are CUDA builds that pull several GB of nvidia-* wheels (1.6 GB
  sat in pip's cache), unpacked through /tmp. Linux Terminal's StorageBalloonWorker
  (`setStorageBalloon 401888899072`) only mirrors the tablet's free space; not the cause.
  * Fix in `install-native-daemon.sh`: the daemon unit sets `TMPDIR=%h/.cache/tmp` (disk), so
    the app's terminals, kernels and language servers inherit it; a user tmpfiles rule clears
    files there older than 7 days. Verified in the running daemon's environment.
  * With the user's permission: installed CPU-only torch 2.14.1 + torchvision 0.29.1 from
    download.pytorch.org/whl/cpu (159 MB instead of GBs of CUDA), plus plotly, pandas and
    nbformat (plotly's notebook renderer needs it), into `fourteen`, with TMPDIR on disk; /tmp
    stayed empty. ipykernel 7.3.0 was already there (the user ran the picker's conda
    install). The Anaconda terms were already accepted, so nothing was agreed to.
  * Made automatic (user, 10-28): the daemon checks /proc/self/mounts at startup and, when /tmp
    is a tmpfs and TMPDIR is unset, uses ~/.cache/tmp for everything it starts (set before the
    tokio runtime spawns threads). The installer does the same check: only then it writes the
    unit's TMPDIR, the tmpfiles rule and, in the guest only (not `--remote` hosts),
    /etc/profile.d/avf-tmpdir.sh for login shells such as Linux Terminal's (via sudo -n; a note
    otherwise). No Settings step needed. Checked on the tablet 10-28 00:07: profile.d written,
    a fresh login shell and the daemon both have TMPDIR=/home/droid/.cache/tmp.
  * 10-28 00:05: the VM stopped while nothing of ours ran in it (Android had just reclaimed
    Firefox for memory; Linux Terminal was relaunched and the VM force-killed 8 s later, which
    matches its window being closed). Restarted with force-stop + launch.
* **Kernel completions checked on the tablet (10-27 23:53).** A test notebook asking for
  `python3` started the `fourteen` kernel on its own; `import torch` ran; typing `x.su` + Tab
  opened the kernel's list (sub, sub_, subtract, subtract_, sum, sum_to_size); Down x4 + Enter
  accepted `sum`; the cell ran (`tensor(3.)`). Copilot's ghost text showed alongside the popup.
* Incidents in this check: Linux Terminal crashed (NullPointerException in its
  TerminalTabFragment page callback) when brought to the front after the app had lost the
  daemon, and Android killed the VM forcibly; the next boot's ext4 journal recovery was clean.
  Then a cleanup command typed for the app's terminal landed in the user's notebook (its
  terminals had ended with the daemon restart, so focus stayed in a cell); removed character
  by character, the file on disk was never changed (grep), and the tab was reopened from disk.
  Fixed in batch 13: tapping an ended terminal takes the keyboard from the editor.

## 14. MaterialCode name and icon; key log removed (10-28 00:55; installed and checked 00:58)
* The app is called MaterialCode (launcher label, top bar, welcome screen, notification,
  dialogs, the daemon's pairing instructions, the installer's notes and unit description). The
  package id stays com.avfstudio.nativeide so the tablet build updates in place and keeps its
  settings, token and drafts; internal names (avf-native-daemon, crates, repo) are unchanged.
* Icon: "< / >" in Material 3 tones (lavender #D0BCFF chevrons, mint #7DDBC4 slash) on #1C1B1F.
  Adaptive icon for Android 8+ (vector foreground, colour background, monochrome layer for
  Android 13 themed icons) and PNG fallbacks for Android 7 rendered from
  `docs/branding/materialcode-icon.svg` (512 px preview next to it).
* Backlog item 15 done: the AVFKEYS routing log is gone (KeyRouter.route is one line again).
* Installed 00:56 with the user's go-ahead: the taskbar and the window caption show the new
  icon, the top bar says MaterialCode. The scratch welcome.py keeps its saved text ("Welcome to
  AVF Native IDE"); only a fresh install gets the new header.
* The guest had stopped at 00:56:10, before the install (Linux Terminal was opened from the app
  list at 00:55:19 and the VM stopped normally ~50 s later, likely the user closing Terminal or
  starting the reset they planned); left alone.

## 15. Next-pass tests, editor bugs, Material You syntax colours (10-28 12:30–13:06; done, checked)
Guest not reset (the user's conda base and Copilot sign-in were still there); tested on it.
* Bug 6 fixed, checked on the tablet: Backspace in leading spaces goes back to the previous tab
  stop (VS Code's `editor.useTabStops`, default on, Settings → Editor → "Backspace by
  indentation level"), in the code editor (NativeCodeEditor.deleteText) and notebook cells.
  After `if x:` + Enter (column 8), one Backspace put the cursor at column 4.
* Bug 7 fixed, checked: LSP edits are applied with sora's `Content.replace` instead of
  `setText`, so choosing a Ctrl+Space completion leaves the cursor after the inserted text
  (`os.pa` → `os.path`, typing continued at Ln 6 Col 20) and undo keeps the history; format
  and workspace edits use the same path.
* Item 11 done, checked: `avf.theme.syntaxColors` (Settings → Appearance → Syntax colours:
  Classic / Material You). Material You generates a TextMate theme and semantic-token colours
  from the scheme: keywords primary, strings tertiary, numbers/functions/types/constants the
  primary hue turned 300/120/180/240° at the scheme's accent lightness, comments and operators
  from onSurfaceVariant. Notebook cells use the same palette. An imported VS Code theme still
  wins. Unit tests check distinct hues and contrast ≥ 3 on the surface in light and dark. Left on
  Material You on the tablet for the user to see.
* Item 3 done, checked: while the app is in front, reconnect attempts are at most 10 s apart
  and don't stop after ten; coming back to the app retries at once instead of waiting for a
  backoff scheduled in the background. Timed by stopping the daemon for 30 s (from a
  `systemd-run --user` transient unit, so the stop doesn't kill the script): the daemon was back
  at 13:04:35 and the app showed Connected by 13:04:43 (before: ~50 s).
* Item 1 checked: GitHub Copilot: Sign Out → "Copilot off". Turning Copilot on from the status
  bar opened the sign-in code (the server said "not signed into GitHub"); Cancel stopped the
  server at once. Toggle Inline Suggestions while signed out → "Copilot · Signed out" without
  starting the server (the signed-out state was remembered), and after force-stopping and
  reopening the app the status bar said "Copilot · Signed out" and no copilot-language-server
  ran in the guest. The user has to sign in again (tap the status bar, enter the code).
* Item 2, setup paths:
  * "Create ~/.venv with ipykernel" via uv: ran in a new terminal, exit 0; the picker reopened
    listing Python (.venv) first and no setup row. (No confirmation dialog for this one.)
  * Install Git: with git removed (`apt-get remove git`, only git itself), Source Control showed
    "Git isn't installed", Install Git → confirmation → apt in a terminal, exit 0, "Git
    installed", the view reloaded. Git 2.47.3 is back.
  * Miniforge: not offered in the UI (miniconda3 counts as conda), so its exact command was run
    in a script: download, checksum, `-b -p ~/miniforge3`, `conda install ipykernel` all
    passed (ipykernel 7.4.0, Python 3.14.7) but it takes 816 MB, not ~400: the dialog text now
    says about 800 MB. The test install and its environments.txt line were removed.
* Item 4: the guest's Copilot CLI had updated itself to 1.0.105 (catalog pins 1.0.103). An ACP
  probe (initialize + session/new, then 25 s of updates) shows the CLI itself offers only
  "auto" (listed twice; one with copilotEnablement "enabled"), and no later model update. So it
  is the account's model availability, not the app or the CLI version: models are enabled per
  account in GitHub's Copilot settings (or limited by an organisation policy). The app already
  shows the duplicate once.
* Item 5, the `port CONDA_SHLVL=0port PATH` prompt prefix: found. Linux Terminal installs a
  DEBUG trap `echo -ne "\e]0;$BASH_COMMAND\007"` (window title per command). `echo -e` turns the
  backslash in conda's hook lines `\export CONDA_SHLVL=0` and `\export PATH` into an ESC, which
  ends the title sequence early, so the rest prints as text. Harmless. Fix (not applied: editing
  ~/.bashrc was blocked by the permission check, waiting for the user): at the top of ~/.bashrc,
  replace that trap with `trap 'printf "\e]0;%s\a" "${BASH_COMMAND//[[:cntrl:]]/ }"' DEBUG`.
* Cleanup: ~/Projects/avf-tests, the test ~/.venv, the 1.0.105 probe copy and the staging files
  in Downloads are removed; git is installed again. The app is left installed and connected.

## 16. XS/S candidates: prompt fix, Plotly updates, /tmp option, effort, measurements (10-28 13:10–13:37; done, checked)
* Prompt prefix fixed (user's permission): the title trap is set in Linux Terminal's
  `~/.bash_aliases` (`trap 'echo -ne "\e]0;$BASH_COMMAND\007"' DEBUG`). ~/.bashrc now replaces
  it, just before conda's block, with `trap - DEBUG` then a `printf "\e]0;%s\a"` trap (the test
  and removal contain no backslashes, since the old trap still runs for them). Backup at
  `~/.bashrc.before-materialcode`. A fresh terminal shows `(base) droid@debian:~/Projects$`.
* Plotly in place (bridge-3 + app): display_data keeps `transient.display_id`;
  update_display_data becomes an `update_display` notification and the app replaces every
  output shown with that id (NotebookDisplays); `clear_output(wait=True)` clears at the cell's
  next output; Plotly redraws with Plotly.react in the loaded page (no reload). Checked: a
  raw-bundle figure updated 5 times stayed one figure ("update 5"), a text display one line,
  the clear_output loop left only "step 3".
  * Finding for the user's mnist notebook: `display(fig, display_id=True)` with a plotly Figure
    can't work in any front end: the Figure's `_ipython_display_` publishes its own output
    without the id, and `handle.update(fig)` does the same, so each epoch adds a figure (seen
    here before the change too). Fix in the notebook: publish the bundle, e.g.
    `plot_handle = display({"application/vnd.plotly.v1+json": fig.to_plotly_json()}, raw=True, display_id=True)`
    and `plot_handle.update({...same...}, raw=True)`. Not edited (the user's file).
* /tmp on disk (Settings → Guest tools → Temp files): the daemon's stats now report
  `tmp_in_memory` and `tmp_mount_masked`; the row shows In memory / Moves to disk when the
  guest restarts / On disk (moved by MaterialCode) / On disk, with Move to disk, Undo or Back
  to memory (confirmed `sudo systemctl mask|unmask tmp.mount` in a terminal). Checked the whole
  loop with one guest restart (force-stop + relaunch of Linux Terminal); then unmasked again, so
  /tmp returns to memory at the next restart (the user's default kept).
* Chat reasoning effort: Copilot CLI 1.0.105 has no ACP option for it, only
  `--reasoning-effort none|minimal|low|medium|high|xhigh|max` at launch. New setting
  `avf.copilot.chatReasoningEffort` (Settings → GitHub Copilot, Default/Low/Medium/High) and an
  "Effort" chip in the chat beside Model and Mode; changing it ends a running agent so the next
  message starts with it. Checked: the CLI opens an ACP session with `--reasoning-effort low`;
  the chip shows. Not checked: a full chat turn (Copilot signed out of suggestions; no quota
  spent).
* Daemon/bridge deployed without the full installer (it would reinstall Copilot CLI 1.0.103
  over the self-updated 1.0.105): new binary in ~/.local/bin, `tools install jupyter`, restart
  through a `systemd-run --user` unit.
* Measurements:
  * App PSS: 209 MB fresh with a notebook open (10-27: 251 MB at rest); 323 MB after two
    Plotly notebooks (graphics 94 MB, native 55 MB: about 45-55 MB per Plotly WebView).
  * TypeScript 7.0.2 (typescript-go, `tsc --lsp --stdio`, 26 MB native binary, no Node): 48 MB
    RSS after hover + completion on a small project, ready in ~2 s. vtsls (catalog today) needs
    Node + tsserver, typically a few hundred MB: switching the catalog is a candidate.
  * Compact layout (window resized to ~570 dp): bottom navigation Files / Editor / Terminal /
    Chat / More works and the notebook fits. Gaps: no way to reach Source Control, Search across
    files, Debug, Output or more terminals (More has Problems, Outline, References, Settings;
    the compact Terminal has no tab strip or +).
* Seen: the chat's Mode placeholder (before a session) reads "Autopilot", though autopilot is
  never re-applied; check `rememberedChatChoice`.
* Cleanup: test notebooks, ~/Projects/avf-tests, TS7 and Copilot probe copies, staging files in
  Downloads removed. App installed and connected.

## 17. Candidates 1-7 (10-28 13:40–14:26; done, checked except 1)
* 1, the mnist notebook's two lines: not applied. Editing the user's notebook was blocked by the
  permission check; candidate 4 makes the notebook work unchanged instead (checked below).
* 2, chat Mode placeholder: it read "Autopilot" for the saved Agent mode (the stored name came
  from a different value). Names are now saved with the value they name
  (`chat-option-<id>-name-of`) and refreshed from the agent's choices each session; a stale or
  missing name falls back to the value's last segment. Checked: "Mode: Agent".
* 3, effort with a real message: Effort Low → "Reply with just the word OK" → "OK"; the guest
  ran `copilot --acp --stdio --reasoning-effort low`. Set back to Default afterwards (the
  agent stopped, as designed).
* 4, `avf.notebook.plotlyDisplayIds` (default on; Settings → Terminal and Jupyter → "Plotly
  figures update in place"): bridge-4 runs a silent snippet in fresh and restarted Python
  kernels that removes plotly's `BaseFigure._ipython_display_` (only if plotly is installed),
  so IPython uses the figure's mime bundle and keeps the display id. Checked with the user's
  exact pattern (`display(fig, display_id=True)`, `handle.update(fig)`): one figure ending at
  "update 5".
* 5, TypeScript 7: catalog entry `typescript` 7.0.2 (npm @typescript/typescript-linux-<arch>,
  sha256 pinned, verified against npm's sha512). New download option `keep_directory` puts the
  package's lib folder in the tool's folder (tsc needs its lib.*.d.ts) behind a `tsgo` launcher;
  Rust test included. Added to the daemon's LSP table and the installer's tool list. Setting
  `avf.typescript.languageServer` (TypeScript 7 / vtsls) in Settings → Language servers; Guest
  tools row; the app offers the install when it's missing. Fixes found on the tablet:
  JSON-RPC now keeps nulls (`processId: null`, which tsgo requires) and the client pulls
  diagnostics (`textDocument/diagnostic`) for servers that don't publish. Checked: "typescript
  ready", completions after `items.` (length, push, …), errors "Type 'string' is not
  assignable to type 'number'" and "Identifier expected".
* 6, compact layout: Terminal has Terminal / Debug / Output chips and New terminal; More adds
  Search and Source Control (chips scroll); starting a debug session opens the Terminal
  destination's Debug tab. Checked at ~570 dp: Source Control lists changes and the branch.
* 7, Python environment chip: status bar "Python: <env>" for .py files (or "Select Python"),
  opening the interpreter picker. ty gets the environment at start (CONDA_PREFIX for conda,
  VIRTUAL_ENV for venvs; allowlisted in ty's catalog entry; RPC starts now carry env),
  basedpyright gets `python.pythonPath` unless set; changing it restarts the server. Checked:
  miniconda3 → "Cannot resolve imported module `torch`"; fourteen → the error is gone.
* Seen: the interpreter picker still listed ~/.venv after it was deleted (cached discovery);
  Linux Terminal and Settings came to the front twice during tests (stray taps outside the app's
  window after resizes).
* Guest: daemon updated twice, bridge-4, ty reinstalled for its allowlist (needs uv on PATH:
  run `tools install` with ~/.local/bin on PATH), typescript 7.0.2 installed. Test files
  removed; the app is left installed and connected.

## 18. Stale environments, problems in the outline (10-28 15:10–15:28; done, checked)
* Stale environments in the pickers: the bridge generated a kernelspec per environment it found
  (`~/.local/share/avf-studio/kernels/avf-*`) and never removed them; `avf--venv` outlived the
  deleted ~/.venv. Bridge-5 prunes its own specs whose interpreter is gone, and hides (doesn't
  delete) any other spec naming a missing interpreter. Checked: the picker lists fourteen,
  miniconda3, system and python3, and offers "Create ~/.venv" again.
* Problems in the outline (setting `outline.problems.enabled`, Settings → Editor, on): each
  Outline entry shows a count badge in its worst problem's colour, counting problems that start
  inside the symbol (a class counts its methods'); a "Problems only · N" filter keeps those
  symbols. The Problems list groups by file (foldable header with a count; errors first, then
  by line). Checked on a test file: Store 3, add 2, helper 2, size and clean none; the filter
  left Store, add, helper; the Problems list showed otest.py · 5.
* Backlog: AI chat session history and management (user).
* Test file removed; the app left installed and connected.

## 19. Problems in the editor, untitled files, Explorer tree, Copilot restart, chat history (10-28 16:10–20:20; done, checked)
User's pick from the backlog list: 1-4 and 6 (5, custom keybindings, skipped for now).
* (1) Problems in the editor, with sora's diagnostics support:
  * Wavy underlines in the problem's colour (the Problems list's colours, `problemColor`). A tap,
    or a mouse hover, shows a tooltip with the message, `source code` and "Quick fix…".
  * Unused code is faded and deprecated code struck through (LSP tags; `tagSupport` advertised).
    The span overlay restyles copies of the analyzer's spans and restores the originals.
  * Marks beside the scrollbar; a tap moves the cursor to that problem.
  * Settings → Editor: `editor.renderValidationDecorations`, `editor.showUnused`,
    `editor.showDeprecated`.
  * More linting (Settings → Language servers):
    * `avf.python.lintRules`: Default / Extended (B, UP, SIM, I, C4, RUF) / All. It becomes Ruff's
      `lint.extendSelect`, sent as `initializationOptions.settings`.
    * `avf.python.typeChecking`: Default / Off / Strict. It sets basedpyright's `typeCheckingMode`
      and ty's `ty.configuration.rules.all`.
    * Checked locally with ty 0.0.94 / Ruff 0.17.3 over LSP. `all = "error"` turns on
      possibly-unresolved-reference and raises warnings to errors; `"ignore"` leaves none.
      Ruff's "ALL" adds D100, ANN001 and others.
  * Code actions now come from every server of the file (ty and Ruff for Python). Each server
    gets back only its own diagnostics at the cursor, with their `data`, which Ruff builds its
    fixes from. Ruff's "Remove unused import" and "Organize imports" now show.
  * Fixed: workspace edits (quick fixes, rename) on the open file replaced its whole text, which
    sent the cursor to line 1 and dropped the colours. They are now applied in place.
  * Fixed: restarting a server (interpreter, server or lint picker) did nothing. `close()` marks
    the server stopped only after shutdown finishes, and the restart ran before that. It now
    waits for `LanguageClient.stop()` and drops that server's old problems.
  * Checked on the tablet: underlines and faded `os`; the tooltip on `undefined_name` ("Ruff
    F821", Quick fix… lists ty's ignore action); Ruff's quick fix removed `import os` with the
    cursor kept; type checking Off hid ty's problems; switching back restarted ty, whose problems
    returned.
* (3) Untitled files get completion and lint:
  * Servers see them as `<workspace>/.untitled/<name>`, never created on disk (ty and Ruff accept
    it). Diagnostics, locations and workspace edits map back to the `scratch:` document.
  * The status-bar language of an untitled file opens a picker (Plain text, Python, TypeScript,
    JavaScript, TSX, JSX), which gives the file that extension.
  * Copilot inline suggestions stay with saved files.
  * Fixed on the way: the first build crashed at launch. Before a workspace exists,
    `scratch:/welcome.py` has no virtual path; `lspDocumentUri` now keeps such URIs as they are.
  * Checked: Untitled-1 → Python → underlines, faded `sys`, problems listed under
    Untitled-1.py; welcome.py reports 4 problems after a restart.
  * Once, on the first launch after install, welcome.py showed none until the next edit. That
    didn't happen again after three restarts. The new `AVFLSP` log tag (`setprop
    log.tag.AVFLSP DEBUG`) logs didOpen and the diagnostics received.
* (4) Explorer tree (`avf.explorer.tree`, Settings, on; off keeps one folder at a time):
  * Folders open in place, indented, with VS Code's chevrons. The open-folder icon was too close
    to the closed one at this size.
  * A Collapse folders button, and "Show only this folder" in a folder's menu.
  * Open folders are remembered across launches and reloaded after file changes.
  * Checked: data → MNIST → raw, Collapse, and data still open after an app restart.
* (2) Copilot exiting after a daemon restart: not reproduced. Five restarts from a transient unit
  each came back to Copilot · Normal within about 6 s. No leftover servers: `KillMode=control-group`,
  and one ty, Ruff and Copilot under the new daemon. The journal had nothing, because the daemon
  logged server exits at debug. Changes:
  * The daemon logs exits and kills at info, with the last 20 stderr lines. The new daemon is
    installed in the guest, with the old binary kept as `avf-native-daemon.previous`.
  * The app retries the start three times (3, 6 and 12 s) instead of once.
* (6) AI chat history. Copilot CLI 1.0.105 keeps its sessions and offers `session/list`,
  `loadSession` and `session/close` over ACP, so the app stores nothing itself:
  * "Past chats" (history icon in the chat header): this workspace's chats, newest first, with
    their age. Tap one to replay it into the pane and continue it.
  * "New chat" (+) starts an empty chat; the previous one stays in the history.
  * A chat's menu offers Rename and Remove from list. ACP has neither, so the name is the app's
    own and removing hides the chat; Copilot keeps its files under `~/.copilot/session-state`.
  * The chat shown continues after the agent stops (idle, reconnect, effort change, Stop): the
    next message reloads its session quietly, without showing the replay again. Setting
    `avf.copilot.chatResume` (on).
  * Checked: the list showed the four chats; the temp-partition chat replayed; Stop, then "What
    word did you reply with before?" got "pong" in the same session (now "Just now", no new
    entry); a rename stayed; a removed chat stayed gone after reloading.
  * Small gap: the header's Stop button can miss that the agent started for the list, until the
    pane recomposes.
* Tests: 155 app tests and all daemon tests pass. The tablet was left with welcome.py as it was,
  the test file deleted, type checking back to Default, and the app installed and connected.

## 20. Chat Stop button, Copilot in untitled files, margin problem dots, editor-neutral controller (10-28 21:20–22:25; done, checked)
User's pick: 1-3 and 5 of the list after batch 19. Also registered: sticky notebook cell
headers and toolbar.
* (1) The chat header's Stop button followed `AcpClient.isStarted`, which Compose can't
  observe. The client now reports start, stop, exit and disconnect through `runningListener`,
  into `chatAgentRunning`. Checked: Stop shows once Past chats starts the agent, and goes away
  when tapped.
* (2) Copilot inline suggestions in untitled files. The scratch gates are gone, and
  `CopilotClient` maps `scratch:` paths to the same `.untitled/` URIs as the language servers
  (`lspDocumentUri`). Checked: Untitled-1.py → `def fibonacci(n):` got a ghost-text body; Tab
  inserted all 13 lines with the cursor at the end.
* (3) Problem dots in the line-number margin: a dot in the line's worst problem colour; a
  breakpoint wins on the same line. It follows `editor.renderValidationDecorations` (the
  setting's text now says so). Fixed on the way: sora keeps line styles in the order they're
  added and looks them up by line, so they're added in line order; line 1's dot was missing
  before. Checked: a warning dot on `import os`, an error dot on `x = missing`; line 1's dot
  turned red when indenting made it a syntax error.
* (5) The controller no longer knows sora. It talks to `EditorSurface`, which gained the cursor,
  offsets and positions, insert/delete, outdent, select all, undo/redo, focus, editor settings
  (`EditorConfig`), line marks (`LineMarks`), problems and the ghost-text flag. The defaults
  work from the text alone, so another engine only overrides what it does faster.
  * `SoraEditorSurface` (workbench) holds all sora calls the workbench used, including the
    problem drawing and the settings application.
  * The neutral problem model moved to `editor/EditorProblems.kt`.
  * `EditorLineCommands` (line moves, bracket jump, Tab indent) take a surface.
  * sora is now only in the adapter, the editor pane (its view, theme and ghost text) and the
    TextMate theme and grammar code: the pieces an Aardink switch replaces.
  * New `EditorSurfaceDefaultsTest`: offsets, insert, outdent, select all, Tab indent.
  * Checked on the tablet: Select All + Tab indented both lines and kept the selection,
    Shift+Tab undid it, Find selected the match, and quick fix and completion still place the
    cursor.
* 159 tests pass. The test untitled file was closed without saving; the app is left installed.

## 21. Sticky cell header, cell run statistics, native notebook outputs (10-29 08:50–09:10; done, checked)
User's pick: 1, 2 and 4 of the backlog list after batch 20 (port 36601).
* (1) Sticky cell header (`avf.notebook.stickyCellHeader`, on). When a cell taller than three
  headers has its top scrolled away, a header stays at the top of the notebook. It shows Run (or
  the spinner), the cell's first line (a tap goes back to the cell's top), its run status and,
  on the selected cell, its actions. The cell's end pushes it out, like a sticky list header.
  The choice is the pure `stickyCell` (NotebookSticky.kt). Checked: the tall cell's header
  stayed with its spinner and live timer while it ran, and shows Run on the output-heavy cells.
* (2) Cell run statistics (`avf.notebook.cellStatistics`, on). The gutter under `[n]` shows a
  live timer while the cell runs, "queued" while it waits, then a check or a cross with the run
  time. A tap puts the finish time in the status bar.
  * Timing comes from the bridge's "busy" notice. It runs executes one at a time in the order
    sent, so each notice starts the oldest waiting cell (`NotebookSession.executing`). The
    bridge needed no change. This also fixes which cell shows the spinner when cells run out of
    notebook order.
  * Results: an `error` reply (an exception or an interrupt) is failed; `aborted` (skipped after
    an earlier error) clears the mark; a kernel exit fails the running cell. Clearing outputs
    clears the marks, as VS Code does.
  * `avf.notebook.recordTiming` (off) saves each run's start and end in the cell's
    `metadata.execution`, as JupyterLab's recordTiming does. Timings saved by either show
    again on reopen. A new run drops a stale timing, as JupyterLab does.
  * Checked: ✓ 1.9s, ✓ 4.0s for the tall cell, ✗ for `1 / 0`. With the setting on, the
    marks were back after closing and reopening. The setting was turned off again afterwards.
* (4) Native notebook outputs (NotebookOutputs.kt):
  * DataFrames (pandas and polars `<table class="dataframe">`, parsed by `OutputTable.kt`)
    draw as native tables in theme colours: header rows stay while the rows scroll, numbers
    align right, rows are striped and the index is shaded. MultiIndex spans are blank as in
    pandas, and filled in once sorted. A tap on a header sorts ascending, then descending, then
    off; missing values stay last. The caption keeps pandas' "rows × columns" or polars' shape.
    `avf.notebook.nativeTables` (on); off shows the HTML as before.
  * The gutter beside the outputs folds them away. This is saved as JupyterLab's
    `jupyter.outputs_hidden` plus classic `collapsed`, and both are read.
  * On the selected cell, each output has a menu:
    * Copy: the table as TSV in its sort order, or the text.
    * Show as table, HTML, plain text, image or Markdown (whatever the output has); Settings
      pick the default.
    * Clear this output, or clear the cell's outputs.
  * Text outputs longer than `notebook.output.textLineLimit` lines (VS Code's key, 30) scroll in
    a box, with "Show all N lines". While the cell runs, the box follows the newest line unless
    you scroll up.
  * What stays a WebView: Plotly, and HTML that isn't a DataFrame (Stylers, rich HTML).
  * Checked: the 25-row frame drew as a table, sorted by score both ways, and copied ("Output
    copied"). Show as HTML switched it back. The MultiIndex frame drew with its spans. The
    120-line loop scrolled after 30 lines. Folding saved `outputs_hidden` and survived a reopen.
* 172 tests pass. New tests: OutputTableTest (pandas, MultiIndex, polars, other HTML, sorting,
  TSV), CellRunsTest (JupyterLab timing, recording, folding keys, durations) and
  NotebookViewTest (sticky choice, views per Settings, copy text, headlines). The test notebook
  `~/Projects/batch21.ipynb` stays in the guest for trying these; the app is left installed.

## 21b. Output menu on hover; why the daemon "dies", and the fix (10-29 09:15–10:05)
* The output menu (⋮ beside each output) now also shows while a mouse or touchpad pointer
  rests over the output, not only on the selected cell. Its open state is kept by the output
  row, because the open menu takes the pointer and ends the hover. Checked on the tablet: the
  selected cell still shows it. Not checked with a real pointer: the `hid` virtual mouse
  registered but moved no pointer on this tablet.
* Investigation (user: the daemon stops during various tasks; reopening the guest fixes it).
  **The daemon never died.** The guest journal shows no unexpected exit, no OOM kill and no
  restart of `avf-native.service`.
  * What fails is Linux Terminal's port forwarder in the guest,
    `forwarder-guest-launcher.service` (`/usr/bin/forwarder_guest_launcher`, root). It reads
    `/usr/sbin/tcpstates-libbpf` (package libbpf-tools), which prints
    `SKADDR PID COMM LADDR LPORT RADDR RPORT OLDSTATE -> NEWSTATE MS`, and splits each line
    on whitespace.
  * When a process or thread name (COMM) has a space, LPORT shifts onto text and the launcher
    exits: `Error: Invalid LPORT format / invalid digit found in string`. systemd restarts it
    in a second, and it reports 19870 as "already opened". But the host never forwards it again
    (no `executing forwarder_guest` afterwards), so the app can't reach a daemon that is still
    running. Only a guest restart recovers.
  * Seen mid-session on 10-28 00:01, 02:38, 10-29 03:13 and 03:16 UTC. The last two were when
    the user reopened the guest.
  * Names with spaces are common now. Python 3.14 copies thread names to the OS (`Thread-2
    (_watc`, ipykernel's `Shell channel`), so Django's dev server threads, a notebook's
    threads and others trigger it. The 03:16 crash came seconds after Django's port 8000 was
    used.
  * Reproduced on 10-29 12:25 UTC: one `listen()` from a Python thread named `name with space`
    crashed the launcher and cut the forward (`/health` stopped answering). Stopping and
    starting the daemon did not bring it back; a guest restart did.
  * Fix (user's pick: a Settings row, and apply it; done and checked the same morning):
    * The wrapper is `guest/tools/tcpstates-space-safe.pl`. It runs the real tool through
      `stdbuf -oL` and joins a spaced COMM with `_`.
    * The daemon embeds the wrapper. `sudo avf-native-daemon guest fix-port-forwarding [--undo]`
      (forwarder.rs) diverts the tool with `dpkg-divert --local --rename` and installs the
      wrapper in its place; undo reverses both. It doesn't restart the forwarder, since a
      restart is what loses the forwards, so the fix applies from the next guest start. The
      subcommand runs before any daemon set-up, so as root it creates nothing.
    * The stats report `forwarder_fix_installed` / `forwarder_fix_active`. Active means a
      process runs `tcpstates-libbpf.real`, i.e. the forwarder started through the wrapper.
    * Settings → Guest tools → Port forwarding fix shows Off with Apply, then "takes effect when
      the guest restarts", then On, with Undo. It runs in a visible terminal, like /tmp.
    * `scripts/install-native-daemon.sh` applies it on new guests when sudo works without a
      password.
    * Checked on the tablet: Apply ran in the terminal, and the diversion was added. After a
      guest restart the row reads "On (installed by MaterialCode)". The reproduction then used
      threads named `name with space` and `Thread-9 (serve)`. The forwarder didn't restart and
      logged no error, `/health` still answered through the forward, and the forwarder logged
      the ports as `name_with` and `Thread-9_(` in real time.
  * Upstream report draft: [docs/upstream/terminal-port-forwarder-space-in-name.md](upstream/terminal-port-forwarder-space-in-name.md),
    not filed yet. Its dependency-free reproduction (`nc` copied to `/tmp/my nc`) was checked:
    the column read as LPORT holds `127.0.0.1`.

## 22. Open folders and recent ones, outputs with scripts, app clean-up (10-29 10:10–10:50; done, checked)
User's pick: 2 and 3 of the backlog list after 21b, and "clean up the behaviour of the app
(welcome.py always open, for example)".
* (2) Open Folder and Open Recent.
  * File > Open Folder (Ctrl+K Ctrl+O; also in the ⋮ menu, from a tap on the workspace name in
    the top bar, and on the empty editor) opens a dialog:
    * Recent folders, each removable, then the guest's roots as chips.
    * A folder browser: up stays inside the roots, hidden folders come last, and a new folder can
      be made in place.
  * Opening a folder makes it the workspace, as VS Code does in its window:
    * Tabs close with their drafts kept (hot exit), and come back with that folder.
    * Language servers, Copilot and the chat agent restart in the new folder, and Explorer, Search,
      Quick Open, Git and Problems reset. Terminals keep running.
    * The app reopens the last folder on the next connect, or the guest's first root if the
      folder is gone.
  * Sessions are saved per folder (`session:<path>`); the old single session is read once as a
    fallback. Recent folders: `recent-workspaces`, at most 10.
  * The Explorer treats the open folder as its top, even when the daemon serves folders above it.
  * Quick Open no longer walks hidden folders (except `.github`, `.vscode`, `.devcontainer`) or
    conda installs, so a home folder stays fast.
  * Daemon: a local guest now serves `~/Projects:~` (`AVF_WORKSPACE_ROOTS`, installer); a LAN
    host still serves only its workspace. The tablet's unit was updated (the old one is kept as
    `avf-native.service.before-home-root`).
  * Checked: Projects → django_example (tabs closed, README.md from the old session) → app
    restart (django_example came back) → Open Recent → Projects (all five tabs and the expanded
    folders back).
* (3) Outputs with scripts (`avf.notebook.scriptOutputs`, on). As in VS Code, they run in their
  own WebView: JavaScript and network on, no bridge into the app, no file or content access, and
  the view grows with its content.
  * Vega and Vega-Lite types (`application/vnd.vega(lite).vN+json`) draw with vega-embed from
    jsDelivr (v6 specs get the 6.x libraries).
  * HTML with scripts runs: Altair's HTML renderer (what Altair emits outside JupyterLab), folium
    and pyvis.
  * Bokeh 3 splits a plot into an HTML `<div>` and a JavaScript-only output with
    `bokehjs_exec`. A JavaScript-only output now runs inside the HTML output before it. The
    loader from `output_notebook()` runs first; without one, BokehJS loads from Bokeh's CDN at
    the output's version.
  * Live ipywidgets need the kernel's comm messages, which the bridge doesn't carry. They show
    their text form and a note, and are a new backlog item.
  * Checked with `~/Projects/batch22.ipynb`, made by nbclient in a throwaway `uv run` environment
    (altair, bokeh 3.10.1, ipywidgets; the user's environments untouched): the Altair bar chart,
    "BokehJS 3.10.1 successfully loaded", the Bokeh line plot with its toolbar, the Altair HTML
    chart, and the slider's text with the note. nbclient's timing showed as run marks, which
    also confirms reading saved timings.
* Clean-up:
  * No scratch welcome.py any more. Nothing opens by default. The empty editor offers Open
    folder (or Connect), New untitled file and the last five folders.
  * Untitled files come back after a restart with their text, as VS Code's hot exit does
    (`session-untitled`). Checked: Untitled-1 survived a force-stop.
  * The side bar view, the panel (shown, and its tab) and the chat pane come back on the next
    launch, under `avf.session.restore`.
  * Status bar messages clear after 10 seconds, as VS Code's do; Output keeps them all.
  * A notebook that names its kernel shows it ("Python 3 · Not started") until it starts,
    instead of "Select kernel".
  * Settings toggles: script outputs got one; the rest follow `avf.session.restore` or need none
    (Output keeps the status history).
* 176 tests pass (new: script outputs and Bokeh pairing, per-folder sessions). The app is left
  installed.

## 23. Parameter hints, symbol highlights, save clean-ups and format on paste (10-29 11:00–11:55; done, checked)
User's pick: 6, 7 and 11 of the list after batch 22 (spec candidates 1, 2 and 6).
* Parameter hints (`editor.parameterHints.enabled`, on; Ctrl+Shift+Space shows them):
  * The client now advertises signatureHelp (context, label offsets, active parameter) and keeps
    the server's trigger and retrigger characters.
  * Typing a trigger (`(`, `,` for ty) asks for hints. While they show, any edit or cursor move
    asks again, as a retrigger carrying the hints shown, so the bold parameter follows and the
    card goes when the cursor leaves the call.
  * The card sits above the cursor's line (below it on the first lines). It shows the signature
    with the current parameter in bold in the theme's primary colour, its documentation (or the
    signature's), and "1/3" for overloads. Escape closes it; switching files closes it too.
  * Checked with ty: `result = scale(` showed `(values: list[float], factor: float = ..., *,
    offset: float = ...) -> list[float]` with `values` in bold and the docstring under it.
    After `x,` the bold moved to `factor`; Escape closed it.
* Symbol highlights (`editor.occurrencesHighlight`, on; "off" or false turns it off):
  * `textDocument/documentHighlight` runs 250 ms after the cursor rests, with an empty
    selection. The uses get a background through the span overlay (a new background colour id in
    the style bits); writes are stronger (VS Code's wordHighlightStrongBackground).
  * Dark+/Light+ colours by default; Material themes use the primary for reads and the tertiary
    for writes. An edit clears them until the next answer, and a stale answer is dropped.
  * Checked: the cursor on `total` marked its four uses, with the two assignments lighter.
* Save clean-ups, VS Code's keys, all off by default: `files.trimTrailingWhitespace` (Markdown
  is left alone, since two spaces are a line break there), `files.insertFinalNewline` and
  `files.trimFinalNewlines`.
  * They run on every save, the auto ones included. An auto save leaves the cursor's line
    untrimmed, as VS Code does. The open editor gets them as edits (so they undo); a file saved
    in the background gets them in its text.
  * The edits come from a pure function (`saveCleanupEdits`), which keeps `\r` of CRLF breaks.
  * Checked: `notes23.txt` (trailing spaces, a tab, three blank lines at the end) saved as
    exactly `line one\nline two\nlast line\n`.
* Format on paste (`editor.formatOnPaste`, off): range formatting of what was pasted, when the
  formatter offers it (Ruff does). It applies only if nothing was typed since the paste. Pastes
  from sora's own menu and selection handles now go through the workbench too.
  * Checked: pasting `y=[1,2 ,3]` gave `y = [1, 2, 3]`; the original line stayed.
* Fixed while checking: opening Settings with an unsaved file and auto save on focus change
  crashed. The auto save ran after the editor pane had gone, and the clean-up edits went to a
  closed editor ("Document is not open"). `TextEditorHost.isOpen` now sends them to the text
  instead. Checked: the same steps no longer crash.
* The four new save and paste settings were switched back off after the checks. Test files
  `~/Projects/batch23.py` and `notes23.txt` stay in the guest. 183 tests pass (new:
  SignatureHelpTest, SaveCleanupsTest).

## 24. Claude Code and Gemini, offline mode, inlay hints, Markdown preview, pinch to zoom (10-29 21:20–22:30; done, checked except pinch)
User's pick: 3, 5, 7, 13 and 11 of the list after batch 23 (port 36601).
* Chat agents (`avf.chat.agent`: copilot, claude-code or gemini; a picker in the chat's title and
  in Settings → Chat, plus "Chat: Use …" commands):
  * Catalog tools `claude-code` and `gemini` (ACP agents). Both install through one bundled guest
    script, `avf-node-agent`, which fetches Node.js 24.22.0 (official tarball, SHA-256 pinned)
    and the pinned npm package into ~/.local/share/materialcode: Claude Code's ACP adapter
    `@agentclientprotocol/claude-agent-acp@0.91.0` (it carries the Claude Code CLI) and
    `@google/gemini-cli@0.64.0` (`gemini --acp`). Nothing is global; `claude-agent-acp`,
    `claude` and `gemini` commands are written to ~/.local/bin unless the user has their own.
  * Daemon: script installs can name `setup` arguments, which the installed script runs once
    (output in the installer's terminal); a failing setup fails the install.
  * Not installed: the first message says so and offers the install (Guest dependencies rows
    too). Sign-in: both answer -32000 "Authentication required" like Copilot; the pane's Sign in
    runs `avf-node-agent login <agent>` in a terminal and lifts the sign-in link into a chat card
    with "Open sign-in page" and a code field that types into the waiting login. Gemini then
    needs `/quit`, which the card's Done sends. Gemini also takes `GEMINI_API_KEY` in
    ~/.gemini/.env.
  * Each agent keeps its own model and mode choices; the reasoning-effort chip is Copilot-only
    (Claude Code lists effort as a session option). Switching agents starts an empty chat.
  * Checked on the tablet (aarch64): both installed from the chat's offer (Claude Code ~330 MB,
    Gemini ~100 MB, Node.js 205 MB shared), started, listed their options (Claude Code: Mode
    Manual, Model, Effort, Fast mode) and asked for a sign-in; the cards showed the claude.com
    and accounts.google.com links and the terminals waited for the code. Sign-in itself needs
    the user's accounts and was left to the user (both cancelled). Running: Claude Code 312 MB
    (Node 94 + CLI 217), Gemini 375 MB (it restarts itself as a second Node process).
  * Fixed while checking: Gemini's login chose Google sign-in in a new ~/.gemini/settings.json;
    after a cancelled sign-in, `gemini --acp` then printed its sign-in prompt into the protocol
    stream and read the app's first request as the code. The login now takes that choice back
    when no credentials were saved (and the tablet's stray file was removed).
* Offline mode (`avf.offline.openLastFolder`, on):
  * Without a guest (auto-connect fails, or no connection set up), the last folder opens from
    this device's copies (the encrypted document cache): its tabs as they were, editable. Saves
    queue as before ("Saved locally; queued until guest reconnects") and go to the guest on
    reconnect; clean tabs that changed in the guest meanwhile are read again.
  * Explorer offline lists the folder's files this device has a copy of (opened since, plus the
    session's tabs); the start page lists recent folders to open offline.
  * Files on the device: "Open from device…" (start page) and "File: Open File from Device..."
    use Android's file picker. They open and save through their content URI (persisted
    permission), come back after a restart, and have no language servers or Copilot (path
    `device:/<encoded URI>/<name>`; the crumb reads "This device").
  * Checked: with the daemon stopped and the app restarted, the tabs and the offline Explorer
    came back; an edit to batch24.py saved locally and was written to the guest after Retry
    ("Reconnected and saved …"). `device24.txt` from Downloads opened, survived a restart, and
    its edit landed in the file on the device.
  * Fixed while checking: a device file crashed the app (its path went into a `file://` URI for
    the problem list). `lspDocumentUri` now keeps anything but absolute guest paths as is. Also
    quieted the "Connect to a guest to use Git" message from background refreshes.
* Inlay hints (`editor.inlayHints.enabled`, on; off/offUnlessPressed turn it off):
  `textDocument/inlayHint` for the visible lines ±100 (again on scrolling past them, on edits
  after 400 ms, on `workspace/inlayHint/refresh`). sora 0.23.6 has inlay-hint classes but no
  renderer and can't open a gap in a line, so the hints are chips at the line's end, each with
  its code: `total: int`, `values=data`. Checked with ty on batch24.py. Fixed: a problem dot
  widened the gutter and left the chips over the code until the next scroll.
* Markdown preview (Ctrl+K V to the side, Ctrl+Shift+V instead of the editor; two buttons at the
  breadcrumbs' end): commonmark-java 0.30.1 with GitHub tables, strikethrough, task lists and
  autolinks, in a WebView in the app's colours. Raw HTML passes as in VS Code, but a content
  security policy lets only the page's own script run. Relative images come from the guest,
  links to files open them, web links the browser. It scrolls with the editor
  (`markdown.preview.scrollPreviewWithEditor`, on) through each block's source line. Checked:
  table, tasks, code, the local PNG, and the `<script>` in the quote did not run. Fixed: the
  side layout's new editor lost its events (the old view's cleanup closed the new binding), so
  scroll sync, inlay hints and LSP changes stopped; `TextEditorHost.close(uri, surface)`.
* Pinch to zoom (`avf.editor.pinchToZoom`, on): sora's own pinch, bounded to 8–40, and the size
  is saved as `editor.fontSize` 600 ms after the fingers stop. Not checked: adb can't inject two
  fingers here (`sendevent` is denied on the touchscreen).
* Guest lesson: `cp` from /mnt/shared made crosvm's virtio-fs panic and killed the VM (the new
  daemon was left at 0 bytes); updates now read the share with `cat`.
* Test files `~/Projects/batch24.{py,md,png}` and `/sdcard/Download/device24.txt` stay. The chat
  is back on Copilot. 198 tests pass (new: InlayHintsTest, ChatAgentsTest, MarkdownRendererTest,
  device and offline repository tests), plus 2 daemon tests.

## 25. Keyboard shortcuts, Quick Open modes, image viewer, Ports, preview and pinned tabs (10-31 20:40–21:25; done, checked except pinch)
User's pick: 1, 5, 7, 8, 13 and 11 of the list after batch 24 (port 39891).
* Custom keyboard shortcuts (`keybindings.json`, VS Code's format, kept on the device like user
  settings): Keyboard Shortcuts (Ctrl+K Ctrl+S) lists every command with its keys; a tap opens a
  recorder that takes one stroke or a two-stroke chord from the keyboard (Enter saves, Escape
  cancels), warns about keys another command has, and offers Remove and Reset. It writes rules
  as VS Code does: the new key, plus `-command` rules that drop the defaults. "Edit as JSON"
  ("Open Keyboard Shortcuts (JSON)") takes comments and trailing commas; rules for unknown
  commands or keys no keyboard here sends (cmd/meta) are skipped and named. `when` clauses are
  kept but not evaluated: a rule works where its command works (editor-only commands in the
  editor). No setting: the file is the setting. Checked: recorded Ctrl+Alt+K for Toggle Word
  Wrap, which then wrapped the editor while Alt+Z did nothing; the JSON matched; Reset brought
  Alt+Z back.
* Quick Open modes by prefix, as in VS Code: `>` commands (Ctrl+Shift+P is now Quick Open with
  `>`), `@` symbols in the file (Ctrl+Shift+O; the Outline view moved to "Show Outline" and the
  rail), `#` workspace symbols (Ctrl+T; `workspace/symbol` from the Python and TypeScript
  servers, 200 ms after typing), `:` line and column (Ctrl+G; the status bar's Ln/Col still opens
  the number-pad dialog), `?` the list of modes. Checked with ty: `@` listed batch23.py's
  symbols with kind and line, `#sca` found `scale` in batch23.py and batch24.py and opened it,
  `:3:12` went to line 3 (an empty line, so column 1).
* Code folding: not possible with sora 0.23.6. It has a `FoldingRegion` class that nothing uses,
  and no way to hide lines. Folding comes with the Aardink switch (it has folding built in).
* Image viewer: PNG, JPEG, GIF (first frame), WebP, BMP, ICO, HEIC and AVIF open in a viewer
  tab instead of failing as binary, from the guest or the device (up to 16 MB; sides over 4096 px
  are subsampled). Pinch and Ctrl+wheel zoom around the fingers, drag or wheel pans, a double tap
  switches fit/actual size, and a toolbar has zoom out/in, the percentage (tap for 100%) and fit;
  Ctrl+= and Ctrl+- zoom the image. Actual size is one image pixel per dp; zoomed in past 2×,
  pixels stay square. Transparent parts show a checkerboard. SVG files open drawn by AndroidSVG
  1.4 (Apache-2.0; no scripts, nothing fetched), vector-sharp at any zoom, with the Markdown
  preview's two buttons to show the source beside it or alone; edits redraw it
  (`avf.svg.openAsImage`, on; off opens SVG as text). Checked: batch24.png (120×60) at 100%,
  fitted (696%) and back; batch25.svg drawn, sharp when fitted, and side by side with its
  source. Not checked: pinch (adb can't inject two fingers) and a double tap (two adb taps are
  too far apart).
* Ports (a panel tab, and "Show Ports"): the daemon's port scan (it already sent ports opening
  and closing) now carries the command line of the process holding each port, found through
  `/proc/<pid>/fd` socket links for new ports only. The view lists port, command and
  `localhost:<port>`, with Open in browser and Copy address. Hidden unless asked: the daemon's
  port, Jupyter kernels', the app's bridges and other users' services (ssh 22, DNS 53, Linux
  Terminal's ttyd 7681). A server starting in the guest is announced in the status bar
  (`remote.autoForwardPortsNotify`, on). Checked: `python3 -m http.server 8765` showed up with
  its command and the status message; Open in browser opened Firefox at localhost:8765, which
  loaded once Linux Terminal's "open a new port" notification was accepted; stopping the
  server removed the row. The guest daemon was updated (sha256 1c783243…ce0f, copied with
  `cat`).
* Preview and pinned tabs (`workbench.editor.enablePreview`, on): a single tap in Explorer or on
  a search match opens an italic preview tab that the next one replaces; editing it, a second
  tap within 500 ms, a double tap on the tab, Pin, or Keep Editor Open (Ctrl+K Enter) keeps it.
  Notebooks never open as preview (closing one would stop its kernel). A long press on a tab
  opens Pin/Unpin, Keep open, Close, Close others and Copy path. Pinned tabs (Ctrl+K
  Shift+Enter toggles) go first, show a pin instead of the close button (tap it to unpin), and
  stay on Close Other Editors. Both are saved with the folder's session. Checked: previews
  replaced each other, two quick taps kept batch23.py, Pin moved it first, and after a restart
  it was still pinned and the SVG still a preview.
* Also: the status bar hides line, encoding and language for an image.
* Test file `~/Projects/batch25.svg` stays (with batch24's). 209 tests pass (new:
  KeybindingsTest, QuickOpenTest, the port packet test), plus the daemon's port tests (one new:
  finding a socket's process).

## 26. Variable explorer (10-31 21:25–21:35; done, checked)
User's pick: 12 of the list after batch 25.
* Variables (a panel tab; the notebook toolbar's `{}` button; "Jupyter: Show Variables"), as
  VS Code's Jupyter Variables: the notebook's kernel's names with type, size (rows × columns,
  length) and a short value; modules, functions, classes, `_` names and IPython's own are left
  out (first 500 names). A filter field and a refresh button; a tap on a plain row shows its
  whole value. It follows the active notebook (or the last one shown) and refreshes after each
  run while the tab is open; a closed tab asks the kernel nothing, so no setting.
* DataFrames, Series and 1–2 dimensional arrays open in a data viewer: their first 1,000 rows
  in the notebook's native table (index first, numbers right-aligned, tap a column to sort).
* Bridge: `variables` and `table` requests go through the same queue as cell runs (one at a
  time, after the cells before them). Each one is a silent execute (no history, no execution
  count) that defines a small helper and reads its JSON back as a user expression. Other kernels
  than Python say the view is for Python. The guest daemon was updated (sha256 82ab7469…d85f)
  and the bridge reinstalled with `tools install jupyter`.
* Checked with batch25.ipynb on Python (fourteen): after Run all the view listed arr (3 × 4),
  config, df (DataFrame 500 × 3), ratio, rng, summary (Series), total and words; df opened in
  the viewer (500 rows) and sorted by radius descending; arr opened as a 3 × 4 table; a new cell
  `extra = 7` ran as [3] (the silent looks took no counts) and `extra` appeared.
* Test file `~/Projects/batch25.ipynb` stays. 211 tests pass (new: KernelVariablesTest).

## 27. Syntax colours on demand, replace in files, Explorer marks, conflicts and side-by-side diff, terminal find and links, coloured outputs, launcher shortcuts and Open with (10-31 21:40–22:20; done, checked)
User's pick: 1, 6, 7, 8, 9, 10 and 14 of the list after batch 26 (port 39891).
* Syntax colours on demand (`avf.languages.download`: ask, always, never; Settings → Languages):
  a file with no grammar whose type one of VS Code's 46 built-in language extensions covers
  (SQL, Rust, Go, YAML, shell, C/C++, Dockerfile, Makefile…) gets a bar above the editor:
  Download, Not now, Never. The pack comes from Open VSX (`vscode` namespace, 1.95.3, 10–60
  KB), pinned by version and SHA-256 in the app (`assets/LanguagePacks.kt`, generated from
  open-vsx.org on 10-31), installed with the existing .vsix installer, and the open editors are
  made again with the colours. Exact names (Dockerfile, Makefile) now count as well as
  extensions. Settings lists the installed ones (Remove) and "Get colours for a language…" picks
  any pack. Checked: batch27.sql offered "vscode.sql 1.95.3", downloaded and was coloured.
* Replace across files (Search's chevron opens a replace field): each match previews the old
  text struck through and the new beside it; a button per match, per file, and Replace All
  (asks first with the count). Regex replacements take `$1`, `${name}`, `$0`/`$&`. Matching is
  per line, like ripgrep. Open files change in their editor and stay unsaved; closed files are
  read, changed and saved (a conflict leaves them alone and says so). Checked: one match, then
  Replace All (case-insensitive) in a closed file, confirmed with `cat`.
  * Found while checking (from batch 9): search columns were one to the right. The daemon sends
    ripgrep's 1-based column and the app took it as 0-based, so highlights were shifted and a
    tapped result landed one column late. Fixed in the codec; the protocol field is documented.
* Explorer marks (`git.decorations.enabled`, `problems.decorations.enabled`, both on): changed
  files in VS Code's Git colours with their letter (M, U, A, D, R, ! for a conflict), files with
  problems in red/yellow with their count, and folders take the most important colour below
  them with a dot. Git status refreshes when Explorer opens and with its refresh button.
  Checked: untracked files green with U, data/ and django_example/ with a dot, batch27_err.py
  yellow with 1.
* Merge conflicts: each conflict is tinted as in VS Code (current green, incoming blue, markers
  stronger) with Accept Current / Incoming / Both on the `<<<<<<<` line; commands for the one at
  the cursor, all current/incoming, next/previous. A conflicted file in Source Control opens in
  the editor. Checked: Accept Incoming then Accept Both on batch27_conflict.py ("No conflicts
  left …; save it and stage it").
* Side-by-side diff in Source Control's diff dialog (default where the screen is ≥ 700 dp wide;
  Inline beside it): old left, new right, line numbers, a changed line's changed part marked.
  Checked on a throwaway repository (created in ~/Projects/b27repo and removed afterwards).
* Terminal: Ctrl+F in a terminal (and a search button) opens a find bar that lists the matching
  lines of its output, matches marked, following new output; a tap opens a link in the line
  (else copies it). "Terminal: Open Detected Link…" (link button) lists web addresses and
  file:line references (Python frames, `path:12:5`) newest first; files open at their line
  (relative ones from the workspace). Web addresses in the terminal were already tappable
  (the library's own detection). The library can't scroll its view to a line, so find lists
  the lines instead of jumping. Each terminal keeps a plain-text copy of its output for this
  (up to 10,000 lines). Checked with batch27_err.py's output: 4 links, frame :10 opened at line
  10, `batch27_words.txt:2:5` at Ln 2, Col 5.
  * Found while checking (from batch 12): jumping into a file that wasn't open yet ran before
    its text was in the editor; a column then crashed the app (sora throws past a line's end).
    The jump now waits for the text, and the editor clamps positions it is given.
* Notebook outputs keep their ANSI colours (16, 256 and RGB, bold, italic, underline, inverse;
  the terminal's palette) instead of stripping them, so IPython tracebacks look as in VS Code.
  Traceback references are links: `Cell In[2], line 4` selects that cell, `File ~/x.py:12` and
  `File "x.py", line 12` open the file at the line. Checked with batch27.ipynb.
* Launcher shortcuts (`avf.launcher.recentFolders`, on): long-pressing the app icon lists the
  last four folders; one opens that folder (now, or once the guest connects). "Open with": text
  and code files (text/*, JSON, JS/TS, XML, YAML, TOML, shell, Python, SQL, ipynb) from other
  apps open as device files (saved back when the app granted write). The activity is
  singleTask now, so these reuse the open window. Checked: the shortcuts registered, the
  OPEN_FOLDER intent switched the workspace, and a VIEW intent for Downloads/device24.txt
  opened it.
* Test files `~/Projects/batch27.{sql,ipynb}`, `batch27_conflict.py` (resolved),
  `batch27_err.py`, `batch27_words.txt` stay. 227 tests pass (new: LanguagePacksTest,
  SearchReplaceTest, DiffModelsTest, ExplorerDecorationsTest, TerminalTextTest, AnsiTextTest).

## Next pass (registered 10-27 23:35, updated 10-28 13:06)
* Batch 15 did the carried-over tests (Copilot signed out, the setup paths, reconnect cap,
  Copilot model list, prompt prefix). Left over:
  * Miniforge's Install row from the UI on a guest without conda (its command is verified).
  * Copilot models beyond "Auto" depend on the user's GitHub Copilot settings; nothing to do in
    the app unless the account offers more and the picker still shows one.

## Mapped for later (user, 10-27)
* Chat adjustments beyond the model/mode picker (batch 11). (Reasoning effort done in batch 16.)
* (Done in batch 24.) Claude Code and Gemini support alongside Copilot (chat agents; ACP where they speak it).
* (Done in batch 19.) AI chat session history and management (user, 10-28): keep past conversations (title, date,
  workspace), reopen one (ACP `session/load` where the agent supports it, else read-only
  history), rename and delete them, start a new one beside the current; check what Copilot CLI
  keeps itself (it lists sessions in its own store) before storing anything in the app.
* (Done in batch 11: auto-save modes after a delay, on focus change and on window change.)
* (Done in batch 24.) Offline experience: the editor alone when no guest is reachable (open/edit local and cached
  files, no tools).

## 28. Output types, cell drag and undo, Interactive window, key bar, next edit suggestions (11-01 11:00–11:50; done, checked)
User's pick: 2, 5, 6, 7 and 8 of the backlog list after batch 27 (port 39891), then a backlog
review against the spec.
* (5) More output types. `image/svg+xml` is drawn natively (AndroidSVG) at its own size, up to the
  output's width. `text/latex` (SymPy, IPython's `Math()`/`Latex()`) is drawn with KaTeX 0.16.11
  from jsDelivr in the isolated script WebView; offline it shows the source. `application/json`
  (IPython's `JSON()`) is a tree: tap an object or array to open it, 200 children at most per
  node, then "… N more". Each is in the output menu's "Show as", and Copy gives the SVG, the
  LaTeX or indented JSON. Setting: "Draw LaTeX" (`avf.notebook.latex`); SVG and JSON need none
  (plain text stays one tap away in the menu). Checked with batch28.ipynb: the SVG at 240×90,
  ∫x²dx = 1/3, the tree opened at `items`. Found on the tablet: the SVG first filled the width.
* (6) Cells move by a drag handle in the selected cell's gutter (the list scrolls at its edges;
  a cell swaps once it passes its neighbour's middle, so tall and short cells don't flicker).
  Delete leaves "Cell deleted · Undo" for 10 seconds; "Notebook: Undo Delete Cell" brings back
  the last 20, with their outputs, where they were. New commands: Move Cell Up/Down (Alt+Up,
  Alt+Down, as VS Code), Delete Cell, Undo Delete Cell. No setting: the handle shows only on the
  selected cell. Checked: third dragged above first, deleted, Undo put it back with its run
  mark, Alt+Up moved it above the JSON cell.
* (7) Interactive window for `# %%` cells of .py files (also `# In[ ]`, `# <codecell>`;
  `# %% [markdown]` cells never run). The editor draws a rule above each marker and Run Cell,
  Run Above, Run Below after it (setting `jupyter.interactiveWindow.codeLens.enable`).
  Ctrl+Enter runs the cell at the cursor and Shift+Enter runs it and moves to the next, only in
  a file with cells. Palette: Run All Cells, Run Cells Above, Run Current Cell and Below, Run
  Selection/Line in Interactive Window, Create Interactive Window. The panel's new Interactive
  tab shows each run's code (file and line above it) and outputs, with its own kernel (picker,
  interrupt, restart, clear, Variables, export as a notebook next to the file, close), and a box
  to type more code (Shift+Enter). It's a hidden notebook session per file, so outputs,
  Variables and the data viewer work as in notebooks. `Cell In[3], line 2` in a traceback opens
  the .py file at that line. Code above the first marker is in no cell, as in VS Code. Checked
  with batch28.py: Run Cell, Run Below (three runs), the NameError's link opened line 16, the
  input box ran `import math`, Ctrl+Enter drew √42 ≈ 6.481, Shift+Enter moved to line 19,
  Variables listed x and y. Not checked: export (it now picks `name-2.ipynb` when `name.ipynb`
  exists, as here).
* (8) Key bar above the on-screen keyboard (setting "Key bar", `avf.keyBar.enabled`): Esc, Tab,
  Ctrl, Alt, Shift, arrows (repeat while held), Home, End, PgUp, PgDn, sent as a keyboard's keys.
  Ctrl, Alt and Shift latch for the next key (tap twice to lock); with Ctrl or Alt latched, the
  next letter typed on the on-screen keyboard becomes the shortcut: in the terminal a control
  character (Ctrl then C is ^C) or Escape first for Alt, in the code editor and notebook cells
  the key combination (Ctrl then S saves). It shows only while the on-screen keyboard does.
  Checked with `show_ime_with_hard_keyboard` on for the test (restored to 0): Ctrl+L cleared
  the terminal, ↑ recalled the last command, Ctrl+U cleared the line, Ctrl+A selected all in
  the editor and in a cell. Found on the tablet: Samsung's keyboard resends the word being
  typed ("matha" after "math", from `setComposingRegion`), so the editor's connection now
  tracks that word and takes the new letter; ← drew lower than the other arrows (icons now).
* (2) Next edit suggestions (setting "Next edit suggestions",
  `github.copilot.nextEditSuggestions.enabled`, on). 600 ms after an edit (or the suggestion
  delay, if longer) the app asks Copilot's language server (1.560.0) with
  `textDocument/copilotInlineEdit`; an edit for the current document version is shown and
  reported with `textDocument/didShowInlineEdit`, and accepting it runs its command (Copilot's
  acceptance telemetry). The editor tints and strikes through what goes, and a card under the
  edit shows the lines as they'd read, with what's new in green. An edit off screen gets a chip at
  the top or bottom ("↓ Copilot's next edit at line 40"). Tab goes to the edit when the cursor is
  more than a line away, then accepts it; Esc dismisses; a ghost-text suggestion at the cursor
  takes Tab first. Palette: "Copilot: Accept Next Edit Suggestion", "Copilot: Suggest the Next
  Edit". A server without the method says so once and isn't asked again until it restarts.
  Checked on batch28_nes.py: after renaming `self.x` to `self.xpos` in two places Copilot
  proposed line 10, Tab went there, Tab accepted; renaming `self.y` then chained lines 7, 10 and
  14 with Tab, Tab each. The first rename alone got no suggestion. Found on the tablet: an edit
  on the last line crashed the app (the card's position read the line after the file's end);
  fixed. Not checked: the off-screen chip. Known: a problem tooltip at the cursor can cover the
  card until the cursor moves.
* Test files `~/Projects/batch28.{py,ipynb}`, `batch28_nes.py`.

## 29. Completion while typing, find widget, Markdown cells, plot viewer (11-01 12:00–13:50; done, checked)
User's pick: 6, 7, 20 and 23 of the backlog list after batch 28 (spec-review items 1, 2, 15, 18).
* (6) Completion while typing. The language server's completions now fill sora's suggest popup
  at the cursor (the palette dialog is gone; Ctrl+Space opens the popup). A `Language` wrapper
  (`SoraCompletionLanguage`) asks on sora's completion thread after each typed letter or trigger
  character (`.` for Python; TypeScript's own set), posting the request to the main thread so it
  follows the edit's didChange, and keeps one list per word being typed, filtering it as the word
  grows (fuzzy, prefix and word-boundary first, then the server's `sortText`); it asks again only
  when the server marked the list incomplete. Not in a line comment, not after a digit. Items show
  their kind, `labelDetails` and module; the edit runs from the item's insert range (or the word)
  to the cursor; `additionalTextEdits` (auto-imports) are applied in the same undo step, and for
  servers that send them only on `completionItem/resolve` after the insert. Snippet text loses its
  placeholders (snippets are backlog 3). Client capabilities now announce labelDetails,
  insert/replace, resolve and itemDefaults. Files without a server keep the grammar's word
  completion. Setting "Suggestions while typing" (`editor.quickSuggestions`). Checked with ty on
  batch29.py: `counter.mos` offered `most_common` (method, signature), Enter took it;
  `defaultdic` offered `DefaultDict (import typing)`, `collections.defaultdict` and Django names,
  and Enter inserted `DefaultDict` and `from typing import DefaultDict` on line 1. Found on the
  tablet, all fixed: a problem's tooltip at the cursor covered the popup (it now waits while the
  popup shows); sora leaves no item selected, so Enter typed a new line (the first item is now
  selected after each refresh); Samsung's keyboard ends the word it composes on Enter, which
  closed the popup before the key arrived (Enter, Tab, ↑, ↓, Esc now go to the popup before the
  keyboard app); a list from a request a newer key had replaced could still show and then
  accept nothing (checked before publishing, and accepted at the cursor if it shows anyway);
  sora drops the popup when keys come within 70 ms (off: the kept list makes each key cheap);
  Replace All's edit opened the popup (only edits while the editor has focus ask now); the
  first request after the app starts can take seconds while ty indexes, so the wait is 15 s.
* (7) Find widget at the top right of the editor, replacing the Find dialog: match case, whole
  word and regex beside the query, "3 of 12" (20,000 matches at most, then "20000+"), previous
  and next, Find in Selection (its offsets follow edits inside it), close; the chevron opens
  Replace with Preserve Case, Replace and Replace All (one edit, one Undo). Regex `^`/`$` work
  per line and a match may cross lines; `$1`, `${name}`, `\n`, `\t` in the replacement. All
  matches get a background, the current one stronger (theme colours, VS Code's in imported
  themes). Typing searches from where the cursor was. Keys as VS Code: Enter/Shift+Enter, F3/
  Shift+F3, Alt+C/W/R/P/L, Ctrl+Shift+1, Ctrl+Alt+Enter, Ctrl+H, Escape (in the widget or the
  editor). The query starts from the selection or the word at the cursor, selected so typing
  replaces it; setting "Find starts with" (`editor.find.seedSearchStringFromSelection`: word at
  cursor, selection, last search). Checked on batch29.py: DefaultDict seeded "? of 2", Next
  "1 of 2", Replace All with OrderedDict ("Replaced 2 occurrences"), `tot` incremental "1 of 4",
  Enter, Enter "4 of 4", Escape closed it. Found on the tablet, fixed: sora's selection call takes
  focus, so after the first match the next keys went into the code (matches are now selected
  without taking focus or showing the cut/copy/paste bar); the keyboard app turns Enter in a
  one-line box into an action, so Enter didn't move (the box's search action is Next, the
  replace box's is Replace).
* (20) Markdown cells (and `text/markdown` outputs) drawn natively from CommonMark: headings
  (h1/h2 underlined), paragraphs with bold, italic, strikethrough, inline code and links (they
  open like the Markdown preview's: workspace files in the editor, web links in the browser),
  bullet and numbered lists nested, task lists, quotes, code blocks (Python coloured), tables
  (each column as wide as its widest cell, scrolling sideways), rules, and images from the
  notebook's folder, `attachment:` (cell attachments), `data:` or the web, SVG too. A cell with
  math (when "Draw LaTeX" is on) or raw HTML is drawn as a small page sized to its content, with
  the preview's styles; its math is taken out before Markdown and put back for KaTeX (Jupyter's
  way), so `\` and `_` survive and "$5 and $10" stays text. Checked with batch29.ipynb: every
  element above, the local image, $E = mc^2$ and a display sum, the HTML block. Found on the
  tablet, fixed: page cells took the view's whole height (now they measure their content); KaTeX's
  auto-render still turned "$5 and $10" into math (only the formulas found are drawn now).
* (23) Plot viewer: a tap on an image or SVG output (setting "Open plots with a tap",
  `avf.notebook.plotViewerOnTap`), the output menu's "Open in plot viewer" or "Jupyter: Show Plot
  Viewer" opens it full screen: zoom and pan as the image viewer (pinch, double tap, Ctrl and
  the wheel, fit), ← → or the side arrows through the notebook's (or Interactive window's)
  plots, "Plot 2 of 2 · batch29.ipynb · cell [2] · SVG". Save beside the notebook
  (`name-plot-1.png`, the next free number), save to Pictures/MaterialCode, copy the image, share
  (Android's share sheet through a FileProvider; an SVG goes as a PNG at twice its size).
  Checked: tap opened plot two, fit (536%), back to plot one (PNG 120×60), saved
  batch29-plot-1.svg and a PNG in Pictures/MaterialCode, the share sheet ("1 imagem", closed
  without sharing), "Plot copied". Found on the tablet, fixed: the bar sat under the window's
  caption.
* Test files `~/Projects/batch29.{py,ipynb}`, `batch29-plot-1.svg`, and
  `Pictures/MaterialCode/batch29-plot-*.png` on the tablet.

## 30. Edit commands, LSP extras, indentation, Explorer, notebook find and outline, data viewer, device extras (11-01 14:10–20:20; done, checked)
User's pick: 2, 3, 4, 11, 12, 16, 17, 21 of the backlog list after batch 29 (spec-review items 4, 5, 6,
11, 12, 17, 19, 21). 275 unit tests pass. The guest daemon was rebuilt (it carries the Jupyter
bridge) and installed on the tablet (`~/.local/bin/avf-native-daemon.previous` kept; "Installed
jupyter bridge-5").
* (4) Indentation detection (`editor.detectIndentation`, setting "Detect indentation", on): each
  file's indentation is guessed when it opens, as VS Code does (tabs or spaces by which more lines
  start with; the tab size from the most common step between neighbouring lines, docstrings left
  out). The status bar shows "Spaces: 4" / "Tab Size: 4" and opens a picker: spaces or tabs and the
  size (this file only), Detect from content, Convert to tabs/spaces. Tab, Shift+Tab, Enter,
  Format Document/Selection, format on paste and Copilot use it. Palette: Indent Using
  Spaces/Tabs, Detect Indentation from Content, Convert Indentation to Spaces/Tabs. Checked: a
  2-space file showed "Spaces: 2"; Convert to tabs gave "Tab Size: 2".
* (2) Edit commands, in the palette with VS Code's ids: Join Lines, Transpose Characters, Sort
  Lines Ascending/Descending (the selected lines, or the file), Delete Duplicate Lines, Trim
  Trailing Whitespace (Ctrl+K Ctrl+X), Transform to Uppercase/Lowercase/Title/Snake/Kebab Case (the
  selection or the word at the cursor), Expand/Shrink Selection (Shift+Alt+→/←: the server's
  `selectionRange`, ty has it, together with words, strings, brackets, lines and indented blocks),
  Format Selection (Ctrl+K Ctrl+F, range formatting of the selected lines). One edit each (one
  Undo). Checked: Transform to Uppercase on `zeta`; Expand twice (statement, then the file) and
  Shrink back. The rest by unit tests only.
* (3) Language-server extras. Reference counts (code lens, setting "Reference counts",
  `editor.codeLens`): the server's own lenses when it has them (vtsls, asked for with
  `typescript.referencesCodeLens.enabled`), else `textDocument/references` for each class,
  function and method in the outline, 16 at a time, shown at the end of the line ("1 reference"),
  a tap lists them in References. Found on the tablet: ty sends flat symbols whose range starts at
  `def`, and references asked there are empty; the name's column is now looked up. Linked editing
  (setting "Linked editing", on): typing or deleting in an HTML/XML/JSX tag's name changes its
  partner (nesting counted, self-closing and half-typed tags left alone, an emptied name stays
  linked); done in the editor, no server needed. `$/progress` from servers shows in the status bar
  with a spinner ("ty: Indexing 40%"); not seen on the tablet (ty finished too fast). Checked: lens
  counts in batch28.py and b30/indent.py, a tap opened References; `<div>` → `<divxy>` renamed the
  outer `</div>` and not the inner one, and back.
* (11) Undo after a delete: the daemon already kept trashed entries 10 s; the app reads the trash
  id from the delete's Ack and shows "Deleted x · Undo" for 9.5 s, restoring the entries and
  reopening their tabs. "Confirm before deleting" (`explorer.confirmDelete`, on as in VS Code).
  Checked: delete one.txt, Undo, "Restored one.txt". (A first try ran past the window while I took
  screenshots; two test files stayed deleted.)
* (12) Explorer: multi-select (Ctrl+tap, Shift+tap a range, or Select in a row's menu, then taps
  toggle; a bar with Move to…, Delete and Clear), drag to move (long-press and drag onto a folder
  or ".."; the list scrolls near its edges; a long press that doesn't move opens the menu; setting
  "Drag to move in Explorer"), Move to… (a folder in the workspace, created if new), Undo for
  moves, Open Editors above the files (setting "Open editors in Explorer"), compact folders
  (`explorer.compactFolders`: a folder holding one folder opens through and shows as `a/b/c`),
  `files.exclude` (VS Code's defaults; `**`, `*`, `?`, `{a,b}`, `[…]`; Explorer and Quick Open;
  Settings → Hidden files opens settings.json). Checked: deep/nested/inner as one row, drag of
  one.txt onto target and Undo, menu on a still long press, Select + tap, Delete 2 items with the
  dialog. Found on the tablet: Open Editors inside the file list was pushed above the rows the
  list keeps in view; it is now its own section. Not checked: Ctrl/Shift+tap (no keyboard),
  files.exclude edits.
* (16) Notebook find and outline. Ctrl+F (and Ctrl+H, F3, Alt+L…) in a notebook open the same
  find widget over every cell's source: matches highlighted in the cells, "3 of 8", Enter moves
  through cells (a Markdown cell opens its source to show it) while the keys stay in the find box;
  Find in Selection means the selected cell; Replace and Replace All work cell by cell; closing
  leaves the match selected in its cell. Outline for notebooks: Markdown headings by level, and
  code cells under them with the "Code cells" chip; a tap selects the cell. Checked: "price"
  8 matches, Enter 8 → 1 → 2, outline taps. Not checked: Replace in notebooks.
* (17) Data viewer: a filter (text in any column, or a pandas query such as `price > 1150 and
  city == "Oslo"`; a query that fails is matched as text, with the error shown), 500 rows a page
  with first/previous/next/last, and sorting by a column over all rows (the kernel sorts). The
  bridge's `_avf_table` takes offset, query, sort and order; an older bridge still shows the
  first rows, with a note. Checked on a 1,200-row DataFrame: rows 1,001–1,200 on the last page,
  the query gave 16 rows "filtered from 1.200", price descending. Typing with adb garbled the
  first word (it types faster than a person; one letter at a time was fine); a fixed dialog size
  came out of chasing it.
* (21) Share target, tile, haptics. MaterialCode is in the share sheet: text opens as a new
  untitled file; files are saved into a workspace folder you choose (a taken name gets " (2)"),
  or one file opens without saving. Quick Settings tile "MaterialCode": a tap opens the app; lit
  while connected. Haptics (`avf.haptics`, setting "Haptic feedback"): breakpoints, latched key-bar
  modifiers, Explorer selection and drag, Undo offered. Checked: shared text → Untitled-1; a plot
  shared from the plot viewer → MaterialCode → saved in b30 and opened; the tile (added with
  `cmd statusbar add-tile`, removed afterwards) lit, and a tap from the home screen brought the
  app back. Haptics not felt (adb). An adb share of a MediaStore file fails with a clear message
  (the shell can't grant the read).
* Found on the way (batch 29): a shared or copied SVG plot drew at its own size in a bitmap twice
  as large (a quarter filled); AndroidSVG now scales it to the bitmap. The Undo snackbar's button
  uses the inverse primary colour (it was hard to read).
* Test files in the guest: ~/Projects/b30 (indent.py, page.html, notebook.ipynb, one.txt,
  deep/nested/inner/leaf.txt, target/, batch29-plot-20261101-201541.png). Untitled-1 holds the
  shared text, unsaved.

## 31. Code folding, Settings by category with icons, Seti file icons (11-01 20:30–21:10; done, checked)
User's pick (11-01): backlog 7 (folding, "go for it already"), Settings reorganized by sections with
icons, and a default file icon theme in Explorer. 279 unit tests pass.
* (7) Code folding on sora 0.23.6, which has none (still none up to 0.24.7, its newest): sora
  draws, hit-tests and moves the cursor through its `Layout`, so `FoldingLayout` (the line-break
  layout with folded lines left out of its rows, in sora's package to reach it) gives real
  folding without a fork. A folded line answers with its fold's first row; Up/Down skip folds; a
  cursor that lands in a fold (arrows, find, go to definition) opens it, as VS Code does; an edit
  inside a fold or on its first line's line break opens it; folds below an edit move with it.
  While anything is folded, sora's hardware line cache and indent guides (they assume a row per
  line) rest. Regions: the language server's `textDocument/foldingRange` (one per first line,
  the outermost), else indentation (VS Code's fallback; trailing blank lines and closing braces
  stay out). Arrows beside the line numbers (down open, right folded; always shown, there's no
  hover), "⋯" after a folded line (tap opens), commands Fold (Ctrl+Shift+[), Unfold
  (Ctrl+Shift+]), Toggle Fold (Ctrl+K Ctrl+L), Fold All (Ctrl+K Ctrl+0), Unfold All (Ctrl+K
  Ctrl+J). Setting "Folding" (`editor.folding`). Word wrap turns folding off (its layout is
  sora's own). Checked on b30/indent.py: fold `def outer` (lines 2-6 hidden, numbers skip), "⋯"
  opened it, Fold All folded nested regions, Down went 1 → 7 → 8 → 9.
* Settings by category: Appearance, Editor, Code intelligence, Saving and formatting, Files and
  Explorer, Languages, Notebooks, Terminal, Copilot and Chat, Guest, Keyboard and device, each
  with its icon. On the tablet the list sits left of the page (title, summary, sections); in a
  narrow window the list comes first and Back returns to it. Every row has an icon; the same
  settings as before, regrouped (Find starts with and Linked editing under Editor, hints and
  problems under Code intelligence, save clean-ups under Saving…).
* File icons: VS Code's default theme, Seti (extensions/theme-seti, MIT; notices in
  assets/icon-themes), in Explorer, Open Editors and the tabs: by file name, extension (longest
  first), then language id, with the light theme's colours in light mode. Its font is WOFF,
  which Android can't load, so it is converted to TTF (res/font/seti.ttf, 57 KB). Setting "File
  icons" (`workbench.iconTheme`: Seti or plain). Folders keep the tree's arrows, as in VS Code.

## 32. Pinch fixes, tooltips and hover, go to type/implementation, hex view, notebook intelligence, icon themes, terminal, leftovers (11-02 10:00–11:05; done, checked)
User's pick (11-02): backlog 1, 3, 5, 6, 8, 9, 17 of the 11-01 table (spec-review items 3, 7, 9,
10, 14, 25, 24), and "pinch to zoom is not working". 290 unit tests pass (daemon 90, protocol 22).
* Pinch to zoom. A touchscreen pinch did zoom the editor (checked with a virtual HID touchscreen:
  14 → 39); what failed:
  * The Book Cover's touchpad: Android 14+ sends a touchpad pinch as a mouse gesture
    (`CLASSIFICATION_PINCH`, two fake fingers), and Compose doesn't hand it to the views inside
    it, so sora never saw it. The activity now routes touchpad pinches to the editor or
    notebook under the pointer. The pinch's scale axis reads 0 on this tablet (its touchpads
    don't declare it), so the scale comes from the span between the two pointers.
  * Notebooks and the Interactive window had no pinch at all; two fingers (screen or touchpad)
    now zoom them, live, saved as `editor.fontSize` when the fingers lift.
  * Ctrl + mouse wheel zooms the editor and notebooks (`editor.mouseWheelZoom`, on by default;
    Settings → Editor).
  * Checked with a virtual HID touchpad (same Android mapper as the Book Cover's): editor 16 →
    25 and back; notebook 16 → 20; touchscreen pinch in the notebook 20 → 16.
* (25 → backlog 9) Tooltips: Material plain tooltips on 80 icon buttons, the find widget, the
  activity bar ("Explorer (Ctrl+Shift+E)") and the status bar ("Select Indentation"), with the
  command's key where the button runs one. They show when a mouse, touchpad or stylus pointer
  rests 0.5 s, and on a long press (which doesn't also press the button). The touchpad's pointer
  arrives as a hovering finger, which Material's own tooltips ignore, so the hover is detected
  for any pointer. Setting "Tooltips" (`avf.workbench.tooltips`, Keyboard and device). Checked:
  long press on Run all, and "Toggle side bar (Ctrl+B)" from a resting touchpad pointer.
* Editor hover: a mouse or touchpad pointer resting on code shows a card above the word with the
  problems there (icon, message, source(code)) and the language server's hover as Markdown;
  Ctrl+K Ctrl+I shows the same card at the cursor (it was a dialog). It stays while the pointer
  is on the word or the card, and closes on another word, leaving the editor (0.5 s), a click,
  typing, scrolling or Escape. Settings "Hover" (`editor.hover.enabled`) and "Hover delay"
  (`editor.hover.delay`, 300 ms) under Code intelligence. Checked: "class Greeter()" from a
  resting touchpad pointer, "def outer(values) -> Unknown" from Ctrl+K Ctrl+I.
  `adb shell setprop log.tag.AVFPTR DEBUG` logs where a hovering pointer is.
* (3 → backlog 1) Go to Type Definition, Go to Implementations (Ctrl+F12), Go to Declaration and
  their Peek commands; Peek Definition (Alt+F12) and Go to References (Shift+F12) open a peek
  under the line (above it near the bottom): the file around the location with its lines
  marked, and a list by file when there are several (tap shows, double tap or Open goes there;
  Escape closes). Several definitions open the peek, as VS Code does. Find All References moved
  to Shift+Alt+F12 and the References view lists each reference's line. A reference count (code
  lens) opens the peek. Call hierarchy (Shift+Alt+H, Show Incoming/Outgoing Calls) in the side
  bar: incoming or outgoing, expanded level by level; a tap goes to the call. Checked with ty:
  peek of `greet`, References (2) of `Greeter` with the list, Peek Type Definition of `ZETA`
  (builtins.pyi `class int`), incoming calls of `greet`. ty has no Go to Implementations (the
  status bar says so).
* (7 → backlog 3) Hex view: a binary file (NUL bytes) opens read-only in hex instead of failing:
  offset, 16 bytes, text; 64 KB pages read as they scroll in; Go to offset (decimal, 0x… or …h);
  tapping a byte shows it decoded (uint8 to float64, binary). Checked on /bin/ls (199,576 bytes):
  the ELF header, and 0x20010 in the third page.
* (10 → backlog 6) Icon themes from VSIX packs: Settings → Appearance → Import icon theme, then
  pick it under File icons (an imported theme is used right away, as VS Code offers). Font
  themes (TTF, OTF; WOFF converted to TTF on the device) and picture themes (SVG through
  AndroidSVG, PNG), folder icons by name and open/closed in the Explorer tree, light variants.
  Checked with Material Icon Theme 5.40 from Open VSX (1,283 files): Python, Jupyter, Markdown,
  image, SVG, SQL and folder icons in Explorer, Open Editors and the tabs. Switched back to
  Seti afterwards; Material Icon Theme stays installed.
* (14 → backlog 8) Terminal:
  * Profiles: bash, zsh, fish, sh, tmux (`new-session -A -s main`) and the user's own
    (`terminal.integrated.profiles.linux`), from the arrow beside + or Terminal: Create New
    Terminal (With Profile); the default one in Settings → Terminal
    (`terminal.integrated.defaultProfile.linux`). The daemon now takes a shell's arguments.
    A missing program says so: "tmux isn't installed in the guest: sudo apt install tmux" (the
    guest has none of zsh, fish, tmux today).
  * Split (Ctrl+Shift+5): two terminals side by side, either active; Unsplit in the arrow's
    menu. Rename… (the name selected, so typing replaces it); Kill terminal.
  * Shell integration (`terminal.integrated.shellIntegration.enabled`): the daemon starts bash
    (`--init-file`, after ~/.bashrc) and zsh (`ZDOTDIR`) with OSC 133 prompt/command/exit marks
    and OSC 633;E command lines. The tab shows the last exit status (a dot in the primary or
    error colour); Commands lists the recent ones with ✓ or the exit code, a tap scrolls the
    terminal to it, with Copy output and Run again; Ctrl+Up / Ctrl+Down step through them;
    Terminal: Copy Last Command Output. termlib keeps its scroll controller internal; a small
    bridge file reaches it.
  * Copy on selection (`terminal.integrated.copyOnSelection`, off as in VS Code).
  * Checked: `false` (exit 1) and `ls` listed, the ls output copied, a tap on `ls` scrolled its
    prompt to the top past `seq 1 100`, the split, rename, the tmux message. Copy on selection
    wasn't checked on the tablet.
  * The guest daemon was updated (b32-update, from a transient unit).
* (9 → backlog 5) Language intelligence in notebook cells (`avf.notebook.languageServer`,
  Settings → Notebooks): the Python server (ty) gets the notebook as one hidden file of its
  cells (`notebook.ipynb.avf-cells.py`; magics and `!` lines as comments; IPython's names and
  top-level await not reported). Problems under each code cell (wavy lines in the text, the first
  three listed below with their line; a tap goes there), hover from a resting pointer or Ctrl+K
  Ctrl+I, and F12 to the definition in another cell or a file. Checked: "Cannot resolve imported
  module `pandas`" on cell 1, F12 on `sales` in cell 2 put the caret on its assignment in cell 1,
  the hover card in the cell. ty resolves imports with the interpreter chosen for the workspace
  (django here, without pandas), not the notebook's kernel: see the backlog.
* (24 → backlog 17) Batch 28 leftovers: sora's problem tooltip no longer covers the next-edit
  card, the hover card or the peek (it waits, and closes when one opens). The Interactive window's
  export wrote a 0-byte notebook: it wrote without the daemon's flush; fixed (and the plot
  viewer's "save beside the notebook", same bug). Checked: cells-2.ipynb with both cells and
  their outputs. The next-edit off-screen chip is still unchecked (Copilot didn't propose one).
* Test files left in ~/Projects/b30: ls.bin, cells.py, cells.ipynb (the empty one from before the
  fix), cells-2.ipynb; in /sdcard/Download: avf-daemon-b32, b32.sh, b32-update.log,
  b32-cells.py, material-icon-theme-5.40.0.vsix.

## 33. Notebook problems with the kernel's Python, terminals kept across restarts, installing a missing shell (11-02 11:15–11:35; done, checked)
User's pick (11-02): backlog 26, 27, 28 (found in batch 32), "to round them up". 290 unit tests pass.
* (26) A notebook's cells are checked with its kernel's Python: when that differs from the
  workspace's interpreter, a second Python server (ty) starts with the kernel's environment for
  the notebooks that need it (one at a time; it restarts for another Python, and stops when no
  notebook uses it). The kernelspec's Python is remembered on the device when a kernel starts,
  so after reopening, imports resolve before the kernel runs. The idle stop no longer closes the
  Python server while only notebooks use it. Hovers ask for Markdown (ty sent plain text, which
  drew a line over `---` as a heading); plain-text hovers show preformatted. Checked:
  "Cannot resolve imported module `pandas`" (django) went away once the Python 3 kernel ran, and
  stayed away after the app restarted with the kernel stopped; hover on `DataFrame` shows pandas'
  docs with the signature as Python.
* (27) Terminals the guest keeps running get their name and profile back after the app
  restarts (kept on the device by session id; the guest knows only their folder), and their
  exit-status mark from the replayed output. Checked: renamed one "build", force-stopped the
  app, reopened: "build" with its mark.
* (28) A built-in shell that isn't installed (zsh, fish, tmux) offers to install it from Debian
  in a new terminal, then opens it. Checked the offer for zsh and dismissed it (nothing was
  installed in the guest).

## 34. Pointer fixes, terminal find, snippets, language servers on demand, Source Control extras (11-02 15:20–16:25, finished 21:25–22:10; done, checked)
User's pick (11-02): backlog 1-10 of the 11-02 table (items 31, 32, 33, 29, 30, the 14/24
leftovers, 16 and 5 of the reviews, 8 and 13 of the 11-01 review). 299 app unit tests, 95
daemon and the protocol tests pass. The guest runs the new daemon (`.previous` is batch 32's).
* (31) Hover card blinking: the card's pointer tracking covered the whole editor (its `layout`
  reported the pane's size), and Compose re-sends the last hover event when the layout changes;
  the editor then saw the pointer "leave" and "rest" again. The card now tracks only its own
  bounds, and the editor ignores re-sent hover moves (same event time). Checked with a
  simulated touchpad: the card stays until the pointer leaves.
* (32) Pointer shape: sora already asks for the I-beam over text for mouse and touchpad
  pointers (logged `PointerIcon type=TEXT` for source 0x2002); the editor now also answers for
  other pointer sources (stylus), and the terminal shows the I-beam. Not seen on screen
  (screencap doesn't draw the touchpad's pointer): **to confirm with the user's pointer**.
* (33) Double click, hold and drag selects by whole words; a triple click selects the line and
  dragging extends by lines (VS Code). Checked with a simulated touchpad.
* Tooltips no longer open on keyboard focus (with a keyboard attached, the first toolbar
  button kept focus and its tooltip stuck after launch); hover and our own long press (whose
  release doesn't click) show them. Checked: no stuck tooltip after launch.
* (29) Terminal find: a tap on a match, or Enter / Shift+Enter in the field, scrolls the
  terminal to it (middle of the view) and marks it; a long press opens a link or copies the
  line. Checked with "250" in `seq 1 400`.
* (30) Find in selection shades the range it searches. Checked.
* (14/24 leftovers) Copy on select checked (long press in a terminal: "Copied the selection").
  The next-edit off-screen chip is **still unchecked**.
* (16) Raw cells: the cell toolbar's kind is a menu (Code, Markdown, Raw), and the palette has
  Notebook: Change Cell to Code/Markdown/Raw. Checked both ways.
* (5) Snippets: VS Code snippet syntax (tab stops, defaults, nested stops, choices, mirrors,
  variables such as TM_FILENAME, CLIPBOARD, CURRENT_YEAR, regex transforms); Tab/Shift+Tab move
  between fields, the active one is outlined, Escape or leaving the snippet ends it. Sources:
  imported packs' `contributes.snippets`, the workspace's `.vscode/*.code-snippets` (reloaded
  on save) and language-server snippet completions. Palette: Snippets: Insert Snippet and
  Configure Workspace Snippets. Setting: Code intelligence → Snippets in suggestions
  (`editor.snippetSuggestions`). Checked: a workspace snippet offered as "prr", its two `name`
  fields typed together, Tab to the end. The outlines lagged a character behind typing; a
  redraw after layout was added (**not rechecked**). Server snippet completions not tried.
* (8) Language servers for other languages (`avf.languageServers.others`, Settings → Guest):
  Go (gopls), C/C++ (clangd), Rust (rust-analyzer) from Debian; Bash, YAML, JSON, HTML, CSS,
  Dockerfile and SQL from npm on MaterialCode's own Node.js (avf-node-agent, pinned versions);
  TOML (Taplo 0.10.0) and Markdown (Marksman 2026-02-08) as pinned, checksum-verified downloads
  (the daemon learned single-file and gzipped downloads). Each starts with its files and is
  offered for install once a session when missing; workspace symbols ask every running server.
  Checked: Cargo.toml offered Taplo, which installed (**it stays in the guest**), and its
  folding and an error underline showed. **Not tried on the tablet**: the apt and npm servers,
  Marksman.
* (13) Source Control: Stage / Unstage / Discard per hunk in the diff (daemon `ApplyPatch`);
  Stash, Stash (include untracked), Pop latest, Stashes… (Apply, Pop, Drop with a confirming
  second tap); a History section (a tap shows the commit); File history of the active file;
  Git blame on the cursor's line (`git.blame.editorDecoration.enabled`, off by default).
  Fixed on the way: `git stash -u` left the untracked files (the daemon's literal pathspecs
  broke stash's own cleanup), and a commit's `---` and file headers were coloured as removals.
  All checked on a scratch repository (since deleted).
* Left on the device: Taplo in ~/.local/bin; copy on select back off; the AVFPTR log property
  cleared; staged files removed. The pointer debug logs stay, gated by `log.tag.AVFPTR`.

Finishing the batch (11-02 evening; the user confirmed the I-beam and the hover card on their
touchpad; backlog 34-37). 299 app unit tests and 95 daemon tests pass; the guest runs this
daemon (it carries the new avf-node-agent).
* (User) The hover card closed as soon as the pointer left the word, so it couldn't be reached
  to scroll it. It now stays for VS Code's hiding delay (`editor.hover.hidingDelay`, 300 ms,
  Settings → Code intelligence → Hover hiding delay) after the pointer leaves its word, the
  editor or the card itself, and a new word's card cancels the pending close. **To confirm with
  the user's touchpad** (scrolling a long card).
* (35) Snippet field outlines lagged a character: the overlay's draw lambda captured nothing
  that changed, so Compose kept it and never redrew it after an edit; the revision (and the
  scroll tick) are now read while drawing. Same fix for the find-in-selection shading, which
  stayed put on scroll. Snippet fields are selected as search does, so sora doesn't open its
  copy/paste bar on them. Checked: "prr" inserted, both `name` fields outlined around "total"
  as it was typed, Tab to the end.
* (35) Language-server snippets: the client told servers it had no snippet support, so they
  sent none. It says it has now; the CSS server's `color: $0;` inserted with the cursor before
  the `;`, then value suggestions. Checked.
* Suggestions while typing didn't open on a fresh line, or at all in a file without a grammar
  (CSS before its grammar was downloaded): sora refuses when no highlight span covers the
  cursor. Only a span marked "no completion" (a comment, a string) stops it now. Checked in CSS
  and Python. A gated debug log for the suggest requests: `log.tag.AVFSUG`.
* (36) Language servers, all installed through the daemon and opened on a file each:
  * Go, C/C++ and Rust start but need their toolchains (`go list` fails, `stdio.h` isn't found,
    "Failed to load workspaces" without cargo). When one runs without its toolchain, the app
    offers it once a session (Go toolchain `golang-go`, about 530 MB; C/C++ compiler and
    headers `build-essential`, about 290 MB; Rust `cargo`, about 970 MB), then restarts the
    server. Checked: all three offers appear. **Not checked**: the servers with their
    toolchains installed (installing them in the guest was blocked in this session).
  * clangd answered (references, an inlay hint, problems); Bash and Marksman show reference
    counts; YAML, HTML, Dockerfile start.
  * JSON showed no problems: VS Code's server checks nothing until `json.validate.enable`; the
    app now sends VS Code's defaults for JSON and YAML. Checked: "Trailing comma".
  * sql-language-server 1.7.1 exited at start: npm resolves its `vscode-languageserver-protocol`
    range to 3.18, which no longer exports a file it imports. avf-node-agent pins 3.16.0 with an
    npm override for that range only (its vscode-languageserver keeps its own 3.17 copy) and
    reinstalls from scratch once. Checked: sqlint's problems show (it prints timings on stdout,
    which the daemon's line-based header reader skips).
  * Reopening the app started every restored tab's server at once (twelve here) and they all
    timed out on initialize. Restored tabs now open in the background, and a file's servers
    start when it is shown (VS Code loads restored editors then too). Checked: only the active
    file's server started; data.json's started on switching to it.
* (37) The next-edit chip: after renaming `self.y`, Copilot proposed line 7; scrolled away, the
  chip read "↑ Copilot's next edit at line 7 · Tab go to it · Esc dismiss", Tab went there,
  Tab accepted. Checked.
* Found on the way: after a fling, the overlays over the text (fold arrows, inlay hints, ghost
  text) stayed where it began. sora sends one ScrollEvent when a fling starts and moves the
  text in `computeScroll` without more; the editor now reports offset changes from there.
  Checked: fold arrows aligned after a fling and a slow drag.
* Left on the device: nothing from this round. The test folder, its snippet file
  (~/Projects/.vscode, created for the test), the npm servers, Marksman and the apt packages
  (gopls, clangd, rust-analyzer and what they pulled in) were removed; the AVFSUG and AVFCOP
  log properties cleared; tabs back to the user's four. Taplo stays from earlier.

## 35. Restored tab order, SQL style rules as warnings, debugging: conditional breakpoints, logpoints, watch, launch.json, Set Value, inline values, attach, Node.js (11-02 22:25–23:05; done, checked)
User's pick (11-02): backlog 41, 42 and the two debugging items (10-29 #25, 11-01 #20); 38 was
confirmed by the user (the hover card can be reached and scrolled). 306 app unit tests and 95
daemon tests pass; the guest runs this daemon.
* (41) Restored tabs keep the saved order: each takes its place as its file loads (they load in
  any order), and the active one is still the one shown. Checked across restarts.
* (42) sqlint's layout rules (a line break after SELECT, upper-case keywords, aligned columns)
  report as warnings: the app sends `sqlLanguageServer.lint.rules` with all eight rules at the
  chosen level. Setting: Guest → SQL style rules (`avf.sql.styleRules`: Warnings, Errors, Off);
  the user's own `sqlLanguageServer.lint` wins. Checked: a lower-case query shows warnings.
* Debugging (10-29 #25):
  * Conditional breakpoints, hit counts and logpoints: a long press on the gutter (or Debug: Add
    Conditional Breakpoint… / Add Logpoint… / Edit Breakpoint) opens a dialog with the
    expression, hit count and log message, plus Remove. Marks as VS Code draws them: a dot with
    "=" for a condition or hit count, a diamond for a logpoint. Kept with the breakpoints.
    Checked: `n == 4` stopped once with n = 4; a logpoint wrote "n is 1…3" without stopping.
    Hit counts not tried on the tablet.
  * Watch: a Watch section above Variables (+, remove per row, errors in red), evaluated in the
    selected frame on each pause; kept across sessions; Debug: Add to Watch takes the selection
    or the name at the cursor (the command wasn't tried on the tablet). Checked: `total * 2` = 12,
    a NameError in red in another program.
  * launch.json: the Debug panel picks a configuration (launch.json's, then the built-in Python:
    Current File, Node.js: Current File and Python: Attach (port 5678)); kept per workspace.
    Add Configuration… writes `.vscode/launch.json` from a template; it is read at connect and on
    save (comments and trailing commas allowed). VS Code's variables (`${file}`,
    `${workspaceFolder}`, `${fileDirname}`, `${relativeFile}`, `${command:python.interpreterPath}`…)
    are resolved; output always goes to the Debug console (no terminal to hand programs to).
    Checked: the template's configurations listed, "Current File with Arguments" ran.
* Debugging (11-01 #20):
  * Set Value: a long press or double click on a variable (setVariable). Checked: `total` set to
    100, the watch and the program followed (the loop went on from 104).
  * Inline values (`debug.inlineValues`, Settings → Debugging): the paused function's locals at
    the end of its lines, up to the paused one. Checked in Python and JavaScript.
  * Attach: a program started with `python -m debugpy --listen 5678 --wait-for-client` runs its
    own adapter as a DAP server, so attaching connects to it directly (VS Code's way); avf-debugpy
    gained `--connect host port`, a stdio bridge. Stop disconnects and leaves an attached program
    running. Checked: attached, paused in `tick`, disconnected, the ticks went on. Attaching to a
    process id (debugpy needs gdb) isn't offered.
  * Node.js: VS Code's js-debug 1.140.0 (its DAP server, a GitHub release, checksum-verified,
    about 3 MB) on MaterialCode's Node.js, set up by avf-node-agent and offered for install on the
    first Node.js debug. js-debug is a TCP server that gives each program a session of its own
    (`startDebugging`): the app picks a port, the first session runs `js-debug serve PORT`, the
    program's runs `js-debug connect PORT`. Checked: install offer, breakpoint in `greet`, step
    over, the return value, console.log output in the Debug console.
* Found on the tablet and fixed:
  * Android's regex engine (ICU) rejected an unescaped `}` the JVM accepted: the Debug panel
    crashed the app on opening.
  * A first gutter mark (a breakpoint, a problem dot) widens the gutter and moves the text, but
    the overlays (inlay hints, ghost text) stayed: they now follow when the marks change.
  * Files without a grammar had no styles, so breakpoints and problem dots weren't drawn there.
  * "Paused on Paused on breakpoint" (js-debug's description already says Paused); saved watch
    expressions only listed after the first pause; "Debug session ended" twice after Stop
    (built, not rechecked); inline values were dark on a dark chip (now the tertiary colour).
* Left on the device: nothing from this batch. The test folder, the launch.json it made (and its
  `.vscode` folder), the attach test program, js-debug and the SQL server were removed, with
  their tool registry entries; batch 34's cleanup had left the removed servers' registry entries
  (the app then saw them as installed but failing), removed too. avf-debugpy and avf-node-agent
  are the new versions. Tabs back to the user's four; no terminal open.

## Backlog
To investigate (user, 10-28):
* (Done in batch 16, with a notebook-side finding.) Plotly figures updating in place (the user's private-notebook-1.ipynb: `plot_handle =
  display(fig, display_id=True)`, then `plot_handle.update(fig)` per epoch). First look: the
  Jupyter bridge maps `update_display_data` to a new `display_data` output and drops
  `transient.display_id` (`guest/tools/avf-jupyter-bridge.py`, `to_nbformat_output`), so each
  update most likely appends a new output instead of replacing the one with that id: an app
  bug, not the notebook's. Check on the tablet, then keep display ids on outputs and replace by
  id (also `clear_output(wait=True)`).
* (Done in batch 21, except widgets.) Cell outputs drawn with Material You elements and interaction: native tables for DataFrames
  (sortable, scrollable, theme colours) instead of an HTML WebView, collapsible/expandable
  outputs, copy and clear actions, maybe widgets later. Decide what stays a WebView (Plotly).
* (Done in batch 19.) A tree view in Explorer (expandable folders in place, VS Code style) instead of one level at a
  time; check what exists today.
* (Done in batch 19.) Problems in the editor itself (user, 10-28): squiggly underlines in the problem's severity
  colour on the exact range, a hover/tap tooltip with the message, source and code (and its
  quick fixes), and gutter/scrollbar markers; plus "appropriate additional linting": e.g. ruff
  rule selection, ty/basedpyright strictness and TypeScript checks exposed as settings, unused
  code shown faded, deprecated code struck through (LSP diagnostic tags). First look: the editor
  shows no diagnostics today (they only reach Problems and the Outline); sora-editor 0.23.6 has
  built-in support (`DiagnosticsContainer`, `DiagnosticRegion`, `DiagnosticDetail` with
  quick fixes, `setDiagnosticIndicatorStyle`, a diagnostic tooltip window), so the underline
  part is likely S-M.
* (Done in batch 18.) Outline from problems: can diagnostics feed the Outline (problems grouped per symbol, markers
  next to outline entries) or a problems-by-file tree.
* (Done in batch 22.) Open different workspaces, folders or projects: today the workspace is the daemon's root
  (`AVF_WORKSPACE_ROOTS`, ~/Projects). Needs an Open Folder flow, recent workspaces, and the
  daemon's roots/permissions model (several roots or switching the root).

* (Done in batch 21.) Sticky notebook cell headers and toolbar (user, 10-28): in a tall cell, its header (execution
  count, language) and toolbar (run, and the cell actions) stay pinned at the top of the
  notebook view while the cell scrolls, so the run button doesn't scroll away; like VS Code's
  sticky cell toolbar. First thoughts: NotebookEditor is a LazyColumn of cells, so this needs an
  overlay for the topmost partly visible cell (from `firstVisibleItemIndex` and its scroll
  offset); evaluate a setting.
* (Done in batch 21.) Notebook cell statistics (user, 10-28): each code cell shows how long its last run took and
  how it ended: done, in progress (queued or running, with a live timer) or failed; like VS
  Code's status mark and duration under the cell. First look: cells already track `running`
  and `queued` (NotebookModel) and the execute reply carries `status` ("ok"/"error"), so this
  needs a start time and duration per run, a failed state, and a small status row; maybe keep
  the timing in the cell's metadata (JupyterLab's `execution` timestamps) so it survives a
  reopen. Evaluate a setting (show run statistics). Likely S-M.
* (Done in batch 28.) Next edit suggestions (user, 10-29): Copilot proposes the next change near or away from the
  cursor, based on the edits just made, as VS Code does
  (`github.copilot.nextEditSuggestions.enabled`). The suggestion is shown as a diff in place
  (removed text struck through, new text beside it) or as a gutter arrow when it is elsewhere
  in the file. Tab jumps to it, then accepts it; Escape dismisses it. Not looked at yet: whether
  copilot-language-server offers it over LSP (open-source editor plugins use a
  `textDocument/copilotInlineEdit` request) and what the account allows. It needs a diff
  decoration in sora, and a setting (on by default when Copilot is signed in). Likely M-L.
* (Done in batch 27.) Syntax colours on demand for more languages (user, 10-31): opening a SQL file (or Rust, Go,
  TOML, …) should colour it. Today only Python's grammar ships with the app; other files are
  coloured only if a .vsix with their grammar was imported by hand (Settings → Import VS Code
  theme takes .vsix files and loads the TextMate grammars in them). First look: Open VSX
  publishes VS Code's built-in language extensions under the `vscode` namespace (e.g.
  `vscode/sql` 1.95.3, MIT grammars, ~50 KB each), and the app already installs a .vsix into
  asset-packs and registers its grammars by file extension. So: a map from file extension to
  extension id (built-ins first; an Open VSX search for the rest), an offer in the editor when a
  file has no grammar ("Colour SQL files? Downloads vscode.sql from Open VSX"), download with
  checksum, install, and re-colour open tabs. A setting to ask, always fetch, or never; an
  "Installed languages" list in Settings to remove them. Not the Microsoft Marketplace. Likely M.
* Refactor: replace sora-editor with Aardink (user, 10-28). **Decided (user, 10-28): it will be
  done, for the licence (Apache-2.0 instead of sora's LGPL-2.1); held for later.** Evaluated the
  same day (`docs/native-editor-evaluation.md`, "Re-evaluation 2026-10-28"); not ready yet,
  revisit at Aardink 0.9.
  * Measured on the tablet against sora: about 130 MB more PSS per editor (324-334 vs
    195-201 MB), 22-25 missed frame deadlines per ~1,000 scroll frames (sora: 1 per ~4,000),
    first frame about 80 ms faster.
  * Missing for parity: TextMate (grammar packs, imported themes, Material You syntax themes),
    semantic tokens and ghost text (planned for 0.9), and host decorations (breakpoints, the
    paused line, bracket colours).
  * Effort if done: XL, about 2-3 weeks of batches:
    * (Done in batch 20.) Route the controller's 29 direct `editorView` uses through
      `EditorSurface`. An Aardink switch now replaces `SoraEditorSurface` and the editor pane.
    * A Compose editor pane with keys, auto-pairing, tab stops, comment and line commands, and
      IME (L).
    * A TextMate tokenizer adapter on tm4e (L), or a hand-written grammar per language that drops
      VS Code grammar packs.
    * Themes mapped to Aardink's `EditorTheme` (M).
    * Semantic and bracket colours merged into its tokens (M-L).
    * Problems onto its built-in diagnostics (S-M).
    * Ghost text, breakpoints and the paused line, which need its 0.9 APIs (M, blocked).
    * The large-file view (S), and tests and tablet checks (M).
  * Gains: Apache-2.0 (settles the open LGPL question), Compose-native, and built-in
    find/replace, folding, minimap, sticky scroll and snippets. Notebook cells could share the
    same editor.

Candidates from the spec review (10-29; `docs/NATIVE-ANDROID-IDE-SPEC.md` against the app).
Not picked yet. Effort: S about half a day, M about a day, L several days.
* Editor and language intelligence (spec 4.2):
  1. (Done in batch 23.) Signature help: parameter hints while typing a call (`textDocument/signatureHelp`). S-M.
  2. (Done in batch 23.) Document highlights: other uses of the symbol under the cursor. S.
  3. (Done in batch 24, at the line end.) Inlay hints: inferred types and parameter names, with a setting. M.
  4. (Done in batch 25.) Quick Open prefixes as in VS Code (`@` symbols in the file, `#` workspace symbols, `:`
     line, `>` commands). M.
  5. (Done in batch 34.) Snippets: VS Code snippet files from asset packs, and LSP snippet completions with tab
     stops. M-L.
  6. (Done in batch 23.) `files.trimTrailingWhitespace`, `files.insertFinalNewline`, `editor.formatOnPaste`. S.
  7. (Done in batch 31, with a folding layout for sora.) Code folding (LSP folding ranges or indentation). M. Checked in batch 25: sora 0.23.6
     can't hide lines, so this waits for the Aardink switch.
  8. (Done in batch 24.) Markdown preview for `.md` files (toggle or side by side). M.
  9. (Done in batch 25.) Image viewer for image files (zoom, pan; SVG too). S-M.
  10. (Done in batch 24; not checked by touch.) Pinch to zoom the editor font, persisted. S.
* Workbench:
  11. (Done in batch 27.) Replace across files in Search, with a preview and per-match accept. M.
  12. (Done in batch 27.) Explorer decorations: Git status colours and problem badges on files and folders. S-M.
  13. (Done in batch 25.) Tabs: preview tabs (a single tap opens a replaceable tab) and pinning. S-M.
  14. (Done in batch 27.) Git: a merge-conflict helper (accept current/incoming/both on conflict markers) and a
      side-by-side diff. M-L.
  15. (Done in batch 25.) Ports view: the daemon's detected listening ports, opened in the browser. M.
  16. (Done in batch 27; find lists lines, the library can't scroll to them.) Terminal: links (file:line opens in the editor, URLs in the browser) and find in
      scrollback. M.
* Notebooks (spec 5.9):
  17. (Done in batch 27.) ANSI colours in outputs (they are stripped today) and clickable traceback lines. S-M.
  18. (Done in batch 28.) More output types: `image/svg+xml`, `application/json` as a tree, `text/latex` (KaTeX). M.
  19. (Done in batch 28.) Cell reordering by drag, and undo for a deleted cell. M.
  20. (Done in batch 26.) Variable explorer (`%whos`-style, opening DataFrames in the native table). L.
  21. (Done in batch 28.) Interactive window: run `# %%` cells of a `.py` file in a kernel. L.
* Device and quality (spec 10, 11):
  22. (Done in batch 27.) Launcher shortcuts for recent folders, and "Open with" for code files from other apps. S-M.
  23. (Done in batch 28.) Touch modifier bar (Ctrl, Alt, Tab, Esc, arrows) above the soft keyboard. M.
  24. Measure the spec's budgets on the tablet (cold start, RSS, keystroke latency, a
      200-cell notebook). S-M.
  25. (Done in batch 35.) Debugging extras: conditional and log breakpoints, watch expressions, `launch.json`. M.
* Left out on purpose: multi-cursor (sora is single-cursor; comes with the Aardink switch),
  split editor groups and the 3-way merge editor (L, little use on a tablet), tasks.json
  (L, the terminal covers it), MCP and other AI providers (Copilot only, by the user's choice).

Spec review (11-01, after batch 28; `docs/NATIVE-ANDROID-IDE-SPEC.md` against the code). Gaps
not listed above, found by reading the code, not by use. Effort: S about half a day, M about a
day, L several days.
* Editor:
  1. (Done in batch 29.) Completion as you type: today language-server completions come only from Ctrl+Space, in a
     palette dialog. Wanted: sora's completion window at the cursor fed by the server, with
     kinds, documentation, `additionalTextEdits` (auto-imports, not applied today), commit
     characters, `completionItem/resolve`. Setting: `editor.quickSuggestions`. M-L.
  2. (Done in batch 29; in-selection without a shaded scope.) Find widget: an inline bar instead of the Find dialog, with regex, match case, whole word,
     in selection, preserve case, previous match, match count and all matches highlighted. M.
  3. (Done in batch 32.) Go to Type Definition, Implementation and Declaration; a real Peek (today it jumps); call
     hierarchy. M.
  4. (Done in batch 30.) More edit commands: join lines, transpose, sort lines, upper/lower case, expand/shrink
     selection (LSP `selectionRange`), format selection. S-M.
  5. (Done in batch 30.) LSP extras: code lens (reference counts), linked editing (HTML tags), `$/progress` in the
     status bar (indexing). M.
  6. (Done in batch 30.) Indentation detection from the file's content (`editor.detectIndentation`). S.
  7. (Done in batch 32.) Hex view for binary files. M.
* Languages:
  8. (Done in batch 34; Taplo checked, the others not yet.) Language servers beyond Python and TypeScript/JavaScript, offered on demand like syntax
     colours: rust-analyzer is in the guest catalog but not wired in the app; Go (gopls), C/C++
     (clangd), Bash, YAML, JSON, TOML (taplo), Markdown (marksman), Dockerfile and SQL aren't in
     the catalog. A generic LSP client per language, catalog entries, an install offer. L.
  9. (Done in batch 32.) Language intelligence in notebook cells (diagnostics, hover, go to definition) through ty
     over the concatenated cells, as the Copilot mirror already does. M-L.
  10. (Done: Seti in batch 31, imported VSIX icon themes in batch 32.) File icon themes from imported VSIX packs (kept but not used today). S-M.
* Workbench:
  11. (Done in batch 30.) Undo after deleting a file (the daemon's trash can restore it; the spec asks for a 10 s
      Undo). S.
  12. (Done in batch 30.) Explorer: multi-select, drag to move, Open Editors section, `files.exclude`, compact
      folders. M.
  13. (Done in batch 34.) Source Control: stage and discard per hunk from the diff, stash, history (log) view,
      blame toggle, file timeline. M-L.
  14. (Done in batch 32; copy on select checked in batch 34.) Terminal: split, rename, profiles (zsh, tmux), shell-integration marks (jump between
      commands, exit status), copy on select. M.
* Notebooks:
  15. (Done in batch 29.) Markdown cells rendered fully: tables, links, images (attachments), code blocks and
      LaTeX math (`$…$`, KaTeX as in outputs). Today headings, bullets and inline styles only;
      commonmark is already in the app. M.
  16. (Done in batch 34.) Raw cells: the model reads them, but the cell toolbar switches only Code and Markdown. S.
  17. (Done in batch 30.) Find and replace across a notebook's cells, and a notebook outline (headings). M.
  18. (Done in batch 29.) Plot viewer: an image output full screen with pinch zoom, save and share. S-M.
  19. (Done in batch 30.) Data viewer: filter rows and page past the first rows. M.
* Debugging:
  20. (Done in batch 35: Set Value, inline values, attach to debugpy --listen, Node.js; codelldb and attaching by process id left.) Set a variable's value, inline values in the editor, attach to a process, more adapters
      (codelldb, js-debug). M-L.
* Device and quality:
  21. (Done in batch 30.) Share-sheet target (text or files into the workspace), a Quick Settings tile, haptics for
      latched modifiers and breakpoints. S-M.
  22. Accessibility pass (TalkBack labels for the editor, tree and outputs). M.
  23. Instrumented tests and golden screenshots (spec 12; the app has unit tests only). L.
  24. (Done in batch 32; the off-screen chip checked in batch 34.) Batch 28 leftovers: the next-edit card can sit under a problem tooltip; the off-screen
      chip and the Interactive window's export weren't checked on the tablet. S.
* Pointer (registered 11-01, user: "I don't know if they should be appearing, but at the moment they are not"):
  25. (Done in batch 32.) Tooltips on hover with a mouse or touchpad, as VS Code shows them. Today none show: the
      app has no TooltipBox; about 66 icon buttons have a label (contentDescription) only for
      TalkBack. Wanted: Material 3 plain tooltips on hover (and long press) for icon buttons in
      the activity bar, toolbars, the find widget, the notebook and cell toolbars, Explorer and
      the status bar, with the keybinding where one exists ("Find Next (F3)"). And the editor's
      hover: the language server's hover card (type, docs) and problems after resting the
      pointer on a symbol (`editor.hover.enabled`, `editor.hover.delay`), as Ctrl+K Ctrl+I shows
      it now. Check how the Book Cover touchpad's hover events arrive (a `hid` test mouse moved no
      pointer on 10-29). Setting for the editor hover. S-M.
* Found in batch 32 (11-02):
  26. (Done in batch 33.) Notebook language intelligence uses the workspace's interpreter (ty's environment), not the
      notebook's kernel: imports the kernel has (pandas) show as unresolved. Point ty at the
      kernel's Python for the hidden cells file, or a per-notebook environment. S-M.
  27. (Done in batch 33.) Terminals reattached after an app restart are titled "Projects" (their folder) and lose
      the profile and the exit-status mark; keep them with the daemon's session. S.
  28. (Done in batch 33.) Zsh, fish and tmux aren't in the guest; offer to install a profile's program from the
      "isn't installed" message (as other guest tools are offered). S.
  29. (Done in batch 34.) Terminal find lists the matching lines but can't scroll to them (batch 27); the scroll
      bridge from batch 32 can now take a tap on a match to its row. S.
  30. (Done in batch 34.) Find in selection (batch 29) works without the shaded area VS Code draws over the
      selection it searches. S.
* Pointer in the code editor (user, 11-02, to investigate; register only):
  31. (Done in batch 34.) The hover card "blinks and disappears" when the pointer rests on code. First look: the card
      is laid out over the whole editor (its `layout` reports the pane's size to place itself),
      so once it shows, the editor view likely gets a hover exit as if the pointer had left;
      with the card's own enter/exit tracking that closes it again. Give the card only its own
      bounds (offset placement), then recheck with a mouse and the touchpad. S.
  32. (Done in batch 34; to confirm with the user's pointer.) The pointer is the arrow over code instead of the text I-beam (VS Code shows the I-beam,
      a hand over links and the gutter's arrow). sora asks for `TYPE_TEXT` over text
      (`onResolvePointerIcon`), so Compose's host view probably decides the icon for the views
      inside it; check, and set it from the editor's region if so. Notebook cells (Compose text
      fields) and the terminal to check too. S-M.
  33. (Done in batch 34.) Double click, hold and drag (select by words) does nothing. sora's mouse handling selects
      the word on a double click and then ignores moves until release
      (`EditorTouchEventHandler.onMouseEvent`: `if (mouseDoubleClick) return true`). Extend the
      selection by whole words while dragging after a double click (and by lines after a triple
      click, as VS Code), in the editor view; check the touchpad's tap-and-drag too. S-M.
* Left from batch 34 (11-02; 34-37 done in batch 34's second round):
  34. (Done: the user confirmed both.) Confirm with the user's mouse/touchpad: the I-beam over code and the terminal, and that
      the hover card no longer blinks (only a simulated touchpad was used). S.
  35. (Done in batch 34.) Check the snippet field outlines after the redraw fix, and a language server's snippet
      completion (a call with parameter stops). S.
  36. (Done in batch 34, except Go, C/C++ and Rust with their toolchains installed.) Try the language servers not yet run on the tablet: gopls, clangd, rust-analyzer (apt,
      needs sudo), the npm ones (Bash, YAML, JSON, HTML, CSS, Dockerfile, SQL) and Marksman. S-M.
  37. (Done in batch 34.) The next-edit off-screen chip, still unchecked (from batch 28). S.
* Left from batch 34's second round (11-02):
  38. (Done: the user confirmed it.) Confirm with the user's touchpad that the hover card can be reached and scrolled (the
      hiding delay). S.
  39. Go, C/C++ and Rust with their toolchains: accept the offer (or install `golang-go`,
      `build-essential`, `cargo`) and check gopls, clangd and rust-analyzer on a module, a file
      with `#include <stdio.h>` and a cargo project. S.
  40. Snippets wait for the language server's answer: while a fresh server is still indexing
      (ty with a large environment takes seconds), typing a prefix shows nothing. VS Code shows
      snippets at once and merges the server's items when they come. S-M.
  41. (Done in batch 35.) Restored tabs come back in the order their files finished loading, with the active one
      last; keep the saved order. S.
  42. (Done in batch 35.) sqlint (inside sql-language-server) reports its style rules ("A linebreak is required
      after SELECT") as errors; consider a default `.sqlintrc` or lowering them to warnings. S.
* Left from batch 35 (11-02):
  43. Remove a guest tool: the daemon has no uninstall, so deleting a tool's files leaves its
      registry entry (the app then sees it installed but failing). `avf-native-daemon tools
      remove <name>` and a Remove button in Settings → Guest tools. S-M.
  44. C, C++ and Rust debugging with codelldb (needs the toolchains of backlog 39 to try). M.
  45. Breakpoints stay on their line numbers when lines are added or removed above them; VS Code
      moves them with the text. S-M.
  46. The Debug console can join a logpoint's line with a half-printed stdout line (seen once:
      "n is 7" followed by " 4 total = 104"); keep output categories apart. S.
  47. Not tried on the tablet: hit counts, Debug: Add to Watch, editing an existing breakpoint
      and Remove, the inline values setting, the single "Debug session ended". S.
* Still left out on purpose: multi-cursor, sticky scroll, minimap (Aardink switch; folding came in batch 31);
  split editor groups and the 3-way merge editor; tasks.json; MCP, other AI providers and the
  built-in chat agent (Copilot, Claude Code and Gemini through ACP instead); foldable postures
  and S Pen hover (not on this tablet); remote kernels and localization (no need yet).

To investigate (user, 10-28, second set; planned for 10-29):
* (Done in batch 19: untitled files reach the servers.) Inline suggestions, completion and hover not working on some files; the Output shows
  "Language intelligence requires a connected Python or TypeScript language server". First look:
  `activeLspPosition` (WorkbenchController) refuses when the file's language client is not ready
  *or* the file is a `scratch:` (untitled) document, so untitled files never get a server, and
  a .py/.ts file gets the message when its server did not start. Check which case the user hit,
  whether the server started (and why not), and give untitled files a language once chosen.
  Confirmed by the user (10-28): the file was unsaved. It should not depend on saving: VS Code
  gives untitled files completion and lint (in-memory text, `untitled:` URI, or a virtual path
  under the workspace for servers that only accept `file://`).
* (Done in batch 17.) Which interpreter a .py file or the workspace uses: a status-bar chip (VS Code style) that
  shows the Python environment used for completion/lint and opens the interpreter picker; decide
  per workspace vs per file, and restart the language server on change.
* (Done in batch 16.) Guest tools / setup option: move /tmp itself onto the disk (e.g. mask `tmp.mount` or bind a
  disk directory over /tmp) so nothing stays in memory, as an explicit opt-in beside the
  daemon's automatic TMPDIR fix; needs sudo and a guest restart, so confirm and explain.
* (Done in batch 15: syntax colours from the Material You palette, Settings → Syntax colours.)

Bugs (user, 10-28): both fixed and checked in batch 15.
* (Done: Backspace after Enter now removes one indentation level, `editor.useTabStops`.)
* (Done: choosing a Ctrl+Space completion leaves the cursor after the inserted text.)

* (Done in batch 16: re-measured the app's memory, TypeScript 7 and the compact layout.)
* Copilot sometimes exits right after a daemon restart ("Guest tool exited with code unknown");
  seen once in two tries, cause unknown. Batch 19: not reproduced in five restarts; the app now
  retries three times and the daemon logs exits with stderr. Look in
  `journalctl --user -u avf-native` if it happens again.
* (Done in batch 22, except live widgets.) Notebook gap: script-based outputs (widgets, bokeh, altair) still show their text form.
* Live ipywidgets (registered 10-29): sliders, dropdowns and interact() need the kernel's comm
  messages (comm_open/comm_msg/comm_close) both ways. Work: the bridge forwards them, and one
  widget manager per notebook (@jupyter-widgets/html-manager in a WebView) is shared by its
  widget outputs. Saved widget state (`metadata.widgets`) could draw static widgets first. L.
* (Done in batch 25.) Customisable keybindings (`keybindings.json`).
* (Done in batch 17: TypeScript 7 as the default TypeScript server; compact layout reaches
  Source Control, Search, Debug, Output and extra terminals.)
* (Done in batch 18: the pickers drop environments that no longer exist.)
* Done and dropped from this list (10-28): Copilot chat sign-in and replies (batch 11),
  stopping the Copilot server while signed out (built; tablet check carried over), HTML outputs
  and kernel completions (batches 11-12).
