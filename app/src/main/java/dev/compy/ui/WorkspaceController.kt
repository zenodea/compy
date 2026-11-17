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
import dev.compy.rex.neighbor
import dev.compy.rex.hostSetupCommand
import dev.compy.rex.shellSetupCommand
import dev.compy.term.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun view(blockId: String): TerminalView? = views[blockId]

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

    /** A paste of several lines that is waiting for the person to confirm it. */
    data class PendingPaste(val blockId: String, val text: String, val lines: Int)

    var pendingPaste by mutableStateOf<PendingPaste?>(null)

    /**
     * Pastes from the clipboard. Several lines going to a program that doesn't take a paste as one
     * piece, such as a plain shell, would each run as a command, so those are confirmed first.
     */
    fun pasteChecked(text: String, blockId: String? = target()) {
        val block = blockId ?: return
        val lines = text.trimEnd('\n', '\r').count { it == '\n' } + 1
        val whole = views[block]?.bracketedPaste == true
        if (lines > 1 && !whole && app.settings.value.pasteWarning) {
            pendingPaste = PendingPaste(block, text, lines)
        } else {
            connection.paste(sessionId, block, text)
        }
    }

    fun confirmPaste() {
        val paste = pendingPaste ?: return
        pendingPaste = null
        connection.paste(sessionId, paste.blockId, paste.text)
    }

    /**
     * Pastes whatever the clipboard holds into a pane. Text goes in as text. A picture or a file,
     * such as a screenshot just copied, is sent to the pane's machine instead, and Rex types the
     * path it arrives at, which is how a coding agent is handed an attachment.
     */
    fun pasteClipboard(blockId: String? = target()) {
        val block = blockId ?: return
        val clip = runCatching { context.getSystemService(ClipboardManager::class.java).primaryClip }.getOrNull()
        // Only what is a file on the phone. A copied web address can arrive as a link too, and is text.
        val uris = clip?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
            .filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isEmpty()) {
            clipboardText()?.takeIf { it.isNotEmpty() }?.let { pasteChecked(it, block) }
            return
        }
        connection.act({ showMessage(it) }) {
            val files = dev.compy.system.uploadFiles(context, uris)
            showMessage("Uploading ${dev.compy.system.uploadLabel(files)}…")
            connection.upload(sessionId, block, files)
            showMessage("Uploaded ${dev.compy.system.uploadLabel(files)}")
        }
    }

    /** Changes a pane's shell to a folder, or types a path into it, quoted so it stays one word. */
    fun usePath(blockId: String, path: String, insert: Boolean) {
        val quoted = dev.compy.rex.shellQuoted(path)
        connection.writeText(sessionId, blockId, if (insert) "$quoted " else "cd $quoted\r") { showMessage(it) }
        if (!insert) {
            app.updateSettings { s -> s.copy(recentFolders = (listOf(path) + (s.recentFolders - path)).take(dev.compy.data.MAX_RECENT_FOLDERS)) }
        }
    }

    /**
     * Whether this session shows one pane at a time, filling the screen, with the others a swipe
     * away. Only the phone's view changes: the layout on the server stays as it is, so other
     * devices still see every pane. Kept with the settings, and here so a tap answers at once.
     */
    var zoomed by mutableStateOf(sessionId in app.settings.value.zoomedSessions)
        private set

    fun zoom(on: Boolean) {
        zoomed = on
        app.updateSettings { it.copy(zoomedSessions = if (on) (it.zoomedSessions - sessionId) + sessionId else it.zoomedSessions - sessionId) }
    }

    /** The pane that fills the screen while this session is zoomed, or null when every pane shows. */
    fun zoomedPane(window: dev.compy.rex.WindowView): String? {
        if (!zoomed || window.shownBlocks.size < 2) return null
        return focusedBlockId?.takeIf { id -> window.shownBlocks.any { it.id == id } }
            ?: window.focusedBlockId?.takeIf { id -> window.shownBlocks.any { it.id == id } }
            ?: window.shownBlocks.first().id
    }

    /**
     * A window as this screen shows it: with the phone's own zoomed pane standing where Rex's
     * would, so everything that draws a zoomed window draws this one the same way.
     */
    fun shown(window: dev.compy.rex.WindowView): dev.compy.rex.WindowView =
        if (window.zoomedBlockId != null) window else window.copy(zoomedBlockId = zoomedPane(window))

    /** Shows every pane again, or only the one with the keyboard. A zoom made on another device is undone as well. */
    fun toggleZoom(window: dev.compy.rex.WindowView?) {
        val elsewhere = window?.zoomedBlockId
        if (elsewhere != null) {
            connection.zoomBlock(sessionId, elsewhere, false)
            zoom(false)
        } else {
            zoom(!zoomed)
        }
    }

    fun focus(blockId: String) {
        focusedBlockId = blockId
        connection.focusBlock(sessionId, blockId)
        connection.clearAttention(blockId)
        app.notifier.cancel(blockId)
    }

    /** Goes to the pane on a side of the one with the keyboard. False when there is none there. */
    fun goToward(window: dev.compy.rex.WindowView, side: dev.compy.rex.Side): Boolean {
        val from = focusedBlockId ?: return false
        focus(window.neighbor(from, side) ?: return false)
        return true
    }

    /** Something shown over the session that asks one thing of the person. */
    sealed interface Sheet {
        /** Every session, window and pane, to go to one by name. */
        data object GoTo : Sheet
        /** The folders on a pane's machine, to change to one or to type a path. */
        data class Browse(val blockId: String, val insert: Boolean) : Sheet
        /** The session's other windows, to move a pane into one. */
        data class MoveToWindow(val blockId: String) : Sheet
        data class RenamePane(val blockId: String) : Sheet
        data object RenameWindow : Sheet
        data object RenameSession : Sheet
        data object EndSession : Sheet
    }

    var sheet by mutableStateOf<Sheet?>(null)

    /** Puts a pane beside the first pane in another window's layout. */
    fun moveToWindow(blockId: String, window: dev.compy.rex.WindowView) {
        val anchor = window.tiledLayer?.blocks?.firstOrNull { it.id != blockId }?.id
        if (anchor == null) {
            showMessage("That window has no pane in its layout to move beside")
            return
        }
        connection.moveBlock(sessionId, blockId, anchor, horizontal = true, before = false) { showMessage(it) }
    }

    /** Gives a pane a larger or smaller text size of its own, as a pinch does. */
    fun stepFontSize(blockId: String, step: Int) {
        app.updateSettings { s ->
            val size = ((s.paneFontSizes[blockId] ?: s.fontSizeSp) + step).coerceIn(6f, 32f)
            val sizes = (s.paneFontSizes - blockId) + (blockId to size)
            s.copy(paneFontSizes = if (sizes.size > MAX_PANE_SIZES) sizes.entries.drop(sizes.size - MAX_PANE_SIZES).associate { it.toPair() } else sizes)
        }
    }

    /** A file on a pane's machine that the person asked to see. */
    data class Peek(val blockId: String, val path: String, val line: Int)

    var peek by mutableStateOf<Peek?>(null)

    /** Steps the find bar to an older or newer match, when a key bound to that is pressed. */
    val findSteps = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    fun clipboardText(): String? {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    /**
     * Keeps a pane on the phone to read offline: an agent's conversation when it can be read,
     * which opens as a chat, and otherwise the pane's screen and history, which opens as a terminal.
     */
    fun savePane(blockId: String, onSaved: () -> Unit = {}) {
        val status = connection.statuses.value[blockId]
        val process = connection.programs.value[blockId]
        val title = BoardRules.title(status, process, connection.titles.value[blockId])
        val session = connection.sessions.value.firstOrNull { it.id == sessionId }?.label
        val where = listOfNotNull(connection.label.value.ifEmpty { null }, session?.ifEmpty { null }, connection.folderPaths.value[blockId]).joinToString(" · ")
        val agent = ProgramNames.candidates(status?.app, process).any { it in CHAT_AGENTS }
        app.scope.launch(Dispatchers.Main) {
            var saved: dev.compy.data.SavedPane? = null
            var tooLarge = false
            if (agent) {
                val chat = runCatching { feed(blockId).readAll() }.getOrNull()
                if (chat != null && chat.status == TranscriptFeed.Status.Live && chat.entries.isNotEmpty()) {
                    saved = withContext(Dispatchers.IO) {
                        app.saved.save(title, where, dev.compy.data.SavedPane.CHAT, dev.compy.rex.encodeChat(dev.compy.rex.newestWithin(chat.entries, MAX_SAVED_CHAT)))
                    }
                    tooLarge = saved == null
                }
            }
            if (saved == null && !tooLarge) {
                // The engine can't be copied in the middle of an escape sequence, which passes in a moment.
                var bytes: ByteArray? = null
                repeat(4) {
                    if (bytes == null) {
                        bytes = withContext(Dispatchers.Default) { views[blockId]?.snapshot() }
                        if (bytes == null) delay(150)
                    }
                }
                val snapshot = bytes
                if (snapshot == null) {
                    showMessage("This pane couldn't be saved just now. Try again in a moment.")
                    return@launch
                }
                saved = withContext(Dispatchers.IO) { app.saved.save(title, where, dev.compy.data.SavedPane.TERMINAL, snapshot) }
                tooLarge = saved == null
            }
            if (saved != null) onSaved()
            showMessage(
                when {
                    saved == null -> "This pane is too large to keep on the phone"
                    saved.kind == dev.compy.data.SavedPane.CHAT -> "Saved the conversation. Hold the save button to open your saved panes."
                    else -> "Saved the terminal. Hold the save button to open your saved panes."
                },
            )
        }
    }

    /** Gives a pane back the text size from Settings. */
    fun resetFontSize(blockId: String) {
        app.updateSettings { it.copy(paneFontSizes = it.paneFontSizes - blockId) }
    }

    private fun startChooser(send: Intent) {
        runCatching {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { showMessage("No app can take that") }
    }

    /** Hands text to another app through Android's share sheet. */
    fun share(text: String) {
        startChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    }

    /** Shares a picture of a pane as it looks now. */
    fun sharePicture(blockId: String) {
        if (app.settings.value.blockScreenshots) {
            showMessage("Hide from screenshots is on in Settings, so panes can't be shared as pictures")
            return
        }
        val picture = views[blockId]?.picture()
        if (picture == null) {
            showMessage("This pane has nothing to show yet")
            return
        }
        app.scope.launch(Dispatchers.IO) {
            val uri = runCatching {
                val folder = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                // One file, written over each time, so shared pictures don't pile up.
                val file = java.io.File(folder, "pane.png")
                file.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }.getOrNull()
            picture.recycle()
            launch(Dispatchers.Main) {
                if (uri == null) {
                    showMessage("The picture couldn't be saved")
                } else {
                    startChooser(
                        Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                            .apply { clipData = ClipData.newRawUri(null, uri) }
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
            }
        }
    }

    /** Copies the command that makes a shell mark its prompts, for prompt jumps and copying output. */
    fun copyShellSetup() {
        copy(shellSetupCommand(context.assets.open("prompt-marks.sh").use { it.readBytes() }))
    }

    /** Copies the shell command that sets this server's computer up for agent chat. */
    fun copyHostSetup() {
        copy(hostSetupCommand(context.assets.open("compy.lua").use { it.readBytes() }))
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
            "paste" -> pasteClipboard()
            "prompt" -> focusedView()?.jumpPrompt(def.key.toIntOrNull() ?: return)
        }
    }

    /** Set by the screen so views and commands can show a short message. */
    var showMessage: (String) -> Unit = {}

    fun host(blockId: String, onTap: () -> Unit) = object : TerminalView.Host {
        override fun showMessage(text: String) = this@WorkspaceController.showMessage(text)

        override fun sendKey(key: RexKey) = connection.sendKey(sessionId, blockId, key)
        override fun sendText(text: String) = this@WorkspaceController.sendText(text, blockId)
        override fun paste(text: String) = pasteChecked(text, blockId)
        override fun pasteClipboard() = this@WorkspaceController.pasteClipboard(blockId)

        override fun openPath(path: String, line: Int) {
            peek = Peek(blockId, path, line)
        }

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

        // A pinch sizes this pane alone. The newest hundred are kept, since panes come and go.
        // A size measured while the chat's keyboard was sliding in must not follow it to the server.
        override fun onGridHeld() {
            sizeJobs.remove(blockId)?.cancel()
            fitted[blockId]?.let { grids[blockId] = it }
        }

        override fun onFontSizeChanged(sizeSp: Float) {
            app.updateSettings { s ->
                val sizes = (s.paneFontSizes - blockId) + (blockId to sizeSp)
                s.copy(paneFontSizes = if (sizes.size > MAX_PANE_SIZES) sizes.entries.drop(sizes.size - MAX_PANE_SIZES).associate { it.toPair() } else sizes)
            }
        }

        override fun shareText(text: String) = share(text)

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
        private const val MAX_PANE_SIZES = 100
        /** The most text of a conversation that is kept, about 12 MB before it is compressed. */
        private const val MAX_SAVED_CHAT = 12_000_000
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
