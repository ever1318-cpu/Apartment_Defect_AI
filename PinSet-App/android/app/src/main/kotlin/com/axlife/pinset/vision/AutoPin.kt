package com.axlife.pinset.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Given the session's start anchor on the floorplan (0..1 normalized) and the
 * capture's compass heading + subject distance, compute the estimated defect
 * position on the floorplan.
 *
 * Convention:
 *   - Floorplan top edge  = magnetic north (heading 0°)
 *   - Heading grows clockwise (east = 90°, south = 180°, west = 270°)
 *   - Y grows downward on the floorplan (image coordinates)
 *
 * @param startX normalized (0..1) x of the entrance / first-shot anchor
 * @param startY normalized (0..1) y of the anchor
 * @param headingDeg compass heading of the camera at capture
 * @param subjectDistM meters between camera and the subject (LENS_FOCUS_DISTANCE)
 * @param metersPerNormX floorplan horizontal extent in meters (default ≈ 12 m)
 * @param metersPerNormY floorplan vertical extent in meters   (default ≈  9 m)
 */
object AutoPin {

    fun estimate(
        startX: Float,
        startY: Float,
        headingDeg: Float,
        subjectDistM: Float,
        metersPerNormX: Float = 12f,
        metersPerNormY: Float = 9f
    ): Pair<Float, Float> {
        val rad = headingDeg.toDouble() * PI / 180.0
        // Displacement in meters along floorplan axes.
        val dxMeters = subjectDistM * sin(rad).toFloat()   // east positive
        val dyMeters = -subjectDistM * cos(rad).toFloat()  // north negative-y
        val x = (startX + dxMeters / metersPerNormX).coerceIn(0f, 1f)
        val y = (startY + dyMeters / metersPerNormY).coerceIn(0f, 1f)
        return x to y
    }
}
