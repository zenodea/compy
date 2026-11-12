package dev.compy.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.compy.MainActivity
import dev.compy.R

/** A home screen summary showing the last known state, with how old it is. */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    companion object {
        private val ROWS = intArrayOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3, R.id.widget_row_4, R.id.widget_row_5)

        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val snapshot = StatusSnapshot.read(context)
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            views.setTextViewText(R.id.widget_summary, snapshot.summary ?: "No data yet")
            views.setTextViewText(R.id.widget_age, StatusSnapshot.updated(context, snapshot))
            // A row for each agent, which opens that agent's pane. With none to name, the rows
            // list the sessions and a tap anywhere opens the app.
            ROWS.forEachIndexed { i, id ->
                val row = snapshot.rows.getOrNull(i)
                val text = row?.text ?: snapshot.lines.getOrNull(i).takeIf { snapshot.rows.isEmpty() }
                views.setViewVisibility(id, if (text == null) android.view.View.GONE else android.view.View.VISIBLE)
                views.setTextViewText(id, text.orEmpty())
                if (row != null) {
                    val target = Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SERVER, row.server)
                        .putExtra(MainActivity.EXTRA_SESSION, row.session)
                        .putExtra(MainActivity.EXTRA_BLOCK, row.block)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    // One for each row: the number tells them apart, since what they carry doesn't.
                    views.setOnClickPendingIntent(
                        id,
                        PendingIntent.getActivity(context, 100 + i, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                }
            }
            val intent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, intent)
            manager.updateAppWidget(ids, views)
        }
    }
}
