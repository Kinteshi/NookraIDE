package io.github.kinteshi.quirecode.workbench

/**
 * What Copy Diagnostics puts on the clipboard (R9): the facts a bug report needs, as plain text
 * for the user to read and paste. Nothing is sent anywhere; the app has no telemetry.
 */
data class DiagnosticsInfo(
    val appVersion: String,
    val versionCode: Long,
    val debuggable: Boolean,
    val editor: String,
    val android: String,
    val sdk: Int,
    val securityPatch: String,
    val device: String,
    val abis: List<String>,
    val linuxTerminal: String?,
    val window: String,
    val memory: String,
    val connected: Boolean,
    val endpoint: String,
    val daemonVersion: String?,
    val daemonStatus: String?,
    val guestStoppedAt: String?,
    val languageServers: List<Pair<String, String>>,
    val notebooks: List<Pair<String, String>>,
    val recentMessages: List<String>,
)

/** How many of the Output panel's messages go into the report. */
const val DIAGNOSTICS_RECENT_MESSAGES = 30

/**
 * The report as Markdown-friendly text. [secrets] (the connection token) are replaced wherever
 * they appear, and the endpoint keeps only its host and port.
 */
fun diagnosticsReport(info: DiagnosticsInfo, secrets: List<String> = emptyList()): String {
    val text = buildString {
        appendLine("Nookra IDE diagnostics")
        appendLine()
        appendLine("App: ${info.appVersion} (${info.versionCode})${if (info.debuggable) ", debug build" else ""}")
        appendLine("Editor: ${info.editor}")
        appendLine("Android: ${info.android} (API ${info.sdk}), security patch ${info.securityPatch.ifBlank { "unknown" }}")
        appendLine("Device: ${info.device}, ${info.abis.joinToString().ifBlank { "unknown ABI" }}")
        appendLine("Linux Terminal: ${info.linuxTerminal ?: "not installed"}")
        appendLine("Window: ${info.window}")
        appendLine("Memory: ${info.memory}")
        appendLine()
        appendLine("Guest: ${if (info.connected) "connected" else "disconnected"}${endpointHost(info.endpoint)?.let { " ($it)" } ?: ""}")
        appendLine("Guest daemon: ${info.daemonVersion ?: "unknown"}${info.daemonStatus?.let { ", $it" } ?: ""}")
        info.guestStoppedAt?.let { appendLine("Guest went away at: $it") }
        if (info.languageServers.isNotEmpty()) {
            appendLine("Language servers: " + info.languageServers.joinToString { (id, state) -> "$id $state" })
        }
        if (info.notebooks.isNotEmpty()) {
            appendLine("Notebook kernels: " + info.notebooks.joinToString { (name, state) -> "$name $state" })
        }
        appendLine()
        if (info.recentMessages.isEmpty()) {
            appendLine("Recent messages: none")
        } else {
            appendLine("Recent messages (oldest first):")
            info.recentMessages.takeLast(DIAGNOSTICS_RECENT_MESSAGES).forEach { appendLine("  ${it.replace('\n', ' ')}") }
        }
    }
    return secrets.filter { it.length >= 4 }.fold(text.trimEnd() + "\n") { acc, secret -> acc.replace(secret, "<redacted>") }
}

/** `ws://127.0.0.1:19870/path?x` to `127.0.0.1:19870`; null when there's none. */
internal fun endpointHost(endpoint: String): String? {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@').ifEmpty { null }
}
