package dev.compy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import dev.compy.AppState
import dev.compy.data.KeyDef
import dev.compy.data.SizeMode
import dev.compy.rex.RexKey
import dev.compy.rex.ServerConnection
import dev.compy.rex.TranscriptFeed
import dev.compy.term.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Ties one on-screen session to its server: input routing, pane sizes and shared key state. */
class WorkspaceController(
    val app: AppState,
    val connection: ServerConnection,
    val sessionId: String,
    private val context: Context,
) {
    private data class Grid(val cols: Int, val rows: Int, val cellW: Int, val cellH: Int)

    private val views = HashMap<String, TerminalView>()
    private val grids = HashMap<String, Grid>()
    private val sizeJobs = HashMap<String, Job>()
    private val fitted = HashMap<String, Grid>()
    private var sizesHeld = false

    var focusedBlockId by mutableStateOf<String?>(null)

    /** Agent panes showing their conversation as a chat instead of the terminal. */
    val chatShown = mutableStateMapOf<String, Boolean>()
    private val feeds = HashMap<String, TranscriptFeed>()

    /** The conversation of the agent in a pane, kept while the session is open so switching back is instant. */
    fun feed(blockId: String): TranscriptFeed = feeds.getOrPut(blockId) { TranscriptFeed(connection, sessionId, blockId) }

    /** Forgets the conversations of panes that have closed. Each holds its entries and up to 16 MB of images. */
    fun keepFeeds(open: Set<String>) {
        feeds.keys.retainAll(open)
    }

    /** Modifiers latched on the key bar, as RexKey bit flags. */
    var stickyMods by mutableIntStateOf(0)
        private set
    private var lockedMods = 0

    val sizeMode: SizeMode get() = app.settings.value.sizeModes[sessionId] ?: SizeMode.TakeOver

    fun register(blockId: String, view: TerminalView) {
        views[blockId] = view
    }

    /**
     * A block can briefly have two views, such as while a floating pane zooms into the grid, and
     * the old one leaves after the new one arrived. Only the view still registered may take the
     * block's state with it, or typing would go to whichever other pane came first.
     */
    fun unregister(blockId: String, view: TerminalView) {
        if (views[blockId] !== view) return
        views.remove(blockId)
        grids.remove(blockId)
        fitted.remove(blockId)
        sizeJobs.remove(blockId)?.cancel()
    }

    fun focusedView(): TerminalView? = focusedBlockId?.let { views[it] } ?: views.values.firstOrNull()

    fun toggleSticky(mod: Int) {
        stickyMods = when {
            lockedMods and mod != 0 -> {
                lockedMods = lockedMods and mod.inv()
                stickyMods and mod.inv()
            }
            stickyMods and mod != 0 -> {
                // A second tap keeps the modifier on until it's tapped again.
                lockedMods = lockedMods or mod
                stickyMods
            }
            else -> stickyMods or mod
        }
    }

    fun isLocked(mod: Int) = lockedMods and mod != 0

    fun consumeSticky(): Int {
        val mods = stickyMods
        stickyMods = lockedMods
        return mods
    }

    private fun target(): String? = focusedBlockId ?: views.keys.firstOrNull()

    fun sendKey(key: RexKey, blockId: String? = target()) {
        connection.sendKey(sessionId, blockId ?: return, key)
    }

    /** Sends typed text. Long text and multi-line text go as a paste, so it arrives in one piece. */
    fun sendText(text: String, blockId: String? = target()) {
        val block = blockId ?: return
        connection.clearAttention(block)
        if (text.length > MAX_TYPED || (text.contains('\n') && text != "\n")) {
            connection.paste(sessionId, block, text)
            return
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val sticky = consumeSticky()
            val key = when (cp) {
                '\n'.code, '\r'.code -> if (sticky == 0) RexKey.ENTER else RexKey("enter", RexKey.modsString(sticky))
                '\t'.code -> if (sticky == 0) RexKey.TAB else RexKey("tab", RexKey.modsString(sticky))
                else -> RexKey.forChar(cp, sticky)
            }
            connection.sendKey(sessionId, block, key)
        }
    }

    fun paste(text: String, blockId: String? = target()) {
        connection.paste(sessionId, blockId ?: return, text)
    }

    fun clipboardText(): String? {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    fun press(def: KeyDef) {
        when (def.kind) {
            "text" -> {
                sendText(def.text)
                if (def.thenEnter) sendKey(RexKey.ENTER)
            }
            "key" -> {
                val sticky = consumeSticky()
                val mods = buildList {
                    if (def.mods.isNotEmpty()) add(def.mods)
                    if (sticky != 0) add(RexKey.modsString(sticky))
                }.joinToString("+")
                val character = def.key.takeIf { it.startsWith("key_") || it.startsWith("digit_") }?.substringAfter('_')
                sendKey(RexKey(def.key, mods, unshifted = character?.firstOrNull()?.code ?: 0))
            }
            "paste" -> clipboardText()?.let { paste(it) }
            "prompt" -> focusedView()?.jumpPrompt(def.key.toIntOrNull() ?: return)
        }
    }

    /** Set by the screen so views and commands can show a short message. */
    var showMessage: (String) -> Unit = {}

    fun host(blockId: String, onTap: () -> Unit) = object : TerminalView.Host {
        override fun showMessage(text: String) = this@WorkspaceController.showMessage(text)

        override fun sendKey(key: RexKey) = connection.sendKey(sessionId, blockId, key)
        override fun sendText(text: String) = this@WorkspaceController.sendText(text, blockId)
        override fun paste(text: String) = connection.paste(sessionId, blockId, text)

        override fun sendMouse(action: String, button: String?, col: Int, row: Int) {
            connection.sendMouse(sessionId, blockId, mousePayload(action, button, col, row))
        }

        override fun sendWheel(lines: Float, col: Int, row: Int) {
            connection.sendWheel(sessionId, blockId, wheelPayload(lines, col, row))
        }

        override fun consumeStickyMods() = consumeSticky()
        override fun onTapped() = onTap()

        override fun onGridMeasured(cols: Int, rows: Int, cellW: Int, cellH: Int) {
            grids[blockId] = Grid(cols, rows, cellW, cellH)
            applySize(blockId)
        }

        override fun onFontSizeChanged(sizeSp: Float) {
            app.updateSettings { it.copy(fontSizeSp = sizeSp) }
        }

        override fun copyText(text: String) = copy(text)
        override fun clipboardText() = this@WorkspaceController.clipboardText()

        override fun openUrl(url: String) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { this@WorkspaceController.showMessage("No app can open that link") }
        }
    }

    private fun applySize(blockId: String) {
        if (sizesHeld || sizeMode != SizeMode.TakeOver || !app.foreground.value) return
        val grid = grids[blockId] ?: return
        // An unchanged size would only make programs redraw for nothing.
        if (fitted[blockId] == grid) {
            sizeJobs.remove(blockId)?.cancel()
            return
        }
        // Layout changes and pinching report many sizes in a row; only the last one matters.
        sizeJobs[blockId]?.cancel()
        // On the main thread, like everything else that touches these maps.
        sizeJobs[blockId] = app.scope.launch(Dispatchers.Main) {
            delay(SIZE_DEBOUNCE_MS)
            fitted[blockId] = grid
            connection.fitBlock(sessionId, blockId, grid.cols, grid.rows, grid.cellW, grid.cellH)
        }
    }

    /**
     * Holds back terminal size changes while the surrounding layout animates. Programs redraw when
     * resized, so this sends one change after the motion ends instead of several during it.
     */
    fun holdSizes(held: Boolean) {
        if (sizesHeld == held) return
        sizesHeld = held
        if (held) {
            sizeJobs.values.forEach { it.cancel() }
        } else {
            grids.keys.toList().forEach(::applySize)
        }
    }

    /** Re-applies sizes after returning to the foreground or changing the session's mode. */
    fun reapplySizes() {
        fitted.clear()
        if (sizeMode == SizeMode.TakeOver) {
            grids.keys.toList().forEach(::applySize)
        } else {
            connection.releaseOwnership(sessionId, grids.keys.toList())
        }
    }

    fun setSizeMode(mode: SizeMode) {
        app.updateSettings { it.copy(sizeModes = it.sizeModes + (sessionId to mode)) }
        // The setting saves asynchronously, so act on the chosen mode directly.
        sizeJobs.values.forEach { it.cancel() }
        fitted.clear()
        if (mode == SizeMode.Keep) {
            connection.releaseOwnership(sessionId, grids.keys.toList())
        } else {
            grids.keys.toList().forEach { block ->
                grids[block]?.let { connection.fitBlock(sessionId, block, it.cols, it.rows, it.cellW, it.cellH) }
            }
        }
    }

    companion object {
        private const val MAX_TYPED = 64
        private const val SIZE_DEBOUNCE_MS = 200L

        fun mousePayload(action: String, button: String?, col: Int, row: Int): JsonObject = buildJsonObject {
            put("action", action)
            if (button != null) put("button", button)
            if (action == "press" && button != null) {
                put("buttons", buildJsonObject { put(button, true) })
            } else {
                put("buttons", JsonNull)
            }
            put("x", col)
            put("y", row)
            put("pixel", JsonNull)
            put("mods", "")
        }

        fun wheelPayload(lines: Float, col: Int, row: Int): JsonObject = buildJsonObject {
            put("delta_mode", "line")
            put("delta_x", 0)
            put("delta_y", lines.toDouble())
            put("x", col)
            put("y", row)
            put("pixel", JsonNull)
            put("mods", "")
        }
    }
}
