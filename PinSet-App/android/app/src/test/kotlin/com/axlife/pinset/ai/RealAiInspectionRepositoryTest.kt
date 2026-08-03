package com.axlife.pinset.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RealAiInspectionRepositoryTest {
    private data class Recorded(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?
    )

    @Test
    fun orchestrates_v2_workflow() = runTest {
        val responses = ArrayDeque(
            listOf(
                HttpResponse(201, """{"id":"inspection-1","revision":1}"""),
                HttpResponse(202, """{"id":"analysis-1","status":"COMPLETED"}"""),
                HttpResponse(201, """{"id":"assistant-1","inspection_id":"inspection-1","state":"NEEDS_CLARIFICATION","turn_count":0,"max_turns":3,"question":{"id":"moisture","text":"젖어 있습니까?","options":[{"id":"wet","label":"젖어 있음"}]}}"""),
                HttpResponse(200, """{"id":"assistant-1","inspection_id":"inspection-1","state":"PROPOSED","turn_count":1,"max_turns":3,"proposal":{"area_code":"AREA_LIVING","part_code":"PART_WALL","part_detail_code":"DETAIL_WALLPAPER","work_kind_code":"WORK_PLUMBING","priority_code":"P2","suspected_cause_code":"CAUSE_LEAK","standardized_opinion":"벽면 누수 흔적"}}"""),
                HttpResponse(200, """{"id":"inspection-1","revision":3}"""),
                HttpResponse(200, """{"id":"inspection-1","revision":4,"state":"USER_CONFIRMED"}""")
            )
        )
        val recorded = mutableListOf<Recorded>()
        val transport = AiHttpTransport { method, url, headers, body ->
            recorded += Recorded(method, url, headers, body)
            responses.removeFirst()
        }
        val repository = RealAiInspectionRepository(
            "https://api.test.example",
            transport,
            bearerToken = { "test-token" }
        )
        val created = repository.create(
            CreateInspectionRequest(
                residentOpinion = "벽에서 물이 보여요",
                imageIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                locationHint = "거실"
            )
        )
        assertEquals("moisture", created.question?.id)

        val proposed = repository.answer(created.inspectionId, "moisture", "젖어 있음")
        val candidate = proposed.candidates.single()
        assertEquals("WORK_PLUMBING", candidate.workKind)
        assertEquals("CAUSE_LEAK", candidate.codes["suspected_cause_code"])

        repository.confirm(created.inspectionId, candidate)

        assertEquals(6, recorded.size)
        assertTrue(recorded.all { it.headers["Idempotency-Key"]?.length ?: 0 >= 16 })
        assertEquals("Bearer test-token", recorded.first().headers["Authorization"])
        assertEquals("3", recorded[5].headers["If-Match"])
        assertTrue(recorded[3].body.orEmpty().contains("\"option_id\":\"wet\""))
    }

    @Test
    fun rejects_missing_uploaded_media_before_network_call() = runTest {
        var called = false
        val repository = RealAiInspectionRepository(
            "https://api.test.example",
            AiHttpTransport { _, _, _, _ ->
                called = true
                HttpResponse(500, "")
            }
        )
        val result = runCatching {
            repository.create(CreateInspectionRequest("균열이 보여요"))
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("이미지 ID 2개"))
        assertTrue(!called)
    }

    @Test
    fun rejects_non_https_endpoint() {
        val result = runCatching { RealAiInspectionRepository("http://example.test") }
        assertTrue(result.isFailure)
    }

    @Test
    fun allows_debug_http_only_when_explicitly_enabled() {
        RealAiInspectionRepository(
            "http://10.0.2.2:8000",
            allowLocalHttp = true
        )
        RealAiInspectionRepository(
            "http://192.168.0.10:8000",
            allowLocalHttp = true
        )
        val disabled = runCatching {
            RealAiInspectionRepository("http://192.168.0.10:8000")
        }
        assertTrue(disabled.isFailure)
    }
}
