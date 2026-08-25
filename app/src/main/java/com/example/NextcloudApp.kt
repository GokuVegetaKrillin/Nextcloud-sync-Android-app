package com.example

import android.app.Application
import com.example.data.repository.NextcloudRepository
import com.example.sync.SyncEngine
import com.example.sync.SyncScheduler
import com.example.sync.service.NextcloudSyncForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class NextcloudApp : Application() {

    lateinit var repository: NextcloudRepository
        private set

    lateinit var syncEngine: SyncEngine
        private set

    lateinit var syncScheduler: SyncScheduler
        private set

    override fun onCreate() {
        super.onCreate()

        repository = NextcloudRepository(this)
        syncEngine = SyncEngine(this, repository)
        syncScheduler = SyncScheduler(this)

        CoroutineScope(Dispatchers.IO).launch {
            repository.initializeDefaultsIfNeeded()
            val settings = repository.settingsFlow.firstOrNull()
            if (settings?.runInBackground == true) {
                syncScheduler.scheduleNextSync()
                try {
                    NextcloudSyncForegroundService.startPersistentDaemon(this@NextcloudApp)
                } catch (e: Exception) {
                    // Ignore foreground service start restrictions if activity not foregrounded yet
                }
            }
        }
    }
}
