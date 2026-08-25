package com.example.sync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SyncAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_SYNC = "com.example.ACTION_TRIGGER_SYNC"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        NextcloudSyncForegroundService.startSyncService(context)
    }
}
