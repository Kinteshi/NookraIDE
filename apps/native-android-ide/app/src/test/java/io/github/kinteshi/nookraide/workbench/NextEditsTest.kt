package io.github.kinteshi.nookraide.workbench

import io.github.kinteshi.nookraide.editor.LspPosition
import io.github.kinteshi.nookraide.editor.LspRange
import io.github.kinteshi.nookraide.language.parseNextEdits
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEditsTest {
    private fun range(l1: Int, c1: Int, l2: Int, c2: Int) = LspRange(LspPosition(l1, c1), LspPosition(l2, c2))

    @Test
    fun aPreviewShowsTheWholeLinesAndWhatChanged() {
        val lines = listOf("def area(w, h):", "    return w * h", "")
        val preview = nextEditPreview(lines, range(1, 11, 1, 16), "w * h / 2")!!
        assertEquals(1, preview.firstLine)
        assertEquals("    return w * h", preview.oldText)
        assertEquals("    return w * h / 2", preview.newText)
        assertEquals(16 until 16, preview.oldChange)
        assertEquals(16 until 20, preview.newChange)
        assertNull(nextEditPreview(lines, range(1, 11, 1, 16), "w * h"))
        assertNull(nextEditPreview(lines, range(5, 0, 5, 0), "x"))
    }

    @Test
    fun multiLineEditsKeepTheirLineSpan() {
        val lines = listOf("a = 1", "b = 2", "c = 3")
        val preview = nextEditPreview(lines, range(0, 0, 1, 5), "a = 10\nb = 20")!!
        assertEquals(1, preview.lastLine)
        assertEquals("a = 10\nb = 20", preview.newText)
    }

    @Test
    fun tabGoesToAFarEditThenAcceptsIt() {
        assertEquals(NextEditTab.Jump, nextEditTab(cursorLine = 2, range(10, 0, 11, 0)))
        assertEquals(NextEditTab.Accept, nextEditTab(cursorLine = 9, range(10, 0, 11, 0)))
        assertEquals(NextEditTab.Accept, nextEditTab(cursorLine = 11, range(10, 0, 11, 0)))
    }

    @Test
    fun repliesParseIntoEdits() {
        val reply = JsonParser.parseString(
            """{"edits":[{"text":"x = 2\r\n","textDocument":{"uri":"file:///a.py","version":7},"range":{"start":{"line":3,"character":0},"end":{"line":4,"character":0}},
            "command":{"title":"Accept inline edit","command":"github.copilot.didAcceptCompletionItem","arguments":["id"]}}]}""",
        )
        val edit = parseNextEdits(reply).single()
        assertEquals("x = 2\n", edit.text)
        assertEquals(7L, edit.version)
        assertEquals(range(3, 0, 4, 0), edit.range)
        assertEquals("github.copilot.didAcceptCompletionItem", edit.command?.get("command")?.asString)
        assertEquals(emptyList<Any>(), parseNextEdits(JsonParser.parseString("null")))
    }
}
