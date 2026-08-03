package com.axlife.pinset.intro

import android.content.Context

data class InspectionContext(
    val householdId: String,
    val unitLabel: String,
    val floorplanId: String,
    val roomCode: String,
    val roomLabel: String,
    val sourceType: String
)

object InspectionContextStore {
    private const val FILE = "inspection_context"

    fun save(context: Context, value: InspectionContext) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("household_id", value.householdId)
            .putString("unit_label", value.unitLabel)
            .putString("floorplan_id", value.floorplanId)
            .putString("room_code", value.roomCode)
            .putString("room_label", value.roomLabel)
            .putString("source_type", value.sourceType)
            .apply()
    }

    fun load(context: Context): InspectionContext? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val householdId = prefs.getString("household_id", null) ?: return null
        return InspectionContext(
            householdId = householdId,
            unitLabel = prefs.getString("unit_label", "").orEmpty(),
            floorplanId = prefs.getString("floorplan_id", "").orEmpty(),
            roomCode = prefs.getString("room_code", "").orEmpty(),
            roomLabel = prefs.getString("room_label", "").orEmpty(),
            sourceType = prefs.getString("source_type", "catalog").orEmpty()
        )
    }
}
