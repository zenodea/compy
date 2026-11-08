package dev.compy.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.rex.ChatEntry
import dev.compy.rex.ResultEntry
import dev.compy.rex.TranscriptFeed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Agents whose conversations Compy can read, by icon name (see [ProgramNames]). */
internal val CHAT_AGENTS = setOf("claude")

/** The line to add to the host's init.lua, after copying rex/compy.lua next to it. */
internal const val COMPY_LUA_LINE = """dofile(os.getenv("HOME") .. "/.config/rex/compy.lua")"""

/**
 * An agent's conversation as a chat, read from its transcript on the host, with a box to send it
 * a message. It covers the pane's terminal, which keeps running and keeps its size underneath.
 */
@Composable
fun AgentChat(
    controller: WorkspaceController,
    blockId: String,
    onShowTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feed = remember(blockId) { controller.feed(blockId) }
    LaunchedEffect(feed) { feed.run() }
    val state by feed.state.collectAsState()
    val statuses by controller.connection.statuses.collectAsStateWithLifecycle()
    val status = statuses[blockId]
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier
            .background(scheme.surface)
            // Touches stay here rather than reaching the terminal underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        when (val s = state.status) {
            TranscriptFeed.Status.Loading -> Centered(Modifier.weight(1f)) { CircularProgressIndicator(Modifier.size(28.dp)) }
            TranscriptFeed.Status.NotInstalled -> SetupNeeded(controller, Modifier.weight(1f))
            TranscriptFeed.Status.NoAgent -> Centered(Modifier.weight(1f)) {
                Explain("No agent Compy can read is running in this pane.", onShowTerminal)
            }
            is TranscriptFeed.Status.Unsupported -> Centered(Modifier.weight(1f)) {
                Explain("Compy can't read ${s.agent} conversations yet.", onShowTerminal)
            }
            TranscriptFeed.Status.Live, is TranscriptFeed.Status.Failed -> {
                if (s is TranscriptFeed.Status.Failed) Banner(s.message)
                Conversation(controller, feed, state, Modifier.weight(1f))
                when {
                    status?.isBlocked == true -> AskingBar(status.message, onShowTerminal)
                    status?.isWorking == true -> WorkingLine(status.message)
                }
                Composer(controller, blockId, feed)
            }
        }
    }
}

@Composable
private fun Conversation(controller: WorkspaceController, feed: TranscriptFeed, state: TranscriptFeed.State, modifier: Modifier) {
    val list = rememberLazyListState()
    val entries = state.entries.asReversed()
    // Near the top (the end of a reversed list): read further back.
    LaunchedEffect(list, state.hasOlder) {
        if (!state.hasOlder) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= list.layoutInfo.totalItemsCount - 4) feed.loadOlder() }
    }
    // Follow new messages while reading the newest ones, but not while reading back.
    val newest = entries.firstOrNull()?.key
    LaunchedEffect(newest) {
        if (newest != null && list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0)
    }
    if (entries.isEmpty()) {
        Centered(modifier) {
            Text("Nothing said yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    SelectionContainer(modifier) {
        LazyColumn(
            state = list,
            reverseLayout = true,
            // Room at the top for the button that swaps back to the terminal.
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 52.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.key }) { entry -> Entry(controller, entry) }
            if (state.loadingOlder) {
                item(key = "older") { Centered { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } }
            }
        }
    }
}

@Composable
private fun Entry(controller: WorkspaceController, entry: ChatEntry) {
    val scheme = MaterialTheme.colorScheme
    when (entry) {
        is ChatEntry.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = scheme.primaryContainer,
                modifier = Modifier.widthIn(max = 520.dp).padding(start = 40.dp),
            ) {
                Text(
                    entry.text,
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimaryContainer,
                )
            }
        }
        is ChatEntry.Assistant -> MarkdownText(entry.text, scheme.onSurface, Modifier.fillMaxWidth().padding(end = 8.dp))
        is ChatEntry.Thinking -> Expandable(Icons.Rounded.Psychology, "Thinking", null, entry.text, mono = false)
        is ChatEntry.Tool -> ToolRow(controller, entry)
        is ChatEntry.Notice -> Text(
            entry.text,
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        is ResultEntry -> Unit
    }
}

@Composable
private fun ToolRow(controller: WorkspaceController, tool: ChatEntry.Tool) {
    val detail = buildString {
        tool.input?.let { append(it) }
        tool.result?.output?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append("\n\n")
            append(it)
        }
    }.ifEmpty { null }
    val mark = when (tool.result?.ok) {
        true -> Icons.Rounded.Check
        false -> Icons.Rounded.Close
        null -> null
    }
    Expandable(Icons.Rounded.Build, tool.name, tool.summary, detail, mono = true, mark = mark, failed = tool.result?.ok == false)
}

/** A one-line row that opens to show more, for tool calls and thinking. */
@Composable
private fun Expandable(
    icon: ImageVector,
    title: String,
    summary: String?,
    detail: String?,
    mono: Boolean,
    mark: ImageVector? = null,
    failed: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainer)
            .clickable(enabled = detail != null) { open = !open }
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Text(
                summary.orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = if (mono) FontFamily.Monospace else null,
                fontStyle = if (mono) null else FontStyle.Italic,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mark != null) Icon(mark, null, Modifier.size(14.dp), tint = if (failed) scheme.error else scheme.onSurfaceVariant)
        }
        if (open && detail != null) {
            Text(
                detail,
                Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 420.dp)
                    .then(if (mono) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (mono) FontFamily.Monospace else null,
                color = scheme.onSurfaceVariant,
                softWrap = !mono,
            )
        }
    }
}

@Composable
private fun Composer(controller: WorkspaceController, blockId: String, feed: TranscriptFeed) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(blockId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message the agent") },
            maxLines = 6,
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = scheme.surfaceContainerHigh,
                unfocusedContainerColor = scheme.surfaceContainerHigh,
            ),
        )
        FilledIconButton(
            onClick = {
                val message = text.trim()
                if (message.isEmpty() || sending) return@FilledIconButton
                sending = true
                scope.launch {
                    runCatching { controller.connection.sendReply(controller.sessionId, blockId, message) }
                        .onSuccess { text = ""; feed.nudge() }
                        .onFailure { controller.showMessage(it.message ?: "Couldn't send that") }
                    sending = false
                }
            },
            enabled = text.isNotBlank() && !sending,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, "Send")
        }
    }
}

/** The agent is waiting on a choice it shows in the terminal, such as a permission prompt. */
@Composable
private fun AskingBar(message: String?, onShowTerminal: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message?.takeIf { it.isNotBlank() } ?: "The agent is waiting for you",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onTertiaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onShowTerminal) { Text("Answer in terminal") }
        }
    }
}

@Composable
private fun WorkingLine(message: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Text(
            message?.takeIf { it.isNotBlank() } ?: "Working…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Banner(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            message,
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** What to do on a host whose Rex config doesn't load compy.lua yet. */
@Composable
private fun SetupNeeded(controller: WorkspaceController, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Set up chat on this host", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        Text(
            "Compy reads the agent's conversation through a small Rex action. On the computer running Rex:\n\n" +
                "1. Copy rex/compy.lua from the Compy repo to ~/.config/rex/\n" +
                "2. Add this line to ~/.config/rex/init.lua\n" +
                "3. Reload the config (rex config reload)",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Text(
            COMPY_LUA_LINE,
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.surfaceContainerHighest)
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
        )
        OutlinedButton(onClick = { controller.copy(COMPY_LUA_LINE) }) {
            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
            Text("Copy line")
        }
    }
}

@Composable
private fun Explain(text: String, onShowTerminal: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onShowTerminal) { Text("Show terminal") }
    }
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

/** The button in an agent pane's corner that swaps between its chat and its terminal. */
@Composable
fun ChatToggle(chat: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.secondaryContainer.copy(alpha = 0.92f),
        modifier = modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (chat) Icons.Rounded.Terminal else Icons.Rounded.ChatBubbleOutline,
                if (chat) "Show terminal" else "Show chat",
                Modifier.size(18.dp),
                tint = scheme.onSecondaryContainer,
            )
        }
    }
}
