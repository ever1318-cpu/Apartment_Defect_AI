package com.axlife.pinset.vision

/**
 * Maps an ARCore-derived world position (x, z in meters, relative to the
 * user-set anchor) onto a normalized floorplan position and the enclosing
 * room label.
 *
 * The mapping requires a calibration: how many normalized floorplan units
 * correspond to one AR meter, and the anchor's location on the floorplan.
 * We accept both as configuration and produce (xNorm, yNorm, roomId).
 *
 * If the walked distance falls outside every room bbox, we still return the
 * clamped coordinate but with a null roomId so the caller can fall back to
 * the user's manual selection.
 */
data class FloorplanCalibration(
    /** Anchor position on the floorplan (0..1 normalized). */
    val anchorXNorm: Float,
    val anchorYNorm: Float,
    /** Meters that map to 1.0 in the floorplan's X (horizontal) axis. */
    val metersPerNormX: Float,
    /** Meters that map to 1.0 in the floorplan's Y (vertical) axis. */
    val metersPerNormY: Float,
    /**
     * Rotation (radians) to apply to the AR (x, z) so that the resulting
     * (x', z') aligns with the floorplan axes. 0 = AR +X points right on the
     * floorplan and AR +Z points down. Positive rotates counter-clockwise.
     */
    val rotationRad: Float = 0f
)

object PoseToRoom {

    /** Convert a relative AR pose (x, z in meters) into normalized floorplan coords. */
    fun toFloorplan(relX: Float, relZ: Float, cal: FloorplanCalibration): Pair<Float, Float> {
        // Rotate to align AR with floorplan.
        val cos = kotlin.math.cos(cal.rotationRad)
        val sin = kotlin.math.sin(cal.rotationRad)
        val rx = relX * cos - relZ * sin
        val rz = relX * sin + relZ * cos
        val x = (cal.anchorXNorm + rx / cal.metersPerNormX).coerceIn(0f, 1f)
        val y = (cal.anchorYNorm + rz / cal.metersPerNormY).coerceIn(0f, 1f)
        return x to y
    }

    /** Which floorplan room contains this normalized point, if any. */
    fun roomAt(xNorm: Float, yNorm: Float, meta: FloorplanMeta): FloorplanRoomAnchor? {
        return meta.rooms.firstOrNull { r ->
            val b = r.bbox
            if (b.size < 4) return@firstOrNull false
            xNorm in b[0]..b[2] && yNorm in b[1]..b[3]
        }
    }

    /**
     * Default calibration for the bundled 101동 1502호 floorplan. Reasonable
     * starting values for a mid-sized apartment (roughly 12m × 9m interior).
     * User can override via a future calibration UI.
     */
    /** Ordered inspection route beginning at the entrance and rotating
     * clockwise. It is a navigation hint; measured AR/PDR position wins. */
    fun clockwiseRoute(meta: FloorplanMeta): List<FloorplanRoomAnchor> {
        val entrance = meta.entrance ?: return meta.rooms
        return meta.rooms.filterNot { it.id == "entrance" }.sortedBy { room ->
            val dx = room.cx - entrance.cx
            val dy = room.cy - entrance.cy
            ((kotlin.math.atan2(dx, -dy) * 180.0 / Math.PI) + 360.0) % 360.0
        }
    }
    val DEFAULT_101_1502 = FloorplanCalibration(
        anchorXNorm = 0.32f,      // roughly the entrance area on this floorplan
        anchorYNorm = 0.72f,
        metersPerNormX = 12.0f,   // 12 m across the whole floorplan width
        metersPerNormY = 9.0f     // 9 m top-to-bottom
    )
}

