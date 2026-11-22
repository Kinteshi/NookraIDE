package io.github.kinteshi.nookraide.workbench

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated notices (scripts/third-party-notices.py) cover what ships, each with a licence text, and no copyleft. */
class ThirdPartyNoticesTest {
    private val notices = JsonParser.parseString(File("src/main/assets/licenses/notices.json").readText()).asJsonObject
    private val components = notices.getAsJsonArray("components").map { it.asJsonObject }

    @Test
    fun everyComponentHasALicenceAndItsText() {
        val texts = notices.getAsJsonObject("texts")
        components.forEach { component ->
            val name = component.get("name").asString
            assertTrue("$name has no licence", component.get("license").asString.isNotBlank())
            assertTrue("$name has no text", texts.get(component.get("text").asString)?.asString.orEmpty().length > 100)
        }
    }

    @Test
    fun theEditorItsColoursAndTheDaemonAreListed() {
        val names = components.map { it.get("name").asString }.toSet()
        listOf("com.aardarch:aardink", "tm4e", "org.jruby.joni:joni", "org.connectbot:termlib", "tokio", "ring").forEach {
            assertTrue("$it is missing", it in names)
        }
        assertEquals(setOf("Android libraries", "Guest daemon (Rust crates)", "Bundled files"), components.map { it.get("group").asString }.toSet())
    }

    @Test
    fun nothingShipsUnderACopyleftLicence() {
        val copyleft = components.filter { component -> Regex("GPL|AGPL|MPL").containsMatchIn(component.get("license").asString) }
        assertEquals(emptyList<String>(), copyleft.map { it.get("name").asString })
        // sora-editor (LGPL) is gone.
        assertTrue(components.none { it.get("name").asString.contains("sora-editor", ignoreCase = true) })
    }
}
