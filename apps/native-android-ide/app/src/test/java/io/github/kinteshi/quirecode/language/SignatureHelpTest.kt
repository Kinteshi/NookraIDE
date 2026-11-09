package io.github.kinteshi.quirecode.language

import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.LspRange
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureHelpTest {
    @Test
    fun theActiveParameterIsFoundByTextAfterTheOnesBeforeIt() {
        val reply = JsonParser.parseString(
            """{"signatures":[{"label":"pad(a, a2, *, width=10)","documentation":{"kind":"markdown","value":"Pads.\n```py\nx\n```"},
            "parameters":[{"label":"a"},{"label":"a2","documentation":"The second one"},{"label":"width=10"}]}],"activeSignature":0,"activeParameter":1}""",
        )
        val hint = parseSignatureHelp(reply)!!
        assertEquals("a2", hint.label.substring(hint.activeStart, hint.activeEnd))
        assertEquals("The second one", hint.parameterDocumentation)
        assertEquals("Pads.\nx", hint.documentation)
        assertEquals(1, hint.count)
    }

    @Test
    fun offsetLabelsAndPerSignatureActiveParametersWin() {
        val reply = JsonParser.parseString(
            """{"signatures":[{"label":"f()","parameters":[]},{"label":"f(x: int, y: str)","activeParameter":1,
            "parameters":[{"label":[2,8]},{"label":[10,16]}]}],"activeSignature":1,"activeParameter":0}""",
        )
        val hint = parseSignatureHelp(reply)!!
        assertEquals("y: str", hint.label.substring(hint.activeStart, hint.activeEnd))
        assertEquals(1 to 2, hint.index to hint.count)
        assertNull(parseSignatureHelp(JsonParser.parseString("""{"signatures":[]}""")))
        assertNull(parseSignatureHelp(com.google.gson.JsonNull.INSTANCE))
    }

    @Test
    fun retriggersCarryTheHintsShown() {
        val shown = parseSignatureHelp(JsonParser.parseString("""{"signatures":[{"label":"g(a)","parameters":[{"label":"a"}]}]}"""))!!
        val typed = signatureContext("(", null)
        assertEquals(2, typed.get("triggerKind").asInt)
        assertFalse(typed.get("isRetrigger").asBoolean)
        val retrigger = signatureContext(null, shown)
        assertEquals(3, retrigger.get("triggerKind").asInt)
        assertTrue(retrigger.get("isRetrigger").asBoolean)
        assertEquals("g(a)", retrigger.getAsJsonObject("activeSignatureHelp").getAsJsonArray("signatures")[0].asJsonObject.get("label").asString)
        assertEquals(1, signatureContext(null, null, invoked = true).get("triggerKind").asInt)
    }

    @Test
    fun highlightsKeepWhichUsesWrite() {
        val reply = JsonParser.parseString(
            """[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":5}},"kind":3},
            {"range":{"start":{"line":2,"character":6},"end":{"line":2,"character":11}},"kind":2},
            {"range":{"start":{"line":3,"character":1},"end":{"line":3,"character":6}}}]""",
        )
        assertEquals(
            listOf(
                SymbolHighlight(LspRange(LspPosition(0, 0), LspPosition(0, 5)), write = true),
                SymbolHighlight(LspRange(LspPosition(2, 6), LspPosition(2, 11)), write = false),
                SymbolHighlight(LspRange(LspPosition(3, 1), LspPosition(3, 6)), write = false),
            ),
            parseDocumentHighlights(reply),
        )
        assertEquals(emptyList<SymbolHighlight>(), parseDocumentHighlights(com.google.gson.JsonNull.INSTANCE))
    }
}
