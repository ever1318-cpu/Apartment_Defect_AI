package com.axlife.pinset.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

data class AiCaptureContext(
    val widePath: String?,
    val closePath: String,
    val locationHint: String? = null,
    val clientKey: String? = null,
    val extraPaths: List<String> = emptyList(),
)

interface AiMediaUploader {
    suspend fun upload(context: AiCaptureContext): List<String>
}

fun interface AiBinaryTransport {
    suspend fun upload(url: String, headers: Map<String, String>, file: File): HttpResponse
}

class UrlConnectionBinaryTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 60_000
) : AiBinaryTransport {
    override suspend fun upload(
        url: String,
        headers: Map<String, String>,
        file: File
    ): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(file.length())
            headers.forEach(connection::setRequestProperty)
            file.inputStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

class RealAiMediaUploader(
    baseUrl: String,
    private val jsonTransport: AiHttpTransport = UrlConnectionAiTransport(),
    private val binaryTransport: AiBinaryTransport = UrlConnectionBinaryTransport(),
    private val bearerToken: () -> String? = { null },
    allowLocalHttp: Boolean = false
) : AiMediaUploader {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = validatedAiApiUrl(baseUrl, allowLocalHttp)

    override suspend fun upload(context: AiCaptureContext): List<String> {
        val files = buildList {
            context.widePath?.let { add(UploadFile("wide", File(it))) }
            add(UploadFile("close", File(context.closePath)))
            context.extraPaths.forEach { add(UploadFile("extra", File(it))) }
        }
        files.forEach {
            require(it.file.isFile && it.file.length() > 0) {
                "촬영 이미지 파일을 찾을 수 없습니다: ${it.file.name}"
            }
            require(it.file.length() <= 20_971_520) {
                "이미지는 파일당 20MB 이하여야 합니다: ${it.file.name}"
            }
        }
        // The API accepts at most four files per session. Split an inspection
        // with memo/follow-up images into consecutive sessions so no evidence
        // remains stuck in the offline queue.
        val mediaIds = mutableListOf<String>()
        for ((chunkIndex, chunk) in files.chunked(4).withIndex()) {
            mediaIds += uploadChunk(chunk, context.clientKey, chunkIndex)
        }
        return mediaIds
    }

    private suspend fun uploadChunk(
        files: List<UploadFile>,
        clientKey: String?,
        chunkIndex: Int
    ): List<String> {
        val requestBody = buildJsonObject {
            put("client_uuid", clientKey ?: UUID.randomUUID().toString())
            put("files", buildJsonArray {
                files.forEach { upload ->
                    add(buildJsonObject {
                        put("slot", upload.slot)
                        put("file_name", upload.file.name)
                        put("mime_type", upload.mimeType)
                        put("size_bytes", upload.file.length())
                        put("sha256", sha256(upload.file))
                    })
                }
            })
        }
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Idempotency-Key" to "${clientKey ?: UUID.randomUUID()}-media-$chunkIndex"
        )
        bearerToken()?.takeIf(String::isNotBlank)?.let {
            headers["Authorization"] = "Bearer $it"
        }
        val sessionResponse = jsonTransport.request(
            "POST", "$root/v2/media/upload-sessions", headers, requestBody.toString()
        )
        if (sessionResponse.status !in 200..299) {
            throw AiApiException(
                sessionResponse.status,
                "UPLOAD_SESSION_FAILED",
                "미디어 업로드 세션 생성에 실패했습니다.",
                sessionResponse.status >= 500
            )
        }
        val uploads = json.parseToJsonElement(sessionResponse.body)
            .jsonObject["uploads"]?.jsonArray
            ?: error("업로드 세션 응답에 uploads가 없습니다.")
        require(uploads.size == files.size) { "업로드 대상 수가 촬영 파일 수와 다릅니다." }
        return uploads.mapIndexed { index, element ->
            val instruction = element.jsonObject
            val mediaId = instruction["media_id"]?.jsonPrimitive?.contentOrNull
                ?: error("업로드 응답에 media_id가 없습니다.")
            val rawUrl = instruction["upload_url"]?.jsonPrimitive?.contentOrNull
                ?: error("업로드 응답에 upload_url이 없습니다.")
            val uploadUrl = if (rawUrl.startsWith("/")) "$root$rawUrl" else rawUrl
            validatedAiApiUrl(uploadUrl, root.startsWith("http://"))
            val instructionHeaders = instruction["headers"]?.jsonObject
                ?.mapValues { it.value.jsonPrimitive.content }
                .orEmpty()
            val response = binaryTransport.upload(uploadUrl, instructionHeaders, files[index].file)
            if (response.status !in 200..299) {
                throw AiApiException(
                    response.status,
                    "MEDIA_UPLOAD_FAILED",
                    "${files[index].slot} 이미지 업로드에 실패했습니다.",
                    response.status >= 500
                )
            }
            mediaId
        }
    }

    private data class UploadFile(val slot: String, val file: File) {
        val mimeType: String = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
