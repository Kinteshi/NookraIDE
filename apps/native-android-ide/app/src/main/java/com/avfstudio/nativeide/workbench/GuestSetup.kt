package com.avfstudio.nativeide.workbench

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Assets the build bundles for "Set up guest" (see scripts/build-guest-bundle.sh). */
private val GUEST_BUNDLE_FILES = listOf("avf-native-daemon", "install-native-daemon.sh")
private const val GUEST_BUNDLE_FOLDER = "avf-studio"

/** The Linux Terminal app sees Android's shared storage at this path. */
private const val GUEST_SHARED_DOWNLOADS = "/mnt/shared/Download"

/** The endpoint of the daemon that `install-native-daemon.sh` sets up, through the Terminal app's forwarding. */
const val GUEST_DAEMON_ENDPOINT = "ws://127.0.0.1:19870/ws"

fun guestBundleAvailable(context: Context): Boolean =
    runCatching { context.assets.list("guest").orEmpty().toSet().containsAll(GUEST_BUNDLE_FILES) }.getOrDefault(false)

/**
 * Copies the bundled daemon and installer to `Download/avf-studio/setup-<time>/` (a fresh folder,
 * because Android won't let an app overwrite Downloads entries it no longer owns) and returns the
 * command to run in Linux Terminal. Older setup folders this app created are removed.
 */
fun exportGuestBundle(context: Context): String {
    if (!guestBundleAvailable(context)) {
        throw IOException("This build has no guest bundle; build it with scripts/build-guest-bundle.sh")
    }
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    resolver.delete(
        collection,
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
        arrayOf("${android.os.Environment.DIRECTORY_DOWNLOADS}/$GUEST_BUNDLE_FOLDER/setup-%"),
    )
    val folder = "setup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    val relativePath = "${android.os.Environment.DIRECTORY_DOWNLOADS}/$GUEST_BUNDLE_FOLDER/$folder/"
    val stored = GUEST_BUNDLE_FILES.map { name ->
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Android refused to create Download/$name")
        resolver.openOutputStream(uri, "w")?.use { output ->
            context.assets.open("guest/$name").use { it.copyTo(output) }
        } ?: throw IOException("Could not write Download/$name")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        // MediaStore may adjust the name (for example add an extension); use what it stored.
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: name
    }
    val directory = "$GUEST_SHARED_DOWNLOADS/$GUEST_BUNDLE_FOLDER/$folder"
    return "bash '$directory/${stored[1]}' '$directory/${stored[0]}'"
}
