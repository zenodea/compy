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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardBackspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.data.KeyDef
import dev.compy.rex.RexKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun iconFor(name: String?): ImageVector? = when (name) {
    "command" -> Icons.Rounded.KeyboardCommandKey
    "left" -> Icons.AutoMirrored.Rounded.ArrowBack
    "right" -> Icons.AutoMirrored.Rounded.ArrowForward
    "up" -> Icons.Rounded.ArrowUpward
    "down" -> Icons.Rounded.ArrowDownward
    "backspace" -> Icons.AutoMirrored.Rounded.KeyboardBackspace
    "enter" -> Icons.AutoMirrored.Rounded.KeyboardReturn
    "paste" -> Icons.Rounded.ContentPaste
    "composer" -> Icons.Rounded.EditNote
    else -> null
}

private fun stickyBit(def: KeyDef) = when (def.key) {
    "ctrl" -> RexKey.CTRL
    "alt" -> RexKey.ALT
    "shift" -> RexKey.SHIFT
    "super" -> RexKey.SUPER
    else -> 0
}

private val CommandKey = KeyDef("sticky", "⌘", icon = "command", key = "super")

/**
 * The extra keys: a command key, swipeable pages of keys and a keyboard toggle. Modifiers latch for
 * the next key, and a second tap holds them on.
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
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundKey(controller, CommandKey)
        val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
        HorizontalPager(pager, Modifier.weight(1f).padding(horizontal = 4.dp)) { page ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.getOrNull(page).orEmpty().forEach { def -> KeyButton(controller, def, onOpenComposer) }
            }
        }
        RoundButton(
            if (keyboardVisible) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
            if (keyboardVisible) "Hide keyboard" else "Show keyboard",
            onClick = onToggleKeyboard,
        )
    }
}

@Composable
private fun RoundKey(controller: WorkspaceController, def: KeyDef) {
    val bit = stickyBit(def)
    val active = controller.stickyMods and bit != 0
    val scheme = MaterialTheme.colorScheme
    RoundButton(
        iconFor(def.icon)!!, def.label,
        tint = if (active) scheme.onPrimary else scheme.onSurface,
        modifier = if (active) Modifier.clip(CircleShape).background(scheme.primary) else Modifier,
        filled = !active,
    ) { controller.toggleSticky(bit) }
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
            .weight(1f)
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    locked -> scheme.primary
                    active -> scheme.primaryContainer
                    else -> Color.Transparent
                },
            )
            .semantics { contentDescription = def.label }
            .pointerInput(def) {
                detectTapGestures(
                    onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        when {
                            def.kind == "sticky" -> if (tryAwaitRelease()) controller.toggleSticky(bit)
                            def.kind == "composer" -> if (tryAwaitRelease()) onOpenComposer()
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
                            else -> if (tryAwaitRelease()) controller.press(def)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val tint = when {
            locked -> scheme.onPrimary
            active -> scheme.onPrimaryContainer
            else -> scheme.onSurface
        }
        val icon = iconFor(def.icon)
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
        } else {
            Text(def.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, softWrap = false)
        }
    }
}
