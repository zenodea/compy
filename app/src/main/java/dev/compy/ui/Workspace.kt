package dev.compy.ui

import androidx.compose.animation.AnimatedVisibility
import dev.compy.system.uploadLabel
import dev.compy.system.uploadFiles
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.AppState
import dev.compy.data.SizeMode
import dev.compy.rex.BlockInfo
import dev.compy.rex.ConnectionState
import dev.compy.rex.LayerView
import dev.compy.rex.RexKey
import dev.compy.rex.ServerConnection
import dev.compy.rex.SessionView
import dev.compy.rex.WindowView
import dev.compy.rex.asWindow

/** The right-hand side of the app: one session's panes, tabs, keys and actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Workspace(
    app: AppState,
    connection: ServerConnection,
    sessionId: String,
    compact: Boolean,
    sidebarHidden: Boolean = false,
    /** True while the surrounding layout animates, when terminals shouldn't be resized yet. */
    layoutMoving: Boolean = false,
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
    // 0 shows the window and 1 the overview, with the window shrinking into its card between them.
    val overviewProgress = remember { Animatable(if (overview) 1f else 0f) }
    var areaBounds by remember { mutableStateOf<Rect?>(null) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    var arranging by rememberSaveable(sessionId) { mutableStateOf(false) }
    var composer by rememberSaveable(sessionId) { mutableStateOf(false) }
    var palette by rememberSaveable(sessionId) { mutableStateOf(false) }
    var finding by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showDetached by rememberSaveable(sessionId) { mutableStateOf(false) }
    val detachedRequest by app.detachedRequest.collectAsStateWithLifecycle()
    LaunchedEffect(detachedRequest, sessionId) {
        if (detachedRequest == sessionId) {
            showDetached = true
            app.showDetached(null)
        }
    }
    val shownBlocks = window?.shownBlocks?.map { it.id }.orEmpty()
    val minimized = window?.minimizedLayers.orEmpty()
    val tray = remember { MutableTransitionState(minimized.isNotEmpty()) }
    tray.targetState = minimized.isNotEmpty()
    val progress by connection.progress.collectAsStateWithLifecycle()
    controller.showMessage = onMessage
    val rootView = LocalView.current
    // A terminal bell is easy to miss on a phone, so feel it when it's from a pane on screen.
    LaunchedEffect(connection) {
        connection.bells.collect { block ->
            if (window?.blocks?.any { it.id == block } == true) {
                rootView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        }
    }

    LaunchedEffect(window?.focusedBlockId, window?.id, shownBlocks) {
        // Typing shouldn't go to a pane that's minimized out of sight.
        controller.focusedBlockId = window?.focusedBlockId?.takeIf { it in shownBlocks } ?: shownBlocks.firstOrNull()
        controller.focusedBlockId?.let {
            connection.clearAttention(it)
            app.notifier.cancel(it)
        }
    }
    LaunchedEffect(foreground, state) {
        if (foreground && state == ConnectionState.Connected) controller.reapplySizes()
    }

    val imeVisible = WindowInsets.isImeVisible
    fun toggleKeyboard() {
        val target = controller.focusedView() ?: return
        if (imeVisible) target.hideKeyboard() else target.showKeyboard()
    }
    val arrangeInset = animateDpAsState(if (arranging) 28.dp else 0.dp, motion(), label = "arrange")
    // The tray changes the height terminals have, so it counts as layout moving too.
    val sizesHeld = layoutMoving || !tray.isIdle
    LaunchedEffect(sizesHeld) { controller.holdSizes(sizesHeld) }
    // The sidebar already lists every window, so the tab strip only shows without it.
    val showTabs = compact || sidebarHidden

    LaunchedEffect(overview) {
        if (overview) controller.focusedView()?.hideKeyboard()
        overviewProgress.animateTo(if (overview) 1f else 0f, motion(MOTION_LONG))
    }
    // These only change at the ends, so the animation itself never recomposes this screen.
    val overviewShown by remember { derivedStateOf { overviewProgress.value > 0f } }
    val overviewSettled by remember { derivedStateOf { overviewProgress.value >= 1f } }
    val overviewClosed by remember { derivedStateOf { overviewProgress.value == 0f } }

    val spawn = remember(sessionId) { PaneSpawn() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val localArea = areaBounds?.translate(-origin)
    val latestWindow = rememberUpdated(window)
    val latestArea = rememberUpdated(localArea)
    val latestDensity = rememberUpdated(LocalDensity.current)
    fun dropAt(point: Offset?): SpawnDrop? {
        val shown = latestWindow.value ?: return null
        val area = latestArea.value ?: return null
        if (point == null || area.width <= 0f || area.height <= 0f) return null
        val fx = (point.x - area.left) / area.width
        val fy = (point.y - area.top) / area.height
        val moving = spawn.moving
        if (moving != null && overTrash(area, latestDensity.value, point)) return null
        return if (moving != null) moveDrop(shown, fx, fy, moving) else spawnDrop(shown, fx, fy)
    }
    fun pointOverTrash(point: Offset?): Boolean {
        val area = latestArea.value ?: return false
        return point != null && overTrash(area, latestDensity.value, point)
    }
    val latestConnection = rememberUpdated(connection)
    LaunchedEffect(spawn.overTrash) {
        if (spawn.overTrash) rootView.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
    }
    val latestMessage = rememberUpdated(onMessage)
    val paneLift = remember(spawn) {
        object : PaneLift {
            override fun lift(blockId: String, at: Offset) {
                spawn.moving = blockId
                spawn.armed = false
                spawn.overTrash = false
                spawn.dropped = false
                spawn.pointer = at - origin
                rootView.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            }

            override fun move(at: Offset, armed: Boolean) {
                spawn.armed = armed
                spawn.pointer = at - origin
                spawn.overTrash = armed && pointOverTrash(spawn.pointer)
            }

            override fun release(place: Boolean) {
                val moving = spawn.moving
                val placing = place && spawn.armed
                val intoTrash = placing && moving != null && pointOverTrash(spawn.pointer)
                val drop = if (placing && !intoTrash) dropAt(spawn.pointer) else null
                // Set before the card disappears, so it falls into the trash instead of fading.
                if (intoTrash) {
                    spawn.dropped = true
                    spawn.swallowed++
                }
                spawn.overTrash = false
                spawn.pointer = null
                spawn.armed = false
                spawn.moving = null
                val c = latestConnection.value
                val error = latestMessage.value
                if (intoTrash && moving != null) {
                    // No confirmation: dragging into the trash is the deliberate gesture.
                    c.closeBlock(sessionId, moving, error)
                    rootView.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    return
                }
                val target = drop?.anchor ?: return
                if (moving == null) return
                when (drop.zone) {
                    DropZone.Swap -> c.swapBlocks(sessionId, moving, target, error)
                    DropZone.Left -> c.moveBlock(sessionId, moving, target, horizontal = true, before = true, onError = error)
                    DropZone.Right -> c.moveBlock(sessionId, moving, target, horizontal = true, before = false, onError = error)
                    DropZone.Top -> c.moveBlock(sessionId, moving, target, horizontal = false, before = true, onError = error)
                    DropZone.Bottom -> c.moveBlock(sessionId, moving, target, horizontal = false, before = false, onError = error)
                }
                rootView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        }
    }
    val spawnTarget by remember { derivedStateOf { if (spawn.armed) dropAt(spawn.pointer) else null } }
    LaunchedEffect(spawnTarget?.anchor, spawnTarget?.zone) {
        if (spawnTarget != null) rootView.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    }

    val paneLook = if (settings.paneLayout == "tight") TightPanes else RelaxedPanes
    CompositionLocalProvider(LocalBlockProgress provides progress, LocalPaneLook provides paneLook) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft },
    ) {
        // Sits under everything, so it only gets touches on empty space between the controls.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(arranging, overviewClosed, window != null) {
                    if (arranging || !overviewClosed || window == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        spawn.pointer = down.position
                        spawn.armed = false
                        rootView.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    // A cancelled touch ends consumed, and shouldn't place anything.
                                    val drop = if (spawn.armed && !change.isConsumed) dropAt(change.position) else null
                                    when {
                                        drop == null -> Unit
                                        // Rex can't split an empty layout, so this pane floats.
                                        drop.anchor == null -> latestWindow.value?.let { connection.newFloatingPane(sessionId, it.id, nextFloatBounds(it), onMessage) }
                                        else -> connection.newSplit(
                                            sessionId, drop.anchor,
                                            horizontal = drop.zone == DropZone.Left || drop.zone == DropZone.Right,
                                            before = drop.zone == DropZone.Left || drop.zone == DropZone.Top,
                                            onError = onMessage,
                                        )
                                    }
                                    if (drop != null) rootView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    break
                                }
                                // A press that doesn't move is a stray tap, not a pane being placed.
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) spawn.armed = true
                                spawn.pointer = change.position
                                change.consume()
                            }
                        } finally {
                            spawn.pointer = null
                            spawn.armed = false
                        }
                    }
                },
        )
        if (overviewShown && view != null) {
            WindowOverview(
                controller, view, progress = { overviewProgress.value },
                onClose = { overview = false },
                onMessage = onMessage,
                onActiveCard = { cardBounds = it },
                // Once settled, the overview is on top and takes all touches.
                modifier = Modifier.zIndex(if (overviewSettled) 2f else 0f),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .zIndex(1f)
                .statusBarsPadding()
                .imePadding()
                .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding()),
        ) {
            if (compact) {
                Box(Modifier.graphicsLayer { alpha = 1f - overviewProgress.value }) {
                    CompactTopBar(controller, view, window, onShowSidebar, onOverview = { overview = true }, onArrange = { arranging = true }, onMessage = onMessage, onOpenPalette = { palette = true }, onOpenDetached = { showDetached = true })
                }
            }
            var lastOffline by remember { mutableStateOf("") }
            (state as? ConnectionState.Offline)?.let { lastOffline = it.reason }
            AnimatedVisibility(
                state is ConnectionState.Offline,
                enter = expandVertically(motion()) + fadeIn(motion(MOTION_SHORT)),
                exit = shrinkVertically(motion()) + fadeOut(motion(MOTION_SHORT)),
            ) { Notice("Offline: $lastOffline. Trying again…") }
            warning?.let { Notice(it) }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // Tight panes fill the screen edge to edge, as tmux does.
                            .padding(horizontal = if (paneLook.tight) 0.dp else 6.dp, vertical = if (paneLook.tight) 0.dp else 2.dp)
                            .layout { measurable, constraints ->
                                val inset = arrangeInset.value.roundToPx()
                                val placeable = measurable.measure(constraints.offset(-inset * 2, -inset * 2))
                                layout(placeable.width + inset * 2, placeable.height + inset * 2) { placeable.place(inset, inset) }
                            }
                            .onGloballyPositioned { areaBounds = it.boundsInRoot() }
                            .graphicsLayer {
                                val p = overviewProgress.value
                                val area = areaBounds
                                val card = cardBounds
                                if (p > 0f && area != null && card != null && area.width > 0f) {
                                    // Fit the window inside its card, keeping its proportions.
                                    val scale = minOf(card.width / area.width, card.height / area.height)
                                    val zoom = 1f + (scale - 1f) * p
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = (card.center.x - area.center.x) * p
                                    translationY = (card.center.y - area.center.y) * p
                                    // The live thumbnail takes over for the last part of the move.
                                    alpha = if (p > 0.85f) (1f - p) / 0.15f else 1f
                                }
                            },
                    ) {
                        if (window == null) {
                            Text(
                                if (view == null) "Loading…" else "This session has no windows",
                                Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val order = view.windows.map { it.id }
                            AnimatedContent(
                                window.id,
                                Modifier.fillMaxSize(),
                                transitionSpec = {
                                    if (overviewProgress.value > 0f) {
                                        EnterTransition.None togetherWith ExitTransition.None
                                    } else {
                                        // Tabs slide in the direction they sit in the tab strip.
                                        val direction = if (order.indexOf(targetState) >= order.indexOf(initialState)) 1 else -1
                                        (slideInHorizontally(motion()) { direction * it / 6 } + fadeIn(motion(MOTION_SHORT))) togetherWith
                                            (slideOutHorizontally(motion()) { -direction * it / 6 } + fadeOut(motion(MOTION_SHORT)))
                                    }
                                },
                                label = "window",
                            ) { id ->
                                val shownWindow = view.windows.firstOrNull { it.id == id } ?: return@AnimatedContent
                                PaneArea(
                                    controller, shownWindow, Modifier.fillMaxSize(),
                                    onPaneTapped = { block ->
                                        if (block != controller.focusedBlockId) {
                                            controller.focusedBlockId = block
                                            connection.focusBlock(sessionId, block)
                                        } else {
                                            controller.focusedView()?.showKeyboard()
                                        }
                                        connection.clearAttention(block)
                                        app.notifier.cancel(block)
                                    },
                                    arranging = arranging,
                                    enabled = overviewClosed && id == window.id,
                                    onDrop = { dragged, target, zone ->
                                        when (zone) {
                                            DropZone.Swap -> connection.swapBlocks(sessionId, dragged, target, onMessage)
                                            DropZone.Left -> connection.moveBlock(sessionId, dragged, target, true, true, onMessage)
                                            DropZone.Right -> connection.moveBlock(sessionId, dragged, target, true, false, onMessage)
                                            DropZone.Top -> connection.moveBlock(sessionId, dragged, target, false, true, onMessage)
                                            DropZone.Bottom -> connection.moveBlock(sessionId, dragged, target, false, false, onMessage)
                                        }
                                    },
                                    onMessage = onMessage,
                                    lift = paneLift,
                                    lifted = spawn.moving?.takeIf { spawn.armed && id == window.id },
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        finding,
                        enter = expandVertically(motion()) + fadeIn(motion(MOTION_SHORT)),
                        exit = shrinkVertically(motion()) + fadeOut(motion(MOTION_SHORT)),
                    ) {
                        FindBar(controller, onClose = { finding = false })
                    }
                    AnimatedContent(
                        arranging,
                        Modifier.graphicsLayer { alpha = 1f - overviewProgress.value },
                        transitionSpec = {
                            (fadeIn(motion(MOTION_SHORT)) togetherWith fadeOut(motion(MOTION_SHORT))) using SizeTransform(clip = false)
                        },
                        label = "bottom",
                    ) { arrange ->
                        if (arrange) {
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
                            Column {
                                AnimatedVisibility(
                                    tray,
                                    enter = expandVertically(motion()) + fadeIn(motion(MOTION_SHORT)),
                                    exit = shrinkVertically(motion()) + fadeOut(motion(MOTION_SHORT)),
                                ) {
                                    MinimizedTray(controller, minimized, onMessage)
                                }
                                AnimatedVisibility(
                                    showTabs,
                                    enter = expandVertically(motion(MOTION_LONG)) + fadeIn(motion(MOTION_LONG)),
                                    exit = shrinkVertically(motion(MOTION_LONG)) + fadeOut(motion(MOTION_SHORT)),
                                ) {
                                    WindowPills(controller, view, onMessage, onOpenComposer = { composer = true })
                                }
                                KeyBar(
                                    controller,
                                    keyboardVisible = imeVisible,
                                    onToggleKeyboard = ::toggleKeyboard,
                                    onOpenComposer = { composer = true },
                                    onOpenPalette = { palette = true },
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    !compact && !arranging,
                    Modifier.graphicsLayer { alpha = 1f - overviewProgress.value },
                    enter = expandHorizontally(motion()) + fadeIn(motion(MOTION_SHORT)),
                    exit = shrinkHorizontally(motion()) + fadeOut(motion(MOTION_SHORT)),
                ) {
                    SideRail(
                        controller, view, window, sidebarHidden,
                        onOverview = { overview = true },
                        onArrange = { arranging = true },
                        onShowSidebar = onShowSidebar,
                        onOpenComposer = { composer = true },
                        onOpenPalette = { palette = true },
                        onOpenDetached = { showDetached = true },
                        onMessage = onMessage,
                    )
                }
            }
        }
        PaneSpawnOverlay(spawn, spawnTarget, localArea, Modifier.zIndex(3f))
    }

    }

    if (showDetached) {
        DetachedSheet(controller, window, onClose = { showDetached = false }, onMessage = onMessage)
    }

    if (composer) {
        ComposerSheet(controller, settings.composerSendsEnter, onClose = { composer = false })
    }
    if (palette) {
        CommandPalette(
            controller, window,
            onClose = { palette = false },
            onFind = { finding = true },
            onDetached = { showDetached = true },
            onOverview = { overview = true },
            onArrange = { arranging = true },
            onCompose = { composer = true },
            onMessage = onMessage,
        )
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
    onOpenPalette: () -> Unit,
    onOpenDetached: () -> Unit,
) {
    val connection = controller.connection
    val folders by connection.folders.collectAsStateWithLifecycle()
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
                listOfNotNull(view?.label, folders[window?.focusedBlockId]).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundButton(Icons.Rounded.GridView, "All windows", filled = false, tint = muted, onClick = onOverview)
        ActionsMenu(controller, view, window, onArrange, onMessage, filled = false, onOpenPalette = onOpenPalette, onOpenDetached = onOpenDetached)
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
    onOpenComposer: () -> Unit,
    onOpenPalette: () -> Unit,
    onOpenDetached: () -> Unit,
    onMessage: (String) -> Unit,
) {
    Column(
        Modifier.width(54.dp).padding(top = 8.dp, end = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val scale = fadeIn(motion(MOTION_LONG)) + scaleIn(motion(MOTION_LONG), initialScale = 0.7f)
        val shrink = fadeOut(motion(MOTION_SHORT)) + scaleOut(motion(MOTION_SHORT), targetScale = 0.7f)
        AnimatedVisibility(sidebarHidden, enter = scale, exit = shrink) {
            RoundButton(Icons.Rounded.ViewSidebar, "Show sessions", onClick = onShowSidebar)
        }
        RoundButton(Icons.Rounded.GridView, "All windows", onClick = onOverview)
        ActionsMenu(controller, view, window, onArrange, onMessage, filled = true, onOpenPalette = onOpenPalette, onOpenDetached = onOpenDetached)
        // With the tab strip hidden, Compose lives here instead.
        AnimatedVisibility(!sidebarHidden, enter = scale, exit = shrink) {
            RoundButton(Icons.Rounded.EditNote, "Compose", onClick = onOpenComposer)
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
    filled: Boolean,
    onOpenPalette: () -> Unit,
    onOpenDetached: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val detached by controller.connection.detached.collectAsStateWithLifecycle()
    val detachedCount = detached[controller.sessionId]?.size ?: 0
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    var rename by remember { mutableStateOf<RenameTarget?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    val connection = controller.connection
    val sessionId = controller.sessionId
    val focused = controller.focusedBlockId
    val paneCount = window?.blocks?.size ?: 0
    val zoomed = window?.zoomedBlockId != null
    val settings by controller.app.settings.collectAsStateWithLifecycle()
    val keepSize = (settings.sizeModes[sessionId] ?: SizeMode.TakeOver) == SizeMode.Keep
    val context = LocalContext.current
    var uploadTo by remember { mutableStateOf<String?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val block = uploadTo ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        connection.act(onMessage) {
            val files = uploadFiles(context, uris)
            onMessage("Uploading ${uploadLabel(files)}…")
            connection.upload(sessionId, block, files)
            onMessage("Uploaded ${uploadLabel(files)}")
        }
    }

    Box {
        RoundButton(
            Icons.Rounded.MoreHoriz, "More", filled = filled,
            tint = if (filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        ) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Commands…") },
                onClick = {
                    open = false
                    onOpenPalette()
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                // Arranging only moves panes in the layout, not floating ones.
                enabled = (window?.tiledLayer?.blocks?.size ?: 0) > 1,
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
            DropdownMenuItem(
                text = { Text("Upload files…") },
                enabled = focused != null,
                onClick = {
                    open = false
                    uploadTo = focused
                    pickFiles.launch(arrayOf("*/*"))
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val focusedLayer = focused?.let { window?.layerOf(it) }
            val dockAnchor = window?.tiledLayer?.blocks?.firstOrNull()?.id
            DropdownMenuItem(
                text = { Text("New floating pane") },
                enabled = window != null,
                onClick = {
                    open = false
                    window?.let { connection.newFloatingPane(sessionId, it.id, nextFloatBounds(it), onMessage) }
                },
            )
            if (focusedLayer?.floating == true) {
                DropdownMenuItem(
                    text = { Text("Dock pane") },
                    enabled = dockAnchor != null,
                    onClick = {
                        open = false
                        if (focused != null && dockAnchor != null) {
                            connection.moveBlock(sessionId, focused, dockAnchor, horizontal = !portrait, before = false, onError = onMessage)
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Minimize pane") },
                    onClick = {
                        open = false
                        connection.setLayerMinimized(sessionId, focusedLayer.id, true, onMessage)
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Float pane") },
                    // With no pane left in the layout, a floating one would have nowhere to dock.
                    enabled = focused != null && (window?.tiledLayer?.blocks?.size ?: 0) > 1,
                    onClick = {
                        open = false
                        if (focused != null && window != null) connection.floatBlock(sessionId, focused, nextFloatBounds(window), onMessage)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Detach pane") },
                enabled = focused != null,
                onClick = {
                    open = false
                    focused?.let { connection.detachBlock(sessionId, it, onMessage) }
                },
            )
            if (detachedCount > 0) {
                DropdownMenuItem(
                    text = { Text("Detached panes ($detachedCount)") },
                    onClick = {
                        open = false
                        onOpenDetached()
                    },
                )
            }
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
    val tabs = rememberLazyListState()
    val activeIndex = view.windows.indexOfFirst { it.id == view.activeWindow?.id }
    LaunchedEffect(activeIndex) { if (activeIndex >= 0) tabs.animateScrollToItem(activeIndex) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = tabs,
            modifier = Modifier
                .weight(1f, fill = false)
                .animateContentSize(motion())
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(view.windows, key = { it.id }) { window ->
                val active = window.id == view.activeWindow?.id
                val tabColor by animateColorAsState(
                    if (active) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
                    motion(),
                    label = "tab",
                )
                Box(Modifier.animateItem()) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(tabColor)
                            .combinedClickable(
                                onClick = { connection.focusWindow(controller.sessionId, window.id) },
                                onLongClick = { menuFor = window.id },
                            )
                            .padding(horizontal = 5.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WindowTile(window, statuses, programs, attention, size = 22.dp)
                        AnimatedVisibility(
                            active,
                            enter = expandHorizontally(motion()) + fadeIn(motion(MOTION_SHORT)),
                            exit = shrinkHorizontally(motion()) + fadeOut(motion(MOTION_SHORT)),
                        ) {
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

/** Floating panes that are minimized. Tap one to bring it back, or hold it for more. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MinimizedTray(controller: WorkspaceController, layers: List<LayerView>, onMessage: (String) -> Unit) {
    val connection = controller.connection
    val sessionId = controller.sessionId
    val statuses by connection.statuses.collectAsStateWithLifecycle()
    val attention by connection.attention.collectAsStateWithLifecycle()
    val titles by connection.titles.collectAsStateWithLifecycle()
    val programs by connection.programs.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var menuFor by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf<String?>(null) }
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(layers, key = { it.id }) { layer ->
            val shown = layer.asWindow()
            Box(Modifier.animateItem()) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(scheme.surfaceContainerHigh)
                        .combinedClickable(
                            onClick = { connection.setLayerMinimized(sessionId, layer.id, false, onMessage) },
                            onLongClick = { menuFor = layer.id },
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WindowTile(shown, statuses, programs, attention, size = 20.dp)
                    Text(
                        shown.displayTitle(titles),
                        Modifier.padding(start = 7.dp, end = 4.dp).widthIn(max = 140.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(menuFor == layer.id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Restore") }, onClick = {
                        menuFor = null
                        connection.setLayerMinimized(sessionId, layer.id, false, onMessage)
                    })
                    DropdownMenuItem(text = { Text("Close") }, onClick = {
                        menuFor = null
                        closing = layer.id
                    })
                }
            }
        }
    }
    closing?.let { layer ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text("Close this floating pane?") },
            text = { Text("This stops the program running in it.") },
            confirmButton = {
                TextButton(onClick = {
                    closing = null
                    connection.closeLayer(sessionId, layer, onMessage)
                }) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("Cancel") } },
        )
    }
}

/** Panes still running outside every window, with live previews and ways to bring them back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetachedSheet(controller: WorkspaceController, window: WindowView?, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val connection = controller.connection
    val sessionId = controller.sessionId
    val detached by connection.detached.collectAsStateWithLifecycle()
    val statuses by connection.statuses.collectAsStateWithLifecycle()
    val attention by connection.attention.collectAsStateWithLifecycle()
    val titles by connection.titles.collectAsStateWithLifecycle()
    val programs by connection.programs.collectAsStateWithLifecycle()
    val blocks = detached[sessionId].orEmpty()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var closing by remember { mutableStateOf<BlockInfo?>(null) }
    val anchor = controller.focusedBlockId ?: window?.shownBlocks?.firstOrNull()?.id
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(Unit) { connection.refreshDetached(sessionId) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Detached panes", style = MaterialTheme.typography.titleMedium)
            Text(
                "These keep running, but aren't in any window.",
                Modifier.padding(top = 2.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            if (blocks.isEmpty()) {
                Text(
                    "Nothing is detached in this session.",
                    Modifier.padding(vertical = 24.dp).align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(blocks, key = { it.id }) { block ->
                    val shown = block.asWindow()
                    Column(Modifier.animateItem()) {
                        TerminalPreview(
                            controller, block.id,
                            Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(14.dp)),
                        )
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            WindowTile(shown, statuses, programs, attention, size = 22.dp)
                            Text(
                                statuses[block.id]?.message ?: shown.displayTitle(titles),
                                Modifier.padding(start = 8.dp).weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { closing = block }) {
                                Text("Close", color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(enabled = anchor != null, onClick = {
                                if (anchor != null) connection.moveBlock(sessionId, block.id, anchor, horizontal = !portrait, before = false, onError = onMessage)
                                onClose()
                            }) { Text("Split here") }
                            TextButton(onClick = {
                                connection.placeInNewWindow(sessionId, block.id, onMessage)
                                onClose()
                            }) { Text("New window") }
                        }
                    }
                }
            }
        }
    }
    closing?.let { block ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text("Close this pane?") },
            text = { Text("This stops the program running in it.") },
            confirmButton = {
                TextButton(onClick = {
                    closing = null
                    connection.closeBlock(sessionId, block.id, onMessage)
                }) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("Cancel") } },
        )
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
                // Long text scrolls inside the field, so Send stays on screen.
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 140.dp).focusRequester(focus),
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
private fun WindowOverview(
    controller: WorkspaceController,
    view: SessionView,
    progress: () -> Float,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
    onActiveCard: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection = controller.connection
    val titles by connection.titles.collectAsStateWithLifecycle()
    val statuses by connection.statuses.collectAsStateWithLifecycle()
    val programs by connection.programs.collectAsStateWithLifecycle()
    val attention by connection.attention.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            // Keep touches away from the terminal underneath.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.graphicsLayer { alpha = progress() }, verticalAlignment = Alignment.CenterVertically) {
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
                    val active = window.id == view.activeWindow?.id
                    Column(
                        Modifier
                            .animateItem()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                connection.focusWindow(controller.sessionId, window.id)
                                onClose()
                            },
                    ) {
                        WindowThumbnail(
                            controller, window,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.35f)
                                .then(if (active) Modifier.onGloballyPositioned { onActiveCard(it.boundsInRoot()) } else Modifier)
                                .graphicsLayer {
                                    // The real window flies into the active card; others fade in around it.
                                    alpha = if (active) ((progress() - 0.85f) / 0.15f).coerceIn(0f, 1f) else progress()
                                    if (!active) {
                                        val scale = 0.94f + 0.06f * progress()
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                },
                        )
                        Row(Modifier.padding(top = 8.dp).graphicsLayer { alpha = progress() }, verticalAlignment = Alignment.CenterVertically) {
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
