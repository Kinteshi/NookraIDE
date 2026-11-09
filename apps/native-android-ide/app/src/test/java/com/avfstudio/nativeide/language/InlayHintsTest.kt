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
}
