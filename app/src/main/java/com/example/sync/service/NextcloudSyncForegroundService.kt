package com.example.sync.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NextcloudApp
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.model.SyncStatus
import com.example.sync.SyncEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NextcloudSyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var syncEngine: SyncEngine
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "nextcloud_sync_service_channel"
        const val NOTIFICATION_ID = 4040
        const val ACTION_START_SYNC = "com.example.ACTION_START_SYNC"
        const val ACTION_PAUSE_SYNC = "com.example.ACTION_PAUSE_SYNC"
        const val ACTION_CANCEL_SYNC = "com.example.ACTION_CANCEL_SYNC"
        const val ACTION_KEEP_ALIVE = "com.example.ACTION_KEEP_ALIVE"

        fun startSyncService(context: Context) {
            val intent = Intent(context, NextcloudSyncForegroundService::class.java).apply {
                action = ACTION_START_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startPersistentDaemon(context: Context) {
            val intent = Intent(context, NextcloudSyncForegroundService::class.java).apply {
                action = ACTION_KEEP_ALIVE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val app = application as NextcloudApp
        syncEngine = app.syncEngine

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NextcloudSync::ActiveSyncWakeLock"
        )?.apply {
            setReferenceCounted(false)
        }

        // Start in foreground immediately to prevent Android 8+ ForegroundServiceDidNotStartInTimeException
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Nextcloud Sync Active", "Background sync service is running", 0, 0, false)
        )

        serviceScope.launch {
            syncEngine.syncState.collectLatest { state ->
                when (state.status) {
                    SyncStatus.SYNCING -> {
                        acquireWakeLock()
                        val progress = if (state.totalFilesCount > 0) {
                            (state.filesSyncedCount * 100) / state.totalFilesCount
                        } else 0
                        val text = if (state.currentFile.isNotEmpty()) {
                            "${state.currentAction} (${state.filesSyncedCount}/${state.totalFilesCount})"
                        } else {
                            state.currentAction
                        }
                        updateNotification("Nextcloud Synchronizing", text, progress, 100, true)
                    }
                    SyncStatus.SUCCESS -> {
                        releaseWakeLock()
                        updateIdleNotification("Last sync completed successfully")
                        app.syncScheduler.scheduleNextSync()
                    }
                    SyncStatus.CONFLICT -> {
                        releaseWakeLock()
                        updateNotification("Nextcloud Conflicts Detected", state.currentAction, 100, 100, false)
                        app.syncScheduler.scheduleNextSync()
                    }
                    SyncStatus.PAUSED -> {
                        releaseWakeLock()
                        updateNotification("Nextcloud Sync Paused", "Tap to resume in app", 0, 0, false)
                    }
                    SyncStatus.ERROR -> {
                        releaseWakeLock()
                        updateIdleNotification("Sync error: ${state.currentAction}")
                        app.syncScheduler.scheduleNextSync()
                    }
                    SyncStatus.IDLE -> {
                        releaseWakeLock()
                        updateIdleNotification("Idle • Ready to sync")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                if (!syncEngine.isCurrentlySyncing()) {
                    syncEngine.startSync(isManual = false)
                }
            }
            ACTION_KEEP_ALIVE -> {
                updateIdleNotification("Background sync daemon active")
            }
            ACTION_PAUSE_SYNC -> {
                syncEngine.pauseSync()
            }
            ACTION_CANCEL_SYNC -> {
                syncEngine.cancelSync()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // START_STICKY tells Android to automatically recreate the service if it ever gets killed under memory pressure
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                // 10 minutes safety timeout for wakelock
                wakeLock?.acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            // Ignore wakelock errors
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore wakelock errors
        }
    }

    private fun updateIdleNotification(statusSubtitle: String) {
        serviceScope.launch {
            val db = AppDatabase.getInstance(this@NextcloudSyncForegroundService)
            val settings = db.syncSettingsDao().getSettings()
            val nextSync = settings?.nextScheduledSyncTimestamp ?: 0L
            val nextSyncStr = if (nextSync > System.currentTimeMillis()) {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                "Next sync at ${sdf.format(Date(nextSync))}"
            } else {
                "Sync every ${settings?.syncIntervalValue ?: 15} ${settings?.syncIntervalUnit?.name?.lowercase() ?: "minutes"}"
            }
            val content = "$statusSubtitle • $nextSyncStr"
            updateNotification("Nextcloud Sync Active", content, 0, 0, false)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sync_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.sync_service_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val syncNowIntent = Intent(this, NextcloudSyncForegroundService::class.java).apply {
            action = ACTION_START_SYNC
        }
        val syncNowPendingIntent = PendingIntent.getService(
            this,
            1,
            syncNowIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.app_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.app_logo, "Sync Now", syncNowPendingIntent)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        } else if (indeterminate) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(
        title: String,
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean
    ) {
        val notification = buildNotification(title, text, progress, max, indeterminate)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
