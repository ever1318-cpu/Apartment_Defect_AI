package com.axlife.pinset.sync

import com.axlife.pinset.ai.AiHttpTransport
import com.axlife.pinset.ai.UrlConnectionAiTransport
import com.axlife.pinset.ai.validatedAiApiUrl
import com.axlife.pinset.data.entity.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.net.URLEncoder
import java.util.UUID

sealed interface SessionCompletionResult {
    data class Completed(val total: Int, val confirmed: Int, val nextUnitNo: String?) : SessionCompletionResult
    data class Blocked(val message: String) : SessionCompletionResult
}

/** Final server-side close. Local completion is deliberately done only after this succeeds. */
class SessionCompletionUploader(
    baseUrl: String,
    private val transport: AiHttpTransport = UrlConnectionAiTransport(),
    allowLocalHttp: Boolean = false,
) {
    private val root = validatedAiApiUrl(baseUrl, allowLocalHttp)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(session: Session): SessionCompletionResult {
        val clientUuid = UUID.nameUUIDFromBytes(
            "pinset-session:${session.id}:${session.unitLabel}".toByteArray(Charsets.UTF_8)
        ).toString()
        val query = URLEncoder.encode(clientUuid, Charsets.UTF_8.name())
        val resolved = runCatching {
            transport.request("GET", "$root/v2/field/sessions/resolve?client_uuid=$query", emptyMap(), null)
        }.getOrElse { return SessionCompletionResult.Blocked("서버 세션을 확인하지 못했습니다. 네트워크를 확인해 주세요.") }
        if (resolved.status !in 200..299) {
            return SessionCompletionResult.Blocked("서버 세션 확인 실패 (HTTP ${resolved.status})")
        }
        val serverId = runCatching {
            json.parseToJsonElement(resolved.body).jsonObject.getValue("id").jsonPrimitive.content
        }.getOrElse { return SessionCompletionResult.Blocked("서버 세션 응답 형식이 올바르지 않습니다.") }
        val summary = runCatching {
            transport.request("GET", "$root/v2/field/sessions/$serverId/summary", emptyMap(), null)
        }.getOrElse { return SessionCompletionResult.Blocked("서버 전송 결과를 확인하지 못했습니다.") }
        if (summary.status !in 200..299) {
            return SessionCompletionResult.Blocked("서버 전송 결과 확인 실패 (HTTP ${summary.status})")
        }
        val document = runCatching { json.parseToJsonElement(summary.body).jsonObject }
            .getOrElse { return SessionCompletionResult.Blocked("서버 집계 응답 형식이 올바르지 않습니다.") }
        val serverSession = document.getValue("session").jsonObject
        val counts = document.getValue("counts").jsonObject
        val total = counts.getValue("total").jsonPrimitive.int
        val confirmed = counts.getValue("confirmed").jsonPrimitive.int
        if (total == 0) return SessionCompletionResult.Blocked("서버에 전송된 하자 정보가 없습니다.")
        if (serverSession.getValue("state").jsonPrimitive.content == "COMPLETED") {
            return SessionCompletionResult.Completed(total, confirmed, null)
        }
        val revision = serverSession.getValue("revision").jsonPrimitive.int
        val completed = runCatching {
            transport.request(
                "POST", "$root/v2/field/sessions/$serverId/complete",
                mapOf("If-Match" to revision.toString()), "{}"
            )
        }.getOrElse { return SessionCompletionResult.Blocked("서버 세션 마감에 실패했습니다. 다시 시도해 주세요.") }
        if (completed.status !in 200..299) {
            return SessionCompletionResult.Blocked("서버 세션 마감 실패 (HTTP ${completed.status})")
        }
        val nextUnit = runCatching {
            json.parseToJsonElement(completed.body).jsonObject["next_unit_no"]?.jsonPrimitive?.content
        }.getOrNull()
        return SessionCompletionResult.Completed(total, confirmed, nextUnit)
    }
}
