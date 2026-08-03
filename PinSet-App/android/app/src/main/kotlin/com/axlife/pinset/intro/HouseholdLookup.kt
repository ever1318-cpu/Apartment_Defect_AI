package com.axlife.pinset.intro

import com.axlife.pinset.vision.FloorplanCatalogEntry

data class HouseholdMatch(
    val householdId: String,
    val complexName: String,
    val buildingNo: String,
    val unitNo: String,
    val ownerMasked: String,
    val floorplanId: String,
    val floorplanLabel: String,
    val fallback: Boolean
) {
    val unitLabel: String get() = "${buildingNo}동 ${unitNo}호"
}

/**
 * Boundary for the future household DB API. The bundled implementation keeps
 * the UX executable before the production endpoint and authentication contract
 * are available.
 */
interface HouseholdLookup {
    suspend fun search(query: String): List<HouseholdMatch>
}

class CatalogHouseholdLookup(
    private val catalog: () -> List<FloorplanCatalogEntry>
) : HouseholdLookup {
    override suspend fun search(query: String): List<HouseholdMatch> {
        val normalized = query.filterNot(Char::isWhitespace).lowercase()
        if (normalized.length < 2) return emptyList()

        val entries = catalog()
        parseAddress(query)?.let { (building, unit) ->
            val floorplan = entries.firstOrNull {
                it.default && it.id.startsWith("ulsan_down")
            } ?: entries.firstOrNull { it.default } ?: entries.first()
            return listOf(floorplan.toMatch(
                fallback = false,
                buildingOverride = building,
                unitOverride = unit
            ))
        }
        val direct = entries.filter { entry ->
            entry.id.lowercase().contains(normalized) ||
                entry.label.filterNot(Char::isWhitespace).lowercase().contains(normalized)
        }
        val selected = direct.ifEmpty {
            // Demo owner/inspection-id aliases. Production replaces this class
            // with an authenticated API implementation; no owner PII is stored.
            if (normalized in setOf("홍길동", "점검001", "inspection001", "점검매니저", "master")) {
                entries.filter { it.default }.ifEmpty { entries.take(1) }
            } else emptyList()
        }
        return selected.map { it.toMatch(fallback = false) }
    }

    fun fallback(): HouseholdMatch {
        val entry = catalog().firstOrNull { it.default } ?: catalog().first()
        return entry.toMatch(fallback = true)
    }

    fun forAddress(
        buildingNo: String,
        unitNo: String,
        floorplanId: String,
        fallback: Boolean = false
    ): HouseholdMatch {
        val entry = catalog().firstOrNull { it.id == floorplanId }
            ?: catalog().firstOrNull { it.default }
            ?: catalog().first()
        return entry.toMatch(fallback, buildingNo, unitNo)
    }

    private fun parseAddress(value: String): Pair<String, String>? {
        val match = Regex("""(\d{3})\s*동?\s*(\d{3,4})\s*호?""").find(value) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun FloorplanCatalogEntry.toMatch(
        fallback: Boolean,
        buildingOverride: String? = null,
        unitOverride: String? = null
    ): HouseholdMatch {
        val numbers = Regex("""(\d+)동\s*(\d+)호""").find(label)
        val building = buildingOverride ?: numbers?.groupValues?.getOrNull(1)
            ?: if (id == "ulsan_down_84b") "101" else "101"
        val unit = unitOverride ?: numbers?.groupValues?.getOrNull(2)
            ?: if (id == "ulsan_down_84b") "1502" else "1501"
        return HouseholdMatch(
            householdId = "$building-$unit",
            complexName = if (id.startsWith("ulsan_down")) "울산다운지구" else "샘플 아파트",
            buildingNo = building,
            unitNo = unit,
            ownerMasked = "홍*동",
            floorplanId = id,
            floorplanLabel = label,
            fallback = fallback
        )
    }
}
