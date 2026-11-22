package io.github.kinteshi.nookraide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val info = DiagnosticsInfo(
        appVersion = "0.1.0",
        versionCode = 100,
        debuggable = false,
        editor = "Aardink 0.10.0",
        android = "16",
        sdk = 36,
        securityPatch = "2026-10-01",
        device = "samsung SM-X930",
        abis = listOf("arm64-v8a"),
        linuxTerminal = "1.0 (36)",
        window = "1280×800 dp, floating",
        memory = "5.1 GB free of 15.2 GB",
        connected = true,
        endpoint = "ws://user:secret-token@127.0.0.1:19870/vfs?token=secret-token",
        daemonVersion = "0.1.0",
        daemonStatus = "matches the app",
        guestStoppedAt = null,
        languageServers = listOf("python" to "ready"),
        notebooks = emptyList(),
        recentMessages = listOf("Connected", "Saved main.py\nsecond line", "Token secret-token refused"),
    )

    @Test
    fun reportHasTheFactsAndRedactsTheToken() {
        val report = diagnosticsReport(info, secrets = listOf("secret-token"))
        assertTrue(report.startsWith("Nookra IDE diagnostics\n"))
        assertTrue(report.contains("App: 0.1.0 (100)\n"))
        assertTrue(report.contains("Guest: connected (127.0.0.1:19870)"))
        assertTrue(report.contains("Language servers: python ready"))
        assertTrue(report.contains("  Saved main.py second line"))
        assertFalse(report.contains("secret-token"))
        assertTrue(report.contains("Token <redacted> refused"))
        assertFalse(report.contains("Notebook kernels"))
    }

    @Test
    fun keepsTheLastMessagesOnly() {
        val many = info.copy(recentMessages = (1..50).map { "message $it" }, linuxTerminal = null, debuggable = true)
        val report = diagnosticsReport(many)
        assertFalse(report.contains("message 20\n"))
        assertTrue(report.contains("message 21\n"))
        assertTrue(report.contains("message 50\n"))
        assertTrue(report.contains("Linux Terminal: not installed"))
        assertTrue(report.contains("(100), debug build"))
    }

    @Test
    fun endpointHostKeepsHostAndPort() {
        assertEquals("127.0.0.1:19870", endpointHost("ws://127.0.0.1:19870/vfs"))
        assertEquals("localhost:19870", endpointHost("localhost:19870"))
        assertEquals(null, endpointHost(" "))
    }
}
