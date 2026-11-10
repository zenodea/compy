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
        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val snapshot = StatusSnapshot.read(context)
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            views.setTextViewText(R.id.widget_summary, snapshot.summary ?: "No data yet")
            views.setTextViewText(R.id.widget_age, StatusSnapshot.updated(context, snapshot))
            views.setTextViewText(R.id.widget_lines, snapshot.lines.joinToString("\n"))
            val intent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, intent)
            manager.updateAppWidget(ids, views)
        }
    }
}
