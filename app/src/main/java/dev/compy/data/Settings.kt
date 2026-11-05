package dev.compy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a session's terminals get their size. */
enum class SizeMode {
    /** Compy takes the terminals over while in the foreground and fits them to this screen. */
    TakeOver,

    /** Terminals keep the size another device gave them, and Compy scales them to fit. */
    Keep,
}

@Serializable
data class SavedServer(val id: String, val label: String, val url: String)

@Serializable
data class KeyDef(
    /** One of `key`, `sticky`, `text`, `paste`, `composer`, `keyboard`. */
    val kind: String,
    val label: String,
    val icon: String? = null,
    /** Rex key name, or the modifier name for `sticky`. */
    val key: String = "",
    val mods: String = "",
    /** Literal text to send for `text`. */
    val text: String = "",
)

@Serializable
data class Settings(
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
    val amoled: Boolean = false,
    val terminalTheme: String = "rex",
    val fontSizeSp: Float = 13f,
    val customFontPath: String? = null,
    val biometricLock: Boolean = true,
    val blockScreenshots: Boolean = false,
    val keyBarPages: List<List<KeyDef>> = DefaultKeyBar.pages,
    val sizeModes: Map<String, SizeMode> = emptyMap(),
    val collapsedSessions: Set<String> = emptySet(),
    val activityMode: Boolean = false,
    val composerSendsEnter: Boolean = true,
)

object DefaultKeyBar {
    val catalog: List<KeyDef> = listOf(
        KeyDef("sticky", "⌘", icon = "command", key = "super"),
        KeyDef("key", "esc", key = "escape"),
        KeyDef("sticky", "ctrl", key = "ctrl"),
        KeyDef("sticky", "opt", key = "alt"),
        KeyDef("sticky", "shift", key = "shift"),
        KeyDef("key", "tab", key = "tab"),
        KeyDef("key", "←", icon = "left", key = "arrow_left"),
        KeyDef("key", "↓", icon = "down", key = "arrow_down"),
        KeyDef("key", "↑", icon = "up", key = "arrow_up"),
        KeyDef("key", "→", icon = "right", key = "arrow_right"),
        KeyDef("key", "home", key = "home"),
        KeyDef("key", "end", key = "end"),
        KeyDef("key", "pgup", key = "page_up"),
        KeyDef("key", "pgdn", key = "page_down"),
        KeyDef("key", "del", key = "delete"),
        KeyDef("key", "⌫", icon = "backspace", key = "backspace"),
        KeyDef("key", "⏎", icon = "enter", key = "enter"),
        KeyDef("paste", "Paste", icon = "paste"),
        KeyDef("composer", "Compose", icon = "composer"),
        KeyDef("text", "/", text = "/"),
        KeyDef("text", "|", text = "|"),
        KeyDef("text", "-", text = "-"),
        KeyDef("text", "~", text = "~"),
        KeyDef("key", "^C", key = "key_c", mods = "ctrl"),
        KeyDef("key", "^D", key = "key_d", mods = "ctrl"),
        KeyDef("key", "^Z", key = "key_z", mods = "ctrl"),
        KeyDef("key", "⇧⇥", key = "tab", mods = "shift"),
    )

    private fun pick(vararg labels: String) = labels.map { l -> catalog.first { it.label == l } }

    val pages: List<List<KeyDef>> = listOf(
        pick("esc", "ctrl", "opt", "tab", "←", "↓", "↑", "→", "Paste", "Compose"),
        pick("⌘", "^C", "^D", "⇧⇥", "/", "|", "-", "~", "home", "end", "pgup", "pgdn"),
    )
}

private val Context.store: DataStore<Preferences> by preferencesDataStore("compy")

/** Saves servers and settings as JSON in one small preferences file. */
class SettingsStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serversKey = stringPreferencesKey("servers")
    private val settingsKey = stringPreferencesKey("settings")

    val servers: Flow<List<SavedServer>> = context.store.data.map { prefs ->
        prefs[serversKey]?.let { runCatching { json.decodeFromString<List<SavedServer>>(it) }.getOrNull() }.orEmpty()
    }

    val settings: Flow<Settings> = context.store.data.map { prefs ->
        prefs[settingsKey]?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
    }

    suspend fun updateServers(change: (List<SavedServer>) -> List<SavedServer>) {
        context.store.edit { prefs ->
            val current = prefs[serversKey]?.let { runCatching { json.decodeFromString<List<SavedServer>>(it) }.getOrNull() }.orEmpty()
            prefs[serversKey] = json.encodeToString(change(current))
        }
    }

    suspend fun updateSettings(change: (Settings) -> Settings) {
        context.store.edit { prefs ->
            val current = prefs[settingsKey]?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
            prefs[settingsKey] = json.encodeToString(change(current))
        }
    }
}
