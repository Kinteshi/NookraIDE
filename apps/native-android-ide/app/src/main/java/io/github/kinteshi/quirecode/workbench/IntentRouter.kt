package io.github.kinteshi.quirecode.workbench

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import io.github.kinteshi.quirecode.MainActivity
import io.github.kinteshi.quirecode.R

/**
 * Hands the activity's intents to the workbench: "Open with" from a file manager (a content URI
 * to view or edit), the share sheet, and the launcher's recent-folder shortcuts. An intent that arrives before the
 * workbench is composed waits for [handler].
 */
object IntentRouter {
    const val ACTION_OPEN_FOLDER = "io.github.kinteshi.quirecode.OPEN_FOLDER"
    const val EXTRA_FOLDER = "folder"

    private var pending: Intent? = null

    var handler: ((Intent) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) pending?.let { intent ->
                pending = null
                value(intent)
            }
        }

    fun deliver(intent: Intent?) {
        if (intent == null || intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, ACTION_OPEN_FOLDER)) return
        handler?.invoke(intent) ?: run { pending = intent }
    }
}

/**
 * The launcher's long-press menu: the most recent folders (up to four), each opening that folder
 * (`avf.launcher.recentFolders`). Empty [folders] removes them.
 */
fun updateLauncherShortcuts(context: Context, folders: List<String>) {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return
    runCatching {
        val limit = manager.maxShortcutCountPerActivity.coerceAtMost(4)
        val shortcuts = folders.take(limit).mapIndexed { rank, folder ->
            val name = folder.trimEnd('/').substringAfterLast('/').ifEmpty { folder }
            ShortcutInfo.Builder(context, "folder:$folder")
                .setShortLabel(name)
                .setLongLabel(folder)
                .setRank(rank)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_folder))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(IntentRouter.ACTION_OPEN_FOLDER)
                        .putExtra(IntentRouter.EXTRA_FOLDER, folder)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                .build()
        }
        manager.dynamicShortcuts = shortcuts
    }
}
