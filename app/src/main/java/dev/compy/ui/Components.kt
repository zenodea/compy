package dev.compy.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.compy.rex.ProgramStatus
import dev.compy.rex.WindowView

/** The one round icon button used across the app. */
@Composable
fun RoundButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    filled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(size * 0.5f), tint = tint)
    }
}

internal val BlockedColor = Color(0xFFF59E0B)

/** A dot for the most urgent state among some blocks; nothing when all are quiet. */
@Composable
fun StatusDot(blockIds: List<String>, statuses: Map<String, ProgramStatus>, attention: Set<String>, modifier: Modifier = Modifier) {
    val all = blockIds.mapNotNull { statuses[it] }
    val scheme = MaterialTheme.colorScheme
    when {
        all.any { it.isBlocked } -> Dot(BlockedColor, modifier)
        all.any { it.isError } -> Dot(scheme.error, modifier)
        all.any { it.isWorking } -> {
            val pulse by rememberInfiniteTransition(label = "working").animateFloat(
                0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "working",
            )
            Dot(scheme.primary, modifier.alpha(pulse))
        }
        blockIds.any { it in attention } -> Dot(scheme.primary, modifier)
    }
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(11.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .padding(2.dp)
            .clip(CircleShape)
            .background(color),
    )
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
    Box(contentAlignment = Alignment.TopEnd) {
        ProgramTile(
            ProgramNames.candidates(shown?.let { statuses[it]?.app }, shown?.let { programs[it] }),
            size = size,
            stacked = blocks.size > 1,
        )
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
