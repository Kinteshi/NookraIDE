package io.github.kinteshi.nookraide.workbench

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.core.TextEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSuggestionsTest {
    private fun item(label: String, priority: Int = 0, range: IntRange? = null, edits: List<TextEdit> = emptyList()) =
        CompletionItem(label, CompletionKind.Function, label, sortPriority = priority, replaceRange = range, additionalEdits = edits)

    private class Service(var items: List<CompletionItem>) : LanguageService {
        var asked = 0
        override val completionsAreIncomplete: Boolean = false
        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = items.also { asked++ }
        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()
        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""
        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null
        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null
        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text
    }

    private fun setUp(text: String, items: List<CompletionItem>): Triple<CodeEditorState, KeyboardSuggestions, Service> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val state = CodeEditorState(text, PlainTextTokenizer, 0L, scope).apply { computeDispatcher = Dispatchers.Unconfined }
        val service = Service(items)
        val list = KeyboardSuggestions(state, scope).apply {
            this.service = service
            triggerCharacters = setOf('.')
        }
        state.addContentListener { changes -> changes.filter { !it.isReset }.forEach { list.edited(it.offset, it.deletedLength, it.insertedText) } }
        return Triple(state, list, service)
    }

    private fun CodeEditorState.type(text: String) = text.forEach { char ->
        val caret = selection.start
        executeEdits(listOf(TextEdit(caret until caret, char.toString())), listOf(TextRange(caret + 1)))
    }

    @Test
    fun typingAsksOnceThenFiltersWhatItHas() {
        val (state, list, service) = setUp("", listOf(item("print"), item("property"), item("sprint"), item("len")))
        state.type("pr")
        assertEquals(1, service.asked)
        assertEquals(listOf("print", "property"), list.shown?.map { it.label })
        state.type("i")
        assertEquals(1, service.asked)
        assertEquals(listOf("print"), list.shown?.map { it.label })
        // Nothing matches: closed.
        state.type("x")
        assertNull(list.shown)
    }

    @Test
    fun aTriggerCharacterAsksAndASpaceCloses() {
        val (state, list, service) = setUp("os", listOf(item("path"), item("sep")))
        state.selection = TextRange(2)
        state.type(".")
        assertEquals(listOf("path", "sep"), list.shown?.map { it.label })
        state.type(" ")
        assertNull(list.shown)
        assertEquals(1, service.asked)
    }

    @Test
    fun acceptingReplacesTheWordAndAddsAnImportAbove() {
        val import = TextEdit(0 until 0, "import os\n")
        val (state, list, _) = setUp("x = ", listOf(item("os_path", edits = listOf(import)), item("other")))
        state.selection = TextRange(4)
        state.type("os")
        list.select(0)
        list.accept(list.shown!!.first())
        assertEquals("import os\nx = os_path", state.text)
        // What it put in doesn't open the list again.
        assertNull(list.shown)
    }

    @Test
    fun movingAwayFromTheWordCloses() {
        val (state, list, _) = setUp("ab\n", listOf(item("print")))
        state.selection = TextRange(3)
        state.type("p")
        assertTrue(list.shown != null)
        state.selection = TextRange(0)
        list.selectionMoved()
        assertNull(list.shown)
    }

    @Test
    fun filteringMatchesWordStartsInOrderAndPrefersPrefixes() {
        val items = listOf(item("toString"), item("getString"), item("gst"), item("strip", priority = 1))
        assertEquals(listOf("gst", "getString"), filterSuggestions(items, "gS", 2, 0).map { it.label })
        assertEquals(listOf("toString", "getString", "strip"), filterSuggestions(items, "st", 2, 0).map { it.label })
    }

    @Test
    fun aServerRangeStretchesWithWhatWasTypedSinceItAsked() {
        val text = "foo.bar_baz"
        // Asked at 5 ("b"), with a range over "bar_baz"; typed two more since.
        assertEquals(4 to 11, acceptRange(item("x", range = 4 until 11), text, 7, 4, 5))
        assertEquals(4 to 7, acceptRange(item("x"), text, 7, 4, 5))
        assertEquals(4, wordStart(text, 7))
    }

    @Test
    fun aComposedWordGrowingCountsAsTyping() {
        val (state, list, service) = setUp("", listOf(item("print"), item("property")))
        // As Samsung's keyboard sends it: the composing word replaced by itself and the next letter.
        state.executeEdits(listOf(TextEdit(0 until 0, "p")), listOf(TextRange(1)))
        state.executeEdits(listOf(TextEdit(0 until 1, "pr")), listOf(TextRange(2)))
        state.executeEdits(listOf(TextEdit(0 until 2, "pri")), listOf(TextRange(3)))
        assertEquals(1, service.asked)
        assertEquals(listOf("print"), list.shown?.map { it.label })
        // And shrinking, as a backspace in a composition comes.
        state.executeEdits(listOf(TextEdit(0 until 3, "pr")), listOf(TextRange(2)))
        assertEquals(listOf("print", "property"), list.shown?.map { it.label })
    }

    @Test
    fun theTypedCaseAndNoImportComeFirst() {
        val items = listOf(
            CompletionItem("PrinceEdwardIsland", CompletionKind.Class, "PrinceEdwardIsland", detail = "(import workalendar)"),
            CompletionItem("principal", CompletionKind.Function, "principal", detail = " (import sympy)"),
            CompletionItem("print", CompletionKind.Function, "print", detail = "Overload[...]"),
        )
        assertEquals(listOf("print", "principal", "PrinceEdwardIsland"), filterSuggestions(items, "prin", 4, 0).map { it.label })
    }
}
