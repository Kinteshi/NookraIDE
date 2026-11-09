package io.github.kinteshi.quirecode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CopilotLoginTest {
    @Test
    fun parsesTheCliDeviceCodePrompt() {
        val output = "To authenticate, visit https://github.com/login/device and enter code AB12-CD34\r\nWaiting for authorization...\r\n"
        assertEquals(DeviceCodePrompt("https://github.com/login/device", "AB12-CD34"), parseDeviceCodePrompt(output))
    }

    @Test
    fun ignoresColourAndHyperlinkEscapes() {
        val output = "\u001B[1mTo authenticate, visit \u001B]8;;https://github.com/login/device\u0007" +
            "https://github.com/login/device\u001B]8;;\u0007 and enter code \u001B[33mWXYZ-9876\u001B[0m"
        assertEquals(DeviceCodePrompt("https://github.com/login/device", "WXYZ-9876"), parseDeviceCodePrompt(output))
    }

    @Test
    fun readsTheFallbackLineAndEnterpriseHosts() {
        val output = "Failed to open browser. Please visit https://example.ghe.com/login/device and enter the code QRST-5678 manually."
        assertEquals(DeviceCodePrompt("https://example.ghe.com/login/device", "QRST-5678"), parseDeviceCodePrompt(output))
    }

    @Test
    fun parsesTheGitHubCliPrompt() {
        val output = "\u001B[0;33m!\u001B[0m First copy your one-time code: \u001B[1mC0DE-1234\u001B[0m\n" +
            "Press Enter to open https://github.com/login/device in your browser... "
        assertEquals(DeviceCodePrompt("https://github.com/login/device", "C0DE-1234"), parseDeviceCodePrompt(output))
        assertEquals(DeviceCodePrompt("https://github.com/login/device", "ABCD-9999"), parseDeviceCodePrompt("! First copy your one-time code: ABCD-9999"))
    }

    @Test
    fun returnsNullUntilTheCodeArrives() {
        assertNull(parseDeviceCodePrompt("droid@debian:~/Projects$ copilot login --device-code"))
    }
}
