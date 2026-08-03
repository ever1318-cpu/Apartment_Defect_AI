package com.axlife.pinset.vision

import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.Severity
import kotlin.math.hypot

data class DefectCluster(
    var lead: Defect,
    val members: MutableList<Defect>,
    val xNorm: Float,
    val yNorm: Float
) {
    val count: Int get() = members.size
}

private val severityRank = mapOf(
    Severity.MAJOR to 3,
    Severity.NORMAL to 2,
    Severity.MINOR to 1
)

/**
 * Groups defects whose floorplan coordinates are within [threshold]
 * (fraction of the image size). The highest-severity, most-recent defect
 * represents the cluster on the map.
 */
fun clusterDefects(defects: List<Defect>, threshold: Float = 0.04f): List<DefectCluster> {
    val clusters = mutableListOf<DefectCluster>()
    for (d in defects) {
        val existing = clusters.firstOrNull {
            hypot((it.xNorm - d.xNorm).toDouble(), (it.yNorm - d.yNorm).toDouble()) < threshold
        }
        if (existing != null) {
            existing.members.add(d)
            if (shouldLead(d, existing.lead)) existing.lead = d
        } else {
            clusters.add(DefectCluster(d, mutableListOf(d), d.xNorm, d.yNorm))
        }
    }
    return clusters
}

private fun shouldLead(candidate: Defect, current: Defect): Boolean {
    val a = severityRank[candidate.severity] ?: 0
    val b = severityRank[current.severity] ?: 0
    if (a != b) return a > b
    return candidate.createdAt > current.createdAt
}
