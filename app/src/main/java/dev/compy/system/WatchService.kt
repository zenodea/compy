package dev.compy.system

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.compy.CompyApp
import dev.compy.MainActivity
import dev.compy.R

/**
 * Keeps connections to Rex servers open while the app is closed, which is what lets alerts arrive
 * without any push service in between. Android requires it to show an ongoing notification.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlertChannels.create(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = (application as CompyApp).state
        if (intent?.action == ACTION_STOP) {
            // Only for this run: it comes back the next time Compy is opened.
            stopWatching(app)
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(this, ONGOING_ID, ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.isSuccess
        if (!started) {
            // Android may refuse to start this from the background, such as after a restart.
            stopWatching(app)
            return START_NOT_STICKY
        }
        app.setWatching(true)
        follow(app)
        return START_STICKY
    }

    private var following: kotlinx.coroutines.Job? = null
    private var shown: AgentSummary.Notice? = null

    /**
     * Keeps the ongoing notification saying what the agents are doing: how many need you or are
     * at work, and a bar for work that reports how far along it is. It is written again only
     * when what it says changes, and no more often than every second and a half.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun follow(app: dev.compy.AppState) {
        if (following?.isActive == true) return
        following = app.scope.launch {
            val dirty = kotlinx.coroutines.flow.MutableStateFlow(0)
            launch {
                app.connections.collectLatest { map ->
                    kotlinx.coroutines.coroutineScope {
                        map.values.forEach { c ->
                            launch { c.statuses.collect { dirty.update { it + 1 } } }
                            launch { c.progress.collect { dirty.update { it + 1 } } }
                        }
                    }
                }
            }
            dirty.debounce(1_500).collect {
                val notice = AgentSummary.notice(agents(app))
                if (notice != shown) {
                    shown = notice
                    runCatching {
                        getSystemService(android.app.NotificationManager::class.java).notify(ONGOING_ID, ongoing(notice))
                    }
                }
            }
        }
    }

    private fun stopWatching(app: dev.compy.AppState) {
        following?.cancel()
        app.setWatching(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        following?.cancel()
        (application as CompyApp).state.setWatching(false)
        super.onDestroy()
    }

    private fun ongoing(notice: AgentSummary.Notice = shown ?: AgentSummary.notice(emptyList())): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AlertChannels.WATCHING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .apply { if (notice.percent != null) setProgress(100, notice.percent, false) }
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop until next open", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val ONGOING_ID = 1
        private const val ACTION_STOP = "dev.compy.action.STOP_WATCHING"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WatchService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}

/** Restarts watching after the phone restarts or Compy updates. Android may decline, which is fine. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = (context.applicationContext as CompyApp).state
        if (app.settings.value.backgroundAlerts && app.servers.value.isNotEmpty()) WatchService.start(context)
    }
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

fun batteryExemptionIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
