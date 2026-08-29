package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NextcloudApp
import com.example.data.model.*
import com.example.data.repository.ConflictResolution
import com.example.sync.SyncProgressState
import com.example.sync.service.NextcloudSyncForegroundService
import com.example.util.BatteryOptimizationHelper
import com.example.util.NetworkHelper
import com.example.util.NetworkType
import com.example.util.StoragePermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

enum class FolderRefreshStatus {
    IDLE,
    REFRESHING,
    SUCCESS,
    ERROR
}

data class FolderRefreshState(
    val isRefreshing: Boolean = false,
    val status: FolderRefreshStatus = FolderRefreshStatus.IDLE,
    val message: String? = null,
    val lastRefreshedTime: Long = 0L,
    val initialLoadCompleted: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NextcloudApp
    private val repository = app.repository
    private val syncEngine = app.syncEngine
    private val syncScheduler = app.syncScheduler

    val syncState: StateFlow<SyncProgressState> = syncEngine.syncState

    val account: StateFlow<AccountEntity?> = repository.accountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val folders: StateFlow<List<SyncFolderConfigEntity>> = repository.foldersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activities: StateFlow<List<SyncActivityEntity>> = repository.activitiesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conflicts: StateFlow<List<ConflictEntity>> = repository.conflictsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<SyncSettingsEntity?> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _folderRefreshState = MutableStateFlow(FolderRefreshState())
    val folderRefreshState: StateFlow<FolderRefreshState> = _folderRefreshState.asStateFlow()

    private val _loadingFolderPaths = MutableStateFlow<Set<String>>(emptySet())
    val loadingFolderPaths: StateFlow<Set<String>> = _loadingFolderPaths.asStateFlow()

    private val _scannedFolderPaths = MutableStateFlow<Set<String>>(emptySet())
    val scannedFolderPaths: StateFlow<Set<String>> = _scannedFolderPaths.asStateFlow()

    private val _currentLocalSubpath = MutableStateFlow("")
    val currentLocalSubpath: StateFlow<String> = _currentLocalSubpath.asStateFlow()

    private val _localFiles = MutableStateFlow<List<File>>(emptyList())
    val localFiles: StateFlow<List<File>> = _localFiles.asStateFlow()

    private val _serverConnectionStatus = MutableStateFlow<ServerStatus?>(null)
    val serverConnectionStatus: StateFlow<ServerStatus?> = _serverConnectionStatus.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _storagePermissionGranted = MutableStateFlow(StoragePermissionHelper.hasStoragePermission(app))
    val storagePermissionGranted: StateFlow<Boolean> = _storagePermissionGranted.asStateFlow()

    private val _batteryOptimizationIgnored = MutableStateFlow(BatteryOptimizationHelper.isBatteryOptimizationIgnored(app))
    val batteryOptimizationIgnored: StateFlow<Boolean> = _batteryOptimizationIgnored.asStateFlow()

    private val _notificationPermissionGranted = MutableStateFlow(BatteryOptimizationHelper.isNotificationPermissionGranted(app))
    val notificationPermissionGranted: StateFlow<Boolean> = _notificationPermissionGranted.asStateFlow()

    private val _exactAlarmAllowed = MutableStateFlow(BatteryOptimizationHelper.canScheduleExactAlarms(app))
    val exactAlarmAllowed: StateFlow<Boolean> = _exactAlarmAllowed.asStateFlow()

    private val _resolvingConflicts = MutableStateFlow<Set<String>>(emptySet())
    val resolvingConflicts: StateFlow<Set<String>> = _resolvingConflicts.asStateFlow()

    private val _networkType = MutableStateFlow(NetworkHelper.getNetworkType(app))
    val networkType: StateFlow<NetworkType> = _networkType.asStateFlow()

    init {
        refreshAllSystemStates()
        refreshLocalFiles()
        checkConnection()
        refreshRemoteFolders()
    }

    fun refreshAllSystemStates() {
        _storagePermissionGranted.value = StoragePermissionHelper.hasStoragePermission(app)
        _batteryOptimizationIgnored.value = BatteryOptimizationHelper.isBatteryOptimizationIgnored(app)
        _notificationPermissionGranted.value = BatteryOptimizationHelper.isNotificationPermissionGranted(app)
        _exactAlarmAllowed.value = BatteryOptimizationHelper.canScheduleExactAlarms(app)
        _networkType.value = NetworkHelper.getNetworkType(app)
    }

    fun refreshStoragePermissionState() {
        refreshAllSystemStates()
    }

    fun hasStoragePermission(): Boolean {
        val granted = StoragePermissionHelper.hasStoragePermission(app)
        _storagePermissionGranted.value = granted
        return granted
    }

    fun isPathRequiringPermission(path: String): Boolean {
        return StoragePermissionHelper.isExternalPath(path, app)
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun checkConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            val acc = repository.getAccount() ?: return@launch
            _isTestingConnection.value = true
            val status = repository.testConnection(
                serverUrl = acc.serverUrl,
                username = acc.username,
                pass = acc.passwordOrToken,
                trustAll = acc.trustAllCerts,
                isDemo = acc.isSimulatedDemo
            )
            _serverConnectionStatus.value = status
            _isTestingConnection.value = false
        }
    }

    fun refreshRemoteFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            _folderRefreshState.update {
                it.copy(
                    isRefreshing = true,
                    status = FolderRefreshStatus.REFRESHING,
                    message = "Scanning folders from Nextcloud server..."
                )
            }
            _isTestingConnection.value = true
            val res = repository.refreshRemoteFolders()
            _isTestingConnection.value = false
            if (res.isSuccess) {
                val folderCount = res.getOrNull()?.size ?: 0
                _folderRefreshState.update {
                    it.copy(
                        isRefreshing = false,
                        status = FolderRefreshStatus.SUCCESS,
                        message = "Folders refreshed successfully ($folderCount server folders)",
                        lastRefreshedTime = System.currentTimeMillis(),
                        initialLoadCompleted = true
                    )
                }
                _statusMessage.value = "Updated: $folderCount server folders discovered"
            } else {
                val errorMsg = res.exceptionOrNull()?.message ?: "Check connection"
                _folderRefreshState.update {
                    it.copy(
                        isRefreshing = false,
                        status = FolderRefreshStatus.ERROR,
                        message = "Refresh failed: $errorMsg",
                        initialLoadCompleted = folders.value.isNotEmpty()
                    )
                }
                _statusMessage.value = "Folder scan failed: $errorMsg"
            }
        }
    }

    fun fetchSubfoldersForPath(remotePath: String) {
        val cleanPath = "/" + remotePath.trim('/')
        if (_loadingFolderPaths.value.contains(cleanPath)) return

        viewModelScope.launch(Dispatchers.IO) {
            _loadingFolderPaths.update { it + cleanPath }
            val result = repository.fetchSubfolders(cleanPath)
            _scannedFolderPaths.update { it + cleanPath }
            _loadingFolderPaths.update { it - cleanPath }

            if (result.isSuccess) {
                val count = result.getOrNull()?.size ?: 0
                if (count > 0) {
                    _statusMessage.value = "Discovered $count subfolders in $cleanPath"
                }
            } else {
                _statusMessage.value = "Failed to load subfolders for $cleanPath: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun setLazyLoadSubfolders(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateLazyLoadSubfolders(enabled)
            _statusMessage.value = if (enabled) {
                "On-demand subfolder discovery enabled (faster)"
            } else {
                "Full recursive folder scanning enabled"
            }
            refreshRemoteFolders()
        }
    }

    fun startSync() {
        syncEngine.startSync(isManual = true)
        refreshLocalFiles()
    }

    fun pauseSync() {
        syncEngine.pauseSync()
    }

    fun resumeSync() {
        syncEngine.resumeSync()
    }

    fun cancelSync() {
        syncEngine.cancelSync()
    }

    fun toggleFolderSelection(remotePath: String, isSelected: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateFolderSelection(remotePath, isSelected)
            _statusMessage.value = "Updated sync for $remotePath: ${if (isSelected) "Synchronized" else "Excluded"}"
        }
    }

    fun setSyncNewFoldersByDefault(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateSyncNewFoldersByDefault(enabled)
            _statusMessage.value = if (enabled) {
                "New Nextcloud folders will be synchronized automatically"
            } else {
                "New Nextcloud folders will NOT be synchronized by default"
            }
        }
    }

    fun setSyncInterval(value: Int, unit: SyncIntervalUnit) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateSyncInterval(value, unit)
            syncScheduler.scheduleNextSync()
            _statusMessage.value = "Sync interval set to $value ${unit.name.lowercase()}"
        }
    }

    fun updateRunInBackground(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateRunInBackground(enabled)
            if (enabled) {
                syncScheduler.scheduleNextSync()
                try {
                    NextcloudSyncForegroundService.startPersistentDaemon(app)
                } catch (e: Exception) {
                    // Ignore foreground service startup exception
                }
                _statusMessage.value = "Persistent background sync daemon enabled"
            } else {
                syncScheduler.cancelScheduledSync()
                _statusMessage.value = "Background sync disabled"
            }
        }
    }

    fun startPersistentForegroundDaemon() {
        try {
            NextcloudSyncForegroundService.startPersistentDaemon(app)
            _statusMessage.value = "Background sync keep-alive service started"
        } catch (e: Exception) {
            _statusMessage.value = "Failed to start service: ${e.message}"
        }
    }

    fun getSharedStorageNextcloudPath(): String {
        val ext = android.os.Environment.getExternalStorageDirectory()
        return File(ext, "Nextcloud").absolutePath
    }

    fun getDefaultInternalPath(): String {
        return File(app.filesDir, "Nextcloud").absolutePath
    }

    fun getExternalDocumentsPath(): String {
        val ext = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
        return File(ext, "Nextcloud").absolutePath
    }

    fun getExternalDownloadPath(): String {
        val ext = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        return File(ext, "Nextcloud").absolutePath
    }

    fun getEffectiveLocalSyncPath(): String {
        val custom = settings.value?.customLocalSyncPath?.trim()
        return if (!custom.isNullOrEmpty()) custom else getDefaultInternalPath()
    }

    fun updateCustomLocalSyncPath(newPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val clean = newPath.trim()
            repository.updateCustomLocalSyncPath(clean)
            if (clean.isNotEmpty()) {
                try {
                    val dir = File(clean)
                    if (!dir.exists()) dir.mkdirs()
                } catch (e: Exception) {
                    // Handled gracefully
                }
            }
            refreshLocalFiles()
            refreshStoragePermissionState()
            _statusMessage.value = if (clean.isBlank()) "Local storage path reset to default"
                                   else "Local storage path set to: $clean"
        }
    }

    fun updateServerAddress(newServerUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isTestingConnection.value = true
            val status = repository.updateServerAddress(newServerUrl)
            _serverConnectionStatus.value = status
            _isTestingConnection.value = false
            if (status.isConnected) {
                repository.refreshRemoteFolders()
                _statusMessage.value = "Server address updated successfully ($newServerUrl)"
            } else {
                _statusMessage.value = "Address saved, but connection failed: ${status.errorMessage}"
            }
        }
    }

    fun updateAccountCredentials(
        serverUrl: String,
        username: String,
        passwordOrToken: String,
        displayName: String,
        trustAll: Boolean,
        isDemo: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isTestingConnection.value = true
            val current = repository.getAccount()
            val updated = (current ?: AccountEntity(serverUrl = serverUrl, username = username, passwordOrToken = passwordOrToken)).copy(
                serverUrl = serverUrl,
                username = username,
                passwordOrToken = passwordOrToken,
                displayName = displayName,
                trustAllCerts = trustAll,
                isSimulatedDemo = isDemo
            )
            repository.saveAccount(updated)
            val status = repository.testConnection(serverUrl, username, passwordOrToken, trustAll, isDemo)
            _serverConnectionStatus.value = status

            if (status.isConnected) {
                val refreshRes = repository.refreshRemoteFolders()
                if (refreshRes.isSuccess) {
                    _statusMessage.value = "Server connected! Discovered ${refreshRes.getOrNull()?.size ?: 0} folders."
                } else {
                    _statusMessage.value = "Connected to Nextcloud. Folder scan: ${refreshRes.exceptionOrNull()?.message ?: "Check WebDAV permissions"}"
                }
            } else {
                _statusMessage.value = "Credentials saved (Connection test: ${status.errorMessage ?: "Unreachable"})"
            }
            _isTestingConnection.value = false
        }
    }

    fun updateSyncEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateSyncEnabled(enabled)
            if (enabled) {
                syncScheduler.scheduleNextSync()
                _statusMessage.value = "Master synchronization enabled"
            } else {
                syncScheduler.cancelScheduledSync()
                _statusMessage.value = "Master synchronization paused"
            }
        }
    }

    fun updateIgnoreDotFiles(ignore: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateIgnoreDotFiles(ignore)
            _statusMessage.value = if (ignore) {
                "Hidden dot-files/folders (.thumbnails, etc.) will be ignored"
            } else {
                "Dot-files will be included in synchronization"
            }
        }
    }

    fun updateSyncOnMobileData(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateSyncOnMobileData(enabled)
            _networkType.value = NetworkHelper.getNetworkType(app)
            _statusMessage.value = if (enabled) {
                "Mobile data synchronization ENABLED"
            } else {
                "Mobile data synchronization DISABLED (Wi-Fi only)"
            }
        }
    }

    fun addCustomFolder(remotePath: String, isSelected: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = repository.addFolder(remotePath, isSelected)
            _statusMessage.value = "Added folder '${folder.remotePath}' to sync configuration"
        }
    }

    fun removeFolder(remotePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteFolder(remotePath)
            _statusMessage.value = "Removed folder '$remotePath' from sync configuration"
        }
    }

    fun updateStallTimeout(timeoutSeconds: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val safeSec = timeoutSeconds.coerceAtLeast(30)
            repository.updateStallTimeout(safeSec)
            _statusMessage.value = "Transfer stall timeout set to $safeSec seconds (${safeSec / 60}m)"
        }
    }

    fun resolveConflict(remotePath: String, resolution: ConflictResolution) {
        viewModelScope.launch(Dispatchers.IO) {
            _resolvingConflicts.update { it + remotePath }
            try {
                repository.resolveConflict(remotePath, resolution)
                syncEngine.refreshConflictStatus()
                refreshLocalFiles()
                _statusMessage.value = "Conflict resolved successfully ($remotePath)"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to resolve conflict: ${e.message}"
            } finally {
                _resolvingConflicts.update { it - remotePath }
            }
        }
    }

    suspend fun exportSettingsJson(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val acc = repository.getAccount()
        val sett = repository.getSettings()
        val folderList = repository.getAllFolders()

        val root = org.json.JSONObject()
        root.put("version", 2)
        root.put("exportTimestamp", System.currentTimeMillis())

        if (acc != null) {
            val accObj = org.json.JSONObject()
            accObj.put("serverUrl", acc.serverUrl)
            accObj.put("username", acc.username)
            accObj.put("passwordOrToken", acc.passwordOrToken)
            accObj.put("displayName", acc.displayName)
            accObj.put("trustAllCerts", acc.trustAllCerts)
            accObj.put("isSimulatedDemo", acc.isSimulatedDemo)
            root.put("account", accObj)
        }

        if (sett != null) {
            val settObj = org.json.JSONObject()
            settObj.put("isSyncEnabled", sett.isSyncEnabled)
            settObj.put("syncIntervalValue", sett.syncIntervalValue)
            settObj.put("syncIntervalUnit", sett.syncIntervalUnit.name)
            settObj.put("syncNewFoldersByDefault", sett.syncNewFoldersByDefault)
            settObj.put("runInBackground", sett.runInBackground)
            settObj.put("syncOnMobileData", sett.syncOnMobileData)
            settObj.put("syncOnWifiOnly", sett.syncOnWifiOnly)
            settObj.put("customLocalSyncPath", sett.customLocalSyncPath)
            settObj.put("ignoreDotFilesAndFolders", sett.ignoreDotFilesAndFolders)
            settObj.put("transferStallTimeoutSeconds", sett.transferStallTimeoutSeconds)
            settObj.put("conflictStrategy", sett.conflictStrategy.name)
            root.put("settings", settObj)
        }

        val folderArray = org.json.JSONArray()
        for (f in folderList) {
            val fObj = org.json.JSONObject()
            fObj.put("remotePath", f.remotePath)
            fObj.put("localRelativePath", f.localRelativePath)
            fObj.put("displayName", f.displayName)
            fObj.put("isSelected", f.isSelected)
            folderArray.put(fObj)
        }
        root.put("folders", folderArray)

        root.toString(2)
    }

    suspend fun importSettingsJson(jsonStr: String): Result<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val root = org.json.JSONObject(jsonStr.trim())

            if (root.has("account")) {
                val accObj = root.getJSONObject("account")
                val acc = AccountEntity(
                    serverUrl = accObj.optString("serverUrl", "https://cloud.example.com"),
                    username = accObj.optString("username", "admin"),
                    passwordOrToken = accObj.optString("passwordOrToken", ""),
                    displayName = accObj.optString("displayName", "Nextcloud User"),
                    trustAllCerts = accObj.optBoolean("trustAllCerts", true),
                    isSimulatedDemo = accObj.optBoolean("isSimulatedDemo", true)
                )
                repository.saveAccount(acc)
            }

            if (root.has("settings")) {
                val settObj = root.getJSONObject("settings")
                val unitName = settObj.optString("syncIntervalUnit", SyncIntervalUnit.MINUTES.name)
                val unit = try { SyncIntervalUnit.valueOf(unitName) } catch (_: Exception) { SyncIntervalUnit.MINUTES }
                val stratName = settObj.optString("conflictStrategy", ConflictStrategy.ASK_USER.name)
                val strat = try { ConflictStrategy.valueOf(stratName) } catch (_: Exception) { ConflictStrategy.ASK_USER }

                val sett = SyncSettingsEntity(
                    id = 1,
                    isSyncEnabled = settObj.optBoolean("isSyncEnabled", true),
                    syncIntervalValue = settObj.optInt("syncIntervalValue", 15),
                    syncIntervalUnit = unit,
                    syncNewFoldersByDefault = settObj.optBoolean("syncNewFoldersByDefault", true),
                    runInBackground = settObj.optBoolean("runInBackground", true),
                    syncOnMobileData = settObj.optBoolean("syncOnMobileData", true),
                    syncOnWifiOnly = settObj.optBoolean("syncOnWifiOnly", false),
                    customLocalSyncPath = settObj.optString("customLocalSyncPath", ""),
                    ignoreDotFilesAndFolders = settObj.optBoolean("ignoreDotFilesAndFolders", true),
                    transferStallTimeoutSeconds = settObj.optInt("transferStallTimeoutSeconds", 300),
                    conflictStrategy = strat
                )
                repository.updateSettings(sett)
            }

            if (root.has("folders")) {
                val folderArray = root.getJSONArray("folders")
                for (i in 0 until folderArray.length()) {
                    val fObj = folderArray.getJSONObject(i)
                    val remotePath = fObj.optString("remotePath", "")
                    val isSelected = fObj.optBoolean("isSelected", true)
                    if (remotePath.isNotEmpty()) {
                        repository.updateFolderSelection(remotePath, isSelected)
                    }
                }
            }

            refreshLocalFiles()
            checkConnection()
            _statusMessage.value = "Settings imported successfully"
            Result.success("Settings imported successfully")
        } catch (e: Exception) {
            _statusMessage.value = "Failed to import settings: ${e.message}"
            Result.failure(e)
        }
    }

    fun resetJournal() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resetSyncJournal()
            _statusMessage.value = "Sync journal cleared. Next sync will re-index."
        }
    }

    fun clearActivities() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearActivities()
        }
    }

    // Local file manager helpers
    fun navigateToSubpath(subpath: String) {
        _currentLocalSubpath.value = subpath
        refreshLocalFiles()
    }

    fun navigateUp(): Boolean {
        val current = _currentLocalSubpath.value
        if (current.isEmpty()) return false
        val parent = current.trimEnd('/').substringBeforeLast('/', "")
        _currentLocalSubpath.value = parent
        refreshLocalFiles()
        return true
    }

    fun refreshLocalFiles() {
        _localFiles.value = repository.getLocalFiles(_currentLocalSubpath.value)
    }

    fun createLocalFile(name: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.createLocalFile(_currentLocalSubpath.value, name, content)
            refreshLocalFiles()
            _statusMessage.value = "Created local file: $name"
        }
    }

    fun createLocalFolder(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.createLocalFolder(_currentLocalSubpath.value, name)
            refreshLocalFiles()
            _statusMessage.value = "Created local folder: $name"
        }
    }

    fun createRemoteDemoFolder(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val acc = repository.getAccount()
            if (acc?.isSimulatedDemo == true) {
                repository.mockServer.addRemoteFolder("/$name")
            } else if (acc != null) {
                repository.nextcloudClient.createDirectory(
                    acc.serverUrl,
                    acc.username,
                    acc.passwordOrToken,
                    "/$name",
                    acc.trustAllCerts
                )
            }
            repository.refreshRemoteFolders()
        }
    }

    fun createRemoteDemoFile(folder: String, fileName: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanFolder = if (folder.startsWith("/")) folder else "/$folder"
            val path = if (cleanFolder == "/") "/$fileName" else "$cleanFolder/$fileName"
            val acc = repository.getAccount()
            if (acc?.isSimulatedDemo == true) {
                repository.mockServer.addRemoteFile(path, content)
                _statusMessage.value = "Added file to demo server: $path"
            } else if (acc != null) {
                val tempFile = File.createTempFile("nc_upload", ".tmp")
                tempFile.writeText(content)
                val res = repository.nextcloudClient.uploadFile(
                    acc.serverUrl,
                    acc.username,
                    acc.passwordOrToken,
                    path,
                    tempFile,
                    trustAll = acc.trustAllCerts
                )
                tempFile.delete()
                if (res.isSuccess) {
                    _statusMessage.value = "Created file on server: $path"
                } else {
                    _statusMessage.value = "Failed to create file on server: ${res.exceptionOrNull()?.message}"
                }
            }
        }
    }

    fun deleteLocalFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteLocalFile(file)
            refreshLocalFiles()
            _statusMessage.value = "Deleted local file: ${file.name}"
        }
    }
}
