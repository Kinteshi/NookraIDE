# Native editor evaluation

The first Track B prototype lives in `apps/native-android-ide`. It hosts the
native editor in Compose, loads a generated 10,000-line document, and applies a
small TextMate Python grammar and dark theme. It is intended to exercise
first-frame rendering, scrolling, touch selection, and keyboard input on a
device.

## N0 device run and decision memo

Tested 2026-10-16 on a Samsung Galaxy Tab S11 Ultra (SM-X930, Android 16),
landscape at 2960×1848, 280 dpi, with the display confirmed at 120 Hz. The
Book Cover Keyboard Slim was attached. The APK was built in debug mode and
loaded the generated 10,000-line document.

| Check | Result | Interpretation |
|---|---|---|
| Cold activity launch | `am start -W` TotalTime 734–755 ms; in-app first editor pre-draw 276 ms in the latest run | Below the 1.5 s cold-start budget in this prototype run. |
| TextMate | Python-like string, comment, and numeric scopes visibly highlighted | The embedded grammar and theme load and render on-device. |
| Edit-to-frame latency | 20 synthetic ADB key events; p95 7 ms from the editor content-change callback to the next pre-draw | Promising proxy, not the full physical key-down-to-glyph latency; input queue time before the callback is excluded. |
| Scroll at 120 Hz | Repeated 10,000-line scrolls. Cleanest isolated run: p95 9 ms, 0 missed vsync, 1 frame-deadline miss in 292 frames. Another run reported p95 18 ms and 31 deadline misses in 341 frames. | No reliable pass for the no-dropped-frames p95 gate; frame timing varied between runs. |
| Process memory | 263,358–273,674 KiB RSS (257–267 MiB), 137,726–151,219 KiB PSS with the single sample editor open | Exceeds the 250 MB RSS budget before adding five files, terminals, or LSP. |
| Soft keyboard | With the attached cover keyboard, sora-editor's default suppresses the IME. The prototype now opts out of that suppression; Android reported the IME shown and text insertion rendered. | IME path is available, but touch-only composing was not measured with the cover attached. The device's original `show_ime_with_hard_keyboard=0` setting was restored. |
| Guest LSP | No basedpyright server was started | Blocked on Track B's native `Rpc` bridge; the prototype does not claim language-intelligence parity. |

The generated APK is 12 MB. The app was captured over `adb exec-out
screencap`; pulling the screenshot through the tablet filesystem produced a
black image, so that capture path was not used for visual conclusions.

### Decision

Do not promote sora-editor 0.23.6 to the shipping editor yet. The published
artifact is LGPL-2.1 (the draft's Apache-2.0 claim was wrong), single-editor
RSS already exceeds budget, and the 120 Hz no-dropped-frames result is
inconsistent. The 7 ms edit-to-pre-draw result is only a proxy for the required
keystroke-to-glyph metric.

This closes N0 as an **engine decision gate, not as a performance pass**. Keep
the prototype isolated. Before basing Track B on this engine, obtain license
approval or choose an alternative, then repeat memory and frame benchmarks
with a repeatable on-device harness. The native `Rpc`/basedpyright check remains
blocked on protocol work and is not evidence for or against the editor engine.

- Maven Central's `io.github.Rosemoe.sora-editor:editor:0.23.6` declares
  LGPL-2.1. The spec's Apache-2.0 license claim was incorrect. The prototype is
  isolated from the shipping app until redistribution is approved or another
  engine is selected.
- The existing daemon exposes the current LSP router, but Track B's v2 `Rpc`
  transport is not implemented yet. Basedpyright completion over that channel
  remains unverified.
- The Book Cover Keyboard Slim was visible to Android's input manager. Text was
  entered using synthetic ADB key events; no physical key was pressed remotely.

## Alternatives evaluated (license aside)

Rule agreed with the owner: an alternative replaces sora only if it meets every
requirement sora meets. Otherwise sora stays. Aardink was measured on the same
tablet (SM-X930, 120 Hz) with the same 10,000-line document in the isolated
`:aardink-lab` module; Kodemirror and Nemo were rejected from source and docs.

| Requirement | sora-editor 0.23.6 | Aardink 0.6.2 | Kodemirror 0.3.7 | Nemo |
| --- | --- | --- | --- | --- |
| License | LGPL-2.1 | Apache-2.0 | Apache-2.0 | MIT |
| Native, no WebView | yes | yes | yes | yes |
| TextMate grammars | yes | no (Monarch subset only) | no | no |
| tree-sitter | no (spec: later) | no | no | no |
| Touch handles + magnifier | yes | yes | none in source; README: "untested on devices" | handles only, basic |
| IME composition | yes | yes | not on Android | partial |
| Multi-cursor | yes | yes | yes | no |
| LSP hooks | yes (manual) | yes (`languages-lsp`) | yes | minimal |
| Python highlight | via TextMate | needs hand-written grammar | yes (legacy mode) | yes |
| Cold start (first editor frame) | 276-328 ms | 349-378 ms | not run | not run |
| Scroll p95, 10k lines | 9-18 ms | 21-36 ms | not run | not run |
| RSS / PSS | 263-274 / 138-151 MB | 308 / 186 MB | not run | not run |
| Needs toolchain bump | no | Kotlin 2.4 / Compose BOM 2026.09 | KMP | KMP |
| Maturity | years, widely used | 0.6.x, ~1 star | 0.3.x, 38 stars | small |

Aardink is the only candidate close to feature parity, and it works on-device
(highlighting, soft IME, touch focus). It still loses to sora on the metrics
this project gates on (scroll jank, RSS, startup), has no TextMate support (the
spec's grammar requirement) and forces a Kotlin 2.4 / newer Compose toolchain.
Kodemirror lacks touch selection and device testing; Nemo lacks multi-cursor and
a grammar/LSP story. Not tested further: Android CodeView-style libraries, which
lack the multi-cursor, IME and 10k-line behavior required.

**Result: no alternative meets every sora requirement, so sora-editor stays.**
The licensing question (LGPL-2.1 vs the Apache/MIT rule) remains open and must
be settled before shipping. Aardink is the fallback to revisit if LGPL is
rejected, once it gains TextMate-compatible grammars and passes the scroll and
memory budgets.

## Re-evaluation 2026-10-28: replace sora with Aardink?

The user asked to evaluate replacing sora-editor with Aardink. The same tablet was used, under
today's load (the guest VM running, load average about 24). Both editors ran in the
`:aardink-lab` app, which now has a sora screen (`SoraLabActivity`) with the app's TextMate
Python grammar and dark theme. The test was the same 10,000-line document, three cold starts
each, then 12 pairs of swipes. Two rounds each, back to back:

| Metric | sora-editor 0.23.6 + TextMate | Aardink 0.7.0 (0.6.2 the same) |
| --- | --- | --- |
| First editor frame | 441-456 ms | 356-393 ms |
| PSS / RSS, one editor | 195-201 / 294-302 MB | 324-334 / 422-433 MB |
| Frame deadlines missed while scrolling | 1 of 3,834-4,106 | 22-25 of 904-1,106 |
| Frame p90 / p95 | 16-17 / 17-18 ms | 20-21 / 24-26 ms |

What Aardink 0.7.0 adds since 0.6.2 is incremental: diagnostic tags, hover cards, a keyboard
toolbar and editor commands. It already has built-in diagnostics with tags, hover, a completion
list, snippets, find/replace, folding, a minimap, sticky scroll, multi-cursor and an LSP client.
The app's toolchain (Kotlin 2.4.20, Compose BOM 2026.09) now fits it. It is Apache-2.0, with
one main author and one star, very active, and a minor version may break the API.

Gaps that matter to this app, from the 0.7.0 classes and the project's own roadmap
(`docs/AARDINK_ROADMAP.md`):
* **No TextMate.** It uses a declarative (Monarch-like) grammar, and Python isn't built in. The
  app's VS Code grammar packs, imported VS Code themes and generated Material You syntax themes
  all depend on TextMate. Keeping them means writing a tokenizer adapter on tm4e (EPL-2.0)
  ourselves.
* **No semantic tokens, ghost text or inlay hints** until the planned 0.9. Copilot inline
  suggestions and semantic highlighting depend on these.
* **No host decorations** ("Nothing for hosts"; no overview ruler). Breakpoint icons, the
  paused line and bracket colours need them. Document change events are `internal`.
* **A data-loss risk** their own roadmap notes: the accessibility node's `setText` replaces the
  whole document.

Verdict: **not viable now.** It would cost about 130 MB more on the tablet for one editor, which
is RAM this project is short of; host memory pressure has corrupted the guest. It scrolls
visibly worse, and Copilot ghost text, semantic tokens and breakpoints would regress.
Revisit when Aardink ships 0.9 (semantic tokens, ghost text, decorations), by re-running the lab
(`adb shell am start -n com.avfstudio.aardinklab/.LabActivity`, or `.SoraLabActivity`).

Build and run the available checks from the repository root:

```sh
./apps/native-android-ide/gradlew -p apps/native-android-ide :app:assembleDebug
./apps/native-android-ide/gradlew -p apps/native-android-ide :app:testDebugUnitTest
```
