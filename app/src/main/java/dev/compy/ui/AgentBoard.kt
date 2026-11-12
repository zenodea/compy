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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The board's columns, in the order they stand side by side. */
enum class BoardColumn(val title: String, val empty: String) {
    Idle("Idle", "No idle agents"),
    Running("Running", "Nothing is running"),
    Attention("Attention", "Nothing needs you"),
    Done("Done", "Nothing has finished"),
}

/**
 * What decides which panes are on the board, where, and what each card says. These are the
 * rules of Renux's board, so the two apps show the same agents the same way.
 */
internal object BoardRules {
    private val AGENTS = setOf(
        "claude", "codex", "codex-cli", "opencode", "pi", "gemini", "cursor", "amp", "aider", "copilot",
        "goose", "crush", "droid", "qwen", "cline", "kiro",
    )
    private val SHELLS = setOf("zsh", "bash", "fish", "sh", "dash", "nu", "elvish", "xonsh", "pwsh")

    /** Only agents are on the board: a pane that reports a status, or runs a program known to be one. */
    fun isAgent(status: ProgramStatus?, process: String?): Boolean =
        status != null || ProgramNames.candidates(null, process).any { it in AGENTS }

    /** A program that needs you or failed is in Attention; one that reports nothing, or anything else, is Idle. */
    fun column(status: ProgramStatus?): BoardColumn = when {
        status == null -> BoardColumn.Idle
        status.isWorking -> BoardColumn.Running
        status.isBlocked || status.isError -> BoardColumn.Attention
        status.isDone -> BoardColumn.Done
        else -> BoardColumn.Idle
    }

    /** A program's name without its script, as `node` is of `node/server.js`. */
    private fun programName(process: String): String = process.substringBefore('/').trimStart('-')

    /**
     * What a card is called: the agent's own name for its task, then the pane's title, then what
     * runs there. A shell's title is only its prompt's idea of one, so a shell goes by its name.
     */
    fun title(status: ProgramStatus?, process: String?, paneTitle: String?): String {
        val program = status?.app?.takeIf { it.isNotEmpty() } ?: process
        val shell = program == null || programName(program) in SHELLS
        return status?.task?.takeIf { it.isNotEmpty() }
            ?: paneTitle?.takeIf { !shell && it.isNotEmpty() }
            ?: programName(program ?: "shell")
    }

    /** What an agent says about itself, or failing that what kind of thing it waits on. */
    fun message(status: ProgramStatus?): String? =
        status?.message?.takeIf { it.isNotEmpty() }
            ?: status?.kind?.takeIf { it.isNotEmpty() }?.let { if (it == "permission") "permission prompt" else it }

    /** Where a card's pane is. Under its session's name it only says where in it; otherwise the session too. */
    fun where(session: String, folder: String?, bySession: Boolean): String = when {
        bySession -> folder.orEmpty()
        folder != null -> "$session · $folder"
        else -> session
    }

    /** A bar shows for work that reports how far along it is. */
    fun progress(status: ProgramStatus?, reported: Int?): Int? {
        if (status?.isWorking != true) return null
        return (status.progress ?: reported)?.takeIf { it >= 0 }
    }

    /** The column to open on a phone, where one shows at a time: the most pressing one with anything in it. */
    fun firstColumn(counts: Map<BoardColumn, Int>): BoardColumn =
        listOf(BoardColumn.Attention, BoardColumn.Running, BoardColumn.Done, BoardColumn.Idle)
            .firstOrNull { (counts[it] ?: 0) > 0 } ?: BoardColumn.Attention

    /** How long ago a state began, in a word: "now", "4 min", "2 h", "3 d". Empty for a moment that was never set. */
    fun ago(since: Long, now: Long): String {
        if (since == 0L) return ""
        val minutes = (now - since).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "$minutes min"
            minutes < 24 * 60 -> "${minutes / 60} h"
            else -> "${minutes / (24 * 60)} d"
        }
    }

    /** The order of a column's cards: by session when grouped, then the latest change first. */
    fun <T> sorted(cards: List<T>, bySession: Boolean, rank: (T) -> Int, since: (T) -> Long): List<T> =
        if (bySession) cards.sortedWith(compareBy<T> { rank(it) }.thenByDescending { since(it) }) else cards.sortedByDescending { since(it) }
}

private class BoardCard(
    val connection: ServerConnection,
    val sessionId: String,
    val sessionLabel: String,
    /** Where the card's session comes in the session list. */
    val sessionRank: Int,
    val blockId: String,
    val column: BoardColumn,
    val title: String,
    val folder: String?,
    val message: String?,
    val status: ProgramStatus?,
    val programs: List<String>,
    val percent: Int?,
    val unseen: Boolean,
) {
    val key get() = "${connection.config.id}-$blockId"
    val since get() = status?.since ?: 0L
}

/** Every agent pane on every server as a card. */
@Composable
private fun boardCards(servers: List<ServerConnection>): List<BoardCard> {
    val cards = mutableListOf<BoardCard>()
    var rank = 0
    for (connection in servers) {
        key(connection.config.id) {
            val sessions by connection.sessions.collectAsState()
            val views by connection.views.collectAsState()
            val statuses by connection.statuses.collectAsState()
            val attention by connection.attention.collectAsState()
            val titles by connection.titles.collectAsState()
            val programs by connection.programs.collectAsState()
            val folders by connection.folderPaths.collectAsState()
            val progress by connection.progress.collectAsState()
            for (session in sessions) {
                rank++
                val view = views[session.id] ?: continue
                for (window in view.windows) for (block in window.blocks) {
                    if (!block.creator.endsWith(".terminal")) continue
                    val status = statuses[block.id]
                    val process = programs[block.id]
                    if (!BoardRules.isAgent(status, process)) continue
                    cards += BoardCard(
                        connection = connection,
                        sessionId = session.id,
                        sessionLabel = session.label.ifEmpty { "session" },
                        sessionRank = rank,
                        blockId = block.id,
                        column = BoardRules.column(status),
                        title = BoardRules.title(status, process, titles[block.id]),
                        folder = folders[block.id],
                        message = BoardRules.message(status),
                        status = status,
                        programs = ProgramNames.candidates(status?.app, process),
                        percent = BoardRules.progress(status, progress[block.id]),
                        unseen = block.id in attention,
                    )
                }
            }
        }
    }
    return cards.distinctBy { it.key }
}

/**
 * The agent board: every agent on every server as a card under Idle, Running, Attention or Done,
 * moving between them as it changes state. A tap on a card goes to its pane. Wide screens show
 * the four columns side by side; a phone shows one at a time and swipes between them.
 */
@Composable
fun AgentBoard(app: AppState, onBack: () -> Unit, onOpened: () -> Unit) {
    val connections by app.connections.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val bySession = settings.boardBySession
    val cards = boardCards(connections.values.toList())
    val byColumn = BoardColumn.entries.associateWith { column ->
        BoardRules.sorted(cards.filter { it.column == column }, bySession, { it.sessionRank }, { it.since })
    }
    val scheme = MaterialTheme.colorScheme
    // "4 min" on each card is kept up to date while the board is open.
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = android.os.SystemClock.elapsedRealtime()
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
            Text("Agents", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleLarge)
            // All cards in one run per column, or under the name of the session each is in.
            Row(Modifier.padding(end = 4.dp).clip(CircleShape).background(scheme.surfaceContainerHigh)) {
                listOf(false to "All", true to "By session").forEach { (grouped, label) ->
                    val selected = bySession == grouped
                    val fill by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh, motion(MOTION_SHORT), label = "grouping")
                    Text(
                        label,
                        Modifier
                            .clip(CircleShape)
                            .background(fill)
                            .clickable { app.updateSettings { it.copy(boardBySession = grouped) } }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    )
                }
            }
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
                            Row(Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                ColumnMark(column)
                                Text(column.title, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(list.size.toString(), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                            }
                            CardList(column, list, bySession, now, open)
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
                                        color = if (column == BoardColumn.Attention) BlockedColor else scheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalPager(pager, Modifier.fillMaxSize().padding(top = 8.dp), pageSpacing = 8.dp) { page ->
                        val column = BoardColumn.entries[page]
                        CardList(column, byColumn[column].orEmpty(), bySession, now, open)
                    }
                }
            }
        }
    }
}

/** The mark at the head of a column, in the color its cards' status dots have. */
@Composable
private fun ColumnMark(column: BoardColumn) {
    val scheme = MaterialTheme.colorScheme
    if (column == BoardColumn.Idle) {
        // Idle agents are asleep: a moon, not a dot.
        Icon(Icons.Rounded.Bedtime, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
        return
    }
    val color = when (column) {
        BoardColumn.Attention -> BlockedColor
        else -> scheme.primary
    }
    Box(
        Modifier.size(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (column == BoardColumn.Done) {
            Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = color)
        } else {
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun CardList(column: BoardColumn, cards: List<BoardCard>, bySession: Boolean, now: Long, onOpen: (BoardCard) -> Unit) {
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
        var lastRank = -1
        cards.forEach { card ->
            // With grouping on, each session's cards come under its name, in the session list's order.
            if (bySession && card.sessionRank != lastRank) {
                lastRank = card.sessionRank
                item(key = "session-${card.sessionRank}") {
                    Text(
                        card.sessionLabel,
                        Modifier.animateItem().padding(start = 6.dp, top = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item(key = card.key) { Card(card, bySession, now, Modifier.animateItem(), onOpen) }
        }
    }
}

@Composable
private fun Card(card: BoardCard, bySession: Boolean, now: Long, modifier: Modifier, onOpen: (BoardCard) -> Unit) {
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
            Text(
                card.title,
                Modifier.padding(start = 12.dp).weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.status?.isError == true) {
                Text(
                    "Failed",
                    Modifier.padding(start = 8.dp).clip(CircleShape).background(scheme.errorContainer).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onErrorContainer,
                )
            }
        }
        if (card.message != null) {
            Text(
                card.message,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.percent != null) {
            LinearProgressIndicator(
                progress = { card.percent.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(CircleShape),
            )
        }
        val where = BoardRules.where(card.sessionLabel, card.folder, bySession)
        // An idle agent has been idle since nobody knows when, so it says nothing of it.
        val since = if (card.column == BoardColumn.Idle) "" else BoardRules.ago(card.since, now)
        if (where.isNotEmpty() || since.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    where,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(since, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
