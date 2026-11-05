package dev.compy.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.AppState
import dev.compy.data.DefaultKeyBar
import dev.compy.data.KeyDef
import dev.compy.rex.RexKey
import kotlin.math.roundToInt

private val RowHeight = 52.dp

/** Roughly how many keys fit comfortably on one page at this screen width. */
private fun comfortableKeys(screenWidthDp: Int): Int = ((screenWidthDp - 112) / 34).coerceAtLeast(4)

/**
 * Edits the key bar with a live preview. Choose a page, tap keys below to add or remove them, and
 * drag rows by their handle to reorder.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyBarEditor(app: AppState, onBack: () -> Unit) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val pages = settings.keyBarPages.ifEmpty { listOf(emptyList()) }
    var selected by remember { mutableIntStateOf(0) }
    val page = selected.coerceIn(0, pages.lastIndex)
    val keys = pages[page]
    var customOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val comfortable = comfortableKeys(LocalConfiguration.current.screenWidthDp)

    fun setPage(change: (List<KeyDef>) -> List<KeyDef>) {
        app.updateSettings { s ->
            val all = s.keyBarPages.ifEmpty { listOf(emptyList()) }
            s.copy(keyBarPages = all.mapIndexed { i, k -> if (i == page) change(k) else k })
        }
    }

    Column(Modifier.fillMaxSize().background(scheme.background)) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", filled = false, onClick = onBack)
            Text("Key bar", Modifier.padding(start = 4.dp).weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { confirmReset = true }) { Text("Reset") }
        }

        // The preview stays in view while the list scrolls under it, so every change is visible.
        KeyBarPreview(keys)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEachIndexed { i, _ ->
                FilterChip(selected = i == page, onClick = { selected = i }, label = { Text("Page ${i + 1}") })
            }
            RoundButton(Icons.Rounded.Add, "Add page", size = 32.dp) {
                app.updateSettings { it.copy(keyBarPages = it.keyBarPages + listOf(emptyList())) }
                selected = pages.size
            }
            if (pages.size > 1) {
                TextButton(onClick = {
                    app.updateSettings { s -> s.copy(keyBarPages = s.keyBarPages.filterIndexed { i, _ -> i != page }) }
                    selected = (page - 1).coerceAtLeast(0)
                }) { Text("Remove page") }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            if (keys.isEmpty()) {
                Text(
                    "This page is empty. Tap keys below to add them.",
                    Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                ReorderableKeys(
                    keys,
                    onMove = { from, to ->
                        setPage { list -> list.toMutableList().also { it.add(to, it.removeAt(from)) } }
                    },
                    onRemove = { index -> setPage { list -> list.filterIndexed { i, _ -> i != index } } },
                )
            }
            if (keys.size > comfortable) {
                Text(
                    "Keys get narrow past $comfortable on this screen. Try adding another page.",
                    Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            Text(
                "Add or remove keys",
                Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            DefaultKeyBar.groups.forEach { group ->
                Text(
                    group,
                    Modifier.padding(start = 24.dp, top = 10.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                FlowRow(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DefaultKeyBar.catalog.filter { DefaultKeyBar.info(it).group == group }.forEach { def ->
                        val onPage = keys.any { it == def }
                        CatalogChip(def, onPage) {
                            setPage { list -> if (onPage) list.filterNot { it == def } else list + def }
                        }
                    }
                }
            }
            Row(
                Modifier
                    .padding(start = 16.dp, top = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { customOpen = true }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp), tint = scheme.primary)
                Text("Make your own key", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
        }
    }

    if (customOpen) {
        CustomKeyDialog(
            onDismiss = { customOpen = false },
            onAdd = { def ->
                customOpen = false
                setPage { it + def }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the key bar?") },
            text = { Text("This restores the original keys and removes your custom ones.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    selected = 0
                    app.updateSettings { it.copy(keyBarPages = DefaultKeyBar.pages) }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/** The selected page as it'll look above the keyboard. */
@Composable
private fun KeyBarPreview(keys: List<KeyDef>) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer)
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewRound { Icon(Icons.Rounded.KeyboardCommandKey, null, Modifier.size(20.dp), tint = scheme.onSurface) }
        Row(
            Modifier.weight(1f).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (keys.isEmpty()) {
                Text("Empty page", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            keys.forEach { def ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { KeyFace(def, scheme.onSurface) }
            }
        }
        PreviewRound { Icon(Icons.Rounded.Keyboard, null, Modifier.size(20.dp), tint = scheme.onSurface) }
    }
}

@Composable
private fun PreviewRound(content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Rows that drag by their handle. Other rows slide aside, and the move saves on release. */
@Composable
private fun ReorderableKeys(keys: List<KeyDef>, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit) {
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val target = if (dragging >= 0) (dragging + (dragY / rowPx).roundToInt()).coerceIn(0, keys.lastIndex) else -1
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        keys.forEachIndexed { index, def ->
            key(index, def) {
                val isDragged = index == dragging
                val shift = when {
                    dragging < 0 || isDragged -> 0f
                    index in (dragging + 1)..target -> -rowPx
                    index in target until dragging -> rowPx
                    else -> 0f
                }
                val slide by animateFloatAsState(shift, motion(MOTION_SHORT), label = "row-slide")
                val background by animateColorAsState(
                    if (isDragged) scheme.surfaceContainerHigh else scheme.background,
                    motion(MOTION_SHORT),
                    label = "row-background",
                )
                val lift by animateDpAsState(if (isDragged) 12.dp else 0.dp, motion(MOTION_SHORT), label = "row-lift")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(RowHeight)
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) dragY else slide
                            shadowElevation = lift.toPx()
                            shape = RoundedCornerShape(14.dp)
                            clip = isDragged
                        }
                        .background(background)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .pointerInput(keys.size) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = index
                                        dragY = 0f
                                    },
                                    onDragEnd = {
                                        val from = dragging
                                        val to = (from + (dragY / rowPx).roundToInt()).coerceIn(0, keys.lastIndex)
                                        dragging = -1
                                        dragY = 0f
                                        if (from != to) onMove(from, to)
                                    },
                                    onDragCancel = {
                                        dragging = -1
                                        dragY = 0f
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragY += amount.y
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.DragHandle, "Drag to reorder", tint = scheme.onSurfaceVariant)
                    }
                    Box(
                        Modifier
                            .widthIn(min = 44.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.surfaceContainerHigh)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { KeyFace(def, scheme.onSurface) }
                    Text(
                        DefaultKeyBar.info(def).name,
                        Modifier.padding(start = 12.dp).weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RoundButton(Icons.Rounded.Close, "Remove ${DefaultKeyBar.info(def).name}", size = 40.dp, filled = false, tint = scheme.onSurfaceVariant) { onRemove(index) }
                }
            }
        }
    }
}

@Composable
private fun CatalogChip(def: KeyDef, onPage: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(if (onPage) scheme.primaryContainer else scheme.surfaceContainerHigh, motion(MOTION_SHORT), label = "chip")
    val tint by animateColorAsState(if (onPage) scheme.onPrimaryContainer else scheme.onSurface, motion(MOTION_SHORT), label = "chip-tint")
    Row(
        Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPage) Icon(Icons.Rounded.Check, "On this page", Modifier.padding(end = 6.dp).size(16.dp), tint = tint)
        KeyFace(def, tint)
    }
}

/** Builds a key that types some text or presses a key combination. */
@Composable
private fun CustomKeyDialog(onDismiss: () -> Unit, onAdd: (KeyDef) -> Unit) {
    var combo by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var thenEnter by remember { mutableStateOf(false) }
    var character by remember { mutableStateOf("") }
    var mods by remember { mutableStateOf(setOf("ctrl")) }
    val modOrder = listOf("shift" to "shift", "ctrl" to "ctrl", "alt" to "opt", "super" to "⌘")
    val comboMods = modOrder.map { it.first }.filter { it in mods }.joinToString("+")
    val comboKey = character.firstOrNull()?.let { RexKey.forChar(it.lowercaseChar().code).key }?.takeIf { it != "unidentified" }
    val suggested = if (combo) {
        (modOrder.filter { it.first in mods }.map { it.second } + character.uppercase()).joinToString("+")
    } else {
        text.take(6)
    }
    val valid = if (combo) comboKey != null else text.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make your own key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!combo, { combo = false }, SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Types text") })
                    SegmentedButton(combo, { combo = true }, SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Key combo") })
                }
                if (combo) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        modOrder.forEach { (id, name) ->
                            FilterChip(
                                selected = id in mods,
                                onClick = { mods = if (id in mods) mods - id else mods + id },
                                label = { Text(name) },
                            )
                        }
                    }
                    OutlinedTextField(
                        character,
                        { character = it.takeLast(1) },
                        label = { Text("Plus this key") },
                        placeholder = { Text("r") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        text, { text = it },
                        label = { Text("Text it types") },
                        placeholder = { Text("git status") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Press Enter after", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(thenEnter, { thenEnter = it })
                    }
                }
                OutlinedTextField(
                    label, { label = it },
                    label = { Text("Label on the key") },
                    placeholder = { Text(suggested.ifEmpty { "Optional" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val name = label.trim().ifEmpty { suggested }
                onAdd(
                    if (combo) {
                        KeyDef("key", name, key = comboKey!!, mods = comboMods)
                    } else {
                        KeyDef("text", name, text = text, thenEnter = thenEnter)
                    },
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
