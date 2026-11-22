package io.github.kinteshi.nookraide.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcpConfigOptionsTest {
    // Trimmed from Copilot CLI 1.0.104's session/new reply on the tablet.
    private val reply = JsonParser.parseString(
        """
        {"sessionId":"s","configOptions":[
          {"type":"select","id":"mode","name":"Mode","category":"mode","currentValue":"#agent",
           "options":[{"value":"#agent","name":"Agent"},{"value":"#plan","name":"Plan","description":"Plans first"}]},
          {"type":"select","id":"model","name":"Model","category":"model","currentValue":"auto",
           "options":[{"value":"auto","name":"Auto","description":"Let Copilot pick","_meta":null},
                      {"value":"auto","name":"Auto","description":"Auto","_meta":{"copilotUsage":"1x"}}]},
          {"type":"boolean","id":"future","name":"Unknown kind","currentValue":true}
        ]}
        """,
    ).asJsonObject

    @Test
    fun selectOptionsAreReadAndDuplicateChoicesFolded() {
        val options = parseConfigOptions(reply)!!
        assertEquals(listOf("mode", "model"), options.map { it.id })
        assertEquals("#agent", options[0].current)
        assertEquals(listOf("Agent", "Plan"), options[0].choices.map { it.name })
        assertEquals(1, options[1].choices.size)
        assertEquals("Let Copilot pick", options[1].choices[0].description)
    }

    @Test
    fun repliesWithoutConfigOptionsAreNull() {
        assertNull(parseConfigOptions(JsonParser.parseString("""{"sessionId":"s"}""").asJsonObject))
    }
}
