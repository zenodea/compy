package dev.compy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.compy.AppState
import dev.compy.Selection
import dev.compy.rex.ProgramStatus
import dev.compy.rex.ServerConnection
import dev.compy.rex.asWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** The board's columns, in the order they stand side by side. */
enum class BoardColumn(val title: String, val empty: String) {
    Idle("Idle", "No idle agents"),
    Running("Running", "Nothing is running"),
    Attention("Attention", "Nothing needs you"),
    Done("Done", "Nothing has finished"),
}

/** What decides which panes are on the board and where. */
internal object BoardRules {
    private val AGENTS = setOf(
        "claude", "codex", "codex-cli", "opencode", "pi", "gemini", "cursor", "amp", "aider", "copilot",
        "goose", "crush", "droid", "qwen", "cline", "kiro",
    )

    /** A pane belongs on the board when its program reports a status, or is a coding agent by name. */
    fun isAgent(status: ProgramStatus?, process: String?): Boolean =
        status != null || ProgramNames.candidates(null, process).any { it in AGENTS }

    /** A program that needs you or failed is in Attention; [unseen] is a pane that finished while out of sight. */
    fun column(status: ProgramStatus?, unseen: Boolean): BoardColumn = when {
        status?.isBlocked == true || status?.isError == true -> BoardColumn.Attention
        status?.isWorking == true -> BoardColumn.Running
        status?.isDone == true || unseen -> BoardColumn.Done
        else -> BoardColumn.Idle
    }

    /** The column to open on a phone, where one shows at a time: the most pressing one with anything in it. */
    fun firstColumn(counts: Map<BoardColumn, Int>): BoardColumn =
        listOf(BoardColumn.Attention, BoardColumn.Running, BoardColumn.Done, BoardColumn.Idle)
            .firstOrNull { (counts[it] ?: 0) > 0 } ?: BoardColumn.Attention

    /** How long ago a status arrived, as short as it can be said: "now", "5m", "2h", "3d". */
    fun ago(updatedAt: String?, now: Instant): String {
        val at = updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
        val seconds = (now.epochSecond - at.epochSecond).coerceAtLeast(0)
        return when {
            seconds < 60 -> "now"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86400 -> "${seconds / 3600}h"
            else -> "${seconds / 86400}d"
        }
    }
}

private class BoardCard(
    val connection: ServerConnection,
    val sessionId: String,
    val blockId: String,
    val column: BoardColumn,
    val title: String,
    val where: String,
    val message: String?,
    val status: ProgramStatus?,
    val programs: List<String>,
    val percent: Int?,
    val unseen: Boolean,
) {
    val key get() = "${connection.config.id}-$blockId"
}

/** Every agent pane on every server as a card, newest change first. */
@Composable
private fun boardCards(servers: List<ServerConnection>): List<BoardCard> {
    val cards = mutableListOf<BoardCard>()
    for (connection in servers) {
        key(connection.config.id) {
            val sessions by connection.sessions.collectAsState()
            val views by connection.views.collectAsState()
            val statuses by connection.statuses.collectAsState()
            val attention by connection.attention.collectAsState()
            val titles by connection.titles.collectAsState()
            val programs by connection.programs.collectAsState()
            val folders by connection.folders.collectAsState()
            val progress by connection.progress.collectAsState()
            val detached by connection.detached.collectAsState()
            // A pane just placed in a window is briefly listed as detached too, and a card's key must be its own.
            val listed = HashSet<String>()
            for (session in sessions) {
                val view = views[session.id] ?: continue
                val windows = view.windows + detached[session.id].orEmpty().map { it.asWindow() }
                for (window in windows) for (block in window.blocks) {
                    if (!listed.add(block.id)) continue
                    val status = statuses[block.id]
                    if (!BoardRules.isAgent(status, programs[block.id])) continue
                    val unseen = block.id in attention
                    val column = BoardRules.column(status, unseen)
                    val folder = folders[block.id]
                    val names = ProgramNames.candidates(status?.app, programs[block.id])
                    cards += BoardCard(
                        connection = connection,
                        sessionId = session.id,
                        blockId = block.id,
                        column = column,
                        // Its own title for what it is doing; failing that its name, which says more
                        // than the "shell" a window is called by default.
                        title = titles[block.id]?.takeIf { it.isNotBlank() }
                            ?: names.firstOrNull()?.replaceFirstChar { it.uppercase() }
                            ?: window.displayTitle(titles),
                        where = if (folder != null) "${session.label} · $folder" else session.label,
                        // What it says about itself, or failing that what it is waiting for.
                        message = status?.message?.takeIf { it.isNotBlank() } ?: status?.takeIf { it.isBlocked }?.blockedReason?.replaceFirstChar { it.uppercase() },
                        status = status,
                        programs = names,
                        percent = if (column == BoardColumn.Running) status?.progress ?: progress[block.id] else null,
                        unseen = unseen,
                    )
                }
            }
        }
    }
    return cards.sortedByDescending { it.status?.updatedAt.orEmpty() }
}

/**
 * The agent board: every agent on every server as a card under Idle, Running, Attention or Done,
 * moving between them as it changes state. A tap on a card goes to its pane. Wide screens show
 * the four columns side by side; a phone shows one at a time and swipes between them.
 */
@Composable
fun AgentBoard(app: AppState, onBack: () -> Unit, onOpened: () -> Unit) {
    val connections by app.connections.collectAsStateWithLifecycle()
    val cards = boardCards(connections.values.toList())
    val byColumn = BoardColumn.entries.associateWith { column -> cards.filter { it.column == column } }
    val scheme = MaterialTheme.colorScheme
    // Counted again every half minute, so "5m" turns into "6m" while the board is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val open: (BoardCard) -> Unit = { card ->
        app.select(Selection(card.connection.config.id, card.sessionId))
        app.revealPane(card.connection, card.sessionId, card.blockId)
        onOpened()
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", filled = false, onClick = onBack)
            Text("Agents", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoardColumn.entries.forEach { column ->
                        val list = byColumn[column].orEmpty()
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(scheme.surfaceContainerLow),
                        ) {
                            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(column.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(list.size.toString(), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                            }
                            CardList(column, list, now, open)
                        }
                    }
                }
            } else {
                val first = remember { BoardRules.firstColumn(byColumn.mapValues { it.value.size }) }
                val pager = rememberPagerState(initialPage = first.ordinal) { BoardColumn.entries.size }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BoardColumn.entries.forEach { column ->
                            val selected = pager.currentPage == column.ordinal
                            val fill by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh, motion(MOTION_SHORT), label = "tab")
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(fill)
                                    .clickable { scope.launch { pager.animateScrollToPage(column.ordinal) } }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    column.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                val count = byColumn[column].orEmpty().size
                                if (count > 0) {
                                    Text(
                                        count.toString(),
                                        Modifier.padding(start = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (column == BoardColumn.Attention) scheme.error else scheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalPager(pager, Modifier.fillMaxSize().padding(top = 8.dp), pageSpacing = 8.dp) { page ->
                        val column = BoardColumn.entries[page]
                        CardList(column, byColumn[column].orEmpty(), now, open)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardList(column: BoardColumn, cards: List<BoardCard>, now: Long, onOpen: (BoardCard) -> Unit) {
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(column.empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(cards, key = { it.key }) { card -> Card(card, now, Modifier.animateItem(), onOpen) }
    }
}

@Composable
private fun Card(card: BoardCard, now: Long, modifier: Modifier, onOpen: (BoardCard) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable { onOpen(card) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.TopEnd) {
                ProgramTile(card.programs)
                StatusDot(
                    listOf(card.blockId),
                    card.status?.let { mapOf(card.blockId to it) }.orEmpty(),
                    if (card.unseen) setOf(card.blockId) else emptySet(),
                )
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.where, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (card.status?.isError == true) {
                Text(
                    "Failed",
                    Modifier.padding(start = 8.dp).clip(CircleShape).background(scheme.errorContainer).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onErrorContainer,
                )
            }
            if (card.column != BoardColumn.Idle) {
                val since = BoardRules.ago(card.status?.updatedAt, Instant.ofEpochMilli(now))
                if (since.isNotEmpty()) {
                    Text(since, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        if (card.message != null) {
            Text(
                card.message,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.percent != null) {
            LinearProgressIndicator(
                progress = { card.percent.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(CircleShape),
            )
        }
    }
}
