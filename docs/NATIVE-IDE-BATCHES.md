# Native IDE (Track B): work batches

This is the plan of record for Track B. Each batch ends in one commit on
`agents/reset-and-implement-native-android-ide`, and this file is updated in that commit.
Device procedures, build commands and the details of finished work are in
[NATIVE-IDE-HANDOFF.md](NATIVE-IDE-HANDOFF.md).

Status values: **done** (committed), **in progress**, **planned**.

| # | Batch | Status | Commit |
|---|-------|--------|--------|
| 1 | Workbench rewrite: Material You, terminal, Jupyter, Copilot | done | `1f6c146` |
| 2 | Keyboard routing, file operations, notebook keys, comments | done | this file's first commit |
| 3 | Notebook arrow navigation; Copilot chat references | in progress | |
| 4 | Setup from the app, pairing code, reset detection, resume session | planned | |
| 5 | Search across files | planned | |
| 6 | Git | planned | |
| 7 | Debugging (DAP with debugpy) | planned | |
| 8 | Daemon hosted on Windows and Linux machines, with a connection guide | planned | |

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

## 3. Notebook arrows and chat references (in progress)
* **Bug:** arrow keys inside a cell jump to other cells instead of moving the cursor. Up/Down
  must move line by line (visual lines when wrapped) and only leave the cell from its first or
  last line; Left/Right must never move focus out of a cell.
* **Chat references:** attach the active file, the selection (or current notebook cell), or any
  workspace file to a Copilot chat message. Files are ACP `resource_link` blocks; excerpts are
  embedded `resource` blocks when the agent supports `embeddedContext`, else fenced snippets.
  Chips above the input; the Explorer menu has "Add to Copilot chat"; Ctrl+Alt+L adds the selection.
* Accept: unit tests for the prompt blocks; on the tablet, a wrapped multi-line cell navigates
  line by line; attached chips appear and are sent.

## 4. Setup and host reset (planned)
* The APK ships the arm64 daemon and the installer; "Set up guest" writes them to shared
  Downloads and shows one command to run in Linux Terminal (copy + open Terminal buttons).
* Pairing: the installer prints a short one-time code (expires, limited attempts); the app sends
  it in a `Pair` handshake and receives the token, so nobody types 64 hex characters.
* Reset detection: refused connections or `InvalidToken` offer "Set up again".
* Resume: reconnect on launch with the saved profile; reopen the tabs and Explorer folder.
* Accept: a reset guest is back in use with the command plus the code; app restart reconnects and
  restores tabs.

## 5. Search (planned)
The daemon already implements `SearchMessage` (`search.rs`). Build the app codec, a Search side
view (query, regex/case/word, include/exclude globs), streamed results grouped by file, opening
at the match, and Ctrl+Shift+F.

## 6. Git (planned)
The daemon already implements `GitMessage`/`GitOp` (`git.rs`). Build a Source Control view: status
with stage/unstage/discard, commit message and commit, branch picker and checkout, pull/push/fetch
with credential prompts (askpass), a diff view, and gutter change markers. Ctrl+Shift+G.

## 7. Debugging (planned)
DAP over the daemon's `Dap` bridge with debugpy (in the tool catalog): launch the active Python
file, breakpoints in the gutter (F9), continue/step/stop (F5, F10, F11, Shift+F11, Shift+F5), call
stack, variables, debug console. Restore the F9 binding when it works.

## 8. Daemon on Windows and Linux hosts (planned)
* Portability: the Git askpass helper (Unix sockets), home and token paths, default shell, tool
  catalog artifacts per OS, file permissions.
* Remote access: loopback stays the default. Opting in to a LAN address requires TLS with a
  self-signed certificate; the pairing string carries host, port, certificate fingerprint and the
  one-time code, and the app pins the fingerprint.
* Installers: a Linux systemd user unit and a Windows logon task; release builds for
  x86_64/aarch64 Linux and x86_64 Windows.
* A guide: install, pair, connect over LAN or VPN, firewall notes.

## Backlog
* Finish Copilot chat sign-in on the tablet (needs the user's approval on GitHub).
* Stop the Copilot language server (~280 MB) while signed out.
* Re-measure the app's memory.
* Notebook gaps: HTML outputs, kernel completion UI.
* Customisable keybindings (`keybindings.json`), TypeScript 7 measurement, compact layout check.
* Remove the `AVFKEY` routing log once the Book Cover keyboard is confirmed.
