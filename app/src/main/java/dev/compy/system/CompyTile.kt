package dev.compy.system

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.compy.MainActivity

/** A Quick Settings tile that opens Compy and shows the last known status. */
class CompyTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val snapshot = StatusSnapshot.read(this)
        tile.state = if (snapshot.blocked > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            snapshot.savedAt == 0L -> "Not opened yet"
            snapshot.blocked > 0 -> "${snapshot.blocked} need you"
            snapshot.working > 0 -> "${snapshot.working} working"
            else -> "${snapshot.sessions} sessions"
        }
        tile.updateTile()
    }

    override fun onClick() {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(intent)
    }
}
