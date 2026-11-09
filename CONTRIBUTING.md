# Contributing to QuireCode

This repository holds **QuireCode**, a native Android IDE for tablets, and the Rust daemon it talks to inside the
Android Virtualization Framework (AVF) Debian guest. These guidelines are for anyone changing it, people and coding
agents alike. They describe what the product is meant to be, the decisions that follow from that, and how work is done
and recorded here. When a change would go against one of them, raise it first rather than working around it.

## What QuireCode is

* **A native editor for a tablet, with a Linux machine behind it.** The app is Kotlin and Jetpack Compose. Everything heavy,
  including language servers, debuggers, Jupyter kernels, Git, search, terminals and Copilot, runs in the AVF guest
  through `avf-native-daemon` ([`crates/avf-daemon`](crates/avf-daemon)). The guest stays an ordinary Linux machine that the
  developer manages.
* **The app hosts the editor; it doesn't replace the developer's tools.** Don't build app-side copies of things a
  developer installs in the guest (toolchains, linters, agents, environments). Offer to install a missing language server
  or tool, and say what it is and where it comes from. Never install anything silently.
* **VS Code's model, not its pixels.** Commands, keybindings, `settings.json` keys, `launch.json`, theme and grammar
  formats, and the Command Palette's vocabulary follow VS Code, so habits and project configuration carry over. When it
  isn't obvious how something should behave, do what VS Code does, and name the VS Code setting or behaviour in the code.
* **Memory and latency come first.** The app shares a tablet with a virtual machine, and memory pressure has destabilised
  the guest before. Respect the budgets in [the spec](docs/NATIVE-ANDROID-IDE-SPEC.md) (section 11), and measure on the
  device rather than estimating.

The full specification is [docs/NATIVE-ANDROID-IDE-SPEC.md](docs/NATIVE-ANDROID-IDE-SPEC.md); its "Scope reset" section
overrides the older sections where they disagree. [SPEC.md](SPEC.md) describes Track A (the code-server workbench), which is
separate.

## Look and feel: Material You

Material You is the style guideline for the whole UI.

* **Use the app's `MaterialTheme`** colours, shapes and typography, and Material 3 components. Dynamic colour, light, dark
  and the pure black option must all work. Prefer the surface container roles (`surfaceContainerHigh` and so on) and their
  `on…` colours over hand-picked ones. Keep content readable on its container: an icon or label in a coloured banner takes
  that container's `on…` colour.
* **Library chrome must not stand out.** When a library draws UI in its own colours (the editor engine's popups, for
  example), wrap it, theme it, or draw the workbench's own component in its place. The editor's cards (hover, parameter
  hints, the peek, Next Edit, suggestions) are the reference: `surfaceContainerHigh`, 8 dp corners, an `outlineVariant`
  border, and Markdown with code coloured like the editor.
* **Slim chrome, AMOLED-friendly.** No decoration for its own sake. Dense where a developer expects density, generous
  where touch needs it.
* **Text in the UI** is plain and in sentence case. A setting's description says what changes when it's switched, and an
  error says what to do next.

## The editor engine

The editor is [Aardink](https://github.com/aardarch/aardink) (Apache-2.0), behind the `EditorSurface` interface. It
replaced sora-editor (LGPL-2.1) in 2026 ([the switch plan](docs/AARDINK-SWITCH-PLAN.md)). Code is coloured by TextMate
grammars through Eclipse tm4e (EPL-2.0), carried as the `tm4e` module: its files keep their licence, and its README
says what was changed.

* **Workbench code talks to `EditorSurface`, not to engine classes.** The engine's own code lives in its surface and pane
  files (`AardinkEditorSurface`, `AardinkEditorPane`, `AardinkLanguageService` and their neighbours), so a later engine
  change stays as contained as this one was.
* **Reach into an engine's internals in one place only, and always with a fallback.** `AardinkGeometry` reads Aardink's text
  positions by reflection, and the app must work, docked and plainer, when that answers null. Every such workaround has a
  backlog item asking upstream for the real API.
* **Pin the engine's version** and take each release in a batch of its own. Review it against the switch plan and the
  backlog ([docs/AARDINK-SWITCH-PLAN.md](docs/AARDINK-SWITCH-PLAN.md), "Release reviews"), and recheck the workarounds.
* **An edit is an edit.** Change text with `applyEdits` (one minimal edit; `minimalEdit` computes it), never by replacing
  the whole text. An edit keeps undo history, reaches the language servers, and moves breakpoints, hints and decorations
  with the text. `setText` is for reloads only, and a reload must tell the servers itself (`notifyDocumentChanged`).
* **Hardware keyboards are first-class.** The reference device is used with a keyboard cover, and Samsung's input method
  composes words even from a hardware keyboard. Keys the editor handles specially (pairs, Backspace, `else:`) are handled
  before the input method, as the existing ones are.
* **Requests to upstream** are drafted in [docs/AARDINK-UPSTREAM-REQUESTS.md](docs/AARDINK-UPSTREAM-REQUESTS.md). The
  maintainer decides whether to file them.

## Features and settings

* **Consider a Settings switch for every feature**, and decide deliberately whether it needs one. Use the VS Code key when
  one exists (`editor.linkedEditing`), and an `avf.…` key otherwise. Settings live on the device. If one is not what you
  expect, the user probably changed it, so ask before changing it back.
* **Dependencies:** prefer Apache-2.0, MIT, BSD and EPL. Avoid GPL and LGPL in the app; that licence is why the editor
  engine changed. Record notices for what ships.
* **Guest tools that walk files** get a thread limit (two threads, as ripgrep and ty have): many threads at once collapse in
  the AVF guest.

## How work is done here

* **Work in batches.** Each batch is one coherent piece of work, recorded as a numbered section in
  [docs/NATIVE-IDE-BATCHES.md](docs/NATIVE-IDE-BATCHES.md) and committed together with its code. The section says what
  changed and why, what was checked on the tablet and what wasn't, what was found and fixed on the way, and what is left.
* **Keep the open backlog current.** The "Open backlog" section has two tables: the general backlog (`G` numbers) and the
  Aardink switch's (`A` numbers), each grouped by what is cheaper done together, with a size (S, M, L) and a one-line
  description. Numbers stay stable. When an item is done, its batch says so and the row goes. "Register" or "add to the
  backlog" means record it, not implement it.
* **Update the plan and handoff documents** ([docs/AARDINK-SWITCH-PLAN.md](docs/AARDINK-SWITCH-PLAN.md),
  [docs/NATIVE-IDE-HANDOFF.md](docs/NATIVE-IDE-HANDOFF.md)) when a batch changes what they say.
* **Commit messages** start with the area and the batch: `Native IDE: batch 55, Aardink phase 6 (A5): …`, then say what the
  user gets. Don't commit the untracked `android/` folder at the root. Pushing is the maintainer's call.
* **Code comments say why**, especially where the code works around a library or a device: name the library version, the
  device behaviour, or the VS Code behaviour being matched.

## Testing

* **Unit tests:** `./gradlew :app:testDebugUnitTest` in `apps/native-android-ide`. The app builds with JDK 17. Tests that
  touch Aardink run on Java 21, which Gradle picks up when it's installed. Daemon tests: `cargo test` at the root. Add tests
  for logic you change. A test that passes for the wrong reason (a listener the real code has but the test didn't wire up,
  say) isn't a test.
* **On the device:** behaviour is checked on the reference tablet (a Galaxy Tab S11 Ultra over wireless adb) with the guest
  running, and the batch says what was checked. Where the device is someone's working machine:
  * Test in a throwaway folder (`~/Projects/bNN`) and delete it afterwards, with anything staged in `/sdcard/Download` and
    `/data/local/tmp`. Leave breakpoints, tabs and settings as they were.
  * Never open, run or change the user's own files and notebooks. Don't install packages in the guest without asking.
  * Look before acting: take a screenshot before any tap, and never tap from remembered coordinates (windows float and
    move). Never press Back in Linux Terminal, which shuts the VM down.
  * Keep processes few. Android limits phantom processes to 32 device-wide, and the VM uses some of them. Never trim the
    host's caches or unbind the memory balloon.
* **Report honestly.** If something wasn't checked, the batch says so. A measurement is recorded with how it was taken, so
  later ones can be compared (key-to-frame through the input method isn't comparable with injected keys).

## Where to start

Read [docs/NATIVE-IDE-HANDOFF.md](docs/NATIVE-IDE-HANDOFF.md) for the current state, then the open backlog at the end of
[docs/NATIVE-IDE-BATCHES.md](docs/NATIVE-IDE-BATCHES.md) for what's next.
