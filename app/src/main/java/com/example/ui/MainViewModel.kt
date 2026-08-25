package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NextcloudApp
import com.example.data.model.*
import com.example.data.repository.ConflictResolution
import com.example.sync.SyncProgressState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

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

    private val _storagePermissionGranted = MutableStateFlow(com.example.util.StoragePermissionHelper.hasStoragePermission(app))
    val storagePermissionGranted: StateFlow<Boolean> = _storagePermissionGranted.asStateFlow()

    init {
        refreshStoragePermissionState()
        refreshLocalFiles()
        checkConnection()
    }

    fun refreshStoragePermissionState() {
        _storagePermissionGranted.value = com.example.util.StoragePermissionHelper.hasStoragePermission(app)
    }

    fun hasStoragePermission(): Boolean {
        val granted = com.example.util.StoragePermissionHelper.hasStoragePermission(app)
        _storagePermissionGranted.value = granted
        return granted
    }

    fun isPathRequiringPermission(path: String): Boolean {
        return com.example.util.StoragePermissionHelper.isExternalPath(path, app)
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
            _isTestingConnection.value = true
            val res = repository.refreshRemoteFolders()
            _isTestingConnection.value = false
            if (res.isSuccess) {
                _statusMessage.value = "Updated: ${res.getOrNull()?.size ?: 0} server folders discovered"
            } else {
                _statusMessage.value = "Folder scan failed: ${res.exceptionOrNull()?.message ?: "Check connection"}"
            }
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
            } else {
                syncScheduler.cancelScheduledSync()
            }
            _statusMessage.value = if (enabled) "Background sync enabled" else "Background sync disabled"
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

    fun resolveConflict(remotePath: String, resolution: ConflictResolution) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resolveConflict(remotePath, resolution)
            refreshLocalFiles()
            _statusMessage.value = "Conflict resolved successfully"
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
