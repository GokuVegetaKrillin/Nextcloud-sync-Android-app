package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncStatus {
    IDLE,
    SYNCING,
    PAUSED,
    SUCCESS,
    CONFLICT,
    ERROR
}

enum class SyncIntervalUnit {
    MINUTES,
    HOURS,
    DAYS
}

enum class ConflictStrategy {
    ASK_USER,
    KEEP_BOTH_RENAME,
    PREFER_REMOTE,
    PREFER_LOCAL
}

enum class ActivityType {
    DOWNLOAD,
    UPLOAD,
    DELETE_LOCAL,
    DELETE_REMOTE,
    CREATE_DIR_LOCAL,
    CREATE_DIR_REMOTE,
    CONFLICT_DETECTED,
    CONFLICT_RESOLVED,
    ERROR,
    INFO
}

data class WebDavItem(
    val href: String,
    val path: String, // e.g. "/Documents/report.pdf"
    val displayName: String,
    val isDirectory: Boolean,
    val size: Long,
    val etag: String,
    val lastModified: Long, // timestamp ms
    val fileId: String? = null,
    val permissions: String? = null
)

data class WebDavQuota(
    val usedBytes: Long,
    val availableBytes: Long,
    val totalBytes: Long
)

data class ServerStatus(
    val installed: Boolean = true,
    val maintenance: Boolean = false,
    val version: String = "Nextcloud Hub (30.0.0)",
    val productname: String = "Nextcloud",
    val isConnected: Boolean = true,
    val responseTimeMs: Long = 0,
    val errorMessage: String? = null
)

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: Int = 1,
    val serverUrl: String,
    val username: String,
    val passwordOrToken: String,
    val displayName: String = "",
    val email: String = "",
    val serverVersion: String = "Nextcloud 30.0",
    val quotaTotalBytes: Long = 100L * 1024 * 1024 * 1024, // 100 GB default
    val quotaUsedBytes: Long = 12L * 1024 * 1024 * 1024,  // 12 GB default
    val trustAllCerts: Boolean = true,
    val isSimulatedDemo: Boolean = false,
    val lastConnectedTime: Long = 0L,
    val isActive: Boolean = true
)

@Entity(tableName = "sync_folders")
data class SyncFolderConfigEntity(
    @PrimaryKey val remotePath: String, // e.g. "/Documents", "/Photos", or "/" for root
    val localRelativePath: String,     // relative to app's sync base directory
    val isSelected: Boolean = true,     // whether this folder is synchronized
    val displayName: String,
    val isExplicitlyConfigured: Boolean = true, // true if user manually set it, false if auto-discovered
    val remoteSize: Long = 0L,
    val remoteItemCount: Int = 0,
    val lastSyncTime: Long = 0L,
    val lastSyncStatus: String = "IDLE"
)

@Entity(tableName = "sync_journal")
data class SyncJournalEntryEntity(
    @PrimaryKey val remotePath: String,
    val localRelativePath: String,
    val isDirectory: Boolean,
    val remoteEtag: String,
    val remoteSize: Long,
    val remoteMtime: Long,
    val localSize: Long,
    val localMtime: Long,
    val fileId: String = "",
    val lastSyncedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_activities")
data class SyncActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: ActivityType,
    val path: String,
    val fileSize: Long = 0L,
    val message: String,
    val isSuccess: Boolean = true
)

@Entity(tableName = "sync_conflicts")
data class ConflictEntity(
    @PrimaryKey val remotePath: String,
    val localRelativePath: String,
    val localMtime: Long,
    val localSize: Long,
    val remoteEtag: String,
    val remoteMtime: Long,
    val remoteSize: Long,
    val detectedAt: Long = System.currentTimeMillis(),
    val conflictLocalFileName: String = "",
    val isResolved: Boolean = false
)

@Entity(tableName = "sync_settings")
data class SyncSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val isSyncEnabled: Boolean = true, // Master toggle to enable/disable synchronization for entire account/app
    val syncIntervalValue: Int = 15,
    val syncIntervalUnit: SyncIntervalUnit = SyncIntervalUnit.MINUTES,
    val syncNewFoldersByDefault: Boolean = true, // Whether new folders added to Nextcloud sync automatically
    val ignoreDotFilesAndFolders: Boolean = true, // Setting to ignore files and folders proceeded by a dot (e.g. .thumbnails)
    val transferStallTimeoutSeconds: Int = 300, // No progress timeout (5 minutes = 300s) to skip stalled transfers
    val runInBackground: Boolean = true,
    val syncOnWifiOnly: Boolean = false,
    val syncOnChargingOnly: Boolean = false,
    val conflictStrategy: ConflictStrategy = ConflictStrategy.ASK_USER,
    val maxSyncFileSizeMb: Int = 500,
    val notifyOnSyncComplete: Boolean = true,
    val lastManualSyncTimestamp: Long = 0L,
    val nextScheduledSyncTimestamp: Long = 0L,
    val customLocalSyncPath: String = "" // Custom local folder path (empty means default app storage)
)
