package dev.compy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.compy.rex.RexKeymap
import dev.compy.rex.WindowView
import dev.compy.term.TerminalView
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private data class Command(
    val id: String,
    val section: String,
    val title: String,
    val subtitle: String? = null,
    val keys: String? = null,
    val searchText: String = "",
    val run: () -> Unit,
)

/** A short description of an action's arguments, such as the command it starts. */
internal fun describeArgs(args: JsonElement?): String? {
    fun flat(e: JsonElement): String? = when (e) {
        is JsonPrimitive -> e.content
        is JsonArray -> e.mapNotNull(::flat).joinToString(" ")
        is JsonObject -> e.values.mapNotNull(::flat).joinToString(" ")
    }
    return args?.let(::flat)?.takeIf { it.isNotBlank() }?.take(60)
}

private val KEY_POSITION = Regex("""\[(?:Key|Digit)?(\w+)]""")

/** How a binding's keys read, with Rex's position markers removed: cmd+[KeyK] becomes cmd+k. */
internal fun describeKeys(keys: List<String>): String? = keys.takeIf { it.isNotEmpty() }?.joinToString(" ") { key ->
    key.replace(KEY_POSITION) { it.groupValues[1].lowercase() }
}

private fun rexCommands(keymap: RexKeymap, controller: WorkspaceController, onMessage: (String) -> Unit, close: () -> Unit): List<Command> {
    val actions = keymap.actions.associateBy { it.name }
    val connection = controller.connection
    fun command(name: String, args: JsonElement?, keys: String?): Command? {
        val action = actions[name] ?: return null
        return Command(
            id = "$name|$args",
            section = action.category ?: "Rex",
            title = action.title,
            subtitle = describeArgs(args) ?: action.description,
            keys = keys,
            searchText = listOfNotNull(action.keywords, name, describeArgs(args)).joinToString(" "),
            run = {
                close()
                connection.runAction(
                    controller.sessionId,
                    if (action.isBlockAction) controller.focusedBlockId else null,
                    name, args, onMessage,
                )
            },
        )
    }
    val bound = keymap.bindings.mapNotNull { command(it.action, it.args, describeKeys(it.keys)) }.distinctBy { it.id }
    val boundNames = keymap.bindings.map { it.action }.toSet()
    // Actions without a key still work, unless they need arguments only a binding would supply.
    val unbound = keymap.actions
        .filter { it.name !in boundNames && !it.needsArguments }
        .mapNotNull { command(it.name, null, null) }
    return bound + unbound
}

/** Everything you can do here, searchable: Compy's own commands plus the host's Rex actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPalette(
    controller: WorkspaceController,
    window: WindowView?,
    onClose: () -> Unit,
    onFind: () -> Unit,
    onDetached: () -> Unit,
    onOverview: () -> Unit,
    onArrange: () -> Unit,
    onCompose: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val connection = controller.connection
    val session = controller.sessionId
    var query by remember { mutableStateOf("") }
    var keymap by remember { mutableStateOf<RexKeymap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            keymap = connection.keymap()
        } catch (_: Exception) {
            failed = true
        }
    }
    val focused = controller.focusedBlockId
    val paneCount = window?.blocks?.size ?: 0
    val zoomed = window?.zoomedBlockId != null
    val own = buildList {
        fun add(title: String, search: String = "", action: () -> Unit) {
            add(Command("compy|$title", "This terminal", title, searchText = search) {
                onClose()
                action()
            })
        }
        add("Find in terminal", "search scrollback text", onFind)
        add("Copy last command's output", "output copy") {
            if (controller.focusedView()?.copyLastOutput() != true) onMessage(TerminalView.NO_OUTPUT_MARKS)
        }
        add("Jump to previous prompt", "scroll back shell") { controller.focusedView()?.jumpPrompt(-1) }
        add("Jump to next prompt", "scroll forward shell") { controller.focusedView()?.jumpPrompt(1) }
        add("Set up prompt jumps and copying output", "shell integration marks osc 133 zsh bash fish") {
            controller.copyShellSetup()
            onMessage("Copied a command. Paste it into a shell on the computer running Rex and press Enter.")
        }
        add("Compose a message", "composer voice long", onCompose)
        focused?.let { block ->
            add("Save this pane to read offline", "history keep archive download export later") { controller.savePane(block) }
            add("Share a picture of this pane", "screenshot image send export") { controller.sharePicture(block) }
            if (block in controller.app.settings.value.paneFontSizes) {
                add("Reset this pane's text size", "font zoom pinch") { controller.resetFontSize(block) }
            }
            add("Split right", "pane new") { connection.newSplit(session, block, horizontal = true, onError = onMessage) }
            add("Split down", "pane new") { connection.newSplit(session, block, horizontal = false, onError = onMessage) }
            if (paneCount > 1) add(if (zoomed) "Show all panes" else "Zoom pane", "maximize") { connection.zoomBlock(session, block, !zoomed) }
            add("Close pane", "kill") { connection.closeBlock(session, block, onMessage) }
        }
        if ((window?.tiledLayer?.blocks?.size ?: 0) > 1) add("Arrange panes", "move swap layout", onArrange)
        window?.let { add("New floating pane", "popup layer overlay") { connection.newFloatingPane(session, it.id, nextFloatBounds(it), onMessage) } }
        add("Detached panes", "background hidden running list", onDetached)
        add("New window", "tab") { connection.newWindow(session, onMessage) }
        add("All windows", "overview tabs", onOverview)
    }
    val all = own + keymap?.let { rexCommands(it, controller, onMessage, onClose) }.orEmpty()
    val shown = if (query.isBlank()) {
        all
    } else {
        all.filter { c -> listOfNotNull(c.title, c.subtitle, c.keys, c.searchText).any { it.contains(query.trim(), ignoreCase = true) } }
    }
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
                    if (query.isEmpty()) Text("Search commands", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    BasicTextField(
                        query, { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.run?.invoke() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp)) {
                val sections = shown.groupBy { it.section }
                sections.forEach { (section, commands) ->
                    item(key = "section-$section") {
                        Text(
                            section,
                            Modifier.animateItem().padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    items(commands, key = { it.id }) { command -> CommandRow(command, Modifier.animateItem()) }
                }
                if (failed) {
                    item(key = "failed") {
                        Text(
                            "Couldn't load this host's actions.",
                            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (shown.isEmpty() && !failed) {
                    item(key = "empty") {
                        Text(
                            "No matching commands",
                            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: Command, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = command.run)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(command.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            command.subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        command.keys?.let {
            Text(
                it,
                Modifier
                    .padding(start = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Searches the focused terminal's screen and scrollback. */
@Composable
fun FindBar(controller: WorkspaceController, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var total by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(-1) }
    val focus = remember { FocusRequester() }
    val view = remember { controller.focusedView() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.isEmpty()) {
            view?.clearSearch()
            total = 0
            index = -1
        } else {
            // Wait for a pause in typing, since scrollback can be long.
            delay(150)
            total = view?.search(query) ?: 0
            index = if (total > 0) 0 else -1
        }
    }
    DisposableEffect(Unit) { onDispose { view?.clearSearch() } }
    BackHandler(onBack = onClose)
    fun step(older: Boolean) {
        if (total > 0) index = view?.searchStep(older) ?: -1
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(44.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = scheme.primary)
        Box(Modifier.padding(start = 10.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Find in terminal", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            BasicTextField(
                query, { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { step(older = true) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            Text(
                if (total == 0) "None" else "${index + 1}/$total",
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        RoundButton(Icons.Rounded.KeyboardArrowUp, "Older match", size = 36.dp, filled = false) { step(older = true) }
        RoundButton(Icons.Rounded.KeyboardArrowDown, "Newer match", size = 36.dp, filled = false) { step(older = false) }
        RoundButton(Icons.Rounded.Close, "Close find", size = 36.dp, filled = false, tint = scheme.onSurfaceVariant, onClick = onClose)
    }
}
