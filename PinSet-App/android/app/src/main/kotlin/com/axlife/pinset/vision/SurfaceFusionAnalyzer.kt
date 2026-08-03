package com.axlife.pinset.vision

import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.max

/**
 * Combines the shutter-time IMU pitch with lightweight image geometry.
 *
 * The image pass intentionally runs entirely on-device: it looks for strong
 * horizontal boundaries in the upper/lower third of a downsampled photo.
 * Those boundaries are useful evidence for ceiling-wall and wall-floor
 * junctions, while the IMU remains the primary signal for a flat plane.
 */
data class SurfaceFusionResult(
    val band: CaptureSurfaceBand,
    val confidence: Float,
    val imuBand: CaptureSurfaceBand,
    val imageBand: CaptureSurfaceBand?,
    val upperBoundaryScore: Float,
    val lowerBoundaryScore: Float,
    /** Normalized Y of the selected evidence line; null for a flat plane. */
    val boundaryYNorm: Float?
)

object SurfaceFusionAnalyzer {
    fun analyze(photoPath: String?, pitchDeg: Float): SurfaceFusionResult {
        val imuBand = captureSurfaceBandFromPitch(pitchDeg)
        val evidence = photoPath?.takeIf { it.isNotBlank() }?.let(::boundaryEvidence)
        if (evidence == null) {
            return SurfaceFusionResult(
                band = imuBand,
                confidence = 0.58f,
                imuBand = imuBand,
                imageBand = null,
                upperBoundaryScore = 0f,
                lowerBoundaryScore = 0f,
                boundaryYNorm = null
            )
        }

        val imageBand = when {
            evidence.upperBoundary >= 0.18f && evidence.upperBoundary > evidence.lowerBoundary * 1.15f ->
                CaptureSurfaceBand.CEILING_WALL
            evidence.lowerBoundary >= 0.18f && evidence.lowerBoundary > evidence.upperBoundary * 1.15f ->
                CaptureSurfaceBand.WALL_FLOOR
            else -> null
        }
        val boundaryYNorm = when (imageBand) {
            CaptureSurfaceBand.CEILING_WALL -> evidence.upperYNorm
            CaptureSurfaceBand.WALL_FLOOR -> evidence.lowerYNorm
            else -> null
        }
        val nearLevel = abs(pitchDeg) < 52f
        val fused = if (nearLevel && imageBand != null) imageBand else imuBand
        val agreement = imageBand == null || imageBand == imuBand
        val confidence = when {
            agreement && imageBand != null -> 0.88f
            imageBand != null && fused == imageBand -> 0.78f
            else -> 0.68f
        }
        return SurfaceFusionResult(
            band = fused,
            confidence = confidence,
            imuBand = imuBand,
            imageBand = imageBand,
            upperBoundaryScore = evidence.upperBoundary,
            lowerBoundaryScore = evidence.lowerBoundary,
            boundaryYNorm = boundaryYNorm
        )
    }

    private data class BoundaryEvidence(
        val upperBoundary: Float,
        val lowerBoundary: Float,
        val upperYNorm: Float,
        val lowerYNorm: Float
    )

    private fun boundaryEvidence(path: String): BoundaryEvidence? {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 8 || height < 8) return null
            val rowEnergy = FloatArray(height)
            var samplesPerRow = 0
            for (y in 1 until height step 2) {
                var energy = 0f
                var count = 0
                for (x in 0 until width step 2) {
                    val above = luminance(bitmap.getPixel(x, y - 1))
                    val below = luminance(bitmap.getPixel(x, y))
                    energy += abs(below - above)
                    count++
                }
                if (count > 0) {
                    rowEnergy[y] = energy / count
                    samplesPerRow++
                }
            }
            if (samplesPerRow == 0) return null
            val normalized = rowEnergy.map { it / 255f }
            val upperRange = (height / 8)..max(height / 8, height / 3)
            val upperY = upperRange.maxByOrNull { normalized[it] } ?: height / 4
            val upper = normalized[upperY]
            val lowerStart = max(height * 2 / 3, 1)
            val lowerRange = lowerStart until height
            val lowerY = lowerRange.maxByOrNull { normalized[it] } ?: height * 3 / 4
            val lower = normalized[lowerY]
            BoundaryEvidence(upper, lower, upperY.toFloat() / height, lowerY.toFloat() / height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun luminance(color: Int): Float =
        ((color shr 16 and 0xff) * 0.299f) + ((color shr 8 and 0xff) * 0.587f) + ((color and 0xff) * 0.114f)
}
