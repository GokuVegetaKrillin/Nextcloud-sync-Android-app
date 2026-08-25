package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.model.*
import com.example.data.remote.MockNextcloudServer
import com.example.data.remote.NextcloudClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class NextcloudRepository(private val context: Context) {

    private val database = AppDatabase.getInstance(context)
    private val accountDao = database.accountDao()
    private val folderDao = database.syncFolderDao()
    private val journalDao = database.syncJournalDao()
    private val activityDao = database.syncActivityDao()
    private val conflictDao = database.conflictDao()
    private val settingsDao = database.syncSettingsDao()

    val nextcloudClient = NextcloudClient()
    val mockServer = MockNextcloudServer(context)

    val accountFlow: Flow<AccountEntity?> = accountDao.getAccountFlow()
    val foldersFlow: Flow<List<SyncFolderConfigEntity>> = folderDao.getAllFoldersFlow()
    val activitiesFlow: Flow<List<SyncActivityEntity>> = activityDao.getRecentActivitiesFlow()
    val conflictsFlow: Flow<List<ConflictEntity>> = conflictDao.getUnresolvedConflictsFlow()
    val settingsFlow: Flow<SyncSettingsEntity?> = settingsDao.getSettingsFlow()

    val localSyncRootDir: File by lazy {
        val dir = File(context.filesDir, "Nextcloud")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    suspend fun initializeDefaultsIfNeeded() = withContext(Dispatchers.IO) {
        // Init settings if missing
        if (settingsDao.getSettings() == null) {
            settingsDao.insertOrUpdateSettings(
                SyncSettingsEntity(
                    id = 1,
                    syncIntervalValue = 15,
                    syncIntervalUnit = SyncIntervalUnit.MINUTES,
                    syncNewFoldersByDefault = true,
                    runInBackground = true,
                    syncOnWifiOnly = false,
                    syncOnChargingOnly = false,
                    conflictStrategy = ConflictStrategy.ASK_USER
                )
            )
        }

        // Init default account if missing
        if (accountDao.getAccount() == null) {
            val defaultAccount = AccountEntity(
                id = 1,
                serverUrl = "https://cloud.example.com",
                username = "admin",
                passwordOrToken = "nextcloud-token-2026",
                displayName = "Nextcloud User",
                email = "user@example.com",
                serverVersion = "Nextcloud Hub 9 (30.0.1)",
                isSimulatedDemo = true,
                trustAllCerts = true
            )
            accountDao.insertOrUpdateAccount(defaultAccount)

            // Seed initial folders
            val initialFolders = listOf(
                SyncFolderConfigEntity(
                    remotePath = "/Documents",
                    localRelativePath = "Documents",
                    isSelected = true,
                    displayName = "Documents",
                    isExplicitlyConfigured = true
                ),
                SyncFolderConfigEntity(
                    remotePath = "/Photos",
                    localRelativePath = "Photos",
                    isSelected = true,
                    displayName = "Photos",
                    isExplicitlyConfigured = true
                ),
                SyncFolderConfigEntity(
                    remotePath = "/Notes",
                    localRelativePath = "Notes",
                    isSelected = true,
                    displayName = "Notes",
                    isExplicitlyConfigured = true
                ),
                SyncFolderConfigEntity(
                    remotePath = "/Projects",
                    localRelativePath = "Projects",
                    isSelected = false,
                    displayName = "Projects",
                    isExplicitlyConfigured = true
                )
            )
            folderDao.insertOrUpdateFolders(initialFolders)

            logActivity(
                type = ActivityType.INFO,
                path = "/",
                message = "Nextcloud Sync initialized. Ready for bidirectional synchronization."
            )
        }
    }

    suspend fun getAccount(): AccountEntity? = accountDao.getAccount()

    suspend fun getSettings(): SyncSettingsEntity {
        return settingsDao.getSettings() ?: SyncSettingsEntity()
    }

    suspend fun saveAccount(account: AccountEntity) = withContext(Dispatchers.IO) {
        accountDao.insertOrUpdateAccount(account)
        logActivity(
            type = ActivityType.INFO,
            path = "/",
            message = "Account configuration updated for ${account.username} (${account.serverUrl})"
        )
    }

    suspend fun updateServerAddress(newServerUrl: String): ServerStatus = withContext(Dispatchers.IO) {
        val sanitized = nextcloudClient.sanitizeServerUrl(newServerUrl)
        val currentAccount = accountDao.getAccount()

        // Probe status
        val status = if (currentAccount?.isSimulatedDemo == true && !newServerUrl.contains("://") && !newServerUrl.contains(".")) {
            mockServer.getStatus(sanitized)
        } else {
            nextcloudClient.checkServerStatus(sanitized, currentAccount?.trustAllCerts ?: true)
        }

        // Update database
        accountDao.updateServerUrl(sanitized)
        logActivity(
            type = ActivityType.INFO,
            path = sanitized,
            message = "Nextcloud server address updated to: $sanitized (Status: ${if (status.isConnected) "Connected" else "Unreachable"})"
        )

        status
    }

    suspend fun testConnection(serverUrl: String, username: String, pass: String, trustAll: Boolean, isDemo: Boolean): ServerStatus = withContext(Dispatchers.IO) {
        if (isDemo) {
            mockServer.getStatus(serverUrl)
        } else {
            val sanitized = nextcloudClient.sanitizeServerUrl(serverUrl)
            nextcloudClient.checkServerStatus(sanitized, trustAll)
        }
    }

    suspend fun updateQuota() = withContext(Dispatchers.IO) {
        val account = accountDao.getAccount() ?: return@withContext
        val quota = if (account.isSimulatedDemo) {
            mockServer.fetchQuota()
        } else {
            nextcloudClient.fetchQuota(account.serverUrl, account.username, account.passwordOrToken, account.trustAllCerts)
        }
        if (quota != null) {
            accountDao.updateQuota(quota.usedBytes, quota.totalBytes)
        }
    }

    suspend fun updateFolderSelection(remotePath: String, isSelected: Boolean) = withContext(Dispatchers.IO) {
        folderDao.updateFolderSelection(remotePath, isSelected)
        logActivity(
            type = ActivityType.INFO,
            path = remotePath,
            message = "Selective sync: Folder '$remotePath' is now ${if (isSelected) "Synchronized" else "Excluded"}"
        )
    }

    suspend fun updateSyncInterval(value: Int, unit: SyncIntervalUnit) = withContext(Dispatchers.IO) {
        settingsDao.updateInterval(value, unit)
        logActivity(
            type = ActivityType.INFO,
            path = "/",
            message = "Sync interval changed to $value ${unit.name.lowercase()}."
        )
    }

    suspend fun updateSyncNewFoldersByDefault(syncNewByDefault: Boolean) = withContext(Dispatchers.IO) {
        settingsDao.updateSyncNewFoldersByDefault(syncNewByDefault)
        logActivity(
            type = ActivityType.INFO,
            path = "/",
            message = "New Nextcloud folders setting changed to: ${if (syncNewByDefault) "Sync automatically" else "Do not sync by default"}"
        )
    }

    suspend fun updateRunInBackground(enabled: Boolean) = withContext(Dispatchers.IO) {
        settingsDao.updateRunInBackground(enabled)
    }

    suspend fun updateSettings(settings: SyncSettingsEntity) = withContext(Dispatchers.IO) {
        settingsDao.insertOrUpdateSettings(settings)
    }

    suspend fun logActivity(type: ActivityType, path: String, message: String, fileSize: Long = 0L, isSuccess: Boolean = true) = withContext(Dispatchers.IO) {
        activityDao.insertActivity(
            SyncActivityEntity(
                type = type,
                path = path,
                message = message,
                fileSize = fileSize,
                isSuccess = isSuccess
            )
        )
    }

    suspend fun clearActivities() = withContext(Dispatchers.IO) {
        activityDao.clearAllActivities()
    }

    suspend fun getUnresolvedConflicts(): List<ConflictEntity> = conflictDao.getUnresolvedConflicts()

    suspend fun resolveConflict(remotePath: String, resolution: ConflictResolution) = withContext(Dispatchers.IO) {
        val conflict = conflictDao.getConflict(remotePath) ?: return@withContext
        val account = accountDao.getAccount() ?: return@withContext
        val localFile = File(localSyncRootDir, conflict.localRelativePath)

        when (resolution) {
            ConflictResolution.KEEP_LOCAL -> {
                // Upload local to remote, overwrite remote
                if (localFile.exists()) {
                    if (account.isSimulatedDemo) {
                        mockServer.uploadFile(conflict.remotePath, localFile, localFile.lastModified())
                    } else {
                        nextcloudClient.uploadFile(
                            account.serverUrl,
                            account.username,
                            account.passwordOrToken,
                            conflict.remotePath,
                            localFile,
                            localFile.lastModified(),
                            account.trustAllCerts
                        )
                    }
                }
                logActivity(
                    type = ActivityType.CONFLICT_RESOLVED,
                    path = remotePath,
                    message = "Conflict resolved: Overwrote Nextcloud server version with local file."
                )
            }
            ConflictResolution.KEEP_REMOTE -> {
                // Download remote and overwrite local
                if (account.isSimulatedDemo) {
                    mockServer.downloadFile(conflict.remotePath, localFile)
                } else {
                    nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        conflict.remotePath,
                        localFile,
                        account.trustAllCerts
                    )
                }
                logActivity(
                    type = ActivityType.CONFLICT_RESOLVED,
                    path = remotePath,
                    message = "Conflict resolved: Overwrote local file with Nextcloud server copy."
                )
            }
            ConflictResolution.KEEP_BOTH -> {
                // Keep both by renaming local to conflict format and downloading remote
                val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dotIndex = localFile.name.lastIndexOf('.')
                val conflictName = if (dotIndex != -1) {
                    val base = localFile.name.substring(0, dotIndex)
                    val ext = localFile.name.substring(dotIndex)
                    "$base (conflicted copy $timestampStr)$ext"
                } else {
                    "${localFile.name} (conflicted copy $timestampStr)"
                }
                val conflictLocalFile = File(localFile.parentFile, conflictName)
                if (localFile.exists()) {
                    localFile.renameTo(conflictLocalFile)
                }

                // Download original remote
                if (account.isSimulatedDemo) {
                    mockServer.downloadFile(conflict.remotePath, localFile)
                } else {
                    nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        conflict.remotePath,
                        localFile,
                        account.trustAllCerts
                    )
                }
                logActivity(
                    type = ActivityType.CONFLICT_RESOLVED,
                    path = remotePath,
                    message = "Conflict resolved: Preserved both files (created '$conflictName')."
                )
            }
        }

        conflictDao.markResolved(remotePath)
    }

    suspend fun resetSyncJournal() = withContext(Dispatchers.IO) {
        journalDao.clearJournal()
        conflictDao.clearAllConflicts()
        logActivity(
            type = ActivityType.INFO,
            path = "/",
            message = "Sync journal cleared. Next sync will perform full reconciliation scan."
        )
    }

    // Local file explorer operations
    fun getLocalFiles(subPath: String = ""): List<File> {
        val dir = if (subPath.isEmpty()) localSyncRootDir else File(localSyncRootDir, subPath.removePrefix("/"))
        if (!dir.exists()) dir.mkdirs()
        return (dir.listFiles() ?: emptyArray()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun createLocalFile(subPath: String, fileName: String, content: String): File {
        val dir = if (subPath.isEmpty()) localSyncRootDir else File(localSyncRootDir, subPath.removePrefix("/"))
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(content)
        return file
    }

    fun createLocalFolder(subPath: String, folderName: String): File {
        val dir = if (subPath.isEmpty()) localSyncRootDir else File(localSyncRootDir, subPath.removePrefix("/"))
        val newFolder = File(dir, folderName)
        newFolder.mkdirs()
        return newFolder
    }

    fun deleteLocalFile(file: File): Boolean {
        return file.deleteRecursively()
    }
}

enum class ConflictResolution {
    KEEP_LOCAL,
    KEEP_REMOTE,
    KEEP_BOTH
}
