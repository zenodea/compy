package dev.compy.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardBackspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardCommandKey
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.data.KeyDef
import dev.compy.rex.RexKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun iconFor(name: String?): ImageVector? = when (name) {
    "command" -> Icons.Filled.KeyboardCommandKey
    "left" -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    "right" -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    "up" -> Icons.Filled.KeyboardArrowUp
    "down" -> Icons.Filled.KeyboardArrowDown
    "backspace" -> Icons.AutoMirrored.Filled.KeyboardBackspace
    "enter" -> Icons.AutoMirrored.Filled.KeyboardReturn
    "paste" -> Icons.Filled.ContentPaste
    "composer" -> Icons.Filled.EditNote
    else -> null
}

private fun stickyBit(def: KeyDef) = when (def.key) {
    "ctrl" -> RexKey.CTRL
    "alt" -> RexKey.ALT
    "shift" -> RexKey.SHIFT
    "super" -> RexKey.SUPER
    else -> 0
}

/**
 * The row of extra keys above the keyboard. Modifiers latch for the next key, and a second tap
 * holds them on. Swipe sideways for more pages of keys.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyBar(
    controller: WorkspaceController,
    keyboardVisible: Boolean,
    onToggleKeyboard: () -> Unit,
    onOpenComposer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by controller.app.settings.collectAsStateWithLifecycle()
    val pages = settings.keyBarPages.filter { it.isNotEmpty() }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pages.getOrNull(page).orEmpty().forEach { def ->
                        KeyButton(controller, def, onOpenComposer)
                    }
                }
            }
            KeyBarIcon(
                if (keyboardVisible) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                if (keyboardVisible) "Hide keyboard" else "Show keyboard",
                onClick = onToggleKeyboard,
            )
        }
    }
}

@Composable
private fun RowScope.KeyButton(controller: WorkspaceController, def: KeyDef, onOpenComposer: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val bit = stickyBit(def)
    val active = bit != 0 && controller.stickyMods and bit != 0
    val locked = bit != 0 && controller.isLocked(bit)
    val scheme = MaterialTheme.colorScheme
    val repeats = def.kind == "key" && def.mods.isEmpty() && def.icon != null
    Box(
        Modifier
            .widthIn(min = 30.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    locked -> scheme.primary
                    active -> scheme.primaryContainer
                    else -> scheme.surfaceContainer
                },
            )
            .pointerInput(def) {
                detectTapGestures(
                    onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        when {
                            def.kind == "sticky" -> {
                                if (tryAwaitRelease()) controller.toggleSticky(bit)
                            }
                            def.kind == "composer" -> {
                                if (tryAwaitRelease()) onOpenComposer()
                            }
                            repeats -> {
                                controller.press(def)
                                val repeat = scope.launch {
                                    delay(400)
                                    while (true) {
                                        controller.press(def)
                                        delay(60)
                                    }
                                }
                                tryAwaitRelease()
                                repeat.cancel()
                            }
                            else -> {
                                if (tryAwaitRelease()) controller.press(def)
                            }
                        }
                    },
                )
            }
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        val tint = when {
            locked -> scheme.onPrimary
            active -> scheme.onPrimaryContainer
            else -> scheme.onSurface
        }
        val icon = iconFor(def.icon)
        if (icon != null) {
            Icon(icon, def.label, tint = tint)
        } else {
            Text(def.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun KeyBarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(38.dp)
            .widthIn(min = 44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface)
    }
}
