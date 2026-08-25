package com.example

import android.app.Application
import com.example.data.repository.NextcloudRepository
import com.example.sync.SyncEngine
import com.example.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            syncScheduler.scheduleNextSync()
        }
    }
}
