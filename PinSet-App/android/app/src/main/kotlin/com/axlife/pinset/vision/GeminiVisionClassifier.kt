package com.axlife.pinset.vision

import android.util.Base64
import android.util.Log
import com.axlife.pinset.data.entity.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini Vision-backed classifier. Sends the captured close-up photo plus
 * the resident's opinion and parses back a JSON catalog path + confidence.
 *
 * v2: reworked to be paranoid about failure modes — every stage logs, every
 * error path returns an AiSuggestion with a diagnostic message in
 * [AiSuggestion.rationale] so the "AI 분석 중" spinner can never get stuck.
 */
class GeminiVisionClassifier(
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash"
) : AiClassifier {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Async entry point. GUARANTEES a non-null return — every error path
     * ends in an AiSuggestion whose rationale explains what went wrong.
     */
    suspend fun classifyAsync(input: AiInput, photoPath: String?): AiSuggestion {
        Log.d(TAG, "classifyAsync start · model=$model · key=${maskKey(apiKey)} · photo=$photoPath")
        if (apiKey.isBlank()) {
            return AiSuggestion("❌ API 키 없음", 0f, "설정 화면에서 API 키를 입력하세요")
        }
        if (photoPath.isNullOrBlank()) {
            return AiSuggestion("❌ 사진 없음", 0f, "촬영 데이터가 전달되지 않았습니다")
        }
        val bytes = try {
            readAndDownscale(photoPath)
        } catch (t: Throwable) {
            Log.w(TAG, "downscale failed", t)
            return AiSuggestion("❌ 사진 로드 실패", 0f, t.message ?: "decode error")
        }
        if (bytes.isEmpty()) {
            return AiSuggestion("❌ 사진 로드 실패", 0f, "파일이 비어있거나 접근 불가")
        }
        Log.d(TAG, "downscaled bytes=${bytes.size}")

        val prompt = buildPrompt(input)
        val body = try {
            buildRequestBody(prompt, bytes)
        } catch (t: Throwable) {
            Log.e(TAG, "buildRequestBody failed", t)
            return AiSuggestion("❌ 요청 조립 실패", 0f, t.message ?: "encode error")
        }
        Log.d(TAG, "request body chars=${body.length}")

        return withContext(Dispatchers.IO) {
            try {
                val resp = post(body)
                Log.d(TAG, "HTTP ok · resp chars=${resp.length}")
                val parsed = parseResponse(resp)
                if (parsed == null) {
                    Log.w(TAG, "parseResponse returned null. Raw: $resp")
                    AiSuggestion(
                        "❌ 응답 파싱 실패",
                        0f,
                        "모델 응답 형식이 예상과 다릅니다"
                    )
                } else parsed
            } catch (t: Throwable) {
                Log.w(TAG, "gemini call failed", t)
                AiSuggestion(
                    "❌ 네트워크 오류",
                    0f,
                    t.message ?: t.javaClass.simpleName
                )
            }
        }
    }

    override fun classify(input: AiInput): AiSuggestion =
        AiSuggestion(
            pathText = "",
            confidence = 0f,
            rationale = "Gemini 는 비동기로 호출됩니다"
        )

    // ---------- prompt ----------------------------------------------------

    private fun buildPrompt(input: AiInput): String {
        val surface = when (input.surface) {
            Surface.CEILING -> "천장"
            Surface.WALL -> "벽"
            Surface.FLOOR -> "바닥"
        }
        val room = input.roomLabel?.takeIf { it.isNotBlank() } ?: "미확인"
        val focus = input.focusDistanceM?.let { "%.1fm".format(it) } ?: "미측정"
        val opinion = input.residentOpinion.ifBlank { "(입주민 의견 없음)" }
        return buildString {
            append("당신은 아파트 하자 점검 전문가입니다. 사진과 상황 정보로 하자를 분류하세요.\n\n")
            append("[상황]\n")
            append("- 공간: ").append(room).append('\n')
            append("- 표면(자동판정): ").append(surface).append('\n')
            append("- 초점거리: ").append(focus).append('\n')
            append("- 촬영방향: ").append(input.headingDeg.toInt()).append("도\n")
            append("- 기울기: ").append(input.pitchDeg.toInt()).append("도\n")
            append("- 입주민의견: ").append(opinion).append('\n')
            append("\n[분류 규칙]\n")
            append("공종: 벽체 도배 타일 바닥 창호 전기 배관 도장 코킹 목문가틀 기타\n")
            append("원인: 흠집 균열 누수결로 들뜸 파손 오염 시공불량 확인필요\n")
            append("\n[출력]\n")
            append("반드시 JSON만 응답. path, confidence, rationale 세 필드.\n")
            append("path 예: '주방발코니 > 벽 > 도장 > 흠집'\n")
            append("confidence 는 0.0~1.0 실수.\n")
            append("rationale 은 20자 이내 한국어 근거.")
        }
    }

    // ---------- request body ----------------------------------------------

    /**
     * Build the request body by hand. We serialize the prompt as a JSON
     * string (using kotlinx.serialization's string encoder for correct
     * escaping) and splice it into a stable template. The base64 blob is
     * ASCII so no escaping needed.
     */
    private fun buildRequestBody(prompt: String, jpegBytes: ByteArray): String {
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        // encodeToString on a plain String produces a properly-escaped JSON
        // string literal (including surrounding quotes).
        val promptJson = json.encodeToString(String.serializer(), prompt)
        return """
{"contents":[{"parts":[{"text":$promptJson},{"inline_data":{"mime_type":"image/jpeg","data":"$b64"}}]}],"generationConfig":{"temperature":0.2,"responseMimeType":"application/json","maxOutputTokens":512}}
        """.trimIndent()
    }

    // ---------- HTTP ------------------------------------------------------

    private fun post(body: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        )
        Log.d(TAG, "POST $url")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            Log.d(TAG, "HTTP $code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = runCatching {
                    json.decodeFromString(GeminiError.serializer(), text).error?.message
                }.getOrNull()
                Log.w(TAG, "HTTP $code error body: $text")
                throw IllegalStateException("HTTP $code · ${hint ?: "API 오류"}")
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    // ---------- response parsing ------------------------------------------

    @Serializable
    private data class GeminiResponse(val candidates: List<Candidate> = emptyList())

    @Serializable
    private data class Candidate(val content: Content? = null)

    @Serializable
    private data class Content(val parts: List<Part> = emptyList())

    @Serializable
    private data class Part(val text: String = "")

    @Serializable
    private data class Suggestion(
        val path: String = "",
        val confidence: Float = 0f,
        val rationale: String = ""
    )

    @Serializable
    private data class GeminiError(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val code: Int = 0, val message: String = "", val status: String = "")

    private fun parseResponse(body: String): AiSuggestion? {
        val parsed = runCatching {
            json.decodeFromString(GeminiResponse.serializer(), body)
        }.getOrNull() ?: return null
        val payload = parsed.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?.trim()
            ?.removePrefix("```json")?.removePrefix("```")
            ?.removeSuffix("```")
            ?.trim()
            ?: return null
        val s = runCatching {
            json.decodeFromString(Suggestion.serializer(), payload)
        }.getOrNull() ?: run {
            // Model returned free text instead of JSON — expose the raw text.
            return AiSuggestion(
                pathText = payload.take(80),
                confidence = 0.3f,
                rationale = "JSON 형식 아님"
            )
        }
        if (s.path.isBlank()) return null
        return AiSuggestion(
            pathText = s.path,
            confidence = s.confidence.coerceIn(0f, 1f),
            rationale = s.rationale
        )
    }

    // ---------- helpers ---------------------------------------------------

    private fun readAndDownscale(path: String): ByteArray {
        val file = java.io.File(path)
        if (!file.exists()) return ByteArray(0)
        val opts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        val longEdge = kotlin.math.max(opts.outWidth, opts.outHeight)
        if (longEdge <= 0) return ByteArray(0)
        val sample = kotlin.math.max(1, longEdge / 800)
        val decode = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path, decode)
            ?: return ByteArray(0)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    private fun maskKey(k: String): String {
        if (k.length < 8) return "***"
        return k.take(4) + "…" + k.takeLast(4)
    }

    companion object {
        private const val TAG = "GeminiVision"
    }
}
