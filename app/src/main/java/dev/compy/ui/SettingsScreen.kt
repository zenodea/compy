package dev.compy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Surface
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
import dev.compy.AppState
import dev.compy.data.DefaultKeyBar
import dev.compy.data.KeyDef
import dev.compy.data.Settings
import dev.compy.term.TerminalView
import java.io.File

@Composable
fun SettingsScreen(app: AppState, onBack: () -> Unit) {
    var keyBarEditor by remember { mutableStateOf(false) }
    BackHandler { if (keyBarEditor) keyBarEditor = false else onBack() }
    Surface(Modifier.fillMaxSize()) {
        if (keyBarEditor) {
            KeyBarEditor(app, onBack = { keyBarEditor = false })
        } else {
            SettingsList(app, onBack, onEditKeyBar = { keyBarEditor = true })
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun SettingsList(app: AppState, onBack: () -> Unit, onEditKeyBar: () -> Unit) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var themeMenu by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }
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

    Column(Modifier.fillMaxSize()) {
        Header("Settings", onBack)
        LazyColumn(Modifier.navigationBarsPadding()) {
            item { Section("Appearance") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (id, name) ->
                            SegmentedButton(
                                selected = settings.themeMode == id,
                                onClick = { update { it.copy(themeMode = id) } },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text(name) }
                        }
                    }
                }
            }
            item { SwitchRow("Wallpaper colours", "Use Material You colours from your wallpaper", settings.dynamicColor) { v -> update { it.copy(dynamicColor = v) } } }
            item { SwitchRow("AMOLED black", "Pure black backgrounds in dark theme", settings.amoled) { v -> update { it.copy(amoled = v) } } }

            item { Section("Terminal") }
            item {
                ListItem(
                    headlineContent = { Text("Colour theme") },
                    supportingContent = { Text(TerminalThemes.options.firstOrNull { it.id == settings.terminalTheme }?.name.orEmpty()) },
                    modifier = Modifier.clickable { themeMenu = true },
                    trailingContent = {
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
                    },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Font size: ${"%.1f".format(settings.fontSizeSp)}", style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        settings.fontSizeSp,
                        onValueChange = { v -> update { it.copy(fontSizeSp = v) } },
                        valueRange = TerminalView.MIN_FONT..TerminalView.MAX_FONT,
                    )
                    Text("You can also pinch a terminal to change this.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Font") },
                    supportingContent = { Text(if (settings.customFontPath == null) "JetBrains Mono Nerd Font (built in)" else "Imported font") },
                    trailingContent = {
                        Row {
                            if (settings.customFontPath != null) {
                                TextButton(onClick = {
                                    settings.customFontPath?.let { File(it).delete() }
                                    update { it.copy(customFontPath = null) }
                                }) { Text("Reset") }
                            }
                            TextButton(onClick = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/octet-stream")) }) { Text("Import") }
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Key bar") },
                    supportingContent = { Text("Reorder keys, add your own, and arrange pages") },
                    modifier = Modifier.clickable(onClick = onEditKeyBar),
                )
            }

            item { Section("Security") }
            item { SwitchRow("Unlock with fingerprint or face", "Asks when you open Compy, and again after a minute away", settings.biometricLock) { v -> update { it.copy(biometricLock = v) } } }
            item { SwitchRow("Hide from screenshots", "Blocks screenshots and hides Compy in the recent apps screen", settings.blockScreenshots) { v -> update { it.copy(blockScreenshots = v) } } }

            item { Section("About") }
            item {
                ListItem(
                    headlineContent = { Text("Compy ${AppState.BuildConfigVersion}") },
                    supportingContent = { Text("An unofficial Android client for Rex servers. Not affiliated with Rex's makers.") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Copy debug details") },
                    supportingContent = { Text("For GitHub issues. Compy sends nothing by itself.") },
                    modifier = Modifier.clickable {
                        val servers = app.connections.value.values.joinToString("\n") {
                            "server: rex ${it.serverVersion.value} state ${it.state.value}"
                        }
                        val details = "Compy ${AppState.BuildConfigVersion}\nAndroid ${android.os.Build.VERSION.RELEASE} ${android.os.Build.MODEL}\n$servers"
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Compy", details))
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Open source licences") },
                    modifier = Modifier.clickable { showLicences = true },
                )
            }
        }
    }
    if (showLicences) {
        AlertDialog(
            onDismissRequest = { showLicences = false },
            title = { Text("Open source licences") },
            text = {
                Text(
                    "Compy: Apache-2.0\nlibghostty-vt: MIT\nJetBrains Mono and Nerd Fonts: SIL Open Font License 1.1\n" +
                        "AndroidX and Compose: Apache-2.0\nKotlin and kotlinx: Apache-2.0\nOkHttp: Apache-2.0\nZXing: Apache-2.0",
                )
            },
            confirmButton = { TextButton(onClick = { showLicences = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun KeyBarEditor(app: AppState, onBack: () -> Unit) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val pages = settings.keyBarPages
    var addingTo by remember { mutableStateOf<Int?>(null) }
    var customFor by remember { mutableStateOf<Int?>(null) }

    fun setPages(change: (List<List<KeyDef>>) -> List<List<KeyDef>>) {
        app.updateSettings { it.copy(keyBarPages = change(it.keyBarPages)) }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Key bar", onBack)
        LazyColumn(Modifier.navigationBarsPadding()) {
            pages.forEachIndexed { pageIndex, page ->
                item(key = "header$pageIndex") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Page ${pageIndex + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { addingTo = pageIndex }) { Text("Add key") }
                        TextButton(onClick = { customFor = pageIndex }) { Text("Custom") }
                        if (pages.size > 1) {
                            TextButton(onClick = { setPages { it.filterIndexed { i, _ -> i != pageIndex } } }) { Text("Remove") }
                        }
                    }
                }
                itemsIndexed(page, key = { i, def -> "p$pageIndex-$i-${def.label}" }) { i, def ->
                    ListItem(
                        headlineContent = { Text(def.label) },
                        trailingContent = {
                            Row {
                                IconButton(enabled = i > 0, onClick = {
                                    setPages { all -> all.mapIndexed { p, keys -> if (p == pageIndex) keys.swapped(i, i - 1) else keys } }
                                }) { Icon(Icons.Filled.ArrowUpward, "Move earlier") }
                                IconButton(enabled = i < page.lastIndex, onClick = {
                                    setPages { all -> all.mapIndexed { p, keys -> if (p == pageIndex) keys.swapped(i, i + 1) else keys } }
                                }) { Icon(Icons.Filled.ArrowDownward, "Move later") }
                                IconButton(onClick = {
                                    setPages { all -> all.mapIndexed { p, keys -> if (p == pageIndex) keys.filterIndexed { k, _ -> k != i } else keys } }
                                }) { Icon(Icons.Filled.Close, "Remove") }
                            }
                        },
                    )
                }
            }
            item(key = "footer") {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { setPages { it + listOf(emptyList()) } }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Text("Add page", Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = { setPages { DefaultKeyBar.pages } }) { Text("Reset to default") }
                }
            }
        }
    }
    addingTo?.let { page ->
        AlertDialog(
            onDismissRequest = { addingTo = null },
            title = { Text("Add a key") },
            text = {
                LazyColumn {
                    itemsIndexed(DefaultKeyBar.catalog) { _, def ->
                        ListItem(
                            headlineContent = { Text(def.label) },
                            modifier = Modifier.clickable {
                                setPages { all -> all.mapIndexed { p, keys -> if (p == page) keys + def else keys } }
                                addingTo = null
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addingTo = null }) { Text("Cancel") } },
        )
    }
    customFor?.let { page ->
        var label by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { customFor = null },
            title = { Text("Custom key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
                    OutlinedTextField(text, { text = it }, label = { Text("Text it sends") })
                }
            },
            confirmButton = {
                TextButton(enabled = label.isNotBlank() && text.isNotEmpty(), onClick = {
                    setPages { all -> all.mapIndexed { p, keys -> if (p == page) keys + KeyDef("text", label.trim(), text = text) else keys } }
                    customFor = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { customFor = null }) { Text("Cancel") } },
        )
    }
}

private fun <T> List<T>.swapped(a: Int, b: Int): List<T> = toMutableList().also {
    val t = it[a]
    it[a] = it[b]
    it[b] = t
}
