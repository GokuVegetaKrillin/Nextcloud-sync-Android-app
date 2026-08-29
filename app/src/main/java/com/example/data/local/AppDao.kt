package com.example.data.local

import androidx.room.*
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM account WHERE id = 1 LIMIT 1")
    fun getAccountFlow(): Flow<AccountEntity?>

    @Query("SELECT * FROM account WHERE id = 1 LIMIT 1")
    suspend fun getAccount(): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAccount(account: AccountEntity)

    @Query("UPDATE account SET serverUrl = :serverUrl WHERE id = 1")
    suspend fun updateServerUrl(serverUrl: String)

    @Query("UPDATE account SET quotaUsedBytes = :used, quotaTotalBytes = :total WHERE id = 1")
    suspend fun updateQuota(used: Long, total: Long)

    @Query("DELETE FROM account")
    suspend fun deleteAccount()
}

@Dao
interface SyncFolderDao {
    @Query("SELECT * FROM sync_folders ORDER BY remotePath ASC")
    fun getAllFoldersFlow(): Flow<List<SyncFolderConfigEntity>>

    @Query("SELECT * FROM sync_folders ORDER BY remotePath ASC")
    suspend fun getAllFolders(): List<SyncFolderConfigEntity>

    @Query("SELECT * FROM sync_folders WHERE remotePath = :remotePath LIMIT 1")
    suspend fun getFolder(remotePath: String): SyncFolderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateFolder(folder: SyncFolderConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateFolders(folders: List<SyncFolderConfigEntity>)

    @Query("UPDATE sync_folders SET isSelected = :isSelected WHERE remotePath = :remotePath")
    suspend fun updateFolderSelection(remotePath: String, isSelected: Boolean)

    @Query("UPDATE sync_folders SET isSelected = :isSelected WHERE remotePath = :remotePath OR remotePath LIKE :remotePath || '/%'")
    suspend fun updateFolderAndSubfoldersSelection(remotePath: String, isSelected: Boolean)

    @Query("DELETE FROM sync_folders WHERE remotePath = :remotePath")
    suspend fun deleteFolder(remotePath: String)

    @Query("DELETE FROM sync_folders WHERE remotePath = :remotePath OR remotePath LIKE :remotePath || '/%'")
    suspend fun deleteFolderAndSubfolders(remotePath: String)

    @Query("DELETE FROM sync_folders")
    suspend fun deleteAllFolders()
}

@Dao
interface SyncJournalDao {
    @Query("SELECT * FROM sync_journal")
    suspend fun getAllJournalEntries(): List<SyncJournalEntryEntity>

    @Query("SELECT * FROM sync_journal WHERE remotePath = :remotePath LIMIT 1")
    suspend fun getJournalEntry(remotePath: String): SyncJournalEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entry: SyncJournalEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SyncJournalEntryEntity>)

    @Query("DELETE FROM sync_journal WHERE remotePath = :remotePath")
    suspend fun deleteByRemotePath(remotePath: String)

    @Query("DELETE FROM sync_journal WHERE remotePath = :pathPrefix OR remotePath LIKE :pathPrefix || '/%'")
    suspend fun deleteByPathPrefix(pathPrefix: String)

    @Query("DELETE FROM sync_journal")
    suspend fun clearJournal()
}

@Dao
interface SyncActivityDao {
    @Query("SELECT * FROM sync_activities ORDER BY timestamp DESC LIMIT 150")
    fun getRecentActivitiesFlow(): Flow<List<SyncActivityEntity>>

    @Query("SELECT * FROM sync_activities ORDER BY timestamp DESC LIMIT 500")
    suspend fun getAllActivities(): List<SyncActivityEntity>

    @Insert
    suspend fun insertActivity(activity: SyncActivityEntity)

    @Query("DELETE FROM sync_activities")
    suspend fun clearAllActivities()
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM sync_conflicts WHERE isResolved = 0 ORDER BY detectedAt DESC")
    fun getUnresolvedConflictsFlow(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM sync_conflicts WHERE isResolved = 0")
    suspend fun getUnresolvedConflicts(): List<ConflictEntity>

    @Query("SELECT * FROM sync_conflicts WHERE remotePath = :remotePath LIMIT 1")
    suspend fun getConflict(remotePath: String): ConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConflict(conflict: ConflictEntity)

    @Query("UPDATE sync_conflicts SET isResolved = 1 WHERE remotePath = :remotePath")
    suspend fun markResolved(remotePath: String)

    @Query("DELETE FROM sync_conflicts WHERE remotePath = :remotePath")
    suspend fun deleteConflict(remotePath: String)

    @Query("DELETE FROM sync_conflicts WHERE remotePath = :pathPrefix OR remotePath LIKE :pathPrefix || '/%'")
    suspend fun deleteConflictsByPathPrefix(pathPrefix: String)

    @Query("DELETE FROM sync_conflicts")
    suspend fun clearAllConflicts()
}

@Dao
interface SyncSettingsDao {
    @Query("SELECT * FROM sync_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<SyncSettingsEntity?>

    @Query("SELECT * FROM sync_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): SyncSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSettings(settings: SyncSettingsEntity)

    @Query("UPDATE sync_settings SET isSyncEnabled = :enabled WHERE id = 1")
    suspend fun updateSyncEnabled(enabled: Boolean)

    @Query("UPDATE sync_settings SET ignoreDotFilesAndFolders = :ignore WHERE id = 1")
    suspend fun updateIgnoreDotFiles(ignore: Boolean)

    @Query("UPDATE sync_settings SET transferStallTimeoutSeconds = :timeoutSeconds WHERE id = 1")
    suspend fun updateStallTimeout(timeoutSeconds: Int)

    @Query("UPDATE sync_settings SET syncIntervalValue = :value, syncIntervalUnit = :unit WHERE id = 1")
    suspend fun updateInterval(value: Int, unit: SyncIntervalUnit)

    @Query("UPDATE sync_settings SET syncNewFoldersByDefault = :syncNewByDefault WHERE id = 1")
    suspend fun updateSyncNewFoldersByDefault(syncNewByDefault: Boolean)

    @Query("UPDATE sync_settings SET syncOnMobileData = :enabled, syncOnWifiOnly = NOT :enabled WHERE id = 1")
    suspend fun updateSyncOnMobileData(enabled: Boolean)

    @Query("UPDATE sync_settings SET syncOnWifiOnly = :wifiOnly, syncOnMobileData = NOT :wifiOnly WHERE id = 1")
    suspend fun updateSyncOnWifiOnly(wifiOnly: Boolean)

    @Query("UPDATE sync_settings SET runInBackground = :enabled WHERE id = 1")
    suspend fun updateRunInBackground(enabled: Boolean)

    @Query("UPDATE sync_settings SET customLocalSyncPath = :path WHERE id = 1")
    suspend fun updateCustomLocalSyncPath(path: String)

    @Query("UPDATE sync_settings SET lazyLoadSubfolders = :enabled WHERE id = 1")
    suspend fun updateLazyLoadSubfolders(enabled: Boolean)

    @Query("UPDATE sync_settings SET nextScheduledSyncTimestamp = :timestamp WHERE id = 1")
    suspend fun updateNextScheduledSync(timestamp: Long)

    @Query("UPDATE sync_settings SET lastManualSyncTimestamp = :timestamp WHERE id = 1")
    suspend fun updateLastSyncTimestamp(timestamp: Long)
}
