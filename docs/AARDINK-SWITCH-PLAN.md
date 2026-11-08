# Plan: switching from sora-editor to Aardink

Status: **phase 2 done** (2026-11-08) on Aardink **0.9.0**; sora stays the default. Phase 3 (language features) is next.

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

Reviewed against each release (see [Release reviews](#release-reviews)); last against 0.9.0 on 11-08.
Filing them is the user's decision.

| # | Request | Where it stands (0.9.0) | Unblocks |
|---|---|---|---|
| 1 | Public text positions: pixel rect for an offset or line | Not on the roadmap. **Needed far less since 0.9:** inline text, inlay hints, code lens, ghost text, Markdown hover and signature help are built in | Next Edit's card and the definition peek, the two overlays left without a built-in answer |
| 2 | Gutter icon in a decoration, and a host callback for gutter taps | Not on the roadmap | Breakpoints |
| 3 | Ghost text, inlay hints, code lens | **Delivered in 0.9** (`InlineCompletionProvider`, `inlayHints`, `codeLenses`). Not yet tried on a device by Aardink, nor with Samsung's keyboard | Copilot's suggestions, inlay hints, lenses, Python cell and merge conflict actions |
| 4 | Semantic token layer | **Delivered in 0.9** (`semanticTokens`, `semanticHighlighting`). Ours works already (batch 44); theirs can replace it once completion goes through `LanguageService` | Optional |
| 5 | Saved view state (scroll, folds) | 1.0 | Optional: we can keep it per tab |
| 6 | The slow frames as a gesture starts | 0.10's performance work (Android gates, baseline profile). Unchanged in 0.9 (measured 11-08) | Better scrolling |
| 7 | Samsung's keyboard: Enter and Shift+arrows (backlog 73) | Not mentioned; 1.0 plans device validation with Samsung's keyboard. **Still there in 0.9** (lab, 11-08) | Removing our workaround |

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

### Phase 2: colours (done 11-08, batch 44)
* `editor/TextMateTokenizer.kt` with semantic colours over the grammar's; `AardinkSyntax` with its own tm4e registry from the
  same grammar files and themes as sora's (Python, grammar packs with embedded languages, imported, Material You, built-in).
* Bracket pair and semantic colours from the app's tables. Checked on the tablet except semantic colours and imported themes.
* tm4e: still from sora's package; for phase 7, upstream 0.18.0 (builds with Java 21, now installed) or sora's 110 files.
* Parity note: Aardink's lines are taller at the same font size (`EditorOptions.lineHeight` can match sora's).

### Phase 3: language features (M; was M-L before 0.9)
* Through `LanguageService`, backed by the app's existing LSP client rather than Aardink's: completion
  (replaces `SoraCompletion.kt`) with 0.9's detail pane and `resolveCompletion`, hover and signature help as Markdown
  (0.9), occurrences, go to definition. Aardink's own `LspLanguageService` (0.9) stays unused: our client serves sora too.
* Auto-indent, closing pairs and surrounding a selection from VS Code's `language-configuration.json` (0.9's
  `LanguageConfiguration`); grammar packs ship one. Python's indentation after `:` comes from here.
* Problems onto its diagnostics, with tags and quick fixes.
* Find and replace, keeping the find widget and scope. Snippets, with stops and mirrors.
* Folding onto `FoldingProvider` with the existing LSP and indentation ranges (`requestFoldableRanges` can wait on the server, 0.9).
* Auto-pairing, tab stops, comment and line commands, linked tag edits.

### Phase 4: decorations (M)
* Paused line, conflict tints, symbol and find highlights, Python cell rules, problem marks on the scrollbar
  (its overview ruler).
* Breakpoints, interim: a red gutter bar, toggled by F9 and a menu item (`contextMenuItems`, `touchMenuItems`),
  including conditional ones. No icon or gutter tap until request 2.
* The large-file view and `PeekView` onto read-only editors.

### Phase 5: what's drawn over the code (M; unblocked by 0.9 except two overlays)
Built in since 0.9, so these move onto Aardink's own: ghost text (`InlineCompletionProvider`, Tab, Alt+] and Alt+[),
inlay hints (`inlayHints`), code lenses (`codeLenses`) and through them Python cell actions and merge conflict actions,
hover and parameter hints (Markdown), fold arrows, snippet stops, find scope.
Left: Next Edit's card and the definition peek need request 1, or a design on decorations (`before`/`after`, 0.9).
Aardink hasn't tried ghost text or lenses on a device or with Samsung's keyboard; this phase does.

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
| Requests 1 and 2 are declined or slow | Interim breakpoints work; two overlays wait; sora stays default meanwhile |
| Samsung's keyboard and Aardink disagree (Enter, selection; ghost text and lenses untested there) | Our workaround (batch 43); each release rechecked in the lab |
| Memory pressure destabilises the guest | Not a gate (user's decision). Phase 0 looks good; measured again in phase 6, since it has corrupted the guest before |
| API breaks in a minor | Pinned version, one bump per batch |
| One main author | Apache-2.0 allows a fork if the project stops |

## Release reviews

The user's habit (11-08): with each Aardink release, read its changelog, migration notes and roadmap against this plan
and the backlog; bump the pinned version in a batch of its own; build, run the tests, recheck in the lab what it touches
(keyboard, scrolling, memory) and in the app what we use; then update the requests, the phases and the backlog, and show
the user the full backlog and what changed in coverage.

| Release | Reviewed | What changed for us |
| --- | --- | --- |
| 0.8.0 | 11-04 | Decorations, change events, commands, keybindings, Material 3 themes (batch 41 revisit). |
| 0.9.0 | 11-08, batch 45 | Requests 3 and 4 delivered; phase 3 smaller, phase 5 unblocked but for two overlays; request 1 narrowed. Our tokenizer broke the new rule for an empty `previousTokens` (fixed). No source change needed. Samsung keyboard issue and gesture hitch unchanged. |

## Size

About 1.5-2 weeks of batches left after phase 2 (was 2-3 weeks for all of it); breakpoints and two overlays still depend on Aardink.
Sizes: S about half a day, M about a day, L several days.
