package com.axlife.pinset.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.axlife.pinset.data.entity.SyncQueueItem
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(item: SyncQueueItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(item: SyncQueueItem)

    @Query("""
        SELECT id FROM defects
        WHERE id NOT IN (SELECT localDefectId FROM sync_queue)
    """)
    suspend fun defectIdsWithoutQueue(): List<Long>

    @Query("SELECT * FROM sync_queue WHERE localDefectId = :defectId LIMIT 1")
    fun observeForDefect(defectId: Long): Flow<SyncQueueItem?>

    @Query("SELECT * FROM sync_queue WHERE localDefectId = :defectId LIMIT 1")
    suspend fun getForDefect(defectId: Long): SyncQueueItem?

    @Query("SELECT COUNT(*) FROM sync_queue WHERE state <> 'COMPLETED'")
    suspend fun countOutstanding(): Int

    @Query("""
        SELECT COUNT(*) FROM sync_queue q
        INNER JOIN defects d ON d.id = q.localDefectId
        WHERE d.sessionId = :sessionId AND q.state <> 'COMPLETED'
    """)
    suspend fun countIncompleteForSession(sessionId: Long): Int

    @Query("""
        SELECT * FROM sync_queue
        WHERE state IN ('PENDING', 'RETRY') AND nextAttemptAt <= :now
        ORDER BY createdAt ASC LIMIT :limit
    """)
    suspend fun due(now: Long, limit: Int = 20): List<SyncQueueItem>

    @Query("""
        UPDATE sync_queue SET state = 'UPLOADING', updatedAt = :now, lastError = NULL
        WHERE operationId = :operationId
    """)
    suspend fun markUploading(operationId: String, now: Long)

    @Query("""
        UPDATE sync_queue SET state = 'COMPLETED', serverRevision = :revision,
        updatedAt = :now, lastError = NULL WHERE operationId = :operationId
    """)
    suspend fun markCompleted(operationId: String, revision: Int?, now: Long)

    @Query("""
        UPDATE sync_queue SET state = 'RETRY', attemptCount = attemptCount + 1,
        nextAttemptAt = :nextAttemptAt, lastError = :error, updatedAt = :now
        WHERE operationId = :operationId
    """)
    suspend fun markRetry(
        operationId: String,
        error: String,
        nextAttemptAt: Long,
        now: Long
    )

    @Query("""
        UPDATE sync_queue SET state = 'CONFLICT', attemptCount = attemptCount + 1,
        lastError = :error, updatedAt = :now WHERE operationId = :operationId
    """)
    suspend fun markConflict(operationId: String, error: String, now: Long)

    @Query("UPDATE sync_queue SET state = 'PENDING', nextAttemptAt = 0, lastError = NULL WHERE localDefectId = :defectId")
    suspend fun requestRetry(defectId: Long)

    /** A fresh app start or restored network is a new delivery opportunity. */
    @Query("UPDATE sync_queue SET nextAttemptAt = 0 WHERE state = 'RETRY'")
    suspend fun makeRetriesDue()

    /** The process may be terminated while an HTTP request is in progress. */
    @Query("""
        UPDATE sync_queue SET state = 'PENDING', nextAttemptAt = 0,
        lastError = 'Previous upload was interrupted; retry scheduled', updatedAt = :now
        WHERE state = 'UPLOADING'
    """)
    suspend fun recoverInterruptedUploads(now: Long)

    @Update
    suspend fun update(item: SyncQueueItem)
}
