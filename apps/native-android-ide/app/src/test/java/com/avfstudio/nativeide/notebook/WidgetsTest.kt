package com.avfstudio.nativeide.notebook

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
}
