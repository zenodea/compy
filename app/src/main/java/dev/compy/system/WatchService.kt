package dev.compy.system

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
        return START_STICKY
    }

    private fun stopWatching(app: dev.compy.AppState) {
        app.setWatching(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        (application as CompyApp).state.setWatching(false)
        super.onDestroy()
    }

    private fun ongoing(): Notification {
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
            .setContentTitle("Watching your Rex servers")
            .setContentText("Compy will alert you when an agent needs you")
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
