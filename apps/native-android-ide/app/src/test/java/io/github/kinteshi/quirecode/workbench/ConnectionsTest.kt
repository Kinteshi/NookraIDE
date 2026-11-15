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
        assertTrue(lost.detail!!.contains("working offline"))
        val computer = connectionSummary(guest.copy(name = "192.168.1.10", tabletGuest = false, unreachable = true))
        assertEquals("Can't reach 192.168.1.10", computer.title)
        assertFalse(computer.detail!!.contains("Linux Terminal"))
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
}
