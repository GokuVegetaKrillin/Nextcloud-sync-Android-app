package com.example.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.AppDatabase
import com.example.data.model.SyncIntervalUnit
import com.example.sync.service.SyncAlarmReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleNextSync() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(context)
            val settings = db.syncSettingsDao().getSettings() ?: return@launch

            if (!settings.runInBackground) {
                cancelScheduledSync()
                return@launch
            }

            val intervalMillis = calculateIntervalMillis(settings.syncIntervalValue, settings.syncIntervalUnit)
            val triggerTime = System.currentTimeMillis() + intervalMillis

            db.syncSettingsDao().updateNextScheduledSync(triggerTime)

            val intent = Intent(context, SyncAlarmReceiver::class.java).apply {
                action = SyncAlarmReceiver.ACTION_TRIGGER_SYNC
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(context, 1001, intent, flags)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } catch (e: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }
    }

    fun cancelScheduledSync() {
        val intent = Intent(context, SyncAlarmReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 1001, intent, flags)
        alarmManager.cancel(pendingIntent)
    }

    fun calculateIntervalMillis(value: Int, unit: SyncIntervalUnit): Long {
        val safeValue = if (value <= 0) 15 else value
        return when (unit) {
            SyncIntervalUnit.MINUTES -> safeValue * 60 * 1000L
            SyncIntervalUnit.HOURS -> safeValue * 60 * 60 * 1000L
            SyncIntervalUnit.DAYS -> safeValue * 24 * 60 * 60 * 1000L
        }
    }
}
