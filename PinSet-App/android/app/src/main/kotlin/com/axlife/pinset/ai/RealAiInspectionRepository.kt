package com.axlife.pinset.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class HttpResponse(val status: Int, val body: String)

fun interface AiHttpTransport {
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): HttpResponse
}

class UrlConnectionAiTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000
) : AiHttpTransport {
    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

class AiApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val retryable: Boolean
) : IllegalStateException(message)

/**
 * API v2 orchestration adapter. Media upload itself is intentionally outside
 * this class; callers must provide two server-issued media UUIDs.
 */
class RealAiInspectionRepository(
    baseUrl: String,
    private val transport: AiHttpTransport = UrlConnectionAiTransport(),
    private val bearerToken: () -> String? = { null },
    allowLocalHttp: Boolean = false
) : AiInspectionRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = validatedAiApiUrl(baseUrl, allowLocalHttp)
    private val contexts = mutableMapOf<String, Context>()

    private data class Context(
        val assistantSessionId: String,
        val optionIdsByLabel: MutableMap<String, String> = mutableMapOf()
    )

    override suspend fun create(request: CreateInspectionRequest): InspectionSession {
        require(request.residentOpinion.isNotBlank()) { "입주자 의견을 입력해 주세요." }
        require(request.imageIds.size >= 2) {
            "실제 API 분석에는 업로드 완료된 원거리·근거리 이미지 ID 2개가 필요합니다."
        }
        val clientUuid = UUID.randomUUID().toString()
        val draft = buildJsonObject {
            put("client_uuid", clientUuid)
            put("taxonomy_version", "2.0.0")
            put("site", buildJsonObject { put("source", "pinset-android") })
            put("location", buildJsonObject {
                put("building", "미지정")
                put("unit", "미지정")
                put("area", request.locationHint ?: "미지정")
            })
            put("capture_pair", buildJsonObject {
                put("wide_media_id", request.imageIds[0])
                put("close_media_id", request.imageIds[1])
            })
            put("raw_opinion", request.residentOpinion)
            put("consent", buildJsonObject {
                put("service_use", true)
                put("training_use", false)
            })
        }
        val inspection = post("/v2/inspections", draft)
        val inspectionId = inspection.string("id")
        val analysis = post(
            "/v2/inspections/$inspectionId/analysis",
            buildJsonObject {
                put("model_name", "apartment-defect-convnext")
                put("model_version", "2.0.0")
                put("top_k", 3)
            }
        )
        val assistant = post(
            "/v2/assistant/sessions",
            buildJsonObject {
                put("inspection_id", inspectionId)
                put("analysis_id", analysis.string("id"))
            }
        )
        val assistantId = assistant.string("id")
        val context = Context(assistantId)
        contexts[inspectionId] = context
        return sessionFrom(inspectionId, assistant, context)
    }

    override suspend fun answer(
        inspectionId: String,
        questionId: String,
        answer: String
    ): InspectionSession {
        val context = contexts[inspectionId] ?: error("검사 세션을 찾을 수 없습니다.")
        val optionId = context.optionIdsByLabel[answer]
        val payload = buildJsonObject {
            put("question_id", questionId)
            if (optionId != null) put("option_id", optionId) else put("free_text", answer)
        }
        val assistant = post(
            "/v2/assistant/sessions/${context.assistantSessionId}/messages",
            payload
        )
        return sessionFrom(inspectionId, assistant, context)
    }

    override suspend fun confirm(
        inspectionId: String,
        candidate: ClassificationCandidate
    ): ClassificationCandidate {
        check(contexts.containsKey(inspectionId)) { "검사 세션을 찾을 수 없습니다." }
        val inspection = get("/v2/inspections/$inspectionId")
        val revision = inspection["revision"]?.jsonPrimitive?.intOrNull
            ?: error("서버 revision이 없습니다.")
        val classification = buildJsonObject {
            candidate.codes.forEach { (key, value) -> put(key, value) }
        }
        require(classification.isNotEmpty()) { "확정할 서버 분류 코드가 없습니다." }
        post(
            "/v2/inspections/$inspectionId/confirmation",
            buildJsonObject {
                put("classification", classification)
                put("confirmation_source", "accepted")
            },
            extraHeaders = mapOf("If-Match" to revision.toString())
        )
        contexts.remove(inspectionId)
        return candidate
    }

    private fun sessionFrom(
        inspectionId: String,
        assistant: JsonObject,
        context: Context
    ): InspectionSession {
        val questionObject = assistant["question"] as? JsonObject
        val question = questionObject?.let {
            val options = (it["options"] as? JsonArray).orEmpty().map { optionElement ->
                val option = optionElement.jsonObject
                val label = option.string("label")
                context.optionIdsByLabel[label] = option.string("id")
                label
            }
            InspectionQuestion(it.string("id"), it.string("text"), options)
        }
        val proposal = assistant["proposal"] as? JsonObject
        val candidates = proposal?.let { listOf(candidateFrom(it)) }.orEmpty()
        return InspectionSession(inspectionId, question, candidates)
    }

    private fun candidateFrom(proposal: JsonObject): ClassificationCandidate {
        val codes = proposal.mapNotNull { (key, value) ->
            value.jsonPrimitive.contentOrNull?.let { key to it }
        }.toMap()
        return ClassificationCandidate(
            rank = 1,
            workKind = codes["work_kind_code"] ?: "미분류",
            location = codes["area_code"] ?: "미지정",
            defectPart = codes["part_code"] ?: "미분류",
            partDetail = codes["part_detail_code"] ?: "미분류",
            defectCause = codes["suspected_cause_code"] ?: "원인 미확정",
            confidence = proposal["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f,
            rationale = codes["standardized_opinion"] ?: "서버 추천 분류",
            codes = codes.filterKeys { it.endsWith("_code") || it == "standardized_opinion" }
        )
    }

    private suspend fun get(path: String): JsonObject = execute("GET", path, null)

    private suspend fun post(
        path: String,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap()
    ): JsonObject = execute("POST", path, body, extraHeaders)

    private suspend fun execute(
        method: String,
        path: String,
        body: JsonElement?,
        extraHeaders: Map<String, String> = emptyMap()
    ): JsonObject {
        val headers = buildMap {
            put("Idempotency-Key", UUID.randomUUID().toString())
            bearerToken()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
            putAll(extraHeaders)
        }
        val response = transport.request(method, "$root$path", headers, body?.toString())
        val parsed = response.body.takeIf { it.isNotBlank() }?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        if (response.status !in 200..299) {
            throw AiApiException(
                status = response.status,
                code = parsed?.get("code")?.jsonPrimitive?.contentOrNull ?: "HTTP_${response.status}",
                message = parsed?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "AI 서버 요청에 실패했습니다.",
                retryable = parsed?.get("retryable")?.jsonPrimitive?.contentOrNull == "true"
            )
        }
        return parsed ?: error("AI 서버 응답이 비어 있거나 JSON 형식이 아닙니다.")
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: error("서버 응답에 $key 값이 없습니다.")
}

object AiInspectionRepositoryProvider {
    fun create(baseUrl: String, allowLocalHttp: Boolean = false): AiInspectionRepository =
        if (baseUrl.isBlank()) FakeAiInspectionRepository()
        else RealAiInspectionRepository(baseUrl, allowLocalHttp = allowLocalHttp)
}

internal fun validatedAiApiUrl(baseUrl: String, allowLocalHttp: Boolean): String {
    val value = baseUrl.trim().trimEnd('/')
    require(value.startsWith("https://") || (allowLocalHttp && value.startsWith("http://"))) {
        "AI API 주소는 HTTPS여야 합니다. HTTP는 명시적으로 허용된 Debug 빌드에서만 사용할 수 있습니다."
    }
    return value
}
