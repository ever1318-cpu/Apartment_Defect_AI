package com.axlife.pinset.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RealAiMediaUploaderTest {
    @Test
    fun uploads_only_close_image_when_wide_context_is_not_selected() = runTest {
        val directory = createTempDirectory("pinset-media-").toFile()
        try {
            val close = File(directory, "close.jpg").apply { writeBytes("close".toByteArray()) }
            var sessionBody = ""
            val uploader = RealAiMediaUploader(
                "https://api.test.example",
                jsonTransport = AiHttpTransport { _, _, _, body ->
                    sessionBody = body.orEmpty()
                    HttpResponse(201, """{"uploads":[{"media_id":"close-id","upload_url":"/v2/media/uploads/close-id","headers":{}}]}""")
                },
                binaryTransport = AiBinaryTransport { _, _, _ -> HttpResponse(200, "") }
            )

            val ids = uploader.upload(AiCaptureContext(null, close.absolutePath))

            assertEquals(listOf("close-id"), ids)
            assertTrue(!sessionBody.contains("\"slot\":\"wide\""))
            assertTrue(sessionBody.contains("\"slot\":\"close\""))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun creates_metadata_and_uploads_wide_close_in_order() = runTest {
        val directory = createTempDirectory("pinset-media-").toFile()
        try {
            val wide = File(directory, "wide.jpg").apply { writeBytes("wide".toByteArray()) }
            val close = File(directory, "close.jpg").apply { writeBytes("close".toByteArray()) }
            var sessionBody = ""
            val jsonTransport = AiHttpTransport { _, _, _, body ->
                sessionBody = body.orEmpty()
                HttpResponse(
                    201,
                    """{"id":"session","uploads":[{"media_id":"wide-id","upload_url":"/v2/media/uploads/wide-id","headers":{"Content-Type":"image/jpeg"}},{"media_id":"close-id","upload_url":"/v2/media/uploads/close-id","headers":{"Content-Type":"image/jpeg"}}]}"""
                )
            }
            val uploaded = mutableListOf<Pair<String, String>>()
            val binaryTransport = AiBinaryTransport { url, headers, file ->
                uploaded += url to file.name
                assertEquals("image/jpeg", headers["Content-Type"])
                HttpResponse(200, """{"status":"UPLOADED"}""")
            }
            val uploader = RealAiMediaUploader(
                "https://api.test.example",
                jsonTransport,
                binaryTransport
            )
            val ids = uploader.upload(
                AiCaptureContext(wide.absolutePath, close.absolutePath, "거실")
            )

            assertEquals(listOf("wide-id", "close-id"), ids)
            assertEquals(
                listOf("wide.jpg", "close.jpg"),
                uploaded.map { it.second }
            )
            assertTrue(sessionBody.contains("\"slot\":\"wide\""))
            assertTrue(sessionBody.contains("\"slot\":\"close\""))
            assertTrue(sessionBody.contains("\"sha256\""))
        } finally {
            directory.deleteRecursively()
        }
    }
}
