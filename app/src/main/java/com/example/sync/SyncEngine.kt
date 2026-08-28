package com.example.sync

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.model.*
import com.example.data.repository.NextcloudRepository
import com.example.util.FileTimeHelper
import com.example.util.NetworkHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class SyncProgressState(
    val status: SyncStatus = SyncStatus.IDLE,
    val currentAction: String = "Idle",
    val currentFile: String = "",
    val filesSyncedCount: Int = 0,
    val totalFilesCount: Int = 0,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedKbps: Double = 0.0,
    val lastSyncTimestamp: Long = 0L,
    val errorMessage: String? = null,
    val isPaused: Boolean = false
)

class SyncEngine(
    private val context: Context,
    private val repository: NextcloudRepository
) {
    private val database = AppDatabase.getInstance(context)
    private val accountDao = database.accountDao()
    private val folderDao = database.syncFolderDao()
    private val journalDao = database.syncJournalDao()
    private val activityDao = database.syncActivityDao()
    private val conflictDao = database.conflictDao()
    private val settingsDao = database.syncSettingsDao()

    private val _syncState = MutableStateFlow(SyncProgressState())
    val syncState: StateFlow<SyncProgressState> = _syncState.asStateFlow()

    private var syncJob: Job? = null
    private var isPaused = false

    fun isCurrentlySyncing(): Boolean {
        return _syncState.value.status == SyncStatus.SYNCING
    }

    fun pauseSync() {
        isPaused = true
        _syncState.value = _syncState.value.copy(
            status = SyncStatus.PAUSED,
            currentAction = "Synchronization paused by user",
            isPaused = true
        )
    }

    fun resumeSync() {
        isPaused = false
        _syncState.value = _syncState.value.copy(isPaused = false)
        startSync(isManual = true)
    }

    fun cancelSync() {
        syncJob?.cancel()
        _syncState.value = _syncState.value.copy(
            status = SyncStatus.IDLE,
            currentAction = "Sync cancelled",
            currentFile = ""
        )
    }

    fun startSync(isManual: Boolean = false): Job {
        syncJob?.cancel()
        val job = CoroutineScope(Dispatchers.IO).launch {
            performSynchronization(isManual)
        }
        syncJob = job
        return job
    }

    suspend fun performSynchronization(isManual: Boolean = false) = withContext(Dispatchers.IO) {
        if (isPaused) {
            _syncState.value = _syncState.value.copy(status = SyncStatus.PAUSED, currentAction = "Sync paused")
            return@withContext
        }

        val account = accountDao.getAccount()
        if (account == null || !account.isActive) {
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.ERROR,
                currentAction = "No active Nextcloud account configured",
                errorMessage = "Account not found"
            )
            return@withContext
        }

        val settings = settingsDao.getSettings() ?: SyncSettingsEntity()
        if (!settings.isSyncEnabled) {
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.PAUSED,
                currentAction = "Synchronization is disabled in settings",
                currentFile = ""
            )
            return@withContext
        }

        // Network connection & mobile data validation
        val (networkAllowed, networkReason) = NetworkHelper.checkSyncNetworkAllowed(context, settings, isManual)
        if (!networkAllowed) {
            val status = if (isManual) SyncStatus.ERROR else SyncStatus.IDLE
            _syncState.value = _syncState.value.copy(
                status = status,
                currentAction = networkReason ?: "Network condition not met",
                errorMessage = if (isManual) networkReason else null
            )
            repository.logActivity(
                type = if (isManual) ActivityType.ERROR else ActivityType.INFO,
                path = "/",
                message = networkReason ?: "Sync condition not met",
                isSuccess = !isManual
            )
            return@withContext
        }

        _syncState.value = SyncProgressState(
            status = SyncStatus.SYNCING,
            currentAction = "Connecting to Nextcloud server...",
            currentFile = "",
            lastSyncTimestamp = System.currentTimeMillis()
        )

        val startTime = System.currentTimeMillis()
        var totalFilesProcessed = 0
        var bytesTransferred = 0L
        val stallTimeout = settings.transferStallTimeoutSeconds.toLong().coerceAtLeast(30L)

        try {
            // 1. Connection check
            val serverStatus = if (account.isSimulatedDemo) {
                repository.mockServer.getStatus(account.serverUrl)
            } else {
                repository.nextcloudClient.checkServerStatus(account.serverUrl, account.trustAllCerts)
            }

            if (!serverStatus.isConnected) {
                val errorMsg = "Cannot connect to Nextcloud server: ${serverStatus.errorMessage ?: "Network unreachable"}"
                _syncState.value = _syncState.value.copy(
                    status = SyncStatus.ERROR,
                    currentAction = "Connection failed",
                    errorMessage = errorMsg
                )
                repository.logActivity(ActivityType.ERROR, "/", errorMsg, isSuccess = false)
                return@withContext
            }

            // 2. Discover Remote Folders & Apply 'syncNewFoldersByDefault' setting
            _syncState.value = _syncState.value.copy(currentAction = "Discovering remote folder structure...")

            // Use repository refresh to sync folder metadata cleanly
            val refreshResult = repository.refreshRemoteFolders()
            val rootRemoteItems = if (account.isSimulatedDemo) {
                repository.mockServer.listFolder("/", depth = 1)
            } else {
                repository.nextcloudClient.listFolder(
                    account.serverUrl,
                    account.username,
                    account.passwordOrToken,
                    "/",
                    depth = 1,
                    account.trustAllCerts
                ).getOrDefault(emptyList())
            }

            // 3. Collect all enabled folders
            val currentFolders = folderDao.getAllFolders()
            val enabledFolders = currentFolders.filter { it.isSelected }

            // 3b. Sanitize journal: Purge any journal entries belonging to unselected folders
            val unselectedFolders = currentFolders.filter { !it.isSelected }
            for (unselected in unselectedFolders) {
                journalDao.deleteByPathPrefix(unselected.remotePath)
            }

            // 4. Discover all remote items recursively inside enabled folders + root files
            _syncState.value = _syncState.value.copy(currentAction = "Indexing Nextcloud remote files...")
            val allRemoteItemsMap = mutableMapOf<String, WebDavItem>()

            // Add root-level files
            for (item in rootRemoteItems) {
                if (!item.isDirectory && item.path.isNotEmpty() && item.path != "/") {
                    if (settings.ignoreDotFilesAndFolders && item.displayName.startsWith(".")) continue
                    allRemoteItemsMap[item.path] = item
                }
            }

            // Index inside enabled folders
            for (folder in enabledFolders) {
                if (!isActive) break
                val remoteItems = if (account.isSimulatedDemo) {
                    repository.mockServer.listFolder(folder.remotePath, depth = 10)
                } else {
                    val res = repository.nextcloudClient.listFolder(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        folder.remotePath,
                        depth = 10,
                        account.trustAllCerts
                    )
                    res.getOrDefault(emptyList())
                }

                for (item in remoteItems) {
                    if (item.path.isNotEmpty() && item.path != "/") {
                        if (settings.ignoreDotFilesAndFolders) {
                            val hasDotSegment = item.path.split("/").any { it.startsWith(".") && it.isNotEmpty() }
                            if (hasDotSegment) continue
                        }
                        allRemoteItemsMap[item.path] = item
                    }
                }
            }

            // 5. Scan local filesystem (including newly created local subdirectories)
            _syncState.value = _syncState.value.copy(currentAction = "Scanning local storage...")
            val localBaseDir = repository.localSyncRootDir
            val localFilesMap = scanLocalDirectory(localBaseDir, enabledFolders, settings.ignoreDotFilesAndFolders)

            // 6. Fetch previous journal
            val journalMap = journalDao.getAllJournalEntries().associateBy { it.remotePath }

            // 7. Calculate diff set and actions
            _syncState.value = _syncState.value.copy(currentAction = "Reconciling changes (CSync algorithm)...")

            val allPaths = mutableSetOf<String>()
            allPaths.addAll(allRemoteItemsMap.keys)
            allPaths.addAll(localFilesMap.keys)
            allPaths.addAll(journalMap.keys)

            // Filter paths belonging to enabled folders (or subdirectories) or root level files
            val targetPaths = allPaths.filter { path ->
                val isRootFile = !path.removePrefix("/").contains("/")
                val belongsToSelected = enabledFolders.any { folder ->
                    path == folder.remotePath || path.startsWith("${folder.remotePath}/")
                }
                isRootFile || belongsToSelected
            }.sortedWith(compareBy({ !it.contains("/") }, { it.count { c -> c == '/' } }, { it }))

            if (targetPaths.isEmpty() && enabledFolders.isEmpty()) {
                _syncState.value = _syncState.value.copy(
                    status = SyncStatus.SUCCESS,
                    currentAction = "Sync complete: All files up to date",
                    currentFile = ""
                )
                return@withContext
            }

            _syncState.value = _syncState.value.copy(
                totalFilesCount = targetPaths.size,
                currentAction = "Synchronizing files..."
            )

            val newlyCreatedLocalDirsInThisRun = mutableSetOf<String>()

            // 8. Reconcile each path
            for (path in targetPaths) {
                if (!isActive || isPaused) break

                val remote = allRemoteItemsMap[path]
                val local = localFilesMap[path]
                val journal = journalMap[path]

                val relativeLocalPath = path.removePrefix("/")
                val targetLocalFile = File(localBaseDir, relativeLocalPath)

                _syncState.value = _syncState.value.copy(
                    currentFile = path,
                    filesSyncedCount = totalFilesProcessed
                )

                try {
                    if (remote != null && local != null) {
                        // Item exists both locally and remotely
                        if (remote.isDirectory) {
                            // Directory exists on both sides
                            targetLocalFile.mkdirs()
                            journalDao.insertOrUpdate(
                                SyncJournalEntryEntity(
                                    remotePath = path,
                                    localRelativePath = relativeLocalPath,
                                    isDirectory = true,
                                    remoteEtag = remote.etag,
                                    remoteSize = 0L,
                                    remoteMtime = remote.lastModified,
                                    localSize = 0L,
                                    localMtime = targetLocalFile.lastModified(),
                                    fileId = remote.fileId ?: ""
                                )
                            )
                        } else {
                            // File exists on both sides
                            val localModified = journal != null && (local.length() != journal.localSize || Math.abs(local.lastModified() - journal.localMtime) > 2000)
                            val remoteModified = journal != null && (remote.etag != journal.remoteEtag)

                            if (journal == null) {
                                // File exists on both sides but never synced before
                                if (local.length() == remote.size && Math.abs(local.lastModified() - remote.lastModified) < 3000) {
                                    // Match: adopt
                                    journalDao.insertOrUpdate(
                                        SyncJournalEntryEntity(
                                            remotePath = path,
                                            localRelativePath = relativeLocalPath,
                                            isDirectory = false,
                                            remoteEtag = remote.etag,
                                            remoteSize = remote.size,
                                            remoteMtime = remote.lastModified,
                                            localSize = local.length(),
                                            localMtime = local.lastModified(),
                                            fileId = remote.fileId ?: ""
                                        )
                                    )
                                } else {
                                    // Conflict or download remote
                                    handleConflictOrResolution(
                                        path = path,
                                        relativeLocalPath = relativeLocalPath,
                                        localFile = local,
                                        remote = remote,
                                        account = account,
                                        settings = settings
                                    )
                                }
                            } else if (localModified && remoteModified) {
                                // CONFLICT! Both sides changed
                                handleConflictOrResolution(
                                    path = path,
                                    relativeLocalPath = relativeLocalPath,
                                    localFile = local,
                                    remote = remote,
                                    account = account,
                                    settings = settings
                                )
                            } else if (localModified) {
                                // Upload local modification
                                _syncState.value = _syncState.value.copy(currentAction = "Uploading ${targetLocalFile.name}")
                                val etag = if (account.isSimulatedDemo) {
                                    repository.mockServer.uploadFile(path, local, local.lastModified()).getOrNull() ?: "etag_${System.currentTimeMillis()}"
                                } else {
                                    repository.nextcloudClient.uploadFile(
                                        account.serverUrl,
                                        account.username,
                                        account.passwordOrToken,
                                        path,
                                        local,
                                        local.lastModified(),
                                        account.trustAllCerts,
                                        stallTimeout
                                    ).getOrNull() ?: "etag_${System.currentTimeMillis()}"
                                }
                                bytesTransferred += local.length()
                                journalDao.insertOrUpdate(
                                    SyncJournalEntryEntity(
                                        remotePath = path,
                                        localRelativePath = relativeLocalPath,
                                        isDirectory = false,
                                        remoteEtag = etag,
                                        remoteSize = local.length(),
                                        remoteMtime = local.lastModified(),
                                        localSize = local.length(),
                                        localMtime = local.lastModified(),
                                        fileId = remote.fileId ?: ""
                                    )
                                )
                                repository.logActivity(ActivityType.UPLOAD, path, "Uploaded updated local file to Nextcloud", local.length())
                            } else if (remoteModified) {
                                // Download remote modification
                                _syncState.value = _syncState.value.copy(currentAction = "Downloading ${targetLocalFile.name}")
                                val etag = if (account.isSimulatedDemo) {
                                    repository.mockServer.downloadFile(path, targetLocalFile).getOrNull() ?: remote.etag
                                } else {
                                    repository.nextcloudClient.downloadFile(
                                        account.serverUrl,
                                        account.username,
                                        account.passwordOrToken,
                                        path,
                                        targetLocalFile,
                                        account.trustAllCerts,
                                        stallTimeout
                                    ).getOrNull() ?: remote.etag
                                }
                                if (remote.lastModified > 0L) {
                                    FileTimeHelper.setLastModified(targetLocalFile, remote.lastModified)
                                }
                                bytesTransferred += targetLocalFile.length()
                                journalDao.insertOrUpdate(
                                    SyncJournalEntryEntity(
                                        remotePath = path,
                                        localRelativePath = relativeLocalPath,
                                        isDirectory = false,
                                        remoteEtag = etag,
                                        remoteSize = targetLocalFile.length(),
                                        remoteMtime = remote.lastModified,
                                        localSize = targetLocalFile.length(),
                                        localMtime = targetLocalFile.lastModified(),
                                        fileId = remote.fileId ?: ""
                                    )
                                )
                                repository.logActivity(ActivityType.DOWNLOAD, path, "Downloaded updated file from Nextcloud", targetLocalFile.length())
                            }
                        }
                    } else if (remote != null && local == null) {
                        // Item exists in remote, not in local
                        val parentDir = targetLocalFile.parentFile
                        val parentRelativePath = "/" + (parentDir?.relativeTo(localBaseDir)?.path?.replace('\\', '/') ?: "").trim('/')
                        
                        // Local deletion confirmed ONLY if:
                        // 1. Parent folder existed in local filesystem at start of this sync
                        // 2. Not located within a folder newly created during this sync run
                        // 3. Remote etag exactly matches previous journal etag
                        val parentExistedAtStart = parentDir != null && parentDir.exists() && (parentRelativePath == "/" || localFilesMap.containsKey(parentRelativePath))
                        val isInsideNewlyCreatedDir = newlyCreatedLocalDirsInThisRun.any { dirPath ->
                            path == dirPath || path.startsWith("$dirPath/")
                        }
                        val isConfirmedLocalDeletion = journal != null && parentExistedAtStart && !isInsideNewlyCreatedDir && (remote.etag == journal.remoteEtag)

                        if (isConfirmedLocalDeletion) {
                            // Local deletion confirmed and remote was not modified in the meantime
                            _syncState.value = _syncState.value.copy(currentAction = "Propagating deletion to Nextcloud...")
                            if (account.isSimulatedDemo) {
                                repository.mockServer.deleteItem(path)
                            } else {
                                repository.nextcloudClient.deleteItem(
                                    account.serverUrl,
                                    account.username,
                                    account.passwordOrToken,
                                    path,
                                    account.trustAllCerts
                                )
                            }
                            journalDao.deleteByRemotePath(path)
                            repository.logActivity(ActivityType.DELETE_REMOTE, path, "Deleted from Nextcloud (deleted locally)")
                        } else {
                            // Download remote item to local (new remote file or re-selected folder protection)
                            if (remote.isDirectory) {
                                targetLocalFile.mkdirs()
                                newlyCreatedLocalDirsInThisRun.add(path)
                                journalDao.insertOrUpdate(
                                    SyncJournalEntryEntity(
                                        remotePath = path,
                                        localRelativePath = relativeLocalPath,
                                        isDirectory = true,
                                        remoteEtag = remote.etag,
                                        remoteSize = 0L,
                                        remoteMtime = remote.lastModified,
                                        localSize = 0L,
                                        localMtime = targetLocalFile.lastModified(),
                                        fileId = remote.fileId ?: ""
                                    )
                                )
                                repository.logActivity(ActivityType.CREATE_DIR_LOCAL, path, "Created local folder for '${remote.displayName}'")
                            } else {
                                _syncState.value = _syncState.value.copy(currentAction = "Downloading ${targetLocalFile.name}")
                                val etag = if (account.isSimulatedDemo) {
                                    repository.mockServer.downloadFile(path, targetLocalFile).getOrNull() ?: remote.etag
                                } else {
                                    repository.nextcloudClient.downloadFile(
                                        account.serverUrl,
                                        account.username,
                                        account.passwordOrToken,
                                        path,
                                        targetLocalFile,
                                        account.trustAllCerts,
                                        stallTimeout
                                    ).getOrNull() ?: remote.etag
                                }
                                if (remote.lastModified > 0L) {
                                    FileTimeHelper.setLastModified(targetLocalFile, remote.lastModified)
                                }
                                bytesTransferred += targetLocalFile.length()
                                journalDao.insertOrUpdate(
                                    SyncJournalEntryEntity(
                                        remotePath = path,
                                        localRelativePath = relativeLocalPath,
                                        isDirectory = false,
                                        remoteEtag = etag,
                                        remoteSize = targetLocalFile.length(),
                                        remoteMtime = remote.lastModified,
                                        localSize = targetLocalFile.length(),
                                        localMtime = targetLocalFile.lastModified(),
                                        fileId = remote.fileId ?: ""
                                    )
                                )
                                repository.logActivity(
                                    ActivityType.DOWNLOAD,
                                    path,
                                    if (journal != null) "Safeguard: Downloaded Nextcloud file '${targetLocalFile.name}' (re-selected folder protection)"
                                    else "Downloaded new file from Nextcloud",
                                    targetLocalFile.length()
                                )
                            }
                        }
                    } else if (remote == null && local != null) {
                        // Item exists in local, not in remote
                        if (journal != null) {
                            // Was in journal -> Remote was deleted on server!
                            targetLocalFile.deleteRecursively()
                            journalDao.deleteByRemotePath(path)
                            repository.logActivity(ActivityType.DELETE_LOCAL, path, "Deleted local file (deleted on Nextcloud server)")
                        } else {
                            // New local item -> Upload to Nextcloud!
                            if (local.isDirectory) {
                                if (account.isSimulatedDemo) {
                                    repository.mockServer.createDirectory(path)
                                } else {
                                    repository.nextcloudClient.createDirectory(
                                        account.serverUrl,
                                        account.username,
                                        account.passwordOrToken,
                                        path,
                                        account.trustAllCerts
                                    )
                                }
                                // Auto-register new local folder in sync folder config so it syncs automatically
                                val existingFolder = folderDao.getFolder(path)
                                if (existingFolder == null) {
                                    folderDao.insertOrUpdateFolder(
                                        SyncFolderConfigEntity(
                                            remotePath = path,
                                            localRelativePath = relativeLocalPath,
                                            isSelected = true,
                                            displayName = local.name,
                                            isExplicitlyConfigured = true,
                                            remoteSize = 0L,
                                            lastSyncTime = System.currentTimeMillis()
                                        )
                                    )
                                }
                                journalDao.insertOrUpdate(
                                    SyncJournalEntryEntity(
                                        remotePath = path,
                                        localRelativePath = relativeLocalPath,
                                        isDirectory = true,
                                        remoteEtag = "dir_etag_${System.currentTimeMillis()}",
                                        remoteSize = 0L,
                                        remoteMtime = local.lastModified(),
                                        localSize = 0L,
                                        localMtime = local.lastModified(),
                                        fileId = ""
                                    )
                                )
                                repository.logActivity(ActivityType.CREATE_DIR_REMOTE, path, "Created directory on Nextcloud and enabled auto-sync for '$path'")
                            } else {
                                _syncState.value = _syncState.value.copy(currentAction = "Uploading ${local.name}")
                                val etag = if (account.isSimulatedDemo) {
                                    repository.mockServer.uploadFile(path, local, local.lastModified()).getOrNull() ?: "etag_${System.currentTimeMillis()}"
                                } else {
                                    repository.nextcloudClient.uploadFile(
                                        account.serverUrl,
                                        account.username,
                                        account.passwordOrToken,
                                        path,
                                        local,
                                        local.lastModified(),
                                        account.trustAllCerts,
                                        stallTimeout
                                    ).getOrNull() ?: "etag_${System.currentTimeMillis()}"
                                }
                                bytesTransferred += local.length()
                                journalDao.insertOrUpdate(
                                    SyncJournalEntryEntity(
                                        remotePath = path,
                                        localRelativePath = relativeLocalPath,
                                        isDirectory = false,
                                        remoteEtag = etag,
                                        remoteSize = local.length(),
                                        remoteMtime = local.lastModified(),
                                        localSize = local.length(),
                                        localMtime = local.lastModified(),
                                        fileId = ""
                                    )
                                )
                                repository.logActivity(ActivityType.UPLOAD, path, "Uploaded new local file to Nextcloud", local.length())
                            }
                        }
                    }
                } catch (transferEx: Exception) {
                    repository.logActivity(
                        type = ActivityType.ERROR,
                        path = path,
                        message = "Transfer skipped for '$path': ${transferEx.localizedMessage ?: "timeout/network stall"}. Will retry next cycle.",
                        isSuccess = false
                    )
                }

                totalFilesProcessed++
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                val speed = if (elapsedSec > 0) (bytesTransferred / 1024.0) / elapsedSec else 0.0

                _syncState.value = _syncState.value.copy(
                    filesSyncedCount = totalFilesProcessed,
                    bytesTransferred = bytesTransferred,
                    speedKbps = speed
                )
            }

            // 9. Update Quota
            repository.updateQuota()
            val now = System.currentTimeMillis()
            settingsDao.updateLastSyncTimestamp(now)

            val unresolvedConflicts = conflictDao.getUnresolvedConflicts()

            _syncState.value = SyncProgressState(
                status = if (unresolvedConflicts.isNotEmpty()) SyncStatus.CONFLICT else SyncStatus.SUCCESS,
                currentAction = if (unresolvedConflicts.isNotEmpty()) {
                    "Sync finished with ${unresolvedConflicts.size} conflict(s)"
                } else {
                    "All files synchronized"
                },
                currentFile = "",
                filesSyncedCount = totalFilesProcessed,
                totalFilesCount = targetPaths.size,
                bytesTransferred = bytesTransferred,
                lastSyncTimestamp = now
            )

        } catch (e: CancellationException) {
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.IDLE,
                currentAction = "Sync stopped",
                currentFile = ""
            )
        } catch (e: Exception) {
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.ERROR,
                currentAction = "Sync failed: ${e.localizedMessage}",
                errorMessage = e.message
            )
            repository.logActivity(ActivityType.ERROR, "/", "Sync error: ${e.message}", isSuccess = false)
        }
    }

    private suspend fun handleConflictOrResolution(
        path: String,
        relativeLocalPath: String,
        localFile: File,
        remote: WebDavItem,
        account: AccountEntity,
        settings: SyncSettingsEntity
    ) {
        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dotIndex = localFile.name.lastIndexOf('.')
        val conflictName = if (dotIndex != -1) {
            val base = localFile.name.substring(0, dotIndex)
            val ext = localFile.name.substring(dotIndex)
            "$base (conflicted copy $timestampStr)$ext"
        } else {
            "${localFile.name} (conflicted copy $timestampStr)"
        }

        when (settings.conflictStrategy) {
            ConflictStrategy.ASK_USER -> {
                // Record in conflict table & rename local to conflict copy, download remote
                val conflictCopy = File(localFile.parentFile, conflictName)
                val origLocalMtime = localFile.lastModified()
                val origLocalSize = localFile.length()
                localFile.renameTo(conflictCopy)

                val etag = if (account.isSimulatedDemo) {
                    repository.mockServer.downloadFile(path, localFile).getOrNull() ?: remote.etag
                } else {
                    repository.nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        path,
                        localFile,
                        account.trustAllCerts
                    ).getOrNull() ?: remote.etag
                }
                if (remote.lastModified > 0L) {
                    FileTimeHelper.setLastModified(localFile, remote.lastModified)
                }

                conflictDao.insertConflict(
                    ConflictEntity(
                        remotePath = path,
                        localRelativePath = relativeLocalPath,
                        localMtime = origLocalMtime,
                        localSize = origLocalSize,
                        remoteEtag = remote.etag,
                        remoteMtime = remote.lastModified,
                        remoteSize = remote.size,
                        conflictLocalFileName = conflictName,
                        isResolved = false
                    )
                )

                // Store journal entry for the newly downloaded server file so background sync doesn't re-trigger conflict
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = path,
                        localRelativePath = relativeLocalPath,
                        isDirectory = false,
                        remoteEtag = etag,
                        remoteSize = localFile.length(),
                        remoteMtime = remote.lastModified,
                        localSize = localFile.length(),
                        localMtime = localFile.lastModified(),
                        fileId = remote.fileId ?: ""
                    )
                )

                repository.logActivity(
                    ActivityType.CONFLICT_DETECTED,
                    path,
                    "Conflict detected on '$path'. Server copy synced (mtime preserved), local copy preserved as '$conflictName'."
                )
            }
            ConflictStrategy.KEEP_BOTH_RENAME -> {
                val conflictCopy = File(localFile.parentFile, conflictName)
                localFile.renameTo(conflictCopy)
                val etag = if (account.isSimulatedDemo) {
                    repository.mockServer.downloadFile(path, localFile).getOrNull() ?: remote.etag
                } else {
                    repository.nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        path,
                        localFile,
                        account.trustAllCerts
                    ).getOrNull() ?: remote.etag
                }
                if (remote.lastModified > 0L) {
                    FileTimeHelper.setLastModified(localFile, remote.lastModified)
                }
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = path,
                        localRelativePath = relativeLocalPath,
                        isDirectory = false,
                        remoteEtag = etag,
                        remoteSize = localFile.length(),
                        remoteMtime = remote.lastModified,
                        localSize = localFile.length(),
                        localMtime = localFile.lastModified(),
                        fileId = remote.fileId ?: ""
                    )
                )
                repository.logActivity(
                    ActivityType.CONFLICT_RESOLVED,
                    path,
                    "Conflict resolved (Preserved both as '$conflictName')"
                )
            }
            ConflictStrategy.PREFER_LOCAL -> {
                val etag = if (account.isSimulatedDemo) {
                    repository.mockServer.uploadFile(path, localFile, localFile.lastModified()).getOrNull()
                        ?: "etag_${System.currentTimeMillis()}"
                } else {
                    repository.nextcloudClient.uploadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        path,
                        localFile,
                        localFile.lastModified(),
                        account.trustAllCerts
                    ).getOrNull() ?: "etag_${System.currentTimeMillis()}"
                }
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = path,
                        localRelativePath = relativeLocalPath,
                        isDirectory = false,
                        remoteEtag = etag,
                        remoteSize = localFile.length(),
                        remoteMtime = localFile.lastModified(),
                        localSize = localFile.length(),
                        localMtime = localFile.lastModified(),
                        fileId = remote.fileId ?: ""
                    )
                )
                repository.logActivity(ActivityType.CONFLICT_RESOLVED, path, "Conflict resolved (Kept local file)")
            }
            ConflictStrategy.PREFER_REMOTE -> {
                val etag = if (account.isSimulatedDemo) {
                    repository.mockServer.downloadFile(path, localFile).getOrNull() ?: remote.etag
                } else {
                    repository.nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        path,
                        localFile,
                        account.trustAllCerts
                    ).getOrNull() ?: remote.etag
                }
                if (remote.lastModified > 0L) {
                    FileTimeHelper.setLastModified(localFile, remote.lastModified)
                }
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = path,
                        localRelativePath = relativeLocalPath,
                        isDirectory = false,
                        remoteEtag = etag,
                        remoteSize = localFile.length(),
                        remoteMtime = remote.lastModified,
                        localSize = localFile.length(),
                        localMtime = localFile.lastModified(),
                        fileId = remote.fileId ?: ""
                    )
                )
                repository.logActivity(ActivityType.CONFLICT_RESOLVED, path, "Conflict resolved (Kept Nextcloud remote)")
            }
        }
    }

    private fun isTempOrIgnoredFile(file: File, ignoreDotFiles: Boolean): Boolean {
        val name = file.name
        if (name.startsWith(".ncsync_part") || name.contains(".tmp_") || name.contains("(conflicted copy")) {
            return true
        }
        if (ignoreDotFiles && name.startsWith(".")) {
            return true
        }
        return false
    }

    private fun scanLocalDirectory(baseDir: File, enabledFolders: List<SyncFolderConfigEntity>, ignoreDotFiles: Boolean): Map<String, File> {
        val map = mutableMapOf<String, File>()
        if (!baseDir.exists()) return map

        val rootFiles = baseDir.listFiles() ?: emptyArray()
        for (file in rootFiles) {
            if (isTempOrIgnoredFile(file, ignoreDotFiles)) continue
            if (file.isDirectory) {
                val remotePath = "/${file.name}"
                scanRecursive(file, remotePath, map, ignoreDotFiles)
            } else {
                map["/${file.name}"] = file
            }
        }
        return map
    }

    private fun scanRecursive(dir: File, currentRemotePath: String, outMap: MutableMap<String, File>, ignoreDotFiles: Boolean) {
        outMap[currentRemotePath] = dir
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (isTempOrIgnoredFile(child, ignoreDotFiles)) continue
            val childRemotePath = "$currentRemotePath/${child.name}"
            if (child.isDirectory) {
                scanRecursive(child, childRemotePath, outMap, ignoreDotFiles)
            } else {
                outMap[childRemotePath] = child
            }
        }
    }

    suspend fun refreshConflictStatus() {
        val unresolved = conflictDao.getUnresolvedConflicts()
        if (unresolved.isEmpty() && _syncState.value.status == SyncStatus.CONFLICT) {
            _syncState.value = _syncState.value.copy(
                status = SyncStatus.SUCCESS,
                currentAction = "All conflicts resolved • Files synchronized"
            )
        }
    }
}
