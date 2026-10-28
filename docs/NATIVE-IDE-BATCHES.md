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

## Next pass (registered 10-27 23:35, updated 10-28 13:06)
* Batch 15 did the carried-over tests (Copilot signed out, the setup paths, reconnect cap,
  Copilot model list, prompt prefix). Left over:
  * Miniforge's Install row from the UI on a guest without conda (its command is verified).
  * Copilot models beyond "Auto" depend on the user's GitHub Copilot settings; nothing to do in
    the app unless the account offers more and the picker still shows one.

## Mapped for later (user, 10-27)
* Chat adjustments beyond the model/mode picker (batch 11). (Reasoning effort done in batch 16.)
* Claude Code and Gemini support alongside Copilot (chat agents; ACP where they speak it).
* (Done in batch 11: auto-save modes after a delay, on focus change and on window change.)
* Offline experience: the editor alone when no guest is reachable (open/edit local and cached
  files, no tools).

## Backlog
To investigate (user, 10-28):
* (Done in batch 16, with a notebook-side finding.) Plotly figures updating in place (the user's private-notebook-1.ipynb: `plot_handle =
  display(fig, display_id=True)`, then `plot_handle.update(fig)` per epoch). First look: the
  Jupyter bridge maps `update_display_data` to a new `display_data` output and drops
  `transient.display_id` (`guest/tools/avf-jupyter-bridge.py`, `to_nbformat_output`), so each
  update most likely appends a new output instead of replacing the one with that id: an app
  bug, not the notebook's. Check on the tablet, then keep display ids on outputs and replace by
  id (also `clear_output(wait=True)`).
* Cell outputs drawn with Material You elements and interaction: native tables for DataFrames
  (sortable, scrollable, theme colours) instead of an HTML WebView, collapsible/expandable
  outputs, copy and clear actions, maybe widgets later. Decide what stays a WebView (Plotly).
* A tree view in Explorer (expandable folders in place, VS Code style) instead of one level at a
  time; check what exists today.
* Outline from problems: can diagnostics feed the Outline (problems grouped per symbol, markers
  next to outline entries) or a problems-by-file tree.
* Open different workspaces, folders or projects: today the workspace is the daemon's root
  (`AVF_WORKSPACE_ROOTS`, ~/Projects). Needs an Open Folder flow, recent workspaces, and the
  daemon's roots/permissions model (several roots or switching the root).

To investigate (user, 10-28, second set; planned for 10-29):
* Inline suggestions, completion and hover not working on some files; the Output shows
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
  seen once in two tries, cause unknown. The app retries once after 3 s.
* Notebook gap: script-based outputs (widgets, bokeh, altair) still show their text form.
* Customisable keybindings (`keybindings.json`).
* (Done in batch 17: TypeScript 7 as the default TypeScript server; compact layout reaches
  Source Control, Search, Debug, Output and extra terminals.)
* The interpreter picker keeps environments that no longer exist (batch 17).
* Done and dropped from this list (10-28): Copilot chat sign-in and replies (batch 11),
  stopping the Copilot server while signed out (built; tablet check carried over), HTML outputs
  and kernel completions (batches 11-12).
