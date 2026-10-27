package com.avfstudio.nativeide.ai

/** The one-time code `copilot login --device-code` asks the user to enter at [url]. */
data class DeviceCodePrompt(val url: String, val code: String)

/** Command the chat Sign-in runs in a terminal. The guest has a display server, so a plain
 *  `copilot login` picks the browser flow with a loopback callback inside the guest; the device
 *  flow needs no port forwarding. The shell exits only when login succeeds. */
const val COPILOT_LOGIN_COMMAND = "copilot login --device-code && exit"

/**
 * One GitHub sign-in for Copilot chat and Git, through the GitHub CLI: Copilot CLI falls back to
 * `gh auth token`, and `gh auth setup-git` makes Git use the same login. Installs `gh` on first
 * use; GH_BROWSER=true makes gh's "press Enter to open the browser" a no-op (the app shows the
 * code and opens the page on Android). Git's name and email are filled in only when unset, with
 * the account's private noreply address. The shell exits only when everything succeeded.
 */
const val GITHUB_SIGN_IN_COMMAND =
    "avf-native-daemon tools install gh >/dev/null && " +
        "GH_BROWSER=true gh auth login --hostname github.com --git-protocol https --web && " +
        "gh auth setup-git --hostname github.com && " +
        "login=\$(gh api user --jq .login) && id=\$(gh api user --jq .id) && name=\$(gh api user --jq '.name // .login') && " +
        "{ git config --global user.name >/dev/null || git config --global user.name \"\$name\"; } && " +
        "{ git config --global user.email >/dev/null || git config --global user.email \"\$id+\$login@users.noreply.github.com\"; } && " +
        "echo \"Signed in to GitHub as \$login; Copilot chat and Git use this account.\" && exit"

private val ANSI_ESCAPE = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\)|[@-Z\\-_])""")
private val DEVICE_PROMPT = Regex("""(https://\S+/login/device)\S*?\s+and enter (?:the )?code\s+([A-Z0-9]{4}-[A-Z0-9]{4})""")
/** gh: "! First copy your one-time code: ABCD-1234" then "Press Enter to open https://github.com/login/device …". */
private val GH_CODE = Regex("""one-time code:\s+([A-Z0-9]{4}-[A-Z0-9]{4})""")
private val DEVICE_URL = Regex("""https://[^\s"']+/login/device""")

fun stripAnsi(text: String): String = ANSI_ESCAPE.replace(text, "")

/** Finds the device-code prompt in terminal output, e.g.
 *  `To authenticate, visit https://github.com/login/device and enter code ABCD-1234`. */
fun parseDeviceCodePrompt(output: String): DeviceCodePrompt? {
    val text = stripAnsi(output)
    DEVICE_PROMPT.find(text)?.let { match -> return DeviceCodePrompt(match.groupValues[1], match.groupValues[2]) }
    val code = GH_CODE.find(text)?.groupValues?.get(1) ?: return null
    return DeviceCodePrompt(DEVICE_URL.find(text)?.value ?: "https://github.com/login/device", code)
}
