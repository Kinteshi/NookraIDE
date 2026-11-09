package io.github.kinteshi.quirecode.document

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Files on the device itself (Downloads, a USB drive, a cloud app), picked through Android's
 * file picker. They open without a guest: the editor reads and writes them through their
 * content URI. A tab knows one by a path that ends in its display name, so the name, the
 * language and the icon come out as for any file: `device:/<encoded URI>/<name>`.
 */
const val DEVICE_PREFIX = "device:/"

fun isDevicePath(path: String): Boolean = path.startsWith(DEVICE_PREFIX)

fun devicePath(uri: String, displayName: String): String =
    DEVICE_PREFIX + URLEncoder.encode(uri, StandardCharsets.UTF_8.name()) + "/" + displayName.replace('/', '_').ifEmpty { "file" }

/** The content URI behind a [devicePath]. */
fun deviceUri(path: String): String? =
    path.takeIf(::isDevicePath)?.removePrefix(DEVICE_PREFIX)?.substringBefore('/')
        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }

/** A guest file, as opposed to an untitled (`scratch:`) or a device file. */
fun isGuestPath(path: String): Boolean = !path.startsWith("scratch:") && !isDevicePath(path)

/** Reads and writes device files by content URI. */
interface DeviceFileStore {
    fun read(uri: String): ByteArray
    fun write(uri: String, bytes: ByteArray)
}

class ContentResolverFileStore(context: Context) : DeviceFileStore {
    private val resolver = context.applicationContext.contentResolver

    override fun read(uri: String): ByteArray =
        resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: throw IOException("Can't open $uri")

    /** "wt" truncates; some providers only know "w", which then has to be enough. */
    override fun write(uri: String, bytes: ByteArray) {
        val target = Uri.parse(uri)
        val stream = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: resolver.openOutputStream(target, "w")
            ?: throw IOException("Can't write $uri")
        stream.use { it.write(bytes) }
    }
}
