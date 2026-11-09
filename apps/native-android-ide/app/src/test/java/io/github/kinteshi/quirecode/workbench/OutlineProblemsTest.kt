package io.github.kinteshi.quirecode.workbench

import io.github.kinteshi.quirecode.language.LanguageDiagnostic
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineProblemsTest {
    private fun range(start: Int, end: Int): JsonObject =
        JsonParser.parseString("""{"start":{"line":$start,"character":0},"end":{"line":$end,"character":0}}""").asJsonObject

    private fun diagnostic(uri: String, line: Int, severity: Int?) =
        LanguageDiagnostic(uri, "problem at $line", severity, range(line, line), "test", null)

    private val method = OutlineSymbol("method", 6, range(2, 4), emptyList())
    private val other = OutlineSymbol("other", 6, range(5, 7), emptyList())
    private val klass = OutlineSymbol("Klass", 5, range(1, 8), listOf(method, other))
    private val helper = OutlineSymbol("helper", 12, range(10, 12), emptyList())
    private val rows = flattenOutline(listOf(klass, helper))

    @Test
    fun problemsCountForTheSymbolsThatContainThem() {
        val problems = outlineProblems(rows, listOf(diagnostic("file:///a.py", 3, 2), diagnostic("file:///a.py", 3, 1), diagnostic("file:///a.py", 20, 1)))
        assertEquals(listOf("Klass", "method", "other", "helper"), rows.map { it.first.name })
        assertEquals(listOf(ProblemCount(2, 1), ProblemCount(2, 1), null, null), problems)
        // Only symbols with problems: the method and its class.
        assertEquals(listOf(0, 1), rowsWithProblems(rows, problems))
    }

    @Test
    fun aMissingSeverityCountsAsAnError() {
        assertEquals(ProblemCount(1, 1), outlineProblems(rows, listOf(diagnostic("file:///a.py", 11, null)))[3])
    }

    @Test
    fun problemsGroupByFileSortedBySeverityThenLine() {
        val groups = problemsByFile(
            listOf(
                diagnostic("file:///w/b.py", 9, 2),
                diagnostic("file:///w/a.py", 5, 2),
                diagnostic("file:///w/b.py", 7, 1),
                diagnostic("file:///w/b.py", 1, 2),
            ),
        )
        assertEquals(listOf("file:///w/a.py", "file:///w/b.py"), groups.map { it.first })
        assertEquals(listOf("problem at 7", "problem at 1", "problem at 9"), groups[1].second.map { it.message })
    }
}
