# Drafts: feature requests to Aardink

**Not filed.** Drafts for the user to review and post, or to ask to have posted, at
<https://github.com/aardarch/aardink/issues>, in its "Feature request" template. Everything below each
rule is the issue's text. Checked against Aardink 0.8.0's sources and roadmap on 2026-11-04.

---

> Note (11-08, after 0.9.0): inline text, inlay hints, code lens, ghost text and Markdown hover are now built in, so
> this request is down to two cases, a card for an edit suggested elsewhere in the file and a definition peek. Reword
> the problem around those before filing.

**Title:** [feature] Public text positions for hosts (rect of an offset or line)

## Problem

We're moving an Android code editor app (MaterialCode) from sora-editor to Aardink, and draw several things
over the text from the host: a clickable action row above a code cell, actions on merge conflict markers,
an inline suggestion widget, a popup anchored to a word. To place these, a host needs to know where text
is on screen.

`CodeEditorState.viewport` gives `scrollTop`, `scrollLeft`, `firstVisibleLine`, `lastVisibleLine`, `height` and
`contentHeight`, but no position for a given line or offset. Working it out from the line height breaks
with soft wrap and folds, which the host can't see into. The editor already has the answer internally
(`EditorView.caretRect`, `EditorImeGeometry.characterBoundsInRoot`), but those are internal, and
`CodeEditorLayout` gives a host no handle on them.

Inline decorations and code lens in 0.9 will replace some of this, but not all: anything interactive or
app-specific still needs an anchor. Monaco has the same escape hatch in `getScrolledVisiblePosition` and
content widgets.

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
- Waiting for inline decorations and code lens: covers some cases; not popups or host-specific actions.
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
