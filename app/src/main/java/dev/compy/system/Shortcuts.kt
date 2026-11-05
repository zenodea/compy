package dev.compy.system

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.compy.MainActivity
import dev.compy.R

/** Long-press shortcuts on the launcher icon, for windows opened recently. */
object Shortcuts {
    fun publish(context: Context, serverId: String, sessionId: String, title: String) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_SERVER, serverId)
            .putExtra(MainActivity.EXTRA_SESSION, sessionId)
        val shortcut = ShortcutInfoCompat.Builder(context, "$serverId/$sessionId")
            .setShortLabel(title.take(24).ifEmpty { "Session" })
            .setLongLabel(title.take(48).ifEmpty { "Session" })
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut))
            .setIntent(intent)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}
