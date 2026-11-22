package io.github.kinteshi.nookraide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoverCardTest {
    @Test
    fun theDocsTitleNamesTheWordAtThePlace() {
        val line = "df = pd.DataFrame(data)"
        assertEquals("DataFrame", wordAt(line, 8))
        assertEquals("DataFrame", wordAt(line, 12))
        // Just after a word, as a caret at its end is.
        assertEquals("DataFrame", wordAt(line, 17))
        assertEquals("pd", wordAt(line, 5))
        assertNull(wordAt(line, 4))
        assertNull(wordAt("", 0))
    }

    @Test
    fun extractsMemberChainsForDebugHoverAndSkipsLiterals() {
        val code = "val = self.user.count\nptr->len = 42\nns::item + 3.14\n"
        assertEquals("self", debugHoverExpression(code, 0, 8)?.first)
        assertEquals("self.user", debugHoverExpression(code, 0, 13)?.first)
        assertEquals("self.user.count", debugHoverExpression(code, 0, 21)?.first)
        assertEquals("ptr->len", debugHoverExpression(code, 1, 6)?.first)
        assertNull(debugHoverExpression(code, 1, 12))
        assertEquals("ns::item", debugHoverExpression(code, 2, 5)?.first)
        assertNull(debugHoverExpression(code, 2, 13))
    }

    @Test
    fun formatsDebugHoverMarkdown() {
        assertEquals(
            "**Debug** `self.count` `int`\n```text\n42\n```",
            formatDebugHoverMarkdown("self.count", "42", "int"),
        )
        assertEquals(
            "**Debug** `raw`\n````text\n```hi```\n````",
            formatDebugHoverMarkdown("raw", "```hi```", null),
        )
    }
}
