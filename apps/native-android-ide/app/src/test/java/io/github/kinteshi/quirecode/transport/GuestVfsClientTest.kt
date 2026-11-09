package io.github.kinteshi.quirecode.transport

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.value.Value
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

class GuestVfsClientTest {
    @Test
    fun rpcStartAndMessageUseProtocolV2ShapesAndRouteBridgeEvents() {
        val server = okhttp3.mockwebserver.MockWebServer()
        val listener = RpcEchoListener()
        server.enqueue(okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(listener))
        server.start()
        val client = GuestVfsClient()
        try {
            val url = server.url("/ws").toString().replaceFirst("http", "ws")
            client.connect(url, "session-token").get(5, TimeUnit.SECONDS)
            val bridgeId = client.startLanguageServer("basedpyright", "/workspace").get(5, TimeUnit.SECONDS)
            client.sendRpcMessage(bridgeId, "{\"jsonrpc\":\"2.0\",\"method\":\"initialized\"}").get(5, TimeUnit.SECONDS)
            val event = listener.events.poll(5, TimeUnit.SECONDS)
            assertTrue(event is RpcEvent.Message)
            assertEquals(bridgeId, event?.bridgeId)
            assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\"}", (event as RpcEvent.Message).body)
        } finally {
            client.close()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun rpcEventsAreDispatchedToTheMatchingBridgeListener() {
        val server = okhttp3.mockwebserver.MockWebServer()
        val listener = RpcEchoListener()
        server.enqueue(okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(listener))
        server.start()
        val client = GuestVfsClient()
        val firstEvents = LinkedBlockingQueue<RpcEvent>()
        val secondEvents = LinkedBlockingQueue<RpcEvent>()
        try {
            val url = server.url("/ws").toString().replaceFirst("http", "ws")
            client.connect(url, "session-token").get(5, TimeUnit.SECONDS)
            val firstBridge = client.startLanguageServer("basedpyright", "/workspace").get(5, TimeUnit.SECONDS)
            val secondBridge = client.startLanguageServer("vtsls", "/workspace").get(5, TimeUnit.SECONDS)
            client.addRpcEventListener(firstBridge, firstEvents::offer)
            client.addRpcEventListener(secondBridge, secondEvents::offer)

            listener.sendMessage(firstBridge, "first")
            listener.sendMessage(secondBridge, "second")

            val first = firstEvents.poll(5, TimeUnit.SECONDS) as RpcEvent.Message
            val second = secondEvents.poll(5, TimeUnit.SECONDS) as RpcEvent.Message
            assertEquals(firstBridge, first.bridgeId)
            assertEquals("first", first.body)
            assertEquals(secondBridge, second.bridgeId)
            assertEquals("second", second.body)
        } finally {
            client.close()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun rpcStartEncoderMatchesRustSerdeFields() {
        val id = UUID.randomUUID()
        val packet = MessagePack.newDefaultUnpacker(
            MessagePackVfsCodec.rpcStart(id, "basedpyright", listOf("--stdio"), "/workspace"),
        ).use { it.unpackValue() }
        val payload = packet.field("Rpc").field("Start")
        assertEquals(id.toString(), payload.field("bridge_id").asStringValue().asString())
        assertEquals("basedpyright", payload.field("tool").asStringValue().asString())
        assertEquals("Lsp", payload.field("kind").asStringValue().asString())
        assertEquals("--stdio", payload.field("args").asArrayValue().list().single().asStringValue().asString())
        assertEquals("/workspace", payload.field("cwd").asStringValue().asString())
        assertEquals(0, payload.field("env").asArrayValue().list().size)
    }

    @Test
    fun vfsReadRequestUsesTheRustExternallyTaggedProtocolShape() {
        val bytes = MessagePackVfsCodec.request(
            "ReadFileReq",
            77,
            mapOf("path" to "/workspace/main.py", "offset" to 4096L, "len" to 2048),
        )
        val packet = MessagePack.newDefaultUnpacker(bytes).use { it.unpackValue() }
        val vfs = packet.field("Vfs")
        val request = vfs.field("ReadFileReq")

        assertEquals(77L, request.field("req_id").asIntegerValue().asLong())
        assertEquals("/workspace/main.py", request.field("path").asStringValue().asString())
        assertEquals(4096L, request.field("offset").asIntegerValue().asLong())
        assertEquals(2048L, request.field("len").asIntegerValue().asLong())
    }

    private class RpcEchoListener : WebSocketListener() {
        val events = LinkedBlockingQueue<RpcEvent>()
        @Volatile private var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            socket = webSocket
        }

        fun sendMessage(bridgeId: UUID, body: String) {
            socket?.send(responsePacket("Rpc", "Message") {
                packMapHeader(2)
                packString("bridge_id")
                packString(bridgeId.toString())
                packString("body")
                packString(body)
            })
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val packet = MessagePack.newDefaultUnpacker(bytes.toByteArray()).use { it.unpackValue() }
            val group = packet.asMapValue().map().entries.single()
            when (group.key.asStringValue().asString()) {
                "System" -> webSocket.send(responsePacket("System", "Welcome") {
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
                    val bridgeId = fields.getValue("bridge_id").asStringValue().asString()
                    if (variant.key.asStringValue().asString() == "Start") {
                        webSocket.send(responsePacket("Rpc", "Started") {
                            packMapHeader(2)
                            packString("bridge_id")
                            packString(bridgeId)
                            packString("pid")
                            packInt(4711)
                        })
                    } else if (variant.key.asStringValue().asString() == "Message") {
                        val message = RpcEvent.Message(
                            UUID.fromString(bridgeId),
                            fields.getValue("body").asStringValue().asString(),
                        )
                        events.offer(message)
                    }
                }
            }
        }
    }

    @Test
    fun clientCompletesVfsRequestAndCanReconnectAfterSocketClose() {
        val server = okhttp3.mockwebserver.MockWebServer()
        val firstListener = ProtocolEchoListener()
        server.enqueue(okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(firstListener))
        server.start()
        val client = GuestVfsClient()
        try {
            val url = server.url("/ws").toString().replaceFirst("http", "ws")
            client.connect(url, "session-token").get(5, TimeUnit.SECONDS)
            assertEquals(
                "avf-native-android://localhost",
                server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Origin"),
            )
            assertEquals(listOf("/workspace"), client.roots().get(5, TimeUnit.SECONDS))
            assertTrue(client.isConnected())

            firstListener.socket?.close(1001, "simulate guest restart")
            val closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (client.isConnected() && System.nanoTime() < closeDeadline) Thread.sleep(10)
            if (client.isConnected()) server.shutdown()
            val disconnectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (client.isConnected() && System.nanoTime() < disconnectDeadline) Thread.sleep(10)
            assertFalse(client.isConnected())

            val reconnectedServer = okhttp3.mockwebserver.MockWebServer()
            val secondListener = ProtocolEchoListener()
            reconnectedServer.enqueue(okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(secondListener))
            reconnectedServer.start()
            val reconnectedUrl = reconnectedServer.url("/ws").toString().replaceFirst("http", "ws")
            client.connect(reconnectedUrl, "session-token").get(5, TimeUnit.SECONDS)
            assertEquals(listOf("/workspace"), client.roots().get(5, TimeUnit.SECONDS))
            reconnectedServer.shutdown()
        } finally {
            client.close()
            runCatching { server.shutdown() }
        }
    }
}

private class ProtocolEchoListener : WebSocketListener() {
    @Volatile var socket: WebSocket? = null

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        socket = webSocket
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val packet = MessagePack.newDefaultUnpacker(bytes.toByteArray()).use { it.unpackValue() }
        val group = packet.asMapValue().map()
        if (group.keys.first().asStringValue().asString() == "System") {
            webSocket.send(responsePacket("System", "Welcome") {
                packMapHeader(3)
                packString("protocol_version")
                packInt(2)
                packString("daemon_version")
                packString("test")
                packString("pty_sessions")
                packArrayHeader(0)
            })
        } else {
            val payload = packet.field("Vfs").field("RootsReq")
            val requestId = payload.field("req_id").asIntegerValue().asLong()
            webSocket.send(responsePacket("Vfs", "RootsRes") {
                packMapHeader(2)
                packString("req_id")
                packLong(requestId)
                packString("roots")
                packArrayHeader(1)
                packString("/workspace")
            })
        }
    }
}

private fun responsePacket(group: String, variant: String, fields: MessagePacker.() -> Unit): ByteString {
    val packer = MessagePack.newDefaultBufferPacker()
    packer.packMapHeader(1)
    packer.packString(group)
    packer.packMapHeader(1)
    packer.packString(variant)
    packer.fields()
    packer.close()
    return ByteString.of(*packer.toByteArray())
}

private fun Value.field(name: String): Value =
    asMapValue().map().entries.first { it.key.asStringValue().asString() == name }.value
