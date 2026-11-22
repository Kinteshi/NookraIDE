package io.github.kinteshi.nookraide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFindTest {
    private fun pattern(query: String, regex: Boolean = false, case: Boolean = false, word: Boolean = false) =
        SearchPattern(query, regex, case, word)

    @Test
    fun findsLiteralCaseAndWholeWordMatches() {
        val text = "Foo foo food\nfoo"
        assertEquals(4, findMatches(text, pattern("foo")).matches.size)
        assertEquals(3, findMatches(text, pattern("foo", case = true)).matches.size)
        assertEquals(listOf(FindMatch(0, 3), FindMatch(4, 7), FindMatch(13, 16)), findMatches(text, pattern("foo", word = true)).matches)
        // A literal "." is not a regex.
        assertEquals(0, findMatches(text, pattern("f.o")).matches.size)
    }

    @Test
    fun regexWorksPerLineAndAcrossLines() {
        val text = "a = 1\nb = 2\n"
        assertEquals(listOf(FindMatch(0, 1), FindMatch(6, 7)), findMatches(text, pattern("^\\w", regex = true)).matches)
        assertEquals(listOf(FindMatch(4, 7)), findMatches(text, pattern("1\\nb", regex = true)).matches)
        // Empty matches are skipped; a bad pattern says so.
        assertTrue(findMatches(text, pattern("^", regex = true)).matches.isEmpty())
        assertNotNull(findMatches(text, pattern("(", regex = true)).error)
    }

    @Test
    fun aScopeAndTheLimitBoundTheMatches() {
        val text = "x x x x"
        assertEquals(listOf(FindMatch(2, 3), FindMatch(4, 5)), findMatches(text, pattern("x"), scope = 2..4).matches)
        val limited = findMatches(text, pattern("x"), limit = 3)
        assertEquals(3, limited.matches.size)
        assertTrue(limited.limited)
    }

    @Test
    fun nextAndPreviousWrapAround() {
        val matches = listOf(FindMatch(2, 3), FindMatch(10, 11))
        assertEquals(1, matchAtOrAfter(matches, 5))
        assertEquals(0, matchAtOrAfter(matches, 11))
        assertEquals(0, matchBefore(matches, 5))
        assertEquals(1, matchBefore(matches, 2))
        assertEquals(-1, matchAtOrAfter(emptyList(), 0))
    }

    @Test
    fun replacementsKeepCaseAndExpandGroups() {
        assertEquals("BAR", preserveCase("FOO", "bar"))
        assertEquals("bar", preserveCase("foo", "Bar"))
        assertEquals("Bar", preserveCase("Foo", "bar"))
        assertEquals("bar", preserveCase("fOO", "bar"))
        val text = "width = 10"
        val regex = pattern("(\\w+) = (\\d+)", regex = true)
        val match = findMatches(text, regex).matches.single()
        assertEquals("10: width\tdone", findReplacement(text, match, regex, "$2: $1\\tdone", keepCase = false))
        assertEquals("a\\nb", findReplacement("x", FindMatch(0, 1), pattern("x"), "a\\nb", keepCase = false))
    }

    @Test
    fun aSeedIsEscapedForRegexSearch() {
        assertEquals("a\\.b\\(\\)", escapeRegexLiteral("a.b()"))
        val text = "call a.b() here"
        assertEquals(1, findMatches(text, pattern(escapeRegexLiteral("a.b()"), regex = true)).matches.size)
    }
}
