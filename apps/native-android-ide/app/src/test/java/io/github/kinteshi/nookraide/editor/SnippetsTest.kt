package io.github.kinteshi.nookraide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetsTest {
    @Test
    fun parsesStopsDefaultsAndTheFinalStop() {
        val parsed = parseSnippet("for \${1:i} in \${2:range(10)}:\n\t\$0")
        assertEquals("for i in range(10):\n\t", parsed.text)
        assertEquals(listOf(1, 2, 0), parsed.stops.map { it.index })
        assertEquals(listOf(SnippetSpan(4, 5)), parsed.stops[0].spans)
        assertEquals(listOf(SnippetSpan(9, 18)), parsed.stops[1].spans)
        assertEquals(listOf(SnippetSpan(21, 21)), parsed.stops[2].spans)
    }

    @Test
    fun mirrorsNestingChoicesAndEscapes() {
        val parsed = parseSnippet("\${1:a} = \$1; \${2:x \${3:y}} \${4|one,t\\,wo|} \\\$5 \\}")
        assertEquals("a = a; x y one $5 }", parsed.text)
        assertEquals(listOf(SnippetSpan(0, 1), SnippetSpan(4, 5)), parsed.stops.first { it.index == 1 }.spans)
        assertEquals(listOf(SnippetSpan(7, 10)), parsed.stops.first { it.index == 2 }.spans)
        assertEquals(listOf(SnippetSpan(9, 10)), parsed.stops.first { it.index == 3 }.spans)
        assertEquals(listOf("one", "t,wo"), parsed.stops.first { it.index == 4 }.choices)
        // No $0: the final stop is at the end.
        assertEquals(SnippetSpan(parsed.text.length, parsed.text.length), parsed.stops.last().spans.single())
    }

    @Test
    fun variablesDefaultsAndTransforms() {
        val values = mapOf("TM_FILENAME" to "my_file.py", "EMPTY" to "")
        val parsed = parseSnippet("\$TM_FILENAME \${UNKNOWN} \${NOPE:\${1:fallback}} \${TM_FILENAME/(.*)\\..*/\${1:/upcase}/}", values::get)
        assertEquals("my_file.py UNKNOWN fallback MY_FILE", parsed.text)
        assertEquals(listOf(SnippetSpan(19, 27)), parsed.stops.first { it.index == 1 }.spans)
        assertEquals("MyFile", applySnippetTransform("my_file", "(.*)", "\${1:/pascalcase}", ""))
        assertEquals("a-b-c", applySnippetTransform("a b c", " ", "-", "g"))
    }

    @Test
    fun indentsLaterLinesAndConvertsTabs() {
        assertEquals("if x:\n        pass\n    end", indentSnippetBody("if x:\n\tpass\nend", "    ", "    "))
    }

    @Test
    fun sessionFollowsTypingInTheActiveStop() {
        // "for i in range(10):" inserted at 100.
        val session = SnippetSession(100, parseSnippet("for \${1:i} in \${2:range(10)}:\$0"))
        // Replace "i" with "item": delete then insert, as the editor does.
        session.onEdit(104, 1, 0)
        session.onEdit(104, 0, 4)
        assertEquals(listOf(104, 108), session.stops[0].spans.single().toList())
        assertEquals(listOf(112, 121), session.stops[1].spans.single().toList())
        assertEquals(listOf(122, 122), session.stops[2].spans.single().toList())
        // Typing at the active stop's end extends it; the next stop moves on.
        session.onEdit(108, 0, 1)
        assertEquals(listOf(104, 109), session.stops[0].spans.single().toList())
        assertEquals(listOf(113, 122), session.stops[1].spans.single().toList())
        // An edit before the snippet shifts it all.
        session.onEdit(0, 0, 3)
        assertEquals(listOf(107, 112), session.stops[0].spans.single().toList())
        assertTrue(session.move(forward = true))
        assertTrue(session.move(forward = true))
        assertTrue(session.isFinal)
        assertFalse(session.move(forward = true))
        assertEquals(107..126, session.extent)
    }
}
