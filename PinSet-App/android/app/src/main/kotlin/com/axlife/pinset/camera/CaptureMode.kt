package com.axlife.pinset.camera

import com.axlife.pinset.data.entity.SlotRole

enum class CaptureMode { SIMULTANEOUS, SEQUENTIAL }

/** A single shot taken with a specific lens for a specific presentation slot. */
data class CapturedShot(
    val filePath: String,
    val slot: SlotRole,
    val lensTag: String,         // "ULTRA" | "MAIN" | "TELE"
    val requestedZoom: Float,    // what the user asked for (may be < physical minimum)
    val effectiveZoom: Float,    // actual zoom after any digital downscale/upscale
    val isDigital: Boolean       // true = downscaled/letterboxed digitally
)

/**
 * Sensor snapshot captured at the same instant as the shutter — the app
 * uses it to auto-classify surface (ceiling/wall/floor) and to attach
 * a world-space anchor-relative position to every defect.
 */
data class CapturePoseSnapshot(
    val imuPitchDeg: Float,
    val imuHeadingDeg: Float,
    val arWorldX: Float?,
    val arWorldY: Float?,
    val arWorldZ: Float?,
    /** Meters to the focused subject, from LENS_FOCUS_DISTANCE. Null on unsupported hw. */
    val focusDistanceM: Float? = null,
    /** Step/PDR position relative to the entrance anchor. */
    val pdrRelXMeters: Float? = null,
    val pdrRelZMeters: Float? = null
) {
    companion object {
        val EMPTY = CapturePoseSnapshot(0f, 0f, null, null, null, null, null, null)
    }
}

data class CaptureResult(
    val shots: List<CapturedShot>,
    val pose: CapturePoseSnapshot = CapturePoseSnapshot.EMPTY,
    /** True when the operator selected the calibrated gap/clearance workflow. */
    val precisionMeasurement: Boolean = false,
    /** A 40 mm printed reference marker was placed in the captured frame. */
    val referenceMarkerCaptured: Boolean = false
) {
    fun forSlot(slot: SlotRole): CapturedShot? = shots.firstOrNull { it.slot == slot }
    val primary: CapturedShot? get() = forSlot(SlotRole.A) ?: shots.firstOrNull()
}

/** Per-slot capture plan handed to the controller. slotC is optional. */
data class CaptureSpec(
    val slotA: Float,
    /** Optional wide-context frame. Null means close-up only. */
    val slotB: Float? = null,
    val slotC: Float? = null
) {
    val slots: List<Pair<SlotRole, Float>>
        get() = buildList {
            add(SlotRole.A to slotA)
            slotB?.let { add(SlotRole.B to it) }
            slotC?.let { add(SlotRole.C to it) }
        }
}
