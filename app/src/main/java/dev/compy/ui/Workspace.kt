package dev.compy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
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
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
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
                        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Drag a pane onto another's edge to split, or its centre to swap",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { arranging = false }) { Text("Done") }
                            }
                        }
                    } else if (view != null) {
                        WindowPills(controller, view, onMessage)
                        KeyBar(
                            controller,
                            keyboardVisible = imeVisible,
                            onToggleKeyboard = ::toggleKeyboard,
                            onOpenComposer = { composer = true },
                        )
                    }
                }
                if (!compact) {
                    SideRail(controller, view, window, imeVisible, onToggleKeyboard = ::toggleKeyboard, onOverview = { overview = true }, onArrange = { arranging = true }, onShowSidebar = onShowSidebar, onMessage = onMessage)
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
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
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
    val titles by controller.connection.titles.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Sessions") }
        Column(Modifier.weight(1f)) {
            Text(
                window?.displayTitle(titles) ?: view?.label.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                view?.label.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOverview) { Icon(Icons.Filled.GridView, "All windows") }
        ActionsMenu(controller, view, window, onArrange, onMessage)
    }
}

/** The floating buttons beside the terminal on wide screens, like the iOS app's right edge. */
@Composable
private fun SideRail(
    controller: WorkspaceController,
    view: SessionView?,
    window: WindowView?,
    imeVisible: Boolean,
    onToggleKeyboard: () -> Unit,
    onOverview: () -> Unit,
    onArrange: () -> Unit,
    onShowSidebar: () -> Unit,
    onMessage: (String) -> Unit,
) {
    Column(
        Modifier.width(56.dp).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(onClick = onShowSidebar) { Icon(Icons.Filled.Menu, "Sessions") }
        FilledTonalIconButton(onClick = onOverview) { Icon(Icons.Filled.GridView, "All windows") }
        Box {
            ActionsMenu(controller, view, window, onArrange, onMessage, tonal = true)
        }
        FilledTonalIconButton(onClick = onToggleKeyboard) {
            Icon(if (imeVisible) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard, "Keyboard")
        }
    }
}

@Composable
private fun ActionsMenu(
    controller: WorkspaceController,
    view: SessionView?,
    window: WindowView?,
    onArrange: () -> Unit,
    onMessage: (String) -> Unit,
    tonal: Boolean = false,
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
        if (tonal) {
            FilledTonalIconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        } else {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        }
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
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Keep other device's size") },
                trailingIcon = { Switch(checked = keepSize, onCheckedChange = null) },
                onClick = {
                    open = false
                    controller.setSizeMode(if (keepSize) SizeMode.TakeOver else SizeMode.Keep)
                },
            )
            HorizontalDivider()
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

@Composable
fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The strip of tabs for a session's windows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowPills(controller: WorkspaceController, view: SessionView, onMessage: (String) -> Unit) {
    val connection = controller.connection
    val statuses by connection.statuses.collectAsStateWithLifecycle()
    val attention by connection.attention.collectAsStateWithLifecycle()
    val titles by connection.titles.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<WindowView?>(null) }
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(view.windows, key = { it.id }) { window ->
            val active = window.id == view.activeWindow?.id
            Box {
                Surface(
                    shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.combinedClickable(
                        onClick = { connection.focusWindow(controller.sessionId, window.id) },
                        onLongClick = { menuFor = window.id },
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(window.blocks.map { it.id }, statuses, attention)
                        Text(
                            window.displayTitle(titles),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp).widthIn(max = PillLabelMaxWidth),
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
        item(key = "add") {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { connection.newWindow(controller.sessionId, onMessage) },
            ) {
                Icon(Icons.Filled.Add, "New window", Modifier.padding(6.dp).size(18.dp))
            }
        }
    }
    renaming?.let { window ->
        RenameDialog("Rename window", window.label, onDismiss = { renaming = null }) { label ->
            renaming = null
            connection.renameWindow(controller.sessionId, window.id, label, onMessage)
        }
    }
}

private val PillLabelMaxWidth = 120.dp

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

/** Every window in a session as a live thumbnail. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowOverview(controller: WorkspaceController, view: SessionView, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val connection = controller.connection
    val titles by connection.titles.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(view.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    connection.newWindow(controller.sessionId, onMessage)
                }) { Icon(Icons.Filled.Add, "New window") }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(240.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                items(view.windows.size, key = { view.windows[it].id }) { i ->
                    val window = view.windows[i]
                    Column(
                        Modifier.clickable {
                            connection.focusWindow(controller.sessionId, window.id)
                            onClose()
                        },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 160.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                                .padding(2.dp),
                        ) {
                            WindowThumbnail(controller, window, Modifier.fillMaxSize())
                        }
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Terminal, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                window.displayTitle(titles),
                                Modifier.padding(start = 6.dp).weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (window.blocks.size == 1) "1 pane" else "${window.blocks.size} panes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
