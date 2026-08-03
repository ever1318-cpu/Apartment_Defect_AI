package com.axlife.pinset.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight room matcher using downsampled color histograms.
 * NOT a substitute for ORB feature matching — MVP-grade recall only.
 * Swap in an OpenCV-backed implementation once the AAR is bundled.
 */
class RoomMatcher(private val db: ReferenceDb) {

    data class MatchResult(
        val roomId: String?,
        val roomLabel: String?,
        val confidence: Float,   // 0..1
        val topCandidates: List<Pair<String, Float>>
    )

    private val minConfidence = 0.55f
    private val cachedRefHists = mutableMapOf<String, FloatArray>()

    fun matchFromPath(photoPath: String): MatchResult {
        val bmp = BitmapFactory.decodeFile(photoPath) ?: return MatchResult(null, null, 0f, emptyList())
        return match(bmp)
    }

    fun match(bitmap: Bitmap): MatchResult {
        val queryHist = colorHistogram(bitmap)
        val entries = db.index()
        if (entries.isEmpty()) {
            return MatchResult(null, null, 0f, emptyList())
        }
        val scoresByRoom = mutableMapOf<String, Pair<String, MutableList<Float>>>() // roomId -> (label, [scores])
        for (entry in entries) {
            val hist = cachedRefHists.getOrPut(entry.file) {
                val ref = db.loadReferenceBitmap(entry) ?: return@getOrPut FloatArray(0)
                colorHistogram(ref)
            }
            if (hist.isEmpty()) continue
            val score = cosineSimilarity(queryHist, hist)
            val bucket = scoresByRoom.getOrPut(entry.roomId) { entry.roomLabel to mutableListOf() }
            // Upgrade label if the previously stored one was blank.
            if (bucket.first.isBlank() && entry.roomLabel.isNotBlank()) {
                scoresByRoom[entry.roomId] = entry.roomLabel to bucket.second
            }
            bucket.second.add(score)
        }
        if (scoresByRoom.isEmpty()) return MatchResult(null, null, 0f, emptyList())

        val roomBest = scoresByRoom.mapValues { (_, v) -> v.second.maxOrNull() ?: 0f }
        val ranked = roomBest.entries.sortedByDescending { it.value }
        val topLabelById = scoresByRoom.mapValues { it.value.first }
        val (bestId, bestScore) = ranked.first()
        val candidates = ranked.take(3).map { topLabelById[it.key]!! to it.value }
        val bestLabel = topLabelById[bestId]!!
        return if (bestScore >= minConfidence) {
            MatchResult(bestId, bestLabel, bestScore, candidates)
        } else {
            MatchResult(null, null, bestScore, candidates)
        }
    }

    // ---- histogram ----
    private val bins = 4    // 4^3 = 64 bins across RGB

    private fun colorHistogram(bmp: Bitmap): FloatArray {
        val step = max(1, bmp.width / 64)
        val hist = FloatArray(bins * bins * bins)
        var total = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16 and 0xFF) * bins / 256
                val g = (c shr 8 and 0xFF) * bins / 256
                val b = (c and 0xFF) * bins / 256
                hist[r * bins * bins + g * bins + b] += 1f
                total++
                x += step
            }
            y += step
        }
        if (total > 0) for (i in hist.indices) hist[i] /= total
        return hist
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na.toDouble()) * sqrt(nb.toDouble())
        return if (denom == 0.0) 0f else (dot / denom).toFloat().coerceIn(0f, 1f)
    }
}
