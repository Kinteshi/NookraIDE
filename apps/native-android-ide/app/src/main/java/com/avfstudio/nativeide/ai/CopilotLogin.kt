package com.avfstudio.nativeide.ai

/** The one-time code `copilot login --device-code` asks the user to enter at [url]. */
data class DeviceCodePrompt(val url: String, val code: String)

/** Command the chat Sign-in runs in a terminal. The guest has a display server, so a plain
 *  `copilot login` picks the browser flow with a loopback callback inside the guest; the device
 *  flow needs no port forwarding. The shell exits only when login succeeds. */
const val COPILOT_LOGIN_COMMAND = "copilot login --device-code && exit"

private val ANSI_ESCAPE = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\)|[@-Z\\-_])""")
private val DEVICE_PROMPT = Regex("""(https://\S+/login/device)\S*?\s+and enter (?:the )?code\s+([A-Z0-9]{4}-[A-Z0-9]{4})""")

fun stripAnsi(text: String): String = ANSI_ESCAPE.replace(text, "")

/** Finds the device-code prompt in terminal output, e.g.
 *  `To authenticate, visit https://github.com/login/device and enter code ABCD-1234`. */
fun parseDeviceCodePrompt(output: String): DeviceCodePrompt? =
    DEVICE_PROMPT.find(stripAnsi(output))?.let { match ->
        DeviceCodePrompt(match.groupValues[1], match.groupValues[2])
    }
