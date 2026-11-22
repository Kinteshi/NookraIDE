package io.github.kinteshi.nookraide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchReplaceTest {
    @Test
    fun replacesLiteralsWithTheSearchOptions() {
        val text = "Total = total + 1\nsubtotal = TOTAL\r\nend"
        assertEquals("sum = sum + 1\nsubtotal = sum\r\nend" to 3, replaceMatches(text, SearchPattern("total", false, false, true), "sum"))
        assertEquals("Total = sum + 1\nsubsum = TOTAL\r\nend" to 2, replaceMatches(text, SearchPattern("total", false, true, false), "sum"))
        // A literal's regex characters and a replacement's dollar stay as typed.
        assertEquals("cost: \$1 (x)" to 1, replaceMatches("cost: a.b (x)", SearchPattern("a.b", false, true, false), "\$1"))
    }

    @Test
    fun regexReplacementsUseGroups() {
        val pattern = SearchPattern("""(\w+)\((\d+)\)""", regex = true, caseSensitive = true, wholeWord = false)
        assertEquals("f[1] g[22]" to 2, replaceMatches("f(1) g(22)", pattern, "\$1[\$2]"))
        assertEquals("<f(1)> g(22)" to 1, replaceMatches("f(1) g(22)", pattern, "<\$&>", only = setOf(MatchPosition(1, 0))))
        assertEquals("f(1) x" to 1, replaceMatches("f(1) g(22)", pattern, "x", only = setOf(MatchPosition(1, 5))))
    }

    @Test
    fun matchesNeverCrossALine() {
        val pattern = SearchPattern("""a\s+b""", regex = true, caseSensitive = true, wholeWord = false)
        assertEquals("a\nb x" to 1, replaceMatches("a\nb a  b", pattern, "x"))
    }

    @Test
    fun previewsWhatAMatchBecomes() {
        assertEquals("sum", replacementPreview("x = total", 4, SearchPattern("total", false, false, false), "sum"))
        assertNull(replacementPreview("x = total", 2, SearchPattern("total", false, false, false), "sum"))
    }
}
