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

## 11. Copilot, auto-save, notebooks, cleanups (planned 10-27; in progress)
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
* Copilot memory: when the language server reports "not signed in", the app remembers it and
  stops the server after 30 s (~280 MB in the guest); later launches don't start it, the status
  bar says "Copilot · Signed out", and tapping it starts the server and signs in.

## Mapped for later (user, 10-27)
* Model picker and adjustments (e.g. reasoning effort) for the chat UI.
* Claude Code and Gemini support alongside Copilot (chat agents; ACP where they speak it).
* Auto-save modes as in VS Code: after a delay (exists), and on focus change (`files.autoSave`
  `onFocusChange`/`onWindowChange`).
* Offline experience: the editor alone when no guest is reachable (open/edit local and cached
  files, no tools).

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
