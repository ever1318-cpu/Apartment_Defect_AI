package com.axlife.pinset.intro

import com.axlife.pinset.vision.FloorplanCatalogEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogHouseholdLookupTest {
    private val lookup = CatalogHouseholdLookup {
        listOf(
            FloorplanCatalogEntry("ulsan_down_84a", "84A-1", 34, true, "site.png"),
            FloorplanCatalogEntry("ulsan_down_84b", "84B-1", 34, false, "site.png")
        )
    }

    @Test
    fun typed_address_is_preserved_in_match() = runTest {
        val match = lookup.search("112동 1803호").single()

        assertEquals("112", match.buildingNo)
        assertEquals("1803", match.unitNo)
        assertEquals("112동 1803호", match.unitLabel)
        assertEquals("ulsan_down_84a", match.floorplanId)
    }

    @Test
    fun changing_floorplan_does_not_change_household_address() {
        val match = lookup.forAddress("119", "2504", "ulsan_down_84b")

        assertEquals("119동 2504호", match.unitLabel)
        assertEquals("ulsan_down_84b", match.floorplanId)
        assertEquals("119-2504", match.householdId)
    }
}
