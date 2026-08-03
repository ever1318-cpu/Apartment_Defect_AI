package com.axlife.pinset.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DefectType { CRACK, LEAK, FINISH, OTHER }
enum class Severity { MINOR, NORMAL, MAJOR }
enum class PinSource { AUTO, MANUAL }
enum class Trade { WALL, WALLPAPER, TILE, FLOOR, WINDOW, ELECTRIC, PLUMBING, OTHER }
enum class Surface { CEILING, WALL, FLOOR }
enum class DefectStatus { PENDING, DONE }

@Entity(
    tableName = "defects",
    foreignKeys = [ForeignKey(
        entity = Session::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class Defect(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val roomId: String,
    val roomLabel: String,
    val xNorm: Float,
    val yNorm: Float,
    val defectType: DefectType,
    val severity: Severity,
    val trade: Trade = Trade.WALL,
    val surface: Surface = Surface.WALL,
    val areaDetail: String = "",
    val defectIndex: Int = 0,
    val note: String = "",
    val source: PinSource,
    val confidence: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val status: DefectStatus = DefectStatus.PENDING,
    // Sensor snapshot at capture time — used to recover the surface (ceiling
    // vs wall vs floor) and to auto-classify the room later via AR pose.
    val imuPitchDeg: Float = 0f,
    val imuHeadingDeg: Float = 0f,
    val arWorldX: Float? = null,
    val arWorldY: Float? = null,
    val arWorldZ: Float? = null,
    /** Approximate distance to the focused subject in meters, from
     *  Camera2 LENS_FOCUS_DISTANCE at capture time. Null when the sensor
     *  reports UNCALIBRATED or the value can't be read. */
    val focusDistanceM: Float? = null,
    /** Physical gap result from a reference card / feeler gauge, in millimetres. */
    val measuredGapMm: Float? = null,
    val measurementMethod: String = "",
    val measurementStatus: String = "",
    // ----- 3-Source classification (see design v1.0) --------------------
    /** Free-form resident opinion (voice-to-text or typed). */
    val residentOpinion: String = "",
    /** AI assistant's suggested catalog path, e.g. "주방발코니 > 벽 > 도장 > 흠집".
     *  Currently rule-based stub; LLM / on-device ML swap-in later. */
    val aiPathText: String = "",
    /** Confidence of the AI classifier, 0..1. */
    val aiConfidence: Float = 0f,
    /** Final confirmed catalog path (user-chosen or overridden). This is the
     *  authoritative "부위 및 원인" string shown in list rows and reports. */
    val finalPathText: String = ""
)
