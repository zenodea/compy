package dev.compy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.AppState
import dev.compy.BuildConfig
import dev.compy.Selection
import dev.compy.rex.BlockInfo
import dev.compy.rex.ConnectionState
import dev.compy.rex.HostEntry
import dev.compy.rex.ProgramStatus
import dev.compy.rex.ServerConnection
import dev.compy.rex.SessionSummary
import dev.compy.rex.SessionView
import dev.compy.rex.WindowView
import kotlinx.coroutines.launch

private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

private enum class Priority(val title: String) {
    Blocked("Needs you"), Error("Errors"), Working("Working"), Done("Finished"), Quiet("Quiet")
}

private data class PaneRow(
    val connection: ServerConnection,
    val session: SessionSummary,
    val window: WindowView,
    val block: BlockInfo,
    val status: ProgramStatus?,
    val priority: Priority,
)

/** The list of servers, sessions and windows, or panes by priority when Activity is on. */
@Composable
fun Sidebar(
    app: AppState,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    onOpened: () -> Unit,
    onMessage: (String) -> Unit,
    onHideSidebar: (() -> Unit)? = null,
) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val connections by app.connections.collectAsStateWithLifecycle()
    val selection by app.selection.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    Column(
        modifier
            .fillMaxHeight()
            .background(scheme.background)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolPill(
                activityOn = settings.activityMode,
                onToggleActivity = { app.updateSettings { it.copy(activityMode = !it.activityMode) } },
                searching = searching,
                query = query,
                onQuery = { query = it },
                onStartSearch = { searching = true },
                onStopSearch = {
                    searching = false
                    query = ""
                },
                onOpenSettings = onOpenSettings,
                modifier = if (searching) Modifier.weight(1f) else Modifier,
            )
            if (onHideSidebar != null) {
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Rounded.ViewSidebar, "Hide sessions", tint = scheme.onSurfaceVariant, onClick = onHideSidebar)
            }
        }
        val servers = connections.values.toList()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (settings.activityMode) {
                item(key = "activity") { ActivityList(servers, query, app, onOpened) }
            } else {
                servers.forEach { connection ->
                    item(key = connection.config.id) {
                        ServerSection(connection, selection, query, settings.collapsedSessions, app, onOpened, onMessage)
                    }
                }
            }
            item(key = "add-server") {
                Row(
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onAddServer)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                    Text("Add server", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Activity, search and settings in one pill. Search is an icon until it's tapped, then a field. */
@Composable
private fun ToolPill(
    activityOn: Boolean,
    onToggleActivity: () -> Unit,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onStartSearch: () -> Unit,
    onStopSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .animateContentSize()
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(
            Icons.Rounded.Visibility, "Activity", filled = false,
            tint = if (activityOn) scheme.primary else scheme.onSurfaceVariant,
            onClick = onToggleActivity,
        )
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = scheme.primary)
            Box(Modifier.padding(start = 8.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                BasicTextField(
                    query, onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            RoundButton(Icons.Rounded.Close, "Close search", size = 36.dp, filled = false, tint = scheme.onSurfaceVariant, onClick = onStopSearch)
        } else {
            RoundButton(Icons.Rounded.Search, "Search", filled = false, tint = scheme.onSurfaceVariant, onClick = onStartSearch)
        }
        RoundButton(Icons.Rounded.Settings, "Settings", filled = false, tint = scheme.onSurfaceVariant, onClick = onOpenSettings)
    }
}

@Composable
private fun ServerSection(
    connection: ServerConnection,
    selection: Selection?,
    query: String,
    collapsed: Set<String>,
    app: AppState,
    onOpened: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by connection.state.collectAsStateWithLifecycle()
    val sessions by connection.sessions.collectAsStateWithLifecycle()
    val views by connection.views.collectAsStateWithLifecycle()
    val statuses by connection.statuses.collectAsStateWithLifecycle()
    val attention by connection.attention.collectAsStateWithLifecycle()
    val titles by connection.titles.collectAsStateWithLifecycle()
    val programs by connection.programs.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<List<HostEntry>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val saved by app.servers.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Laptop, null, Modifier.size(20.dp), tint = scheme.onSurface)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(connection.config.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = when (val s = state) {
                    ConnectionState.Connected -> null
                    ConnectionState.Connecting -> "Connecting…"
                    is ConnectionState.Offline -> s.reason
                }
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2)
                }
                if (state is ConnectionState.Offline && ".ts.net" in connection.config.url && !isVpnOn(context)) {
                    val launch = remember { context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE) }
                    Text("Tailscale looks off on this phone.", style = MaterialTheme.typography.labelSmall, color = scheme.error)
                    if (launch != null) {
                        TextButton(onClick = { context.startActivity(launch) }) { Text("Open Tailscale") }
                    }
                }
            }
            Box {
                RoundButton(Icons.Rounded.MoreHoriz, "Server options", size = 36.dp, filled = false, tint = scheme.onSurfaceVariant) { menu = true }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("New session") }, onClick = {
                        menu = false
                        creating = true
                    })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menu = false
                        renaming = true
                    })
                    DropdownMenuItem(text = { Text("Add its other hosts") }, onClick = {
                        menu = false
                        scope.launch {
                            try {
                                val hosts = connection.listHosts()
                                val known = saved.map { it.url }
                                importing = hosts.filter { host ->
                                    normalizeServerUrl(host.endpoint, BuildConfig.ALLOW_CLEARTEXT).getOrNull()?.let { it !in known } == true
                                }
                            } catch (e: Exception) {
                                onMessage(e.message ?: "Couldn't read this server's hosts")
                            }
                        }
                    })
                    DropdownMenuItem(text = { Text("Connect again") }, onClick = {
                        menu = false
                        connection.retryNow()
                    })
                    DropdownMenuItem(text = { Text("Remove from Compy") }, onClick = {
                        menu = false
                        removing = true
                    })
                }
            }
        }
        if (sessions.isEmpty() && state == ConnectionState.Connected) {
            Text(
                "Start a session",
                Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(CircleShape)
                    .background(scheme.primary)
                    .clickable { creating = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimary,
            )
        }
        sessions.forEach { session ->
            val view = views[session.id]
            val matches = query.isBlank() || session.label.contains(query, ignoreCase = true) ||
                view?.windows?.any { it.displayTitle(titles).contains(query, ignoreCase = true) } == true
            if (matches) {
                SessionGroup(connection, session, view, selection, query, statuses, attention, titles, programs, session.id in collapsed, app, onOpened, onMessage)
            }
        }
    }
    importing?.let { hosts ->
        AlertDialog(
            onDismissRequest = { importing = null },
            title = { Text("Add hosts from ${connection.config.label}") },
            text = {
                if (hosts.isEmpty()) {
                    Text("This server has no other hosts to add.")
                } else {
                    Column {
                        hosts.forEach { host ->
                            ListItem(
                                headlineContent = { Text(host.label) },
                                supportingContent = { Text(host.endpoint) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable {
                                    normalizeServerUrl(host.endpoint, BuildConfig.ALLOW_CLEARTEXT).getOrNull()?.let { app.addServer(host.label, it) }
                                    importing = hosts - host
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { importing = null }) { Text("Done") } },
        )
    }
    if (renaming) {
        RenameDialog("Rename server", connection.config.label, onDismiss = { renaming = false }) {
            renaming = false
            app.renameServer(connection.config.id, it)
        }
    }
    if (creating) {
        NewSessionDialog(onDismiss = { creating = false }) { label ->
            creating = false
            scope.launch {
                try {
                    val id = connection.createSession(label)
                    if (id != null) {
                        app.select(Selection(connection.config.id, id))
                        onOpened()
                    }
                } catch (e: Exception) {
                    onMessage(e.message ?: "Couldn't start a session")
                }
            }
        }
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${connection.config.label}?") },
            text = { Text("Compy forgets this server. Its sessions keep running.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    app.removeServer(connection.config.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NewSessionDialog(onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name.ifBlank { null }) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SessionGroup(
    connection: ServerConnection,
    session: SessionSummary,
    view: SessionView?,
    selection: Selection?,
    query: String,
    statuses: Map<String, ProgramStatus>,
    attention: Set<String>,
    titles: Map<String, String>,
    programs: Map<String, String>,
    collapsed: Boolean,
    app: AppState,
    onOpened: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            session.label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RoundButton(Icons.Rounded.Add, "New window in ${session.label}", size = 30.dp, filled = false, tint = muted) {
            connection.newWindow(session.id, onMessage)
        }
        RoundButton(
            if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
            if (collapsed) "Expand" else "Collapse",
            size = 30.dp, filled = false, tint = muted,
        ) {
            app.updateSettings {
                it.copy(collapsedSessions = if (collapsed) it.collapsedSessions - session.id else it.collapsedSessions + session.id)
            }
        }
    }
    if (collapsed) return
    view?.windows?.forEach { window ->
        val title = window.displayTitle(titles)
        if (query.isNotBlank() && !title.contains(query, true) && !session.label.contains(query, true)) return@forEach
        val selected = selection?.serverId == connection.config.id && selection.sessionId == session.id && view.activeWindow?.id == window.id
        WindowRow(
            title = title,
            subtitle = window.blocks.firstNotNullOfOrNull { statuses[it.id]?.takeIf { s -> s.isBlocked }?.message },
            selected = selected,
            tile = { WindowTile(window, statuses, programs, attention) },
            onClick = {
                app.select(Selection(connection.config.id, session.id))
                connection.focusWindow(session.id, window.id)
                app.recordRecent(connection.config.id, session.id, title)
                onOpened()
            },
        )
    }
}

@Composable
private fun WindowRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    tile: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile()
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Panes from every server grouped by how much they need attention. */
@Composable
private fun ActivityList(servers: List<ServerConnection>, query: String, app: AppState, onOpened: () -> Unit) {
    val rows = mutableListOf<PaneRow>()
    val attentionAll = mutableSetOf<String>()
    val statusAll = mutableMapOf<String, ProgramStatus>()
    val titlesAll = mutableMapOf<String, String>()
    val programsAll = mutableMapOf<String, String>()
    for (connection in servers) {
        val sessions by connection.sessions.collectAsState()
        val views by connection.views.collectAsState()
        val statuses by connection.statuses.collectAsState()
        val attention by connection.attention.collectAsState()
        val titles by connection.titles.collectAsState()
        val programs by connection.programs.collectAsState()
        titlesAll += titles
        attentionAll += attention
        statusAll += statuses
        programsAll += programs
        for (session in sessions) {
            val view = views[session.id] ?: continue
            for (window in view.windows) for (block in window.blocks) {
                val status = statuses[block.id]
                val priority = when {
                    status?.isBlocked == true -> Priority.Blocked
                    status?.isError == true -> Priority.Error
                    status?.isWorking == true -> Priority.Working
                    status?.isDone == true || block.id in attention -> Priority.Done
                    else -> Priority.Quiet
                }
                rows += PaneRow(connection, session, window, block, status, priority)
            }
        }
    }
    val filtered = rows.filter {
        query.isBlank() || it.window.displayTitle(titlesAll).contains(query, true) || it.session.label.contains(query, true) ||
            it.status?.message?.contains(query, true) == true
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Priority.entries.forEach { priority ->
            val group = filtered.filter { it.priority == priority }
            if (group.isEmpty()) return@forEach
            Text(
                priority.title,
                Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            group.forEach { row ->
                WindowRow(
                    title = titlesAll[row.block.id] ?: row.window.displayTitle(titlesAll),
                    subtitle = row.status?.message ?: row.session.label,
                    selected = false,
                    tile = {
                        Box(contentAlignment = Alignment.TopEnd) {
                            ProgramTile(ProgramNames.candidates(row.status?.app, programsAll[row.block.id]))
                            StatusDot(listOf(row.block.id), statusAll, attentionAll)
                        }
                    },
                    onClick = {
                        app.select(Selection(row.connection.config.id, row.session.id))
                        row.connection.focusBlock(row.session.id, row.block.id)
                        onOpened()
                    },
                )
            }
        }
    }
}
