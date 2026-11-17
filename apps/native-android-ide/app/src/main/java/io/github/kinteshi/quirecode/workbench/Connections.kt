package io.github.kinteshi.quirecode.workbench

/**
 * Where the app stands with its daemon (G22), in one place for the top bar's chip, the banner under it and the
 * Connections dialog, so they say the same thing in the same voice: what's happening, and that the app still works.
 */
enum class ConnectionState {
    /** The workspace is on this device; no connection is needed. */
    OnDevice,
    /** Nothing paired yet: the first run. */
    NotSetUp,
    /** A saved connection, not connected, nothing tried yet or auto-connect off. */
    Idle,
    Connecting,
    Connected,
    /** It was connected, and went away; the workspace stays usable offline with an explicit reconnect. */
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
    /** The open workspace is a device folder, not a disconnected guest workspace. */
    val workingOnDevice: Boolean = false,
    /** Active connected route when the profile defines multiple routes (G37). */
    val activeRoute: ConnectionRoute? = null,
    /** Number of configured routes on the active profile (G37). */
    val routeCount: Int = 1,
)

/** The [ConnectionState] for [facts], the most specific first. */
fun connectionState(facts: ConnectionFacts): ConnectionState = with(facts) {
    when {
        workingOnDevice -> ConnectionState.OnDevice
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
        ConnectionState.OnDevice -> ConnectionSummary(
            state, "On this device", "Working on this device",
            if (facts.workingOffline) {
                "This kept-offline folder uses a copy on this device; edits stay here and sync when its guest is available."
            } else {
                "This folder is on this device; it doesn't need a Linux connection."
            },
        )
        ConnectionState.NotSetUp -> ConnectionSummary(
            state, "Set up", "Not set up yet",
            "QuireCode works on a Linux machine: this tablet's Linux guest, or a computer on your network. Set one up once; " +
                "afterwards the app connects by itself.",
        )
        ConnectionState.Idle -> ConnectionSummary(state, "Connect", "Not connected", "Connect to ${facts.name} to open its files.")
        ConnectionState.Connecting -> ConnectionSummary(state, "Connecting…", "Connecting to ${facts.name}", null)
        ConnectionState.Connected -> {
            val routeSuffix = facts.activeRoute?.takeIf { !facts.tabletGuest && facts.routeCount > 1 }
                ?.let { " · ${it.shortLabel()}" }.orEmpty()
            ConnectionSummary(state, facts.name + routeSuffix, "Connected to ${facts.name}", null)
        }
        ConnectionState.Lost -> ConnectionSummary(
            state, "Offline", "${where.replaceFirstChar(Char::uppercase)} stopped at ${facts.stoppedAt}",
            (if (facts.tabletGuest) "Open Linux Terminal to start it again, then tap Reconnect. " else "When it's back, tap Reconnect. ") +
                meanwhile,
        )
        ConnectionState.Unreachable -> ConnectionSummary(
            state, "Offline", "Can't reach ${where}",
            (if (facts.tabletGuest) "Open Linux Terminal so the guest starts, then tap Reconnect. " else "Check that the computer is on and its daemon runs, then tap Reconnect. ") +
                meanwhile,
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

/** A saved remote profile is either the tablet's guest or a computer; device folders aren't profiles. */
enum class ConnectionKind(val label: String) {
    TabletGuest("Linux guest"),
    Computer("Computer"),
    Device("This device"),
}

/** Route inferred from the saved endpoint; labels avoid claiming a route that the URL cannot establish. */
enum class ConnectionRoute(val label: String) {
    LinuxTerminal("Linux Terminal"),
    Wifi("Wi-Fi / local network"),
    Tailscale("Tailscale"),
    Tunnel("Cloudflare Tunnel"),
    LocalForward("Local forward (ADB/SSH)"),
    SecureWebSocket("Secure WebSocket"),
    WebSocket("WebSocket"),
}

internal fun ConnectionRoute.shortLabel(): String = when (this) {
    ConnectionRoute.LinuxTerminal -> "Guest"
    ConnectionRoute.Wifi -> "Wi-Fi"
    ConnectionRoute.Tailscale -> "Tailscale"
    ConnectionRoute.Tunnel -> "Tunnel"
    ConnectionRoute.LocalForward -> "Local"
    ConnectionRoute.SecureWebSocket -> "TLS"
    ConnectionRoute.WebSocket -> "WS"
}

/**
 * Orders [routes] for [preferred] and the current network state (G37):
 * if [preferred] is specific, matching routes go first; under Auto, local Wi-Fi/LAN goes first at home
 * (`onLocalNetwork`), and Tailscale (or tunnel if no VPN) goes first away from home.
 */
internal fun orderProfileRoutes(
    routes: List<io.github.kinteshi.quirecode.transport.ProfileRoute>,
    preferred: io.github.kinteshi.quirecode.transport.RoutePreference,
    onLocalNetwork: Boolean,
    vpnActive: Boolean,
): List<io.github.kinteshi.quirecode.transport.ProfileRoute> {
    if (routes.size <= 1) return routes
    fun matchesPreference(route: ConnectionRoute): Boolean = when (preferred) {
        io.github.kinteshi.quirecode.transport.RoutePreference.Auto -> false
        io.github.kinteshi.quirecode.transport.RoutePreference.Wifi -> route == ConnectionRoute.Wifi || route == ConnectionRoute.LocalForward
        io.github.kinteshi.quirecode.transport.RoutePreference.Tailscale -> route == ConnectionRoute.Tailscale
        io.github.kinteshi.quirecode.transport.RoutePreference.Tunnel -> route == ConnectionRoute.Tunnel || route == ConnectionRoute.SecureWebSocket
    }
    fun autoRank(route: ConnectionRoute): Int = when {
        onLocalNetwork -> when (route) {
            ConnectionRoute.Wifi, ConnectionRoute.LocalForward -> 0
            ConnectionRoute.Tailscale -> 1
            ConnectionRoute.Tunnel, ConnectionRoute.SecureWebSocket -> 2
            else -> 3
        }
        vpnActive -> when (route) {
            ConnectionRoute.Tailscale -> 0
            ConnectionRoute.Tunnel, ConnectionRoute.SecureWebSocket -> 1
            ConnectionRoute.Wifi, ConnectionRoute.LocalForward -> 3
            else -> 2
        }
        else -> when (route) {
            ConnectionRoute.Tunnel, ConnectionRoute.SecureWebSocket -> 0
            ConnectionRoute.Tailscale -> 1
            ConnectionRoute.Wifi, ConnectionRoute.LocalForward -> 2
            else -> 3
        }
    }
    return routes.withIndex()
        .sortedWith(
            compareBy<IndexedValue<io.github.kinteshi.quirecode.transport.ProfileRoute>>(
                { if (matchesPreference(connectionRoute(it.value.endpoint))) 0 else 1 },
                { autoRank(connectionRoute(it.value.endpoint)) },
                { it.index },
            ),
        )
        .map { it.value }
}

/** Route preference choices relevant to [profile]'s configured endpoints (G37). */
internal fun availableRoutePreferences(
    profile: io.github.kinteshi.quirecode.transport.ConnectionProfile,
): List<io.github.kinteshi.quirecode.transport.RoutePreference> {
    val routes = profile.allRoutes().map { connectionRoute(it.endpoint) }.toSet()
    return buildList {
        add(io.github.kinteshi.quirecode.transport.RoutePreference.Auto)
        if (ConnectionRoute.Wifi in routes || ConnectionRoute.LocalForward in routes) {
            add(io.github.kinteshi.quirecode.transport.RoutePreference.Wifi)
        }
        if (ConnectionRoute.Tailscale in routes) {
            add(io.github.kinteshi.quirecode.transport.RoutePreference.Tailscale)
        }
        if (ConnectionRoute.Tunnel in routes || ConnectionRoute.SecureWebSocket in routes) {
            add(io.github.kinteshi.quirecode.transport.RoutePreference.Tunnel)
        }
    }
}

fun connectionKind(endpoint: String): ConnectionKind =
    if (isTabletGuestEndpoint(endpoint)) ConnectionKind.TabletGuest else ConnectionKind.Computer

fun connectionRoute(endpoint: String): ConnectionRoute {
    if (isTabletGuestEndpoint(endpoint)) return ConnectionRoute.LinuxTerminal
    val uri = runCatching { java.net.URI(endpoint.trim()) }.getOrNull()
        ?: return if (endpoint.isBlank()) ConnectionRoute.LinuxTerminal else ConnectionRoute.WebSocket
    val host = uri.host?.lowercase() ?: return ConnectionRoute.WebSocket
    if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]") {
        return ConnectionRoute.LocalForward
    }
    if (host.endsWith(".ts.net") || host.ipv4InTailscaleRange()) return ConnectionRoute.Tailscale
    if (host.endsWith(".trycloudflare.com") || host.endsWith(".cfargotunnel.com")) return ConnectionRoute.Tunnel
    if (host.endsWith(".local") || host.ipv4IsPrivate()) return ConnectionRoute.Wifi
    return if (uri.scheme.equals("wss", ignoreCase = true)) {
        ConnectionRoute.SecureWebSocket
    } else {
        ConnectionRoute.WebSocket
    }
}

private fun String.ipv4Octets(): List<Int>? {
    val parts = split('.')
    if (parts.size != 4) return null
    return parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
}

internal fun String.ipv4InTailscaleRange(): Boolean =
    ipv4Octets()?.let { (a, b) -> a == 100 && b in 64..127 } ?: false

private fun String.ipv4IsPrivate(): Boolean =
    ipv4Octets()?.let { (a, b) ->
        a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    } ?: false

/**
 * A connection to this tablet's own guest (Linux Terminal forwards it to localhost, unencrypted) rather than to a
 * computer. A `wss://` connection is a computer's even on localhost (through a tunnel: adb, SSH).
 */
fun isTabletGuestEndpoint(endpoint: String): Boolean {
    if (endpoint.trim().startsWith("wss://", ignoreCase = true)) return false
    val uri = runCatching { java.net.URI(endpoint.trim()) }.getOrNull() ?: return endpoint.isBlank()
    val host = uri.host ?: return endpoint.isBlank()
    return uri.port == TABLET_GUEST_DAEMON_PORT &&
        (host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]")
}

private const val TABLET_GUEST_DAEMON_PORT = 19870

/** The host and port a `ws://` or `wss://` endpoint connects to, for the Connections dialog's check; null if it isn't one. */
fun endpointHostPort(endpoint: String): Pair<String, Int>? = runCatching {
    val uri = java.net.URI(endpoint.trim())
    val scheme = uri.scheme?.lowercase()
    if (scheme != "ws" && scheme != "wss") return null
    val port = if (uri.port > 0) uri.port else if (scheme == "wss") 443 else 80
    (uri.host ?: return null) to port
}.getOrNull()

/** Returns why [url] cannot be used as a connection endpoint, or null when it is valid. */
fun connectionUrlProblem(url: String): String? {
    val trimmed = url.trim()
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return "Enter a valid ws:// or wss:// WebSocket URL"
    val scheme = uri.scheme?.lowercase()
    if (scheme != "ws" && scheme != "wss") return "URL must start with ws:// or wss://"
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return "Enter a valid host in the WebSocket URL"
    if (scheme == "ws" && host !in setOf("localhost", "127.0.0.1", "::1", "[::1]")) {
        return "Remote connections require wss:// (ws:// is only allowed on localhost)"
    }
    return null
}

/** The Connections dialog's checks (G22, G31, G38): null where it doesn't apply or isn't known yet. */
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
    /** Route inferred for the endpoint being checked (G38). */
    val route: ConnectionRoute = ConnectionRoute.LinuxTerminal,
    /** Whether a VPN or Tailscale interface (`100.64.0.0/10`) is active on this tablet (G38). */
    val vpnActive: Boolean? = null,
    /** Whether the Tailscale app is installed on this tablet (G38). */
    val tailscaleInstalled: Boolean = false,
    /** Whether this tablet has an active Wi-Fi or Ethernet connection (G38). */
    val onLocalNetwork: Boolean? = null,
    /** Whether this tablet is on mobile data without Wi-Fi/Ethernet (G38). */
    val onCellularOnly: Boolean? = null,
)

/** Route-aware Troubleshoot diagnosis for [check] when the daemon is not connected (G38). */
fun troubleshootDiagnosis(check: ConnectionCheck, tabletGuest: Boolean): String {
    val target = check.target ?: "the daemon's port"
    if (check.checking) return "Checking $target…"
    if (check.daemonAnswers == true) return "The daemon answers at $target"
    return when {
        tabletGuest || check.route == ConnectionRoute.LinuxTerminal -> if (check.portOpen == true) {
            "Port $target is open, but nothing answers through it: the daemon isn't running in the guest, or Linux " +
                "Terminal's port forwarding stopped (turning on a VPN such as Tailscale can do this). Open Linux Terminal; if " +
                "it's already open, force stop it in App info and open it again."
        } else {
            "Nothing answers at $target: the guest isn't running, or Linux Terminal isn't forwarding the port. Open Linux " +
                "Terminal; if Android asks to open port 19870, accept it."
        }
        check.route == ConnectionRoute.Tailscale -> when {
            check.vpnActive == false -> "Tailscale isn't connected on this tablet: turn on Tailscale to reach $target."
            check.portOpen == true -> "Port $target is open over Tailscale, but the daemon didn't answer: check that quirecode-daemon is running on the computer."
            else -> "Nothing answers at $target over Tailscale: check that the computer is on, connected to Tailscale, and running quirecode-daemon."
        }
        check.route == ConnectionRoute.Wifi -> when {
            check.onCellularOnly == true || check.onLocalNetwork == false ->
                "You're away from your Wi-Fi network: connect to the same Wi-Fi as the computer, or use Tailscale."
            check.portOpen == true -> "Port $target is open on the local network, but the daemon didn't answer: check that quirecode-daemon is running on the computer."
            else -> "Nothing answers at $target on the local network: check that the computer is on the same Wi-Fi, quirecode-daemon is running, and the port is open in its firewall."
        }
        check.route == ConnectionRoute.Tunnel -> if (check.portOpen == true) {
            "The tunnel reached $target, but the daemon didn't answer behind it: check that cloudflared and quirecode-daemon are running on the computer."
        } else {
            "Couldn't reach the tunnel at $target: check your internet connection and that cloudflared and quirecode-daemon are running on the computer."
        }
        check.route == ConnectionRoute.LocalForward -> if (check.portOpen == true) {
            "Local port $target is open, but nothing answered through the forward: check that quirecode-daemon is running on the computer."
        } else {
            "Nothing listens on local port $target: check that your ADB reverse or SSH port forward is active."
        }
        else -> if (check.portOpen == true) {
            "Port $target is open, but the daemon didn't answer through it: check that quirecode-daemon is running on the computer."
        } else {
            "Nothing answers at $target: check that the computer is reachable, quirecode-daemon is running, and its port is open."
        }
    }
}

/** Startup connection and workspace restore plan (G39). */
data class StartupConnectionPlan(
    val selectedProfileId: String?,
    val connectProfileId: String?,
    val fallbackProfileIds: List<String>,
    val openDeviceWorkspace: String? = null,
    val openOfflineGuestWorkspace: RecentWorkspace? = null,
)

/**
 * Resolves which profile and workspace take priority on launch (G39):
 * the last workspace and its owning connection stay primary and never silently switch to another daemon.
 */
fun planStartupConnection(
    profiles: List<io.github.kinteshi.quirecode.transport.ConnectionProfile>,
    lastUsedProfileId: String?,
    lastWorkspace: RecentWorkspace?,
    offlineByChoice: Boolean,
    autoConnectSetting: Boolean,
    offlineWorkspaceSetting: Boolean,
): StartupConnectionPlan {
    val defaultProfileId = profiles.firstOrNull { it.id == lastUsedProfileId }?.id
        ?: profiles.firstOrNull { it.autoConnect }?.id
        ?: profiles.firstOrNull()?.id
    if (lastWorkspace != null && lastWorkspace.isDeviceFolder) {
        return StartupConnectionPlan(
            selectedProfileId = defaultProfileId,
            connectProfileId = null,
            fallbackProfileIds = emptyList(),
            openDeviceWorkspace = lastWorkspace.path,
        )
    }
    if (lastWorkspace != null && lastWorkspace.isGuestFolder) {
        val owner = profiles.firstOrNull { it.id == lastWorkspace.connectionId }
            ?: if (lastWorkspace.connectionId == null) profiles.firstOrNull { it.id == defaultProfileId } else null
        val selectedId = owner?.id ?: defaultProfileId
        val shouldConnect = owner != null && !offlineByChoice && autoConnectSetting && owner.autoConnect
        val shouldOpenOffline = offlineByChoice || offlineWorkspaceSetting || owner == null
        return StartupConnectionPlan(
            selectedProfileId = selectedId,
            connectProfileId = owner?.id?.takeIf { shouldConnect },
            fallbackProfileIds = emptyList(),
            openOfflineGuestWorkspace = lastWorkspace.copy(connectionId = owner?.id ?: lastWorkspace.connectionId)
                .takeIf { shouldOpenOffline },
        )
    }
    val autoProfiles = if (autoConnectSetting && !offlineByChoice) profiles.filter { it.autoConnect } else emptyList()
    val selectedId = autoProfiles.firstOrNull()?.id ?: defaultProfileId
    return StartupConnectionPlan(
        selectedProfileId = selectedId,
        connectProfileId = autoProfiles.firstOrNull()?.id,
        fallbackProfileIds = autoProfiles.drop(1).map { it.id },
    )
}

/**
 * Probes [endpoint]'s port (G31), returning `(portOpen, daemonAnswers)`:
 * - `(false, false)`: nothing is listening on the TCP port.
 * - `(true, false)`: the TCP port accepted the connection (Linux Terminal's forwarder or a tunnel),
 *   but nothing answered `GET /health` through it.
 * - `(true, true)`: the daemon answered `GET /health` over the port.
 */
fun probeDaemonPort(
    endpoint: String,
    fingerprint: String? = null,
    timeoutMs: Int = 2_000,
    extraHeaders: Map<String, String> = emptyMap(),
): Pair<Boolean, Boolean> {
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
                val extraLines = extraHeaders.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }
                val request = "GET /health HTTP/1.1\r\nHost: $host:$port\r\n${extraLines}Connection: close\r\n\r\n"
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
