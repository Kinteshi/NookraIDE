package io.github.kinteshi.nookraide.workbench

import androidx.compose.ui.graphics.Color
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NotebookRichWidgetsTest {
    @Test
    fun parsesAndFormatsCssColors() {
        assertEquals(Color(0xFFFF0000), parseCssColor("red"))
        assertEquals(Color(0xFF112233), parseCssColor("#123")?.let { parseCssColor("#112233") })
        assertEquals(Color(0xFF123456), parseCssColor("#123456"))
        assertEquals(Color(0x80102030), parseCssColor("#10203080"))
        assertEquals(Color(red = 12, green = 34, blue = 56), parseCssColor("rgb(12, 34, 56)"))
        assertNull(parseCssColor("not-a-color"))
        assertEquals("#1a2b3c", formatCssHex(Color(0xFF1A2B3C)))
    }

    @Test
    fun roundTripsIpyWidgetDatesWithZeroIndexedMonths() {
        val raw = JsonParser.parseString("""{"year":2026,"month":10,"date":18}""")
        val parsed = parseIpyWidgetDate(raw)
        assertEquals(LocalDate.of(2026, 11, 18), parsed)
        val encoded = toIpyWidgetDateJson(LocalDate.of(2026, 1, 5))
        assertEquals(2026, encoded.get("year").asInt)
        assertEquals(0, encoded.get("month").asInt)
        assertEquals(5, encoded.get("date").asInt)
        assertEquals(LocalDate.of(2026, 11, 18), parseIpyWidgetDate(JsonPrimitive("2026-11-18")))
        assertNull(parseIpyWidgetDate(null))
    }

    @Test
    fun resolvesMediaMimeTypesDimensionsAndByteRanges() {
        assertEquals("audio/mpeg", mediaWidgetMime("Audio", "mp3"))
        assertEquals("video/webm", mediaWidgetMime("Video", "webm"))
        assertEquals("image/svg+xml", mediaWidgetMime("Image", "svg+xml"))
        assertEquals(320f, parseCssPxOrDp("320px"))
        assertNull(parseCssPxOrDp("100%"))

        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val full = computeByteRangeSlice(payload.size, null)
        assertEquals(200, full.statusCode)
        assertArrayEquals(payload, payload.copyOfRange(full.offset, full.offset + full.length))

        val partial = computeByteRangeSlice(payload.size, "bytes=1-3")
        assertEquals(206, partial.statusCode)
        assertEquals("bytes 1-3/5", partial.headers["Content-Range"])
        assertArrayEquals(byteArrayOf(20, 30, 40), payload.copyOfRange(partial.offset, partial.offset + partial.length))
    }

    @Test
    fun generatesThirdPartyAndAnywidgetHtmlHostsSafely() {
        val bundle = JsonParser.parseString("""{
            "version_major":2,
            "version_minor":0,
            "state":{"m1":{"model_name":"CanvasModel","model_module":"ipycanvas","model_module_version":"0.13.0","state":{"width":200,"height":100}}}
        }""").asJsonObject
        val html = thirdPartyWidgetPage(
            rootId = "m1",
            modelName = "CanvasModel",
            stateBundle = bundle,
            initialCustom = listOf(
                Triple("m1", JsonParser.parseString("""{"type":"commands"}""").asJsonObject, listOf("[\"clear\"]".toByteArray())),
            ),
            dark = true,
        )
        assertTrue(html.contains("@jupyter-widgets/html-manager@1/dist/embed-amd.js"))
        assertTrue(html.contains("CanvasModel"))
        assertTrue(html.contains("__quirePushComm"))
        assertTrue(html.contains("QuireWidgets.sendCommMsg"))
    }
}
