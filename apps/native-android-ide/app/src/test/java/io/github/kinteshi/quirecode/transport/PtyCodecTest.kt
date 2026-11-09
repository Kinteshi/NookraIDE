package io.github.kinteshi.quirecode.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.msgpack.core.MessagePack
import java.util.UUID

class PtyCodecTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun ptyCommandsUseTheDaemonsTaggedEnumShape() {
        // Matches avf_protocol::encode for MultiplexedPacket::Pty { session_id, data: Reattach }.
        val value = MessagePack.newDefaultUnpacker(MessagePackVfsCodec.ptyCommand(id, "Reattach")).unpackValue()
        val fields = value.asMapValue().map().entries.single().value.asMapValue().map()
            .mapKeys { it.key.asStringValue().asString() }
        assertEquals(id.toString(), fields.getValue("session_id").asStringValue().asString())
        assertEquals("Reattach", fields.getValue("data").asStringValue().asString())

        val input = MessagePack.newDefaultUnpacker(MessagePackVfsCodec.ptyInput(id, "ls\n".toByteArray())).unpackValue()
        val data = input.asMapValue().map().entries.single().value.asMapValue().map()
            .mapKeys { it.key.asStringValue().asString() }.getValue("data").asMapValue().map().entries.single()
        assertEquals("Input", data.key.asStringValue().asString())
        assertArrayEquals("ls\n".toByteArray(), data.value.asBinaryValue().asByteArray())
    }

    @Test
    fun ptyOutputAndExitDecode() {
        fun packet(variant: String, body: org.msgpack.core.MessagePacker.() -> Unit): ByteArray {
            val packer = MessagePack.newDefaultBufferPacker()
            packer.packMapHeader(1); packer.packString("Pty")
            packer.packMapHeader(2)
            packer.packString("session_id"); packer.packString(id.toString())
            packer.packString("data"); packer.packMapHeader(1); packer.packString(variant); packer.body()
            packer.close()
            return packer.toByteArray()
        }
        val output = MessagePackVfsCodec.decode(packet("Output") { packBinaryHeader(2); writePayload("ok".toByteArray()) })
        assertArrayEquals("ok".toByteArray(), ((output as IncomingPacket.Pty).event as PtyEvent.Output).data)
        val exit = MessagePackVfsCodec.decode(packet("Exit") { packMapHeader(1); packString("code"); packInt(3) })
        assertEquals(3, ((exit as IncomingPacket.Pty).event as PtyEvent.Exit).code)
    }
}
