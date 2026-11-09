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
        assertTrue(connectionSummary(guest.copy(failure = "Handshake timed out")).detail!!.startsWith("Handshake timed out"))
    }

    @Test
    fun tabletGuestEndpointsAndPorts() {
        assertTrue(isTabletGuestEndpoint(GUEST_DAEMON_ENDPOINT))
        assertTrue(isTabletGuestEndpoint("ws://localhost:19870/ws"))
        assertTrue(isTabletGuestEndpoint(""))
        assertFalse(isTabletGuestEndpoint("wss://192.168.1.10:19870/ws"))
        assertEquals("127.0.0.1" to 19870, endpointHostPort(GUEST_DAEMON_ENDPOINT))
        assertEquals("host.lan" to 443, endpointHostPort("wss://host.lan/ws"))
        assertNull(endpointHostPort("http://host.lan:80"))
        assertNull(endpointHostPort("not a url"))
    }
}
