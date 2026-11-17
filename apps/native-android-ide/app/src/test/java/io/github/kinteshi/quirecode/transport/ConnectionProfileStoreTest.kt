package io.github.kinteshi.quirecode.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileStoreTest {
    private class MemoryValues : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private object ReversingCipher : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:").reversed().takeIf { encoded.startsWith("enc:") }
    }

    @Test
    fun savesMultipleProfilesAndNeverStoresTheTokenInClearText() {
        val values = MemoryValues()
        val store = ConnectionProfileStore(values, ReversingCipher)

        val home = store.save(null, "Home", "ws://127.0.0.1:19860/ws", "secret-token")
        val work = store.save(null, "Work PC", "ws://192.168.1.20:19860/ws", "other")

        assertEquals(listOf("Home", "Work PC"), store.profiles().map { it.name })
        assertEquals("secret-token", store.token(home.id))
        assertNull(store.lastUsedId())
        store.markUsed(work.id)
        assertEquals(work.id, store.lastUsedId())
        assertFalse(values.map.values.any { it.contains("secret-token") })
    }

    @Test
    fun blankTokenKeepsTheSavedOneAndUpdatesTheEndpoint() {
        val store = ConnectionProfileStore(MemoryValues(), ReversingCipher)
        val saved = store.save(null, "Home", "ws://a/ws", "tok")

        store.save(saved.id, "Home", "ws://b/ws", "")

        assertEquals(1, store.profiles().size)
        assertEquals("ws://b/ws", store.profiles().single().endpoint)
        assertEquals("tok", store.token(saved.id))
    }

    @Test
    fun clearFingerprintRemovesPreviouslyPinnedCertificate() {
        val store = ConnectionProfileStore(MemoryValues(), ReversingCipher)
        val pinned = store.save(null, "Tunnel", "wss://quirecode.example.com:443/ws", "tok", fingerprint = "a".repeat(64))
        assertEquals("a".repeat(64), pinned.fingerprint)

        val kept = store.save(pinned.id, "Tunnel", "wss://quirecode.example.com:443/ws", "")
        assertEquals("a".repeat(64), kept.fingerprint)

        val publicTls = store.save(pinned.id, "Tunnel", "wss://quirecode.example.com:443/ws", "", clearFingerprint = true)
        assertNull(publicTls.fingerprint)
    }

    @Test
    fun deleteRemovesProfileAndLastUsedPointer() {
        val store = ConnectionProfileStore(MemoryValues(), ReversingCipher)
        val saved = store.save(null, "", "ws://a/ws", "tok")

        assertEquals("ws://a/ws", saved.name)
        store.delete(saved.id)

        assertTrue(store.profiles().isEmpty())
        assertNull(store.lastUsedId())
        assertNull(store.token(saved.id))
    }

    @Test
    fun autoConnectAndOrderArePersistedWithLastUsedTime() {
        val store = ConnectionProfileStore(MemoryValues(), ReversingCipher)
        val guest = store.save(null, "Guest", "ws://127.0.0.1:19870/ws", "guest-token")
        val computer = store.save(null, "Work PC", "wss://work.example.com/ws", "computer-token")

        store.setAutoConnect(guest.id, false)
        store.markUsed(computer.id, atMillis = 1234L)
        store.reorder(listOf(computer.id, guest.id))

        assertEquals(listOf(computer.id, guest.id), store.profiles().map { it.id })
        assertFalse(store.profiles().last().autoConnect)
        assertEquals(1234L, store.profiles().first().lastUsedAt)
        assertEquals(computer.id, store.lastUsedId())
    }

    @Test
    fun editingAProfileDoesNotMarkItAsUsed() {
        val store = ConnectionProfileStore(MemoryValues(), ReversingCipher)
        val guest = store.save(null, "Guest", "ws://127.0.0.1:19870/ws", "guest-token")
        val computer = store.save(null, "Work PC", "wss://work.example.com/ws", "computer-token")
        store.markUsed(guest.id, atMillis = 1234L)

        store.save(computer.id, "Work PC", "wss://work.example.com/ws", "")

        assertEquals(guest.id, store.lastUsedId())
    }

    @Test
    fun legacyDataOwnerDoesNotChangeWhenTheLastUsedProfileChangesOrIsDeleted() {
        val store = ConnectionProfileStore(MemoryValues(), ReversingCipher)
        val original = store.save(null, "Old guest", "ws://127.0.0.1:19870/ws", "old-token")
        val next = store.save(null, "Work PC", "wss://work.example.com/ws", "work-token")
        store.markUsed(original.id)

        assertEquals(original.id, store.legacyCacheOwnerId(next.id))
        store.delete(original.id)

        assertNull(store.lastUsedId())
        assertEquals(original.id, store.legacyCacheOwnerId())
        store.markUsed(next.id)
        assertEquals(next.id, store.lastUsedId())
        assertEquals(original.id, store.legacyCacheOwnerId())
    }

    @Test
    fun oldProfilesDefaultToAutoConnect() {
        val values = MemoryValues().apply {
            map["profiles"] = """[{"id":"old","name":"Old","endpoint":"ws://host/ws","token":"enc:tok"}]"""
        }

        assertTrue(ConnectionProfileStore(values, ReversingCipher).profiles().single().autoConnect)
    }

    @Test
    fun corruptStorageIsTreatedAsEmpty() {
        val values = MemoryValues().apply { map["profiles"] = "{not json" }
        assertTrue(ConnectionProfileStore(values, ReversingCipher).profiles().isEmpty())
    }

    @Test
    fun storesExtraRoutesPreferredRouteAndEncryptedAccessCredentials() {
        val values = MemoryValues()
        val store = ConnectionProfileStore(values, ReversingCipher)
        val extra = listOf(
            ProfileRoute("wss://100.64.0.1:19870/ws", "b".repeat(64)),
            ProfileRoute("wss://quirecode.example.com:443/ws", null),
        )
        val saved = store.save(
            id = null,
            name = "Work PC",
            endpoint = "wss://192.168.1.10:19870/ws",
            token = "device-tok",
            fingerprint = "b".repeat(64),
            extraRoutes = extra,
            preferredRoute = RoutePreference.Tailscale,
            accessClientId = "cf-client-id-secret",
            accessClientSecret = "cf-client-secret-value",
        )

        assertTrue(saved.hasAccessToken)
        assertEquals(RoutePreference.Tailscale, saved.preferredRoute)
        assertEquals(3, saved.allRoutes().size)
        assertEquals(AccessCredentials("cf-client-id-secret", "cf-client-secret-value"), store.accessCredentials(saved.id))
        assertFalse(values.map.values.any { it.contains("cf-client-id-secret") || it.contains("cf-client-secret-value") })

        // Saving the primary route without passing extraRoutes or access creds keeps them intact.
        val updated = store.save(saved.id, "Work PC", "wss://192.168.1.10:19870/ws", "")
        assertEquals(3, updated.allRoutes().size)
        assertTrue(updated.hasAccessToken)
        assertEquals(RoutePreference.Tailscale, updated.preferredRoute)

        store.setPreferredRoute(saved.id, RoutePreference.Auto)
        assertEquals(RoutePreference.Auto, store.profiles().single().preferredRoute)

        val cleared = store.save(saved.id, "Work PC", "wss://192.168.1.10:19870/ws", "", clearAccessToken = true)
        assertFalse(cleared.hasAccessToken)
        assertNull(store.accessCredentials(saved.id))
    }
}
