package dev.compy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val ChestW = 72.dp
private val ChestH = 63.dp
private val ChestMargin = 20.dp
private val ChestReach = 22.dp

/** Where the chest sits: the bottom-right corner of the pane area. */
internal fun chestRect(area: Rect, density: Density): Rect = with(density) {
    val right = area.right - ChestMargin.toPx()
    val bottom = area.bottom - ChestMargin.toPx()
    Rect(right - ChestW.toPx(), bottom - ChestH.toPx(), right, bottom)
}

/** Whether a point counts as over the chest. It reaches a little past the drawing, for fingers. */
internal fun overChest(area: Rect, density: Density, point: Offset): Boolean =
    chestRect(area, density).inflate(with(density) { ChestReach.toPx() }).contains(point)

private val Wood = Color(0xFFB8763C)
private val WoodDark = Color(0xFF8A5528)
private val Outline = Color(0xFF4A2A12)
private val Gold = Color(0xFFF6C744)
private val GoldDark = Color(0xFFC9932A)
private val Inside = Color(0xFF2A160A)

/**
 * A treasure chest that shows while a pane is carried. Hovering opens its lid and makes it wiggle;
 * dropping the pane in slams the lid, squashes the chest and throws out sparkles.
 */
@Composable
fun PaneChest(spawn: PaneSpawn, area: Rect) {
    val density = LocalDensity.current
    val carrying = spawn.moving != null && spawn.armed
    var lingering by remember { mutableStateOf(false) }
    val squash = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    LaunchedEffect(spawn.swallowed) {
        if (spawn.swallowed == 0) return@LaunchedEffect
        lingering = true
        launch {
            // A quick squash as the lid slams, then a springy recovery.
            squash.animateTo(1f, tween(90, easing = FastOutLinearInEasing))
            squash.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 380f))
        }
        launch {
            delay(70)
            burst.snapTo(0f)
            burst.animateTo(1f, tween(820, easing = LinearOutSlowInEasing))
        }
        delay(950)
        lingering = false
    }
    val shown = carrying || lingering
    val pop by animateFloatAsState(
        if (shown) 1f else 0f,
        if (shown) spring(dampingRatio = 0.42f, stiffness = 360f) else tween(200, easing = FastOutLinearInEasing),
        label = "chest-pop",
    )
    if (!shown && pop <= 0.001f) return

    val open = spawn.overChest
    val lid by animateFloatAsState(if (open) 1f else 0f, spring(dampingRatio = 0.38f, stiffness = 420f), label = "chest-lid")
    val hover by animateFloatAsState(if (open) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "chest-hover")
    val motionLoop = rememberInfiniteTransition(label = "chest-loop")
    val wiggle by motionLoop.animateFloat(-1f, 1f, infiniteRepeatable(tween(170, easing = LinearEasing), RepeatMode.Reverse), label = "chest-wiggle")
    val bob by motionLoop.animateFloat(-1f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "chest-bob")
    val rect = chestRect(area, density)
    val primary = MaterialTheme.colorScheme.primary

    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(ChestW, ChestH),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = squash.value
                    val grow = pop * (1f + 0.14f * hover)
                    scaleX = grow * (1f + 0.22f * s)
                    scaleY = grow * (1f - 0.2f * s)
                    rotationZ = wiggle * 5f * hover + (1f - pop) * -25f
                    translationY = bob * 2.dp.toPx() * (1f - hover)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
        ) {
            drawChest(lid.coerceAtLeast(-0.12f), hover)
        }
        Canvas(Modifier.fillMaxSize()) { drawSparkles(burst.value, primary) }
    }
    // To the chest's left, where neither the finger nor the card covers it.
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
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
            Text(
                "Close pane",
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 1,
            )
        }
    }
}

/** Draws the chest in a 64 by 56 grid, scaled to fit. */
private fun DrawScope.drawChest(lid: Float, glow: Float) {
    val u = size.width / 64f
    fun r(x: Float, y: Float, w: Float, h: Float) = Rect(x * u, y * u, (x + w) * u, (y + h) * u)
    val stroke = Stroke(width = 2f * u)

    drawOval(Color.Black.copy(alpha = 0.22f), topLeft = Offset(8 * u, 51 * u), size = Size(48 * u, 6 * u))
    // The dark inside, seen once the lid lifts, with a warm glow coming out of it.
    drawRoundRect(Inside, topLeft = Offset(6 * u, 21 * u), size = Size(52 * u, 8 * u), cornerRadius = CornerRadius(3 * u))
    if (glow > 0f) {
        drawOval(
            Brush.radialGradient(listOf(Gold.copy(alpha = 0.75f * glow), Color.Transparent), center = Offset(32 * u, 22 * u), radius = 34 * u),
            topLeft = Offset(-4 * u, -10 * u),
            size = Size(72 * u, 44 * u),
        )
    }

    // Body.
    val body = r(4f, 24f, 56f, 29f)
    drawRoundRect(Wood, body.topLeft, body.size, CornerRadius(5 * u))
    drawRect(WoodDark, topLeft = Offset(4 * u, 38 * u), size = Size(56 * u, 1.6f * u))
    drawRect(Color.White.copy(alpha = 0.1f), topLeft = Offset(6 * u, 25.5f * u), size = Size(52 * u, 3 * u))
    for (x in listOf(12f, 46f)) {
        drawRect(Gold, topLeft = Offset(x * u, 24 * u), size = Size(6 * u, 29 * u))
        drawRect(GoldDark, topLeft = Offset((x + 4.6f) * u, 24 * u), size = Size(1.4f * u, 29 * u))
    }
    drawRoundRect(Outline, body.topLeft, body.size, CornerRadius(5 * u), style = stroke)
    // Lock plate and keyhole.
    drawRoundRect(Gold, topLeft = Offset(26 * u, 27 * u), size = Size(12 * u, 13 * u), cornerRadius = CornerRadius(3 * u))
    drawRoundRect(Outline, topLeft = Offset(26 * u, 27 * u), size = Size(12 * u, 13 * u), cornerRadius = CornerRadius(3 * u), style = Stroke(1.5f * u))
    drawCircle(Outline, radius = 1.9f * u, center = Offset(32 * u, 32 * u))
    drawRect(Outline, topLeft = Offset(31.2f * u, 32 * u), size = Size(1.6f * u, 4.5f * u))

    // Lid, hinged at its back left corner.
    rotate(degrees = -50f * lid, pivot = Offset(5 * u, 25 * u)) {
        val lidRect = r(4f, 8f, 56f, 17f)
        val lidPath = Path().apply {
            addRoundRect(
                RoundRect(
                    lidRect,
                    topLeft = CornerRadius(16 * u),
                    topRight = CornerRadius(16 * u),
                    bottomRight = CornerRadius(2 * u),
                    bottomLeft = CornerRadius(2 * u),
                ),
            )
        }
        drawPath(lidPath, Wood)
        clipPath(lidPath) {
            drawRect(Color.White.copy(alpha = 0.14f), topLeft = Offset(4 * u, 8 * u), size = Size(56 * u, 5 * u))
            drawRect(WoodDark, topLeft = Offset(4 * u, 22 * u), size = Size(56 * u, 3 * u))
            for (x in listOf(12f, 46f)) {
                drawRect(Gold, topLeft = Offset(x * u, 8 * u), size = Size(6 * u, 17 * u))
                drawRect(GoldDark, topLeft = Offset((x + 4.6f) * u, 8 * u), size = Size(1.4f * u, 17 * u))
            }
        }
        drawPath(lidPath, Outline, style = stroke)
        drawRoundRect(Gold, topLeft = Offset(28 * u, 21 * u), size = Size(8 * u, 7 * u), cornerRadius = CornerRadius(2 * u))
        drawRoundRect(Outline, topLeft = Offset(28 * u, 21 * u), size = Size(8 * u, 7 * u), cornerRadius = CornerRadius(2 * u), style = Stroke(1.5f * u))
    }
}

/** Sparkles thrown up out of the chest, arcing out and falling as they fade. */
private fun DrawScope.drawSparkles(t: Float, accent: Color) {
    if (t <= 0f || t >= 1f) return
    val u = size.width / 64f
    val origin = Offset(32 * u, 22 * u)
    val colors = listOf(Gold, Color.White, accent, Gold, Color(0xFFFF8FB1))
    for (i in 0 until 11) {
        // Fanned upward, each a little different so the burst doesn't look mechanical.
        val angle = (-90.0 + (i - 5) * 17.0 + (if (i % 2 == 0) 6.0 else -4.0)) * Math.PI / 180.0
        val reach = (34f + (i * 7 % 5) * 7f) * u
        val out = 1f - (1f - t) * (1f - t)
        val center = Offset(
            origin.x + (cos(angle) * reach * out).toFloat(),
            origin.y + (sin(angle) * reach * out).toFloat() + 26f * u * t * t,
        )
        val s = (2.6f + (i % 3) * 1.1f) * u * (1f - 0.45f * t) * (if (t < 0.12f) t / 0.12f else 1f)
        val alpha = if (t < 0.7f) 1f else (1f - t) / 0.3f
        drawPath(sparkle(center, s), colors[i % colors.size].copy(alpha = alpha))
    }
}

private fun sparkle(c: Offset, s: Float) = Path().apply {
    val k = 0.32f
    moveTo(c.x, c.y - s)
    lineTo(c.x + s * k, c.y - s * k)
    lineTo(c.x + s, c.y)
    lineTo(c.x + s * k, c.y + s * k)
    lineTo(c.x, c.y + s)
    lineTo(c.x - s * k, c.y + s * k)
    lineTo(c.x - s, c.y)
    lineTo(c.x - s * k, c.y - s * k)
    close()
}
