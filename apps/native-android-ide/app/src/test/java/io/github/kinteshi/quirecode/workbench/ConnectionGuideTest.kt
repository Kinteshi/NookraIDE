package io.github.kinteshi.quirecode.workbench

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGuideTest {
    @Test
    fun guidedOptionsRecommendLanAtHomeAndTailscaleAnywhereWithWarnings() {
        assertEquals(listOf(RemoteSetupOption.Lan), guidedOptionsFor(UsageScope.HomeWifi))
        val anywhere = guidedOptionsFor(UsageScope.Anywhere)
        assertEquals(
            listOf(
                RemoteSetupOption.Tailscale,
                RemoteSetupOption.CloudflareTunnel,
                RemoteSetupOption.RouterPortForward,
            ),
            anywhere,
        )
        assertTrue(RemoteSetupOption.Tailscale.recommended)
        assertNotNull(RemoteSetupOption.CloudflareTunnel.warning)
        assertNotNull(RemoteSetupOption.RouterPortForward.warning)
    }

    @Test
    fun guidedCommandsProvideInstallAndPairCommandsForEveryOptionAndOs() {
        RemoteSetupOption.entries.forEach { option ->
            ComputerOs.entries.forEach { os ->
                val cmd = guidedCommands(option, os)
                assertTrue("$option/$os setup blank", cmd.setupStep.isNotBlank())
                assertTrue("$option/$os pair blank", cmd.pairCommand.contains("quirecode-daemon pair"))
            }
        }
        assertTrue(guidedCommands(RemoteSetupOption.Tailscale, ComputerOs.Linux).setupStep.contains("--tailscale"))
        assertTrue(guidedCommands(RemoteSetupOption.Lan, ComputerOs.WindowsWsl).setupStep.contains("install-host-daemon-wsl.ps1"))
        assertTrue(guidedCommands(RemoteSetupOption.CloudflareTunnel, ComputerOs.Linux).pairCommand.contains("--tunnel"))
    }

    @Test
    fun extractConnectionStringAcceptsValidAndRejectsInvalidText() {
        val valid = "avf://192.168.1.10:19870?fp=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef&code=K7QM-2XPA"
        val publicTls = "avf://quirecode.example.com:443?fp=public&code=K7QM-2XPA"
        assertEquals(valid, extractConnectionStringFromText("  $valid \n"))
        assertEquals(publicTls, extractConnectionStringFromText(publicTls))
        assertNull(extractConnectionStringFromText(null))
        assertNull(extractConnectionStringFromText("https://example.com"))
        assertNull(extractConnectionStringFromText("avf://192.168.1.10:19870?fp=abc"))
    }

    @Test
    fun decodeQrConnectionStringDecodesNormalAndInvertedQrAndRejectsOtherUrls() {
        val payload = "avf://100.64.0.1:19870?fp=cd80878123be4c07a5accddef3f77325ff4024545aeca7624ab307e98f4e3a60&code=K7QM-2XPA"
        val normalLuma = encodeQrLuma(payload, inverted = false)
        assertEquals(QrScanResult.Connection(payload), decodeQrConnectionString(normalLuma.first, normalLuma.second, normalLuma.second))

        // Terminal QR printed on a dark background renders modules light-on-dark.
        val invertedLuma = encodeQrLuma(payload, inverted = true)
        assertEquals(QrScanResult.Connection(payload), decodeQrConnectionString(invertedLuma.first, invertedLuma.second, invertedLuma.second))

        val nonConnection = encodeQrLuma("https://example.com", inverted = false)
        assertEquals(QrScanResult.OtherQr, decodeQrConnectionString(nonConnection.first, nonConnection.second, nonConnection.second))
    }

    private fun encodeQrLuma(text: String, inverted: Boolean): Pair<ByteArray, Int> {
        val size = 240
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val out = ByteArray(size * size)
        val darkByte = if (inverted) 0xFF.toByte() else 0x00.toByte()
        val lightByte = if (inverted) 0x00.toByte() else 0xFF.toByte()
        for (y in 0 until size) {
            for (x in 0 until size) {
                out[y * size + x] = if (matrix.get(x, y)) darkByte else lightByte
            }
        }
        return out to size
    }
}
