package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.editor.ProblemMark
import com.avfstudio.nativeide.editor.TagDecoration
import com.avfstudio.nativeide.editor.editorProblems
import com.avfstudio.nativeide.editor.problemLines
import com.avfstudio.nativeide.editor.problemMarks
import com.avfstudio.nativeide.editor.tagDecorations
import com.avfstudio.nativeide.language.LanguageDiagnostic
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorProblemsTest {
    private val lines = listOf(10, 0, 20)

    private fun diagnostic(
        startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int,
        severity: Int? = 1, tags: List<Int> = emptyList(), code: String = "\"F401\"",
    ) = LanguageDiagnostic(
        "file:///a.py",
        "message",
        severity,
        JsonParser.parseString(
            """{"start":{"line":$startLine,"character":$startCharacter},"end":{"line":$endLine,"character":$endCharacter}}""",
        ).asJsonObject,
        "ruff",
        JsonParser.parseString(code),
        tags,
    )

    @Test
    fun rangesAreClampedToTheText() {
        val problem = editorProblems(listOf(diagnostic(0, 4, 9, 99)), lines).single()
        assertEquals(listOf(0, 4, 2, 20), listOf(problem.startLine, problem.startColumn, problem.endLine, problem.endColumn))
        assertEquals("ruff F401", problem.detail)
    }

    @Test
    fun emptyRangesWidenToOneCharacter() {
        val inLine = editorProblems(listOf(diagnostic(0, 3, 0, 3)), lines).single()
        assertEquals(3 to 4, inLine.startColumn to inLine.endColumn)
        val atEnd = editorProblems(listOf(diagnostic(0, 10, 0, 10)), lines).single()
        assertEquals(9 to 10, atEnd.startColumn to atEnd.endColumn)
    }

    @Test
    fun missingSeverityIsAnErrorAndCodeObjectsKeepTheirValue() {
        val problem = editorProblems(listOf(diagnostic(0, 0, 0, 1, severity = null, code = """{"value":"E1","target":"x"}""")), lines).single()
        assertEquals(1, problem.severity)
        assertEquals("ruff E1", problem.detail)
    }

    @Test
    fun taggedProblemsFadeOrStrikeEachLineTheyCover() {
        val problems = editorProblems(
            listOf(diagnostic(0, 2, 2, 5, severity = 4, tags = listOf(1)), diagnostic(2, 0, 2, 3, tags = listOf(2))),
            lines,
        )
        assertEquals(
            listOf(
                TagDecoration(0, 2, 10, faded = true, struck = false),
                TagDecoration(1, 0, 0, faded = true, struck = false),
                TagDecoration(2, 0, 5, faded = true, struck = false),
                TagDecoration(2, 0, 3, faded = false, struck = true),
            ),
            tagDecorations(problems, lines, showUnused = true, showDeprecated = true),
        )
        assertEquals(1, tagDecorations(problems, lines, showUnused = false, showDeprecated = true).size)
    }

    @Test
    fun hintsGetNoUnderlineOrMark() {
        assertEquals(null, soraSeverity(4))
        val problems = editorProblems(
            listOf(diagnostic(2, 0, 2, 1, severity = 2), diagnostic(2, 4, 2, 5, severity = 1), diagnostic(0, 0, 0, 1, severity = 4)),
            lines,
        )
        // One mark per line, in the colour of its worst problem; the margin shows the same.
        assertEquals(listOf(ProblemMark(2f / 3f, 2, 4, 1)), problemMarks(problems, 3))
        assertEquals(mapOf(2 to 1), problemLines(problems))
    }
}
