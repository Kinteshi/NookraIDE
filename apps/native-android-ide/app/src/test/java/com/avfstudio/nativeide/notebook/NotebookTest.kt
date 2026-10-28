package com.avfstudio.nativeide.notebook

import com.avfstudio.nativeide.testing.FakeGuest
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class NotebookTest {
    @Test
    fun notebooksRoundTripSourcesOutputsAndMetadata() {
        val original = """
            {"cells":[
              {"cell_type":"markdown","metadata":{},"source":["# Title\n","text"]},
              {"cell_type":"code","execution_count":3,"metadata":{"tags":["x"]},"outputs":[{"output_type":"stream","name":"stdout","text":["hi\n"]}],"source":"print('hi')"}
            ],"metadata":{"kernelspec":{"name":"python3","display_name":"Python 3","language":"python"},"custom":1},"nbformat":4,"nbformat_minor":2}
        """.trimIndent()
        val notebook = NotebookDocument.parse("/w/a.ipynb", original)
        assertEquals("python3", notebook.kernelName)
        assertEquals("# Title\ntext", notebook.cells[0].source)
        assertEquals(CellType.Code, notebook.cells[1].type)
        assertEquals(3, notebook.cells[1].executionCount)

        val saved = JsonParser.parseString(notebook.toJson()).asJsonObject
        assertEquals(5, saved.get("nbformat_minor").asInt)
        assertEquals(1, saved.getAsJsonObject("metadata").get("custom").asInt)
        val code = saved.getAsJsonArray("cells")[1].asJsonObject
        assertEquals(listOf("print('hi')"), code.getAsJsonArray("source").map { it.asString })
        assertEquals("x", code.getAsJsonObject("metadata").getAsJsonArray("tags")[0].asString)
        assertEquals("hi\n", NotebookDocument.multiline(code.getAsJsonArray("outputs")[0].asJsonObject.get("text")))
        assertTrue(saved.getAsJsonArray("cells").all { it.asJsonObject.has("id") })
        val markdown = saved.getAsJsonArray("cells")[0].asJsonObject
        assertEquals(listOf("# Title\n", "text"), markdown.getAsJsonArray("source").map { it.asString })
        assertTrue(!markdown.has("outputs"))
    }

    @Test
    fun kernelClientDrivesTheGuestBridge() {
        FakeGuest { body ->
            val message = JsonParser.parseString(body).asJsonObject
            val id = message.get("id")
            when (message.get("method")?.asString) {
                "kernelspecs" -> listOf("""{"jsonrpc":"2.0","id":$id,"result":{"kernelspecs":[{"name":"avf-miniconda3","display_name":"Python (miniconda3)","language":"python"}],"installable":[{"python":"/usr/bin/python3","label":"system"}]}}""")
                "start" -> listOf("""{"jsonrpc":"2.0","id":$id,"result":{"name":"avf-miniconda3"}}""")
                "execute" -> listOf(
                    """{"jsonrpc":"2.0","method":"status","params":{"state":"busy"}}""",
                    """{"jsonrpc":"2.0","method":"output","params":{"cell_id":"c1","output":{"output_type":"stream","name":"stdout","text":"3\n"}}}""",
                    """{"jsonrpc":"2.0","id":$id,"result":{"status":"ok","execution_count":1}}""",
                )
                else -> emptyList()
            }
        }.use { guest ->
            val kernel = KernelClient(guest.transport)
            val outputs = LinkedBlockingQueue<Pair<String, JsonObject>>()
            kernel.outputListener = { cell, output, _ -> outputs.offer(cell to output) }
            kernel.launch("/w").get(5, TimeUnit.SECONDS)
            assertEquals("Kernel", guest.startedKinds.poll(5, TimeUnit.SECONDS))
            assertEquals(JUPYTER_BRIDGE_TOOL, guest.startedTools.poll(5, TimeUnit.SECONDS))
            val discovery = kernel.kernelSpecs().get(5, TimeUnit.SECONDS)
            assertEquals("Python (miniconda3)", discovery.specs.single().displayName)
            assertEquals("/usr/bin/python3", discovery.installable.single().python)
            kernel.startKernel("avf-miniconda3", "/w").get(5, TimeUnit.SECONDS)
            val reply = kernel.execute("c1", "1+2").get(5, TimeUnit.SECONDS)
            assertEquals(1, reply.executionCount)
            val (cell, output) = outputs.poll(5, TimeUnit.SECONDS)!!
            assertEquals("c1", cell)
            assertEquals("3\n", output.get("text").asString)
        }
    }

    @Test
    fun generatedKernelNamesMatchTheBridge() {
        // avf-jupyter-bridge.py: "avf-" + label.lower() with non-alphanumerics replaced by "-".
        org.junit.Assert.assertEquals("avf-miniconda3", generatedKernelName("miniconda3"))
        org.junit.Assert.assertEquals("avf-conda--data-env", generatedKernelName("conda: Data env"))
    }
}
