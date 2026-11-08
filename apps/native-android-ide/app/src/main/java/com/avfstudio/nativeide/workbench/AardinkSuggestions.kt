package com.avfstudio.nativeide.workbench

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.TextEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Our own suggestion list for the Aardink editor while a hardware keyboard is attached: on Android, Aardink 0.9 shows
 * suggestions only as its touch strip, which the keys can't choose from (request 8, A13). Asked for as typing goes, as
 * Aardink would, filtered as VS Code filters, and chosen with Up, Down, Enter and Tab; Escape closes it. It goes when an
 * Aardink release shows its own list at the caret with a keyboard.
 */
internal class KeyboardSuggestions(private val state: CodeEditorState, private val scope: CoroutineScope) {
    /** Where suggestions come from; the pane sets it. */
    var service: LanguageService? = null
    /** What typed after a word asks for suggestions (`.`, `(`…): the server's, which Aardink isn't given while this is in charge. */
    var triggerCharacters: Set<Char> = emptySet()
    /** Whether typing asks by itself (the "Suggestions while typing" setting); Ctrl+Space always does. */
    var whileTyping = true

    /** What's shown, best first; null while closed. */
    var shown by mutableStateOf<List<CompletionItem>?>(null)
        private set
    var selected by mutableIntStateOf(0)
        private set
    /** The selected item's documentation once resolved. */
    var documentation by mutableStateOf<String?>(null)
        private set
    /** What's typed of the word the list is for, which the shown items match. */
    var typed by mutableStateOf("")
        private set

    private var all: List<CompletionItem> = emptyList()
    private var incomplete = false
    /** Where the word the list is for starts, and where the caret was when it was asked for. */
    private var anchor = -1
    private var askedAt = -1
    private var request: Job? = null
    private var applying = false
    private var resolving: Job? = null

    /**
     * An edit Aardink reports, as [offset], what went and what came: typing asks or filters, anything else closes. Samsung's
     * keyboard composes words even from a hardware keyboard, so a letter often comes as the word so far replaced by itself
     * and the letter (`pr` → `pri`): an edit that leaves the caret after a word it put there counts as typing.
     */
    fun edited(offset: Int, deletedLength: Int, inserted: String) {
        // What accepting an item puts in isn't typing: it would ask again for the word just finished.
        if (applying) return
        val caret = state.selection.takeIf { it.collapsed }?.start ?: return dismiss()
        val atEnd = offset + inserted.length == caret
        val word = atEnd && inserted.isNotEmpty() && inserted.all(::isWordChar) && deletedLength < inserted.length + 1
        val trigger = atEnd && deletedLength == 0 && inserted.length == 1 && inserted[0] in triggerCharacters
        val deleted = deletedLength > 0 && (inserted.isEmpty() || (atEnd && inserted.all(::isWordChar))) && caret <= offset + inserted.length
        val open = shown != null || request?.isActive == true
        when {
            word -> if (open) follow(caret) else if (whileTyping) ask(invoked = false)
            trigger -> if (whileTyping) ask(invoked = false) else dismiss()
            deleted && open -> follow(caret)
            else -> dismiss()
        }
    }

    /** The caret moved without an edit: a list for a word the caret has left closes. */
    fun selectionMoved() {
        if (shown == null) return
        val selection = state.selection
        if (!selection.collapsed || selection.start < anchor || !wordBetween(anchor, selection.start)) dismiss()
    }

    /** Asks the service for what fits at the caret; [invoked] (Ctrl+Space) shows the list even before a word. */
    fun ask(invoked: Boolean) {
        val service = service ?: return
        val caret = state.selection.takeIf { it.collapsed }?.start ?: return
        request?.cancel()
        val snapshot = state.document.snapshot()
        val wordStart = wordStart(state.text, caret)
        request = scope.launch {
            val items = withContext(state.computeDispatcher) { runCatching { service.completions(snapshot, caret) }.getOrDefault(emptyList()) }
            all = items
            incomplete = service.completionsAreIncomplete
            anchor = wordStart
            askedAt = caret
            arrange(keepSelected = false)
        }
    }

    /** Typing went on: the list follows it, from what it has or, when the server said its answer was incomplete, asked again. */
    private fun follow(caret: Int) {
        if (caret < anchor || !wordBetween(anchor, caret)) return dismiss()
        if (incomplete && request?.isActive != true) ask(invoked = false) else if (request?.isActive != true) arrange(keepSelected = true)
    }

    private fun arrange(keepSelected: Boolean) {
        val caret = state.selection.takeIf { it.collapsed }?.start ?: return dismiss()
        val text = state.text
        val previous = shown?.getOrNull(selected)
        val word = if (anchor in 0..caret) text.substring(anchor, caret) else ""
        val items = filterSuggestions(all, text, caret, anchor)
        typed = word
        if (items.isEmpty()) return dismiss(keepRequest = true)
        shown = items
        selected = if (keepSelected) items.indexOf(previous).coerceAtLeast(0) else 0
        resolveSelected()
    }

    /** Closes the list. */
    fun dismiss(keepRequest: Boolean = false) {
        if (!keepRequest) request?.cancel()
        resolving?.cancel()
        shown = null
        documentation = null
        selected = 0
    }

    /** Up, Down, Page Up, Page Down, Enter, Tab and Escape while the list shows; false for any other key, or when it's closed. */
    fun handleKey(event: KeyEvent): Boolean {
        val items = shown ?: return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> select((selected + 1).mod(items.size))
            KeyEvent.KEYCODE_DPAD_UP -> select((selected - 1).mod(items.size))
            KeyEvent.KEYCODE_PAGE_DOWN -> select((selected + PAGE).coerceAtMost(items.size - 1))
            KeyEvent.KEYCODE_PAGE_UP -> select((selected - PAGE).coerceAtLeast(0))
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                if (event.isShiftPressed) return false
                accept(items[selected])
            }
            KeyEvent.KEYCODE_ESCAPE -> dismiss()
            else -> return false
        }
        return true
    }

    fun select(index: Int) {
        if (shown?.indices?.contains(index) != true || index == selected) return
        selected = index
        resolveSelected()
    }

    private fun resolveSelected() {
        val item = shown?.getOrNull(selected) ?: return
        documentation = item.documentation
        resolving?.cancel()
        val service = service ?: return
        resolving = scope.launch {
            val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { withContext(state.computeDispatcher) { runCatching { service.resolveCompletion(item) }.getOrNull() } }
            if (shown?.getOrNull(selected) == item) documentation = resolved?.documentation ?: item.documentation
        }
    }

    /**
     * Puts [item] in place of the word it's for (a snippet through Aardink's snippet engine), then its other edits (an
     * import), once resolved if the server only gives them then.
     */
    fun accept(item: CompletionItem) {
        val caret = state.selection.takeIf { it.collapsed }?.start ?: return dismiss()
        val (start, end) = acceptRange(item, state.text, caret, anchor, askedAt)
        dismiss()
        applying {
            if (item.isSnippet) {
                state.selection = androidx.compose.ui.text.TextRange(start, end)
                state.execute(EditorCommand.InsertSnippet(item.insertText))
            } else {
                state.executeEdits(listOf(TextEdit(start until end, item.insertText)), listOf(androidx.compose.ui.text.TextRange(start + item.insertText.length)))
            }
        }
        val service = service ?: return
        scope.launch {
            val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { withContext(state.computeDispatcher) { runCatching { service.resolveCompletion(item) }.getOrNull() } } ?: item
            // Only edits before the word: above it, the text is as the server saw it.
            val others = resolved.additionalEdits.filter { it.range.last < start }
            if (others.isNotEmpty()) applying { state.executeEdits(others) }
        }
    }

    private inline fun applying(edit: () -> Unit) {
        applying = true
        try {
            edit()
        } finally {
            applying = false
        }
    }

    private fun wordBetween(from: Int, to: Int): Boolean {
        val text = state.text
        return from in 0..to && to <= text.length && (from until to).all { isWordChar(text[it]) }
    }

    companion object {
        private const val PAGE = 8
        private const val RESOLVE_TIMEOUT_MS = 1_500L
    }
}

private fun isWordChar(char: Char) = char.isLetterOrDigit() || char == '_' || char == '$'

/** Where the word before [caret] starts, as Aardink guesses what a completion replaces. */
internal fun wordStart(text: CharSequence, caret: Int): Int {
    var start = caret.coerceIn(0, text.length)
    while (start > 0 && isWordChar(text[start - 1])) start--
    return start
}

/**
 * [items] that match what's typed of each, in order: by priority, then those starting with it (its case first), then
 * those needing no import, otherwise as the server sorted them. What's typed runs from the start of what an item replaces (or the word, [anchor]) to [caret].
 */
internal fun filterSuggestions(items: List<CompletionItem>, text: CharSequence, caret: Int, anchor: Int): List<CompletionItem> =
    items.map { item ->
        val start = (item.replaceRange?.first ?: anchor).coerceIn(0, caret)
        item to text.subSequence(start, caret).toString()
    }
        .filter { (item, typed) -> suggestionMatches(typed, item.filterText) }
        .sortedWith(
            compareBy(
                { it.first.sortPriority },
                { (item, typed) ->
                    when {
                        item.filterText.startsWith(typed) -> 0
                        item.filterText.startsWith(typed, ignoreCase = true) -> 1
                        else -> 2
                    }
                },
                // What's there already before what another module would have to be imported for (a server's auto-imports).
                { (item, _) -> if (item.detail?.trimStart()?.startsWith("(import ") == true) 1 else 0 },
            ),
        )
        .map { it.first }

/** Whether [typed] is in [candidate] as VS Code matches a suggestion: its characters in order, from a word's start. */
internal fun suggestionMatches(typed: String, candidate: String): Boolean {
    if (typed.isEmpty()) return true
    for (start in candidate.indices) {
        if (!candidate[start].equals(typed[0], ignoreCase = true) || !isWordStartIn(candidate, start)) continue
        var next = start + 1
        var matched = 1
        while (matched < typed.length && next < candidate.length) {
            if (candidate[next].equals(typed[matched], ignoreCase = true)) matched++
            next++
        }
        if (matched == typed.length) return true
    }
    return false
}

private fun isWordStartIn(text: String, index: Int): Boolean {
    if (index == 0) return true
    val before = text[index - 1]
    return !before.isLetterOrDigit() || (text[index].isUpperCase() && before.isLowerCase())
}

/**
 * What accepting [item] at [caret] replaces: the server's range, stretched by what was typed since it was asked for at
 * [askedAt], or the word from [anchor] to the caret.
 */
internal fun acceptRange(item: CompletionItem, text: CharSequence, caret: Int, anchor: Int, askedAt: Int): Pair<Int, Int> {
    val range = item.replaceRange ?: return anchor.coerceIn(0, caret) to caret
    val start = range.first.coerceIn(0, caret)
    val end = (range.last + 1 + (caret - askedAt).coerceAtLeast(0)).coerceIn(caret, text.length)
    return start to end
}

/**
 * The list, under the caret's line from Aardink's geometry (A19), or docked at the editor's bottom edge, or the top when
 * the caret is in the lower half of the view, when that can't be reached (request 1).
 */
@Composable
internal fun AardinkSuggestionList(controller: WorkbenchController) {
    val surface = controller.editorSurface as? AardinkEditorSurface ?: return
    val suggestions = surface.suggestions ?: return
    val items = suggestions.shown ?: return
    val colors = MaterialTheme.colorScheme
    val viewport = surface.state.viewport
    val caretLine = surface.state.cursorLine
    val dockTop = caretLine - viewport.firstVisibleLine > (viewport.lastVisibleLine - viewport.firstVisibleLine) / 2
    val fontSize = (surface.config.fontSizeSp * 0.95f).sp
    val list = rememberLazyListState()
    LaunchedEffect(suggestions.selected, items) {
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == suggestions.selected && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }) {
            list.scrollToItem((suggestions.selected - 3).coerceAtLeast(0))
        }
    }
    // Under the caret's line (over it when there's no room), the label under the word, as VS Code's; docked at an
    // edge when Aardink's geometry can't be reached.
    val caret = surface.geometry?.let { geometry ->
        geometry.caretAt(wordStart(surface.state.text, surface.state.selection.end))?.takeIf(geometry::visible)
    }
    val card: @Composable (Modifier) -> Unit = { placement -> SuggestionCard(suggestions, items, list, fontSize, placement) }
    if (caret != null) {
        AtText(caret, shiftX = (-30).dp) { card(Modifier) }
        return
    }
    Box(Modifier.fillMaxSize()) {
        card(Modifier.align(if (dockTop) Alignment.TopStart else Alignment.BottomStart).padding(horizontal = 56.dp, vertical = 8.dp))
    }
}

/** The list's card: the items, and the selected one's detail and documentation beside them. */
@Composable
private fun SuggestionCard(
    suggestions: KeyboardSuggestions,
    items: List<CompletionItem>,
    list: androidx.compose.foundation.lazy.LazyListState,
    fontSize: androidx.compose.ui.unit.TextUnit,
    placement: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    run {
        Surface(
            color = colors.surfaceContainerHigh,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 4.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            modifier = placement.widthIn(max = 760.dp),
        ) {
            Row(Modifier.heightIn(max = 260.dp)) {
                LazyColumn(Modifier.width(380.dp), state = list) {
                    itemsIndexed(items) { index, item ->
                        val on = index == suggestions.selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (on) colors.secondaryContainer else colors.surfaceContainerHigh)
                                .clickable { suggestions.accept(item) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                kindLetter(item),
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                color = colors.primary,
                                modifier = Modifier.width(14.dp),
                            )
                            Text(
                                highlighted(item.label, suggestions.typed, colors.primary),
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                color = if (on) colors.onSecondaryContainer else colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            item.detail?.let { detail ->
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 140.dp),
                                )
                            }
                        }
                    }
                }
                val selected = items.getOrNull(suggestions.selected)
                val doc = suggestions.documentation?.takeIf { it.isNotBlank() }
                if (selected != null && (doc != null || selected.detail != null)) {
                    VerticalDivider(color = colors.outlineVariant)
                    Column(Modifier.width(340.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        selected.detail?.let {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        doc?.let {
                            Text(
                                it.replace("```python", "").replace("```", "").trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The item's kind as one letter, as a stand-in for VS Code's icons. */
private fun kindLetter(item: CompletionItem): String = when (item.kind) {
    com.aardarch.aardink.core.CompletionKind.Method, com.aardarch.aardink.core.CompletionKind.Function -> "ƒ"
    com.aardarch.aardink.core.CompletionKind.Class, com.aardarch.aardink.core.CompletionKind.Struct, com.aardarch.aardink.core.CompletionKind.Interface -> "C"
    com.aardarch.aardink.core.CompletionKind.Module -> "M"
    com.aardarch.aardink.core.CompletionKind.Keyword -> "K"
    com.aardarch.aardink.core.CompletionKind.Snippet -> "S"
    com.aardarch.aardink.core.CompletionKind.Variable, com.aardarch.aardink.core.CompletionKind.Field, com.aardarch.aardink.core.CompletionKind.Property -> "v"
    com.aardarch.aardink.core.CompletionKind.Constant, com.aardarch.aardink.core.CompletionKind.EnumMember -> "c"
    else -> "·"
}

/** [label] with the characters [typed] matched in bold, as VS Code marks them. */
private fun highlighted(label: String, typed: String, color: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    append(label)
    var next = 0
    for (char in typed) {
        val at = label.indexOf(char, next, ignoreCase = true).takeIf { it >= 0 } ?: break
        addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color), at, at + 1)
        next = at + 1
    }
}
