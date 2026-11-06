package dev.compy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.data.SizeMode
import dev.compy.rex.FractionRect
import dev.compy.rex.LayerView
import dev.compy.rex.LayoutNode
import dev.compy.rex.TerminalSession
import dev.compy.rex.WindowView
import dev.compy.term.TerminalView
import kotlin.math.roundToInt

/** How panes are drawn: rounded with gaps, or packed edge to edge with thin lines like tmux. */
data class PaneLook(val shape: Shape, val gap: Dp, val tight: Boolean)

private val CornerRadiusDp = 18.dp
val RelaxedPanes = PaneLook(RoundedCornerShape(CornerRadiusDp), 6.dp, tight = false)
val TightPanes = PaneLook(RectangleShape, 1.dp, tight = true)
val LocalPaneLook = staticCompositionLocalOf { RelaxedPanes }

/** How far the drag handle sits from a pane's corner; rounded corners need it further in. */
private val PaneLook.handleInset: Dp get() = if (tight) 3.dp else 6.dp

/** Extra room above a pane's text, so its first line starts just below the drag handle. */
private val PaneLook.handleSpace: Dp get() = if (tight) handleInset + 11.dp else 9.dp

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
    onMessage: (String) -> Unit = {},
    lift: PaneLift? = null,
    lifted: String? = null,
) {
    val tiled = window.tiledLayer
    val overrides = remember(window.id) { mutableStateMapOf<String, Float>() }
    // Panes already here when the window opens shouldn't animate in.
    val known = remember(window.id) { mutableSetOf<String>() }
    val knownLayers = remember(window.id) { mutableSetOf<String>() }
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
        val look = LocalPaneLook.current
        val inset = animateDpAsState(if (multiple) look.gap / 2 else 0.dp, motion(), label = "inset")
        // Tight panes sit on a line-colored backing, so the hairline gaps between them read as lines.
        if (look.tight && multiple) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
        }
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
                    dimmed = blockId == lifted,
                ) {
                    TerminalPane(
                        controller = controller,
                        blockId = blockId,
                        focused = blockId == controller.focusedBlockId,
                        showFocusRing = multiple,
                        interactive = !arranging && enabled && visible,
                        onTapped = { onPaneTapped(blockId) },
                        // Room for the drag handle in the corner, which shows once there are two panes.
                        handleSpace = lift != null && multiple && !arranging,
                    )
                }
            }
        }
        // Floating layers sit above the layout. The server lists them from back to front, and
        // zIndex keeps that order without moving the terminals around in the composition.
        val floating = window.floatingLayers
        val front = floating.lastOrNull { !it.minimized }?.id
        val firstFloats = knownLayers.isEmpty()
        floating.sortedBy { it.id }.forEach { layer ->
            key(layer.id) {
                val isNew = !firstFloats && layer.id !in knownLayers
                SideEffect { knownLayers += layer.id }
                FloatingLayer(
                    controller = controller,
                    layer = layer,
                    stackIndex = floating.indexOf(layer),
                    isFront = layer.id == front,
                    isNew = isNew,
                    // A zoomed pane fills the window, and arranging only moves the tiled panes.
                    shown = !layer.minimized && zoomed == null && !arranging,
                    enabled = enabled && !arranging,
                    onPaneTapped = onPaneTapped,
                    onMessage = onMessage,
                )
            }
        }
        if (panes.isEmpty() && floating.isNotEmpty() && floating.all { it.minimized }) {
            Text(
                "Every pane here is minimized. Tap one below to bring it back.",
                Modifier.align(Alignment.Center).padding(32.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        }
        // After the dividers, so a grip wins where the two meet. Floating panes still cover it.
        if (lift != null) {
            panes.forEach { (blockId, rect) ->
                key("grip", blockId) {
                    PaneGrip(blockId, rect, shown = multiple && !arranging && enabled, snap = dragging, inset = inset, lift = lift)
                }
            }
        }
        if (arranging) {
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

private val BoundsConverter = TwoWayConverter<FractionRect, AnimationVector4D>(
    { AnimationVector4D(it.x, it.y, it.w, it.h) },
    { FractionRect(it.v1, it.v2, it.v3, it.v4) },
)

/**
 * A floating pane: a card with a bar to drag it by, minimize and close buttons, and a corner to
 * resize it from. Moves stay local until the finger lifts, then go to the server in one change.
 */
@Composable
private fun FloatingLayer(
    controller: WorkspaceController,
    layer: LayerView,
    stackIndex: Int,
    isFront: Boolean,
    isNew: Boolean,
    shown: Boolean,
    enabled: Boolean,
    onPaneTapped: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val connection = controller.connection
    val sessionId = controller.sessionId
    val scheme = MaterialTheme.colorScheme
    val titles by connection.titles.collectAsStateWithLifecycle()
    val visible = remember(layer.id) { MutableTransitionState(shown && !isNew) }
    visible.targetState = shown
    val bounds = remember(layer.id) { Animatable(layer.bounds, BoundsConverter) }
    // What the finger set, read only while laying out so that dragging never recomposes the card.
    var local by remember(layer.id) { mutableStateOf<FractionRect?>(null) }
    val gesture = remember(layer.id) { LayerGesture() }
    var confirmClose by remember(layer.id) { mutableStateOf(false) }
    LaunchedEffect(layer.bounds) {
        if (gesture.active) return@LaunchedEffect
        local?.let {
            bounds.snapTo(it)
            local = null
        }
        bounds.animateTo(layer.bounds, motion())
    }
    fun finish(rect: FractionRect) {
        gesture.active = false
        connection.resizeLayer(sessionId, layer.id, rect) { message ->
            local = null
            onMessage(message)
        }
    }
    val focused = layer.blocks.any { it.id == controller.focusedBlockId }
    val ring by animateColorAsState(
        if (focused) scheme.primary.copy(alpha = 0.75f) else scheme.outlineVariant,
        motion(MOTION_SHORT),
        label = "float-ring",
    )
    Box(
        Modifier
            .zIndex(2f + stackIndex)
            .layout { measurable, constraints ->
                gesture.parentW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                gesture.parentH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .fractionalPane({ local ?: bounds.value }, { 0.dp }),
    ) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(motion(MOTION_SHORT)) + scaleIn(motion(), initialScale = 0.88f),
            exit = fadeOut(motion(MOTION_SHORT)) + scaleOut(motion(), targetScale = 0.7f, transformOrigin = TransformOrigin(0.5f, 1f)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .shadow(10.dp, LocalPaneLook.current.shape)
                    .clip(LocalPaneLook.current.shape)
                    .background(scheme.surfaceContainerHigh)
                    .border(1.dp, ring, LocalPaneLook.current.shape),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(FloatBarHeight)
                        .pointerInput(layer.id) { detectTapGestures { connection.focusLayer(sessionId, layer.id, onMessage) } }
                        .pointerInput(layer.id) {
                            var rect = FractionRect(0f, 0f, 1f, 1f)
                            detectDragGestures(
                                onDragStart = {
                                    gesture.active = true
                                    rect = local ?: bounds.value
                                    connection.raiseLayer(sessionId, layer.id)
                                },
                                onDragEnd = { finish(rect) },
                                onDragCancel = { finish(rect) },
                            ) { change, amount ->
                                change.consume()
                                rect = moved(rect, amount.x / gesture.parentW, amount.y / gesture.parentH)
                                local = rect
                            }
                        }
                        .padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        layer.asWindow().displayTitle(titles),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RoundButton(Icons.Rounded.Remove, "Minimize", size = 30.dp, filled = false, tint = scheme.onSurfaceVariant) {
                        connection.setLayerMinimized(sessionId, layer.id, true, onMessage)
                    }
                    RoundButton(Icons.Rounded.Close, "Close floating pane", size = 30.dp, filled = false, tint = scheme.onSurfaceVariant) {
                        confirmClose = true
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val inner = mutableListOf<Pair<String, FractionRect>>()
                    layer.layout?.let { place(it, FractionRect(0f, 0f, 1f, 1f), emptyMap(), inner, mutableListOf()) }
                    inner.forEach { (blockId, rect) ->
                        key(blockId) {
                            Box(Modifier.fractionalPane({ rect }, { 0.dp })) {
                                TerminalPane(
                                    controller = controller,
                                    blockId = blockId,
                                    focused = blockId == controller.focusedBlockId,
                                    showFocusRing = inner.size > 1,
                                    interactive = enabled,
                                    onTapped = {
                                        if (!isFront) connection.raiseLayer(sessionId, layer.id)
                                        onPaneTapped(blockId)
                                    },
                                )
                            }
                        }
                    }
                    // The corner to resize from, which holds terminal sizes until it's let go.
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(36.dp)
                            .pointerInput(layer.id) {
                                var rect = FractionRect(0f, 0f, 1f, 1f)
                                detectDragGestures(
                                    onDragStart = {
                                        gesture.active = true
                                        rect = local ?: bounds.value
                                        if (!isFront) connection.raiseLayer(sessionId, layer.id)
                                        controller.holdSizes(true)
                                    },
                                    onDragEnd = {
                                        controller.holdSizes(false)
                                        finish(rect)
                                    },
                                    onDragCancel = {
                                        controller.holdSizes(false)
                                        finish(rect)
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    rect = resized(rect, amount.x / gesture.parentW, amount.y / gesture.parentH)
                                    local = rect
                                }
                            }
                            .padding(9.dp)
                            .drawBehind {
                                val grip = scheme.onSurface.copy(alpha = 0.55f)
                                val stroke = 2.dp.toPx()
                                val w = size.width
                                val h = size.height
                                drawLine(grip, Offset(w, h * 0.2f), Offset(w * 0.2f, h), stroke, StrokeCap.Round)
                                drawLine(grip, Offset(w, h * 0.62f), Offset(w * 0.62f, h), stroke, StrokeCap.Round)
                            },
                    )
                }
            }
        }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close this floating pane?") },
            text = { Text("This stops the program running in it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    connection.closeLayer(sessionId, layer.id, onMessage)
                }) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } },
        )
    }
}

/** State a floating layer's gestures share without recomposing it. */
private class LayerGesture {
    var active = false
    var parentW = 1f
    var parentH = 1f
}

private const val MIN_FLOAT_WIDTH = 0.3f
private const val MIN_FLOAT_HEIGHT = 0.2f
private val FloatBarHeight = 34.dp

/** Moves a floating layer, keeping it inside the window as the server requires. */
internal fun moved(r: FractionRect, dx: Float, dy: Float) = r.copy(
    x = (r.x + dx).coerceIn(0f, (1f - r.w).coerceAtLeast(0f)),
    y = (r.y + dy).coerceIn(0f, (1f - r.h).coerceAtLeast(0f)),
)

/** Resizes a floating layer from its bottom right corner, within the window and a usable minimum. */
internal fun resized(r: FractionRect, dx: Float, dy: Float) = r.copy(
    w = (r.w + dx).coerceIn(minOf(MIN_FLOAT_WIDTH, 1f - r.x), 1f - r.x),
    h = (r.h + dy).coerceIn(minOf(MIN_FLOAT_HEIGHT, 1f - r.y), 1f - r.y),
)

/** Where the next floating pane goes: mostly filling a phone screen, stepped so none hide another. */
internal fun nextFloatBounds(window: WindowView): FractionRect {
    val step = window.floatingLayers.size % 4 * 0.03f
    return FractionRect(0.06f + step, 0.06f + step, 0.84f, 0.66f)
}

/** Carries a pane by its grip. Positions are in root coordinates. */
interface PaneLift {
    fun lift(blockId: String, at: Offset)
    fun move(at: Offset, armed: Boolean)
    fun release(place: Boolean)
}

/**
 * The drag handle in a pane's top-left corner. Dragging it carries the pane to another place in the window.
 * A long press inside the pane selects text and the lines between panes resize them, so the pane
 * gets its own small handle. It sits above the dividers, so it wins where the two meet.
 */
@Composable
private fun PaneGrip(
    blockId: String,
    target: FractionRect,
    shown: Boolean,
    snap: Boolean,
    inset: State<Dp>,
    lift: PaneLift,
) {
    val latest = rememberUpdated(lift)
    // Glides with its pane, using the same motion.
    val spec: AnimationSpec<Float> = if (snap) snap() else motion()
    val x = animateFloatAsState(target.x, spec, label = "grip-x")
    val y = animateFloatAsState(target.y, spec, label = "grip-y")
    // Kept outside of state, so a pane gliding around never recomposes its grip.
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var active by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        when {
            !shown -> 0f
            active -> 0.9f
            else -> 0.32f
        },
        motion(MOTION_SHORT),
        label = "grip",
    )
    val look = LocalPaneLook.current
    val handleInset = look.handleInset
    val ink = MaterialTheme.colorScheme.onSurface
    val grow by animateFloatAsState(if (active) 1.2f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "grip-grow")
    if (!shown && alpha == 0f) return
    Box(
        Modifier
            .layout { measurable, constraints ->
                val i = inset.value.roundToPx()
                val parentW = constraints.maxWidth
                val parentH = constraints.maxHeight
                val left = (parentW * x.value).roundToInt() + i
                val top = (parentH * y.value).roundToInt() + i
                // Tucked into the top-left corner, where the dividers' handles never are.
                val box = 34.dp.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(box, box))
                layout(parentW, parentH) { placeable.place(left, top) }
            }
            .onGloballyPositioned { coords[0] = it }
            .pointerInput(blockId, shown) {
                if (!shown) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val start = coords[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    active = true
                    latest.value.lift(blockId, start.localToRoot(down.position))
                    var armed = false
                    var place = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // A cancelled touch ends consumed, and shouldn't move anything.
                                place = armed && !change.isConsumed
                                break
                            }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) armed = true
                            val now = coords[0]?.takeIf { it.isAttached } ?: break
                            latest.value.move(now.localToRoot(change.position), armed)
                            change.consume()
                        }
                    } finally {
                        active = false
                        latest.value.release(place)
                    }
                }
            },
    ) {
        if (look.tight) {
            androidx.compose.material3.Icon(
                Icons.Rounded.DragIndicator,
                "Move pane",
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = handleInset, top = handleInset)
                    .size(18.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = grow
                        scaleY = grow
                    },
                tint = ink,
            )
        } else {
            // Two short arcs that follow the pane's rounded corner, like grip lines on its edge.
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val corner = CornerRadiusDp.toPx()
                val width = 2.4.dp.toPx() * grow
                for (r in listOf(corner - 5.5.dp.toPx(), corner - 10.dp.toPx())) {
                    drawArc(
                        ink.copy(alpha = alpha),
                        startAngle = 192f,
                        sweepAngle = 66f,
                        useCenter = false,
                        topLeft = Offset(corner - r, corner - r),
                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
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
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val spec: AnimationSpec<Float> = if (snap) snap() else motion()
    val x = animateFloatAsState(target.x, spec, label = "pane-x")
    val y = animateFloatAsState(target.y, spec, label = "pane-y")
    val w = animateFloatAsState(target.w, spec, label = "pane-w")
    val h = animateFloatAsState(target.h, spec, label = "pane-h")
    val fade = animateFloatAsState(if (visible) 1f else 0f, motion(MOTION_SHORT), label = "pane-fade")
    // A pane being carried elsewhere stays in place, faded, until it lands.
    val dim = animateFloatAsState(if (dimmed) 0.4f else 1f, motion(MOTION_SHORT), label = "pane-dim")
    val enter = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, motion()) }
    Box(
        Modifier
            .zIndex(if (onTop) 1f else 0f)
            .fractionalPane({ FractionRect(x.value, y.value, w.value, h.value) }, { inset.value })
            .graphicsLayer {
                alpha = fade.value * enter.value * dim.value
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

/** Which side of a pane a new one goes on, from where the finger is in it: its nearest edge. */
internal fun splitZone(x: Float, y: Float): DropZone {
    val dx = x - 0.5f
    val dy = y - 0.5f
    return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
        if (dx < 0) DropZone.Left else DropZone.Right
    } else {
        if (dy < 0) DropZone.Top else DropZone.Bottom
    }
}

/** The part of a pane that a drop in this zone would take. */
internal fun zoneRect(r: FractionRect, zone: DropZone): FractionRect = when (zone) {
    DropZone.Left -> FractionRect(r.x, r.y, r.w / 2, r.h)
    DropZone.Right -> FractionRect(r.x + r.w / 2, r.y, r.w / 2, r.h)
    DropZone.Top -> FractionRect(r.x, r.y, r.w, r.h / 2)
    DropZone.Bottom -> FractionRect(r.x, r.y + r.h / 2, r.w, r.h / 2)
    DropZone.Swap -> r
}

/** A mini window being dragged out of the background, in the workspace's own coordinates. */
class PaneSpawn {
    var pointer by mutableStateOf<Offset?>(null)
    var armed by mutableStateOf(false)
    /** The pane being carried by its grip, or null while a new pane is being placed. */
    var moving by mutableStateOf<String?>(null)
    /** True while a carried pane hovers over the trash can that closes it. */
    var overTrash by mutableStateOf(false)
    /** True once a pane has been dropped into the trash, until the next one is lifted. */
    var dropped by mutableStateOf(false)
    /** Counts panes dropped into the trash, so each drop plays its animation. */
    var swallowed by mutableIntStateOf(0)
}

/** Where a dragged mini window would land. With no anchor, the window has no panes to split. */
data class SpawnDrop(val anchor: String?, val zone: DropZone, val rect: FractionRect)

/** Works out the landing place under a point given as a fraction of the pane area. */
internal fun spawnDrop(window: WindowView, fx: Float, fy: Float): SpawnDrop? {
    if (fx < 0f || fx >= 1f || fy < 0f || fy >= 1f) return null
    val zoomed = window.zoomedBlockId
    if (zoomed != null) {
        return splitZone(fx, fy).let { SpawnDrop(zoomed, it, zoneRect(FractionRect(0f, 0f, 1f, 1f), it)) }
    }
    val whole = FractionRect(0f, 0f, 1f, 1f)
    // The front floating pane wins, then the ones behind it, then the layout under them.
    val layers = window.floatingLayers.filterNot { it.minimized }.reversed() + listOfNotNull(window.tiledLayer)
    var any = false
    for (layer in layers) {
        val panes = mutableListOf<Pair<String, FractionRect>>()
        layer.layout?.let { place(it, if (layer.floating) layer.bounds else whole, emptyMap(), panes, mutableListOf()) }
        any = any || panes.isNotEmpty()
        val hit = panes.firstOrNull { (_, r) -> fx >= r.x && fx < r.x + r.w && fy >= r.y && fy < r.y + r.h } ?: continue
        val zone = splitZone((fx - hit.second.x) / hit.second.w, (fy - hit.second.y) / hit.second.h)
        return SpawnDrop(hit.first, zone, zoneRect(hit.second, zone))
    }
    return if (any) null else SpawnDrop(null, DropZone.Swap, whole)
}

/**
 * Where a pane carried by its grip would land: beside another tiled pane, or swapped with it from
 * its middle. Floating panes cover what's beneath them, so a point over one lands nowhere.
 */
internal fun moveDrop(window: WindowView, fx: Float, fy: Float, moving: String): SpawnDrop? {
    if (fx < 0f || fx >= 1f || fy < 0f || fy >= 1f || window.zoomedBlockId != null) return null
    fun FractionRect.contains() = fx >= x && fx < x + w && fy >= y && fy < y + h
    if (window.floatingLayers.any { !it.minimized && it.bounds.contains() }) return null
    val panes = mutableListOf<Pair<String, FractionRect>>()
    window.tiledLayer?.layout?.let { place(it, FractionRect(0f, 0f, 1f, 1f), emptyMap(), panes, mutableListOf()) }
    val (target, r) = panes.firstOrNull { it.second.contains() } ?: return null
    if (target == moving) return null
    val zone = zoneFor((fx - r.x) / r.w, (fy - r.y) / r.h)
    return SpawnDrop(target, zone, zoneRect(r, zone))
}

/** The mini window under the finger and the highlight showing where it would land. */
@Composable
fun PaneSpawnOverlay(spawn: PaneSpawn, drop: SpawnDrop?, area: Rect?, modifier: Modifier = Modifier) {
    val halfGap = LocalPaneLook.current.gap / 2
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val density = LocalDensity.current
    val highlight = remember { Animatable(FractionRect(0f, 0f, 1f, 1f), BoundsConverter) }
    val fade by animateFloatAsState(if (drop != null) 1f else 0f, motion(MOTION_SHORT), label = "spawn-highlight")
    var wasShown by remember { mutableStateOf(false) }
    LaunchedEffect(drop?.rect) {
        val rect = drop?.rect
        if (rect != null) {
            // It fades in where it's needed, then glides between zones.
            if (wasShown) highlight.animateTo(rect, motion(MOTION_SHORT)) else highlight.snapTo(rect)
        }
        wasShown = rect != null
    }
    val overlayWidth = remember { floatArrayOf(0f) }
    Box(modifier.fillMaxSize().onSizeChanged { overlayWidth[0] = it.width.toFloat() }) {
        if (area != null && fade > 0f) {
            Box(
                Modifier
                    .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                    .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() }),
            ) {
                Box(
                    Modifier
                        .fractionalPane({ highlight.value }, { halfGap })
                        .graphicsLayer { alpha = fade }
                        .clip(LocalPaneLook.current.shape)
                        .background(scheme.primary.copy(alpha = 0.3f))
                        .border(2.dp, scheme.primary, LocalPaneLook.current.shape),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedVisibility(
                        spawn.moving != null && drop?.zone == DropZone.Swap,
                        enter = fadeIn(motion(MOTION_SHORT)) + scaleIn(motion(MOTION_SHORT), initialScale = 0.6f),
                        exit = fadeOut(motion(MOTION_SHORT)) + scaleOut(motion(MOTION_SHORT), targetScale = 0.6f),
                    ) {
                        androidx.compose.material3.Icon(Icons.Rounded.SwapHoriz, "Swap", Modifier.size(36.dp), tint = scheme.primary)
                    }
                }
            }
        }
        if (area != null) PaneTrash(spawn, area)
        val visible = remember { MutableTransitionState(false) }
        visible.targetState = spawn.pointer != null
        val last = remember { floatArrayOf(0f, 0f) }
        // Over the trash the card shrinks a little and hovers above its open lid, about to fall in.
        val shrink by animateFloatAsState(if (spawn.overTrash) 0.72f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "card-shrink")
        val pull = animateFloatAsState(if (spawn.overTrash || spawn.dropped) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "card-pull")
        AnimatedVisibility(
            visible,
            Modifier.offset {
                spawn.pointer?.let {
                    last[0] = it.x
                    last[1] = it.y
                }
                // Held above the finger, so the finger doesn't hide it.
                var x = last[0] - 44.dp.toPx()
                var y = last[1] - 92.dp.toPx()
                val bin = area?.let { trashRect(it, this) }
                if (bin != null && pull.value > 0f) {
                    x += (bin.center.x - 44.dp.toPx() - x) * pull.value
                    // High enough that the lid flipping open stays in view.
                    y += (bin.top - 78.dp.toPx() - y) * pull.value
                }
                // Kept on screen, even when the finger is right at the edge.
                val edge = 8.dp.toPx()
                x = x.coerceIn(edge, maxOf(edge, overlayWidth[0] - 88.dp.toPx() - edge))
                IntOffset(x.roundToInt(), y.roundToInt())
            },
            enter = fadeIn(motion(MOTION_SHORT)) + scaleIn(motion(MOTION_SHORT), initialScale = 0.6f),
            // Dropped in the trash, it falls in and vanishes instead of just fading.
            exit = if (spawn.dropped) {
                fadeOut(motion(MOTION_SHORT)) + scaleOut(motion(MOTION_SHORT), targetScale = 0f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                    androidx.compose.animation.slideOutVertically(motion(MOTION_SHORT)) { it }
            } else {
                fadeOut(motion(MOTION_SHORT)) + scaleOut(motion(MOTION_SHORT), targetScale = 0.6f)
            },
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = shrink
                        scaleY = shrink
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .size(88.dp, 60.dp)
                    .shadow(8.dp, shape)
                    .clip(shape)
                    .background(scheme.surfaceContainerHighest)
                    .border(1.5.dp, scheme.primary, shape),
                contentAlignment = Alignment.Center,
            ) {
                // Kept through the fade out, after the drag has already ended.
                var moving by remember { mutableStateOf(false) }
                if (spawn.pointer != null) moving = spawn.moving != null
                androidx.compose.material3.Icon(if (moving) Icons.Rounded.OpenWith else Icons.Rounded.Add, null, tint = scheme.primary)
            }
        }
    }
}

/** Like [splitZone], with the middle of the pane swapping the two. */
internal fun zoneFor(x: Float, y: Float): DropZone {
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
        val highlight = zoneRect(r, zoneFor(fx, fy))
        Box(
            paneModifier(highlight, fullW, fullH, LocalPaneLook.current.gap / 2)
                .clip(LocalPaneLook.current.shape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                .border(2.dp, MaterialTheme.colorScheme.primary, LocalPaneLook.current.shape),
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
                .clip(LocalPaneLook.current.shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(2.dp, MaterialTheme.colorScheme.primary, LocalPaneLook.current.shape),
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
    handleSpace: Boolean = false,
) {
    val app = controller.app
    val density = LocalDensity.current
    val look = LocalPaneLook.current
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
            .clip(LocalPaneLook.current.shape)
            .border(1.dp, ringColor, LocalPaneLook.current.shape),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clip(LocalPaneLook.current.shape),
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
                view.topSpace = if (handleSpace) with(density) { look.handleSpace.toPx() } else 0f
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
    BoxWithConstraints(modifier) {
        val panes = mutableListOf<Pair<String, FractionRect>>()
        window.tiledLayer?.layout?.let { place(it, FractionRect(0f, 0f, 1f, 1f), emptyMap(), panes, mutableListOf()) }
        // Later entries draw on top, so floating panes sit above the layout as they do in the window.
        window.floatingLayers.filterNot { it.minimized }.forEach { layer ->
            layer.layout?.let { place(it, layer.bounds, emptyMap(), panes, mutableListOf()) }
        }
        val w = maxWidth
        val h = maxHeight
        panes.forEach { (blockId, rect) ->
            key(blockId) {
                TerminalPreview(controller, blockId, paneModifier(rect, w, h, 2.dp).clip(RoundedCornerShape(12.dp)))
            }
        }
    }
}

/** A live terminal that can only be looked at. */
@Composable
fun TerminalPreview(controller: WorkspaceController, blockId: String, modifier: Modifier = Modifier) {
    val app = controller.app
    val session = remember(blockId) { controller.connection.terminal(controller.sessionId, blockId) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalView(ctx) { app.fonts.value }.also { view ->
                view.interactive = false
                view.bind(session)
            }
        },
        onRelease = { it.bind(null) },
    )
}

@Composable
fun <T> rememberUpdated(value: T) = remember { mutableStateOf(value) }.also { it.value = value }
