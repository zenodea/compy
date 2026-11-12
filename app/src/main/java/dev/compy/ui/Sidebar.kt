package dev.compy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ViewKanban
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.input.ImeAction
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
import dev.compy.rex.asWindow
import dev.compy.rex.SessionSummary
import dev.compy.rex.SessionView
import dev.compy.rex.WindowView
import kotlinx.coroutines.CancellationException
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
    onOpenBoard: () -> Unit,
    onOpenSaved: () -> Unit,
    onAddServer: () -> Unit,
    onOpened: () -> Unit,
    onMessage: (String) -> Unit,
    onHideSidebar: (() -> Unit)? = null,
) {
    val savedPanes by app.saved.list.collectAsState()
    val settings by app.settings.collectAsStateWithLifecycle()
    val connections by app.connections.collectAsStateWithLifecycle()
    val selection by app.selection.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    if (creating) {
        NewSessionDialog(
            servers = connections.values.toList(),
            preferred = selection?.serverId,
            onDismiss = { creating = false },
            onCreated = { connection, id ->
                creating = false
                app.select(Selection(connection.config.id, id))
                onOpened()
            },
            onMessage = onMessage,
        )
    }
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                val pillWidth by animateDpAsState(if (searching) maxWidth else 160.dp, motion(), label = "search")
                Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    !searching,
                    enter = expandHorizontally(motion()) + fadeIn(motion(MOTION_SHORT)),
                    exit = shrinkHorizontally(motion()) + fadeOut(motion(MOTION_SHORT)),
                ) {
                    RoundButton(Icons.Rounded.Add, "New session", modifier = Modifier.padding(end = 8.dp)) { creating = true }
                }
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
                    onOpenBoard = onOpenBoard,
                    modifier = Modifier.width(pillWidth),
                )
                }
            }
            if (onHideSidebar != null) {
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Rounded.ViewSidebar, "Hide sessions", tint = scheme.onSurfaceVariant, onClick = onHideSidebar)
            }
        }
        val servers = connections.values.toList()
        val activity = if (settings.activityMode) activityData(servers, query) else null
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (activity != null) {
                // Rows keep their identity across groups, so they glide when their status changes.
                Priority.entries.forEach { priority ->
                    val group = activity.rows.filter { it.priority == priority }
                    if (group.isNotEmpty()) {
                        item(key = "group-${priority.name}") {
                            Text(
                                priority.title,
                                Modifier.animateItem().padding(start = 18.dp, top = 12.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        items(group, key = { "pane-${it.connection.config.id}-${it.block.id}" }) { row ->
                            WindowRow(
                                title = activity.titles[row.block.id] ?: row.window.displayTitle(activity.titles),
                                subtitle = row.status?.message ?: row.session.label,
                                selected = false,
                                modifier = Modifier.animateItem(),
                                tile = {
                                    Box(contentAlignment = Alignment.TopEnd) {
                                        ProgramTile(ProgramNames.candidates(row.status?.app, activity.programs[row.block.id]))
                                        StatusDot(listOf(row.block.id), activity.statuses, activity.attention)
                                    }
                                },
                                onClick = {
                                    app.select(Selection(row.connection.config.id, row.session.id))
                                    app.revealPane(row.connection, row.session.id, row.block.id)
                                    onOpened()
                                },
                            )
                        }
                    }
                }
            } else {
                servers.forEach { connection ->
                    item(key = connection.config.id) {
                        Box(Modifier.animateItem()) {
                            ServerSection(connection, selection, query, settings.collapsedSessions, app, onOpened, onMessage)
                        }
                    }
                }
            }
            // Reachable with no server connected, since reading offline is what they are for.
            if (savedPanes.isNotEmpty()) {
                item(key = "saved-panes") {
                    Row(
                        Modifier
                            .animateItem()
                            .padding(horizontal = 8.dp)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenSaved)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Bookmarks, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                        Text(
                            if (savedPanes.size == 1) "1 saved pane" else "${savedPanes.size} saved panes",
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "add-server") {
                Row(
                    Modifier
                        .animateItem()
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
    onOpenBoard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
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
            RoundButton(Icons.Rounded.ViewKanban, "Agent board", filled = false, tint = scheme.onSurfaceVariant, onClick = onOpenBoard)
            RoundButton(Icons.Rounded.Search, "Search", filled = false, tint = scheme.onSurfaceVariant, onClick = onStartSearch)
        }
        if (!searching) Spacer(Modifier.weight(1f))
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
    val folders by connection.folders.collectAsStateWithLifecycle()
    val progress by connection.progress.collectAsStateWithLifecycle()
    val serverLabel by connection.label.collectAsStateWithLifecycle()
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
                Text(serverLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            } catch (e: CancellationException) {
                                throw e
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
                CompositionLocalProvider(LocalBlockProgress provides progress) {
                    SessionGroup(connection, session, view, selection, query, statuses, attention, titles, programs, folders, session.id in collapsed, app, onOpened, onMessage)
                }
            }
        }
    }
    importing?.let { hosts ->
        AlertDialog(
            onDismissRequest = { importing = null },
            title = { Text("Add hosts from ${serverLabel}") },
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
        RenameDialog("Rename server", serverLabel, onDismiss = { renaming = false }) {
            renaming = false
            app.renameServer(connection.config.id, it)
        }
    }
    if (creating) {
        NewSessionDialog(
            servers = listOf(connection),
            preferred = connection.config.id,
            onDismiss = { creating = false },
            onCreated = { _, id ->
                creating = false
                app.select(Selection(connection.config.id, id))
                onOpened()
            },
            onMessage = onMessage,
        )
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${serverLabel}?") },
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

/** Starts a session. It's ready to confirm straight away, because naming one is optional. */
@Composable
private fun NewSessionDialog(
    servers: List<ServerConnection>,
    preferred: String?,
    onDismiss: () -> Unit,
    onCreated: (ServerConnection, String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var serverId by remember {
        val connected = servers.filter { it.state.value == ConnectionState.Connected }
        mutableStateOf((connected.firstOrNull { it.config.id == preferred } ?: connected.firstOrNull() ?: servers.firstOrNull())?.config?.id)
    }
    var starting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun start() {
        val connection = servers.firstOrNull { it.config.id == serverId } ?: return
        if (starting) return
        starting = true
        scope.launch {
            try {
                val id = connection.createSession(name.ifBlank { null })
                if (id != null) onCreated(connection, id) else onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(e.message ?: "Couldn't start a session")
                onDismiss()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { start() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (servers.size > 1) {
                    Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    servers.forEach { server ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { serverId = server.config.id }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(serverId == server.config.id, null, Modifier.padding(8.dp))
                            Text(server.label.collectAsStateWithLifecycle().value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::start, enabled = serverId != null && !starting) { Text("Start") } },
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
    folders: Map<String, String>,
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
            Icons.Rounded.ExpandLess,
            if (collapsed) "Expand" else "Collapse",
            size = 30.dp, filled = false, tint = muted,
            iconRotation = if (collapsed) 180f else 0f,
        ) {
            app.updateSettings {
                it.copy(collapsedSessions = if (collapsed) it.collapsedSessions - session.id else it.collapsedSessions + session.id)
            }
        }
    }
    AnimatedVisibility(
        !collapsed,
        enter = expandVertically(motion()) + fadeIn(motion(MOTION_SHORT)),
        exit = shrinkVertically(motion()) + fadeOut(motion(MOTION_SHORT)),
    ) {
        Column(Modifier.animateContentSize(motion())) {
            view?.windows?.forEach { window ->
                val title = window.displayTitle(titles)
                if (query.isNotBlank() && !title.contains(query, true) && !session.label.contains(query, true)) return@forEach
                val selected = selection?.serverId == connection.config.id && selection.sessionId == session.id && view.activeWindow?.id == window.id
                key(window.id) {
                    WindowRow(
                        title = title,
                        subtitle = windowSubtitle(window, statuses, folders),
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
            val detached by connection.detached.collectAsStateWithLifecycle()
            val loose = detached[session.id].orEmpty()
            if (loose.isNotEmpty() && (query.isBlank() || session.label.contains(query, true))) {
                WindowRow(
                    title = if (loose.size == 1) "1 detached pane" else "${loose.size} detached panes",
                    subtitle = "Running outside any window",
                    selected = false,
                    tile = {
                        Icon(Icons.Rounded.LinkOff, null, Modifier.size(22.dp), tint = muted)
                    },
                    onClick = {
                        app.select(Selection(connection.config.id, session.id))
                        app.showDetached(session.id)
                        onOpened()
                    },
                )
            }
        }
    }
}

/** What's most useful under a window's name: what it needs, what it's doing, or where it is. */
private fun windowSubtitle(window: WindowView, statuses: Map<String, ProgramStatus>, folders: Map<String, String>): String? {
    val blocks = window.blocks
    return blocks.firstNotNullOfOrNull { statuses[it.id]?.takeIf { s -> s.isBlocked }?.message }
        ?: blocks.firstNotNullOfOrNull { statuses[it.id]?.takeIf { s -> s.isWorking }?.message }
        ?: folders[window.focusedBlockId ?: blocks.firstOrNull()?.id]
}

@Composable
private fun WindowRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    tile: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (selected) scheme.surfaceContainerHigh else scheme.background.copy(alpha = 0f),
        motion(),
        label = "row",
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile()
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(
                subtitle != null,
                enter = expandVertically(motion()) + fadeIn(motion(MOTION_SHORT)),
                exit = shrinkVertically(motion()) + fadeOut(motion(MOTION_SHORT)),
            ) {
                Text(subtitle.orEmpty(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private class ActivityData(
    val rows: List<PaneRow>,
    val statuses: Map<String, ProgramStatus>,
    val attention: Set<String>,
    val titles: Map<String, String>,
    val programs: Map<String, String>,
)

private fun priorityOf(status: ProgramStatus?, attention: Boolean) = when {
    status?.isBlocked == true -> Priority.Blocked
    status?.isError == true -> Priority.Error
    status?.isWorking == true -> Priority.Working
    status?.isDone == true || attention -> Priority.Done
    else -> Priority.Quiet
}

/** Gathers panes from every server, each with how much it needs attention. */
@Composable
private fun activityData(servers: List<ServerConnection>, query: String): ActivityData {
    val rows = mutableListOf<PaneRow>()
    val attentionAll = mutableSetOf<String>()
    val statusAll = mutableMapOf<String, ProgramStatus>()
    val titlesAll = mutableMapOf<String, String>()
    val programsAll = mutableMapOf<String, String>()
    for (connection in servers) {
        key(connection.config.id) {
            val sessions by connection.sessions.collectAsState()
            val views by connection.views.collectAsState()
            val statuses by connection.statuses.collectAsState()
            val attention by connection.attention.collectAsState()
            val titles by connection.titles.collectAsState()
            val programs by connection.programs.collectAsState()
            val detached by connection.detached.collectAsState()
            titlesAll += titles
            attentionAll += attention
            statusAll += statuses
            programsAll += programs
            // Rows are keyed by block, and a duplicate key crashes the list. The detached list is
            // read after the layout, so a pane just placed in a window is briefly in both.
            val listed = HashSet<String>()
            for (session in sessions) {
                val view = views[session.id] ?: continue
                for (window in view.windows) for (block in window.blocks) {
                    if (!listed.add(block.id)) continue
                    val status = statuses[block.id]
                    rows += PaneRow(connection, session, window, block, status, priorityOf(status, block.id in attention))
                }
                for (block in detached[session.id].orEmpty()) {
                    if (!listed.add(block.id)) continue
                    val status = statuses[block.id]
                    rows += PaneRow(connection, session, block.asWindow(), block, status, priorityOf(status, block.id in attention))
                }
            }
        }
    }
    val filtered = rows.filter {
        // The same title the row shows.
        query.isBlank() || (titlesAll[it.block.id] ?: it.window.displayTitle(titlesAll)).contains(query, true) || it.session.label.contains(query, true) ||
            it.status?.message?.contains(query, true) == true
    }
    return ActivityData(filtered, statusAll, attentionAll, titlesAll, programsAll)
}
