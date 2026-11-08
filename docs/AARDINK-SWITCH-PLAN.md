# Plan: switching from sora-editor to Aardink

Status: **phase 1 done** (2026-11-08) except the user's own keyboard check; sora stays the default. Phase 2 is next.

**Decided by the user:** the switch will happen, for the licence (Apache-2.0 instead of sora's LGPL-2.1).
On 11-04 they judged the measured memory and scrolling not to be impediments, and expect Aardink's release
cadence to deliver what's missing. Measurements and the API review are in
[native-editor-evaluation.md](native-editor-evaluation.md), "Revisit 2026-11-04".

## Approach

Build Aardink beside sora and flip when it reaches parity, so the app stays usable throughout.

* **A setting picks the engine:** `avf.editor.engine`, `sora` (default) or `aardink`, marked experimental in
  Settings. It is removed with sora in phase 7. A partial Aardink mode is fine while sora is the default.
* **`EditorSurface` is the seam:** a new `AardinkEditorSurface` sits beside `SoraEditorSurface`, so the
  controller changes little: it no longer touches sora at all (phase 0).
* **One live editor at a time,** as today: the app recreates the view when the active tab changes, so tabs
  don't each hold an editor. Scroll and folds per tab are kept by us.
* **One batch per phase or less,** each ending in a commit, tests and a tablet check, as usual.
* **Pin the version.** A minor may break the API until 1.0, so take each one in its own small batch.

## Requests to Aardink

The app's biggest dependencies aren't all on Aardink's roadmap, so the release cadence alone won't deliver them.
These are ordered by how much they unblock. Filing them is the user's decision.

| # | Request | On its roadmap? | Unblocks |
|---|---|---|---|
| 1 | Public text positions: pixel rect for an offset or line, updated on scroll and layout | No | The ten overlays in phase 5, as interim or permanent answers |
| 2 | Gutter icon in a decoration, and a host callback for gutter taps | No: the planned glyph was built as a bar | Breakpoints |
| 3 | Ghost text, inlay hints, code lens | 0.9, after a layout spike | Copilot's suggestions, inlay hints, lenses, Python cell actions |
| 4 | Semantic token layer | 0.9 | Optional: our tokenizer adapter can merge them |
| 5 | Saved view state (scroll, folds) | 1.0 | Optional: we can keep it per tab |
| 6 | The slow frames as a gesture starts; a note on the declarative tokenizer's native memory | Android gates in 0.10 | Better numbers; the app avoids the second through its adapter |

If 1 or 2 are declined, a fork is allowed by the licence but is a real maintenance cost; ask the user before going there.

## Phases

### Phase 0: preparation (done 11-05, except filing requests)
* Lab: file size and tab options. Normal files are fine, and five tabs don't accumulate.
* The TextMate adapter works in the lab (`TextMateTokenizer`, tested on the device), with sora's colours, and removes the
  memory peak: 77-79 MB at 10,000 lines against sora's 107-115. Numbers in
  [native-editor-evaluation.md](native-editor-evaluation.md), "Switch phase 0".
* The controller no longer touches sora; what's left is sora-only code. `minSdk` is 34.
* Still open: the user filing requests 1 and 2, drafted in [AARDINK-UPSTREAM-REQUESTS.md](AARDINK-UPSTREAM-REQUESTS.md).

### Phase 1: a working editor behind the setting (done 11-08, batch 43)
* Settings → Editor → Editor engine (`avf.editor.engine`), `AardinkEditorSurface` and `AardinkEditorPane`. `minSdk` 34 from phase 0.
* Text, selection, undo, events, font size, wrap, line numbers, tab size, focus on opening a file.
* Keys: the window-level `KeyRouter` keeps first say. Enter and the moving keys skip the input method for this editor, and
  a keyboard selection the input method turns round is turned back: Samsung's keyboard and Aardink 0.8.0 lost the cursor
  and the selection's direction otherwise (backlog 73).
* Tests touching Aardink run on Java 21 (installed beside 17).
* **Still for the user:** the Book Cover itself, and the on-screen keyboard (hidden while the Book Cover is attached).

### Phase 2: colours (L)
* The adapter from phase 0, for all grammar packs, in the background and cancellable.
* tm4e as our own module, since it has no official Maven package and leaves with sora: `org.eclipse.tm4e.core`, EPL-2.0.
  Upstream 0.18.0 needs Java 21 to build, so either that or the 110 files from sora's sources jar. Decide here.
* Themes through `EditorTheme`: built-in light and dark, Material You syntax colours
  (`fromColorScheme`), imported VS Code themes.
* Built-in bracket pair colours replace `BracketColorizer.kt`. Semantic tokens merged in the adapter.

### Phase 3: language features (M-L)
* Through `LanguageService`, backed by the app's existing LSP client rather than Aardink's: completion
  (replaces `SoraCompletion.kt`), hover with the app's hiding delay, signature help, occurrences, go to definition.
* Problems onto its diagnostics, with tags and quick fixes.
* Find and replace, keeping the find widget and scope. Snippets, with stops and mirrors.
* Folding onto `FoldingProvider` with the existing LSP and indentation ranges.
* Auto-pairing, tab stops, comment and line commands, linked tag edits.

### Phase 4: decorations (M)
* Paused line, conflict tints, symbol and find highlights, Python cell rules, problem marks on the scrollbar
  (its overview ruler).
* Breakpoints, interim: a red gutter bar, toggled by F9 and a menu item (`contextMenuItems`, `touchMenuItems`),
  including conditional ones. No icon or gutter tap until request 2.
* The large-file view and `PeekView` onto read-only editors.

### Phase 5: what's drawn over the code (M; depends on requests 1 and 3)
Ghost text, inlay hints, parameter hints, hover card, fold arrows, snippet stops, find scope, merge conflict
actions, Python cell actions and Next Edit are drawn at positions read from sora.

For each: use Aardink's own version where it has one (parameter hints, hover and fold markers now; ghost text,
inlays and lenses in 0.9), otherwise an overlay through request 1.

### Phase 6: flip the default (S-M, then time)
* Run through the parity list below on the tablet; `aardink` becomes the default, `sora` stays one setting away.
* Measure the real app: memory with five files, two terminals and servers (sora today: 420-504 MB), keystroke to
  frame (24.3 ms p95), guest stability. These are recorded for the user to judge, not gates.
* Use it day to day for a period the user chooses, with notebooks and the guest running.

### Phase 7: remove sora (S-M)
* Remove the setting, `SoraEditorSurface`, `SoraCompletion`, `NativeCodeEditor`, `BracketColorizer`, the
  `FoldingLayout` and `EditorLayoutAccess` files in sora's package, and both sora dependencies.
* Licences: tm4e (EPL-2.0) notices, with the LGPL question closed in the spec and evaluation.
* Then see what notebook cells could gain from sharing the editor; a separate decision.

## Parity list

Checked on the tablet before phase 6. Features the app has today that the switch must keep:

* **Editing:** auto-pairs and paired delete, tab stops, indentation, comments, line commands, linked tags, snippets,
  format on paste and save, trim whitespace, final newline, encodings and line endings, auto-save.
* **Input:** VS Code shortcuts and custom `keybindings.json`, chords, hardware and on-screen keyboards, touchpad
  pinch, pointer icons, double and triple selection, paste.
* **Language:** completion with its details, hover, signature help, inlay hints, code lenses, go to and peek,
  rename, code actions, occurrences, folding, semantic colours, restart commands.
* **Copilot:** ghost text with Tab, Esc and partial accept; Next Edit.
* **Debug:** breakpoints (plain, conditional, logpoints), moved breakpoints, paused line, hover values.
* **Look:** grammar packs, imported themes, Material You colours, icon themes in tabs, syntax colours in Settings.
* **Files:** large-file paging, hex view, Markdown preview, restored tabs, drafts, deleted-file state, offline mode.
* **Other views:** merge conflict actions, `# %%` cells, Interactive window, find in selection, breadcrumbs, Outline.

## Risks

| Risk | Answer |
|---|---|
| Real keyboards or IME don't hold up | Phase 1 tests them first; stop before the expensive work |
| Requests 1 and 2 are declined or slow | Interim breakpoints work; overlays wait; sora stays default meanwhile |
| Memory pressure destabilises the guest | Not a gate (user's decision). Phase 0 looks good; measured again in phase 6, since it has corrupted the guest before |
| API breaks in a minor | Pinned version, one bump per batch |
| One main author | Apache-2.0 allows a fork if the project stops |

## Size

Still XL: about 2-3 weeks of batches, with phase 5 and the flip depending on Aardink's releases.
Sizes: S about half a day, M about a day, L several days.
