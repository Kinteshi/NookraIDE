package io.github.kinteshi.quirecode.workbench

/**
 * Where the app stands with its daemon (G22), in one place for the top bar's chip, the banner under it and the
 * Connections dialog, so they say the same thing in the same voice: what's happening, and that the app still works.
 */
enum class ConnectionState {
    /** Nothing paired yet: the first run. */
    NotSetUp,
    /** A saved connection, not connected, nothing tried yet or auto-connect off. */
    Idle,
    Connecting,
    Connected,
    /** It was connected, and went away; the app retries by itself. */
    Lost,
    /** It couldn't connect: the daemon doesn't answer. */
    Unreachable,
    /** The daemon doesn't answer twice in a row while Linux Terminal is probably open: its port forwarding stuck. */
    ForwardingStuck,
    /** The daemon answered but refused this device's token: Linux Terminal reset or the daemon reinstalled. */
    PairingRejected,
    /** Another failure, with its message. */
    Failed,
}

/** What the chip, banner and dialog say for a [ConnectionState]. */
data class ConnectionSummary(
    val state: ConnectionState,
    /** The chip's few words. */
    val chip: String,
    /** The headline in the banner and the dialog. */
    val title: String,
    /** A sentence or two of what it means and what to do; null when the title says it all. */
    val detail: String?,
)

/** What [connectionSummary] reads; plain values, so it's testable apart from the controller. */
data class ConnectionFacts(
    val hasConnections: Boolean,
    val connected: Boolean,
    val connecting: Boolean,
    /** The connection's name ("Linux guest", a computer's host). */
    val name: String,
    /** The connection is this tablet's Linux guest (Linux Terminal), not a computer. */
    val tabletGuest: Boolean,
    /** When a connected guest went away, formatted for people; null if it didn't. */
    val stoppedAt: String? = null,
    val unreachable: Boolean = false,
    val forwardingStuck: Boolean = false,
    val pairingRejected: Boolean = false,
    /** The last failure's message, for [ConnectionState.Failed]. */
    val failure: String? = null,
    /** A folder is open from this device's copies while away. */
    val workingOffline: Boolean = false,
)

/** The [ConnectionState] for [facts], the most specific first. */
fun connectionState(facts: ConnectionFacts): ConnectionState = with(facts) {
    when {
        connected -> ConnectionState.Connected
        connecting -> ConnectionState.Connecting
        !hasConnections -> ConnectionState.NotSetUp
        pairingRejected -> ConnectionState.PairingRejected
        forwardingStuck -> ConnectionState.ForwardingStuck
        stoppedAt != null -> ConnectionState.Lost
        unreachable -> ConnectionState.Unreachable
        failure != null -> ConnectionState.Failed
        else -> ConnectionState.Idle
    }
}

/** What to say about [facts]: calm, and that the app keeps working (edits stay on this device). */
fun connectionSummary(facts: ConnectionFacts): ConnectionSummary {
    val state = connectionState(facts)
    val where = if (facts.tabletGuest) "the Linux guest" else facts.name
    val meanwhile = if (facts.workingOffline) {
        "Meanwhile you're working offline: files open from this device's copies, and saves wait for the connection."
    } else {
        "Files you open from this device still work meanwhile."
    }
    return when (state) {
        ConnectionState.NotSetUp -> ConnectionSummary(
            state, "Set up", "Not set up yet",
            "QuireCode works on a Linux machine: this tablet's Linux guest, or a computer on your network. Set one up once; " +
                "afterwards the app connects by itself.",
        )
        ConnectionState.Idle -> ConnectionSummary(state, "Connect", "Not connected", "Connect to ${facts.name} to open its files.")
        ConnectionState.Connecting -> ConnectionSummary(state, "Connecting…", "Connecting to ${facts.name}", null)
        ConnectionState.Connected -> ConnectionSummary(state, facts.name, "Connected to ${facts.name}", null)
        ConnectionState.Lost -> ConnectionSummary(
            state, "Offline", "${where.replaceFirstChar(Char::uppercase)} stopped at ${facts.stoppedAt}",
            (if (facts.tabletGuest) "Open Linux Terminal to start it again; " else "When it's back, ") +
                "QuireCode reconnects by itself. $meanwhile",
        )
        ConnectionState.Unreachable -> ConnectionSummary(
            state, "Offline", "Can't reach ${where}",
            (if (facts.tabletGuest) "Open Linux Terminal so the guest starts; " else "Check that the computer is on and its daemon runs; ") +
                "QuireCode reconnects by itself. $meanwhile",
        )
        ConnectionState.ForwardingStuck -> ConnectionSummary(
            state, "Offline", "The guest doesn't answer",
            "If Linux Terminal is already open, it may have stopped forwarding the connection (turning on a VPN such as Tailscale can do this). " +
                "Force stop it in App info (that restarts the guest) and open it again. $meanwhile",
        )
        ConnectionState.PairingRejected -> ConnectionSummary(
            state, "Pair again", "${where.replaceFirstChar(Char::uppercase)} needs pairing again",
            if (facts.tabletGuest) {
                "It no longer accepts this device's pairing, which happens after Linux Terminal is reset or the daemon is " +
                    "reinstalled. Pair again with a new code; your files and settings are kept."
            } else {
                "It no longer accepts this device's pairing: this device was revoked there, or its daemon was reinstalled. " +
                    "Pair again with a new code from `quirecode-daemon pair --remote`; your files and settings are kept."
            },
        )
        ConnectionState.Failed -> ConnectionSummary(
            state, "Offline", "Couldn't connect to ${facts.name}", listOfNotNull(facts.failure?.trimEnd()?.let { if (it.endsWith('.')) it else "$it." }, meanwhile).joinToString(" "),
        )
    }
}

/**
 * A connection to this tablet's own guest (Linux Terminal forwards it to localhost, unencrypted) rather than to a
 * computer. A `wss://` connection is a computer's even on localhost (through a tunnel: adb, SSH).
 */
fun isTabletGuestEndpoint(endpoint: String): Boolean {
    if (endpoint.trim().startsWith("wss://", ignoreCase = true)) return false
    val host = runCatching { java.net.URI(endpoint.trim()).host }.getOrNull() ?: return endpoint.isBlank()
    return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
}

/** The host and port a `ws://` or `wss://` endpoint connects to, for the Connections dialog's check; null if it isn't one. */
fun endpointHostPort(endpoint: String): Pair<String, Int>? = runCatching {
    val uri = java.net.URI(endpoint.trim())
    val scheme = uri.scheme?.lowercase()
    if (scheme != "ws" && scheme != "wss") return null
    val port = if (uri.port > 0) uri.port else if (scheme == "wss") 443 else 80
    (uri.host ?: return null) to port
}.getOrNull()

/** The Connections dialog's checks (G22, G31): null where it doesn't apply or isn't known yet. */
data class ConnectionCheck(
    /** Linux Terminal can be opened (only asked for the tablet's guest). */
    val terminalAvailable: Boolean?,
    /** Something answers HTTP on the daemon's port. */
    val daemonAnswers: Boolean?,
    /** "127.0.0.1:19870". */
    val target: String?,
    val checking: Boolean,
    /**
     * Whether the TCP port accepted a socket connection at all (G31). When [portOpen] is true and
     * [daemonAnswers] is false on the tablet, Linux Terminal's forwarder accepted `127.0.0.1:19870`
     * on the host side, but nothing reached `quirecode-daemon` in the guest (e.g. after toggling a VPN).
     */
    val portOpen: Boolean? = null,
)

/**
 * Probes [endpoint]'s port (G31), returning `(portOpen, daemonAnswers)`:
 * - `(false, false)`: nothing is listening on the TCP port.
 * - `(true, false)`: the TCP port accepted the connection (Linux Terminal's forwarder or a tunnel),
 *   but nothing answered `GET /health` through it.
 * - `(true, true)`: the daemon answered `GET /health` over the port.
 */
fun probeDaemonPort(endpoint: String, fingerprint: String? = null, timeoutMs: Int = 2_000): Pair<Boolean, Boolean> {
    val (host, port) = endpointHostPort(endpoint) ?: return false to false
    val tls = endpoint.trim().startsWith("wss://", ignoreCase = true)
    return try {
        java.net.Socket().use { tcp ->
            tcp.soTimeout = timeoutMs
            tcp.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            // TCP connected: the port itself is open. Now check whether the daemon answers /health through it.
            val answers = runCatching {
                val streamSocket: java.net.Socket = if (tls) {
                    val factory = if (fingerprint != null) {
                        val trust = io.github.kinteshi.quirecode.transport.PinnedCertificateTrustManager(fingerprint)
                        javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
                    } else {
                        javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                    }
                    (factory.createSocket(tcp, host, port, true) as javax.net.ssl.SSLSocket).apply {
                        soTimeout = timeoutMs
                        startHandshake()
                    }
                } else {
                    tcp
                }
                val request = "GET /health HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n"
                streamSocket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val statusLine = java.io.BufferedReader(java.io.InputStreamReader(streamSocket.getInputStream(), Charsets.US_ASCII)).readLine()
                statusLine != null &&
                    statusLine.startsWith("HTTP/") &&
                    !statusLine.contains(" 502 ") &&
                    !statusLine.contains(" 503 ") &&
                    !statusLine.contains(" 504 ") &&
                    !statusLine.contains(" 530 ")
            }.getOrDefault(false)
            true to answers
        }
    } catch (_: Exception) {
        false to false
    }
}
