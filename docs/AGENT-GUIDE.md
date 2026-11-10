# Working on QuireCode: a guide for coding agents

This guide is for any coding agent (or person) picking up work on QuireCode. It says how the work is done, how to report it,
what must never happen on the maintainer's devices, and what earlier sessions learned the hard way. It doesn't depend on
any one agent's tools.

What it doesn't repeat:
* **Current state and setup:** [NATIVE-IDE-HANDOFF.md](NATIVE-IDE-HANDOFF.md).
* **The batch record and the open backlog:** [NATIVE-IDE-BATCHES.md](NATIVE-IDE-BATCHES.md).
* **House rules for contributors:** [CONTRIBUTING.md](../CONTRIBUTING.md), covering Material You, the editor engine, settings
  and testing.

Read those three first, in that order. Where this guide and an explicit instruction from the maintainer disagree, the
maintainer wins. Then record the new rule here.

---

## 1. The working loop

Every piece of work is a **batch**: one coherent change, recorded and committed as a unit.

1. **Start from the record.** Read the handoff's "Current state", the last two or three batch entries, and the open backlog.
   Run `git log --oneline -10` and look at CI for the latest commit (`gh run list -L 5`). If the latest commit is red, fixing
   it is the first job.
2. **Agree on the scope.** The maintainer picks the batch, usually from candidates you suggest (see §2). "Register" or
   "add to the backlog" means write it down, not build it.
3. **Read before writing.** Find the code paths involved and read them whole. `WorkbenchController.kt` is over 11,000
   lines; search it, but read each function you change from top to bottom.
4. **Implement in small, verified steps.** After each step, build, run the unit tests, and check it on the tablet when it
   has a visible effect.
5. **Do a second pass before calling it done.** Re-read your own diff as a reviewer. Look for:
   * paths that skip the new rule (offline, device folders, the tablet guest versus a computer);
   * errors that are swallowed;
   * threading (UI state is only touched on the main thread);
   * claims in the docs that the code doesn't back.

   Every second pass so far has found real bugs.
6. **Record it.** Add the batch's section to NATIVE-IDE-BATCHES.md (§3). Update the backlog tables, the handoff's "Current
   state", and HOSTING-THE-DAEMON.md or the README when user-facing behaviour changed.
7. **Commit once per batch** (§4). Don't push unless the maintainer says so. Once they have said "you can push" for a
   batch, push after CI-equivalent checks pass locally.
8. **Report** (§2), then clean up test state on the device (§6).

If a session may run out mid-batch, commit progress into the batch doc early ("in progress: done X, Y; left Z"), so the
next agent can resume from the record rather than from guesses.

---

## 2. Talking to the maintainer

The maintainer reads reports on a phone or a narrow pane. They decide quickly when given clear options.

**Style:**
* Plain, short sentences. Lead with the outcome. Skip the narration of what you tried, unless a failure matters.
* Use the product's words ("kept offline", "paired devices", "the guest") rather than internal names, unless the
  maintainer is asking about code.
* Say what was **not** checked, and why. "Builds and tests pass; not tried on the tablet because the guest was off" is a
  complete, acceptable sentence. Never imply a check you didn't run.
* When you disagree or see a risk, say so plainly, with the concrete failure ("a second tablet of the same model loses its
  pairing"), and propose the fix.

**Decisions:**
* End with the decisions that are theirs to make, each with your recommendation first. Don't ask about things with an
  obvious default; pick it and mention it.
* Destructive or outward-facing steps need a fresh yes right before you do them, even if a similar step was approved
  earlier. These include force-stopping Linux Terminal (which restarts the guest), uninstalling, rewriting history,
  pushing, tagging, and deleting user files.

**The end-of-batch report always has:**
1. **What the user gets**, in a few bullets.
2. **What was checked**, on the tablet and in tests (counts: "398 app tests, 144 daemon tests").
3. **What was found and fixed on the way.**
4. **What's left, and the risks.**
5. **The backlog, in full:** three tables (G general, A Aardink, R release) with the open items only. Summarise the done
   ones in a line.
6. **The candidate next batches,** two to four of them, with sizes and a recommendation.

The maintainer asked for item 5 and item 6 on *every* "what next?" answer, not only after batches.

---

## 3. The batch record (NATIVE-IDE-BATCHES.md)

Each batch gets a numbered section with these parts:

* **A heading:** `## NN. What the user gets (MM-DD; done | in progress, checked on the tablet | not checked)`.
* **The request:** the maintainer's words, quoted *only when they really said them*. If the work came from another
  agent's plan or prompt, say that ("Continued from batch 66's plan"). Don't present an agent's prompt as a user quote.
* **What changed,** grouped by feature, naming the files and functions a later agent will look for.
* **Checked on the tablet:** the concrete steps and what was seen. Describe your own run, not a template.
* **Found and fixed on the way.**
* **Left / not done.**
* **Tests:** the counts.

Backlog rows keep their numbers forever. When an item is done, its batch says so and the row says "Done in NN" (or goes).
Sizes are S, M, L, L+.

---

## 4. Commits and CI

* **Identity:**
  `GIT_AUTHOR_NAME=Kinteshi GIT_AUTHOR_EMAIL=36821534+Kinteshi@users.noreply.github.com`, with the same values for
  `GIT_COMMITTER_*`.
* **Message:**
  * A subject line: `Batch NN: <what the user gets>`.
  * A blank line, then a short body: the main changes, what was checked, and the test counts.
  * If your tool asks for one, end with a trailer that names the agent, e.g. `Co-Authored-By: <agent/model> <noreply@…>`.
* **Never commit a red build.** Before committing, run all of these:
  ```sh
  ./apps/native-android-ide/gradlew -p apps/native-android-ide :app:testDebugUnitTest :app:assembleDebug
  cargo test -p avf-daemon -p avf-protocol
  cargo clippy -p avf-daemon -p avf-protocol
  ```
  These are what CI runs ([ci.yml](../.github/workflows/ci.yml)), plus clippy, which CI doesn't run. As of batch 68, clippy
  1.99 reports five older lints (`tmpdir.rs`, `pairing.rs`, `lib.rs` around line 2586). Don't add new ones.
  Batch 67 was pushed missing about 40 controller functions and failed CI; batch 68 had to restore them. When a large
  file is regenerated or merged, compare the function list before and after (e.g. `grep -c "fun "`).
* **Check CI after every push** (`gh run list`, `gh run view --log-failed`).
* **Don't commit:**
  * the untracked top-level `android/` folder;
  * `local.properties`;
  * screenshots, scratch scripts or test daemons' files.
* **Signed release builds come only from CI.** Key passwords never enter a session. Tagging v0.1.0 waits for the
  maintainer's call on repository visibility.

---

## 5. Hard rules for the maintainer's devices

The tablet is the maintainer's working machine, and its Linux guest holds their uncommitted work. These rules come from
real incidents.

**Never:**
* Press Back in Linux Terminal. It shuts the VM down.
* `cp` a file out of `/mnt/shared` inside the guest. crosvm's virtio-fs panicked on `copy_file_range` and killed the VM.
  Use `cat src > dst`.
* Trim caches (`drop_caches`, `compact_memory`) or unbind the virtio balloon. The guest corrupts memory under host
  pressure, and unbinding the balloon resets the VM.
* Start many processes in any app. Android allows 32 phantom processes device-wide, and the VM uses about 6. Going over
  trims Terminal's `virtmgr`.
* Open, run or edit the maintainer's own files and notebooks. Today these are `main.ipynb`, `private-notebook-1.ipynb`,
  `private-notebook-*.ipynb` and `private-notebook-2.ipynb` in `~/Projects`. Test in your own throwaway folder
  (`~/Projects/bNN`).
* Install packages in the guest without asking.
* Tap contacts in Android's share sheet.
* Print or log pairing codes or tokens. Stage them in a file, type them on the device, delete the staged file.
* Run downloaded third-party binaries on the development machine.
* Use `pkill -f <pattern>` from an agent's shell. It matches the agent's own command line and kills it. Use
  `pkill -x <name>` (the name is truncated to 15 characters: `quirecode-daemo`).

**Always:**
* **Look before every tap.** Take a screenshot first and never tap from remembered coordinates; windows float and move.
  Check `adb shell dumpsys window | grep mCurrentFocus` first: the dev app and the release app look alike, and the
  maintainer may pick the tablet up mid-session. If another app is in front, stop.
* **Ask before touching Settings.** Settings live on the device. If one differs from what you expect, the maintainer
  probably changed it, so ask before reverting.
* **Test in the dev app.** Test builds install as `io.github.kinteshi.quirecode.dev` ("QuireCode Dev", orange, DEV badge)
  and stay installed beside the release app. The release-signed app is only updated with CI's APK.
* **Clean up after a batch:**
  * your test folders in the guest;
  * staged files in `/sdcard/Download` and `/data/local/tmp`;
  * test connections and test pairings (revoke them);
  * Recent-folder entries you created;
  * temporary settings changes;
  * `adb reverse` rules;
  * daemons you started on the development machine.

---

## 6. Driving the tablet

The reference device is a Galaxy Tab S11 Ultra (SM-X930) on wireless adb at `192.0.2.66:<port>`. The port changes after
reboots, so ask for it. Detailed notes are in the handoff's "Reference tablet" section.

**Screen:**
* `adb exec-out screencap -p > shot.png`, then downscale to 1000 px wide to read it.
* **Tablet pixels = screenshot pixels × 2.96** at 1000 px wide (× 1.48 at 2000 px). A wrong factor has hit the wrong
  button before.
* The display is 2960×1848 at 280 dpi (1.75 px per dp).
* If the app opens as a floating window, double-tap its caption to maximize. Check the icon first: double-tapping an
  already maximized window shrinks it.
* Taps below y≈1804 land in Android's gesture zone.
* Avoid `uiautomator dump` while Linux Terminal is visible. It reloads the Terminal page.

**Text input:**
* `adb shell input text` drops characters in Compose fields. Type in 4-character chunks with `sleep 0.3` between them,
  single-quoted inside the adb string (`#`, `>` and quotes get cut otherwise). Use `%s` for spaces.
* Never type code into the editor through `input text`. Suggestions and ghost text mangle it. Write test files with a
  pushed script instead.
* For real keyboard shortcuts, register a HID keyboard with Android's `hid` tool (every JSON line, delays too, needs an
  `id`). The maintainer's Book Cover uses a Brazilian layout, so `/` types `;`.
* Mouse hover can be tested with a HID mouse. Read the pointer from `dumpsys input`.

**Guest commands:**
* Push a script to `/sdcard/Download`, bring Linux Terminal to the front, and type `bash /mnt/shared/Download/x.sh`.
* Write output to `/mnt/shared/Download/*.log` and read it back with adb.
* To restart the daemon: `systemctl --user stop quirecode; sleep 3; systemctl --user start quirecode`. Never `restart`:
  the port forwarder loses the port.
* To update the guest daemon:
  * Build with `cargo zigbuild --release -p avf-daemon --target aarch64-unknown-linux-gnu.2.28`.
  * Push the binary, then install it from Linux Terminal, not from the app's own terminal. That shell is a child of the
    daemon it stops.

**Health checks:**
* Is the VM up? `adb shell ps -A | grep crosvm_debian`. A `virtmgr` alone doesn't mean it is.
* Is the daemon forwarded?
  `(printf 'GET /health HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n'; sleep 2) | adb shell nc 127.0.0.1 19870`.
* If the port is open but nothing answers, Linux Terminal's forwarding is stuck. Turning on the tablet's Tailscale VPN did
  this once. The fix is force-stopping Linux Terminal, which restarts the guest and needs the maintainer's OK.

**The development machine as a remote host.** This WSL machine can play "a computer running the daemon". It is on the
maintainer's tailnet as `example-host` (100.64.0.1); the tablet is 100.64.0.2.
* Run the built daemon with a throwaway `HOME` and `AVF_BIND_ADDR=100.64.0.1:<port> AVF_TLS=1`.
* Pair with `quirecode-daemon pair --remote …` and type the connection string on the tablet in chunks.
* Without Tailscale, use `AVF_BIND_ADDR=127.0.0.1:<port>` plus `adb reverse tcp:<port> tcp:<port>`.
* Remove everything afterwards.

**Android's folder picker** refuses the storage root and Download's root. For tests, make a new empty folder in the
picker; Documents holds the maintainer's notes vault.

---

## 7. The code, in one page

```
apps/native-android-ide/app/src/main/java/io/github/kinteshi/quirecode/
  MainActivity.kt, GuestConnectionService.kt
  workbench/   ~90 files: the whole UI (Compose) and WorkbenchController.kt, the single state holder
  document/    DocumentRepository (drafts, the encrypted cache, saves with conflict checks), DeviceTreeFs and
               RoutingVfs (tree:/ paths go to Android's SAF, the rest to the guest), OfflineIndex (kept folders)
  transport/   GuestVfsClient (WebSocket protocol client), ConnectionProfileStore (encrypted), PinnedTls
  editor/      Aardink engine integration, the app's own Material cards drawn over it
  language/    LSP clients (ty, ruff and others in the guest), tm4e grammars (module apps/native-android-ide/tm4e)
  notebook/    .ipynb model, cells, the Jupyter bridge client
  ai/          Copilot and agent panels
crates/avf-protocol   messages shared by app and daemon; feature strings in Welcome
crates/avf-daemon     the guest/computer daemon: vfs, search, pty, lsp, git, tools, pairing, devices, tls
scripts/install-native-daemon.sh   guest and Linux installer (--remote, --tailscale)
```

**Concepts that cut across features:**
* **Three kinds of path:**
  * guest paths (`/home/droid/...`);
  * device folders (`tree:/<encoded SAF uri>/...`);
  * single device files (`device:/...`).

  Show paths with `displayPath` / `displayName`, never raw. Language servers, kernels, Copilot and terminals only exist
  for guest paths. Guard every new guest-only feature with `onDevice()` / `guestWorkspace`.
* **Offline:** when the guest is away, `RoutingVfs.servesOffline` answers kept folders from `OfflineIndex`, and file text
  comes from `DocumentRepository`'s copies. Saves queue (`SaveResult.QueuedOffline`) and go out on reconnect. Kept-offline
  limits are 2 MB per file, 5,000 files and 256 MB in all; binary files are skipped.
* **Connections:** `connectionSummary(ConnectionFacts)` is the one source for the chip, the banner and the dialog. Add
  states there, not in the UI. Reconnects are always scheduled after a failure. Diagnoses that are specific to Linux
  Terminal apply only when `isTabletGuestEndpoint`.
* **Daemon security:**
  * Each paired device has its own token, stored as a SHA-256 digest in `devices.json`.
  * The shared install token is accepted only from loopback.
  * Failed attempts are limited (10 in 10 minutes, loopback exempt), and connections are logged.
  * Anything that makes remote traffic arrive from loopback (a reverse proxy, a tunnel) breaks these assumptions. Treat
    it as a security change.
* **Protocol changes:** add a feature string in Welcome, and make the app check it before using the new messages. Old
  daemons must keep working.

---

## 8. Coding conventions

* **Comments say why**, especially workarounds: name the device behaviour, library version or VS Code behaviour matched.
  Tag backlog items in KDoc (`(G29)`).
* **Every feature gets a deliberate Settings decision:** a toggle, a picker, or a stated reason for none. Use VS Code's
  key when one exists, `avf.…` otherwise.
* **Material You, and the workbench's own cards** over library chrome. Calm wording in the UI: say what happened and that
  the app keeps working.
* **Tests for logic you change.** Pure functions (`connectionSummary`, `parseConnectionString`, `OfflineIndex`) get unit
  tests. Daemon changes get Rust tests in the same module. A test that passes for the wrong reason isn't a test; check
  that the trigger you claim is the one that fires.
* **Threading:** controller state changes on the main thread (`post { }` / `mainHandler.post`). I/O goes on the `io`
  executor. Startup work that touches state is posted, not run in `init`; running it in `init` crashed a session restore.
* **Kotlin and Rust pitfalls:**
  * The controller's `io` property shadows the `io.github…` package in fully qualified names. Import instead.
  * A KDoc containing `src/**` opens a nested comment that never closes. Write `src/` and "everything below it".
  * Android's ICU regex rejects an unescaped `}` that the JVM accepts in unit tests. Escape braces.
  * Fix clippy warnings in code you touch. Allow one only with a reason (as on `process_packet`).
* **Guest tools that walk files** are limited to 2 threads. Many threads at once collapse the AVF guest's I/O.
* **Dependencies:** Apache-2.0, MIT, BSD or EPL. No GPL or LGPL in the app. Record notices for what ships.

---

## 9. Lessons worth keeping

* **Verify before you claim.** An instruction in the docs ("exclude X in split tunnelling") is a promise to users. Only
  write what was tried, or mark it as a suggestion. Likewise "checked on the tablet" means you saw it, today, in this
  build.
* **Test the trigger alone.** A refresh "worked" in batch 38 only because something else also refreshed. Remove the
  other causes before concluding.
* **Second passes find bugs.** Batches 62, 63 and 66 each had real defects found on the second pass:
  * a crash on tree-path search results;
  * a dirty dot that stayed after saving;
  * a startup race replacing a device folder;
  * no reconnect after deleting the last-used profile.
* **Security changes need a threat model, not just a feature.** Before adding a convenience to pairing or networking,
  ask who else could use it. Two examples:
  * Replacing pairings by device *name* lets any client that picks the same name revoke another device. Proof of
    ownership (the old token) is the safe key.
  * A tunnel that connects from 127.0.0.1 inherits loopback trust.
* **Don't confuse "safe default" with "nobody asked".** Ask the maintainer only about real decisions, and decide the rest
  yourself, saying what you chose.
* **Leave the device as you found it,** and say in the batch what you left behind on purpose (the dev app, a pairing).
* **Respect the maintainer's taste:**
  * minimal scope (the app hosts the editor; tooling is the developer's);
  * AMOLED-friendly, slim UI;
  * no engine-vs-engine measurements, since the switch to Aardink happens regardless;
  * review every Aardink release against the switch plan.
* **When another agent's work is handed to you,** review it like a pull request:
  * build it and run the tests;
  * read the diffs;
  * check CI for every commit;
  * compare the docs' claims with the code.

  Report what you'd keep and what you'd change before building on it.
