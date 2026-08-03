package com.axlife.pinset.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Physical lens role of the capture stream (kept for future analytics). */
enum class Lens { ULTRA, MAIN, TELE }

/**
 * Presentation slot the user configured for this shot.
 *   A = primary (subject-filling), the reference view (usually 1x)
 *   B = wider context (usually 0.5x)
 *   C = full overview (usually 0.1x — digitally letterboxed if optics can't reach it)
 */
enum class SlotRole { A, B, C }

@Entity(
    tableName = "defect_photos",
    foreignKeys = [ForeignKey(
        entity = Defect::class,
        parentColumns = ["id"],
        childColumns = ["defectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("defectId")]
)
data class DefectPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val defectId: Long,
    val filePath: String,
    val lens: Lens,
    val slot: SlotRole,
    val zoomRatio: Float,       // user-facing zoom label (may be virtual)
    val isDigital: Boolean,     // true if achieved via digital downscale/letterbox
    val isPrimary: Boolean = false
)
