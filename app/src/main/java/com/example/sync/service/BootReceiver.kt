package com.example.sync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.sync.SyncScheduler

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val scheduler = SyncScheduler(context)
            scheduler.scheduleNextSync()
        }
    }
}
