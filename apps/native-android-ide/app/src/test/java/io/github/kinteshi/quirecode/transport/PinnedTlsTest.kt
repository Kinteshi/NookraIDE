package io.github.kinteshi.quirecode.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedTlsTest {
    private val fp = "fe1de5f6cac59f713449f383ed96c310d1851e90dea37d687344d7eb0d2502e5"

    @Test
    fun parsesTheDaemonsConnectionString() {
        val parsed = parseConnectionString("  avf://192.168.1.10:19870?fp=$fp&code=K7QM-2XPA ")
        assertEquals(ConnectionString("192.168.1.10", 19870, fp, "K7QM-2XPA"), parsed)
        assertEquals("wss://192.168.1.10:19870/ws", parsed!!.endpoint)
        assertEquals("wss://[fe80::1]:19870/ws", parseConnectionString("avf://[fe80::1]:19870?fp=$fp&code=C")!!.endpoint)
        assertEquals(19870, parseConnectionString("avf://host.lan?fp=$fp&code=C")!!.port)
    }

    @Test
    fun parsesPublicTlsTunnelConnectionStrings() {
        val tunnel = parseConnectionString("avf://quirecode.example.com:443?fp=public&code=K7QM-2XPA")
        assertEquals(ConnectionString("quirecode.example.com", 443, null, "K7QM-2XPA"), tunnel)
        assertEquals("wss://quirecode.example.com:443/ws", tunnel!!.endpoint)
        assertEquals(443, parseConnectionString("avf://quirecode.example.com?fp=public&code=C")!!.port)
        assertNull(parseConnectionString("avf://quirecode.example.com?fp=public&code=C")!!.fingerprint)
    }

    @Test
    fun rejectsMissingOrMalformedFingerprints() {
        assertNull(parseConnectionString("avf://h:1?code=C"))
        assertNull(parseConnectionString("avf://h:1?fp=abc&code=C"))
        assertNull(parseConnectionString("wss://h:1/ws"))
        assertNull(parseConnectionString("avf://h:1?fp=$fp"))
    }
}
