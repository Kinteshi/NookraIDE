package io.github.kinteshi.quirecode.document

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedDocumentCache(context: Context) : DocumentCache {
    private val directory = File(context.filesDir, "n2-document-cache").apply {
        if (!exists() && !mkdirs()) error("Could not create the private document cache directory")
    }
    private val key: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadOrCreateKey() }

    override fun load(path: String): DocumentSnapshot? {
        val file = cacheFile(path)
        if (!file.exists()) return null
        // A draft cut short (the tablet rebooted mid-write) is lost either way; don't let it
        // block opening the file from the guest.
        return try {
            decode(file, path)
        } catch (error: Exception) {
            android.util.Log.w("AVFCache", "Dropping unreadable draft for $path: ${error.message}")
            file.delete()
            null
        }
    }

    private fun decode(file: File, path: String): DocumentSnapshot {
        val envelope = JSONObject(file.readText(StandardCharsets.UTF_8))
        val iv = android.util.Base64.decode(envelope.getString("iv"), android.util.Base64.NO_WRAP)
        val ciphertext = android.util.Base64.decode(
            envelope.getString("ciphertext"),
            android.util.Base64.NO_WRAP,
        )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val snapshot = JSONObject(String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8))
        check(snapshot.getString("path") == path) { "Cached document path did not match its cache key" }
        return DocumentSnapshot(
            path = path,
            text = snapshot.getString("text"),
            modifiedTs = if (snapshot.isNull("modifiedTs")) null else snapshot.getLong("modifiedTs"),
            dirty = snapshot.getBoolean("dirty"),
            encoding = snapshot.optString("encoding", "UTF-8"),
            eol = snapshot.optString(
                "eol",
                if (snapshot.getString("text").contains("\r\n")) "\r\n" else "\n",
            ),
        )
    }

    override fun save(snapshot: DocumentSnapshot) {
        val plainText = JSONObject()
            .put("path", snapshot.path)
            .put("text", snapshot.text)
            .put("modifiedTs", snapshot.modifiedTs)
            .put("dirty", snapshot.dirty)
            .put("encoding", snapshot.encoding)
            .put("eol", snapshot.eol)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plainText)
        val envelope = JSONObject()
            .put("iv", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .put(
                "ciphertext",
                android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP),
            )
            .toString()
        val target = cacheFile(snapshot.path)
        // A draft and a save of the same file can run at once on different threads: each writes
        // its own temporary file, and the renames take turns (the later snapshot wins).
        val temporary = File.createTempFile("${target.name}.", ".tmp", directory)
        try {
            // Flush before the rename, or a power loss can leave the new name with empty contents.
            java.io.FileOutputStream(temporary).use { stream ->
                stream.write(envelope.toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            synchronized(this) {
                if (!temporary.renameTo(target)) error("Could not atomically persist the document cache")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun contains(path: String): Boolean = cacheFile(path).isFile

    override fun delete(path: String) {
        val file = cacheFile(path)
        if (file.exists() && !file.delete()) error("Could not remove cached document")
    }

    private fun cacheFile(path: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "avf-native-ide-n2-document-cache"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
