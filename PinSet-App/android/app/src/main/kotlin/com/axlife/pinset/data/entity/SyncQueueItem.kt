package com.axlife.pinset.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

object SyncState {
    const val PENDING = "PENDING"
    const val UPLOADING = "UPLOADING"
    const val RETRY = "RETRY"
    const val COMPLETED = "COMPLETED"
    const val CONFLICT = "CONFLICT"
}

@Entity(
    tableName = "sync_queue",
    foreignKeys = [ForeignKey(
        entity = Defect::class,
        parentColumns = ["id"],
        childColumns = ["localDefectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["localDefectId"], unique = true),
        Index(value = ["state", "nextAttemptAt"])
    ]
)
data class SyncQueueItem(
    @PrimaryKey val operationId: String = UUID.randomUUID().toString(),
    val entityId: String = UUID.randomUUID().toString(),
    val localDefectId: Long,
    val state: String = SyncState.PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
    val serverRevision: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
