package io.github.kinteshi.quirecode.testing

import io.github.kinteshi.quirecode.transport.GuestVfsClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A guest daemon stand-in: completes the protocol handshake, acknowledges RPC bridge starts,
 * and passes each JSON-RPC body to [onMessage], whose return values are sent back in order.
 */
class FakeGuest(private val onMessage: FakeGuest.(String) -> List<String>) : Closeable {
    val startedKinds = LinkedBlockingQueue<String>()
    val startedTools = LinkedBlockingQueue<String>()
    val received = LinkedBlockingQueue<String>()
    private val server = MockWebServer()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var bridge: String? = null
    val transport = GuestVfsClient()

    init {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes)
        }))
        server.start()
        transport.connect(server.url("/ws").toString().replaceFirst("http", "ws"), "test-token")
            .get(5, TimeUnit.SECONDS)
    }

    /** Sends an unsolicited JSON-RPC body from the guest tool. */
    fun push(body: String) {
        val activeBridge = requireNotNull(bridge) { "No bridge has started" }
        requireNotNull(socket).send(rpcMessage(activeBridge, body))
    }

    private fun handle(webSocket: WebSocket, bytes: ByteString) {
        socket = webSocket
        val packet = MessagePack.newDefaultUnpacker(bytes.toByteArray()).use { it.unpackValue() }
        val group = packet.asMapValue().map().entries.single()
        when (group.key.asStringValue().asString()) {
            "System" -> webSocket.send(packet("System", "Welcome") {
                packMapHeader(3)
                packString("protocol_version"); packInt(2)
                packString("daemon_version"); packString("test")
                packString("pty_sessions"); packArrayHeader(0)
            })
            "Rpc" -> {
                val variant = group.value.asMapValue().map().entries.single()
                val fields = variant.value.asMapValue().map().entries.associate {
                    it.key.asStringValue().asString() to it.value
                }
                val currentBridge = fields.getValue("bridge_id").asStringValue().asString()
                when (variant.key.asStringValue().asString()) {
                    "Start" -> {
                        bridge = currentBridge
                        startedKinds.offer(fields.getValue("kind").asStringValue().asString())
                        startedTools.offer(fields.getValue("tool").asStringValue().asString())
                        webSocket.send(packet("Rpc", "Started") {
                            packMapHeader(2)
                            packString("bridge_id"); packString(currentBridge)
                            packString("pid"); packInt(404)
                        })
                    }
                    "Message" -> {
                        val body = fields.getValue("body").asStringValue().asString()
                        received.offer(body)
                        onMessage(body).forEach { reply -> webSocket.send(rpcMessage(currentBridge, reply)) }
                    }
                }
            }
        }
    }

    override fun close() {
        transport.close()
        runCatching { server.shutdown() }
    }
}

private fun rpcMessage(bridge: String, body: String): ByteString = packet("Rpc", "Message") {
    packMapHeader(2)
    packString("bridge_id"); packString(bridge)
    packString("body"); packString(body)
}

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
