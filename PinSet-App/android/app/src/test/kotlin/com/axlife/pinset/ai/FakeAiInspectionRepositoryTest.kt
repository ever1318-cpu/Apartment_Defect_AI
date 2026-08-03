package com.axlife.pinset.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAiInspectionRepositoryTest {
    private val repository = FakeAiInspectionRepository(latencyMs = 0)

    @Test
    fun create_requires_resident_opinion() = runTest {
        val result = runCatching { repository.create(CreateInspectionRequest("")) }
        assertTrue(result.isFailure)
    }

    @Test
    fun water_opinion_leads_to_question_and_ranked_candidates() = runTest {
        val created = repository.create(CreateInspectionRequest("비가 오면 창호에 물이 맺혀요"))
        assertNotNull(created.question)
        assertEquals("water_source", created.question?.id)

        val answered = repository.answer(
            created.inspectionId,
            created.question!!.id,
            "창호 주변·비 올 때"
        )
        assertEquals(2, answered.candidates.size)
        assertEquals(1, answered.candidates.first().rank)
        assertTrue(answered.candidates.first().confidence > answered.candidates.last().confidence)

        val confirmed = repository.confirm(created.inspectionId, answered.candidates.first())
        assertEquals(answered.candidates.first(), confirmed)
    }
}
