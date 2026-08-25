package com.example.sync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AppDatabase
import com.example.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getInstance(context)
                val settings = db.syncSettingsDao().getSettings()
                if (settings?.runInBackground == true) {
                    val scheduler = SyncScheduler(context)
                    scheduler.scheduleNextSync()
                    NextcloudSyncForegroundService.startPersistentDaemon(context)
                }
            }
        }
    }
}
