package io.github.kinteshi.nookraide.transport

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

        val limited = system("Rejected") {
            packMapHeader(1)
            packString("reason")
            packMapHeader(1)
            packString("TooManyAttempts")
            packMapHeader(1)
            packString("retry_after_secs")
            packInt(541)
        }
        val rejected = MessagePackVfsCodec.decode(limited) as IncomingPacket.Rejected
        assertEquals(IncomingPacket.Rejected("TooManyAttempts", null, 541), rejected)
        assertEquals(
            "Too many failed attempts from this device; try again in 10 minutes",
            GuestRejectedException(rejected.reason, rejected.attemptsLeft, rejected.retryAfterSecs).message,
        )
    }

    @Test
    fun decodesPairedDevices() {
        val packet = system("Devices") {
            packMapHeader(5)
            packString("request_id"); packInt(4)
            packString("devices"); packArrayHeader(2)
            packMapHeader(5)
            packString("id"); packString("3fa9c1")
            packString("name"); packString("QuireCode on Tab")
            packString("paired_at"); packLong(1_762_000_000)
            packString("last_seen"); packLong(1_762_000_100)
            packString("last_address"); packString("100.64.0.2")
            packMapHeader(5)
            packString("id"); packString("aa0001")
            packString("name"); packString("QuireCode on Phone")
            packString("paired_at"); packLong(1_762_000_000)
            packString("last_seen"); packNil()
            packString("last_address"); packNil()
            packString("this_device"); packString("3fa9c1")
            packString("recent"); packArrayHeader(1); packString("2026-11-09 17:00:00 UTC - paired")
            packString("error"); packNil()
        }
        val devices = MessagePackVfsCodec.decode(packet) as IncomingPacket.Devices
        assertEquals(4L, devices.requestId)
        assertEquals("3fa9c1", devices.info.thisDevice)
        assertEquals(listOf("QuireCode on Tab", "QuireCode on Phone"), devices.info.devices.map { it.name })
        assertEquals(null, devices.info.devices[1].lastSeen)
        assertEquals("100.64.0.2", devices.info.devices[0].lastAddress)
        assertEquals(1, devices.info.recent.size)
        assertEquals(null, devices.info.error)
    }

    @Test
    fun pairingSendsTheDeviceName() {
        val packet = MessagePack.newDefaultUnpacker(MessagePackVfsCodec.pair("ABCD-EFGH", "QuireCode on Galaxy Tab")).unpackValue().toString()
        assert(packet.contains("QuireCode on Galaxy Tab")) { packet }
        assert(!packet.contains("previous_token")) { packet }
    }

    @Test
    fun pairingAgainSendsTheOldTokenAsProofOfOwnership() {
        val packet = MessagePack.newDefaultUnpacker(MessagePackVfsCodec.pair("ABCD-EFGH", "Tab", "f".repeat(64))).unpackValue().toString()
        assert(packet.contains("\"previous_token\":\"${"f".repeat(64)}\"")) { packet }
        val blank = MessagePack.newDefaultUnpacker(MessagePackVfsCodec.pair("ABCD-EFGH", "Tab", " ")).unpackValue().toString()
        assert(!blank.contains("previous_token")) { blank }
    }

    @Test
    fun decodesGuestPortsOpeningAndClosing() {
        val opened = system("PortOpened") {
            packMapHeader(3)
            packString("port")
            packInt(8000)
            packString("protocol")
            packString("Tcp6")
            packString("process_name")
            packString("python3 -m http.server")
        }
        assertEquals(IncomingPacket.Port(GuestPort(8000, ipv6 = true, command = "python3 -m http.server"), open = true), MessagePackVfsCodec.decode(opened))
        val closed = system("PortClosed") {
            packMapHeader(2)
            packString("port")
            packInt(8000)
            packString("protocol")
            packString("Tcp")
        }
        assertEquals(IncomingPacket.Port(GuestPort(8000, ipv6 = false, command = null), open = false), MessagePackVfsCodec.decode(closed))
        val unknownOwner = system("PortOpened") {
            packMapHeader(3)
            packString("port")
            packInt(22)
            packString("protocol")
            packString("Tcp")
            packString("process_name")
            packNil()
        }
        assertEquals(IncomingPacket.Port(GuestPort(22, ipv6 = false, command = null), open = true), MessagePackVfsCodec.decode(unknownOwner))
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
        assertEquals(null to null, storage.stats.tmpInMemory to storage.stats.tmpMountMasked)
        val tmp = MessagePackVfsCodec.decode(system("Pong") {
            packMapHeader(2)
            packString("tmp_in_memory"); packBoolean(true)
            packString("tmp_mount_masked"); packNil()
        }) as IncomingPacket.Stats
        assertEquals(true to null, tmp.stats.tmpInMemory to tmp.stats.tmpMountMasked)
        val broken = MessagePackVfsCodec.decode(pong("The guest's disk went read-only after an error")) as IncomingPacket.Stats
        assertEquals("The guest's disk went read-only after an error", broken.stats.diskProblem)
    }

    @Test
    fun readsWelcomeFeaturesAndProbeReplies() {
        val welcome = MessagePackVfsCodec.decode(system("Welcome") {
            packMapHeader(4)
            packString("protocol_version"); packInt(2)
            packString("daemon_version"); packString("0.1.0")
            packString("pty_sessions"); packArrayHeader(0)
            packString("features"); packArrayHeader(1); packString(PROBE_COMMANDS_FEATURE)
        }) as IncomingPacket.Welcome
        assertEquals(setOf(PROBE_COMMANDS_FEATURE), welcome.features)
        // Daemons from before the field still say Welcome without it.
        val older = MessagePackVfsCodec.decode(system("Welcome") {
            packMapHeader(1)
            packString("protocol_version"); packInt(2)
        }) as IncomingPacket.Welcome
        assertEquals(emptySet<String>(), older.features)

        val probed = MessagePackVfsCodec.decode(system("CommandsProbed") {
            packMapHeader(2)
            packString("request_id"); packLong(4)
            packString("commands"); packArrayHeader(2)
            packMapHeader(2); packString("name"); packString("git"); packString("path"); packString("/usr/bin/git")
            packMapHeader(2); packString("name"); packString("conda"); packString("path"); packNil()
        })
        assertEquals(IncomingPacket.CommandsProbed(4, mapOf("git" to "/usr/bin/git", "conda" to null)), probed)
    }

    @Test
    fun encodesTheProbeRequest() {
        val unpacked = MessagePack.newDefaultUnpacker(MessagePackVfsCodec.probeCommands(9, listOf("git", "uv"))).unpackValue()
        val probe = unpacked.asMapValue().map().values.first().asMapValue().map().values.first().asMapValue().map()
            .mapKeys { (key, _) -> key.asStringValue().asString() }
        assertEquals(9L, probe.getValue("request_id").asIntegerValue().asLong())
        assertEquals(listOf("git", "uv"), probe.getValue("names").asArrayValue().list().map { it.asStringValue().asString() })
    }
}
