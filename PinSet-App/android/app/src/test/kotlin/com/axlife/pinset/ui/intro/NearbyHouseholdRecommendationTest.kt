package com.axlife.pinset.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyHouseholdRecommendationTest {
    @Test
    fun recommends_next_line_on_same_floor() {
        assertEquals("1502", nextInspectionUnit("1501"))
        assertEquals("1504", nextInspectionUnit("1503"))
    }

    @Test
    fun moves_to_first_line_on_next_floor_after_line_four() {
        assertEquals("1601", nextInspectionUnit("1504"))
    }

    @Test
    fun preserves_manual_value_when_it_is_not_numeric() {
        assertEquals("직접입력", nextInspectionUnit("직접입력"))
    }
}
