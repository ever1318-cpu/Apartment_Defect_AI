package com.axlife.pinset.sync

import com.axlife.pinset.ai.AiCaptureContext
import com.axlife.pinset.ai.AiHttpTransport
import com.axlife.pinset.ai.AiMediaUploader
import com.axlife.pinset.ai.UrlConnectionAiTransport
import com.axlife.pinset.ai.validatedAiApiUrl
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectPhoto
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.data.entity.SyncQueueItem
import com.axlife.pinset.data.entity.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

sealed interface SyncUploadResult {
    data class Applied(val serverRevision: Int?) : SyncUploadResult
    data class Conflict(val message: String) : SyncUploadResult
    data class Retry(val message: String) : SyncUploadResult
}

fun interface DefectSyncUploader {
    suspend fun upload(
        item: SyncQueueItem,
        defect: Defect,
        photos: List<DefectPhoto>,
        session: Session
    ): SyncUploadResult
}

class RealDefectSyncUploader(
    baseUrl: String,
    private val deviceId: String,
    private val mediaUploader: AiMediaUploader,
    private val transport: AiHttpTransport = UrlConnectionAiTransport(),
    private val bearerToken: () -> String? = { null },
    allowLocalHttp: Boolean = false
) : DefectSyncUploader {
    private val root = validatedAiApiUrl(baseUrl, allowLocalHttp)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun upload(
        item: SyncQueueItem,
        defect: Defect,
        photos: List<DefectPhoto>,
        session: Session
    ): SyncUploadResult {
        val inspectionLocation = parseInspectionLocation(session.unitLabel)
            ?: return SyncUploadResult.Conflict("동·호수 또는 공용부 위치를 확인하세요: ${session.unitLabel}")
        val address = inspectionLocation.buildingNo to inspectionLocation.unitNo
        val commonHeaders = mutableMapOf("Content-Type" to "application/json")
        bearerToken()?.takeIf(String::isNotBlank)?.let {
            commonHeaders["Authorization"] = "Bearer $it"
        }
        val building = URLEncoder.encode(address.first, Charsets.UTF_8.name())
        val unit = URLEncoder.encode(address.second, Charsets.UTF_8.name())
        val householdResponse = runCatching {
            transport.request(
                "GET",
                "$root/v2/field/households/resolve?building_no=$building&unit_no=$unit",
                commonHeaders,
                null
            )
        }.getOrElse { return SyncUploadResult.Retry(it.message ?: "세대 조회 실패") }
        if (householdResponse.status !in 200..299) {
            return SyncUploadResult.Retry("세대 조회 실패 HTTP ${householdResponse.status}")
        }
        val householdId = runCatching {
            json.parseToJsonElement(householdResponse.body).jsonObject
                .getValue("id").jsonPrimitive.content
        }.getOrElse { return SyncUploadResult.Retry("세대 조회 응답 형식 오류") }

        val sessionClientUuid = UUID.nameUUIDFromBytes(
            "pinset-session:${session.id}:${session.unitLabel}".toByteArray(Charsets.UTF_8)
        ).toString()
        val sessionResponse = runCatching {
            transport.request(
                "POST", "$root/v2/field/sessions",
                commonHeaders + ("Idempotency-Key" to sessionClientUuid),
                buildJsonObject {
                    put("client_uuid", sessionClientUuid)
                    put("household_id", householdId)
                    put("inspector_id", "Master")
                    put("building_no", address.first)
                    put("unit_no", address.second)
                    put("inspection_kind", inspectionLocation.kind)
                    put("revision_no", session.revisionNo)
                    put("session_mode", session.sessionMode)
                    session.amendedFromSessionId?.let { put("amended_from_local_session_id", it) }
                    inspectionLocation.commonAreaLabel?.let { put("common_area_label", it) }
                }.toString()
            )
        }.getOrElse { return SyncUploadResult.Retry(it.message ?: "서버 점검 세션 생성 실패") }
        if (sessionResponse.status !in 200..299) {
            return SyncUploadResult.Retry("서버 점검 세션 생성 실패 HTTP ${sessionResponse.status}")
        }
        val serverSessionId = runCatching {
            json.parseToJsonElement(sessionResponse.body).jsonObject
                .getValue("id").jsonPrimitive.content
        }.getOrElse { return SyncUploadResult.Retry("세션 응답 형식 오류") }

        val summaryResponse = runCatching {
            transport.request(
                "GET", "$root/v2/field/sessions/$serverSessionId/summary",
                commonHeaders, null
            )
        }.getOrElse { return SyncUploadResult.Retry(it.message ?: "서버 세션 확인 실패") }
        val serverSession = runCatching {
            json.parseToJsonElement(summaryResponse.body).jsonObject
                .getValue("session").jsonObject
        }.getOrElse { return SyncUploadResult.Retry("세션 확인 응답 형식 오류") }
        if (serverSession["state"]?.jsonPrimitive?.content == "ANCHOR_REQUIRED") {
            val x = session.startXNorm
                ?: return SyncUploadResult.Retry("인트로 앵커 위치가 없습니다.")
            val y = session.startYNorm
                ?: return SyncUploadResult.Retry("인트로 앵커 위치가 없습니다.")
            val revision = serverSession["revision"]?.jsonPrimitive?.intOrNull ?: 1
            val anchorResponse = runCatching {
                transport.request(
                    "PUT", "$root/v2/field/sessions/$serverSessionId/anchor",
                    commonHeaders + ("If-Match" to revision.toString()),
                    buildJsonObject {
                        put("room_code", defect.roomId)
                        put("room_label", session.anchorLocationLabel)
                        put("x_norm", x)
                        put("y_norm", y)
                        put("heading_deg", session.startHeadingDeg ?: 0f)
                    }.toString()
                )
            }.getOrElse { return SyncUploadResult.Retry(it.message ?: "앵커 동기화 실패") }
            if (anchorResponse.status !in 200..299) {
                return SyncUploadResult.Conflict("앵커 동기화 충돌 HTTP ${anchorResponse.status}")
            }
        }

        if (photos.isEmpty()) return SyncUploadResult.Retry("저장된 하자 이미지가 없습니다.")
        val close = photos.firstOrNull { it.slot == SlotRole.A } ?: photos.first()
        val wide = photos.firstOrNull { it.slot == SlotRole.B }
        val extras = photos.filter { it.id != close.id && it.id != wide?.id }
        val mediaIds = runCatching {
            mediaUploader.upload(
                AiCaptureContext(
                    widePath = wide?.filePath,
                    closePath = close.filePath,
                    extraPaths = extras.map { it.filePath },
                    locationHint = defect.roomLabel,
                    clientKey = item.operationId
                )
            )
        }.getOrElse { return SyncUploadResult.Retry(it.message ?: "이미지 업로드 실패") }

        var mediaIndex = 0
        val wideMediaId = if (wide != null) mediaIds[mediaIndex++] else null
        val closeMediaId = mediaIds.getOrNull(mediaIndex++)
            ?: return SyncUploadResult.Retry("Close image upload response is missing")
        val taxonomy = DefectSyncTaxonomy.from(defect)
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("operations", buildJsonArray {
                add(buildJsonObject {
                    put("operation_id", item.operationId)
                    put("kind", "UPSERT_DEFECT")
                    put("entity_type", "defect")
                    put("entity_id", item.entityId)
                    put("base_revision", item.serverRevision ?: 0)
                    put("payload", buildJsonObject {
                        put("client_uuid", item.entityId)
                        put("session_id", serverSessionId)
                        put("defect_index", defect.defectIndex)
                        put("local_defect_id", defect.id)
                        put("room_code", defect.roomId)
                        put("room_label", defect.roomLabel)
                        put("x_norm", defect.xNorm)
                        put("y_norm", defect.yNorm)
                        put("defect_type", defect.defectType.name)
                        put("severity", defect.severity.name)
                        put("surface_code", taxonomy.surfaceCode)
                        put("area_detail", defect.areaDetail)
                        put("raw_resident_opinion", defect.residentOpinion)
                        put("standardized_opinion", defect.finalPathText.ifBlank { defect.residentOpinion })
                        put("taxonomy_version", "2.1.0")
                        put("final_location_code", taxonomy.locationCode)
                        put("final_part_code", taxonomy.partCode)
                        put("final_part_detail_code", taxonomy.partDetailCode)
                        put("final_work_kind_code", taxonomy.workKindCode)
                        put("final_cause_code", taxonomy.causeCode)
                        put("priority_code", taxonomy.priorityCode)
                        put("ai_path", defect.aiPathText)
                        put("ai_confidence", defect.aiConfidence)
                        put("final_path", defect.finalPathText)
                        defect.focusDistanceM?.let { put("focus_distance_m", it) }
                        defect.measuredGapMm?.let { put("measured_gap_mm", it) }
                        if (defect.measurementMethod.isNotBlank()) put("measurement_method", defect.measurementMethod)
                        if (defect.measurementStatus.isNotBlank()) put("measurement_status", defect.measurementStatus)
                        put("capture_metadata", buildJsonObject {
                            defect.focusDistanceM?.let { put("focus_distance_m", it) }
                            put("imu_pitch_deg", defect.imuPitchDeg)
                            put("imu_heading_deg", defect.imuHeadingDeg)
                            defect.arWorldX?.let { put("ar_world_x", it) }
                            defect.arWorldY?.let { put("ar_world_y", it) }
                            defect.arWorldZ?.let { put("ar_world_z", it) }
                        })
                        put("final_classification", buildJsonObject {
                            put("location_code", taxonomy.locationCode)
                            put("part_code", taxonomy.partCode)
                            put("part_detail_code", taxonomy.partDetailCode)
                            put("work_kind_code", taxonomy.workKindCode)
                            put("cause_code", taxonomy.causeCode)
                            put("priority_code", taxonomy.priorityCode)
                        })
                        put("created_at_epoch_ms", defect.createdAt)
                        put("wide_media_id", wideMediaId ?: closeMediaId)
                        put("close_media_id", closeMediaId)
                    })
                })
            })
        }
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Idempotency-Key" to item.operationId
        )
        bearerToken()?.takeIf(String::isNotBlank)?.let {
            headers["Authorization"] = "Bearer $it"
        }
        val response = runCatching {
            transport.request("POST", "$root/v2/field/sync/batches", headers, body.toString())
        }.getOrElse { return SyncUploadResult.Retry(it.message ?: "동기화 통신 실패") }
        if (response.status !in 200..299) {
            return if (response.status == 409) {
                SyncUploadResult.Conflict("서버 데이터와 충돌했습니다.")
            } else {
                SyncUploadResult.Retry("동기화 서버 오류 HTTP ${response.status}")
            }
        }
        val result = runCatching {
            json.parseToJsonElement(response.body).jsonObject["results"]
                ?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull() ?: return SyncUploadResult.Retry("동기화 응답 형식 오류")
        return when (result["state"]?.jsonPrimitive?.contentOrNull?.uppercase()) {
            "APPLIED" -> {
                val resource = result["resource"]?.jsonObject
                    ?: return SyncUploadResult.Retry("Missing synced defect resource")
                val defectId = resource["id"]?.jsonPrimitive?.contentOrNull
                    ?: return SyncUploadResult.Retry("Missing synced defect id")
                val uploadPhotos = buildList {
                    wide?.let { add(it) }
                    add(close)
                    addAll(extras)
                }
                val roles = buildList {
                    if (wide != null) add("WIDE")
                    add("CLOSE")
                    addAll(extras.map { "EXTRA" })
                }
                val mediaResult = registerMedia(
                    defectId = defectId,
                    operationId = item.operationId,
                    photos = uploadPhotos,
                    roles = roles,
                    mediaIds = mediaIds,
                    headers = commonHeaders,
                    defect = defect,
                )
                if (mediaResult != null) {
                    SyncUploadResult.Retry(mediaResult)
                } else {
                    SyncUploadResult.Applied(resource["revision"]?.jsonPrimitive?.intOrNull)
                }
            }
            "CONFLICT" -> SyncUploadResult.Conflict("서버 데이터와 충돌했습니다.")
            "REJECTED" -> SyncUploadResult.Conflict("서버가 데이터를 거부했습니다.")
            else -> SyncUploadResult.Retry("알 수 없는 동기화 상태")
        }
    }

    private suspend fun registerMedia(
        defectId: String,
        operationId: String,
        photos: List<DefectPhoto>,
        roles: List<String>,
        mediaIds: List<String>,
        headers: Map<String, String>,
        defect: Defect,
    ): String? {
        if (photos.size != roles.size || photos.size != mediaIds.size) {
            return "Uploaded media metadata is inconsistent"
        }
        for (index in photos.indices) {
            val photo = photos[index]
            val file = File(photo.filePath)
            if (!file.isFile || file.length() <= 0) return "Media file is missing: ${file.name}"
            val response = runCatching {
                transport.request(
                    "POST",
                    "$root/v2/field/defects/$defectId/media",
                    headers + ("Idempotency-Key" to "$operationId-media-${photo.id}"),
                    buildJsonObject {
                        put("client_uuid", UUID.nameUUIDFromBytes(
                            "$operationId:${photo.id}".toByteArray(Charsets.UTF_8)
                        ).toString())
                        put("role", roles[index])
                        put("mime_type", mimeType(file))
                        put("size_bytes", file.length())
                        put("sha256", sha256(file))
                        // The upload service normalizes readable Korean file names
                        // before writing them. Store the same normalized key in
                        // PostgreSQL so the gallery content endpoint can reopen it.
                        put("object_key", "field-media/${fieldMediaFileName(file)}")
                        put("upload_state", "UPLOADED")
                        put("metadata", buildJsonObject {
                            put("local_photo_id", photo.id)
                            put("slot", photo.slot.name)
                            put("lens", photo.lens.name)
                            put("zoom_ratio", photo.zoomRatio)
                            put("is_digital", photo.isDigital)
                            defect.focusDistanceM?.let { put("focus_distance_m", it) }
                            put("imu_pitch_deg", defect.imuPitchDeg)
                            put("imu_heading_deg", defect.imuHeadingDeg)
                        })
                    }.toString(),
                )
            }.getOrElse { return it.message ?: "Media registration failed" }
            if (response.status !in 200..299) {
                return "Media registration failed HTTP ${response.status}"
            }
        }
        return null
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fieldMediaFileName(file: File): String {
        val extension = when (file.extension.lowercase()) {
            "png" -> ".png"
            "webp" -> ".webp"
            else -> ".jpg"
        }
        val rawStem = file.name.substringBeforeLast('.', file.name)
        val normalized = rawStem
            .replace(Regex("[^0-9A-Za-z가-힣_-]+"), "_")
            .trim('_', '.')
        return if (normalized.isBlank()) "field-photo-${UUID.randomUUID()}$extension" else "$normalized$extension"
    }
}

private data class InspectionLocation(
    val buildingNo: String,
    val unitNo: String,
    val kind: String = "HOUSEHOLD",
    val commonAreaLabel: String? = null,
)

private fun parseInspectionLocation(label: String): InspectionLocation? {
    val commonPrefix = "공용부 ·"
    if (label.startsWith(commonPrefix)) {
        val location = label.removePrefix(commonPrefix).trim()
        return location.takeIf(String::isNotBlank)?.let {
            InspectionLocation("COMMON", "0000", "COMMON_AREA", it)
        }
    }
    val match = Regex("""(\d{3})동\s*(\d{3,4})호""").find(label) ?: return null
    return InspectionLocation(match.groupValues[1], match.groupValues[2])
}
