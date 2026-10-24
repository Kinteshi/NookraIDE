package com.avfstudio.nativeide.transport

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** [fingerprint] pins a remote host's self-signed TLS certificate (SHA-256 of its DER, hex). */
data class ConnectionProfile(
    val id: String,
    val name: String,
    val endpoint: String,
    val hasToken: Boolean,
    val fingerprint: String? = null,
)

interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String?
}

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** Saved guest connection profiles. Session tokens are encrypted and never stored in clear text. */
class ConnectionProfileStore(
    private val values: KeyValueStore,
    private val cipher: SecretCipher,
) {
    private val gson = Gson()

    fun profiles(): List<ConnectionProfile> = readEntries().map { entry ->
        ConnectionProfile(
            entry.get("id").asString,
            entry.get("name").asString,
            entry.get("endpoint").asString,
            entry.has("token"),
            entry.get("fingerprint")?.takeIf { it.isJsonPrimitive }?.asString,
        )
    }

    fun lastUsedId(): String? = values.getString(LAST_USED_KEY)

    fun token(id: String): String? = readEntries()
        .firstOrNull { it.get("id").asString == id }
        ?.get("token")?.asString
        ?.let(cipher::decrypt)

    /**
     * Creates or updates a profile; a blank token keeps the previously saved token. A null
     * [fingerprint] keeps the saved pin unless the endpoint changed.
     */
    fun save(id: String?, name: String, endpoint: String, token: String, fingerprint: String? = null): ConnectionProfile {
        val entries = readEntries()
        val profileId = id?.takeIf { existing -> entries.any { it.get("id").asString == existing } }
            ?: UUID.randomUUID().toString()
        val previous = entries.firstOrNull { it.get("id").asString == profileId }
        val entry = JsonObject().apply {
            addProperty("id", profileId)
            addProperty("name", name.trim().ifEmpty { endpoint.trim() })
            addProperty("endpoint", endpoint.trim())
            when {
                token.isNotEmpty() -> addProperty("token", cipher.encrypt(token))
                previous?.has("token") == true -> add("token", previous.get("token"))
            }
            val pin = fingerprint ?: previous?.takeIf { it.get("endpoint")?.asString == endpoint.trim() }?.get("fingerprint")?.asString
            pin?.let { addProperty("fingerprint", it) }
        }
        val updated = JsonArray().apply {
            var replaced = false
            entries.forEach { existing ->
                if (existing.get("id").asString == profileId) {
                    add(entry)
                    replaced = true
                } else {
                    add(existing)
                }
            }
            if (!replaced) add(entry)
        }
        values.putString(PROFILES_KEY, gson.toJson(updated))
        values.putString(LAST_USED_KEY, profileId)
        return profiles().first { it.id == profileId }
    }

    fun delete(id: String) {
        val updated = JsonArray().apply { readEntries().filter { it.get("id").asString != id }.forEach(::add) }
        values.putString(PROFILES_KEY, gson.toJson(updated))
        if (lastUsedId() == id) values.putString(LAST_USED_KEY, null)
    }

    private fun readEntries(): List<JsonObject> {
        val raw = values.getString(PROFILES_KEY) ?: return emptyList()
        return try {
            gson.fromJson(raw, JsonArray::class.java).mapNotNull { element ->
                element.asJsonObject.takeIf {
                    it.get("id")?.isJsonPrimitive == true &&
                        it.get("name")?.isJsonPrimitive == true &&
                        it.get("endpoint")?.isJsonPrimitive == true
                }
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private companion object {
        const val PROFILES_KEY = "profiles"
        const val LAST_USED_KEY = "last_used"
    }
}

class SharedPreferencesKeyValueStore(context: Context) : KeyValueStore {
    private val preferences = context.getSharedPreferences("native-ide-connections", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun putString(key: String, value: String?) {
        preferences.edit().putString(key, value).apply()
    }
}

/** AES-GCM with a non-exportable Android Keystore key. */
class KeystoreSecretCipher(private val alias: String = "avf-native-ide-connection-tokens") : SecretCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
