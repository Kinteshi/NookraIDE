package io.github.kinteshi.quirecode.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsTest {
    private val guest = ConnectionFacts(hasConnections = true, connected = false, connecting = false, name = "Linux guest", tabletGuest = true)

    @Test
    fun theMostSpecificStateWins() {
        assertEquals(ConnectionState.NotSetUp, connectionState(guest.copy(hasConnections = false)))
        assertEquals(ConnectionState.Idle, connectionState(guest))
        assertEquals(ConnectionState.Connected, connectionState(guest.copy(connected = true, unreachable = true)))
        assertEquals(ConnectionState.Connecting, connectionState(guest.copy(connecting = true, stoppedAt = "10:42")))
        assertEquals(ConnectionState.PairingRejected, connectionState(guest.copy(pairingRejected = true, unreachable = true)))
        assertEquals(ConnectionState.ForwardingStuck, connectionState(guest.copy(forwardingStuck = true, stoppedAt = "10:42", unreachable = true)))
        assertEquals(ConnectionState.Lost, connectionState(guest.copy(stoppedAt = "10:42", unreachable = true)))
        assertEquals(ConnectionState.Unreachable, connectionState(guest.copy(unreachable = true)))
        assertEquals(ConnectionState.Failed, connectionState(guest.copy(failure = "Handshake timed out")))
    }

    @Test
    fun summariesAreCalmAndSayTheAppStillWorks() {
        val lost = connectionSummary(guest.copy(stoppedAt = "10:42", workingOffline = true))
        assertEquals("Offline", lost.chip)
        assertEquals("The Linux guest stopped at 10:42", lost.title)
        assertTrue(lost.detail!!.contains("Open Linux Terminal"))
        assertTrue(lost.detail!!.contains("Reconnect"))
        assertTrue(lost.detail!!.contains("working offline"))
        val computer = connectionSummary(guest.copy(name = "192.168.1.10", tabletGuest = false, unreachable = true))
        assertEquals("Can't reach 192.168.1.10", computer.title)
        assertFalse(computer.detail!!.contains("Linux Terminal"))
        assertTrue(computer.detail!!.contains("Reconnect"))
        assertEquals("Pair again", connectionSummary(guest.copy(pairingRejected = true)).chip)
        assertEquals("Linux guest", connectionSummary(guest.copy(connected = true)).chip)
        assertNull(connectionSummary(guest.copy(connected = true)).detail)
        assertEquals("Set up", connectionSummary(guest.copy(hasConnections = false)).chip)
        assertTrue(connectionSummary(guest.copy(failure = "Handshake timed out")).detail!!.startsWith("Handshake timed out. "))
        assertTrue(connectionSummary(guest.copy(name = "100.64.0.1", tabletGuest = false, pairingRejected = true)).detail!!.contains("revoked"))
        assertTrue(connectionSummary(guest.copy(pairingRejected = true)).detail!!.contains("Linux Terminal is reset"))
        assertTrue(connectionSummary(guest.copy(forwardingStuck = true)).detail!!.contains("Tailscale"))
    }

    @Test
    fun probeDaemonPortDistinguishesRefusedFromOpenWithoutAnswerAndAnswering() {
        // 1. Refused port: nothing listening.
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(false to false, probeDaemonPort("ws://127.0.0.1:$closedPort/ws", timeoutMs = 500))

        // 2. Port open, but closes without sending HTTP (e.g. wedged Linux Terminal port forwarder).
        java.net.ServerSocket(0).use { silentServer ->
            val thread = Thread {
                runCatching { silentServer.accept().close() }
            }.apply { isDaemon = true; start() }
            assertEquals(true to false, probeDaemonPort("ws://127.0.0.1:${silentServer.localPort}/ws", timeoutMs = 500))
            thread.join(1_000)
        }

        // 3. Port open and daemon answers /health with HTTP 200 OK.
        java.net.ServerSocket(0).use { healthServer ->
            val thread = Thread {
                runCatching {
                    healthServer.accept().use { client ->
                        val out = client.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }
            assertEquals(true to true, probeDaemonPort("ws://127.0.0.1:${healthServer.localPort}/ws", timeoutMs = 500))
            thread.join(1_000)
        }
    }

    @Test
    fun tabletGuestEndpointsAndPorts() {
        assertTrue(isTabletGuestEndpoint(GUEST_DAEMON_ENDPOINT))
        assertTrue(isTabletGuestEndpoint("ws://localhost:19870/ws"))
        assertTrue(isTabletGuestEndpoint(""))
        assertFalse(isTabletGuestEndpoint("ws://127.0.0.1:39495/ws"))
        assertFalse(isTabletGuestEndpoint("wss://192.168.1.10:19870/ws"))
        // Encrypted means a computer, even through a tunnel on localhost.
        assertFalse(isTabletGuestEndpoint("wss://127.0.0.1:19871/ws"))
        assertEquals("127.0.0.1" to 19870, endpointHostPort(GUEST_DAEMON_ENDPOINT))
        assertEquals("host.lan" to 443, endpointHostPort("wss://host.lan/ws"))
        assertNull(endpointHostPort("http://host.lan:80"))
        assertNull(endpointHostPort("not a url"))
    }

    @Test
    fun onDeviceIsAWorkStateNotAnOfflineError() {
        val facts = guest.copy(hasConnections = false, connected = true, workingOnDevice = true)
        assertEquals(ConnectionState.OnDevice, connectionState(facts))
        assertEquals("On this device", connectionSummary(facts).chip)
        assertTrue(connectionSummary(facts).detail!!.contains("doesn't need a Linux connection"))
        assertTrue(connectionSummary(facts.copy(workingOffline = true)).detail!!.contains("sync when its guest is available"))
    }

    @Test
    fun connectionRoutesAreOnlySpecificWhenTheEndpointShowsThem() {
        assertEquals(ConnectionKind.TabletGuest, connectionKind("ws://127.0.0.1:19870/ws"))
        assertEquals(ConnectionRoute.LinuxTerminal, connectionRoute("ws://127.0.0.1:19870/ws"))
        assertEquals(ConnectionRoute.Wifi, connectionRoute("ws://192.168.1.20:19870/ws"))
        assertEquals(ConnectionRoute.Tailscale, connectionRoute("ws://100.100.10.20:19870/ws"))
        assertEquals(ConnectionRoute.Tailscale, connectionRoute("wss://example-host.ts.net/ws"))
        assertEquals(ConnectionRoute.Tunnel, connectionRoute("wss://random.trycloudflare.com/ws"))
        assertEquals(ConnectionRoute.LocalForward, connectionRoute("ws://127.0.0.1:39495/ws"))
        assertEquals(ConnectionRoute.LocalForward, connectionRoute("wss://127.0.0.1:8443/ws"))
        assertEquals(ConnectionRoute.SecureWebSocket, connectionRoute("wss://work.example.com/ws"))
        assertEquals(ConnectionRoute.SecureWebSocket, connectionRoute("wss://host.name.invalid.extra/ws"))
    }

    @Test
    fun guestSessionKeysIncludeTheConnectionButDeviceKeysDoNot() {
        assertEquals("session:tree:/device/uri/Projects", workspaceSessionKey("tree:/device/uri/Projects", "profile-a"))
        assertEquals("session:profile-a:/home/u/Projects", workspaceSessionKey("/home/u/Projects", "profile-a"))
        assertEquals("session:profile-b:/home/u/Projects", workspaceSessionKey("/home/u/Projects", "profile-b"))
    }

    @Test
    fun startupPlanFollowsLastWorkspaceAndDoesNotFallbackToAnotherDaemon() {
        val tablet = io.github.kinteshi.quirecode.transport.ConnectionProfile(
            id = "guest", name = "Linux guest", endpoint = GUEST_DAEMON_ENDPOINT, hasToken = true, autoConnect = true,
        )
        val homePc = io.github.kinteshi.quirecode.transport.ConnectionProfile(
            id = "pc", name = "Work PC", endpoint = "wss://100.64.0.1:19870/ws", hasToken = true, autoConnect = true,
        )
        val profiles = listOf(tablet, homePc)

        // 1. Device folder last: open on this device and do not auto-connect.
        val devicePlan = planStartupConnection(
            profiles = profiles,
            lastUsedProfileId = "guest",
            lastWorkspace = RecentWorkspace("tree:/notes"),
            offlineByChoice = false,
            autoConnectSetting = true,
            offlineWorkspaceSetting = true,
        )
        assertEquals("tree:/notes", devicePlan.openDeviceWorkspace)
        assertNull(devicePlan.connectProfileId)
        assertTrue(devicePlan.fallbackProfileIds.isEmpty())

        // 2. Guest folder owned by Work PC: select & try Work PC only, never fallback to Linux guest.
        val pcPlan = planStartupConnection(
            profiles = profiles,
            lastUsedProfileId = "guest",
            lastWorkspace = RecentWorkspace("/home/user/api", "pc"),
            offlineByChoice = false,
            autoConnectSetting = true,
            offlineWorkspaceSetting = true,
        )
        assertEquals("pc", pcPlan.selectedProfileId)
        assertEquals("pc", pcPlan.connectProfileId)
        assertTrue(pcPlan.fallbackProfileIds.isEmpty())
        assertEquals(RecentWorkspace("/home/user/api", "pc"), pcPlan.openOfflineGuestWorkspace)

        // 3. Deliberate offline mode: restore the workspace offline without auto-connecting.
        val offlinePlan = planStartupConnection(
            profiles = profiles,
            lastUsedProfileId = "pc",
            lastWorkspace = RecentWorkspace("/home/user/api", "pc"),
            offlineByChoice = true,
            autoConnectSetting = true,
            offlineWorkspaceSetting = true,
        )
        assertEquals("pc", offlinePlan.selectedProfileId)
        assertNull(offlinePlan.connectProfileId)
        assertEquals(RecentWorkspace("/home/user/api", "pc"), offlinePlan.openOfflineGuestWorkspace)

        // 4. No recent workspace: follow auto-connect order with fallbacks.
        val emptyPlan = planStartupConnection(
            profiles = profiles,
            lastUsedProfileId = null,
            lastWorkspace = null,
            offlineByChoice = false,
            autoConnectSetting = true,
            offlineWorkspaceSetting = true,
        )
        assertEquals("guest", emptyPlan.selectedProfileId)
        assertEquals("guest", emptyPlan.connectProfileId)
        assertEquals(listOf("pc"), emptyPlan.fallbackProfileIds)
    }

    @Test
    fun troubleshootDiagnosisKnowsTheRoute() {
        val guestClosed = ConnectionCheck(
            terminalAvailable = true, daemonAnswers = false, target = "127.0.0.1:19870",
            checking = false, portOpen = false, route = ConnectionRoute.LinuxTerminal,
        )
        assertTrue(troubleshootDiagnosis(guestClosed, tabletGuest = true).contains("Nothing answers at 127.0.0.1:19870"))

        val guestForwardingWedged = guestClosed.copy(portOpen = true)
        assertTrue(troubleshootDiagnosis(guestForwardingWedged, tabletGuest = true).contains("Port 127.0.0.1:19870 is open"))

        val tailscaleOff = ConnectionCheck(
            terminalAvailable = null, daemonAnswers = false, target = "100.64.0.1:19870",
            checking = false, portOpen = false, route = ConnectionRoute.Tailscale, vpnActive = false,
        )
        assertTrue(troubleshootDiagnosis(tailscaleOff, tabletGuest = false).contains("turn on Tailscale"))

        val tailscaleOnClosed = tailscaleOff.copy(vpnActive = true)
        assertTrue(troubleshootDiagnosis(tailscaleOnClosed, tabletGuest = false).contains("over Tailscale"))

        val wifiAway = ConnectionCheck(
            terminalAvailable = null, daemonAnswers = false, target = "192.168.1.20:19870",
            checking = false, portOpen = false, route = ConnectionRoute.Wifi, onLocalNetwork = false, onCellularOnly = true,
        )
        assertTrue(troubleshootDiagnosis(wifiAway, tabletGuest = false).contains("away from your Wi-Fi"))

        val tunnelClosed = ConnectionCheck(
            terminalAvailable = null, daemonAnswers = false, target = "dev.trycloudflare.com:443",
            checking = false, portOpen = false, route = ConnectionRoute.Tunnel,
        )
        assertTrue(troubleshootDiagnosis(tunnelClosed, tabletGuest = false).contains("cloudflared"))

        val tunnelEdgeOpen = tunnelClosed.copy(portOpen = true)
        assertTrue(troubleshootDiagnosis(tunnelEdgeOpen, tabletGuest = false).contains("tunnel reached"))

        val localForwardClosed = ConnectionCheck(
            terminalAvailable = null, daemonAnswers = false, target = "127.0.0.1:39495",
            checking = false, portOpen = false, route = ConnectionRoute.LocalForward,
        )
        assertTrue(troubleshootDiagnosis(localForwardClosed, tabletGuest = false).contains("ADB reverse or SSH"))
    }

    @Test
    fun connectionUrlValidationAllowsLoopbackWsAndRequiresWssForRemoteHosts() {
        assertNull(connectionUrlProblem(GUEST_DAEMON_ENDPOINT))
        assertNull(connectionUrlProblem("ws://localhost:19870/ws"))
        assertNull(connectionUrlProblem("wss://100.64.0.1:19870/ws"))
        assertTrue(connectionUrlProblem("ws://100.64.0.1:19870/ws")!!.contains("wss://"))
        assertTrue(connectionUrlProblem("http://127.0.0.1:19870/ws")!!.contains("ws://"))
        assertTrue(connectionUrlProblem("not a url")!!.contains("valid"))
    }

    @Test
    fun orderProfileRoutesFollowsHomeWifiAwayTailscaleAndExplicitPreference() {
        val wifi = io.github.kinteshi.quirecode.transport.ProfileRoute("wss://192.168.1.10:19870/ws", "a".repeat(64))
        val tailscale = io.github.kinteshi.quirecode.transport.ProfileRoute("wss://100.64.0.1:19870/ws", "a".repeat(64))
        val tunnel = io.github.kinteshi.quirecode.transport.ProfileRoute("wss://quirecode.example.com:443/ws", null)
        val routes = listOf(tunnel, tailscale, wifi)

        assertEquals(
            listOf(wifi, tailscale, tunnel),
            orderProfileRoutes(routes, io.github.kinteshi.quirecode.transport.RoutePreference.Auto, onLocalNetwork = true, vpnActive = true),
        )
        assertEquals(
            listOf(tailscale, tunnel, wifi),
            orderProfileRoutes(routes, io.github.kinteshi.quirecode.transport.RoutePreference.Auto, onLocalNetwork = false, vpnActive = true),
        )
        assertEquals(
            listOf(tunnel, tailscale, wifi),
            orderProfileRoutes(routes, io.github.kinteshi.quirecode.transport.RoutePreference.Auto, onLocalNetwork = false, vpnActive = false),
        )
        assertEquals(
            listOf(tailscale, wifi, tunnel),
            orderProfileRoutes(routes, io.github.kinteshi.quirecode.transport.RoutePreference.Tailscale, onLocalNetwork = true, vpnActive = true),
        )
    }

    @Test
    fun connectedChipShowsActiveRouteWhenProfileHasMultipleRoutes() {
        val single = guest.copy(name = "Work PC", tabletGuest = false, connected = true, activeRoute = ConnectionRoute.Wifi, routeCount = 1)
        assertEquals("Work PC", connectionSummary(single).chip)

        val multi = single.copy(activeRoute = ConnectionRoute.Tailscale, routeCount = 2)
        assertEquals("Work PC · Tailscale", connectionSummary(multi).chip)
    }

    @Test
    fun probeDaemonPortIncludesExtraAccessHeaders() {
        val receivedHeaders = StringBuilder()
        java.net.ServerSocket(0).use { server ->
            val thread = Thread {
                runCatching {
                    server.accept().use { client ->
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            receivedHeaders.append(line).append('\n')
                            line = reader.readLine()
                        }
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val result = probeDaemonPort(
                "ws://127.0.0.1:${server.localPort}/ws",
                timeoutMs = 500,
                extraHeaders = mapOf("CF-Access-Client-Id" to "id-abc", "CF-Access-Client-Secret" to "sec-xyz"),
            )
            thread.join(1_000)
            assertEquals(true to true, result)
            assertTrue(receivedHeaders.toString().contains("CF-Access-Client-Id: id-abc"))
            assertTrue(receivedHeaders.toString().contains("CF-Access-Client-Secret: sec-xyz"))
        }
    }

    @Test
    fun connectionRowCopyReflectsManualModePreferredRouteAndLastUsed() {
        val multi = io.github.kinteshi.quirecode.transport.ConnectionProfile(
            id = "p1",
            name = "Work PC",
            endpoint = "wss://192.168.1.10:19870/ws",
            hasToken = true,
            fingerprint = "a".repeat(64),
            autoConnect = false,
            extraRoutes = listOf(io.github.kinteshi.quirecode.transport.ProfileRoute("wss://100.64.0.1:19870/ws", "a".repeat(64))),
            preferredRoute = io.github.kinteshi.quirecode.transport.RoutePreference.Tailscale,
            hasAccessToken = true,
        )
        val offlineCopy = connectionRowCopy(
            profile = multi,
            order = 2,
            current = true,
            connected = false,
            busy = false,
            pairingRejected = false,
            connectionState = ConnectionState.ForwardingStuck,
            activeRouteEndpoint = "",
            formattedLastUsed = "5:10 PM",
        )
        assertEquals("Wi-Fi, Tailscale · Manual · Offline · Prefer Tailscale · Access token", offlineCopy.primary)
        assertEquals("Last used 5:10 PM", offlineCopy.lastUsed)

        val autoConnected = multi.copy(autoConnect = true, hasAccessToken = false)
        val connectedCopy = connectionRowCopy(
            profile = autoConnected,
            order = 1,
            current = true,
            connected = true,
            busy = false,
            pairingRejected = false,
            connectionState = ConnectionState.Connected,
            activeRouteEndpoint = "wss://100.64.0.1:19870/ws",
        )
        assertEquals("Wi-Fi, Tailscale · Auto-connect #1 · Connected (Tailscale) · Prefer Tailscale", connectedCopy.primary)
        assertNull(connectedCopy.lastUsed)
    }

    @Test
    fun visibleRecentWorkspacesDropsDeletedHostsAndComparesHostFolderPair() {
        val device = RecentWorkspace("tree:/notes", connectionId = null)
        val guestProjects = RecentWorkspace("/home/droid/Projects", connectionId = "guest")
        val pcProjects = RecentWorkspace("/home/droid/Projects", connectionId = "pc")
        val deletedPc = RecentWorkspace("/home/user/old", connectionId = "deleted-pc")
        val orphanLegacy = RecentWorkspace("/home/user/legacy", connectionId = null)

        val visible = visibleRecentWorkspaces(
            recents = listOf(device, guestProjects, pcProjects, deletedPc, orphanLegacy),
            activeWorkspaceRoot = "/home/droid/Projects",
            activeConnectionId = "guest",
            profileIds = setOf("guest", "pc"),
        )
        assertEquals(listOf(device, pcProjects), visible)
    }
}
