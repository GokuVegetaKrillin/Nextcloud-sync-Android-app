package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.model.*

class Converters {
    @TypeConverter
    fun fromSyncIntervalUnit(unit: SyncIntervalUnit): String = unit.name

    @TypeConverter
    fun toSyncIntervalUnit(value: String): SyncIntervalUnit =
        try { SyncIntervalUnit.valueOf(value) } catch (e: Exception) { SyncIntervalUnit.MINUTES }

    @TypeConverter
    fun fromConflictStrategy(strategy: ConflictStrategy): String = strategy.name

    @TypeConverter
    fun toConflictStrategy(value: String): ConflictStrategy =
        try { ConflictStrategy.valueOf(value) } catch (e: Exception) { ConflictStrategy.ASK_USER }

    @TypeConverter
    fun fromActivityType(type: ActivityType): String = type.name

    @TypeConverter
    fun toActivityType(value: String): ActivityType =
        try { ActivityType.valueOf(value) } catch (e: Exception) { ActivityType.INFO }
}

@Database(
    entities = [
        AccountEntity::class,
        SyncFolderConfigEntity::class,
        SyncJournalEntryEntity::class,
        SyncActivityEntity::class,
        ConflictEntity::class,
        SyncSettingsEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun syncFolderDao(): SyncFolderDao
    abstract fun syncJournalDao(): SyncJournalDao
    abstract fun syncActivityDao(): SyncActivityDao
    abstract fun conflictDao(): ConflictDao
    abstract fun syncSettingsDao(): SyncSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nextcloud_sync.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
