package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.ConflictStrategy
import com.example.data.model.SyncIntervalUnit
import com.example.data.repository.ConflictResolution
import com.example.data.repository.NextcloudRepository
import com.example.sync.SyncEngine
import com.example.sync.SyncScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SyncEngineRobolectricTest {

    private lateinit var context: Context
    private lateinit var repository: NextcloudRepository
    private lateinit var syncEngine: SyncEngine
    private lateinit var syncScheduler: SyncScheduler

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = NextcloudRepository(context)
        repository.initializeDefaultsIfNeeded()
        repository.refreshRemoteFolders()
        val allFolders = repository.databaseInstance().syncFolderDao().getAllFolders()
        for (f in allFolders) {
            repository.updateFolderSelection(f.remotePath, true)
        }
        syncEngine = SyncEngine(context, repository)
        syncScheduler = SyncScheduler(context)
    }

    @Test
    fun testDefaultsInitialization() = runBlocking {
        val account = repository.getAccount()
        assertNotNull("Default account should be initialized", account)
        assertEquals("admin", account?.username)

        val settings = repository.getSettings()
        assertNotNull("Default settings should exist", settings)
        assertEquals(15, settings.syncIntervalValue)
        assertEquals(SyncIntervalUnit.MINUTES, settings.syncIntervalUnit)
        assertTrue("New folders should sync by default", settings.syncNewFoldersByDefault)
        assertTrue("Background sync should be enabled", settings.runInBackground)
    }

    @Test
    fun testArbitrarySyncIntervalCalculations() {
        val min15 = syncScheduler.calculateIntervalMillis(15, SyncIntervalUnit.MINUTES)
        assertEquals(15 * 60 * 1000L, min15)

        val hours4 = syncScheduler.calculateIntervalMillis(4, SyncIntervalUnit.HOURS)
        assertEquals(4 * 3600 * 1000L, hours4)

        val days3 = syncScheduler.calculateIntervalMillis(3, SyncIntervalUnit.DAYS)
        assertEquals(3 * 86400 * 1000L, days3)
    }

    @Test
    fun testUpdateServerAddress() = runBlocking {
        val newUrl = "https://nextcloud.customdomain.org:8443"
        val status = repository.updateServerAddress(newUrl)
        val updatedAccount = repository.getAccount()
        assertEquals(newUrl, updatedAccount?.serverUrl)
    }

    @Test
    fun testSelectiveFolderSyncToggle() = runBlocking {
        repository.updateFolderSelection("/Projects", true)
        val folders = repository.databaseInstance().syncFolderDao().getAllFolders()
        val projectsFolder = folders.find { it.remotePath == "/Projects" }
        assertTrue("Projects folder should now be selected", projectsFolder?.isSelected == true)

        repository.updateFolderSelection("/Projects", false)
        val updatedFolders = repository.databaseInstance().syncFolderDao().getAllFolders()
        val updatedProjects = updatedFolders.find { it.remotePath == "/Projects" }
        assertFalse("Projects folder should now be excluded", updatedProjects?.isSelected == true)
    }

    @Test
    fun testSyncNewFoldersByDefaultSetting() = runBlocking {
        repository.updateSyncNewFoldersByDefault(false)
        val settings = repository.getSettings()
        assertFalse(settings.syncNewFoldersByDefault)

        repository.updateSyncNewFoldersByDefault(true)
        val settingsTrue = repository.getSettings()
        assertTrue(settingsTrue.syncNewFoldersByDefault)
    }

    @Test
    fun testSyncOnMobileDataSetting() = runBlocking {
        repository.updateSyncOnMobileData(false)
        val settingsFalse = repository.getSettings()
        assertFalse("syncOnMobileData should be false", settingsFalse.syncOnMobileData)

        repository.updateSyncOnMobileData(true)
        val settingsTrue = repository.getSettings()
        assertTrue("syncOnMobileData should be true", settingsTrue.syncOnMobileData)
    }

    @Test
    fun testSelectiveSubdirectorySyncWithoutParent() = runBlocking {
        // Exclude parent "/Documents", but select child "/Documents/Work"
        repository.updateFolderSelection("/Documents", false)
        repository.updateFolderSelection("/Documents/Work", true)

        val folders = repository.databaseInstance().syncFolderDao().getAllFolders()
        val docFolder = folders.find { it.remotePath == "/Documents" }
        val workFolder = folders.find { it.remotePath == "/Documents/Work" }

        assertFalse("Parent Documents folder should not be selected", docFolder?.isSelected == true)
        assertTrue("Child Work folder should be selected", workFolder?.isSelected == true)
    }

    @Test
    fun testBidirectionalSyncExecution() = runBlocking {
        // Run synchronization
        syncEngine.performSynchronization(isManual = true)

        // Verify local files downloaded from server
        val localDocsDir = File(repository.localSyncRootDir, "Documents")
        assertTrue("Local Documents folder should exist", localDocsDir.exists())

        val roadmapFile = File(localDocsDir, "Project-Roadmap.md")
        assertTrue("Project roadmap file should be downloaded locally", roadmapFile.exists())

        // Test local creation & upward sync
        val localCreated = repository.createLocalFile("/Documents", "Client_Created_Note.txt", "Created on Android client.")
        assertTrue(localCreated.exists())

        syncEngine.performSynchronization(isManual = true)

        // Verify journal entry exists
        val journalEntry = repository.databaseInstance().syncJournalDao().getJournalEntry("/Documents/Client_Created_Note.txt")
        assertNotNull("Journal entry should exist after sync", journalEntry)
    }
}

private fun NextcloudRepository.databaseInstance(): com.example.data.local.AppDatabase {
    val field = NextcloudRepository::class.java.getDeclaredField("database")
    field.isAccessible = true
    return field.get(this) as com.example.data.local.AppDatabase
}
