package com.avfstudio.nativeide.language

import com.avfstudio.nativeide.editor.LspPosition
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class InlayHintsTest {
    @Test
    fun readsStringAndPartLabels() {
        val hints = parseInlayHints(
            JsonParser.parseString(
                """[{"position":{"line":2,"character":5},"label":": int","kind":1},
                   {"position":{"line":3,"character":10},"label":[{"value":"values"},{"value":"="}],"kind":2},
                   {"position":{"line":4,"character":0},"label":"  "}]""",
            ),
        )
        assertEquals(
            listOf(InlayHint(LspPosition(2, 5), ": int", 1), InlayHint(LspPosition(3, 10), "values=", 2)),
            hints,
        )
    }

    @Test
    fun typeHintsFollowTheirNameAndParameterNamesLeadTheirArgument() {
        val line = "total = scale(xs, factor=2, offset=[1, 2])"
        val hints = listOf(
            InlayHint(LspPosition(0, 5), ": list[int]", 1),
            InlayHint(LspPosition(0, 14), "values=", 2),
        )
        assertEquals(listOf("total: list[int]", "values=xs"), endOfLineHints(line, hints))
    }

    @Test
    fun argumentsStopAtTheirBracketAndLongOnesAreShortened() {
        val line = "f(g(a, b), \"x,y\", averyveryverylongname)"
        val hints = listOf(
            InlayHint(LspPosition(0, 2), "first=", 2),
            InlayHint(LspPosition(0, 11), "second=", 2),
            InlayHint(LspPosition(0, 18), "third:", 2),
        )
        assertEquals(listOf("first=g(a, b)", "second=\"x,y\"", "third: averyveryverylo…"), endOfLineHints(line, hints))
    }

    @Test
    fun returnTypesAndUnknownHintsShowAsTheyAre() {
        val hints = listOf(InlayHint(LspPosition(0, 11), "-> None", 1), InlayHint(LspPosition(0, 0), "label", null))
        assertEquals(listOf("label", "-> None"), endOfLineHints("def main():", hints))
    }
}
