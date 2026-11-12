package dev.compy.system

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.text.format.DateUtils
import dev.compy.AppState
import dev.compy.rex.ConnectionState
import dev.compy.rex.ProgramStatus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One agent on the widget, and the pane a tap on it opens. */
@Serializable
data class WidgetRow(val text: String, val server: String, val session: String, val block: String)

/** What the widget and the ongoing notification say about the agents, worked out from their statuses. */
object AgentSummary {
    /** One agent as the summary sees it. [percent] is how far along its work is, when it says. */
    data class Agent(val name: String, val status: ProgramStatus, val percent: Int? = null, val server: String = "", val session: String = "", val block: String = "")

    /** How pressing an agent is: one that needs you first, then one that failed, then one at work. */
    private fun rank(status: ProgramStatus) = when {
        status.isBlocked -> 0
        status.isError -> 1
        status.isWorking -> 2
        else -> 3
    }

    private fun says(agent: Agent): String = when {
        agent.status.isBlocked -> "${agent.name} ${agent.status.blockedReason}"
        agent.status.isError -> "${agent.name} failed"
        else -> agent.status.message?.takeIf { it.isNotBlank() }?.let { "${agent.name}: $it" } ?: "${agent.name} is working"
    }

    /** The agents worth a row, most pressing first, and no more than [limit]. Idle and finished ones have none. */
    fun rows(agents: List<Agent>, limit: Int): List<WidgetRow> =
        agents.filter { rank(it.status) < 3 }
            .sortedWith(compareBy<Agent> { rank(it.status) }.thenByDescending { it.status.since })
            .take(limit)
            .map { WidgetRow(says(it), it.server, it.session, it.block) }

    /** The ongoing notification: its title, its line of text, and a bar when one agent says how far along it is. */
    data class Notice(val title: String, val text: String, val percent: Int?)

    fun notice(agents: List<Agent>): Notice {
        val blocked = agents.filter { it.status.isBlocked }
        val working = agents.filter { it.status.isWorking }
        if (blocked.isNotEmpty()) {
            val title = if (blocked.size == 1) "1 agent needs you" else "${blocked.size} agents need you"
            return Notice(title, says(blocked.maxBy { it.status.since }), null)
        }
        if (working.isEmpty()) return Notice("Watching your Rex servers", "Compy will alert you when an agent needs you", null)
        // The bar is one agent's: the one whose work began last, of those that say how far along they are.
        val measured = working.filter { it.percent != null && it.percent >= 0 }.maxByOrNull { it.status.since }
        val shown = measured ?: working.maxBy { it.status.since }
        val title = if (working.size == 1) "1 agent working" else "${working.size} agents working"
        return Notice(title, says(shown), measured?.percent?.coerceIn(0, 100))
    }
}

/** The last known state, saved for the widget and tile, which run without a connection. */
@Serializable
data class StatusSnapshot(
    val savedAt: Long = 0,
    val blocked: Int = 0,
    val working: Int = 0,
    val sessions: Int = 0,
    val lines: List<String> = emptyList(),
    /** The agents that need you or are at work, each with the pane to open. */
    val rows: List<WidgetRow> = emptyList(),
) {
    /** "2 need you", "3 working" or "4 sessions". Null before anything was saved. */
    val summary: String?
        get() = when {
            savedAt == 0L -> null
            blocked > 0 -> "$blocked need you"
            working > 0 -> "$working working"
            else -> "$sessions sessions"
        }

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

        /**
         * When the snapshot was saved, as a date and time. The widget is drawn when a snapshot is
         * saved and then sits unchanged, so an age such as "just now" would be wrong within minutes.
         */
        fun updated(context: Context, snapshot: StatusSnapshot): String {
            if (snapshot.savedAt == 0L) return "Open Compy to update"
            val flags = DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
            return "Updated ${DateUtils.formatDateTime(context, snapshot.savedAt, flags)}"
        }
    }
}

/** How many agents the widget has room to name. */
const val WIDGET_ROWS = 5

/** Every pane on every server that reports a status, as the widget and notification want them. */
fun agents(app: AppState): List<AgentSummary.Agent> {
    val all = mutableListOf<AgentSummary.Agent>()
    for (c in app.connections.value.values) {
        val statuses = c.statuses.value
        val titles = c.titles.value
        val programs = c.programs.value
        val progress = c.progress.value
        for (session in c.sessions.value) {
            val view = c.views.value[session.id] ?: continue
            for (window in view.windows) for (block in window.blocks) {
                val status = statuses[block.id] ?: continue
                val name = dev.compy.ui.BoardRules.title(status, programs[block.id], titles[block.id])
                all += AgentSummary.Agent(name, status, status.progress ?: progress[block.id], c.config.id, session.id, block.id)
            }
        }
    }
    return all
}

/** Refreshes the snapshot for the widget and tile whenever an agent's state changes. */
class StatusPublisher(private val context: Context, private val app: AppState) {
    private val dirty = MutableStateFlow(0)

    @OptIn(FlowPreview::class)
    fun start() {
        app.scope.launch {
            dirty.debounce(1_500).collect { publish() }
        }
        app.scope.launch {
            // collectLatest ends the watchers of the previous set of servers. Without that, every
            // change to the list added three more per server and kept removed servers alive.
            app.connections.collectLatest { map ->
                coroutineScope {
                    map.values.forEach { c ->
                        launch { c.statuses.collect { dirty.update { it + 1 } } }
                        launch { c.progress.collect { dirty.update { it + 1 } } }
                        launch { c.views.collect { dirty.update { it + 1 } } }
                        launch { c.state.collect { dirty.update { it + 1 } } }
                    }
                }
            }
        }
    }

    private fun publish() {
        val connections = app.connections.value.values
        if (connections.none { it.state.value == ConnectionState.Connected }) return
        val agents = agents(app)
        val blocked = agents.count { it.status.isBlocked }
        val working = agents.count { it.status.isWorking }
        val sessions = connections.sumOf { it.sessions.value.size }
        val rows = AgentSummary.rows(agents, WIDGET_ROWS)
        // With no agent to name, the widget lists the sessions instead.
        val lines = if (rows.isNotEmpty()) rows.map { it.text } else connections.flatMap { c -> c.sessions.value.map { it.label } }.take(WIDGET_ROWS)
        StatusSnapshot.write(context, StatusSnapshot(System.currentTimeMillis(), blocked, working, sessions, lines, rows))
        val widgets = AppWidgetManager.getInstance(context)
        val ids = widgets.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
        if (ids.isNotEmpty()) StatusWidget.update(context, widgets, ids)
        runCatching { TileService.requestListeningState(context, ComponentName(context, CompyTile::class.java)) }
    }
}
