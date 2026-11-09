# Drafts: feature requests to Aardink

**Not filed.** Drafts for the user to review and post, or to ask to have posted, at
<https://github.com/aardarch/aardink/issues>, in its "Feature request" template (the bug reports in its bug template).
Everything below each rule is the issue's text. The first three were checked against Aardink 0.8.0's sources and roadmap
on 2026-11-04; the last three (batch 61, 11-09) against 0.9.0, the pinned release.

| Draft | Backlog | Kind |
|---|---|---|
| Public text positions for hosts | A9 (71) | Feature |
| Gutter icons and a host callback for gutter taps | A10 (72) | Feature |
| Completion list at the caret with a hardware keyboard | A13 (77) | Feature |
| Samsung Keyboard: Enter, selections, composing | A11 (73) | Bug |
| `LanguageConfiguration` can't load on Android | A14 (75) | Bug |
| Typing as VS Code does: type-over, Backspace to a tab stop | A15 (76), A18 | Feature |

A9 and A13 refer to each other: the list at the caret needs a caret position, which hosts can't get.

---

**Title:** [feature] Public text positions for hosts (rect of an offset or line)

## Problem

Our Android code editor (QuireCode) moved from sora-editor to Aardink in 0.9.0. 0.9's inline text, inlay hints, code
lens, ghost text and Markdown hover covered most of what we drew over the text ourselves. What's left needs to be placed
at a line or a word, and a host can't find where that is:

* A card for an edit suggested elsewhere in the file (Copilot's next edit suggestions), shown at that edit's line.
* A definition peek under the line it was asked from.
* Our own completion list for hardware keyboards (until the list at the caret works there; see the request about it).
* Hover cards and signature help drawn in the app's own style, at their word.

We place all four today by reading the editor's internals by reflection (the view's caret rectangle and hit
testing), which any release can break; we recheck it with every Aardink release.

`CodeEditorState.viewport` gives `scrollTop`, `scrollLeft`, `firstVisibleLine`, `lastVisibleLine`, `height` and
`contentHeight`, but no position for a given line or offset. Working it out from the line height breaks with soft
wrap and folds, which the host can't see into. The editor already has the answer internally (`EditorView.caretRect`,
`EditorImeGeometry.characterBoundsInRoot`), but those are internal, and `CodeEditorLayout` gives a host no handle on
them. Monaco has the same escape hatch in `getScrolledVisiblePosition` and content widgets.

## Proposed solution

Something small and read-only on `CodeEditorState`, for example:

```kotlin
/** Where this offset's character is, relative to the editor's text area; null if not laid out or folded away. */
fun boundsForOffset(offset: Int): Rect?

/** The vertical extent of this line's rows, relative to the text area; null if not laid out or folded away. */
fun boundsForLine(line: Int): Rect?

/** Where the text area is, in root coordinates, for hosts drawing in their own layer. */
val textAreaBoundsInRoot: Rect?
```

Reads should be snapshot-observable, or there should be a version to observe alongside `viewport`, so an overlay
follows scrolling, zoom and layout. Only lines on screen need an answer, so it adds no layout work.

## Alternatives considered

- Computing from `viewport` and the line height: wrong with soft wrap and folds, and depends on internals.
- Inline decorations and code lens (0.9): they covered the action rows; not cards, peeks or lists.
- A content-widget API, as in Monaco, where the host passes a composable and an anchor: also good, and arguably
  nicer, but a larger change. Positions would be enough for us.

## Scope

- [x] Belongs in `editor` (generic, language-agnostic)
- [ ] Belongs in a consumer app via `LanguageService` / `IncrementalTokenizer` / `FoldingProvider`
- [ ] Not sure

---

**Title:** [feature] Gutter icons in decorations, and a host callback for gutter taps

## Problem

The same app has a debugger, which needs breakpoints: an icon in the gutter per line (plain, conditional,
logpoint, disabled, moved), set and cleared by tapping there.

`DecorationStyle.gutterColor` draws a coloured bar at the gutter's text edge. The roadmap's "gutter glyph" (§6.3)
was built as that bar (§14.1) so the text never shifts, which makes sense as a default, but it can't tell these
states apart. A host also can't receive a tap: `EditorGutterView` handles them internally for fold markers,
annotations and `onLineNumberTap`, which selects the line.

## Proposed solution

Two additive parts, both off by default, so the layout of existing hosts doesn't change:

```kotlin
// A lane reserved only when the host asks for it, so the text doesn't shift when a mark comes and goes.
EditorOptions(showGlyphMargin = true)

// On DecorationStyle: drawn in that lane, on the first line of the decoration's range.
val gutterGlyph: Painter? = null

// On CodeEditorLayout. Where it was tapped lets a host treat the glyph lane differently from line numbers.
onGutterTap: ((line: Int, area: GutterArea) -> Unit)? = null
```

When `onGutterTap` is null, today's behaviour stays. When set, a tap in the glyph lane goes only to the host. A
secondary click or long press there would be useful too, for a breakpoint's menu.

Ranges already follow edits through `TrackedRanges`, so breakpoints would move with their code for free.

## Alternatives considered

- `gutterColor` bars with a distinct colour per state: works as a stopgap, but colour alone can't carry five states,
  and it's poor for accessibility.
- Toggling through a key and context menu items only (`contextMenuItems`, `touchMenuItems`): our stopgap
  too, but on a tablet the gutter is where people expect to tap.
- Drawing icons in a host overlay: needs public text positions (the other request), and tap handling over
  the gutter would conflict with the editor's own.

## Scope

- [x] Belongs in `editor` (generic, language-agnostic)
- [ ] Belongs in a consumer app via `LanguageService` / `IncrementalTokenizer` / `FoldingProvider`
- [ ] Not sure

---

**Title:** [feature] Completion list at the caret on Android when a hardware keyboard is attached

## Problem

On Android tablets with a physical keyboard (ours is a Galaxy Tab S11 Ultra with Samsung's Book Cover Keyboard),
completions only ever show as the touch strip under the editor. The list at the caret, with Up, Down, Enter and Tab
to choose an item, is never used, so with a keyboard a suggestion can be tapped but not chosen from the keys.

In 0.9.0, `PlatformInfo.android.kt` has `actual val hasSoftKeyboard: Boolean = true`, and `CodeEditorLayout` reads
`val completionAtCaret = !PlatformInfo.hasSoftKeyboard`. The key handling for the list (`if (!completionAtCaret || …)
return@key false`) and the commit characters are tied to the same flag, so they're off on Android whatever is attached.

## Proposed solution

Decide at runtime, and let a host decide too:

```kotlin
// EditorOptions
val completionPresentation: CompletionPresentation = CompletionPresentation.Auto

enum class CompletionPresentation { Auto, AtCaret, Strip }
```

`Auto` on Android could show the list at the caret while `Configuration.keyboard == KEYBOARD_QWERTY` and
`hardKeyboardHidden == HARDKEYBOARDHIDDEN_NO`, updating when the keyboard is attached or detached, and the strip
otherwise. A host that knows better (ours routes hardware keys itself) would pass `AtCaret` or `Strip`.

A hardware keyboard's characters may reach the editor through the input method (`commitText`, often composing)
rather than as key events, so `LocalInputModeManager` alone may stay in touch mode; the configuration check
doesn't depend on that.

## Alternatives considered

- Drawing our own list from `completions`: we're doing it meanwhile, but it duplicates the editor's filtering,
  ranking, detail pane and commit characters, and needs a position for the caret, which hosts can't get.
- A build-time flag: tablets attach and detach keyboards, so it has to follow the device's state.

## Scope

- [x] Belongs in `editor` (generic, language-agnostic)
- [ ] Belongs in a consumer app via `LanguageService` / `IncrementalTokenizer` / `FoldingProvider`
- [ ] Not sure

---

**Title:** [bug] Samsung Keyboard on Android: hardware Enter leaves the cursor behind, keyboard selections turn round, words compose from a hardware keyboard

## Problem

On a Galaxy Tab S11 Ultra (Android 16, One UI 8.5) with Samsung Keyboard (`com.samsung.android.honeyboard`), the default
input method on Samsung devices. 1 and 2 reproduce in a bare test app holding only Aardink's editor, on 0.8.0 and again on
0.9.0, so they aren't our host; 3 and 4 were seen in our app:

1. **Hardware Enter:** the line breaks, but the cursor stays at the end of the previous line, before the break.
2. **Shift+arrows from a hardware keyboard:** each selection change comes straight back from the input method as the
   same range turned round (`setSelection(end, start)`), so Shift+Left twice selects one character and then nothing.
3. **Composing from a hardware keyboard:** Samsung Keyboard composes words even when the keys come from the Book
   Cover, so typing `i` after `pr` arrives as the composing region `pr` replaced by `pri`, not as one character
   inserted. Anything that reacts to typed characters (opening the completion list, commit characters, type-over)
   sees a replacement instead.
4. **On-screen Enter:** with a word suggestion showing, Enter can just accept the suggestion without breaking the
   line; in other cases it breaks the line and leaves the cursor before it (as in 1).

## Proposed solution

* Ignore a `setSelection` from the input method that only turns the current selection round (same range, other
  direction), or keep the editor's direction when the range is unchanged.
* Handle Enter (and the moving keys) as key events before the input method when they come from a hardware keyboard,
  as many Android editors do; our host does this meanwhile, which fixes 1 and 2.
* Treat a composing replacement that only adds characters at the end (`pr` → `pri`) as typing those characters, for
  the completion list's trigger and commit characters.
* For 4, finish composing before inserting the line break, so the break lands after the committed word and the
  cursor follows it.

## Alternatives considered

* Our host routes Enter and the moving keys around the input method for a hardware keyboard, and turns the echoed
  selection back once per key. That works for a hardware keyboard, but not for the on-screen keyboard's Enter (4),
  which only the editor's input connection sees.

## Scope

- [x] Belongs in `editor` (generic, language-agnostic)
- [ ] Belongs in a consumer app via `LanguageService` / `IncrementalTokenizer` / `FoldingProvider`
- [ ] Not sure

---

**Title:** [bug] `LanguageConfiguration` can't load on Android: a regex in its initializer

## Problem

`LanguageConfiguration` fails to load on Android: its class initializer compiles a regex containing `\{\d+(,\d*)?}`,
with the closing `}` unescaped, and Android's regex engine refuses it. The JVM's engine accepts that; Android's (ICU) requires the `}` to be escaped. So unit tests on the JVM
pass, and the same code fails on a device.

Every host that wants a language's `language-configuration.json` (Enter rules, indentation rules, auto-closing and
surrounding pairs, comments) through Aardink is affected on Android.

## Proposed solution

Escape the brace (`\{\d+(,\d*)?\}`), and run the class's tests on an Android runtime (an instrumented or
Robolectric test), since the JVM won't catch this kind of difference.

## Alternatives considered

* We read `language-configuration.json` ourselves meanwhile and pass the rules in. It works, but duplicates what
  Aardink has.

## Scope

- [x] Belongs in `editor` (generic, language-agnostic)
- [ ] Belongs in a consumer app via `LanguageService` / `IncrementalTokenizer` / `FoldingProvider`
- [ ] Not sure

---

**Title:** [feature] Typing as VS Code does: type over auto-inserted closers, and Backspace to a tab stop

## Problem

Two small things people coming from VS Code notice at once, both missing in 0.9.0:

1. **Typing over closers:** with auto-closing on, typing `(` inserts `()`, and then typing `)` inserts a second `)`
   instead of moving over the one the editor inserted. VS Code's `editor.autoClosingOvertype` ("auto") types over a
   closer only when the editor inserted it and the cursor hasn't left since. On the on-screen keyboard this gives
   doubled closers all the time.
2. **Backspace in indentation:** in a line's leading spaces, Backspace deletes one space; VS Code's
   `editor.useTabStops` deletes back to the previous tab stop, so four-space indentation comes off in one key, as it
   went on with Tab.

## Proposed solution

```kotlin
// EditorOptions
val autoClosingOvertype: AutoClosingOvertype = AutoClosingOvertype.Auto   // Auto, Always, Never
val useTabStops: Boolean = true
```

For type-over, the editor already knows which closers it inserted (it pairs them); tracking them as ranges that are
dropped when the cursor leaves gives VS Code's behaviour. Paired delete (Backspace between an auto-inserted pair
deletes both) would fit the same bookkeeping.

## Alternatives considered

* Our host does both for hardware keys before Aardink sees them, with its own record of inserted closers. It can't
  for the on-screen keyboard, whose text comes through the input connection, and it duplicates the editor's
  knowledge of what it inserted.

## Scope

- [x] Belongs in `editor` (generic, language-agnostic)
- [ ] Belongs in a consumer app via `LanguageService` / `IncrementalTokenizer` / `FoldingProvider`
- [ ] Not sure

