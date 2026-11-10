package dev.compy.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.compy.rex.ProgramStatus
import dev.compy.rex.WindowView

/** Compy's motion: quick and decelerating, so things feel light and never hold up typing. */
internal val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal const val MOTION_SHORT = 180
internal const val MOTION_MEDIUM = 280
internal const val MOTION_LONG = 340

internal fun <T> motion(duration: Int = MOTION_MEDIUM) = tween<T>(duration, easing = EmphasizedEasing)

/** The one round icon button used across the app. It dips slightly while pressed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoundButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    filled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconRotation: Float = 0f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, motion(MOTION_SHORT), label = "press")
    val rotation by animateFloatAsState(iconRotation, motion(), label = "rotation")
    val shownTint by animateColorAsState(tint, motion(MOTION_SHORT), label = "tint")
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .then(if (filled) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
            .combinedClickable(interactionSource = interaction, indication = ripple(), onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(size * 0.5f).rotate(rotation), tint = shownTint)
    }
}

internal val BlockedColor = Color(0xFFF59E0B)

/** A dot for the most urgent state among some blocks. It fades and scales in and out. */
@Composable
fun StatusDot(blockIds: List<String>, statuses: Map<String, ProgramStatus>, attention: Set<String>, modifier: Modifier = Modifier) {
    val all = blockIds.mapNotNull { statuses[it] }
    val scheme = MaterialTheme.colorScheme
    val working = all.none { it.isBlocked || it.isError } && all.any { it.isWorking }
    val color = when {
        all.any { it.isBlocked } -> BlockedColor
        all.any { it.isError } -> scheme.error
        working -> scheme.primary
        blockIds.any { it in attention } -> scheme.primary
        else -> null
    }
    // Keep the last color while fading out, so a dot doesn't turn black as it leaves.
    var lastColor by remember { mutableStateOf(color ?: scheme.primary) }
    if (color != null) lastColor = color
    val shownColor by animateColorAsState(lastColor, motion(), label = "dot-color")
    val scale by animateFloatAsState(if (color != null) 1f else 0f, motion(), label = "dot-scale")
    if (scale <= 0.01f) return
    // Kept as state and read only while drawing. Read here, it recomposed every working dot on
    // every frame for as long as an agent worked.
    val pulse = if (working) {
        rememberInfiniteTransition(label = "working").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "working",
        )
    } else {
        null
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale
            }
            .size(11.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .padding(2.dp)
            .clip(CircleShape)
            .graphicsLayer { alpha = pulse?.value ?: 1f }
            .background(shownColor),
    )
}

/** Reported progress for each block, in percent, or -1 for busy with no known amount. */
val LocalBlockProgress = compositionLocalOf<Map<String, Int>> { emptyMap() }

/** A thin ring around a tile, showing how far along a program is. */
@Composable
private fun ProgressRing(percent: Int?, size: Dp) {
    val shown by animateFloatAsState(if (percent == null) 0f else 1f, motion(), label = "ring-alpha")
    if (shown <= 0.01f) return
    val sweep by animateFloatAsState(((percent ?: 0).coerceAtLeast(0)) / 100f * 360f, motion(), label = "ring-sweep")
    val indeterminate = percent == -1
    // Only a ring with no known amount spins, so only that one keeps an animation running.
    val spin = if (indeterminate) {
        rememberInfiniteTransition(label = "ring").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "ring-spin",
        )
    } else {
        null
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size).graphicsLayer { alpha = shown }) {
        val stroke = 2.dp.toPx()
        drawArc(
            color,
            startAngle = spin?.value ?: -90f,
            sweepAngle = if (indeterminate) 90f else sweep,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** A window's icon tile with its status dot in the corner. */
@Composable
fun WindowTile(
    window: WindowView,
    statuses: Map<String, ProgramStatus>,
    programs: Map<String, String>,
    attention: Set<String>,
    size: Dp = 28.dp,
) {
    val blocks = window.blocks.map { it.id }
    val main = window.focusedBlockId ?: blocks.firstOrNull()
    // A blocked or working agent in any pane is the most useful thing to show.
    val busy = blocks.firstOrNull { statuses[it]?.isBlocked == true } ?: blocks.firstOrNull { statuses[it]?.isWorking == true }
    val shown = busy ?: main
    val reported = LocalBlockProgress.current
    val percent = blocks.firstNotNullOfOrNull { statuses[it]?.progress ?: reported[it] }
    Box(contentAlignment = Alignment.TopEnd) {
        Box(contentAlignment = Alignment.Center) {
            ProgramTile(
                ProgramNames.candidates(shown?.let { statuses[it]?.app }, shown?.let { programs[it] }),
                size = if (percent != null) size - 6.dp else size,
                stacked = blocks.size > 1,
            )
            ProgressRing(percent, size)
        }
        StatusDot(blocks, statuses, attention, Modifier.padding(0.dp))
    }
}

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
