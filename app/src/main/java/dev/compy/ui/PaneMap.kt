package dev.compy.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.compy.rex.FractionRect
import dev.compy.rex.WindowView
import dev.compy.rex.paneRects

/** One pane on the map: where it is in the window, and whether it floats over the layout. */
internal data class MapPane(val blockId: String, val rect: FractionRect, val floating: Boolean)

/** Every pane of a window that can be gone to, as the map draws them: the layout's first, then the floating ones on top. */
internal fun mapPanes(window: WindowView): List<MapPane> =
    paneRects(window.tiledLayer?.layout).map { MapPane(it.first, it.second, floating = false) } +
        window.floatingLayers.filterNot { it.minimized }.flatMap { layer -> layer.blocks.map { MapPane(it.id, layer.bounds, floating = true) } }

/**
 * A small picture of a window's layout, shown while one pane fills the screen, with that pane
 * lit. It says where you are among the panes and which way the others lie. Tapped, it grows
 * large enough to pick a pane from, each with its name.
 */
@Composable
fun PaneMap(window: WindowView, shown: String, titles: Map<String, String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var open by remember(window.id) { mutableStateOf(false) }
    val panes = remember(window) { mapPanes(window) }
    val width by animateDpAsState(if (open) 232.dp else 44.dp, motion(), label = "map-width")
    val height by animateDpAsState(if (open) 168.dp else 32.dp, motion(), label = "map-height")
    val gap = if (open) 2.dp else 1.dp
    Box(
        modifier
            .size(width, height)
            .clip(RoundedCornerShape(if (open) 16.dp else 9.dp))
            .background(scheme.surfaceContainerHighest.copy(alpha = 0.94f))
            .clickable(onClickLabel = if (open) "Close the map of panes" else "Choose a pane") { open = !open }
            .semantics { contentDescription = "Map of this window's ${panes.size} panes" }
            .padding(if (open) 6.dp else 3.dp),
    ) {
        BoxWithConstraints {
            val w = maxWidth
            val h = maxHeight
            panes.forEach { pane ->
                val lit = pane.blockId == shown
                val shape = RoundedCornerShape(if (open) 8.dp else 3.dp)
                Box(
                    Modifier
                        .offset(w * pane.rect.x + gap, h * pane.rect.y + gap)
                        .size((w * pane.rect.w - gap * 2).coerceAtLeast(2.dp), (h * pane.rect.h - gap * 2).coerceAtLeast(2.dp))
                        .clip(shape)
                        .background(if (lit) scheme.primary else scheme.onSurfaceVariant.copy(alpha = if (pane.floating) 0.5f else 0.28f))
                        // A floating pane lies over the layout, so it is outlined to stand apart from it.
                        .then(if (pane.floating) Modifier.border(1.dp, scheme.surfaceContainerHighest, shape) else Modifier)
                        .then(
                            if (open) {
                                Modifier.clickable {
                                    open = false
                                    onPick(pane.blockId)
                                }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (open) {
                        Text(
                            titles[pane.blockId]?.takeIf { it.isNotEmpty() } ?: "Pane",
                            Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lit) scheme.onPrimary else scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
