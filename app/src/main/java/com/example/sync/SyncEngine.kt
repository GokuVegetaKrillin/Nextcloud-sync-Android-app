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

            // Add root-level files and enabled root-level folders only
            for (item in rootRemoteItems) {
                if (item.path.isNotEmpty() && item.path != "/") {
                    if (settings.ignoreDotFilesAndFolders && item.displayName.startsWith(".")) continue
                    if (item.isDirectory) {
                        // Crucial: Only index root directory if it is enabled for synchronization!
                        if (enabledFolders.any { it.remotePath == item.path }) {
                            allRemoteItemsMap[item.path] = item
                        }
                    } else {
                        // Genuine root-level file (e.g. /Readme.md)
                        allRemoteItemsMap[item.path] = item
                    }
                }
            }

            // Index inside enabled folders
            for (folder in enabledFolders) {
                if (!isActive) break
                val remoteItems = mutableListOf<WebDavItem>()
                if (account.isSimulatedDemo) {
                    remoteItems.addAll(repository.mockServer.listFolder(folder.remotePath, depth = 10))
                } else {
                    // Traverse with WebDAV standard depth=1 to avoid 400 Bad Request or server limits
                    val dirQueue = ArrayDeque<String>()
                    dirQueue.add(folder.remotePath)
                    var dirScans = 0
                    while (dirQueue.isNotEmpty() && dirScans < 200) {
                        val currentDir = dirQueue.removeFirst()
                        dirScans++
                        val res = repository.nextcloudClient.listFolder(
                            account.serverUrl,
                            account.username,
                            account.passwordOrToken,
                            currentDir,
                            depth = 1,
                            account.trustAllCerts
                        )
                        val items = res.getOrDefault(emptyList())
                        for (item in items) {
                            val itemClean = "/" + item.path.trim('/')
                            val currentClean = "/" + currentDir.trim('/')
                            if (itemClean == currentClean || item.path.isEmpty() || item.path == "/") continue
                            remoteItems.add(item)
                            if (item.isDirectory) {
                                dirQueue.add(item.path)
                            }
                        }
                    }
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

            // Filter paths belonging to enabled folders (or subdirectories) or genuine root level files
            // Folders that are not selected for synchronization must NEVER be created or processed!
            val targetPaths = allPaths.filter { path ->
                // 1. Strictly exclude any path belonging to or inside an unselected folder
                val belongsToUnselected = unselectedFolders.any { unselected ->
                    path == unselected.remotePath || path.startsWith("${unselected.remotePath}/")
                }
                if (belongsToUnselected) {
                    return@filter false
                }

                // 2. Identify if this path represents a folder
                val isDirectory = allRemoteItemsMap[path]?.isDirectory == true ||
                    localFilesMap[path]?.isDirectory == true ||
                    journalMap[path]?.isDirectory == true ||
                    currentFolders.any { it.remotePath == path }

                if (isDirectory) {
                    // Folders must explicitly be selected or be inside an enabled folder
                    enabledFolders.any { folder ->
                        path == folder.remotePath || path.startsWith("${folder.remotePath}/")
                    }
                } else {
                    // Files: must either belong to an enabled folder or be a genuine root-level file (e.g. /notes.txt)
                    val belongsToSelected = enabledFolders.any { folder ->
                        path.startsWith("${folder.remotePath}/")
                    }
                    val isRootFile = !path.removePrefix("/").contains("/") && currentFolders.none { it.remotePath == path }
                    isRootFile || belongsToSelected
                }
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
            val locallyDeletedDirsInThisRun = mutableSetOf<String>()

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
                                    remoteEtag = cleanEtag(remote.etag),
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
                            val remoteCleanEtag = cleanEtag(remote.etag)
                            val journalCleanEtag = cleanEtag(journal?.remoteEtag)
                            val remoteModified = journal != null && (
                                if (remoteCleanEtag.isNotEmpty() && journalCleanEtag.isNotEmpty()) {
                                    remoteCleanEtag != journalCleanEtag
                                } else {
                                    remote.size != journal.remoteSize || Math.abs(remote.lastModified - journal.remoteMtime) > 2000L
                                }
                            )

                            if (journal == null) {
                                // File exists on both sides but never synced before
                                if (local.length() == remote.size && Math.abs(local.lastModified() - remote.lastModified) < 3000) {
                                    // Match: adopt
                                    journalDao.insertOrUpdate(
                                        SyncJournalEntryEntity(
                                            remotePath = path,
                                            localRelativePath = relativeLocalPath,
                                            isDirectory = false,
                                            remoteEtag = cleanEtag(remote.etag),
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
                                    repository.mockServer.uploadFile(path, local, local.lastModified()).getOrNull()?.ifBlank { null } ?: "etag_${System.currentTimeMillis()}"
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
                                    ).getOrNull()?.ifBlank { null } ?: "etag_${System.currentTimeMillis()}"
                                }
                                bytesTransferred += local.length()
                                journalDao.insertOrUpdate(
                                    SyncJournalEntryEntity(
                                        remotePath = path,
                                        localRelativePath = relativeLocalPath,
                                        isDirectory = false,
                                        remoteEtag = cleanEtag(etag),
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
                                val downloadedEtag = if (account.isSimulatedDemo) {
                                    repository.mockServer.downloadFile(path, targetLocalFile).getOrNull()?.ifBlank { null } ?: remote.etag
                                } else {
                                    repository.nextcloudClient.downloadFile(
                                        account.serverUrl,
                                        account.username,
                                        account.passwordOrToken,
                                        path,
                                        targetLocalFile,
                                        account.trustAllCerts,
                                        stallTimeout
                                    ).getOrNull()?.ifBlank { null } ?: remote.etag
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
                                        remoteEtag = cleanEtag(downloadedEtag).ifBlank { cleanEtag(remote.etag) },
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
                        
                        // Check if an ancestor directory was already confirmed deleted in this sync run
                        val isAncestorLocallyDeleted = locallyDeletedDirsInThisRun.any { dirPath ->
                            path.startsWith("$dirPath/")
                        }

                        if (isAncestorLocallyDeleted) {
                            // Ancestor folder was already deleted on Nextcloud in this run
                            // Clean up journal entry for this child without redundant re-download or deletion request
                            journalDao.deleteByRemotePath(path)
                        } else if (journal != null) {
                            // The file/folder was previously synced and recorded in the database (journal != null),
                            // but no longer exists on the local file system (local == null).
                            // This means it was deleted locally!
                            val remoteCleanEtag = cleanEtag(remote.etag)
                            val journalCleanEtag = cleanEtag(journal.remoteEtag)

                            // Check if the remote file was also modified on the server after the last sync
                            val remoteModifiedOnServer = !remote.isDirectory && (
                                remote.lastModified > journal.remoteMtime + 2000L &&
                                remoteCleanEtag.isNotEmpty() &&
                                journalCleanEtag.isNotEmpty() &&
                                remoteCleanEtag != journalCleanEtag
                            )

                            val shouldDeleteOnServer = if (remoteModifiedOnServer) {
                                when (settings.conflictStrategy) {
                                    ConflictStrategy.PREFER_LOCAL -> true
                                    ConflictStrategy.PREFER_REMOTE -> false
                                    else -> false // On conflict with ASK_USER / KEEP_BOTH_RENAME, safeguard download
                                }
                            } else {
                                true
                            }

                            if (shouldDeleteOnServer) {
                                _syncState.value = _syncState.value.copy(currentAction = "Propagating deletion to Nextcloud...")
                                val deleteResult = if (account.isSimulatedDemo) {
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

                                if (deleteResult.isSuccess) {
                                    journalDao.deleteByRemotePath(path)
                                    if (remote.isDirectory) {
                                        locallyDeletedDirsInThisRun.add(path)
                                        journalDao.deleteByPathPrefix(path)
                                    }
                                    repository.logActivity(
                                        ActivityType.DELETE_LOCAL,
                                        path,
                                        "Detected local deletion of ${if (remote.isDirectory) "folder" else "file"} '$path'"
                                    )
                                    repository.logActivity(
                                        ActivityType.DELETE_REMOTE,
                                        path,
                                        "Deleted ${if (remote.isDirectory) "folder" else "file"} from Nextcloud (deleted locally): '$path'"
                                    )
                                } else {
                                    val errorDetail = deleteResult.exceptionOrNull()?.message ?: "Server rejected deletion"
                                    repository.logActivity(
                                        ActivityType.ERROR,
                                        path,
                                        "Failed to delete ${if (remote.isDirectory) "folder" else "file"} '$path' from Nextcloud: $errorDetail. Journal entry preserved to retry on next sync."
                                    )
                                }
                            } else {
                                // Safeguard download if remote was modified on server and server wins
                                if (remote.isDirectory) {
                                    targetLocalFile.mkdirs()
                                    newlyCreatedLocalDirsInThisRun.add(path)
                                    journalDao.insertOrUpdate(
                                        SyncJournalEntryEntity(
                                            remotePath = path,
                                            localRelativePath = relativeLocalPath,
                                            isDirectory = true,
                                            remoteEtag = cleanEtag(remote.etag),
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
                                    val downloadedEtag = if (account.isSimulatedDemo) {
                                        repository.mockServer.downloadFile(path, targetLocalFile).getOrNull()?.ifBlank { null } ?: remote.etag
                                    } else {
                                        repository.nextcloudClient.downloadFile(
                                            account.serverUrl,
                                            account.username,
                                            account.passwordOrToken,
                                            path,
                                            targetLocalFile,
                                            account.trustAllCerts,
                                            stallTimeout
                                        ).getOrNull()?.ifBlank { null } ?: remote.etag
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
                                            remoteEtag = cleanEtag(downloadedEtag).ifBlank { cleanEtag(remote.etag) },
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
                                        "Safeguard: Downloaded Nextcloud file '${targetLocalFile.name}' (remote modified on server)",
                                        targetLocalFile.length()
                                    )
                                }
                            }
                        } else {
                            // journal == null: Genuine new remote item never synced to this client before
                            if (remote.isDirectory) {
                                targetLocalFile.mkdirs()
                                newlyCreatedLocalDirsInThisRun.add(path)
                                journalDao.insertOrUpdate(
                                    SyncJournalEntryEntity(
                                        remotePath = path,
                                        localRelativePath = relativeLocalPath,
                                        isDirectory = true,
                                        remoteEtag = cleanEtag(remote.etag),
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
                                val downloadedEtag = if (account.isSimulatedDemo) {
                                    repository.mockServer.downloadFile(path, targetLocalFile).getOrNull()?.ifBlank { null } ?: remote.etag
                                } else {
                                    repository.nextcloudClient.downloadFile(
                                        account.serverUrl,
                                        account.username,
                                        account.passwordOrToken,
                                        path,
                                        targetLocalFile,
                                        account.trustAllCerts,
                                        stallTimeout
                                    ).getOrNull()?.ifBlank { null } ?: remote.etag
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
                                        remoteEtag = cleanEtag(downloadedEtag).ifBlank { cleanEtag(remote.etag) },
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
                                    "Downloaded new file from Nextcloud",
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
                            if (local.isDirectory) {
                                journalDao.deleteByPathPrefix(path)
                            }
                            repository.logActivity(
                                ActivityType.DELETE_REMOTE,
                                path,
                                "Detected remote deletion of ${if (local.isDirectory) "folder" else "file"} '$path' on Nextcloud"
                            )
                            repository.logActivity(
                                ActivityType.DELETE_LOCAL,
                                path,
                                "Deleted local ${if (local.isDirectory) "folder" else "file"} '$path' (deleted on Nextcloud server)"
                            )
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

                val downloadedEtag = if (account.isSimulatedDemo) {
                    repository.mockServer.downloadFile(path, localFile).getOrNull()?.ifBlank { null } ?: remote.etag
                } else {
                    repository.nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        path,
                        localFile,
                        account.trustAllCerts
                    ).getOrNull()?.ifBlank { null } ?: remote.etag
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
                        remoteEtag = cleanEtag(remote.etag),
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
                        remoteEtag = cleanEtag(downloadedEtag).ifBlank { cleanEtag(remote.etag) },
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
                val downloadedEtag = if (account.isSimulatedDemo) {
                    repository.mockServer.downloadFile(path, localFile).getOrNull()?.ifBlank { null } ?: remote.etag
                } else {
                    repository.nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        path,
                        localFile,
                        account.trustAllCerts
                    ).getOrNull()?.ifBlank { null } ?: remote.etag
                }
                if (remote.lastModified > 0L) {
                    FileTimeHelper.setLastModified(localFile, remote.lastModified)
                }
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = path,
                        localRelativePath = relativeLocalPath,
                        isDirectory = false,
                        remoteEtag = cleanEtag(downloadedEtag).ifBlank { cleanEtag(remote.etag) },
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
                    repository.mockServer.uploadFile(path, localFile, localFile.lastModified()).getOrNull()?.ifBlank { null }
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
                    ).getOrNull()?.ifBlank { null } ?: "etag_${System.currentTimeMillis()}"
                }
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = path,
                        localRelativePath = relativeLocalPath,
                        isDirectory = false,
                        remoteEtag = cleanEtag(etag),
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
                val downloadedEtag = if (account.isSimulatedDemo) {
                    repository.mockServer.downloadFile(path, localFile).getOrNull()?.ifBlank { null } ?: remote.etag
                } else {
                    repository.nextcloudClient.downloadFile(
                        account.serverUrl,
                        account.username,
                        account.passwordOrToken,
                        path,
                        localFile,
                        account.trustAllCerts
                    ).getOrNull()?.ifBlank { null } ?: remote.etag
                }
                if (remote.lastModified > 0L) {
                    FileTimeHelper.setLastModified(localFile, remote.lastModified)
                }
                journalDao.insertOrUpdate(
                    SyncJournalEntryEntity(
                        remotePath = path,
                        localRelativePath = relativeLocalPath,
                        isDirectory = false,
                        remoteEtag = cleanEtag(downloadedEtag).ifBlank { cleanEtag(remote.etag) },
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
                // Only scan local folder if it is selected for sync or contains selected folders
                val isFolderEnabledOrAncestor = enabledFolders.any {
                    it.remotePath == remotePath || it.remotePath.startsWith("$remotePath/") || remotePath.startsWith("${it.remotePath}/")
                }
                if (isFolderEnabledOrAncestor) {
                    scanRecursive(file, remotePath, map, ignoreDotFiles, enabledFolders)
                }
            } else {
                map["/${file.name}"] = file
            }
        }
        return map
    }

    private fun scanRecursive(
        dir: File,
        currentRemotePath: String,
        outMap: MutableMap<String, File>,
        ignoreDotFiles: Boolean,
        enabledFolders: List<SyncFolderConfigEntity>
    ) {
        val isEnabled = enabledFolders.any {
            it.remotePath == currentRemotePath || it.remotePath.startsWith("$currentRemotePath/") || currentRemotePath.startsWith("${it.remotePath}/")
        }
        if (!isEnabled) return

        outMap[currentRemotePath] = dir
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (isTempOrIgnoredFile(child, ignoreDotFiles)) continue
            val childRemotePath = "$currentRemotePath/${child.name}"
            if (child.isDirectory) {
                scanRecursive(child, childRemotePath, outMap, ignoreDotFiles, enabledFolders)
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

    private fun cleanEtag(etag: String?): String {
        if (etag.isNullOrBlank()) return ""
        return etag.trim()
            .removePrefix("W/")
            .removePrefix("w/")
            .removeSurrounding("\"")
            .trim()
    }
}
