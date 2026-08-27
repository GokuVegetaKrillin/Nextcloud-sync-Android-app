package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.model.*
import com.example.data.remote.MockNextcloudServer
import com.example.data.remote.NextcloudClient
import com.example.util.FileTimeHelper
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

    fun resolveLocalSyncRootDir(): File {
        val defaultDir = File(context.filesDir, "Nextcloud")
        try {
            val settings = kotlinx.coroutines.runBlocking(Dispatchers.IO) { settingsDao.getSettings() }
            val custom = settings?.customLocalSyncPath?.trim()
            if (!custom.isNullOrBlank()) {
                val dir = File(custom)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                return dir
            }
        } catch (e: Exception) {
            // Fallback to default internal app directory if read error occurs
        }
        if (!defaultDir.exists()) defaultDir.mkdirs()
        return defaultDir
    }

    val localSyncRootDir: File
        get() = resolveLocalSyncRootDir()

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

    suspend fun getAllFolders(): List<SyncFolderConfigEntity> = withContext(Dispatchers.IO) {
        folderDao.getAllFolders()
    }

    suspend fun saveFolder(folder: SyncFolderConfigEntity) = withContext(Dispatchers.IO) {
        folderDao.insertOrUpdateFolder(folder)
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

    suspend fun refreshRemoteFolders(): Result<List<SyncFolderConfigEntity>> = withContext(Dispatchers.IO) {
        val account = accountDao.getAccount() ?: return@withContext Result.failure(Exception("No account configured"))
        val settings = settingsDao.getSettings() ?: SyncSettingsEntity()

        val rootRemoteItems = if (account.isSimulatedDemo) {
            mockServer.listFolder("/", depth = 1)
        } else {
            val res = nextcloudClient.listFolder(
                account.serverUrl,
                account.username,
                account.passwordOrToken,
                "/",
                depth = 1,
                account.trustAllCerts
            )
            res.getOrElse { return@withContext Result.failure(it) }
        }

        val existingFolders = folderDao.getAllFolders().associateBy { it.remotePath }
        val discoveredFolders = rootRemoteItems.filter { it.isDirectory && it.path != "/" && it.path.isNotEmpty() }

        // Clean up old seeded folders if discovering from a real Nextcloud server
        if (!account.isSimulatedDemo && discoveredFolders.isNotEmpty()) {
            val serverFolderPaths = discoveredFolders.map { it.path }.toSet()
            val stale = existingFolders.values.filter { it.remotePath !in serverFolderPaths }
            for (staleFolder in stale) {
                folderDao.deleteFolder(staleFolder.remotePath)
            }
        }

        for (item in discoveredFolders) {
            val existing = existingFolders[item.path]
            val shouldSyncByDefault = settings.syncNewFoldersByDefault
            val folderConfig = existing?.copy(
                remoteSize = item.size,
                displayName = item.displayName,
                lastSyncTime = System.currentTimeMillis()
            ) ?: SyncFolderConfigEntity(
                remotePath = item.path,
                localRelativePath = item.displayName,
                isSelected = shouldSyncByDefault,
                displayName = item.displayName,
                isExplicitlyConfigured = false,
                remoteSize = item.size,
                lastSyncTime = System.currentTimeMillis()
            )
            folderDao.insertOrUpdateFolder(folderConfig)
        }

        logActivity(
            type = ActivityType.INFO,
            path = "/",
            message = "Discovered ${discoveredFolders.size} folders on Nextcloud server."
        )

        Result.success(folderDao.getAllFolders())
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

    suspend fun updateSyncEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        settingsDao.updateSyncEnabled(enabled)
        logActivity(
            type = ActivityType.INFO,
            path = "/",
            message = "Master synchronization toggle set to: ${if (enabled) "ENABLED" else "DISABLED"}"
        )
    }

    suspend fun updateIgnoreDotFiles(ignore: Boolean) = withContext(Dispatchers.IO) {
        settingsDao.updateIgnoreDotFiles(ignore)
        logActivity(
            type = ActivityType.INFO,
            path = "/",
            message = "Dot-files filtering set to: ${if (ignore) "Ignore dot files & folders (.thumbnails, .git, etc.)" else "Include dot files"}"
        )
    }

    suspend fun updateStallTimeout(timeoutSeconds: Int) = withContext(Dispatchers.IO) {
        val safeTimeout = timeoutSeconds.coerceAtLeast(30)
        settingsDao.updateStallTimeout(safeTimeout)
        logActivity(
            type = ActivityType.INFO,
            path = "/",
            message = "Transfer stall timeout set to $safeTimeout seconds (${safeTimeout / 60} min)."
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

    suspend fun updateCustomLocalSyncPath(newPath: String) = withContext(Dispatchers.IO) {
        val cleanPath = newPath.trim()
        settingsDao.updateCustomLocalSyncPath(cleanPath)
        logActivity(
            type = ActivityType.INFO,
            path = cleanPath.ifEmpty { "/data/user/0/com.aistudio.nextcloudsync/files/Nextcloud" },
            message = if (cleanPath.isEmpty()) "Reset local sync location to default app storage."
                      else "Changed local sync destination folder to: $cleanPath"
        )
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
                // If localFile was previously renamed to conflict copy in ASK_USER, restore it
                if (!localFile.exists() && conflict.conflictLocalFileName.isNotEmpty()) {
                    val conflictCopy = File(localFile.parentFile, conflict.conflictLocalFileName)
                    if (conflictCopy.exists()) {
                        conflictCopy.renameTo(localFile)
                    }
                }

                // Upload local to remote, overwrite remote
                var newEtag = conflict.remoteEtag
                if (localFile.exists()) {
                    newEtag = if (account.isSimulatedDemo) {
                        mockServer.uploadFile(conflict.remotePath, localFile, localFile.lastModified()).getOrNull()
                            ?: "etag_${System.currentTimeMillis()}"
                    } else {
                        nextcloudClient.uploadFile(
                            account.serverUrl,
                            account.username,
                            account.passwordOrToken,
                            conflict.remotePath,
                            localFile,
                            localFile.lastModified(),
                            account.trustAllCerts
                        ).getOrNull() ?: "etag_${System.currentTimeMillis()}"
                    }
                }

                // Record in sync journal so subsequent sync cycles know local & remote match perfectly
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = conflict.remotePath,
                        localRelativePath = conflict.localRelativePath,
                        isDirectory = false,
                        remoteEtag = newEtag,
                        remoteSize = localFile.length(),
                        remoteMtime = localFile.lastModified(),
                        localSize = localFile.length(),
                        localMtime = localFile.lastModified(),
                        fileId = ""
                    )
                )

                // Clean up conflict copy if present
                if (conflict.conflictLocalFileName.isNotEmpty()) {
                    val conflictCopy = File(localFile.parentFile, conflict.conflictLocalFileName)
                    if (conflictCopy.exists()) conflictCopy.delete()
                }

                logActivity(
                    type = ActivityType.CONFLICT_RESOLVED,
                    path = remotePath,
                    message = "Conflict resolved: Overwrote Nextcloud server version with local file."
                )
            }
            ConflictResolution.KEEP_REMOTE -> {
                // Download remote and overwrite local
                val etag = if (account.isSimulatedDemo) {
                    mockServer.downloadFile(conflict.remotePath, localFile).getOrNull() ?: conflict.remoteEtag
                } else {
                    nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        conflict.remotePath,
                        localFile,
                        account.trustAllCerts
                    ).getOrNull() ?: conflict.remoteEtag
                }

                // CRITICAL: Synchronize local file modification timestamp to match server timestamp
                if (conflict.remoteMtime > 0L) {
                    FileTimeHelper.setLastModified(localFile, conflict.remoteMtime)
                }

                // Synchronize sync journal with exact file timestamps to prevent duplicate conflict detection
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = conflict.remotePath,
                        localRelativePath = conflict.localRelativePath,
                        isDirectory = false,
                        remoteEtag = etag,
                        remoteSize = localFile.length(),
                        remoteMtime = conflict.remoteMtime,
                        localSize = localFile.length(),
                        localMtime = localFile.lastModified(),
                        fileId = ""
                    )
                )

                // Clean up conflict copy file if present
                if (conflict.conflictLocalFileName.isNotEmpty()) {
                    val conflictCopy = File(localFile.parentFile, conflict.conflictLocalFileName)
                    if (conflictCopy.exists()) conflictCopy.delete()
                }

                logActivity(
                    type = ActivityType.CONFLICT_RESOLVED,
                    path = remotePath,
                    message = "Conflict resolved: Overwrote local file with Nextcloud server copy (modification date synchronized)."
                )
            }
            ConflictResolution.KEEP_BOTH -> {
                // Preserve both files: local remains as conflicted copy, download remote to main filename
                if (conflict.conflictLocalFileName.isEmpty() && localFile.exists()) {
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
                    localFile.renameTo(conflictLocalFile)
                }

                // Download original remote
                val etag = if (account.isSimulatedDemo) {
                    mockServer.downloadFile(conflict.remotePath, localFile).getOrNull() ?: conflict.remoteEtag
                } else {
                    nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        conflict.remotePath,
                        localFile,
                        account.trustAllCerts
                    ).getOrNull() ?: conflict.remoteEtag
                }

                // Synchronize local file modification timestamp to match server timestamp
                if (conflict.remoteMtime > 0L) {
                    FileTimeHelper.setLastModified(localFile, conflict.remoteMtime)
                }

                // Synchronize sync journal
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = conflict.remotePath,
                        localRelativePath = conflict.localRelativePath,
                        isDirectory = false,
                        remoteEtag = etag,
                        remoteSize = localFile.length(),
                        remoteMtime = conflict.remoteMtime,
                        localSize = localFile.length(),
                        localMtime = localFile.lastModified(),
                        fileId = ""
                    )
                )

                logActivity(
                    type = ActivityType.CONFLICT_RESOLVED,
                    path = remotePath,
                    message = "Conflict resolved: Preserved both files (server copy modification date synchronized)."
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
