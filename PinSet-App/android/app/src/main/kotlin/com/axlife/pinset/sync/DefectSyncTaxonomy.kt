package com.axlife.pinset.sync

import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.Surface
import com.axlife.pinset.data.entity.Trade

/** Maps local UI values to stable PostgreSQL classification codes. */
internal data class DefectSyncTaxonomy(
    val locationCode: String,
    val partCode: String,
    val partDetailCode: String,
    val workKindCode: String,
    val causeCode: String,
    val priorityCode: String,
    val surfaceCode: String,
) {
    companion object {
        fun from(defect: Defect) = DefectSyncTaxonomy(
            locationCode = locationCode(defect.roomId),
            partCode = partCode(defect.surface),
            partDetailCode = partDetailCode(defect.trade),
            workKindCode = workKindCode(defect.trade),
            causeCode = causeCode(defect.defectType),
            priorityCode = priorityCode(defect.severity),
            surfaceCode = "SURFACE_${defect.surface.name}",
        )

        private fun locationCode(roomId: String) = when (roomId.lowercase()) {
            "livingroom" -> "AREA_LIVING"
            "mainbed" -> "AREA_MAIN_BEDROOM"
            "bed1" -> "AREA_BEDROOM_1"
            "bed2" -> "AREA_BEDROOM_2"
            "kitchen" -> "AREA_KITCHEN"
            "bath_main" -> "AREA_MAIN_BATHROOM"
            "bath_common" -> "AREA_COMMON_BATHROOM"
            "balcony" -> "AREA_BALCONY"
            "entrance" -> "AREA_ENTRANCE"
            else -> "AREA_OTHER"
        }

        private fun partCode(surface: Surface) = when (surface) {
            Surface.CEILING -> "PART_CEILING"
            Surface.WALL -> "PART_WALL"
            Surface.FLOOR -> "PART_FLOOR"
        }

        private fun partDetailCode(trade: Trade) = when (trade) {
            Trade.WALL -> "DETAIL_WALL_SURFACE"
            Trade.WALLPAPER -> "DETAIL_WALLPAPER"
            Trade.TILE -> "DETAIL_TILE"
            Trade.FLOOR -> "DETAIL_FLOOR_FINISH"
            Trade.WINDOW -> "DETAIL_WINDOW_FRAME"
            Trade.ELECTRIC -> "DETAIL_ELECTRICAL_FIXTURE"
            Trade.PLUMBING -> "DETAIL_PIPE_FIXTURE"
            Trade.OTHER -> "DETAIL_OTHER"
        }

        private fun workKindCode(trade: Trade) = when (trade) {
            Trade.WALL, Trade.WALLPAPER, Trade.FLOOR -> "WORK_FINISH"
            Trade.TILE -> "WORK_TILE"
            Trade.WINDOW -> "WORK_WINDOW"
            Trade.ELECTRIC -> "WORK_ELECTRICAL"
            Trade.PLUMBING -> "WORK_PLUMBING"
            Trade.OTHER -> "WORK_OTHER"
        }

        private fun causeCode(type: DefectType) = when (type) {
            DefectType.CRACK -> "CAUSE_CRACK"
            DefectType.LEAK -> "CAUSE_LEAK"
            DefectType.FINISH -> "CAUSE_CONSTRUCTION"
            DefectType.OTHER -> "CAUSE_OTHER"
        }

        private fun priorityCode(severity: Severity) = when (severity) {
            Severity.MAJOR -> "P1"
            Severity.NORMAL -> "P2"
            Severity.MINOR -> "P3"
        }
    }
}
