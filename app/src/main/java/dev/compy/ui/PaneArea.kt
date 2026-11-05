package dev.compy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.data.SizeMode
import dev.compy.rex.FractionRect
import dev.compy.rex.LayoutNode
import dev.compy.rex.TerminalSession
import dev.compy.rex.WindowView
import dev.compy.term.TerminalView
import kotlin.math.roundToInt

private val PaneShape = RoundedCornerShape(18.dp)
private val Gap = 6.dp

private data class PlacedSplit(
    val split: LayoutNode.Split,
    val rect: FractionRect,
    val ratio: Float,
)

/** Works out every pane's rectangle from the split tree, using any ratio being dragged locally. */
private fun place(
    node: LayoutNode,
    rect: FractionRect,
    overrides: Map<String, Float>,
    panes: MutableList<Pair<String, FractionRect>>,
    splits: MutableList<PlacedSplit>,
) {
    when (node) {
        is LayoutNode.Leaf -> panes += node.blockId to rect
        is LayoutNode.Split -> {
            val ratio = overrides[node.splitId] ?: node.ratio
            splits += PlacedSplit(node, rect, ratio)
            if (node.horizontal) {
                val w = rect.w * ratio
                place(node.before, FractionRect(rect.x, rect.y, w, rect.h), overrides, panes, splits)
                place(node.after, FractionRect(rect.x + w, rect.y, rect.w - w, rect.h), overrides, panes, splits)
            } else {
                val h = rect.h * ratio
                place(node.before, FractionRect(rect.x, rect.y, rect.w, h), overrides, panes, splits)
                place(node.after, FractionRect(rect.x, rect.y + h, rect.w, rect.h - h), overrides, panes, splits)
            }
        }
    }
}

private fun firstLeaf(node: LayoutNode): String = when (node) {
    is LayoutNode.Leaf -> node.blockId
    is LayoutNode.Split -> firstLeaf(node.before)
}

/**
 * Lays out a window's panes exactly as Rex describes them, with draggable dividers. Panes glide to
 * new positions, and a pane's real size only goes to the server once it has settled.
 */
@Composable
fun PaneArea(
    controller: WorkspaceController,
    window: WindowView,
    modifier: Modifier = Modifier,
    onPaneTapped: (String) -> Unit,
    arranging: Boolean = false,
    enabled: Boolean = true,
    onDrop: (dragged: String, target: String, zone: DropZone) -> Unit = { _, _, _ -> },
) {
    val tiled = window.layers.firstOrNull { !it.floating }
    val overrides = remember(window.id) { mutableStateMapOf<String, Float>() }
    // Panes already here when the window opens shouldn't animate in.
    val known = remember(window.id) { mutableSetOf<String>() }
    Box(modifier) {
        val zoomed = window.zoomedBlockId
        val panes = mutableListOf<Pair<String, FractionRect>>()
        val splits = mutableListOf<PlacedSplit>()
        val whole = FractionRect(0f, 0f, 1f, 1f)
        tiled?.layout?.let { place(it, whole, overrides, panes, splits) }
        if (zoomed != null && panes.none { it.first == zoomed }) panes += zoomed to whole
        val multiple = zoomed == null && panes.size > 1
        val dragging = overrides.isNotEmpty()
        val firstLayout = known.isEmpty()
        val inset = animateDpAsState(if (multiple) Gap / 2 else 0.dp, motion(), label = "inset")
        panes.forEach { (blockId, rect) ->
            key(blockId) {
                val isNew = !firstLayout && blockId !in known
                SideEffect { known += blockId }
                val visible = zoomed == null || zoomed == blockId
                AnimatedPane(
                    target = if (zoomed == blockId) whole else rect,
                    visible = visible,
                    onTop = zoomed == blockId,
                    snap = dragging,
                    animateIn = isNew,
                    inset = inset,
                ) {
                    TerminalPane(
                        controller = controller,
                        blockId = blockId,
                        focused = blockId == controller.focusedBlockId,
                        showFocusRing = multiple,
                        interactive = !arranging && enabled && visible,
                        onTapped = { onPaneTapped(blockId) },
                    )
                }
            }
        }
        // Floating layers sit above the layout, in order from back to front.
        if (zoomed == null) {
            window.layers.filter { it.floating && !it.minimized }.forEach { layer ->
                val inner = mutableListOf<Pair<String, FractionRect>>()
                layer.layout?.let { place(it, layer.bounds, overrides, inner, mutableListOf()) }
                inner.forEach { (blockId, rect) ->
                    key(blockId) {
                        Box(Modifier.zIndex(2f).fractionalPane({ rect }, { Gap / 2 })) {
                            TerminalPane(
                                controller = controller,
                                blockId = blockId,
                                focused = blockId == controller.focusedBlockId,
                                showFocusRing = true,
                                interactive = !arranging && enabled,
                                onTapped = { onPaneTapped(blockId) },
                            )
                        }
                    }
                }
            }
        }
        if (!arranging && zoomed == null) {
            splits.forEach { placed ->
                key(placed.split.splitId) {
                    SplitHandle(placed, dragging, onDrag = { ratio ->
                        overrides[placed.split.splitId] = ratio
                    }, onRelease = { ratio ->
                        controller.connection.resizePane(
                            controller.sessionId, placed.split.horizontal,
                            firstLeaf(placed.split.before), firstLeaf(placed.split.after), ratio,
                        )
                    })
                }
            }
            // Once the server reports the new ratio, drop the local one.
            LaunchedEffect(window) {
                val server = splits.associateBy { it.split.splitId }
                overrides.keys.toList().forEach { id ->
                    val ratio = server[id]?.split?.ratio
                    if (ratio == null || kotlin.math.abs(ratio - (overrides[id] ?: ratio)) < 0.01f) overrides.remove(id)
                }
            }
        } else if (arranging) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                ArrangeOverlay(panes, maxWidth, maxHeight, onDrop)
            }
        }
    }
}

/**
 * Places a pane as a fraction of its parent, with its edges on whole pixels. It reads its values
 * only while laying out, so moving panes never recompose their contents.
 */
private fun Modifier.fractionalPane(rect: () -> FractionRect, inset: () -> Dp): Modifier = layout { measurable, constraints ->
    val r = rect()
    val i = inset().roundToPx()
    val parentW = constraints.maxWidth
    val parentH = constraints.maxHeight
    val left = (parentW * r.x).roundToInt() + i
    val top = (parentH * r.y).roundToInt() + i
    val right = (parentW * (r.x + r.w)).roundToInt() - i
    val bottom = (parentH * (r.y + r.h)).roundToInt() - i
    val placeable = measurable.measure(Constraints.fixed(maxOf(1, right - left), maxOf(1, bottom - top)))
    layout(parentW, parentH) { placeable.place(left, top) }
}

/** One pane's place in the window: it glides to new positions and fades when hidden by a zoom. */
@Composable
private fun AnimatedPane(
    target: FractionRect,
    visible: Boolean,
    onTop: Boolean,
    snap: Boolean,
    animateIn: Boolean,
    inset: State<Dp>,
    content: @Composable () -> Unit,
) {
    val spec: AnimationSpec<Float> = if (snap) snap() else motion()
    val x = animateFloatAsState(target.x, spec, label = "pane-x")
    val y = animateFloatAsState(target.y, spec, label = "pane-y")
    val w = animateFloatAsState(target.w, spec, label = "pane-w")
    val h = animateFloatAsState(target.h, spec, label = "pane-h")
    val fade = animateFloatAsState(if (visible) 1f else 0f, motion(MOTION_SHORT), label = "pane-fade")
    val enter = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, motion()) }
    Box(
        Modifier
            .zIndex(if (onTop) 1f else 0f)
            .fractionalPane({ FractionRect(x.value, y.value, w.value, h.value) }, { inset.value })
            .graphicsLayer {
                alpha = fade.value * enter.value
                val scale = 0.94f + 0.06f * enter.value
                scaleX = scale
                scaleY = scale
            },
    ) { content() }
}

private fun paneModifier(rect: FractionRect, w: Dp, h: Dp, inset: Dp): Modifier =
    Modifier
        .offset(w * rect.x + inset, h * rect.y + inset)
        .size((w * rect.w - inset * 2).coerceAtLeast(1.dp), (h * rect.h - inset * 2).coerceAtLeast(1.dp))

@Composable
private fun SplitHandle(
    placed: PlacedSplit,
    dragging: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
) {
    val rect = placed.rect
    val horizontal = placed.split.horizontal
    var ratio by remember(placed.split.splitId) { mutableStateOf(placed.ratio) }
    if (kotlin.math.abs(ratio - placed.ratio) > 0.001f) ratio = placed.ratio
    val shownRatio = animateFloatAsState(placed.ratio, if (dragging) snap() else motion(), label = "handle")
    var active by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (active) 0.9f else 0.28f, motion(MOTION_SHORT), label = "handle-alpha")
    var span by remember { mutableStateOf(1f) }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val thickness = 28.dp.roundToPx()
                val parentW = constraints.maxWidth
                val parentH = constraints.maxHeight
                span = (if (horizontal) parentW * rect.w else parentH * rect.h).coerceAtLeast(1f)
                val placeable = if (horizontal) {
                    measurable.measure(Constraints.fixed(thickness, (parentH * rect.h).roundToInt().coerceAtLeast(1)))
                } else {
                    measurable.measure(Constraints.fixed((parentW * rect.w).roundToInt().coerceAtLeast(1), thickness))
                }
                val x = if (horizontal) {
                    (parentW * (rect.x + rect.w * shownRatio.value)).roundToInt() - thickness / 2
                } else {
                    (parentW * rect.x).roundToInt()
                }
                val y = if (horizontal) {
                    (parentH * rect.y).roundToInt()
                } else {
                    (parentH * (rect.y + rect.h * shownRatio.value)).roundToInt() - thickness / 2
                }
                layout(parentW, parentH) { placeable.place(x, y) }
            }
            .pointerInput(placed.split.splitId) {
                detectDragGestures(
                    onDragStart = { active = true },
                    onDragEnd = {
                        active = false
                        onRelease(ratio)
                    },
                    onDragCancel = { active = false },
                ) { change, amount ->
                    change.consume()
                    val delta = (if (horizontal) amount.x else amount.y) / span
                    ratio = (ratio + delta).coerceIn(0.1f, 0.9f)
                    onDrag(ratio)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (horizontal) 3.dp else 32.dp, if (horizontal) 32.dp else 3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
        )
    }
}

enum class DropZone { Left, Right, Top, Bottom, Swap }

private fun zoneFor(x: Float, y: Float): DropZone {
    val dx = x - 0.5f
    val dy = y - 0.5f
    if (kotlin.math.abs(dx) < 0.2f && kotlin.math.abs(dy) < 0.2f) return DropZone.Swap
    return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
        if (dx < 0) DropZone.Left else DropZone.Right
    } else {
        if (dy < 0) DropZone.Top else DropZone.Bottom
    }
}

/** Drag a pane onto another's edge to split beside it, or onto its centre to swap them. */
@Composable
private fun BoxScope.ArrangeOverlay(
    panes: List<Pair<String, FractionRect>>,
    fullW: Dp,
    fullH: Dp,
    onDrop: (String, String, DropZone) -> Unit,
) {
    val density = LocalDensity.current
    var dragged by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val widthPx = with(density) { fullW.toPx() }
    val heightPx = with(density) { fullH.toPx() }
    fun paneAt(p: Offset) = panes.firstOrNull { (_, r) ->
        val fx = p.x / widthPx
        val fy = p.y / heightPx
        fx >= r.x && fx < r.x + r.w && fy >= r.y && fy < r.y + r.h
    }
    val target = dragged?.let { d -> paneAt(pointer)?.takeIf { it.first != d } }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(panes) {
                detectDragGestures(
                    onDragStart = { start ->
                        pointer = start
                        dragged = paneAt(start)?.first
                    },
                    onDragEnd = {
                        val d = dragged
                        val t = paneAt(pointer)
                        if (d != null && t != null && t.first != d) {
                            val fx = (pointer.x / widthPx - t.second.x) / t.second.w
                            val fy = (pointer.y / heightPx - t.second.y) / t.second.h
                            onDrop(d, t.first, zoneFor(fx, fy))
                        }
                        dragged = null
                    },
                    onDragCancel = { dragged = null },
                ) { change, amount ->
                    change.consume()
                    pointer += amount
                }
            },
    )
    if (target != null) {
        val r = target.second
        val fx = (pointer.x / widthPx - r.x) / r.w
        val fy = (pointer.y / heightPx - r.y) / r.h
        val highlight = when (zoneFor(fx, fy)) {
            DropZone.Left -> FractionRect(r.x, r.y, r.w / 2, r.h)
            DropZone.Right -> FractionRect(r.x + r.w / 2, r.y, r.w / 2, r.h)
            DropZone.Top -> FractionRect(r.x, r.y, r.w, r.h / 2)
            DropZone.Bottom -> FractionRect(r.x, r.y + r.h / 2, r.w, r.h / 2)
            DropZone.Swap -> r
        }
        Box(
            paneModifier(highlight, fullW, fullH, Gap / 2)
                .clip(PaneShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                .border(2.dp, MaterialTheme.colorScheme.primary, PaneShape),
        )
    }
    val d = dragged
    val rect = panes.firstOrNull { it.first == d }?.second
    if (d != null && rect != null) {
        // The pane being carried, drawn as a shrunken card under the finger.
        val w = fullW * rect.w
        val h = fullH * rect.h
        val px = with(density) { pointer.x.toDp() }
        val py = with(density) { pointer.y.toDp() }
        Box(
            Modifier
                .offset(px - w / 2, py - h / 2)
                .size(w, h)
                .graphicsLayer {
                    scaleX = 0.6f
                    scaleY = 0.6f
                    alpha = 0.85f
                }
                .clip(PaneShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(2.dp, MaterialTheme.colorScheme.primary, PaneShape),
        )
    }
}

/** One terminal pane: the rounded surface, the terminal view and any connection notice. */
@Composable
fun TerminalPane(
    controller: WorkspaceController,
    blockId: String,
    focused: Boolean,
    showFocusRing: Boolean,
    interactive: Boolean,
    onTapped: () -> Unit,
) {
    val app = controller.app
    val settings by app.settings.collectAsStateWithLifecycle()
    val fonts by app.fonts.collectAsStateWithLifecycle()
    val session = remember(blockId) { controller.connection.terminal(controller.sessionId, blockId) }
    val state by session.liveState.collectAsState()
    val fallback by session.fallbackInUse.collectAsState()
    val scheme = MaterialTheme.colorScheme
    // Only the Material You theme depends on the app's colors, which change during a fade.
    val themeKey: Any = if (settings.terminalTheme == "material") scheme else settings.terminalTheme
    val colors = remember(settings.terminalTheme, themeKey) { TerminalThemes.colors(settings.terminalTheme, scheme) }
    LaunchedEffect(session, colors) { session.setLocalTheme(colors) }
    val latestTap = rememberUpdated(onTapped)
    val ringColor by animateColorAsState(
        if (showFocusRing && focused) scheme.primary.copy(alpha = 0.75f) else Color.Transparent,
        motion(MOTION_SHORT),
        label = "focus-ring",
    )

    Box(
        Modifier
            .fillMaxSize()
            .clip(PaneShape)
            .border(1.dp, ringColor, PaneShape),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clip(PaneShape),
            factory = { ctx ->
                TerminalView(ctx) { app.fonts.value }.also { view ->
                    view.fontSizeSp = settings.fontSizeSp
                    view.host = controller.host(blockId) { latestTap.value() }
                    view.bind(session)
                    controller.register(blockId, view)
                }
            },
            update = { view ->
                view.interactive = interactive
                view.keepSize = settings.sizeModes[controller.sessionId] == SizeMode.Keep
                view.paneFocused = focused && interactive
                view.fontSizeSp = settings.fontSizeSp
                view.tag.let { if (it !== fonts) { view.tag = fonts; view.fontsChanged() } }
            },
            onRelease = { view ->
                controller.unregister(blockId)
                view.bind(null)
            },
        )
        if (fallback) {
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                color = scheme.tertiaryContainer,
            ) {
                Text(
                    "This Rex version sends history Compy can't read yet, so earlier output is missing. Update Compy if one is available.",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onTertiaryContainer,
                )
            }
        }
        AnimatedVisibility(
            state != TerminalSession.State.Live,
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            enter = fadeIn(motion(MOTION_SHORT)),
            exit = fadeOut(motion(MOTION_SHORT)),
        ) {
            // While fading out the state is already Live, so keep showing the last message.
            var message by remember { mutableStateOf("Connecting…") }
            when (state) {
                TerminalSession.State.Connecting -> message = "Connecting…"
                TerminalSession.State.Reconnecting -> message = "Reconnecting…"
                TerminalSession.State.Closed -> message = "Closed"
                TerminalSession.State.Live -> Unit
            }
            Surface(shape = CircleShape, color = scheme.secondaryContainer) {
                Text(
                    message,
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** A read-only preview of a window's panes, used in the overview. */
@Composable
fun WindowThumbnail(controller: WorkspaceController, window: WindowView, modifier: Modifier = Modifier) {
    val app = controller.app
    val tiled = window.layers.firstOrNull { !it.floating }
    BoxWithConstraints(modifier) {
        val panes = mutableListOf<Pair<String, FractionRect>>()
        tiled?.layout?.let { place(it, FractionRect(0f, 0f, 1f, 1f), emptyMap(), panes, mutableListOf()) }
        val w = maxWidth
        val h = maxHeight
        panes.forEach { (blockId, rect) ->
            key(blockId) {
                val session = remember(blockId) { controller.connection.terminal(controller.sessionId, blockId) }
                AndroidView(
                    modifier = paneModifier(rect, w, h, 2.dp).clip(RoundedCornerShape(12.dp)),
                    factory = { ctx ->
                        TerminalView(ctx) { app.fonts.value }.also { view ->
                            view.interactive = false
                            view.bind(session)
                        }
                    },
                    onRelease = { it.bind(null) },
                )
            }
        }
    }
}

@Composable
fun <T> rememberUpdated(value: T) = remember { mutableStateOf(value) }.also { it.value = value }
