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

## Revisit 2026-11-04: Aardink 0.8.0

Read from 0.8.0's sources, changelog and roadmap, and from the app's code. The `:aardink-lab` app now
builds against 0.8.0 with no code change, on the app's Kotlin 2.4.20 and Compose BOM 2026.09 (Aardink
itself uses Kotlin 2.4.21). The licence is still Apache-2.0 in the file and the POM; GitHub's "Other" label
is its detector, not a change.

Measured on the tablet the same evening, with the guest running and load average about 24: 10,000 lines,
three cold starts each, then 12 pairs of 250 ms injected swipes, two rounds each. The lab gained a release
build (R8, not debuggable), because a debuggable build penalises a Compose editor far more than a view.
These are the release build, compiled with `cmd package compile -m speed -f`:

| Metric | sora-editor 0.23.6 + TextMate | Aardink 0.8.0 |
| --- | --- | --- |
| First editor frame (composition only, not tokenizing) | 66-91 ms | 54-62 ms |
| PSS 8 s after a cold start, before scrolling | 136-152 MB | 430-502 MB |
| PSS after scrolling | 140-142 MB | 121-140 MB |
| Frame deadlines missed | 1 of 4,986-5,226 | 23-24 of 1,608-1,622 |
| Frame p90 / p95 / p99 | 11 / 12 / 12 ms | 10 / 12 / 17 ms |
| Frames over 16.6 ms in one swipe | none of 117 | two of 28-35, at its start: 18-50 ms |

What this adds to the 10-28 figures:
* **The debug build misled that comparison on scrolling.** In debug, today's run repeats 10-28 for both
  versions: 0.7.0 and 0.8.0 both miss 24 and have p95 23 ms, against sora's 1 and 11 ms. Release makes p95 equal.
  The misses stay, at one per swipe or so, so they're a hitch as a gesture starts, not constant jank.
* **Memory depends on when it's read.** Left idle, Aardink stays high: one run settled at 325 MB with
  239 MB of native heap and 13 MB of Java heap, unchanged from 8 s to 90 s (sora 126 MB). Scrolling then
  frees it, to sora's level. Its cause wasn't investigated. It may scale with the 10,000-line document, and
  normal-sized files weren't tested; the lab's document is fixed.
* 0.7.0 to 0.8.0 changed nothing measurable (debug: 276-311 vs 300-332 MB before scrolling, within noise or
  slightly up).

Limits of this test: one editor, not several files; Aardink uses its own declarative Python grammar while
sora's figure includes TextMate, so an adapter would add to Aardink's side; injected swipes, not a finger.
Aardink renders about 30 frames per swipe to sora's 117, so its momentum after the swipe seems much shorter,
which also makes frame totals not directly comparable. How that feels wasn't judged.

To repeat: `./gradlew -p apps/native-android-ide :aardink-lab:assembleRelease`, install it, then
`adb shell cmd package compile -m speed -f com.avfstudio.aardinklab`, and read `dumpsys meminfo` and
`dumpsys gfxinfo` (also `framestats`) for `.LabActivity` and `.SoraLabActivity`.

| Gap on 10-28 | Aardink 0.8.0 |
| --- | --- |
| Host decorations | Closed for ranges and whole lines: background, underline, border, minimap and overview-ruler marks, hover text. They follow edits. |
| Breakpoint icons | Open. The gutter gets a coloured bar (the roadmap's glyph was built as a bar) and the host gets no gutter tap. Not on the roadmap. |
| Document change events | Closed: `contentChanges()`, content and selection listeners. |
| Semantic tokens, ghost text, inlay hints | Open, planned for 0.9 (§7.3), which depends on an inline-layout spike. No date, and no 0.9 branch on 11-04. Releases: 0.6 on 10-03, 0.7 on 10-24, 0.8 on 11-01. |
| TextMate | Still none, and not planned. An adapter is possible on the public `IncrementalTokenizer` and `NamedTokenType`, whose colours resolve by scope prefix as TextMate's do. |
| Python | Not built in until 0.10. An adapter makes this moot. |
| `setText` data-loss risk | Closed in 0.6.3: it edits only the exposed window. |

New on our side since 10-28:
* The app draws inlay hints, code lenses, ghost text, `# %%` cell rules, fold arrows and conflict actions
  as overlays on sora, using its line positions. Aardink has no public equivalent: `ViewportInfo` has
  scroll offsets and line numbers, and `CodeEditorLayout` gives the host no way to reach its controller.
  So overlays can't stand in for 0.9's APIs.
* `minSdk`: Aardink needs 26, the app was on 24. Raised to 34 the same day.
* Our folding is two Java files in sora's package (`FoldingLayout`, `EditorLayoutAccess`), extending its
  `LineBreakLayout`. Include them when the LGPL question is settled; the switch would remove them.

Verdict: **still not viable, but closer.** The blockers are now specific, and the switch's conditions are
listed in `docs/NATIVE-IDE-BATCHES.md`, under the backlog's "Refactor: replace sora-editor with Aardink".
Scrolling is no longer the wide gap it looked like, apart from the hitch at each gesture's start. Memory
before the first scroll is now the largest measured problem. Next step: a lab document of normal size.

The user decided on 11-04 that these measurements aren't an impediment and started planning the switch:
[AARDINK-SWITCH-PLAN.md](AARDINK-SWITCH-PLAN.md).

Build and run the available checks from the repository root:

```sh
./apps/native-android-ide/gradlew -p apps/native-android-ide :app:assembleDebug
./apps/native-android-ide/gradlew -p apps/native-android-ide :app:testDebugUnitTest
```
