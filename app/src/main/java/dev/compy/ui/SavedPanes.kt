package dev.compy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.AppState
import dev.compy.data.SavedPane
import dev.compy.data.SavedPaneStore
import dev.compy.rex.ChatEntry
import dev.compy.rex.RexKey
import dev.compy.rex.SavedTerminal
import dev.compy.rex.decodeChat
import dev.compy.term.GhosttyTerminal
import dev.compy.term.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The panes kept on the phone to read without a connection. An agent's pane is kept as its
 * conversation and opens as a chat; any other pane is kept as its screen and history and opens
 * as a terminal. Nothing here talks to a server.
 */
@Composable
fun SavedPanes(app: AppState, onBack: () -> Unit) {
    val saved by app.saved.list.collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = saved.firstOrNull { it.id == openId }
    if (open != null) {
        SavedViewer(app, open, onBack = { openId = null })
        return
    }
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<SavedPane?>(null) }
    var deletingAll by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", filled = false, onClick = onBack)
            Text("Saved panes", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleLarge)
            if (saved.isNotEmpty()) {
                RoundButton(Icons.Rounded.DeleteSweep, "Delete all saved panes", filled = false, tint = scheme.onSurfaceVariant) { deletingAll = true }
            }
        }
        Text(
            // Said plainly, so it is no surprise when an old one goes.
            "Kept on this phone to read offline. Compy keeps the newest ${SavedPaneStore.MAX_COUNT}, up to " +
                "${Formatter.formatShortFileSize(context, SavedPaneStore.MAX_BYTES)}, and drops the oldest to make room. " +
                "Using ${Formatter.formatShortFileSize(context, saved.sumOf { it.bytes })}.",
            Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (saved.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing saved yet. Open a pane, then choose \"Save this pane to read offline\" in the command menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(saved, key = { it.id }) { pane ->
                Row(
                    Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.surfaceContainer)
                        .clickable { openId = pane.id }
                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (pane.kind == SavedPane.CHAT) Icons.Rounded.ChatBubbleOutline else Icons.Rounded.Terminal,
                        if (pane.kind == SavedPane.CHAT) "Conversation" else "Terminal",
                        Modifier.size(22.dp),
                        tint = scheme.primary,
                    )
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(pane.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (pane.where.isNotEmpty()) {
                            Text(pane.where, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                        Text(
                            "${savedWhen(context, pane.savedAt)} · ${Formatter.formatShortFileSize(context, pane.bytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    RoundButton(Icons.Rounded.DeleteOutline, "Delete", size = 40.dp, filled = false, tint = scheme.onSurfaceVariant) { deleting = pane }
                }
            }
        }
    }
    deleting?.let { pane ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this saved pane?") },
            text = { Text("\"${pane.title}\" is removed from this phone. The pane on the server isn't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    app.scope.launch(Dispatchers.IO) { app.saved.delete(pane.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (deletingAll) {
        AlertDialog(
            onDismissRequest = { deletingAll = false },
            title = { Text("Delete all ${saved.size} saved panes?") },
            text = { Text("They are removed from this phone. The panes on the server aren't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    deletingAll = false
                    app.scope.launch(Dispatchers.IO) { app.saved.deleteAll() }
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { deletingAll = false }) { Text("Cancel") } },
        )
    }
}

private fun savedWhen(context: android.content.Context, at: Long): String =
    DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)

/** What a saved pane holds once it is read from the phone. */
private sealed interface SavedContent {
    data object Loading : SavedContent
    data object Unreadable : SavedContent
    data class Chat(val entries: List<ChatEntry>) : SavedContent
    data class Terminal(val terminal: SavedTerminal) : SavedContent
}

@Composable
private fun SavedViewer(app: AppState, pane: SavedPane, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var content by remember(pane.id) { mutableStateOf<SavedContent>(SavedContent.Loading) }
    var searching by remember(pane.id) { mutableStateOf(false) }
    LaunchedEffect(pane.id) {
        content = withContext(Dispatchers.IO) {
            val bytes = app.saved.read(pane.id) ?: return@withContext SavedContent.Unreadable
            if (pane.kind == SavedPane.CHAT) {
                decodeChat(bytes)?.let { SavedContent.Chat(it) } ?: SavedContent.Unreadable
            } else {
                GhosttyTerminal.fromSnapshot(bytes, bytes.size)?.let { SavedContent.Terminal(SavedTerminal(it)) } ?: SavedContent.Unreadable
            }
        }
    }
    // The terminal is the engine's own memory, which is given back when the pane is closed.
    DisposableEffect(content) {
        val shown = content
        onDispose { if (shown is SavedContent.Terminal) shown.terminal.close() }
    }
    BackHandler(enabled = !searching, onBack = onBack)
    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", filled = false, onClick = onBack)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(pane.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Saved ${savedWhen(LocalContext.current, pane.savedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (content is SavedContent.Chat || content is SavedContent.Terminal) {
                RoundButton(Icons.Rounded.Search, "Search", filled = false, tint = if (searching) scheme.primary else scheme.onSurfaceVariant) { searching = !searching }
            }
        }
        when (val shown = content) {
            SavedContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            SavedContent.Unreadable -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("This saved pane can't be read any more.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            is SavedContent.Chat -> ChatBody(
                entries = shown.entries,
                feed = null,
                hasOlder = false,
                loadingOlder = false,
                onOpenImage = {},
                searching = searching,
                onCloseSearch = { searching = false },
                modifier = Modifier.fillMaxSize().background(scheme.surface),
                topPadding = 10.dp,
            ) {}
            is SavedContent.Terminal -> SavedTerminalView(app, shown.terminal, searching, onCloseSearch = { searching = false })
        }
    }
}

/** A saved terminal: it scrolls, rewraps to the screen, can be searched and copied from, and takes no typing. */
@Composable
private fun SavedTerminalView(app: AppState, saved: SavedTerminal, searching: Boolean, onCloseSearch: () -> Unit) {
    val context = LocalContext.current
    val settings by app.settings.collectAsStateWithLifecycle()
    val fonts by app.fonts.collectAsStateWithLifecycle()
    // Pinching sizes the text here and nowhere else.
    var fontSize by remember { mutableFloatStateOf(settings.fontSizeSp) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var total by remember { mutableIntStateOf(0) }
    var place by remember { mutableIntStateOf(-1) }
    LaunchedEffect(query, searching, view) {
        val v = view ?: return@LaunchedEffect
        if (!searching || query.isBlank()) {
            v.clearSearch()
            total = 0
            place = -1
        } else {
            total = v.search(query)
            place = if (total > 0) 0 else -1
        }
    }
    val host = remember(saved) {
        object : TerminalView.Host {
            override fun sendKey(key: RexKey) = Unit
            override fun sendText(text: String) = Unit
            override fun paste(text: String) = Unit
            override fun sendMouse(action: String, button: String?, col: Int, row: Int) = Unit
            override fun sendWheel(lines: Float, col: Int, row: Int) = Unit
            override fun consumeStickyMods() = 0
            override fun onTapped() = Unit
            override fun onGridMeasured(cols: Int, rows: Int, cellW: Int, cellH: Int) = saved.resize(cols, rows, cellW, cellH)
            override fun onFontSizeChanged(sizeSp: Float) {
                fontSize = sizeSp
            }
            override fun copyText(text: String) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
            }
            override fun clipboardText(): String? = null
            override fun openUrl(url: String) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            override fun shareText(text: String) {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            override fun showMessage(text: String) = Unit
        }
    }
    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(18.dp)),
            factory = { ctx ->
                TerminalView(ctx) { app.fonts.value }.also {
                    it.readOnly = true
                    it.fontSizeSp = fontSize
                    it.host = host
                    it.bind(saved)
                    view = it
                }
            },
            update = {
                it.fontSizeSp = fontSize
                it.look = settings.terminalLook()
                it.tag.let { tag -> if (tag !== fonts) { it.tag = fonts; it.fontsChanged() } }
            },
            onRelease = {
                it.bind(null)
                view = null
            },
        )
        if (searching) {
            SearchBar(
                hint = "Find in this pane",
                query = query,
                onQuery = { query = it },
                place = if (place < 0) null else place + 1,
                total = total,
                onOlder = { if (total > 0) place = view?.searchStep(older = true) ?: -1 },
                onNewer = { if (total > 0) place = view?.searchStep(older = false) ?: -1 },
                onClose = {
                    query = ""
                    onCloseSearch()
                },
            )
        }
    }
}
