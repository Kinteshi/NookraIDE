package io.github.kinteshi.nookraide.document

import io.github.kinteshi.nookraide.transport.GuestVfsClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Response
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.value.Value
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class GuestDocumentReconnectIntegrationTest {
    @Test
    fun changedDocumentIsSavedThroughProtocolAfterGuestReconnects() {
        val remote = RemoteFile(ByteArray(0))
        remote.content = "before\n".toByteArray()
        remote.modifiedTs = 77
        val firstServer = MockWebServer()
        firstServer.enqueue(MockResponse().withWebSocketUpgrade(FileProtocolListener(remote)))
        firstServer.start()
        val client = GuestVfsClient()
        val repository = DocumentRepository(client, MemoryCache())
        var secondServer: MockWebServer? = null
        try {
            client.connect(firstServer.wsUrl(), "test-token").get(5, TimeUnit.SECONDS)
            val document = (repository.open("/workspace/main.py").get(5, TimeUnit.SECONDS) as OpenDocument.Editable)
                .document
            assertEquals("before\n", document.text)
            document.updateText("edited while disconnected\n")

            firstServer.shutdown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (client.isConnected() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(!client.isConnected())
            assertEquals(SaveResult.QueuedOffline, repository.save(document).get(5, TimeUnit.SECONDS))

            val restarted = MockWebServer()
            secondServer = restarted
            restarted.enqueue(MockResponse().withWebSocketUpgrade(FileProtocolListener(remote)))
            restarted.start()
            client.connect(restarted.wsUrl(), "test-token").get(5, TimeUnit.SECONDS)
            assertTrue(repository.reconcile(document).get(5, TimeUnit.SECONDS) is SaveResult.Saved)
            assertEquals(
                "edited while disconnected\n",
                remote.content.toString(StandardCharsets.UTF_8),
            )
            assertTrue(!document.dirty)
        } finally {
            client.close()
            runCatching { firstServer.shutdown() }
            secondServer?.let { runCatching { it.shutdown() } }
        }
    }
}

private class RemoteFile(
    var content: ByteArray,
    var modifiedTs: Long = 1,
) {
    var staged: ByteArray? = null
}

private class FileProtocolListener(private val remote: RemoteFile) : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val packet = MessagePack.newDefaultUnpacker(bytes.toByteArray()).use { it.unpackValue() }
        val group = packet.asMapValue().map().entries.single()
        if (group.key.asStringValue().asString() == "System") {
            webSocket.send(response("System", "Welcome") {
                packMapHeader(3)
                packString("protocol_version")
                packInt(2)
                packString("daemon_version")
                packString("test")
                packString("pty_sessions")
                packArrayHeader(0)
            })
            return
        }

        val request = group.value.asMapValue().map().entries.single()
        val kind = request.key.asStringValue().asString()
        val fields = request.value.asMapValue().map().entries.associate {
            it.key.asStringValue().asString() to it.value
        }
        val requestId = fields.getValue("req_id").asIntegerValue().asLong()
        when (kind) {
            "StatReq" -> {
                val path = fields.getValue("path").asStringValue().asString()
                webSocket.send(response("Vfs", "StatRes") {
                    packMapHeader(2)
                    packString("req_id")
                    packLong(requestId)
                    packString("entry")
                    packEntry(path, remote)
                })
            }
            "ReadFileReq" -> {
                val offset = fields.getValue("offset").asIntegerValue().asLong().toInt()
                val length = fields.getValue("len").asIntegerValue().asInt()
                val end = minOf(offset + length, remote.content.size)
                val bytesToSend = remote.content.copyOfRange(offset, end)
                webSocket.send(response("Vfs", "ReadFileRes") {
                    packMapHeader(5)
                    packString("req_id")
                    packLong(requestId)
                    packString("content")
                    packBinaryHeader(bytesToSend.size)
                    writePayload(bytesToSend)
                    packString("eof")
                    packBoolean(end >= remote.content.size)
                    packString("modified_ts")
                    packLong(remote.modifiedTs)
                    packString("size")
                    packLong(remote.content.size.toLong())
                })
            }
            "WriteFileReq" -> {
                val truncate = fields.getValue("truncate").asBooleanValue().boolean
                val expected = fields.getValue("expected_modified_ts")
                if (truncate && !expected.isNilValue && expected.asIntegerValue().asLong() != remote.modifiedTs) {
                    webSocket.send(response("Vfs", "VfsError") {
                        packMapHeader(3)
                        packString("req_id")
                        packLong(requestId)
                        packString("code")
                        packString("Conflict")
                        packString("message")
                        packString("modified timestamp changed")
                    })
                    return
                }
                val chunk = fields.getValue("content").asBinaryValue().asByteArray()
                remote.staged = if (truncate) chunk else (remote.staged ?: ByteArray(0)) + chunk
                webSocket.send(ack(requestId, null))
            }
            "FlushReq" -> {
                remote.content = remote.staged ?: ByteArray(0)
                remote.staged = null
                remote.modifiedTs += 1
                webSocket.send(ack(requestId, remote.modifiedTs))
            }
            else -> error("Unexpected VFS request: $kind")
        }
    }
}

private fun MessagePacker.packEntry(path: String, remote: RemoteFile) {
    packMapHeader(9)
    packString("name")
    packString(path.substringAfterLast('/'))
    packString("path")
    packString(path)
    packString("is_dir")
    packBoolean(false)
    packString("is_symlink")
    packBoolean(false)
    packString("size")
    packLong(remote.content.size.toLong())
    packString("modified_ts")
    packLong(remote.modifiedTs)
    packString("mode")
    packInt(0)
    packString("is_hidden")
    packBoolean(false)
    packString("git_status")
    packNil()
}

private fun ack(requestId: Long, modifiedTs: Long?): ByteString =
    response("Vfs", "Ack") {
        packMapHeader(3)
        packString("req_id")
        packLong(requestId)
        packString("modified_ts")
        if (modifiedTs == null) packNil() else packLong(modifiedTs)
        packString("trash_id")
        packNil()
    }

private fun response(group: String, variant: String, fields: MessagePacker.() -> Unit): ByteString {
    val packer = MessagePack.newDefaultBufferPacker()
    packer.packMapHeader(1)
    packer.packString(group)
    packer.packMapHeader(1)
    packer.packString(variant)
    packer.fields()
    packer.close()
    return ByteString.of(*packer.toByteArray())
}

private fun MockWebServer.wsUrl(): String = url("/ws").toString().replaceFirst("http", "ws")

private fun Value.field(name: String): Value =
    asMapValue().map().entries.first { it.key.asStringValue().asString() == name }.value

private class MemoryCache : DocumentCache {
    private val snapshots = mutableMapOf<String, DocumentSnapshot>()
    override fun load(path: String) = snapshots[path]
    override fun save(snapshot: DocumentSnapshot) {
        snapshots[snapshot.path] = snapshot
    }
    override fun delete(path: String) {
        snapshots.remove(path)
    }
}
