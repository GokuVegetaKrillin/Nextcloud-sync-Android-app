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

    init {
        refreshLocalFiles()
        checkConnection()
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

    fun updateServerAddress(newServerUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isTestingConnection.value = true
            val status = repository.updateServerAddress(newServerUrl)
            _serverConnectionStatus.value = status
            _isTestingConnection.value = false
            if (status.isConnected) {
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
            _isTestingConnection.value = false
            _statusMessage.value = "Account settings saved"
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

    fun refreshLocalFiles() {
        val files = repository.getLocalFiles(_currentLocalSubpath.value)
        _localFiles.value = files
    }

    fun createLocalFile(fileName: String, content: String) {
        repository.createLocalFile(_currentLocalSubpath.value, fileName, content)
        refreshLocalFiles()
        _statusMessage.value = "Created local file: $fileName"
    }

    fun createLocalFolder(folderName: String) {
        repository.createLocalFolder(_currentLocalSubpath.value, folderName)
        refreshLocalFiles()
        _statusMessage.value = "Created local folder: $folderName"
    }

    fun deleteLocalFile(file: File) {
        repository.deleteLocalFile(file)
        refreshLocalFiles()
        _statusMessage.value = "Deleted local item: ${file.name}"
    }

    // Demo/Simulation file creators for testing
    fun createRemoteDemoFile(folder: String, name: String, content: String) {
        val rel = if (folder.isEmpty() || folder == "/") "/$name" else "/$folder/$name"
        repository.mockServer.addRemoteFile(rel, content)
        _statusMessage.value = "Simulated new remote file on Nextcloud: $rel"
    }

    fun createRemoteDemoFolder(folderName: String) {
        repository.mockServer.addRemoteFolder("/$folderName")
        _statusMessage.value = "Simulated new remote folder on Nextcloud: /$folderName"
    }
}
