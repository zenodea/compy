package dev.compy.ui

import kotlinx.coroutines.delay
import dev.compy.rex.chatMatches
import dev.compy.rex.RexKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.compy.rex.ChatEntry
import dev.compy.rex.ChatImage
import dev.compy.rex.ResultEntry
import dev.compy.rex.TranscriptFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Agents whose conversations Compy can read, by icon name (see [ProgramNames]). */
internal val CHAT_AGENTS = dev.compy.rex.CHAT_AGENT_NAMES

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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Only while the app is visible: alerts keep the connection open in the background, and the
    // feed would go on asking the host for new lines every second and a half.
    LaunchedEffect(feed, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { feed.run() } }
    val state by feed.state.collectAsState()
    val statuses by controller.connection.statuses.collectAsStateWithLifecycle()
    val status = statuses[blockId]
    val scheme = MaterialTheme.colorScheme

    var viewing by remember(blockId) { mutableStateOf<ChatImage?>(null) }
    var gallery by rememberSaveable(blockId) { mutableStateOf(false) }
    var searching by rememberSaveable(blockId) { mutableStateOf(false) }
    val live = state.status == TranscriptFeed.Status.Live || state.status is TranscriptFeed.Status.Failed
    Box(
        modifier
            .background(scheme.surface)
            // Touches stay here rather than reaching the terminal underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
    Column(Modifier.fillMaxSize()) {
        when (val s = state.status) {
            TranscriptFeed.Status.Loading -> Centered(Modifier.weight(1f)) { CircularProgressIndicator(Modifier.size(28.dp)) }
            TranscriptFeed.Status.NotInstalled -> SetupNeeded(controller, update = false, Modifier.weight(1f))
            TranscriptFeed.Status.Outdated -> SetupNeeded(controller, update = true, Modifier.weight(1f))
            TranscriptFeed.Status.NoAgent -> Centered(Modifier.weight(1f)) {
                Explain("No agent Compy can read is running in this pane.", onShowTerminal)
            }
            is TranscriptFeed.Status.Unsupported -> Centered(Modifier.weight(1f)) {
                Explain("Compy can't read ${s.agent} conversations yet.", onShowTerminal)
            }
            TranscriptFeed.Status.Live, is TranscriptFeed.Status.Failed -> {
                if (s is TranscriptFeed.Status.Failed) Banner(s.message)
                ChatBody(
                    entries = state.entries,
                    feed = feed,
                    hasOlder = state.hasOlder,
                    loadingOlder = state.loadingOlder,
                    onOpenImage = { viewing = it },
                    searching = searching,
                    onCloseSearch = { searching = false },
                    modifier = Modifier.weight(1f),
                ) {
                    when {
                        status?.isBlocked == true -> AskingBar(status.message, onShowTerminal)
                        // Escape is what stops Claude Code, Codex and Pi in the middle of their work.
                        status?.isWorking == true -> WorkingLine(status.message) { controller.sendKey(RexKey("escape"), blockId) }
                    }
                    Composer(controller, blockId, feed)
                }
            }
        }
    }
        if (live && gallery) ChatImageGallery(feed, onOpen = { viewing = it }, onClose = { gallery = false }, modifier = Modifier.fillMaxSize())
        // Beside the chat and terminal toggle, which the pane puts in the top-right corner.
        if (live && !gallery) GalleryButton({ gallery = true }, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 48.dp))
        if (live && !gallery && !searching) {
            CornerButton(Icons.Rounded.Search, "Search this conversation", { searching = true }, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 90.dp))
        }
    }
    viewing?.let { image -> ChatImageViewer(feed, image) { viewing = null } }
}

/** A round button in a corner of the chat, like the one that swaps to the terminal. */
@Composable
internal fun CornerButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = CircleShape, color = scheme.secondaryContainer.copy(alpha = 0.92f), modifier = modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(18.dp), tint = scheme.onSecondaryContainer)
        }
    }
}

/**
 * A conversation, live or saved: the messages, a button back to the newest one while reading
 * further up, and a search bar that takes the place of [footer] while it is open. [feed] is the
 * live conversation's, for reading further back and for its pictures; a saved one has none.
 */
@Composable
internal fun ChatBody(
    entries: List<ChatEntry>,
    feed: TranscriptFeed?,
    hasOlder: Boolean,
    loadingOlder: Boolean,
    onOpenImage: (ChatImage) -> Unit,
    searching: Boolean,
    onCloseSearch: () -> Unit,
    modifier: Modifier = Modifier,
    topPadding: Dp = 52.dp,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Newest first, which is the order the list holds them in.
    val shown = entries.asReversed()
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(shown, query, searching) { if (searching) chatMatches(shown, query) else emptyList() }
    var at by remember { mutableIntStateOf(0) }
    // A new search starts at the newest match, and a list that changed keeps the place in range.
    LaunchedEffect(query) { at = 0 }
    val current = matches.getOrNull(at.coerceIn(0, maxOf(0, matches.size - 1)))
    LaunchedEffect(current, query) { if (current != null) list.animateScrollToItem(current) }
    val away by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 2 } }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Conversation(shown, feed, hasOlder, loadingOlder, list, current?.let { shown.getOrNull(it)?.key }, topPadding, onOpenImage, Modifier.fillMaxSize())
            androidx.compose.animation.AnimatedVisibility(
                away && !searching,
                Modifier.align(Alignment.BottomEnd).padding(10.dp),
                enter = fadeIn(motion(MOTION_SHORT)) + scaleIn(motion(MOTION_SHORT), initialScale = 0.7f),
                exit = fadeOut(motion(MOTION_SHORT)) + scaleOut(motion(MOTION_SHORT), targetScale = 0.7f),
            ) {
                RoundButton(Icons.Rounded.KeyboardArrowDown, "Go to the newest message", size = 40.dp) {
                    scope.launch { list.animateScrollToItem(0) }
                }
            }
        }
        if (searching) {
            SearchBar(
                hint = "Search this conversation",
                query = query,
                onQuery = { query = it },
                place = if (matches.isEmpty()) null else at.coerceIn(0, matches.size - 1) + 1,
                total = matches.size,
                onOlder = { if (matches.isNotEmpty()) at = (at + 1).coerceAtMost(matches.size - 1) },
                onNewer = { if (matches.isNotEmpty()) at = (at - 1).coerceAtLeast(0) },
                onClose = {
                    query = ""
                    onCloseSearch()
                },
            )
        } else {
            footer()
        }
    }
}

/** A search bar with its count of matches. The arrows go to older and newer ones. */
@Composable
internal fun SearchBar(
    hint: String,
    query: String,
    onQuery: (String) -> Unit,
    place: Int?,
    total: Int,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onClose)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .height(48.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = scheme.primary)
        Box(Modifier.padding(start = 10.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            BasicTextField(
                query, onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onOlder() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotBlank()) {
            Text(
                if (place == null) "None" else "$place/$total",
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        RoundButton(Icons.Rounded.KeyboardArrowUp, "Older match", size = 36.dp, filled = false, onClick = onOlder)
        RoundButton(Icons.Rounded.KeyboardArrowDown, "Newer match", size = 36.dp, filled = false, onClick = onNewer)
        RoundButton(Icons.Rounded.Close, "Close search", size = 36.dp, filled = false, tint = scheme.onSurfaceVariant, onClick = onClose)
    }
}

@Composable
private fun Conversation(
    entries: List<ChatEntry>,
    feed: TranscriptFeed?,
    hasOlder: Boolean,
    loadingOlder: Boolean,
    list: LazyListState,
    current: String?,
    topPadding: Dp,
    onOpenImage: (ChatImage) -> Unit,
    modifier: Modifier,
) {
    // Near the top (the end of a reversed list): read further back.
    LaunchedEffect(list, hasOlder, feed) {
        if (!hasOlder || feed == null) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (last < list.layoutInfo.totalItemsCount - 4) return@collect
                // A read can add nothing to show, such as a chunk holding only tool results, and
                // the list then never moves to ask again. So keep reading, a few times at most.
                repeat(OLDER_READS) {
                    val before = feed.state.value.entries.size
                    feed.loadOlder()
                    val now = feed.state.value
                    if (now.entries.size != before || !now.hasOlder || now.status != TranscriptFeed.Status.Live) return@collect
                }
            }
    }
    // Follow new messages while reading the newest ones, but not while reading back.
    val newest = entries.firstOrNull()?.key
    // Decided here, before the new rows are laid out. The list keeps its place by key when rows
    // arrive, so afterwards its first visible index has moved past them, and three rows arriving
    // at once used to stop the chat following for good.
    val nearNewest by remember(list) { derivedStateOf { list.firstVisibleItemIndex <= 2 } }
    val follow = nearNewest
    LaunchedEffect(newest) {
        if (newest != null && follow) list.animateScrollToItem(0)
    }
    if (entries.isEmpty()) {
        Centered(modifier) {
            Text("Nothing said yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val scheme = MaterialTheme.colorScheme
    SelectionContainer(modifier) {
        LazyColumn(
            state = list,
            reverseLayout = true,
            // Room at the top for the buttons in the pane's corner.
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topPadding, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.key }) { entry ->
                // The match a search is on has a line around it.
                val found = entry.key == current
                Box(
                    if (found) {
                        Modifier.clip(RoundedCornerShape(12.dp)).background(scheme.primary.copy(alpha = 0.12f)).padding(4.dp)
                    } else {
                        Modifier
                    },
                ) { Entry(feed, entry, onOpenImage) }
            }
            if (loadingOlder) {
                item(key = "older") { Centered { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } }
            }
        }
    }
}

/** Pictures aren't kept with a saved conversation, which says so where they were. */
@Composable
private fun PicturesNote(count: Int, modifier: Modifier = Modifier) {
    Text(
        if (count == 1) "1 picture, not saved" else "$count pictures, not saved",
        modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A small button that copies [text], for a message, a block of code or a tool's output. */
@Composable
internal fun CopyButton(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            if (copied) "Copied" else "Copy",
            Modifier.size(14.dp),
            tint = if (copied) scheme.primary else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Entry(feed: TranscriptFeed?, entry: ChatEntry, onOpenImage: (ChatImage) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    when (entry) {
        is ChatEntry.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = scheme.primaryContainer,
                modifier = Modifier.widthIn(max = 520.dp).padding(start = 40.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entry.text.isNotEmpty()) {
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer)
                    }
                    if (entry.images.isNotEmpty()) {
                        if (feed != null) ChatImageRow(feed, entry.images, onOpenImage) else PicturesNote(entry.images.size)
                    }
                }
            }
        }
        is ChatEntry.Assistant -> Column {
            MarkdownText(entry.text, scheme.onSurface, Modifier.fillMaxWidth().padding(end = 8.dp))
            // Not part of what a finger selects, or it would be picked up with the message's text.
            DisableSelection { CopyButton(entry.text, Modifier.padding(top = 2.dp)) }
        }
        is ChatEntry.Thinking -> Expandable(Icons.Rounded.Psychology, "Thinking", null, entry.text, mono = false)
        is ChatEntry.Tool -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolRow(entry)
            // What the agent looked at, shown without opening the row.
            entry.result?.images?.takeIf { it.isNotEmpty() }?.let {
                if (feed != null) ChatImageRow(feed, it, onOpenImage, Modifier.padding(start = 28.dp)) else PicturesNote(it.size, Modifier.padding(start = 28.dp))
            }
        }
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
private fun ToolRow(tool: ChatEntry.Tool) {
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
            if (open && detail != null) DisableSelection { CopyButton(detail, Modifier.size(22.dp)) }
            if (mark != null) Icon(mark, null, Modifier.size(14.dp), tint = if (failed) scheme.error else scheme.onSurfaceVariant)
        }
        if (open && detail != null) {
            Text(
                detail,
                Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 420.dp)
                    // Without this the rest of a long result is cut off with no way to reach it.
                    .verticalScroll(rememberScrollState())
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
                    try {
                        controller.connection.sendReply(controller.sessionId, blockId, message)
                        // Leave anything typed while this was on its way.
                        if (text.trim() == message) text = ""
                        feed.nudge()
                    } catch (e: TimeoutCancellationException) {
                        controller.showMessage("The server took too long to answer")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        controller.showMessage(e.message ?: "Couldn't send that")
                    } finally {
                        sending = false
                    }
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
private fun WorkingLine(message: String?, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Text(
            message?.takeIf { it.isNotBlank() } ?: "Working…",
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Stops the agent where it is, as pressing Escape in its terminal does.
        TextButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
            Icon(Icons.Rounded.Stop, null, Modifier.size(16.dp))
            Text("Stop", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
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
private fun SetupNeeded(controller: WorkspaceController, update: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        // It scrolls, so the button stays in reach in a short pane or above the keyboard.
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(if (update) "Update chat on this host" else "Set up chat on this host", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        Text(
            (if (update) {
                "The computer running Rex has an older compy.lua, which doesn't know the agent in this pane. Chat now reads Claude Code, Codex and Pi.\n\n"
            } else {
                "Compy reads an agent's conversation through a small Rex action, which the computer running Rex has to load.\n\n"
            }) +
                "1. Tap Copy command.\n" +
                "2. Paste it into a shell on that computer and press Enter. A shell pane here in Compy works.\n" +
                "3. Come back to this pane. The chat shows up by itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Button(onClick = { controller.copyHostSetup() }) {
            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
            Text("Copy command")
        }
        Text(
            "The command saves compy.lua in ~/.config/rex, loads it from init.lua and reloads Rex's config.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
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

/** How many reads back in a row may add nothing to show before the chat stops asking. */
private const val OLDER_READS = 8
