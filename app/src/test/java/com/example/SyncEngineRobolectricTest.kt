package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.ActivityType
import com.example.data.model.ConflictStrategy
import com.example.data.model.SyncIntervalUnit
import com.example.data.repository.ConflictResolution
import com.example.data.repository.NextcloudRepository
import com.example.sync.SyncEngine
import com.example.sync.SyncScheduler
import com.example.util.FileTimeHelper
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
        // Fetch subfolders of "/Documents" under lazy loading mode
        repository.fetchSubfolders("/Documents")

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

    @Test
    fun testConflictResolutionKeepLocal() = runBlocking {
        // 1. Initial sync to establish baseline journal
        syncEngine.performSynchronization(isManual = true)

        val localDocsDir = File(repository.localSyncRootDir, "Documents")
        val roadmapFile = File(localDocsDir, "Project-Roadmap.md")
        assertTrue("Project roadmap file should exist locally", roadmapFile.exists())

        // 2. Introduce conflicting changes on both server and client
        val serverRootDir = File(context.filesDir, "mock_nextcloud_remote_storage")
        val serverRoadmap = File(serverRootDir, "Documents/Project-Roadmap.md")
        serverRoadmap.writeText("# Remote Server Version Content")
        FileTimeHelper.setLastModified(serverRoadmap, System.currentTimeMillis() + 10000L)

        roadmapFile.writeText("# User Local Custom Version Content")
        FileTimeHelper.setLastModified(roadmapFile, System.currentTimeMillis() + 20000L)

        // 3. Perform sync with ASK_USER strategy (default in SyncSettingsEntity)
        syncEngine.performSynchronization(isManual = true)

        // Verify conflict was recorded
        val conflicts = repository.getUnresolvedConflicts()
        val roadmapConflict = conflicts.find { it.remotePath == "/Documents/Project-Roadmap.md" }
        assertNotNull("Conflict should be detected for roadmap file", roadmapConflict)

        // 4. Resolve conflict by keeping local version
        repository.resolveConflict("/Documents/Project-Roadmap.md", ConflictResolution.KEEP_LOCAL)

        // 5. Verify that local file retained the local content, and server received local content
        assertTrue("Local file must exist", roadmapFile.exists())
        assertEquals(
            "Local file must contain user local content, not server content",
            "# User Local Custom Version Content",
            roadmapFile.readText()
        )

        assertEquals(
            "Server file must have been overwritten with user local content",
            "# User Local Custom Version Content",
            serverRoadmap.readText()
        )

        // Verify conflict is resolved
        val remainingConflicts = repository.getUnresolvedConflicts()
        assertTrue("Conflict should now be marked resolved", remainingConflicts.none { it.remotePath == "/Documents/Project-Roadmap.md" })
    }

    @Test
    fun testLocalFileDeletionPropagatesToServer() = runBlocking {
        // 1. Initial sync establishes baseline journal
        syncEngine.performSynchronization(isManual = true)

        val localDocsDir = File(repository.localSyncRootDir, "Documents")
        val roadmapFile = File(localDocsDir, "Project-Roadmap.md")
        assertTrue("Project roadmap file should exist locally after initial sync", roadmapFile.exists())

        val serverRootDir = File(context.filesDir, "mock_nextcloud_remote_storage")
        val serverRoadmap = File(serverRootDir, "Documents/Project-Roadmap.md")
        assertTrue("Server roadmap file must exist", serverRoadmap.exists())

        val journalBefore = repository.databaseInstance().syncJournalDao().getJournalEntry("/Documents/Project-Roadmap.md")
        assertNotNull("Journal entry should exist after initial sync", journalBefore)

        // 2. User deletes file locally
        assertTrue("Local file must be deleted", roadmapFile.delete())
        assertFalse("Local file must not exist", roadmapFile.exists())

        // 3. Perform synchronization
        syncEngine.performSynchronization(isManual = true)

        // 4. File should be deleted on server, NOT re-downloaded locally, and journal record removed
        assertFalse("Local file must NOT be re-downloaded", roadmapFile.exists())
        assertFalse("Server file must be deleted from server", serverRoadmap.exists())

        val journalAfter = repository.databaseInstance().syncJournalDao().getJournalEntry("/Documents/Project-Roadmap.md")
        assertNull("Journal entry should be removed from database after deletion propagation", journalAfter)

        // 5. Verify activity log recorded both local and remote deletion events
        val activities = repository.databaseInstance().syncActivityDao().getAllActivities()
        val localDeleteEvent = activities.find { it.type == ActivityType.DELETE_LOCAL && it.path == "/Documents/Project-Roadmap.md" }
        val remoteDeleteEvent = activities.find { it.type == ActivityType.DELETE_REMOTE && it.path == "/Documents/Project-Roadmap.md" }
        assertNotNull("DELETE_LOCAL activity event should be logged", localDeleteEvent)
        assertNotNull("DELETE_REMOTE activity event should be logged", remoteDeleteEvent)

        // 6. Test recreating the file locally - should be treated as a brand new file
        val recreatedFile = repository.createLocalFile("/Documents", "Project-Roadmap.md", "# Recreated Brand New Roadmap")
        assertTrue("Recreated file must exist locally", recreatedFile.exists())

        syncEngine.performSynchronization(isManual = true)

        assertTrue("Server roadmap file should now be uploaded as a new file", serverRoadmap.exists())
        assertEquals("# Recreated Brand New Roadmap", serverRoadmap.readText())

        val journalRecreated = repository.databaseInstance().syncJournalDao().getJournalEntry("/Documents/Project-Roadmap.md")
        assertNotNull("Journal entry must be recreated for the new file", journalRecreated)
    }

    @Test
    fun testDeleteFailurePreservesJournalAndLogsDetailedError() = runBlocking {
        // 1. Add a test file on mock server and perform initial sync
        repository.mockServer.addRemoteFile("/Documents/test-delete.txt", "Test content for deletion failure test")
        syncEngine.performSynchronization(isManual = true)

        val localDocsDir = File(repository.localSyncRootDir, "Documents")
        val testFile = File(localDocsDir, "test-delete.txt")
        assertTrue("test-delete.txt should exist locally after initial sync", testFile.exists())

        val journalBefore = repository.databaseInstance().syncJournalDao().getJournalEntry("/Documents/test-delete.txt")
        assertNotNull("Journal entry should exist", journalBefore)

        // 2. Delete locally
        assertTrue(testFile.delete())
        assertFalse(testFile.exists())

        // 3. Simulate server delete failure (e.g. 500 error or permission denied)
        repository.mockServer.shouldFailDelete = true
        repository.mockServer.deleteErrorMessage = "HTTP 423 Locked: Resource locked by Nextcloud server"

        // 4. Run sync
        syncEngine.performSynchronization(isManual = true)

        // 5. Verify journal was NOT deleted
        val journalAfterFailure = repository.databaseInstance().syncJournalDao().getJournalEntry("/Documents/test-delete.txt")
        assertNotNull("Journal entry MUST be preserved when server deletion fails", journalAfterFailure)

        // 6. Verify detailed error was logged to activity screen
        val activities = repository.databaseInstance().syncActivityDao().getAllActivities()
        val errorActivity = activities.find {
            it.type == ActivityType.ERROR && it.path == "/Documents/test-delete.txt"
        }
        assertNotNull("ActivityType.ERROR must be logged when delete fails", errorActivity)
        assertTrue("Error message must contain detailed error reason",
            errorActivity?.message?.contains("HTTP 423 Locked") == true ||
            errorActivity?.message?.contains("Resource locked") == true
        )

        // 7. Verify local file was NOT re-downloaded
        assertFalse("Local file must not be re-downloaded even if server delete failed", testFile.exists())

        // 8. Now resolve server error, sync again, and verify deletion succeeds and journal is removed
        repository.mockServer.shouldFailDelete = false
        syncEngine.performSynchronization(isManual = true)

        val journalAfterSuccess = repository.databaseInstance().syncJournalDao().getJournalEntry("/Documents/test-delete.txt")
        assertNull("Journal entry should be removed after server deletion succeeds", journalAfterSuccess)
    }

    @Test
    fun testUnselectedFoldersNotCreatedLocallyOnServerAddressChange() = runBlocking {
        // 1. Change server address to new server
        val newUrl = "https://newcloud.example.org"
        repository.updateServerAddress(newUrl)

        // 2. Refresh remote folders and select ONLY /Documents; unselect /Photos and /Projects
        repository.refreshRemoteFolders()
        repository.updateFolderSelection("/Documents", true)
        repository.updateFolderSelection("/Photos", false)
        repository.updateFolderSelection("/Projects", false)

        // 3. Perform synchronization
        syncEngine.performSynchronization(isManual = true)

        // 4. Verify that unselected folders (/Photos, /Projects) were NOT created locally
        val localSyncRoot = repository.localSyncRootDir
        val localPhotosDir = File(localSyncRoot, "Photos")
        val localProjectsDir = File(localSyncRoot, "Projects")
        val localDocsDir = File(localSyncRoot, "Documents")

        assertTrue("Selected folder /Documents MUST exist locally", localDocsDir.exists())
        assertFalse("Unselected folder /Photos must NOT be created locally", localPhotosDir.exists())
        assertFalse("Unselected folder /Projects must NOT be created locally", localProjectsDir.exists())

        // 5. Verify that no journal entries exist for unselected folders
        val allJournal = repository.databaseInstance().syncJournalDao().getAllJournalEntries()
        val photosJournal = allJournal.filter { it.remotePath.startsWith("/Photos") }
        val projectsJournal = allJournal.filter { it.remotePath.startsWith("/Projects") }
        assertTrue("No journal entries for unselected /Photos", photosJournal.isEmpty())
        assertTrue("No journal entries for unselected /Projects", projectsJournal.isEmpty())

        // 6. Now simulate selecting /Photos in the future:
        // Server has files in /Photos, and they must be downloaded without being deleted from server!
        repository.updateFolderSelection("/Photos", true)
        syncEngine.performSynchronization(isManual = true)

        assertTrue("Now selected /Photos must exist locally", localPhotosDir.exists())
        val localPhotosFiles = localPhotosDir.listFiles() ?: emptyArray()
        assertTrue("Photos files should be downloaded cleanly from server", localPhotosFiles.isNotEmpty())

        val serverRootDir = File(context.filesDir, "mock_nextcloud_remote_storage")
        val serverPhotosDir = File(serverRootDir, "Photos")
        val serverPhotosFiles = serverPhotosDir.listFiles() ?: emptyArray()
        assertTrue("Server photos must still exist on server and not be deleted", serverPhotosFiles.isNotEmpty())
    }
}

private fun NextcloudRepository.databaseInstance(): com.example.data.local.AppDatabase {
    val field = NextcloudRepository::class.java.getDeclaredField("database")
    field.isAccessible = true
    return field.get(this) as com.example.data.local.AppDatabase
}
