package dev.compy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.app.NotificationManagerCompat
import dev.compy.AppState
import dev.compy.system.batteryExemptionIntent
import dev.compy.system.isIgnoringBatteryOptimizations
import dev.compy.system.notificationSettingsIntent
import dev.compy.data.DefaultKeyBar
import dev.compy.data.KeyDef
import dev.compy.data.Settings
import dev.compy.term.TerminalView
import java.io.File

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

@Composable
private fun SettingsList(app: AppState, onBack: () -> Unit, onEditKeyBar: () -> Unit) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var themeMenu by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val update: ((Settings) -> Settings) -> Unit = { app.updateSettings(it) }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val file = File(context.filesDir, "terminal-font")
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            }.onSuccess {
                // Each import needs a fresh path so the change is seen even with the same name.
                val stamped = File(context.filesDir, "terminal-font-${System.currentTimeMillis()}.ttf")
                file.renameTo(stamped)
                val old = settings.customFontPath
                update { it.copy(customFontPath = stamped.path) }
                old?.let { path -> File(path).delete() }
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
                }
            }
            item {
                Group("Notifications") {
                    val notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
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
                    if (settings.backgroundAlerts && !isIgnoringBatteryOptimizations(context)) {
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
                        Text("Font size: ${"%.1f".format(settings.fontSizeSp)}", style = MaterialTheme.typography.bodyLarge)
                        Slider(
                            settings.fontSizeSp,
                            onValueChange = { v -> update { it.copy(fontSizeSp = v) } },
                            valueRange = TerminalView.MIN_FONT..TerminalView.MAX_FONT,
                        )
                        Text("You can also pinch a terminal.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Row2("Key bar", "Reorder keys, add your own and arrange pages", onClick = onEditKeyBar)
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
