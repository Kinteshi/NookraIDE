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
    fun corruptStorageIsTreatedAsEmpty() {
        val values = MemoryValues().apply { map["profiles"] = "{not json" }
        assertTrue(ConnectionProfileStore(values, ReversingCipher).profiles().isEmpty())
    }
}
