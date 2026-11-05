package dev.compy.system

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import dev.compy.AppState
import dev.compy.rex.ConnectionState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The last known state, saved for the widget and tile, which run without a connection. */
@Serializable
data class StatusSnapshot(
    val savedAt: Long = 0,
    val blocked: Int = 0,
    val working: Int = 0,
    val sessions: Int = 0,
    val lines: List<String> = emptyList(),
) {
    companion object {
        private const val PREFS = "status"
        private const val KEY = "snapshot"

        fun read(context: Context): StatusSnapshot {
            val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return StatusSnapshot()
            return runCatching { Json.decodeFromString<StatusSnapshot>(text) }.getOrDefault(StatusSnapshot())
        }

        fun write(context: Context, snapshot: StatusSnapshot) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, Json.encodeToString(snapshot)).apply()
        }

        fun age(snapshot: StatusSnapshot, now: Long = System.currentTimeMillis()): String {
            if (snapshot.savedAt == 0L) return "Open Compy to update"
            val minutes = (now - snapshot.savedAt) / 60_000
            return when {
                minutes < 1 -> "Updated just now"
                minutes < 60 -> "Updated $minutes min ago"
                minutes < 60 * 24 -> "Updated ${minutes / 60} h ago"
                else -> "Updated ${minutes / (60 * 24)} d ago"
            }
        }
    }
}

/** Refreshes the snapshot while the app is open. There's no background work, by design. */
class StatusPublisher(private val context: Context, private val app: AppState) {
    private val dirty = MutableStateFlow(0)

    @OptIn(FlowPreview::class)
    fun start() {
        app.scope.launch {
            dirty.debounce(1_500).collect { publish() }
        }
        app.scope.launch {
            app.connections.collect { map ->
                map.values.forEach { c ->
                    app.scope.launch { c.statuses.collect { dirty.value++ } }
                    app.scope.launch { c.views.collect { dirty.value++ } }
                    app.scope.launch { c.state.collect { dirty.value++ } }
                }
            }
        }
    }

    private fun publish() {
        val connections = app.connections.value.values
        if (connections.none { it.state.value == ConnectionState.Connected }) return
        var blocked = 0
        var working = 0
        var sessions = 0
        val lines = mutableListOf<String>()
        for (c in connections) {
            val statuses = c.statuses.value
            val titles = c.titles.value
            for (session in c.sessions.value) {
                sessions++
                val view = c.views.value[session.id] ?: continue
                for (window in view.windows) for (block in window.blocks) {
                    val status = statuses[block.id] ?: continue
                    val title = window.displayTitle(titles)
                    if (status.isBlocked) {
                        blocked++
                        lines.add(0, "● $title: needs you")
                    } else if (status.isWorking) {
                        working++
                        lines.add("○ $title: working")
                    }
                }
            }
        }
        if (lines.isEmpty()) {
            for (c in connections) for (session in c.sessions.value) lines += session.label
        }
        StatusSnapshot.write(context, StatusSnapshot(System.currentTimeMillis(), blocked, working, sessions, lines.take(4)))
        val widgets = AppWidgetManager.getInstance(context)
        val ids = widgets.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
        if (ids.isNotEmpty()) StatusWidget.update(context, widgets, ids)
        runCatching { TileService.requestListeningState(context, ComponentName(context, CompyTile::class.java)) }
    }
}
