package dev.compy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TrashSize = 60.dp
private val TrashMargin = 20.dp
private val TrashReach = 22.dp

/** Where the trash can sits: the bottom-right corner of the pane area. */
internal fun trashRect(area: Rect, density: Density): Rect = with(density) {
    val right = area.right - TrashMargin.toPx()
    val bottom = area.bottom - TrashMargin.toPx()
    Rect(right - TrashSize.toPx(), bottom - TrashSize.toPx(), right, bottom)
}

/** Whether a point counts as over the trash can. It reaches a little past the drawing, for fingers. */
internal fun overTrash(area: Rect, density: Density, point: Offset): Boolean =
    trashRect(area, density).inflate(with(density) { TrashReach.toPx() }).contains(point)

/**
 * A round trash button that shows while a pane is carried. Hovering turns it red and flips its lid
 * open; dropping the pane in slams the lid, gives the can a squash and sends out a ripple and a
 * little puff of dust.
 */
@Composable
fun PaneTrash(spawn: PaneSpawn, area: Rect) {
    val density = LocalDensity.current
    val carrying = spawn.moving != null && spawn.armed
    var lingering by remember { mutableStateOf(false) }
    val squash = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    LaunchedEffect(spawn.swallowed) {
        if (spawn.swallowed == 0) return@LaunchedEffect
        lingering = true
        launch {
            // The lid slams first, then the can squashes under it and springs back.
            delay(60)
            squash.animateTo(1f, tween(80, easing = FastOutLinearInEasing))
            squash.animateTo(0f, spring(dampingRatio = 0.32f, stiffness = 420f))
        }
        launch {
            delay(90)
            burst.snapTo(0f)
            burst.animateTo(1f, tween(620, easing = LinearOutSlowInEasing))
        }
        delay(760)
        lingering = false
    }
    val shown = carrying || lingering
    val pop by animateFloatAsState(
        if (shown) 1f else 0f,
        if (shown) spring(dampingRatio = 0.5f, stiffness = 420f) else tween(180, easing = FastOutLinearInEasing),
        label = "trash-pop",
    )
    if (!shown && pop <= 0.001f) return

    val open = spawn.overTrash
    val scheme = MaterialTheme.colorScheme
    val lid by animateFloatAsState(if (open) 1f else 0f, spring(dampingRatio = 0.35f, stiffness = 520f), label = "trash-lid")
    val hover by animateFloatAsState(if (open) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 520f), label = "trash-hover")
    // It stays red through the drop, and fades back as it leaves.
    val danger by animateFloatAsState(if (open || lingering) 1f else 0f, tween(160), label = "trash-danger")
    val badge = lerp(scheme.surfaceContainerHighest, scheme.error, danger)
    val ink = lerp(scheme.onSurface, scheme.onError, danger)
    val rect = trashRect(area, density)

    Canvas(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(TrashSize)
            .graphicsLayer {
                val s = squash.value
                val grow = pop * (1f + 0.16f * hover)
                scaleX = grow * (1f + 0.14f * s)
                scaleY = grow * (1f - 0.16f * s)
                rotationZ = (1f - pop) * 30f
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
    ) {
        drawRipple(burst.value, scheme.error)
        drawCircle(Color.Black.copy(alpha = 0.22f), radius = size.minDimension / 2, center = center + Offset(0f, 2.dp.toPx()))
        drawCircle(badge)
        drawCan(ink, lid.coerceAtLeast(-0.15f))
        drawDust(burst.value, ink)
    }
    // To the can's left, where neither the finger nor the card covers it.
    AnimatedVisibility(
        open,
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints())
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(
                    (rect.left - placeable.width - 10.dp.toPx()).roundToInt(),
                    (rect.center.y - placeable.height / 2f).roundToInt(),
                )
            }
        },
        enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f), initialScale = 0.5f),
        exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.7f),
    ) {
        Surface(shape = CircleShape, color = scheme.errorContainer) {
            Text(
                "Close pane",
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onErrorContainer,
                maxLines = 1,
            )
        }
    }
}

/** A line-drawn trash can on a 24 unit grid, centred in the badge. The lid hinges at its right end. */
private fun DrawScope.drawCan(color: Color, lid: Float) {
    val u = size.minDimension * 0.5f / 24f
    val ox = (size.width - 24f * u) / 2f
    val oy = (size.height - 24f * u) / 2f + 0.6f * u
    fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    val line = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

    val body = Path().apply {
        p(6.3f, 9.2f).let { moveTo(it.x, it.y) }
        p(7.3f, 19f).let { lineTo(it.x, it.y) }
        val c1 = p(7.5f, 20.6f)
        val e1 = p(9.1f, 20.6f)
        quadraticTo(c1.x, c1.y, e1.x, e1.y)
        p(14.9f, 20.6f).let { lineTo(it.x, it.y) }
        val c2 = p(16.5f, 20.6f)
        val e2 = p(16.7f, 19f)
        quadraticTo(c2.x, c2.y, e2.x, e2.y)
        p(17.7f, 9.2f).let { lineTo(it.x, it.y) }
    }
    drawPath(body, color, style = line)
    drawLine(color, p(10.2f, 11.8f), p(10.4f, 17.6f), line.width, StrokeCap.Round)
    drawLine(color, p(13.8f, 11.8f), p(13.6f, 17.6f), line.width, StrokeCap.Round)

    translate(top = -2.4f * u * lid) {
        rotate(degrees = 36f * lid, pivot = p(19f, 6.8f)) {
            drawLine(color, p(5f, 6.8f), p(19f, 6.8f), line.width, StrokeCap.Round)
            val handle = Path().apply {
                p(9.6f, 6.8f).let { moveTo(it.x, it.y) }
                p(9.9f, 4.4f).let { lineTo(it.x, it.y) }
                p(14.1f, 4.4f).let { lineTo(it.x, it.y) }
                p(14.4f, 6.8f).let { lineTo(it.x, it.y) }
            }
            drawPath(handle, color, style = line)
        }
    }
}

/** A ring that spreads out from the badge and fades. */
private fun DrawScope.drawRipple(t: Float, color: Color) {
    if (t <= 0f || t >= 1f) return
    val r = size.minDimension / 2f * (1f + 0.75f * t)
    drawCircle(color.copy(alpha = 0.45f * (1f - t)), radius = r, style = Stroke(width = 3.dp.toPx() * (1f - t)))
}

/** Little puffs pushed out sideways from under the lid as it slams. */
private fun DrawScope.drawDust(t: Float, color: Color) {
    if (t <= 0f || t >= 0.8f) return
    val k = t / 0.8f
    val u = size.minDimension * 0.5f / 24f
    val ox = (size.width - 24f * u) / 2f
    val oy = (size.height - 24f * u) / 2f + 0.6f * u
    val out = 1f - (1f - k) * (1f - k)
    for (side in listOf(-1f, 1f)) {
        val rim = Offset(ox + (12f + side * 6.5f) * u, oy + 7.5f * u)
        for (i in 0 until 3) {
            val spread = (3.5f + i * 2.6f) * u * out
            val lift = (i - 1) * 2.2f * u * out - 1.2f * u * out
            val radius = (1.3f - i * 0.25f) * u * (1f - 0.5f * k)
            drawCircle(color.copy(alpha = 0.85f * (1f - k)), radius, Offset(rim.x + side * spread, rim.y + lift))
        }
    }
}
