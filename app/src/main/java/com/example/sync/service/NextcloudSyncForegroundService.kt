package com.example.sync.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NextcloudApp
import com.example.R
import com.example.data.model.SyncStatus
import com.example.sync.SyncEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class NextcloudSyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var syncEngine: SyncEngine

    companion object {
        const val CHANNEL_ID = "nextcloud_sync_service_channel"
        const val NOTIFICATION_ID = 4040
        const val ACTION_START_SYNC = "com.example.ACTION_START_SYNC"
        const val ACTION_PAUSE_SYNC = "com.example.ACTION_PAUSE_SYNC"
        const val ACTION_CANCEL_SYNC = "com.example.ACTION_CANCEL_SYNC"

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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val app = application as NextcloudApp
        syncEngine = app.syncEngine
        startForeground(NOTIFICATION_ID, buildNotification("Nextcloud Sync Ready", "Preparing sync...", 0, 0, false))

        serviceScope.launch {
            syncEngine.syncState.collectLatest { state ->
                when (state.status) {
                    SyncStatus.SYNCING -> {
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
                        updateNotification("Nextcloud Synchronized", state.currentAction, 100, 100, false)
                        app.syncScheduler.scheduleNextSync()
                    }
                    SyncStatus.CONFLICT -> {
                        updateNotification("Nextcloud Conflicts Detected", state.currentAction, 100, 100, false)
                        app.syncScheduler.scheduleNextSync()
                    }
                    SyncStatus.PAUSED -> {
                        updateNotification("Nextcloud Sync Paused", "Tap to resume in app", 0, 0, false)
                    }
                    SyncStatus.ERROR -> {
                        updateNotification("Nextcloud Sync Error", state.currentAction, 0, 0, false)
                        app.syncScheduler.scheduleNextSync()
                    }
                    SyncStatus.IDLE -> {
                        updateNotification("Nextcloud Sync Idle", "Up to date", 0, 0, false)
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
            ACTION_PAUSE_SYNC -> {
                syncEngine.pauseSync()
            }
            ACTION_CANCEL_SYNC -> {
                syncEngine.cancelSync()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
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
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String, progress: Int, max: Int, indeterminate: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.app_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        } else if (indeterminate) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(title: String, text: String, progress: Int, max: Int, indeterminate: Boolean) {
        val notification = buildNotification(title, text, progress, max, indeterminate)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
