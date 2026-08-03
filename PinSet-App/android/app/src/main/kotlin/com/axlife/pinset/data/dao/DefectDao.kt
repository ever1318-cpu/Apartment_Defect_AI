package com.axlife.pinset.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.OnConflictStrategy
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectPhoto
import com.axlife.pinset.data.entity.SyncQueueItem
import kotlinx.coroutines.flow.Flow

@Dao
interface DefectDao {
    @Insert
    suspend fun insert(defect: Defect): Long

    @Insert
    suspend fun insertPhoto(photo: DefectPhoto): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSync(item: SyncQueueItem)

    @Transaction
    suspend fun insertOfflineBundle(
        defect: Defect,
        photos: List<DefectPhoto>,
        operationId: String,
        entityId: String
    ): Long {
        val defectId = insert(defect)
        photos.forEach { insertPhoto(it.copy(defectId = defectId)) }
        upsertSync(
            SyncQueueItem(
                operationId = operationId,
                entityId = entityId,
                localDefectId = defectId
            )
        )
        return defectId
    }

    @Update
    suspend fun update(defect: Defect)

    @Delete
    suspend fun delete(defect: Defect)

    @Query("SELECT * FROM defects WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeBySession(sessionId: Long): Flow<List<Defect>>

    /** The household containing the most recently saved photographed defect. */
    @Query("SELECT s.unitLabel FROM defects d INNER JOIN sessions s ON s.id = d.sessionId ORDER BY d.createdAt DESC LIMIT 1")
    fun latestCapturedHousehold(): Flow<String?>

    @Query("SELECT * FROM defects WHERE id = :id")
    suspend fun getById(id: Long): Defect?

    @Query("SELECT * FROM defects WHERE id = :id")
    fun observeById(id: Long): Flow<Defect?>

    @Query("SELECT COUNT(*) FROM defects WHERE sessionId = :sessionId")
    fun countBySession(sessionId: Long): Flow<Int>
}
