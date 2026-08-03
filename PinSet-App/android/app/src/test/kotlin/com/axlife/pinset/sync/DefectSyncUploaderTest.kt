package com.axlife.pinset.sync

import com.axlife.pinset.ai.AiCaptureContext
import com.axlife.pinset.ai.AiMediaUploader
import com.axlife.pinset.ai.HttpResponse
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectPhoto
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Lens
import com.axlife.pinset.data.entity.PinSource
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.data.entity.SyncQueueItem
import com.axlife.pinset.data.entity.Session
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefectSyncUploaderTest {
    private val defect = Defect(
        id = 7,
        sessionId = 3,
        roomId = "livingroom",
        roomLabel = "거실",
        xNorm = .4f,
        yNorm = .5f,
        defectType = DefectType.CRACK,
        severity = Severity.NORMAL,
        source = PinSource.MANUAL,
        residentOpinion = "벽에 균열이 있어요"
    )
    private val photos = listOf(
        DefectPhoto(1, 7, "close.jpg", Lens.TELE, SlotRole.A, 3f, false, true),
        DefectPhoto(2, 7, "wide.jpg", Lens.ULTRA, SlotRole.B, .5f, false)
    )

    @Test
    fun `stable operation id is used for media and batch idempotency`() = runTest {
        val closeFile = File.createTempFile("pinset-close", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val wideFile = File.createTempFile("pinset-wide", ".jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val testPhotos = listOf(
            photos[0].copy(filePath = closeFile.absolutePath),
            photos[1].copy(filePath = wideFile.absolutePath),
        )
        var capture: AiCaptureContext? = null
        var headers: Map<String, String> = emptyMap()
        var body = ""
        var syncHeaders: Map<String, String> = emptyMap()
        var syncBody = ""
        var call = 0
        val uploader = RealDefectSyncUploader(
            baseUrl = "http://localhost:8000",
            deviceId = "test-device",
            mediaUploader = object : AiMediaUploader {
                override suspend fun upload(context: AiCaptureContext): List<String> {
                    capture = context
                    return listOf("wide-id", "close-id")
                }
            },
            transport = { method, url, h, b ->
                headers = h
                if (!url.contains("/media")) body = b.orEmpty()
                if (method == "POST" && url.contains("/sync/batches")) {
                    syncHeaders = h
                    syncBody = b.orEmpty()
                }
                call += 1
                when {
                    method == "GET" && url.contains("households/resolve") ->
                        HttpResponse(200, """{"id":"11111111-1111-4111-8111-111111111111"}""")
                    method == "POST" && url.endsWith("/v2/field/sessions") ->
                        HttpResponse(201, """{"id":"22222222-2222-4222-8222-222222222222","revision":1}""")
                    method == "GET" && url.contains("/summary") ->
                        HttpResponse(200, """{"session":{"state":"ANCHOR_REQUIRED","revision":1}}""")
                    method == "PUT" && url.contains("/anchor") ->
                        HttpResponse(200, """{"state":"ACTIVE","revision":2}""")
                    method == "POST" && url.contains("/sync/batches") ->
                        HttpResponse(200, """{"results":[{"operation_id":"op-123","state":"APPLIED","resource":{"id":"33333333-3333-4333-8333-333333333333","revision":4}}]}""")
                    else ->
                        HttpResponse(201, "{}")
                }
            },
            allowLocalHttp = true
        )

        val result = uploader.upload(
            SyncQueueItem(operationId = "op-123", entityId = "entity-123", localDefectId = 7),
            defect,
            testPhotos,
            Session(
                id = 3,
                unitLabel = "101동 1501호",
                floorplanAssetId = "ulsan_down_84a",
                startXNorm = .4f,
                startYNorm = .5f,
                startHeadingDeg = 90f,
                anchorLocationLabel = "거실"
            )
        )

        assertEquals("op-123", capture?.clientKey)
        assertEquals("op-123", syncHeaders["Idempotency-Key"])
        assertTrue(body.contains("\"raw_resident_opinion\":\"벽에 균열이 있어요\""))
        assertTrue(syncBody.contains("\"kind\":\"UPSERT_DEFECT\""))
        assertTrue(syncBody.contains("\"final_location_code\":\"AREA_LIVING\""))
        assertTrue(syncBody.contains("\"final_part_code\":\"PART_WALL\""))
        assertTrue(syncBody.contains("\"final_part_detail_code\":\"DETAIL_WALL_SURFACE\""))
        assertTrue(syncBody.contains("\"final_work_kind_code\":\"WORK_FINISH\""))
        assertTrue(syncBody.contains("\"final_cause_code\":\"CAUSE_CRACK\""))
        assertTrue(syncBody.contains("\"priority_code\":\"P2\""))
        assertTrue(call >= 7)
        assertEquals(SyncUploadResult.Applied(4), result)
        closeFile.delete()
        wideFile.delete()
    }

    @Test
    fun `retry backoff is bounded`() {
        assertEquals(60_000L, SyncRetryPolicy.delayMs(0))
        assertEquals(6 * 60 * 60_000L, SyncRetryPolicy.delayMs(100))
    }

    @Test
    fun `taxonomy mapper uses stable codes for room and enums`() {
        val taxonomy = DefectSyncTaxonomy.from(defect)

        assertEquals("AREA_LIVING", taxonomy.locationCode)
        assertEquals("PART_WALL", taxonomy.partCode)
        assertEquals("DETAIL_WALL_SURFACE", taxonomy.partDetailCode)
        assertEquals("WORK_FINISH", taxonomy.workKindCode)
        assertEquals("CAUSE_CRACK", taxonomy.causeCode)
        assertEquals("P2", taxonomy.priorityCode)
    }
}
