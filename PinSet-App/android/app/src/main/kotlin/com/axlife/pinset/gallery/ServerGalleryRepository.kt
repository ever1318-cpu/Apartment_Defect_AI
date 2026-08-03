package com.axlife.pinset.gallery

import android.content.Context
import com.axlife.pinset.BuildConfig
import com.axlife.pinset.ai.UrlConnectionAiTransport
import com.axlife.pinset.ai.validatedAiApiUrl
import com.axlife.pinset.data.FieldEndpointPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GalleryHousehold(
    val buildingNo: String,
    val unitNo: String,
    val defectCount: Int,
    val lastInspectedAt: String,
)

data class GalleryMedia(
    val id: String,
    val role: String,
    val createdAt: String?,
    val isVirtualReference: Boolean,
)

data class GalleryDefect(
    val index: Int,
    val room: String,
    val rawOpinion: String,
    val finalOpinion: String,
    val classification: String,
    /** Subject distance measured by Camera2 at shutter time, if supported. */
    val focusDistanceM: Float?,
    val measuredGapMm: Float?,
    val measurementMethod: String,
    val measurementStatus: String,
    val commonAreaLabel: String?,
    val media: List<GalleryMedia>,
)

class GalleryRequestException(val status: Int) : IllegalStateException("HTTP $status")

class ServerGalleryRepository(context: Context) {
    private val root = validatedAiApiUrl(
        FieldEndpointPrefs.load(context),
        allowLocalHttp = BuildConfig.DEBUG
    )
    private val transport = UrlConnectionAiTransport()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun households(): List<GalleryHousehold> {
        val body = get("$root/v2/field/gallery/households")
        return body["households"]?.jsonArray.orEmpty().map { element ->
            val item = element.jsonObject
            GalleryHousehold(
                item.string("building_no"), item.string("unit_no"),
                item["defect_count"]?.jsonPrimitive?.intOrNull ?: 0,
                item["last_inspected_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
    }

    suspend fun defects(buildingNo: String, unitNo: String): List<GalleryDefect> {
        val body = get("$root/v2/field/gallery/households/$buildingNo/$unitNo")
        return body["defects"]?.jsonArray.orEmpty().map { element ->
            val item = element.jsonObject
            val c = item["classification"]?.jsonObject
            val path = listOf("location", "part", "detail", "trade", "cause")
                .mapNotNull { c?.get(it)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
                .joinToString(".")
            GalleryDefect(
                index = item["defect_index"]?.jsonPrimitive?.intOrNull ?: 0,
                room = item.string("room_label"),
                rawOpinion = item["raw_resident_opinion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                finalOpinion = item["standardized_opinion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                classification = path,
                focusDistanceM = item["focus_distance_m"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
                measuredGapMm = item["measured_gap_mm"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
                measurementMethod = item["measurement_method"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                measurementStatus = item["measurement_status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                commonAreaLabel = item["common_area_label"]?.jsonPrimitive?.contentOrNull,
                media = item["media"]?.jsonArray.orEmpty().map { media ->
                    val value = media.jsonObject
                    GalleryMedia(
                        id = value.string("id"),
                        role = value.string("role"),
                        createdAt = value["created_at"]?.jsonPrimitive?.contentOrNull,
                        isVirtualReference = value["object_key"]?.jsonPrimitive?.contentOrNull
                            ?.contains("_REFERENCE_", ignoreCase = true) == true,
                    )
                }
            )
        }
    }

    fun contentUrl(mediaId: String): String = "$root/v2/field/media/$mediaId/content"

    private suspend fun get(url: String) = json.parseToJsonElement(
        transport.request("GET", url, emptyMap(), null).also { response ->
            if (response.status !in 200..299) throw GalleryRequestException(response.status)
        }.body
    ).jsonObject

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
