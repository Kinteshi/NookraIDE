package io.github.kinteshi.quirecode

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * QuireCode's Quick Settings tile: a tap opens the IDE; the tile is lit while the app is
 * connected to the guest.
 */
class IdeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (connected) "Connected" else "Not connected"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    }

    companion object {
        @Volatile private var connected = false

        /** The workbench's connection changed: the tile shows it the next time it is drawn. */
        fun update(context: Context, isConnected: Boolean) {
            if (connected == isConnected) return
            connected = isConnected
            runCatching { requestListeningState(context, ComponentName(context, IdeTileService::class.java)) }
        }
    }
}
