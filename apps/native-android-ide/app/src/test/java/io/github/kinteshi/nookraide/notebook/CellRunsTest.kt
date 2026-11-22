package io.github.kinteshi.nookraide.notebook

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellRunsTest {
    @Test
    fun jupyterLabTimingShowsAsTheLastRun() {
        val metadata = JsonParser.parseString(
            """{"execution":{"iopub.execute_input":"2026-10-29T10:00:00.250000Z","shell.execute_reply":"2026-10-29T10:00:01.500000Z"}}""",
        ).asJsonObject
        val error = JsonObject().apply { addProperty("output_type", "error") }
        assertEquals(CellRun(failed = false, millis = 1250, finishedAt = 1_793_268_001_500), savedRun(metadata, emptyList()))
        assertTrue(savedRun(metadata, listOf(error))!!.failed)
        assertNull(savedRun(JsonObject(), emptyList()))
    }

    @Test
    fun recordedTimingRoundTripsAndClears() {
        val metadata = JsonObject()
        recordTiming(metadata, 1_000, 3_500)
        assertEquals(2_500L, savedRun(metadata, emptyList())!!.millis)
        assertTrue(clearTiming(metadata))
        assertFalse(metadata.has("execution"))
    }

    @Test
    fun hiddenOutputsUseJupyterLabsKeys() {
        val cell = NotebookCell("c", CellType.Code, "", metadata = JsonParser.parseString("""{"jupyter":{"source_hidden":true}}""").asJsonObject)
        assertFalse(cell.outputsHidden)
        cell.hideOutputs(true)
        assertEquals("""{"jupyter":{"source_hidden":true,"outputs_hidden":true},"collapsed":true}""", cell.metadata.toString())
        cell.hideOutputs(false)
        assertEquals("""{"jupyter":{"source_hidden":true}}""", cell.metadata.toString())
        assertTrue(savedOutputsHidden(JsonParser.parseString("""{"collapsed":true}""").asJsonObject))
    }

    @Test
    fun durationsReadShortest() {
        assertEquals("0.4s", formatRunDuration(400))
        assertEquals("59.9s", formatRunDuration(59_940))
        assertEquals("2m 05s", formatRunDuration(125_000))
        assertEquals("1h 02m", formatRunDuration(3_720_000))
    }
}
