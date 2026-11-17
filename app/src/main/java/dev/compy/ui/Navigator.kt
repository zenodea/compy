package dev.compy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.Selection
import dev.compy.rex.DirListing
import dev.compy.rex.ServerConnection
import dev.compy.rex.SessionView
import dev.compy.rex.WindowView
import kotlinx.coroutines.CancellationException

/** One thing to go to, or one entry of a folder. */
internal data class Place(
    val id: String,
    val section: String,
    val title: String,
    val subtitle: String? = null,
    val go: () -> Unit,
)

/** A sheet with a search field over a list, as the command palette has. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    hint: String,
    query: String,
    onQuery: (String) -> Unit,
    onGo: () -> Unit,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainer,
    ) {
        Column(Modifier.imePadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                Box(Modifier.padding(start = 10.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    BasicTextField(
                        query, onQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onGo() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun PlaceRow(place: Place, modifier: Modifier = Modifier, mono: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = place.go)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            place.title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = if (mono) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
        )
        place.subtitle?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Every session, window and pane on every server as places to go to, and the folders gone to lately. */
internal fun places(
    servers: List<ServerConnection>,
    recentFolders: List<String>,
    open: (Selection) -> Unit,
    openPane: (serverId: String, sessionId: String, blockId: String) -> Unit,
    openFolder: ((String) -> Unit)?,
): List<Place> = buildList {
    val several = servers.size > 1
    for (server in servers) {
        val serverId = server.config.id
        val host = server.label.value.takeIf { several && it.isNotEmpty() }
        val titles = server.titles.value
        val folders = server.folderPaths.value
        for (session in server.sessions.value) {
            val name = session.label.ifEmpty { "Session" }
            add(Place("session|$serverId|${session.id}", "Sessions", name, host) { open(Selection(serverId, session.id)) })
            val view = server.views.value[session.id] ?: continue
            for (window in view.windows) {
                val title = window.displayTitle(titles)
                add(
                    Place("window|$serverId|${window.id}", "Windows", title, listOfNotNull(name, host).joinToString(" · ")) {
                        open(Selection(serverId, session.id))
                        server.focusWindow(session.id, window.id)
                    },
                )
                // A window with one pane is that pane, so only the panes of a split window are listed.
                if (window.blocks.size < 2) continue
                for (block in window.blocks) {
                    val pane = titles[block.id]?.takeIf { it.isNotEmpty() } ?: block.label.ifEmpty { "Pane" }
                    add(
                        Place("pane|$serverId|${block.id}", "Panes", pane, listOfNotNull(folders[block.id], title, name, host).joinToString(" · ")) {
                            openPane(serverId, session.id, block.id)
                        },
                    )
                }
            }
        }
    }
    if (openFolder != null) {
        for (folder in recentFolders) add(Place("folder|$folder", "Recent folders", folder, "Go there in this pane") { openFolder(folder) })
    }
}

/** Which places a search finds: every word of it, in any order, somewhere in the name or what is under it. */
internal fun matches(place: Place, query: String): Boolean {
    val words = query.trim().split(' ').filter { it.isNotEmpty() }
    val text = place.title + " " + place.subtitle.orEmpty()
    return words.all { text.contains(it, ignoreCase = true) }
}

/** One search over every session, window and pane on every server, and the folders gone to lately. */
@Composable
fun GoToAnything(controller: WorkspaceController, onClose: () -> Unit) {
    val app = controller.app
    val connections by app.connections.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val focused = controller.focusedBlockId
    // Read once, as the sheet opens: a list that moved under the finger would be worse than a stale one.
    val all = remember(connections) {
        places(
            connections.values.toList(), settings.recentFolders,
            open = { app.requestOpen(it) },
            openPane = { server, session, block -> app.openBlock(server, session, block) },
            openFolder = focused?.let { block -> { folder: String -> controller.usePath(block, folder, insert = false) } },
        )
    }
    val shown = all.filter { matches(it, query) }
    fun go(place: Place) {
        onClose()
        place.go()
    }
    SearchSheet("Go to a session, window, pane or folder", query, { query = it }, onGo = { shown.firstOrNull()?.let(::go) }, onClose = onClose) {
        LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp)) {
            shown.groupBy { it.section }.forEach { (section, group) ->
                item(key = "section-$section") { SectionTitle(section, Modifier.animateItem()) }
                items(group, key = { it.id }) { place ->
                    PlaceRow(place.copy(go = { go(place) }), Modifier.animateItem(), mono = section == "Recent folders")
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing matches",
                        Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The names to list for a folder: `..` first unless it is the root, and hidden ones only when the search starts with a dot. */
internal fun folderRows(listing: DirListing, query: String): List<Pair<String, Boolean>> {
    val q = query.trim()
    val rows = listing.entries
        .filter { (q.startsWith(".") || !it.name.startsWith(".")) && it.name.contains(q, ignoreCase = true) }
        .map { it.name to it.isFile }
    return if (listing.path != "/" && q.isEmpty()) listOf(".." to false) + rows else rows
}

/**
 * Browses the folders on a pane's machine, starting from the pane's own folder, to change to one
 * or to type a path into the pane. Tapping a folder opens it, and the button takes the one shown.
 */
@Composable
fun PathBrowser(controller: WorkspaceController, blockId: String, insert: Boolean, onClose: () -> Unit) {
    val connection = controller.connection
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    // "." is the folder the pane is in, which the server can tell even when the pane's shell hasn't said.
    var wanted by remember { mutableStateOf(connection.workingFolder(blockId) ?: ".") }
    var listing by remember { mutableStateOf<DirListing?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wanted) {
        try {
            listing = connection.listDir(controller.sessionId, blockId, wanted, withFiles = insert)
            problem = null
            query = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = if (listing == null) "Couldn't list this pane's folders. The host may run a Rex too old for it." else "Couldn't open that folder"
        }
    }
    fun take(path: String) {
        onClose()
        controller.usePath(blockId, path, insert)
    }
    val shown = listing
    val rows = shown?.let { folderRows(it, query) }.orEmpty()
    fun pick(name: String, isFile: Boolean) {
        val current = listing ?: return
        if (isFile) take(current.child(name)) else wanted = current.child(name)
    }
    SearchSheet(
        if (insert) "Insert a path" else "Go to a folder", query, { query = it },
        onGo = { rows.firstOrNull { it.first != ".." }?.let { pick(it.first, it.second) } },
        onClose = onClose,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                shown?.path ?: wanted,
                Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            Button(onClick = { shown?.let { take(it.path) } }, enabled = shown != null) {
                Text(if (insert) "Insert this folder" else "Open this folder")
            }
        }
        problem?.let {
            Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.error)
        }
        LazyColumn(Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp)) {
            items(rows, key = { it.first }) { (name, isFile) ->
                PlaceRow(Place(name, "", if (isFile) name else "$name/", go = { pick(name, isFile) }), mono = true)
            }
            if (shown != null && rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (query.isBlank()) "This folder is empty" else "Nothing here matches",
                        Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Asks which of a session's other windows a pane should move into. */
@Composable
fun WindowPicker(view: SessionView, blockId: String, titles: Map<String, String>, onPick: (WindowView) -> Unit, onClose: () -> Unit) {
    val others = view.windows.filter { window -> window.blocks.none { it.id == blockId } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Move pane to window") },
        text = {
            if (others.isEmpty()) {
                Text("This session has no other window. Move the pane to a new window instead.")
            } else {
                Column {
                    others.forEach { window ->
                        PlaceRow(Place(window.id, "", window.displayTitle(titles), "${window.blocks.size} ${if (window.blocks.size == 1) "pane" else "panes"}") { onPick(window) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
