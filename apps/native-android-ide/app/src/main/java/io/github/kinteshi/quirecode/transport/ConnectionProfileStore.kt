package io.github.kinteshi.quirecode.transport

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

/** Route preference for a multi-route connection profile (G37). */
enum class RoutePreference(val key: String, val label: String) {
    Auto("auto", "Automatic (Wi-Fi at home, Tailscale away)"),
    Wifi("wifi", "Wi-Fi / local network"),
    Tailscale("tailscale", "Tailscale"),
    Tunnel("tunnel", "Cloudflare Tunnel"),
}

/** One WebSocket address on a profile with its optional pinned SHA-256 certificate (G37). */
data class ProfileRoute(
    val endpoint: String,
    val fingerprint: String? = null,
)

/** Cloudflare Access service-token headers (stored encrypted, G32). */
data class AccessCredentials(
    val clientId: String,
    val clientSecret: String,
)

/** [fingerprint] pins a remote host's self-signed TLS certificate (SHA-256 of its DER, hex). */
data class ConnectionProfile(
    val id: String,
    val name: String,
    val endpoint: String,
    val hasToken: Boolean,
    val fingerprint: String? = null,
    val autoConnect: Boolean = true,
    val lastUsedAt: Long? = null,
    val extraRoutes: List<ProfileRoute> = emptyList(),
    val preferredRoute: RoutePreference = RoutePreference.Auto,
    val hasAccessToken: Boolean = false,
) {
    /** All distinct routes for this connection (primary first, followed by saved alternates). */
    fun allRoutes(): List<ProfileRoute> =
        (listOf(ProfileRoute(endpoint, fingerprint)) + extraRoutes).distinctBy { it.endpoint }
}

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
        val extraRoutes = entry.get("extraRoutes")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val ep = obj.get("endpoint")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val fp = obj.get("fingerprint")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotEmpty)
            ProfileRoute(ep, fp)
        }.orEmpty()
        val preferred = entry.get("preferredRoute")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let { key -> RoutePreference.entries.firstOrNull { it.key == key } }
            ?: RoutePreference.Auto
        ConnectionProfile(
            entry.get("id").asString,
            entry.get("name").asString,
            entry.get("endpoint").asString,
            entry.has("token"),
            entry.get("fingerprint")?.takeIf { it.isJsonPrimitive }?.asString,
            entry.get("autoConnect")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            entry.get("lastUsedAt")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong,
            extraRoutes = extraRoutes,
            preferredRoute = preferred,
            hasAccessToken = entry.has("accessClientId") && entry.has("accessClientSecret"),
        )
    }

    fun lastUsedId(): String? = values.getString(LAST_USED_KEY)

    /** The profile that owns unscoped data created before paths and caches were connection-aware. */
    fun legacyCacheOwnerId(fallback: String? = null): String? {
        values.getString(LEGACY_OWNER_KEY)?.let { return it }
        val owner = lastUsedId() ?: fallback ?: return null
        values.putString(LEGACY_OWNER_KEY, owner)
        return owner
    }

    fun token(id: String): String? = readEntries()
        .firstOrNull { it.get("id").asString == id }
        ?.get("token")?.asString
        ?.let(cipher::decrypt)

    /** Returns decrypted Cloudflare Access service-token credentials for [id], or null if unset (G32). */
    fun accessCredentials(id: String): AccessCredentials? {
        val entry = readEntries().firstOrNull { it.get("id").asString == id } ?: return null
        val clientId = entry.get("accessClientId")?.takeIf { it.isJsonPrimitive }?.asString?.let(cipher::decrypt)
            ?.takeIf(String::isNotBlank) ?: return null
        val clientSecret = entry.get("accessClientSecret")?.takeIf { it.isJsonPrimitive }?.asString?.let(cipher::decrypt)
            ?.takeIf(String::isNotBlank) ?: return null
        return AccessCredentials(clientId, clientSecret)
    }

    /**
     * Creates or updates a profile; a blank token keeps the previously saved token. A null
     * [fingerprint] keeps the saved pin unless the endpoint changed or [clearFingerprint] is true.
     * Blank/null [extraRoutes], [preferredRoute], or [accessClientId]/[accessClientSecret] preserve existing ones
     * unless explicitly replaced or cleared via [clearAccessToken].
     */
    fun save(
        id: String?,
        name: String,
        endpoint: String,
        token: String,
        fingerprint: String? = null,
        clearFingerprint: Boolean = false,
        extraRoutes: List<ProfileRoute>? = null,
        preferredRoute: RoutePreference? = null,
        accessClientId: String? = null,
        accessClientSecret: String? = null,
        clearAccessToken: Boolean = false,
    ): ConnectionProfile {
        val entries = readEntries()
        val profileId = id?.takeIf { existing -> entries.any { it.get("id").asString == existing } }
            ?: UUID.randomUUID().toString()
        val previous = entries.firstOrNull { it.get("id").asString == profileId }
        val trimmedEndpoint = endpoint.trim()
        val entry = JsonObject().apply {
            addProperty("id", profileId)
            addProperty("name", name.trim().ifEmpty { trimmedEndpoint })
            addProperty("endpoint", trimmedEndpoint)
            when {
                token.isNotEmpty() -> addProperty("token", cipher.encrypt(token))
                previous?.has("token") == true -> add("token", previous.get("token"))
            }
            val pin = if (clearFingerprint) {
                null
            } else {
                fingerprint ?: previous?.takeIf { it.get("endpoint")?.asString == trimmedEndpoint }?.get("fingerprint")?.asString
            }
            pin?.let { addProperty("fingerprint", it) }
            addProperty("autoConnect", previous?.get("autoConnect")?.asBoolean ?: true)
            previous?.get("lastUsedAt")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong
                ?.let { addProperty("lastUsedAt", it) }
            val resolvedExtra = extraRoutes?.filter { it.endpoint.isNotBlank() && it.endpoint != trimmedEndpoint }
                ?.distinctBy { it.endpoint }
            if (resolvedExtra != null) {
                if (resolvedExtra.isNotEmpty()) {
                    add(
                        "extraRoutes",
                        JsonArray().apply {
                            resolvedExtra.forEach { route ->
                                add(
                                    JsonObject().apply {
                                        addProperty("endpoint", route.endpoint.trim())
                                        route.fingerprint?.let { addProperty("fingerprint", it) }
                                    },
                                )
                            }
                        },
                    )
                }
            } else {
                previous?.get("extraRoutes")?.let { add("extraRoutes", it) }
            }
            val resolvedPreferred = preferredRoute?.key
                ?: previous?.get("preferredRoute")?.takeIf { it.isJsonPrimitive }?.asString
            if (resolvedPreferred != null && resolvedPreferred != RoutePreference.Auto.key) {
                addProperty("preferredRoute", resolvedPreferred)
            }
            val cleanAccessId = accessClientId?.trim().orEmpty()
            val cleanAccessSecret = accessClientSecret?.trim().orEmpty()
            when {
                clearAccessToken -> Unit
                cleanAccessId.isNotEmpty() && cleanAccessSecret.isNotEmpty() -> {
                    addProperty("accessClientId", cipher.encrypt(cleanAccessId))
                    addProperty("accessClientSecret", cipher.encrypt(cleanAccessSecret))
                }
                else -> {
                    previous?.get("accessClientId")?.let { add("accessClientId", it) }
                    previous?.get("accessClientSecret")?.let { add("accessClientSecret", it) }
                }
            }
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
        return profiles().first { it.id == profileId }
    }

    fun delete(id: String) {
        val updated = JsonArray().apply { readEntries().filter { it.get("id").asString != id }.forEach(::add) }
        values.putString(PROFILES_KEY, gson.toJson(updated))
        if (lastUsedId() == id) values.putString(LAST_USED_KEY, null)
    }

    fun setAutoConnect(id: String, enabled: Boolean) {
        updateEntry(id) { addProperty("autoConnect", enabled) }
    }

    fun setPreferredRoute(id: String, route: RoutePreference) {
        updateEntry(id) {
            if (route == RoutePreference.Auto) remove("preferredRoute") else addProperty("preferredRoute", route.key)
        }
    }

    fun markUsed(id: String, atMillis: Long = System.currentTimeMillis()) {
        updateEntry(id) { addProperty("lastUsedAt", atMillis) }
        if (readEntries().any { it.get("id").asString == id }) {
            values.putString(LAST_USED_KEY, id)
        }
    }

    /** Keeps only known ids and appends any omitted profiles in their existing order. */
    fun reorder(ids: List<String>) {
        val entries = readEntries()
        val byId = entries.associateBy { it.get("id").asString }
        val ordered = (ids.distinct().mapNotNull(byId::get) + entries.filter { it.get("id").asString !in ids })
        values.putString(PROFILES_KEY, gson.toJson(JsonArray().apply { ordered.forEach(::add) }))
    }

    private fun updateEntry(id: String, update: JsonObject.() -> Unit) {
        val updated = JsonArray()
        readEntries().forEach { entry ->
            if (entry.get("id").asString == id) {
                updated.add(entry.deepCopy().apply(update))
            } else {
                updated.add(entry)
            }
        }
        values.putString(PROFILES_KEY, gson.toJson(updated))
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
        const val LEGACY_OWNER_KEY = "legacy_cache_owner"
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
