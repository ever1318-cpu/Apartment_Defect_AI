package com.axlife.pinset.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions", indices = [Index(value = ["amendedFromSessionId"])])
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unitLabel: String,
    val floorplanAssetId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val done: Boolean = false,
    /** 1=초기 점검, 2이상=수정·보완 이력. 원본 세션은 유지된다. */
    @ColumnInfo(defaultValue = "1")
    val revisionNo: Int = 1,
    /** 수정·보완본의 원본 로컬 세션 ID. */
    val amendedFromSessionId: Long? = null,
    /** INITIAL | AMENDMENT. */
    @ColumnInfo(defaultValue = "'INITIAL'")
    val sessionMode: String = "INITIAL",
    /** Floorplan-space entrance anchor (0..1). Set on the very first capture. */
    val startXNorm: Float? = null,
    val startYNorm: Float? = null,
    /** IMU heading at the first capture — the session's "north". */
    val startHeadingDeg: Float? = null,
    /**
     * Absolute path to a user-imported floorplan image (e.g. picked from the
     * phone gallery). When non-null, this takes precedence over
     * [floorplanAssetId] for rendering the floorplan.
     */
    val customFloorplanPath: String? = null,
    /**
     * Entrance anchor photos captured by the user at the very start of the
     * session. Both are optional (session can pre-exist without them); when
     * present, [anchorPhotoNearPath] is the 20x close-up of the unit-number
     * sign and [anchorPhotoFarPath] is the 0.5x wide-context frame.
     * The session is considered "anchored" once these are set AND
     * [startXNorm]/[startYNorm] are non-null.
     */
    val anchorPhotoNearPath: String? = null,
    val anchorPhotoFarPath: String? = null,
    /**
     * User-facing label of where the anchor was captured. Common presets:
     * "현관문", "거실", "베란다", "기타". Free-form text allowed. Home screen
     * banner shows this next to the anchor photo thumbnail.
     */
    val anchorLocationLabel: String = "현관문"
)
