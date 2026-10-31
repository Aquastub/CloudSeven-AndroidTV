package com.nuvio.tv.core.localaddon.program

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Shared between the program host (full build) and the service below. */
object AddonProgramState {
    /** How many addon programs (recorders, ffmpeg) are running right now. */
    val activeCount = MutableStateFlow(0)

    /** Set by the program host so the notification's "Stop all" button can stop everything. */
    @Volatile
    var stopAll: (() -> Unit)? = null
}

/**
 * Keeps the app alive while addon programs run, with one notification for all of them.
 * It stops itself as soon as the last program ends.
 */
class AddonProgramService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) AddonProgramState.stopAll?.invoke()

        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(AddonProgramState.activeCount.value.coerceAtLeast(1)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        watcher?.cancel()
        watcher = scope.launch {
            AddonProgramState.activeCount.collect { count ->
                if (count <= 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(count))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Addon programs", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(count: Int): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, AddonProgramService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (count == 1) "An addon program is running" else "$count addon programs are running")
            .setContentText("For example a recording. Choose Stop to end it.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "addon_programs"
        const val NOTIFICATION_ID = 7421
        const val ACTION_STOP_ALL = "com.nuvio.tv.addons.STOP_ALL"
    }
}
