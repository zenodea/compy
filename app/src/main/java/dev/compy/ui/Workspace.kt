package dev.compy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.AppState
import dev.compy.data.SizeMode
import dev.compy.rex.ConnectionState
import dev.compy.rex.RexKey
import dev.compy.rex.ServerConnection
import dev.compy.rex.SessionView
import dev.compy.rex.WindowView

/** The right-hand side of the app: one session's panes, tabs, keys and actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Workspace(
    app: AppState,
    connection: ServerConnection,
    sessionId: String,
    compact: Boolean,
    sidebarHidden: Boolean = false,
    onShowSidebar: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(connection, sessionId) { WorkspaceController(app, connection, sessionId, context.applicationContext) }
    val views by connection.views.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()
    val warning by connection.versionWarning.collectAsStateWithLifecycle()
    val foreground by app.foreground.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val titles by connection.titles.collectAsStateWithLifecycle()
    val view = views[sessionId]
    val window = view?.activeWindow

    var overview by rememberSaveable(sessionId) { mutableStateOf(false) }
    var arranging by rememberSaveable(sessionId) { mutableStateOf(false) }
    var composer by rememberSaveable(sessionId) { mutableStateOf(false) }

    LaunchedEffect(window?.focusedBlockId, window?.id) {
        controller.focusedBlockId = window?.focusedBlockId ?: window?.blocks?.firstOrNull()?.id
        controller.focusedBlockId?.let(connection::clearAttention)
    }
    LaunchedEffect(foreground, state) {
        if (foreground && state == ConnectionState.Connected) controller.reapplySizes()
    }

    val imeVisible = WindowInsets.isImeVisible
    fun toggleKeyboard() {
        val target = controller.focusedView() ?: return
        if (imeVisible) target.hideKeyboard() else target.showKeyboard()
    }
    val arrangeInset by animateDpAsState(if (arranging) 28.dp else 0.dp, label = "arrange")

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding()),
        ) {
            if (compact) {
                CompactTopBar(controller, view, window, onShowSidebar, onOverview = { overview = true }, onArrange = { arranging = true }, onMessage = onMessage)
            }
            (state as? ConnectionState.Offline)?.let { Notice("Offline: ${it.reason}. Trying again…") }
            warning?.let { Notice(it) }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .padding(horizontal = arrangeInset, vertical = arrangeInset),
                    ) {
                        if (window == null) {
                            Text(
                                if (view == null) "Loading…" else "This session has no windows",
                                Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            androidx.compose.runtime.key(window.id) {
                                PaneArea(
                                    controller, window, Modifier.fillMaxSize(),
                                    onPaneTapped = { block ->
                                        if (block != controller.focusedBlockId) {
                                            controller.focusedBlockId = block
                                            connection.focusBlock(sessionId, block)
                                        } else {
                                            controller.focusedView()?.showKeyboard()
                                        }
                                        connection.clearAttention(block)
                                    },
                                    arranging = arranging,
                                    onDrop = { dragged, target, zone ->
                                        when (zone) {
                                            DropZone.Swap -> connection.swapBlocks(sessionId, dragged, target, onMessage)
                                            DropZone.Left -> connection.moveBlock(sessionId, dragged, target, true, true, onMessage)
                                            DropZone.Right -> connection.moveBlock(sessionId, dragged, target, true, false, onMessage)
                                            DropZone.Top -> connection.moveBlock(sessionId, dragged, target, false, true, onMessage)
                                            DropZone.Bottom -> connection.moveBlock(sessionId, dragged, target, false, false, onMessage)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (arranging) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                window?.displayTitle(titles).orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Drag a pane onto another's edge to split, or its center to swap",
                                Modifier.padding(top = 2.dp, bottom = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { arranging = false }) { Text("Done") }
                        }
                    } else if (view != null) {
                        WindowPills(controller, view, onMessage, onOpenComposer = { composer = true })
                        KeyBar(
                            controller,
                            keyboardVisible = imeVisible,
                            onToggleKeyboard = ::toggleKeyboard,
                            onOpenComposer = { composer = true },
                        )
                    }
                }
                if (!compact && !arranging) {
                    SideRail(controller, view, window, sidebarHidden, onOverview = { overview = true }, onArrange = { arranging = true }, onShowSidebar = onShowSidebar, onMessage = onMessage)
                }
            }
        }
        if (overview && view != null) {
            WindowOverview(controller, view, onClose = { overview = false }, onMessage = onMessage)
        }
    }

    if (composer) {
        ComposerSheet(controller, settings.composerSendsEnter, onClose = { composer = false })
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun CompactTopBar(
    controller: WorkspaceController,
    view: SessionView?,
    window: WindowView?,
    onBack: () -> Unit,
    onOverview: () -> Unit,
    onArrange: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val connection = controller.connection
    val titles by connection.titles.collectAsStateWithLifecycle()
    val statuses by connection.statuses.collectAsStateWithLifecycle()
    val programs by connection.programs.collectAsStateWithLifecycle()
    val attention by connection.attention.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Sessions", filled = false, onClick = onBack)
        if (window != null) WindowTile(window, statuses, programs, attention, size = 24.dp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                window?.displayTitle(titles) ?: view?.label.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                view?.label.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundButton(Icons.Rounded.GridView, "All windows", filled = false, tint = muted, onClick = onOverview)
        ActionsMenu(controller, view, window, onArrange, onMessage, filled = false)
    }
}

/** The floating buttons beside the terminal on wide screens, like the iOS app's right edge. */
@Composable
private fun SideRail(
    controller: WorkspaceController,
    view: SessionView?,
    window: WindowView?,
    sidebarHidden: Boolean,
    onOverview: () -> Unit,
    onArrange: () -> Unit,
    onShowSidebar: () -> Unit,
    onMessage: (String) -> Unit,
) {
    Column(
        Modifier.width(54.dp).padding(top = 8.dp, end = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (sidebarHidden) RoundButton(Icons.Rounded.ViewSidebar, "Show sessions", onClick = onShowSidebar)
        RoundButton(Icons.Rounded.GridView, "All windows", onClick = onOverview)
        ActionsMenu(controller, view, window, onArrange, onMessage, filled = true)
    }
}

@Composable
private fun ActionsMenu(
    controller: WorkspaceController,
    view: SessionView?,
    window: WindowView?,
    onArrange: () -> Unit,
    onMessage: (String) -> Unit,
    filled: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<RenameTarget?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    val connection = controller.connection
    val sessionId = controller.sessionId
    val focused = controller.focusedBlockId
    val paneCount = window?.blocks?.size ?: 0
    val zoomed = window?.zoomedBlockId != null
    val settings by controller.app.settings.collectAsStateWithLifecycle()
    val keepSize = (settings.sizeModes[sessionId] ?: SizeMode.TakeOver) == SizeMode.Keep

    Box {
        RoundButton(
            Icons.Rounded.MoreHoriz, "More", filled = filled,
            tint = if (filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        ) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Split right") },
                enabled = focused != null,
                onClick = {
                    open = false
                    focused?.let { connection.newSplit(sessionId, it, horizontal = true, onError = onMessage) }
                },
            )
            DropdownMenuItem(
                text = { Text("Split down") },
                enabled = focused != null,
                onClick = {
                    open = false
                    focused?.let { connection.newSplit(sessionId, it, horizontal = false, onError = onMessage) }
                },
            )
            DropdownMenuItem(
                text = { Text(if (zoomed) "Show all panes" else "Zoom pane") },
                enabled = focused != null && paneCount > 1,
                onClick = {
                    open = false
                    focused?.let { connection.zoomBlock(sessionId, it, !zoomed) }
                },
            )
            DropdownMenuItem(
                text = { Text("Arrange panes") },
                enabled = paneCount > 1,
                onClick = {
                    open = false
                    onArrange()
                },
            )
            DropdownMenuItem(
                text = { Text("Close pane") },
                enabled = focused != null,
                onClick = {
                    open = false
                    focused?.let { connection.closeBlock(sessionId, it, onMessage) }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text("Keep other device's size") },
                trailingIcon = { Switch(checked = keepSize, onCheckedChange = null) },
                onClick = {
                    open = false
                    controller.setSizeMode(if (keepSize) SizeMode.TakeOver else SizeMode.Keep)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text("Rename window") },
                enabled = window != null,
                onClick = {
                    open = false
                    rename = RenameTarget.Window
                },
            )
            DropdownMenuItem(
                text = { Text("Rename session") },
                onClick = {
                    open = false
                    rename = RenameTarget.Session
                },
            )
            DropdownMenuItem(
                text = { Text("End session") },
                onClick = {
                    open = false
                    confirmEnd = true
                },
            )
        }
    }
    rename?.let { target ->
        RenameDialog(
            title = if (target == RenameTarget.Window) "Rename window" else "Rename session",
            initial = if (target == RenameTarget.Window) window?.label.orEmpty() else view?.label.orEmpty(),
            onDismiss = { rename = null },
            onConfirm = { label ->
                rename = null
                if (target == RenameTarget.Window) {
                    window?.let { connection.renameWindow(sessionId, it.id, label, onMessage) }
                } else {
                    connection.renameSession(sessionId, label, onMessage)
                }
            },
        )
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this session?") },
            text = { Text("This closes every window in \"${view?.label.orEmpty()}\" and stops the programs running in them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    connection.destroySession(sessionId, onMessage)
                }) { Text("End session") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }
}

private enum class RenameTarget { Window, Session }

/** A capsule of window tabs. The active one shows its name; the others are just their icons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowPills(controller: WorkspaceController, view: SessionView, onMessage: (String) -> Unit, onOpenComposer: () -> Unit) {
    val connection = controller.connection
    val statuses by connection.statuses.collectAsStateWithLifecycle()
    val attention by connection.attention.collectAsStateWithLifecycle()
    val titles by connection.titles.collectAsStateWithLifecycle()
    val programs by connection.programs.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<WindowView?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            Modifier
                .weight(1f, fill = false)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(view.windows, key = { it.id }) { window ->
                val active = window.id == view.activeWindow?.id
                Box {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(if (active) scheme.surfaceContainerHighest else Color.Transparent)
                            .combinedClickable(
                                onClick = { connection.focusWindow(controller.sessionId, window.id) },
                                onLongClick = { menuFor = window.id },
                            )
                            .padding(horizontal = 5.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WindowTile(window, statuses, programs, attention, size = 22.dp)
                        if (active) {
                            Text(
                                window.displayTitle(titles),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 7.dp, end = 6.dp).widthIn(max = 150.dp),
                            )
                        }
                    }
                    DropdownMenu(menuFor == window.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {
                            menuFor = null
                            renaming = window
                        })
                        DropdownMenuItem(text = { Text("Close window") }, onClick = {
                            menuFor = null
                            connection.closeWindow(controller.sessionId, window.id, onMessage)
                        })
                    }
                }
            }
        }
        RoundButton(Icons.Rounded.Add, "New window", size = 36.dp, tint = scheme.onSurfaceVariant) {
            connection.newWindow(controller.sessionId, onMessage)
        }
        RoundButton(Icons.Rounded.EditNote, "Compose", size = 36.dp, tint = scheme.onSurfaceVariant, onClick = onOpenComposer)
    }
    renaming?.let { window ->
        RenameDialog("Rename window", window.label, onDismiss = { renaming = null }) { label ->
            renaming = null
            connection.renameWindow(controller.sessionId, window.id, label, onMessage)
        }
    }
}

/** A pop-up editor with normal keyboard features, for long prompts and voice typing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerSheet(controller: WorkspaceController, sendsEnter: Boolean, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding()) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).focusRequester(focus),
                label = { Text("Message for this pane") },
            )
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(sendsEnter, { value -> controller.app.updateSettings { it.copy(composerSendsEnter = value) } })
                Text("Press Enter after sending", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                Button(
                    enabled = text.isNotEmpty(),
                    onClick = {
                        controller.paste(text)
                        if (sendsEnter) controller.sendKey(RexKey.ENTER)
                        onClose()
                    },
                ) { Text("Send") }
            }
        }
    }
}

/** Every window in a session as a live thumbnail on the bare canvas. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowOverview(controller: WorkspaceController, view: SessionView, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val connection = controller.connection
    val titles by connection.titles.collectAsStateWithLifecycle()
    val statuses by connection.statuses.collectAsStateWithLifecycle()
    val programs by connection.programs.collectAsStateWithLifecycle()
    val attention by connection.attention.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(view.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                RoundButton(Icons.Rounded.Add, "New window", onClick = { connection.newWindow(controller.sessionId, onMessage) })
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Rounded.Close, "Close", onClick = onClose)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                items(view.windows, key = { it.id }) { window ->
                    Column(
                        Modifier.clickable {
                            connection.focusWindow(controller.sessionId, window.id)
                            onClose()
                        },
                    ) {
                        WindowThumbnail(controller, window, Modifier.fillMaxWidth().aspectRatio(1.35f))
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            WindowTile(window, statuses, programs, attention, size = 22.dp)
                            Text(
                                window.displayTitle(titles),
                                Modifier.padding(start = 8.dp).weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
