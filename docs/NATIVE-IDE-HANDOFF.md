# Native IDE (Track B) — handoff

Last updated: 2026-11-15 (UTC). Read this first when resuming. The batch plan and its status are in [NATIVE-IDE-BATCHES.md](NATIVE-IDE-BATCHES.md). Scope decisions are in
[NATIVE-ANDROID-IDE-SPEC.md](NATIVE-ANDROID-IDE-SPEC.md) under "Scope reset (2026-10-23)". How work is done here (Material You,
the engine seam, batches, testing on the device) is in [CONTRIBUTING.md](../CONTRIBUTING.md). For agents: how to work with the maintainer, the device's hard rules and the lessons so far are in [AGENT-GUIDE.md](AGENT-GUIDE.md).

## Current state (2026-11-15)

* **Progress:** batches 1–70 are done (41 was exploration with no app change). Batch 70 implements G33 + G34 and was
  checked on the tablet. Each batch is described in the batch plan.
* **What's open:** [Open backlog](NATIVE-IDE-BATCHES.md#open-backlog-11-08), in two lists: the remaining general
  backlog and the Aardink switch's, which replaces this file's old "Remaining" list.
* **Next batch:** on hold at the user's request. G39 records the workspace-first, explicit-reconnect behavior for
  discussion; do not start another batch until the user chooses to proceed.
* **Release 0.1.0 on hold:** all release backlog items (R1–R10: Apache-2.0, notices, signing, CI workflows, QuireCode renaming, diagnostics) and branch consolidation (R11) are finished. Tagging and release are paused awaiting user decision on repository visibility.
* **Offline & remote features (batches 62–70):** file sharing/import/export (group 9, batch 62), Tailscale daemon hardening (batch 63), device folder workspaces without a guest (batch 64), paired device management UI (batch 65), guest folders kept offline with sync, offline Explorer, Quick Open, and offline search (batch 66), offline/device-only previews, images, hex view, snippets, and kernel-less notebook editing (batch 67, completing G29), and Troubleshoot port-vs-daemon probing + Tailscale split-tunneling guidance, Cloudflare Tunnel (`--tunnel`, public TLS), and router port forwarding (batch 68, completing G30 and G31); batch 69 fixed issues from review. Batch 70 implements and verifies G33/G34: connection kinds and ordering, Recent origins and connection-scoped workspace/cache state. G39 captures the user's follow-up request for workspace-first, explicit-reconnect behavior.
* **The switch to Aardink:** [AARDINK-SWITCH-PLAN.md](AARDINK-SWITCH-PLAN.md). All phases are done (batches 42 to 57), on Aardink 0.9.0 (batch 45): **Aardink is the editor**; sora-editor was removed in batch 57, and tm4e is the app's own `tm4e` module. The workbench's own Material You cards are drawn over Aardink from its geometry (batch 54). Review each Aardink release against the plan (its "Release reviews"). Requests to Aardink are drafted or listed, not filed.
* **Waiting on the user:** whether to continue the glibc test (backlog item 70 d). Claude Code's permission system blocked it, so it needs the user's approval.
* **Tablet:** the batch 70 dev build is installed and running. Wireless ADB is paired and connected; Linux Terminal's VM
  is running for the guest connection check. The sections "Reference tablet" and "Done" below mostly date from 10-23, so
  some of what they describe may have moved on. The batch plan is the record since then.

## Where things are

* **Branch:** `main` of `github.com/Kinteshi/QuireCode` (private). Since 11-08 it's the only branch: the earlier trials were deleted after a backup (`~/backups/AVFStudio-before-rewrite-2026-11-08.bundle`), and the history's authors rewritten to `Kinteshi <36821534+Kinteshi@users.noreply.github.com>`, the identity commits use from now. Pushing is the user's call. The untracked top-level `android/` folder holds build leftovers from Track A, so ignore it.
* **App:** `apps/native-android-ide`, app id and package `io.github.kinteshi.quirecode` (QuireCode; `com.avfstudio.nativeide` before 11-08).
  Debug builds are a separate app, `io.github.kinteshi.quirecode.dev` ("QuireCode Dev"), so testing on the tablet never
  replaces the release-signed app (signed builds come only from CI, whose key's password never enters a session). The
  dev app pairs with the guest like any client (`quirecode-daemon pair`); it stays installed beside the release app (user, 11-09); remove test files when a batch's checks end. It
  looks different on purpose (batch 60): an orange palette by default, a DEV badge by its name, its own launcher icon.
* **Guest daemon:** `crates/avf-daemon` with protocol `crates/avf-protocol`. The guest tool catalog is `guest/tools/catalog.json`, and the Jupyter bridge is `guest/tools/avf-jupyter-bridge.py`; the daemon embeds both.
* **Guest install script:** `scripts/install-native-daemon.sh`.

## Build and test

```sh
# App (Android SDK at /root/Android/sdk; local.properties is git-ignored)
./apps/native-android-ide/gradlew -p apps/native-android-ide :app:testDebugUnitTest :app:assembleDebug
# Daemon tests, then the ARM64 build for the guest (cargo-zigbuild lives in ~/.cargo/bin)
cargo test -p avf-daemon -p avf-protocol
PATH=$HOME/.cargo/bin:$PATH cargo zigbuild --release -p avf-daemon --target aarch64-unknown-linux-gnu.2.28
```

At last run (batch 70): 411 app unit tests (on Java 21, installed beside 17) and 146 daemon/protocol tests, all passing; debug assemble passes. Clippy passes with the same five pre-existing lints; the tablet check is recorded in batch 70. CI runs app and Rust tests on every push.

## Reference tablet (Galaxy Tab S11 Ultra, SM-X930)

* **adb:** wireless at `192.0.2.66:<port>`. The port changes after reboots, so ask the user for the new one.
* **Guest services:**
  * Track A Supervisor on 19860.
  * Track B daemon `quirecode.service` on **19870**, binary `~/.local/bin/quirecode-daemon`, token in `~/.config/quirecode/env`, workspace `~/Projects` (before batch 58: `avf-native.service`, `avf-native-daemon`, `~/.config/avf-native`; the installer moves them over, keeping the pairing).
  * A system code-server on 8080, which is not ours.
* **Running guest commands:** push a script to `/sdcard/Download`, bring Linux Terminal to the front, and type `bash /mnt/shared/Download/x.sh`. Write results to `/mnt/shared/Download/*.log` and read them back with adb.
* **Never send Back to Linux Terminal.** It shuts the VM down and the app then kills it forcibly. After a VM stop, "Preparando o terminal" is just the boot (about a minute).
* **Don't start many processes in any app while the guest matters.** Android allows 32 phantom processes across the device, and the VM takes 6. Going over and then changing the foreground app trims processes of any app, including Terminal's `virtmgr` (backlog item 69).
* **Check that the VM is really up:** `adb shell ps -A | grep crosvm_debian`. A `virtmgr` alone doesn't mean it is. Opening Terminal boots it again.
* **Guest reset:** at 13:46 on 2026-10-23 the Debian image was reset from Terminal → Settings → Recovery (not by automation), which wiped the whole guest, Track A and the system code-server included. To re-provision Track B, push the arm64 daemon and run `scripts/install-native-daemon.sh` (it now bootstraps a pinned, checksum-verified uv 0.13.0 and no longer needs openssl). Then enter the new token in the app and accept port 19870 in the notification shade.
* **Port forwarding after a reboot:** forwards can be missing even though the ports are allowed. Toggle the port off and on in Terminal → Settings → Controle de portas. A new port raises a notification; tap *Aceitar*.
* **Hosting on a computer (batch 8):** see [HOSTING-THE-DAEMON.md](HOSTING-THE-DAEMON.md).
* **Pairing (batch 4):** the app's Connect → "Set up or pair…" shows the setup command; on an installed guest, `quirecode-daemon pair` prints a one-time code to enter there. Typing the token by hand is now only a fallback.
* **The token:** never print it. To fill the app's Connect dialog, have a guest script copy the value to `/sdcard/Download`, type it on the device in 4-character chunks with `sleep 0.3` between them (8-character chunks still dropped characters), then delete the copy. The app saves the profile encrypted, so this is a one-time step per guest.
* **Updating the daemon:** reinstall the binary, then `systemctl --user stop quirecode; sleep 3; start` (never `restart`).
* **The tablet still panics:** a sixth host kernel panic happened at 13:06 on 2026-10-23 with the VM running (dropbox `SYSTEM_LAST_KMSG_10_20261023_130718_KP`). The log is truncated before the panic line.

## Done

### Guest (daemon)
* **Tool catalog:**
  * Python language servers: ty (default), ruff and basedpyright (opt-in).
  * Copilot: `copilot-language-server` and `copilot` (the CLI, used for ACP chat). Both are pinned per-architecture npm tarballs with SHA-256 checks, so no Node is needed.
  * Jupyter: `jupyter`, the bundled bridge, installed by the new `script` install method.
* **Idle language servers:** a detached server stops after 60 s instead of 30 min.
* **New `Kernel` RPC kind** for the Jupyter bridge: newline-delimited JSON-RPC, like ACP.
* **Terminals:** PTY shells get `TERM=xterm-256color` and `COLORTERM=truecolor`.
* **Jupyter bridge:** runs under `uv run --script` with `jupyter_client`. It finds kernels in conda prefixes and envs, venvs and system Python, and lists environments that lack ipykernel. It uses IPC sockets and runs one kernel per notebook.

### App
* **Architecture:** all logic moved from the 3,500-line composable into `WorkbenchController`. The UI is split across:
  * `NativeWorkbench` (the shell)
  * `EditorPane`
  * `SidePanes`
  * `PanelArea` (terminal, problems, output)
  * `CopilotChatPane`
  * `NotebookEditor`
  * `SettingsScreen`
  * `WorkbenchDialogs`
  * `WorkbenchTheme`
* **Material You:** dynamic colour, light/dark/system, an optional pure-black mode, a navigation rail, rounded panes, and a bottom navigation bar on phones.
* **Syntax colouring:** VS Code's MagicPython grammar plus Dark+/Light+ themes (MIT; licence in `assets/textmate/VSCODE-LICENSE.txt`). They follow the app theme, and an imported VSIX theme overrides them.
* **Settings page:** writes VS Code-style keys to `settings.json` (`editor.*`, `files.autoSave`, `terminal.integrated.fontSize`, `avf.*`). Dotted keys are accepted, and a raw JSON editor is still available.
* **Shortcuts:** VS Code defaults, including Ctrl+`, Ctrl+Shift+`, Ctrl+B, Ctrl+J, Ctrl+Shift+E/M/O/U, Ctrl+PageUp/Down, Ctrl+K Ctrl+S, Ctrl+F5 (run file), and Ctrl+= / Ctrl+-. Some still work while the terminal has focus (`EditorCommands.global`).
* **Terminal:** ConnectBot `termlib` 0.3.14 (Apache-2.0) over the daemon's PTY. Supports multiple sessions, reattach after a reconnect, and theme colours.
* **Notebooks:** open `.ipynb`; edit code and Markdown cells; run with Shift/Ctrl/Alt+Enter; run all, interrupt, restart, clear, save. Outputs show stream text, errors and PNG/JPEG images. A kernel picker includes an "Install ipykernel" option that runs pip in a terminal.
* **Copilot:**
  * Inline suggestions appear as ghost text at the cursor (Tab accepts, Esc dismisses). Copilot stays off until sign-in succeeds. The status-bar item turns suggestions off when the status is Normal; in any other state (signed out, error) it starts sign-in instead. Toggling it while signed out was what silently switched Copilot off before.
  * Chat sits in the right-hand pane, over ACP. **Sign-in** runs `copilot login --device-code && exit` in a terminal; the app reads the one-time code from the output and shows it in a chat card with *Copy code and open GitHub* and *Cancel*. The guest runs a display server, so a plain `copilot login` picks the browser flow with a loopback callback on guest port 37733; the device flow needs no forwarding. With no keyring in the guest, the CLI stores its token under `~/.copilot/`, where the daemon-launched `copilot --acp` (same user) reads it.
* **Terminal links** (plain URLs and OSC 8) open in the Android browser; only http(s).
* **Quick Open:** recursive workspace index.
* **Syntax colouring after edits** (was "turned white"): the bracket/semantic colorizer ran on a background thread and could read sora's placeholder spans (`[0:TEXT_NORMAL]`, returned when the analyzer holds the span lock) and write them back. It now runs on the UI thread from `NativeCodeEditor.setStyles/updateStyles`, skips the pass when any line read is a placeholder, and invalidates sora's cached line renders (`updateStyles` with a `SequenceUpdateRange`) after writing. Semantic tokens are also requested when switching tabs.
* **Fixes:**
  * `file:///` URIs
  * LSP `shutdown`/`exit` sent without params
  * Python language servers started on demand and stopped when idle; scratch files are ignored
  * close codes 1005/1006 handled
  * semantic-token ContentModified errors silenced

### Keyboard and files (round 2)
* **Shortcuts route at the window level.** They used to depend on Compose focus (a root `onPreviewKeyEvent`), so after launch, a dialog, or a notebook run nothing reached them. `MainActivity` now wraps the content in a layout whose `dispatchKeyEventPreIme` calls `KeyRouter` (before Gboard, which manages the Book Cover's BR layout), with `Activity.dispatchKeyEvent` as the fallback; `WorkbenchController.routeHardwareKey` dedupes. In the code editor, plain Tab/Enter/Escape stay with sora (completion popup, Copilot ghost text).
* **Terminal:** unbound Ctrl+letter is written to the PTY as a control byte (Ctrl+C = 0x03), so the IME can't swallow it.
* **Notebooks:** Shift/Ctrl/Alt+Enter and Ctrl+Shift+Enter are keybindings (`EditorCommands.notebook`); the list scrolls to the selected cell, which takes focus (also after a kernel starts). Up on a cell's first line / Down on its last moves between cells; notebook buttons are out of the arrow-key path (`noKeyFocus`) so Enter can't hit Delete. "Install ipykernel" runs `pip … && exit` and starts the new kernel when pip succeeds.
* **Focus areas:** Editor, Terminal, Notebook, Chat, Other decide which bindings apply (Shift+Enter in chat stays a newline).
* **Removed unimplemented bindings** (breakpoint, folding) and implemented Next/Previous Problem (F8), Undo Cursor Move (Ctrl+U), Block Comment (Shift+Alt+A); Ctrl+/ now toggles every selected line. ABNT2's `/` key (`KEYCODE_RO`) also counts as `/`.
* **Files:** Explorer header has New File / New Folder (one tap; names used as typed, `dir/name` creates folders); long-press or right-click an entry for Rename / Delete (to the daemon's trash). Open files follow a rename. Ctrl+N makes a plain `Untitled-N`; Save on it, and Save As (Ctrl+Shift+S), ask for a guest path and never overwrite. `GuestVfsClient` gained `create`, `rename`, `delete`.
* **Debug logging:** `routeHardwareKey` still logs routed Ctrl/Alt keys under the `AVFKEY` tag, to confirm the Book Cover keyboard; remove it once confirmed.
* **Guest:** Miniconda `py313_26.9.3` (checksum-verified) in `~/miniconda3`, `conda init bash` (terminals start in `(base)`), ipykernel installed into base by the app's install flow. Anaconda's channel Terms of Service were not accepted.
* **Testing keys like a real keyboard:** Android's `hid` tool registers a USB HID keyboard through `/dev/uhid` (`adb shell hid file.jsonl`); `sendevent` to the Book Cover is denied. Under the BR layout the HID `/` key types `;`.

### Verified on the tablet
* Connect, Explorer, open files, ty and Ruff diagnostics, and the servers stopping when idle.
* Terminal: commands, colours, resize.
* Ctrl+` and the command palette shortcut.
* Notebook: kernel picker, run all, stdout and results, an inline matplotlib image, restart.
* Copilot inline suggestions: signed in, status Normal.
* Chat sign-in up to GitHub's Device Activation page: the code card, copy-and-open into the browser, and Cancel. Approving the device was left to the user.
* Colours stay correct when opening a file, on edit bursts, and on new lines (`~/Projects/hl_test.py`).
* Measured memory (earlier build): daemon 7 MB, ty 41 MB, ruff 18 MB, Copilot language server 283 MB, `copilot --acp` 288 MB.

## Remaining

See the [Open backlog](NATIVE-IDE-BATCHES.md#open-backlog-11-08). One decision from the old list has no entry there: whether to merge daemon changes with Track A's Supervisor branch. The two branches' `lib.rs` have diverged, and Track A's guest install was lost in the 10-23 reset.

## Device leftovers to clean up

Recorded on 10-23 after the 13:46 guest reset, and not rechecked since:
* `~/Projects/hl_test.py` (highlighting test file; the user also edited it), `~/Projects/notes` (empty, from the rename test), and `~/Projects/avf-tests/` (`keys.ipynb`, `comments.py`, `Untitled-1`, plus a `welcome.py` the user saved there with Ctrl+S). The app also keeps an unsaved draft of it with test edits, and test lines in the `welcome.py` scratch file; close both without saving.
