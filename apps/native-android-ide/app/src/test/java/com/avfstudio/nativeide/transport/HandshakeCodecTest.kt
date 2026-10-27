package com.avfstudio.nativeide.transport

import org.junit.Assert.assertEquals
import org.junit.Test
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker

class HandshakeCodecTest {
    private fun system(variant: String, body: MessagePacker.() -> Unit): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packString("System")
        packer.packMapHeader(1)
        packer.packString(variant)
        packer.body()
        return packer.toByteArray()
    }

    @Test
    fun decodesUnitAndStructRejectionReasons() {
        val invalid = system("Rejected") {
            packMapHeader(1)
            packString("reason")
            packString("InvalidToken")
        }
        assertEquals(IncomingPacket.Rejected("InvalidToken"), MessagePackVfsCodec.decode(invalid))

        val failed = system("Rejected") {
            packMapHeader(1)
            packString("reason")
            packMapHeader(1)
            packString("PairingFailed")
            packMapHeader(1)
            packString("attempts_left")
            packInt(3)
        }
        assertEquals(IncomingPacket.Rejected("PairingFailed", 3), MessagePackVfsCodec.decode(failed))
    }

    @Test
    fun decodesThePairedToken() {
        val paired = system("Paired") {
            packMapHeader(1)
            packString("token")
            packString("f".repeat(64))
        }
        assertEquals(IncomingPacket.Paired("f".repeat(64)), MessagePackVfsCodec.decode(paired))
    }

    @Test
    fun encodesThePairRequest() {
        val unpacked = MessagePack.newDefaultUnpacker(MessagePackVfsCodec.pair(" K7QM-2XPA ")).unpackValue()
        val pair = unpacked.asMapValue().map().values.first().asMapValue().map().values.first().asMapValue().map()
            .mapKeys { (key, _) -> key.asStringValue().asString() }
        assertEquals("K7QM-2XPA", pair.getValue("code").asStringValue().asString().trim())
        assertEquals(2L, pair.getValue("protocol_version").asIntegerValue().asLong())
    }

    @Test
    fun decodesPongWithAndWithoutADiskProblem() {
        fun pong(diskProblem: String?) = system("Pong") {
            packMapHeader(4)
            packString("timestamp"); packLong(7)
            packString("mem_total_mb"); packLong(3908)
            packString("mem_free_mb"); packLong(2000)
            packString("disk_problem"); if (diskProblem == null) packNil() else packString(diskProblem)
        }
        val healthy = MessagePackVfsCodec.decode(pong(null)) as IncomingPacket.Stats
        assertEquals(null, healthy.stats.diskProblem)
        assertEquals(1908, healthy.stats.footprintMb)
        val storage = MessagePackVfsCodec.decode(system("Pong") {
            packMapHeader(2)
            packString("disk_used_mb"); packLong(12_000)
            packString("disk_total_mb"); packLong(64_000)
        }) as IncomingPacket.Stats
        assertEquals(12_000L to 64_000L, storage.stats.diskUsedMb to storage.stats.diskTotalMb)
        val broken = MessagePackVfsCodec.decode(pong("The guest's disk went read-only after an error")) as IncomingPacket.Stats
        assertEquals("The guest's disk went read-only after an error", broken.stats.diskProblem)
    }
}
