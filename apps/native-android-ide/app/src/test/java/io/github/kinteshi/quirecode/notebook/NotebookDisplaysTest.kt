package io.github.kinteshi.quirecode.notebook

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookDisplaysTest {
    private fun display(loss: Int): JsonObject =
        JsonParser.parseString("""{"output_type":"display_data","data":{"text/plain":"loss $loss"},"metadata":{}}""").asJsonObject

    private fun text(output: JsonObject) = output.getAsJsonObject("data").get("text/plain").asString

    @Test
    fun updatesReplaceTheOutputShownWithTheirIdInAnyCell() {
        val first = NotebookCell("a", CellType.Code, "handle = display(fig, display_id=True)")
        val second = NotebookCell("b", CellType.Code, "handle.update(fig)")
        val displays = NotebookDisplays()
        displays.append(first, display(1), "plot")
        displays.append(first, display(9), null)
        assertTrue(displays.update(listOf(first, second), "plot", display(2)))
        assertTrue(displays.update(listOf(first, second), "plot", display(3)))
        assertEquals(listOf("loss 3", "loss 9"), first.outputs.map(::text))
        assertTrue(second.outputs.isEmpty())
        assertFalse(displays.update(listOf(first), "unknown", display(4)))
    }

    @Test
    fun anUpdateAfterTheOutputWasClearedDoesNothing() {
        val cell = NotebookCell("a", CellType.Code, "")
        val displays = NotebookDisplays()
        displays.append(cell, display(1), "plot")
        displays.clear(cell, wait = false)
        assertFalse(displays.update(listOf(cell), "plot", display(2)))
        assertTrue(cell.outputs.isEmpty())
    }

    @Test
    fun clearOutputWithWaitEmptiesTheCellAtItsNextOutput() {
        val cell = NotebookCell("a", CellType.Code, "")
        val displays = NotebookDisplays()
        displays.append(cell, display(1), null)
        displays.clear(cell, wait = true)
        assertEquals(1, cell.outputs.size)
        displays.append(cell, display(2), null)
        assertEquals(listOf("loss 2"), cell.outputs.map(::text))
        displays.append(cell, display(3), null)
        assertEquals(2, cell.outputs.size)
    }
}
