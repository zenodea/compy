package dev.compy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.app.NotificationManagerCompat
import dev.compy.AppState
import dev.compy.system.AppIcon
import dev.compy.system.AppIcons
import dev.compy.system.batteryExemptionIntent
import dev.compy.system.isIgnoringBatteryOptimizations
import dev.compy.system.notificationSettingsIntent
import dev.compy.data.DefaultKeyBar
import dev.compy.data.KeyDef
import dev.compy.data.Settings
import dev.compy.term.TerminalFonts
import dev.compy.term.TerminalView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(app: AppState, onBack: () -> Unit) {
    var keyBarEditor by remember { mutableStateOf(false) }
    BackHandler { if (keyBarEditor) keyBarEditor = false else onBack() }
    AnimatedContent(
        keyBarEditor,
        transitionSpec = {
            val direction = if (targetState) 1 else -1
            (slideInHorizontally(motion()) { direction * it / 4 } + fadeIn(motion(MOTION_SHORT))) togetherWith
                (slideOutHorizontally(motion()) { -direction * it / 8 } + fadeOut(motion(MOTION_SHORT)))
        },
        label = "settings",
    ) { editing ->
        if (editing) {
            KeyBarEditor(app, onBack = { keyBarEditor = false })
        } else {
            SettingsList(app, onBack, onEditKeyBar = { keyBarEditor = true })
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", filled = false, onClick = onBack)
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
    }
}

/** A titled group of settings on one rounded card. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp).padding(top = 14.dp)) {
        Text(
            title,
            Modifier.padding(start = 12.dp, bottom = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) { content() }
    }
}

@Composable
private fun Row2(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** A setting on a slider, saved when the thumb is let go so the file isn't rewritten on every step. */
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        var shown by remember(value) { mutableFloatStateOf(value) }
        Text("$title: ${format(shown)}", style = MaterialTheme.typography.bodyLarge)
        Slider(shown, onValueChange = { shown = it }, onValueChangeFinished = { onChange(shown) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row2(title, subtitle, onClick = { onChange(!checked) }) { Switch(checked, onChange) }
}

@Composable
private fun Segments(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        options.forEachIndexed { i, (id, name) ->
            SegmentedButton(
                selected = selected == id,
                onClick = { onSelect(id) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(name) },
            )
        }
    }
}

/** Each icon as it looks on a home screen, to choose which one the launcher shows. */
@Composable
private fun AppIconPicker() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcons.current(context)) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppIcon.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { icon ->
                    val selected = icon == current
                    val shape = RoundedCornerShape(18.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                AppIcons.choose(icon)
                                current = icon
                            }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(68.dp)
                                .border(2.dp, if (selected) scheme.primary else Color.Transparent, RoundedCornerShape(22.dp))
                                .padding(5.dp)
                                .clip(shape),
                        ) {
                            // These layers are drawn for a launcher, which shows only their middle two thirds.
                            listOf(icon.background, icon.foreground).forEach { layer ->
                                Image(
                                    painterResource(layer), null,
                                    Modifier.fillMaxSize().graphicsLayer {
                                        scaleX = 1.5f
                                        scaleY = 1.5f
                                    },
                                )
                            }
                        }
                        Text(
                            icon.label,
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Text(
            "The icon changes when you leave Compy, and your launcher can take a few seconds to update. An icon you placed on the home screen may need adding again.",
            Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsList(app: AppState, onBack: () -> Unit, onEditKeyBar: () -> Unit) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var themeMenu by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var editingEditor by remember { mutableStateOf(false) }
    val update: ((Settings) -> Settings) -> Unit = { app.updateSettings(it) }
    // Counts returns to this screen, so what Android allows is read again after a trip to its settings.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val old = settings.customFontPath
            // Off the main thread: a file kept in the cloud is downloaded as it's read.
            app.scope.launch(Dispatchers.IO) {
                // Each import needs a fresh path so the change is seen even with the same name.
                val stamped = File(context.filesDir, "terminal-font-${System.currentTimeMillis()}.ttf")
                val usable = runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input -> stamped.outputStream().use { input.copyTo(it) } }
                    TerminalFonts.fromFile(context, stamped) != null
                }.getOrDefault(false)
                if (usable) {
                    update { it.copy(customFontPath = stamped.path) }
                    old?.let { path -> File(path).delete() }
                } else {
                    // Kept, it would show as the imported font while the built-in one was drawn.
                    stamped.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "That file isn't a font Compy can use", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header("Settings", onBack)
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Group("Appearance") {
                    Segments(listOf("host" to "Match terminal", "wallpaper" to "Wallpaper"), settings.appColors) { v -> update { it.copy(appColors = v) } }
                    AnimatedVisibility(
                        settings.appColors == "wallpaper",
                        enter = expandVertically(motion()) + fadeIn(motion(MOTION_SHORT)),
                        exit = shrinkVertically(motion()) + fadeOut(motion(MOTION_SHORT)),
                    ) {
                        Segments(listOf("system" to "System", "light" to "Light", "dark" to "Dark"), settings.themeMode) { v -> update { it.copy(themeMode = v) } }
                    }
                    SwitchRow("AMOLED black", "Use a pure black canvas with dark colors", settings.amoled) { v -> update { it.copy(amoled = v) } }
                    Row2("Pane layout", "Tight packs panes edge to edge with thin lines, like tmux. Only changes how Compy shows them.")
                    Segments(listOf("relaxed" to "Relaxed", "tight" to "Tight"), settings.paneLayout) { v -> update { it.copy(paneLayout = v) } }
                }
            }
            item {
                Group("App icon") { AppIconPicker() }
                Group("Notifications") {
                    val notificationsAllowed = remember(resumes) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
                    val batteryExempt = remember(resumes) { isIgnoringBatteryOptimizations(context) }
                    SwitchRow(
                        "Alert me when Compy is closed",
                        "Stays connected in the background and shows a silent ongoing notification. Uses some battery.",
                        settings.backgroundAlerts,
                    ) { v -> update { it.copy(backgroundAlerts = v) } }
                    if (!notificationsAllowed) {
                        Row2("Notifications are off", "Android is blocking them, so alerts can't appear. Tap to turn them on.", onClick = {
                            context.startActivity(notificationSettingsIntent(context))
                        })
                    } else {
                        Row2("Choose alert types", "Sound and importance for each kind of alert", onClick = {
                            context.startActivity(notificationSettingsIntent(context))
                        })
                    }
                    if (settings.backgroundAlerts && !batteryExempt) {
                        Row2("Allow background use", "Without this, your phone may stop the connection alerts depend on", onClick = {
                            context.startActivity(batteryExemptionIntent(context))
                        })
                    }
                }
            }
            item {
                Group("Terminal") {
                    Box {
                        Row2(
                            "Colors",
                            TerminalThemes.options.firstOrNull { it.id == settings.terminalTheme }?.name.orEmpty(),
                            onClick = { themeMenu = true },
                        )
                        DropdownMenu(themeMenu, onDismissRequest = { themeMenu = false }) {
                            TerminalThemes.options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.name) },
                                    leadingIcon = { RadioButton(settings.terminalTheme == option.id, null) },
                                    onClick = {
                                        themeMenu = false
                                        update { it.copy(terminalTheme = option.id) }
                                    },
                                )
                            }
                        }
                    }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        // Saved on release. Saving every step rewrote the settings file dozens of
                        // times a second, and the thumb trailed the finger while it waited.
                        var size by remember(settings.fontSizeSp) { mutableFloatStateOf(settings.fontSizeSp) }
                        Text("Font size: ${"%.1f".format(size)}", style = MaterialTheme.typography.bodyLarge)
                        Slider(
                            size,
                            onValueChange = { size = it },
                            onValueChangeFinished = { update { it.copy(fontSizeSp = size) } },
                            valueRange = TerminalView.MIN_FONT..TerminalView.MAX_FONT,
                        )
                        Text("For every pane. Pinching a pane sizes that pane alone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row2("Font", if (settings.customFontPath == null) "JetBrains Mono Nerd Font" else "Imported font") {
                        Row {
                            if (settings.customFontPath != null) {
                                TextButton(onClick = {
                                    settings.customFontPath?.let { File(it).delete() }
                                    update { it.copy(customFontPath = null) }
                                }) { Text("Reset") }
                            }
                            TextButton(onClick = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/octet-stream")) }) { Text("Import") }
                        }
                    }
                    SliderRow("Line height", settings.lineHeight, 1f..1.6f, steps = 11, format = { "%.2f".format(it) }) { v ->
                        update { it.copy(lineHeight = v) }
                    }
                    SliderRow("Letter spacing", settings.letterSpacing, -1f..3f, steps = 7, format = { "%.1f".format(it) }) { v ->
                        update { it.copy(letterSpacing = v) }
                    }
                    SwitchRow("Ligatures", "Join pairs such as => and != into one symbol", settings.ligatures) { on ->
                        update { it.copy(ligatures = on) }
                    }
                    SwitchRow("Readable text colors", "Lighten or darken text that is too close to its background", settings.improveContrast) { on ->
                        update { it.copy(improveContrast = on) }
                    }
                    SwitchRow("Keep the screen on", "While an agent in a pane on screen is working", settings.keepScreenOn) { on ->
                        update { it.copy(keepScreenOn = on) }
                    }
                }
            }
            item {
                Group("Cursor and typing") {
                    Text(
                        "Cursor shape",
                        Modifier.padding(start = 16.dp, top = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Segments(
                        listOf("program" to "Auto", "block" to "Block", "bar" to "Bar", "underline" to "Line"),
                        settings.cursorStyle,
                    ) { style -> update { it.copy(cursorStyle = style) } }
                    SliderRow("Bar and line thickness", settings.cursorWidth, 1f..4f, steps = 5, format = { "%.1f".format(it) }) { v ->
                        update { it.copy(cursorWidth = v) }
                    }
                    SwitchRow("Blinking cursor", "Blink when the program asks for a blinking cursor", settings.cursorBlink) { on ->
                        update { it.copy(cursorBlink = on) }
                    }
                    SwitchRow("Tap to move the cursor", "On the line you are typing, while the keyboard is open", settings.tapMovesCursor) { on ->
                        update { it.copy(tapMovesCursor = on) }
                    }
                    SwitchRow("Ask before pasting several lines", "When the program would run each line as a command", settings.pasteWarning) { on ->
                        update { it.copy(pasteWarning = on) }
                    }
                    Row2("Key bar", "Reorder keys, add your own and arrange pages", onClick = onEditKeyBar)
                    Row2("Editor command", "Opens a file from its preview, in a new pane on the host: ${settings.editorCommand}", onClick = { editingEditor = true })
                }
            }
            item {
                Group("Security") {
                    SwitchRow("Unlock with fingerprint or face", "Asks when you open Compy, and again after a minute away", settings.biometricLock) { v -> update { it.copy(biometricLock = v) } }
                    SwitchRow("Hide from screenshots", "Blocks screenshots and hides Compy in recent apps", settings.blockScreenshots) { v -> update { it.copy(blockScreenshots = v) } }
                }
            }
            item {
                Group("About") {
                    Row2("Compy ${AppState.BuildConfigVersion}", "An unofficial Android client for Rex servers. Not affiliated with Rex's makers.")
                    Row2("Copy debug details", "For GitHub issues. Compy sends nothing by itself.", onClick = {
                        val servers = app.connections.value.values.joinToString("\n") {
                            "server: rex ${it.serverVersion.value} state ${it.state.value}"
                        }
                        val details = "Compy ${AppState.BuildConfigVersion}\nAndroid ${android.os.Build.VERSION.RELEASE} ${android.os.Build.MODEL}\n$servers"
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Compy", details))
                    })
                    Row2("Open source licenses", onClick = { showLicenses = true })
                }
            }
        }
    }
    if (editingEditor) {
        RenameDialog("Editor command", settings.editorCommand, onDismiss = { editingEditor = false }) { command ->
            editingEditor = false
            update { it.copy(editorCommand = command) }
        }
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open source licenses") },
            text = {
                Text(
                    "Compy: Apache-2.0\nlibghostty-vt: MIT\nJetBrains Mono, Nerd Fonts and Noto Sans symbols: SIL Open Font License 1.1\n" +
                        "AndroidX and Compose: Apache-2.0\nKotlin and kotlinx: Apache-2.0\nOkHttp: Apache-2.0\nZXing: Apache-2.0\n\n" +
                        "Program icons: Simple Icons and Nerd Fonts, mostly CC0-1.0, with the rest listed in assets/icons/ATTRIBUTION.md. " +
                        "Names and logos are trademarks of their owners and don't imply endorsement of Compy.",
                )
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
}
