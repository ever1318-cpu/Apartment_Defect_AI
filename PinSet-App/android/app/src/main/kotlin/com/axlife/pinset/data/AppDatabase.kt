package com.axlife.pinset.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.axlife.pinset.data.dao.DefectDao
import com.axlife.pinset.data.dao.DefectPhotoDao
import com.axlife.pinset.data.dao.SessionDao
import com.axlife.pinset.data.dao.SyncQueueDao
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectPhoto
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.data.entity.SyncQueueItem
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Session::class, Defect::class, DefectPhoto::class, SyncQueueItem::class],
    version = 14,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun defectDao(): DefectDao
    abstract fun defectPhotoDao(): DefectPhotoDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pinset.db"
                ).addMigrations(MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE defects ADD COLUMN measuredGapMm REAL")
                db.execSQL("ALTER TABLE defects ADD COLUMN measurementMethod TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE defects ADD COLUMN measurementStatus TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN revisionNo INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sessions ADD COLUMN amendedFromSessionId INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN sessionMode TEXT NOT NULL DEFAULT 'INITIAL'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_amendedFromSessionId ON sessions(amendedFromSessionId)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_queue (
                        operationId TEXT NOT NULL PRIMARY KEY,
                        entityId TEXT NOT NULL,
                        localDefectId INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        nextAttemptAt INTEGER NOT NULL,
                        lastError TEXT,
                        serverRevision INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(localDefectId) REFERENCES defects(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_queue_localDefectId ON sync_queue(localDefectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_state_nextAttemptAt ON sync_queue(state, nextAttemptAt)")
            }
        }
    }
}
