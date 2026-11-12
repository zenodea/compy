package dev.compy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
    /** Press Enter after typing `text`, so a key can run a whole command. */
    val thenEnter: Boolean = false,
)

/** A host's colors, saved so the app looks right before it reconnects. */
@Serializable
data class SavedTheme(val foreground: Int, val background: Int, val cursor: Int, val ansi: List<Int>, val scheme: String? = null)

@Serializable
data class Settings(
    /** "host" colors the app from the Rex host's theme; "wallpaper" uses Material You. */
    val appColors: String = "host",
    val hostThemes: Map<String, SavedTheme> = emptyMap(),
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
    val amoled: Boolean = false,
    /** "relaxed" draws rounded panes with gaps; "tight" packs them edge to edge like tmux. Only Compy sees this. */
    val paneLayout: String = "relaxed",
    val terminalTheme: String = "rex",
    val fontSizeSp: Float = 13f,
    /** Text sizes set by pinching a pane, by block. A pane without one uses [fontSizeSp]. */
    val paneFontSizes: Map<String, Float> = emptyMap(),
    val customFontPath: String? = null,
    /** Moves text colors that are too close to their background until they can be read. */
    val improveContrast: Boolean = true,
    /** Row height as a multiple of the font's own. */
    val lineHeight: Float = 1f,
    /** Extra room between letters, in dp. */
    val letterSpacing: Float = 0f,
    /** `program` follows what runs in the terminal; otherwise `block`, `bar` or `underline`. */
    val cursorStyle: String = "program",
    val cursorBlink: Boolean = true,
    /** Thickness of a bar or underline cursor, in dp. */
    val cursorWidth: Float = 2f,
    val ligatures: Boolean = false,
    /** A tap on the line being typed moves the cursor there. */
    val tapMovesCursor: Boolean = true,
    /** Ask before pasting several lines into a program that would run each as it arrives. */
    val pasteWarning: Boolean = true,
    /** Keep the screen on while an agent in a pane on screen is working. */
    val keepScreenOn: Boolean = false,
    /** Messages sent from the composer, newest first, to send again. */
    val composerHistory: List<String> = emptyList(),
    /** Messages saved in the composer to use again. */
    val snippets: List<String> = emptyList(),
    val biometricLock: Boolean = true,
    val blockScreenshots: Boolean = false,
    val keyBarPages: List<List<KeyDef>> = DefaultKeyBar.pages,
    val sizeModes: Map<String, SizeMode> = emptyMap(),
    val collapsedSessions: Set<String> = emptySet(),
    val activityMode: Boolean = false,
    val composerSendsEnter: Boolean = true,
    /** Stay connected in the background and notify when an agent needs attention. */
    val backgroundAlerts: Boolean = true,
    val askedNotificationPermission: Boolean = false,
    val askedBatteryExemption: Boolean = false,
    val lastServerId: String? = null,
    val lastSessionId: String? = null,
)

object DefaultKeyBar {
    val catalog: List<KeyDef> = listOf(
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
        KeyDef("prompt", "Previous prompt", icon = "prompt-up", key = "-1"),
        KeyDef("prompt", "Next prompt", icon = "prompt-down", key = "1"),
    )

    /** A plain name and group for a key, shown in the editor. */
    data class Info(val name: String, val group: String)

    fun info(def: KeyDef): Info = when (def.label) {
        "esc" -> Info("Escape", "Essentials")
        "tab" -> Info("Tab", "Essentials")
        "⏎" -> Info("Enter", "Essentials")
        "⌫" -> Info("Backspace", "Essentials")
        "del" -> Info("Delete", "Essentials")
        "Paste" -> Info("Paste", "Essentials")
        "Compose" -> Info("Compose", "Essentials")
        "ctrl" -> Info("Control (latches)", "Modifiers")
        "opt" -> Info("Option (latches)", "Modifiers")
        "shift" -> Info("Shift (latches)", "Modifiers")
        "←" -> Info("Left", "Movement")
        "↓" -> Info("Down", "Movement")
        "↑" -> Info("Up", "Movement")
        "→" -> Info("Right", "Movement")
        "home" -> Info("Home", "Movement")
        "end" -> Info("End", "Movement")
        "pgup" -> Info("Page up", "Movement")
        "pgdn" -> Info("Page down", "Movement")
        "Previous prompt" -> Info("Previous prompt", "Movement")
        "Next prompt" -> Info("Next prompt", "Movement")
        "^C" -> Info("Control-C", "Shortcuts")
        "^D" -> Info("Control-D", "Shortcuts")
        "^Z" -> Info("Control-Z", "Shortcuts")
        "⇧⇥" -> Info("Shift-Tab", "Shortcuts")
        "/", "|", "-", "~" -> Info("Types ${def.label}", "Symbols")
        else -> when (def.kind) {
            "text" -> Info("Types ${def.text.take(24)}" + if (def.thenEnter) ", then Enter" else "", "Custom")
            else -> Info(listOf(def.mods, def.key.removePrefix("key_").removePrefix("digit_")).filter { it.isNotEmpty() }.joinToString("+"), "Custom")
        }
    }

    val groups = listOf("Essentials", "Modifiers", "Movement", "Shortcuts", "Symbols")

    private fun pick(vararg labels: String) = labels.map { l -> catalog.first { it.label == l } }

    val pages: List<List<KeyDef>> = listOf(
        pick("esc", "ctrl", "opt", "tab", "←", "↓", "↑", "→"),
        pick("Paste", "^C", "^D", "⇧⇥", "/", "|", "-", "~"),
        pick("home", "end", "pgup", "pgdn", "del", "shift", "Previous prompt", "Next prompt"),
    )
}

private val Context.store: DataStore<Preferences> by preferencesDataStore("compy")

/** Saves servers and settings as JSON in one small preferences file. */
class SettingsStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // A value this build doesn't know, such as one a newer build saved, becomes that
        // setting's default. Without this it made everything unreadable.
        coerceInputValues = true
    }
    private val serversKey = stringPreferencesKey("servers")
    private val settingsKey = stringPreferencesKey("settings")

    val servers: Flow<List<SavedServer>> = context.store.data.map { prefs -> decode<List<SavedServer>>(prefs[serversKey]).orEmpty() }

    val settings: Flow<Settings> = context.store.data.map { prefs -> decode<Settings>(prefs[settingsKey]) ?: Settings() }

    suspend fun updateServers(change: (List<SavedServer>) -> List<SavedServer>) {
        context.store.edit { prefs -> prefs.change(serversKey, emptyList(), change) }
    }

    suspend fun updateSettings(change: (Settings) -> Settings) {
        context.store.edit { prefs -> prefs.change(settingsKey, Settings(), change) }
    }

    private inline fun <reified T> decode(text: String?): T? = text?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    /**
     * Saves a change to what's stored under [key]. What's stored but can't be read is about to be
     * replaced by [fallback], which would lose every server or setting for good, so it's kept
     * under a second key first, where it can be recovered from.
     */
    private inline fun <reified T> MutablePreferences.change(key: Preferences.Key<String>, fallback: T, change: (T) -> T) {
        val stored = this[key]
        val current = decode<T>(stored)
        if (stored != null && current == null) this[stringPreferencesKey("${key.name}-unreadable")] = stored
        this[key] = json.encodeToString(change(current ?: fallback))
    }
}
