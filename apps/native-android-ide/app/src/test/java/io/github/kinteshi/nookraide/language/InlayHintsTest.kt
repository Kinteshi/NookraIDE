package io.github.kinteshi.nookraide.language

import io.github.kinteshi.nookraide.editor.LspPosition
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class InlayHintsTest {
    @Test
    fun readsStringAndPartLabels() {
        val hints = parseInlayHints(
            JsonParser.parseString(
                """[{"position":{"line":2,"character":5},"label":": int","kind":1,"tooltip":"Return type"},
                   {"position":{"line":3,"character":10},"label":[{"value":"values"},{"value":"="}],"kind":2,"tooltip":{"kind":"markdown","value":"Values list"}},
                   {"position":{"line":4,"character":0},"label":"  "},
                   {"position":{"line":5,"character":1},"label":": str","kind":1,"tooltip":"   "}]""",
            ),
        )
        assertEquals(
            listOf(
                InlayHint(LspPosition(2, 5), ": int", 1, "Return type"),
                InlayHint(LspPosition(3, 10), "values=", 2, "Values list"),
                InlayHint(LspPosition(5, 1), ": str", 1, null),
            ),
            hints,
        )
    }
}
