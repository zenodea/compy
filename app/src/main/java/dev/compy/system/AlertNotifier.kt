package dev.compy.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.compy.AppState
import dev.compy.CompyApp
import dev.compy.MainActivity
import dev.compy.R
import dev.compy.rex.ProgramStatus
import dev.compy.rex.ServerConnection
import kotlinx.coroutines.launch
import java.time.Instant

object AlertChannels {
    const val WATCHING = "watching"
    const val NEEDS_YOU = "needs_you"
    const val FINISHED = "finished"
    const val ERRORS = "errors"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: String, description: String, importance: Int) =
            NotificationChannel(id, name, importance).also { it.description = description }
        manager.createNotificationChannels(
            listOf(
                channel(NEEDS_YOU, "Agent needs you", "A program is waiting for your permission, answer or password", NotificationManager.IMPORTANCE_HIGH),
                channel(FINISHED, "Agent finished", "A program reports that it's done", NotificationManager.IMPORTANCE_DEFAULT),
                channel(ERRORS, "Errors", "A program reports that it failed", NotificationManager.IMPORTANCE_DEFAULT),
                channel(WATCHING, "Background connection", "The ongoing notification Android requires while Compy stays connected", NotificationManager.IMPORTANCE_MIN),
            ),
        )
    }
}

/** What, if anything, a status change should alert about. */
internal enum class AlertKind { NeedsYou, Finished, Failed }

internal fun alertKind(status: ProgramStatus?): AlertKind? = when {
    status == null -> null
    status.isBlocked -> AlertKind.NeedsYou
    status.isError -> AlertKind.Failed
    status.isDone -> AlertKind.Finished
    else -> null
}

/**
 * Whether a report should alert. A finished report found on reconnecting is only worth mentioning
 * if it's recent, while something still waiting on you always is.
 */
internal fun shouldAlert(previous: ProgramStatus?, status: ProgramStatus, now: Instant): Boolean {
    val kind = alertKind(status) ?: return false
    if (kind != AlertKind.Finished || previous?.isWorking == true) return true
    val at = status.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    return at.isAfter(now.minusSeconds(FINISHED_RECENT_SECONDS))
}

private const val FINISHED_RECENT_SECONDS = 600L

/** Turns program status changes into notifications, and clears them when no longer relevant. */
class AlertNotifier(private val context: Context, private val app: AppState) {
    private val seen = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    private val manager = NotificationManagerCompat.from(context)

    suspend fun watch(connection: ServerConnection) {
        AlertChannels.create(context)
        var previous = emptyMap<String, ProgramStatus>()
        connection.statuses.collect { now ->
            for ((block, status) in now) {
                val before = previous[block]
                if (before?.state == status.state && before.updatedAt == status.updatedAt) continue
                handle(connection, block, before, status)
            }
            // Status that's gone means the program moved on.
            (previous.keys - now.keys).forEach(::cancel)
            previous = now
        }
    }

    private fun handle(connection: ServerConnection, block: String, before: ProgramStatus?, status: ProgramStatus) {
        if (alertKind(status) == null) {
            cancel(block)
            return
        }
        // The server's timestamp identifies a report, so restarts don't repeat old alerts.
        val key = "${status.state}|${status.updatedAt}"
        if (seen.getString(block, null) == key) return
        seen.edit().putString(block, key).apply()
        if (!shouldAlert(before, status, Instant.now()) || isOnScreen(connection, block)) return
        post(connection, block, status)
    }

    private fun isOnScreen(connection: ServerConnection, block: String): Boolean {
        if (!app.foreground.value) return false
        val selected = app.selection.value ?: return false
        if (selected.serverId != connection.config.id) return false
        return connection.views.value[selected.sessionId]?.activeWindow?.shownBlocks?.any { it.id == block } == true
    }

    private fun post(connection: ServerConnection, block: String, status: ProgramStatus) {
        // A detached pane isn't in any window, but its program can still need attention.
        val view = connection.views.value.values.firstOrNull { v ->
            v.windowOf(block) != null || connection.detached.value[v.sessionId]?.any { it.id == block } == true
        } ?: return
        val window = view.windowOf(block)
        val who = status.app?.replaceFirstChar { it.uppercase() } ?: "A program"
        val kind = alertKind(status) ?: return
        val (channel, title) = when (kind) {
            AlertKind.NeedsYou -> AlertChannels.NEEDS_YOU to when (status.kind) {
                "permission" -> "$who needs your permission"
                "question" -> "$who has a question"
                "auth" -> "$who needs a password"
                else -> "$who needs you"
            }
            AlertKind.Finished -> AlertChannels.FINISHED to "$who finished"
            AlertKind.Failed -> AlertChannels.ERRORS to "$who hit an error"
        }
        val body = status.message ?: window?.displayTitle(connection.titles.value) ?: connection.titles.value[block] ?: "Detached pane"
        val where = "${view.label} · ${connection.config.label}"
        postNotification(block, channel, title, body, where, connection.config.id, view.sessionId, kind == AlertKind.NeedsYou)
    }

    private fun postNotification(
        block: String,
        channel: String,
        title: String,
        body: String,
        where: String,
        serverId: String,
        sessionId: String,
        urgent: Boolean,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val request = block.hashCode()
        val open = PendingIntent.getActivity(
            context, request,
            Intent(context, MainActivity::class.java)
                .setAction("open-$block")
                .putExtra(MainActivity.EXTRA_SERVER, serverId)
                .putExtra(MainActivity.EXTRA_SESSION, sessionId)
                .putExtra(MainActivity.EXTRA_BLOCK, block),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyIntent = PendingIntent.getBroadcast(
            context, request,
            Intent(context, AlertReplyReceiver::class.java)
                .setAction("reply-$block")
                .putExtra(MainActivity.EXTRA_SERVER, serverId)
                .putExtra(MainActivity.EXTRA_SESSION, sessionId)
                .putExtra(MainActivity.EXTRA_BLOCK, block),
            // Inline replies have to fill in the intent, so this one must stay mutable.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reply = NotificationCompat.Action.Builder(0, "Reply", replyIntent)
            .addRemoteInput(RemoteInput.Builder(AlertReplyReceiver.KEY_TEXT).setLabel("Reply to this pane").build())
            .setAllowGeneratedReplies(false)
            // A reply types into a terminal, so it must never work from a locked phone.
            .setAuthenticationRequired(true)
            .build()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(where)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(reply)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            // Terminal text can be sensitive, so hide the details on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("Compy")
                    .setContentText("A program needs attention")
                    .build(),
            )
            .build()
        runCatching { manager.notify(block, NOTIFICATION_ID, notification) }
    }

    fun cancel(block: String) {
        manager.cancel(block, NOTIFICATION_ID)
    }

    /**
     * Confirms a reply. Android keeps showing a notification that was replied to, with a spinner,
     * until the app posts it again, so cancelling alone leaves it stuck. This replaces it quietly
     * and lets it time out by itself.
     */
    fun replySent(block: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(context, AlertChannels.FINISHED)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Reply sent")
            .setContentText(text)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(REPLY_CONFIRM_MS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { manager.notify(block, NOTIFICATION_ID, notification) }
    }

    /** Reports that a reply couldn't be sent, keeping the notification so nothing gets lost. */
    fun replyFailed(block: String, serverId: String, sessionId: String, reason: String) {
        postNotification(block, AlertChannels.NEEDS_YOU, "Couldn't send your reply", reason, "Tap to open the pane", serverId, sessionId, urgent = false)
    }

    private companion object {
        const val NOTIFICATION_ID = 2
        const val REPLY_CONFIRM_MS = 3_000L
    }
}

/** Sends text typed into a notification to its pane. */
class AlertReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
        val server = intent.getStringExtra(MainActivity.EXTRA_SERVER)
        val session = intent.getStringExtra(MainActivity.EXTRA_SESSION)
        val block = intent.getStringExtra(MainActivity.EXTRA_BLOCK)
        if (text.isNullOrEmpty() || server == null || session == null || block == null) return
        val app = (context.applicationContext as CompyApp).state
        val pending = goAsync()
        app.scope.launch {
            try {
                val connection = app.connections.value[server] ?: error("This server is no longer in Compy")
                connection.sendReply(session, block, text)
                app.notifier.replySent(block, text)
            } catch (e: Exception) {
                app.notifier.replyFailed(block, server, session, e.message ?: "Compy isn't connected")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_TEXT = "reply"
    }
}
