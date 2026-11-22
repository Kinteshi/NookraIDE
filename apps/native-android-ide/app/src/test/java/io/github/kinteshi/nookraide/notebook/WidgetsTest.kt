package io.github.kinteshi.nookraide.notebook

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetsTest {
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test
    fun widgetsOpenUpdateAndClose() {
        val widgets = NotebookWidgets()
        widgets.apply(parseWidgetMessage("comm_open", json("""{"comm_id":"s1","target_name":"jupyter.widget","data":{"state":{"_model_name":"IntSliderModel","value":3,"max":10}}}"""))!!)
        // Other comms (Plotly's, the widget control channel) aren't widgets.
        widgets.apply(parseWidgetMessage("comm_open", json("""{"comm_id":"c1","target_name":"jupyter.widget.control","data":{}}"""))!!)
        assertEquals(setOf("s1"), widgets.models.keys)
        widgets.apply(parseWidgetMessage("comm_msg", json("""{"comm_id":"s1","data":{"method":"update","state":{"value":7}}}"""))!!)
        val model = widgets.model("IPY_MODEL_s1")!!
        assertEquals("IntSliderModel", model.name)
        assertEquals(7, model.state.get("value").asInt)
        assertEquals(10, model.state.get("max").asInt)
        // The kernel's echo of this client's own change is ignored.
        assertNull(parseWidgetMessage("comm_msg", json("""{"comm_id":"s1","data":{"method":"echo_update","state":{"value":1}}}""")))
        widgets.apply(parseWidgetMessage("comm_close", json("""{"comm_id":"s1"}"""))!!)
        assertEquals(emptySet<String>(), widgets.models.keys)
    }

    @Test
    fun outputWidgetsCollectAndClearTheirOutputs() {
        val widgets = NotebookWidgets()
        widgets.apply(WidgetMessage.Open("o1", NotebookWidgets.WIDGET_TARGET, json("""{"_model_name":"OutputModel","outputs":[]}""")))
        fun stream(text: String) = json("""{"output_type":"stream","name":"stdout","text":"$text"}""")
        widgets.apply(WidgetMessage.Output("o1", stream("a")))
        widgets.apply(WidgetMessage.Output("o1", stream("b\n")))
        val model = widgets.models.getValue("o1")
        assertEquals(1, model.state.getAsJsonArray("outputs").size())
        assertEquals("ab\n", model.state.getAsJsonArray("outputs")[0].asJsonObject.get("text").asString)
        // interact(): clear_output(wait=True), then the new result replaces the old one.
        widgets.apply(WidgetMessage.ClearOutput("o1", wait = true))
        assertEquals(1, model.state.getAsJsonArray("outputs").size())
        widgets.apply(WidgetMessage.Output("o1", stream("c")))
        assertEquals("c", model.state.getAsJsonArray("outputs")[0].asJsonObject.get("text").asString)
        widgets.apply(WidgetMessage.ClearOutput("o1", wait = false))
        assertEquals(0, model.state.getAsJsonArray("outputs").size())
        widgets.apply(WidgetMessage.Reset)
        assertEquals(0, widgets.models.size)
    }

    @Test
    fun aChangeShowsAtOnceAndSendsOnlyTheChangedKeys() {
        val widgets = NotebookWidgets()
        widgets.apply(WidgetMessage.Open("d1", NotebookWidgets.WIDGET_TARGET, json("""{"_model_name":"DropdownModel","index":0,"_options_labels":["a","b"]}""")))
        val model = widgets.models.getValue("d1")
        val sent = widgets.change(model, json("""{"index":1}"""))
        assertEquals(1, model.state.get("index").asInt)
        assertEquals("""{"method":"update","state":{"index":1},"buffer_paths":[]}""", sent.toString())
        assertEquals("""{"method":"custom","content":{"event":"click"}}""", NotebookWidgets.event("click").toString())
        assertEquals(listOf("IPY_MODEL_a", "IPY_MODEL_b"), widgetChildren(json("""{"children":["IPY_MODEL_a","IPY_MODEL_b"]}""")))
    }

    @Test
    fun parsesBinaryBuffersCustomCommsAndLinks() {
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val widgets = NotebookWidgets()

        widgets.apply(parseWidgetMessage("comm_open", json("""{
            "comm_id":"img1",
            "target_name":"jupyter.widget",
            "data":{"state":{"_model_name":"ImageModel","format":"png"},"buffer_paths":[["value"]]},
            "buffers":["$b64"]
        }"""))!!)
        val img = widgets.models.getValue("img1")
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()), img.bufferBytes("value")!!.toList())

        val canvasCmd = java.util.Base64.getEncoder().encodeToString("""["fill_rect",0,0,10,10]""".toByteArray())
        widgets.apply(parseWidgetMessage("comm_open", json("""{
            "comm_id":"cv1",
            "target_name":"jupyter.widget",
            "data":{"state":{"_model_name":"CanvasModel","_model_module":"ipycanvas"}}
        }"""))!!)
        widgets.apply(parseWidgetMessage("comm_msg", json("""{
            "comm_id":"cv1",
            "data":{"method":"custom","content":{"type":"commands"}},
            "buffers":["$canvasCmd"]
        }"""))!!)
        val cv = widgets.models.getValue("cv1")
        assertEquals(1, cv.customEvents.size)
        assertEquals("""["fill_rect",0,0,10,10]""", cv.customEvents[0].buffers[0].toString(Charsets.UTF_8))

        // PlayModel -> LinkModel -> IntSliderModel client-side propagation
        widgets.apply(WidgetMessage.Open("play1", NotebookWidgets.WIDGET_TARGET, json("""{"_model_name":"PlayModel","value":0,"max":10}""")))
        widgets.apply(WidgetMessage.Open("slider1", NotebookWidgets.WIDGET_TARGET, json("""{"_model_name":"IntSliderModel","value":0,"max":10}""")))
        widgets.apply(WidgetMessage.Open("link1", NotebookWidgets.WIDGET_TARGET, json("""{
            "_model_name":"LinkModel",
            "source":["IPY_MODEL_play1","value"],
            "target":["IPY_MODEL_slider1","value"]
        }""")))
        val dispatches = widgets.changeAndPropagate(widgets.models.getValue("play1"), json("""{"value":5}"""))
        assertEquals(5, widgets.models.getValue("slider1").state.get("value").asInt)
        assertEquals(listOf("play1", "slider1"), dispatches.map { it.first })
    }

    @Test
    fun loadsAndWritesSavedMetadataWidgetsAcrossKernelLifecycle() {
        val widgets = NotebookWidgets()
        val pngB64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val metadata = json("""{
            "widgets":{
                "application/vnd.jupyter.widget-state+json":{
                    "version_major":2,
                    "version_minor":0,
                    "state":{
                        "saved1":{
                            "model_name":"ImageModel",
                            "model_module":"@jupyter-widgets/controls",
                            "model_module_version":"2.0.0",
                            "state":{"format":"png"},
                            "buffers":[{"data":"$pngB64","encoding":"base64","path":["value"]}]
                        }
                    }
                }
            }
        }""")
        widgets.loadSavedState(metadata)
        val saved = widgets.models.getValue("saved1")
        assertEquals(true, saved.savedOnly)
        assertEquals("ImageModel", saved.name)
        assertEquals(listOf(9.toByte(), 8.toByte(), 7.toByte()), saved.bufferBytes("value")!!.toList())

        // Opening a live child widget + clearing live models preserves saved snapshot.
        widgets.apply(WidgetMessage.Open("liveBox", NotebookWidgets.WIDGET_TARGET, json("""{"_model_name":"VBoxModel","children":["IPY_MODEL_liveColor"]}""")))
        widgets.apply(WidgetMessage.Open("liveColor", NotebookWidgets.WIDGET_TARGET, json("""{"_model_name":"ColorPickerModel","value":"#ff00aa"}""")))
        val doc = NotebookDocument.parse(
            "/tmp/w.ipynb",
            """{"nbformat":4,"nbformat_minor":5,"metadata":{},"cells":[{"cell_type":"code","id":"c1","metadata":{},"source":[],"execution_count":1,"outputs":[{"output_type":"display_data","data":{"application/vnd.jupyter.widget-view+json":{"model_id":"liveBox","version_major":2,"version_minor":0}},"metadata":{}}]}]}""",
        )
        widgets.writeSavedState(doc)
        val savedBack = doc.metadata.getAsJsonObject("widgets").getAsJsonObject("application/vnd.jupyter.widget-state+json").getAsJsonObject("state")
        assertEquals(setOf("liveBox", "liveColor"), savedBack.keySet())

        widgets.clearLive()
        assertEquals(setOf("saved1"), widgets.models.keys)
    }
}
