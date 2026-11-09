package io.github.kinteshi.quirecode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReferencesTest {
    private val file = ChatReference("/home/droid/Projects/app/main.py")
    private val excerpt = ChatReference("/home/droid/Projects/app/main.py", 10, 12, "def f():\n    return 1\n")

    @Test
    fun labelsAndUrisCarryTheLineRange() {
        assertEquals("main.py", file.label)
        assertEquals("main.py:10-12", excerpt.label)
        assertEquals("file:///home/droid/Projects/app/main.py#L10-L12", excerpt.uri)
        assertEquals("nb.ipynb · cell 3", ChatReference("/w/nb.ipynb", text = "x", detail = "cell 3").label)
    }

    @Test
    fun filesBecomeResourceLinksAndExcerptsEmbeddedResources() {
        val blocks = acpPromptBlocks("Explain this", listOf(file, excerpt), embeddedContext = true)
        assertEquals("Explain this", blocks[0].asJsonObject.get("text").asString)
        assertEquals("resource_link", blocks[1].asJsonObject.get("type").asString)
        assertEquals("file:///home/droid/Projects/app/main.py", blocks[1].asJsonObject.get("uri").asString)
        val resource = blocks[2].asJsonObject
        assertEquals("resource", resource.get("type").asString)
        assertEquals("text/x-python", resource.getAsJsonObject("resource").get("mimeType").asString)
        assertEquals(excerpt.text, resource.getAsJsonObject("resource").get("text").asString)
    }

    @Test
    fun excerptsFallBackToFencedTextWithoutEmbeddedContext() {
        val blocks = acpPromptBlocks("Fix it", listOf(excerpt), embeddedContext = false)
        assertEquals(1, blocks.size())
        val text = blocks[0].asJsonObject.get("text").asString
        assertTrue(text.startsWith("Fix it\n\n`/home/droid/Projects/app/main.py` lines 10-12:\n```py\ndef f():"))
        assertTrue(text.endsWith("return 1\n```"))
    }
}
