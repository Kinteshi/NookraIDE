package io.github.kinteshi.quirecode.language

import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.LspRange
import io.github.kinteshi.quirecode.transport.GuestVfsClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.value.Value
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.UUID

class LanguageClientTest {
    @Test
    fun untitledDocumentsLiveUnderTheWorkspacesUntitledFolder() {
        assertEquals("/root/Projects/.untitled/Untitled-1.py", lspDocumentPath("scratch:/Untitled-1.py", "/root/Projects/"))
        assertEquals("/root/Projects/a.py", lspDocumentPath("/root/Projects/a.py", "/root/Projects"))
        assertEquals("scratch:/welcome.py", lspDocumentPath("scratch:/welcome.py", null))
        assertEquals("scratch:/Untitled-1.py", untitledPathFor("/root/Projects/.untitled/Untitled-1.py", "/root/Projects"))
        assertEquals(null, untitledPathFor("/root/Projects/a.py", "/root/Projects"))
        // Before a workspace (not connected yet) an untitled URI stays as it is, without throwing.
        assertEquals("scratch:/welcome.py", lspDocumentUri("scratch:/welcome.py", null))
        // A device file has no guest path; it must not become a (rejected) relative file URI.
        assertEquals("device:/content%3A%2F%2Fa/notes.txt", lspDocumentUri("device:/content%3A%2F%2Fa/notes.txt", "/root/Projects"))
        assertEquals("file:///root/Projects/.untitled/welcome.py", lspDocumentUri("scratch:/welcome.py", "/root/Projects"))
    }

    @Test
    fun semanticTokenDeltasDecodeIntoUtf16RangesAndLegendNames() {
        val result = JsonParser.parseString(
            """{"data":[0,2,3,1,1,0,4,2,0,0,1,0,1,1,0]}""",
        )

        val decorations = decodeSemanticTokens(
            result,
            tokenTypes = listOf("variable", "function"),
            tokenModifiers = listOf("declaration"),
        )

        assertEquals(3, decorations.size)
        assertEquals(LspRange(LspPosition(0, 2), LspPosition(0, 5)), decorations[0].range)
        assertEquals("function", decorations[0].kind)
        assertEquals("declaration", decorations[0].value)
        assertEquals(LspRange(LspPosition(0, 6), LspPosition(0, 8)), decorations[1].range)
        assertEquals("variable", decorations[1].kind)
        assertEquals(LspRange(LspPosition(1, 0), LspPosition(1, 1)), decorations[2].range)
    }

    @Test
    fun initializeConfigurationAndDiagnosticsFlowThroughGuestRpcBridge() {
        val server = MockWebServer()
        val protocol = LanguageProtocolListener()
        server.enqueue(MockResponse().withWebSocketUpgrade(protocol))
        server.start()
        val transport = GuestVfsClient()
        val diagnostics = LinkedBlockingQueue<List<LanguageDiagnostic>>()
        val configured = JsonObject().apply {
            addProperty("typeCheckingMode", "strict")
        }
        val languageClient = LanguageClient(
            transport,
            mapOf("python.analysis" to configured),
        )
        languageClient.diagnosticsListener = { _, items -> diagnostics.offer(items) }
        try {
            transport.connect(server.url("/ws").toString().replaceFirst("http", "ws"), "test-token")
                .get(5, TimeUnit.SECONDS)
            languageClient.start("/workspace").get(5, TimeUnit.SECONDS)
            assertTrue(protocol.diagnosticsCapability.await(5, TimeUnit.SECONDS))
            assertTrue(protocol.publishDiagnosticsSupported)
            // Untitled files reach the server under the workspace's .untitled/ folder.
            languageClient.didOpen("scratch:/welcome.py", "python", 1, "offline")
            assertEquals("/workspace/.untitled/welcome.py", protocol.openedPaths.poll(5, TimeUnit.SECONDS))
            languageClient.didOpen("/workspace/main.py", "python", 1, "value = missing_name\n")
            assertEquals("/workspace/main.py", protocol.openedPaths.poll(5, TimeUnit.SECONDS))
            assertTrue(protocol.configurationResponse.await(5, TimeUnit.SECONDS))
            val received = diagnostics.poll(5, TimeUnit.SECONDS)
            assertEquals(1, received?.size)
            assertEquals("Undefined name", received?.single()?.message)
            assertEquals(1, received?.single()?.severity)
            val edits = languageClient.formatting("/workspace/main.py").get(5, TimeUnit.SECONDS)
            assertEquals(1, edits.size)
            assertEquals("value = missing_name\n", edits.single().newText)
        } finally {
            languageClient.close()
            transport.close()
            runCatching { server.shutdown() }
        }
    }

}

private class LanguageProtocolListener : WebSocketListener() {
    val openedPaths = LinkedBlockingQueue<String>()
    val configurationResponse = java.util.concurrent.CountDownLatch(1)
    val diagnosticsCapability = java.util.concurrent.CountDownLatch(1)
    @Volatile var publishDiagnosticsSupported = false
    @Volatile private var bridgeId: String? = null

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val packet = MessagePack.newDefaultUnpacker(bytes.toByteArray()).use { it.unpackValue() }
        val group = packet.asMapValue().map().entries.single()
        when (group.key.asStringValue().asString()) {
            "System" -> webSocket.send(packet("System", "Welcome") {
                packMapHeader(3)
                packString("protocol_version")
                packInt(2)
                packString("daemon_version")
                packString("test")
                packString("pty_sessions")
                packArrayHeader(0)
            })
            "Rpc" -> {
                val variant = group.value.asMapValue().map().entries.single()
                val fields = variant.value.asMapValue().map().entries.associate {
                    it.key.asStringValue().asString() to it.value
                }
                val currentBridge = fields.getValue("bridge_id").asStringValue().asString()
                bridgeId = currentBridge
                when (variant.key.asStringValue().asString()) {
                    "Start" -> webSocket.send(rpcPacket("Started", currentBridge) {
                        packMapHeader(2)
                        stringField("bridge_id", currentBridge)
                        stringField("pid", 404)
                    })
                    "Message" -> handleLspMessage(webSocket, currentBridge, fields.getValue("body").asStringValue().asString())
                }
            }
        }
    }

    private fun handleLspMessage(webSocket: WebSocket, bridge: String, body: String) {
        val message = JsonParser.parseString(body).asJsonObject
        if (message.get("id")?.asInt == 99 && message.has("result")) {
            val value = message.getAsJsonArray("result").single().asJsonObject
            assertEquals("strict", value.get("typeCheckingMode").asString)
            configurationResponse.countDown()
            return
        }
        when (message.get("method")?.asString) {
            "initialize" -> {
                val textDocumentCapabilities = message.getAsJsonObject("params")
                    .getAsJsonObject("capabilities").getAsJsonObject("textDocument")
                // Strict servers such as Ruff refuse initialize without semanticTokens.formats.
                // TypeScript 7 refuses initialize without processId (null is allowed, absent isn't).
                publishDiagnosticsSupported = textDocumentCapabilities.has("publishDiagnostics") &&
                    textDocumentCapabilities.getAsJsonObject("semanticTokens").has("formats") &&
                    message.getAsJsonObject("params").has("processId")
                diagnosticsCapability.countDown()
                val id = message.get("id").asLong
                webSocket.send(rpcPacket("Message", bridge) {
                    val response = """{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{"textDocumentSync":1}}}"""
                    packMapHeader(2)
                    stringField("bridge_id", bridge)
                    stringField("body", response)
                })
                webSocket.send(rpcPacket("Message", bridge) {
                    val request =
                        """{"jsonrpc":"2.0","id":99,"method":"workspace/configuration","params":{"items":[{"section":"python.analysis"}]}}"""
                    packMapHeader(2)
                    stringField("bridge_id", bridge)
                    stringField("body", request)
                })
            }
            "textDocument/didOpen" -> {
                val uri = message.getAsJsonObject("params")
                    .getAsJsonObject("textDocument").get("uri").asString
                openedPaths.offer(java.net.URI(uri).path)
                webSocket.send(rpcPacket("Message", bridge) {
                    val notification =
                        """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"$uri","diagnostics":[{"range":{"start":{"line":0,"character":8},"end":{"line":0,"character":20}},"severity":1,"message":"Undefined name","source":"basedpyright"}]}}"""
                    packMapHeader(2)
                    stringField("bridge_id", bridge)
                    stringField("body", notification)
                })
            }
            "textDocument/formatting" -> {
                val id = message.get("id").asLong
                webSocket.send(rpcPacket("Message", bridge) {
                    val response =
                        """{"jsonrpc":"2.0","id":$id,"result":[{"range":{"start":{"line":0,"character":0},"end":{"line":1,"character":0}},"newText":"value = missing_name\n"}]}"""
                    packMapHeader(2)
                    stringField("bridge_id", bridge)
                    stringField("body", response)
                })
            }
        }
    }
}

private fun rpcPacket(
    variant: String,
    @Suppress("UNUSED_PARAMETER") bridgeId: String,
    fields: MessagePacker.() -> Unit,
): ByteString = packet("Rpc", variant, fields)

private fun packet(group: String, variant: String, fields: MessagePacker.() -> Unit): ByteString {
    val packer = MessagePack.newDefaultBufferPacker()
    packer.packMapHeader(1)
    packer.packString(group)
    packer.packMapHeader(1)
    packer.packString(variant)
    packer.fields()
    packer.close()
    return ByteString.of(*packer.toByteArray())
}

private fun MessagePacker.stringField(name: String, value: String) {
    packString(name)
    packString(value)
}

private fun MessagePacker.stringField(name: String, value: Int) {
    packString(name)
    packInt(value)
}
