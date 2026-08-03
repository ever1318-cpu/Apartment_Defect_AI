package com.axlife.pinset.ai

import kotlinx.coroutines.delay
import java.util.UUID

data class InspectionSession(
    val inspectionId: String,
    val question: InspectionQuestion?,
    val candidates: List<ClassificationCandidate>
)

interface AiInspectionRepository {
    suspend fun create(request: CreateInspectionRequest): InspectionSession
    suspend fun answer(inspectionId: String, questionId: String, answer: String): InspectionSession
    suspend fun confirm(inspectionId: String, candidate: ClassificationCandidate): ClassificationCandidate
}

/**
 * API v2와 동일한 화면 상태 전이를 검증하기 위한 결정적 fake입니다.
 * 네트워크·DB·외부 LLM을 호출하지 않으며 Real repository로 교체할 경계를 제공합니다.
 */
class FakeAiInspectionRepository(
    private val latencyMs: Long = 250
) : AiInspectionRepository {
    private val sessions = mutableMapOf<String, MutableList<Pair<String, String>>>()

    override suspend fun create(request: CreateInspectionRequest): InspectionSession {
        delay(latencyMs)
        require(request.residentOpinion.isNotBlank()) { "입주자 의견을 입력해 주세요." }
        val id = UUID.randomUUID().toString()
        sessions[id] = mutableListOf()
        return InspectionSession(id, firstQuestion(request.residentOpinion), emptyList())
    }

    override suspend fun answer(
        inspectionId: String,
        questionId: String,
        answer: String
    ): InspectionSession {
        delay(latencyMs)
        val answers = sessions[inspectionId] ?: error("검사 세션을 찾을 수 없습니다.")
        answers += questionId to answer
        return InspectionSession(
            inspectionId = inspectionId,
            question = null,
            candidates = buildCandidates(answer)
        )
    }

    override suspend fun confirm(
        inspectionId: String,
        candidate: ClassificationCandidate
    ): ClassificationCandidate {
        delay(latencyMs)
        check(sessions.containsKey(inspectionId)) { "검사 세션을 찾을 수 없습니다." }
        return candidate
    }

    private fun firstQuestion(opinion: String): InspectionQuestion {
        val isWater = listOf("누수", "물", "습기", "곰팡이").any(opinion::contains)
        return if (isWater) {
            InspectionQuestion(
                id = "water_source",
                text = "물이 보이는 위치와 발생 시점을 확인해 주세요.",
                options = listOf("천장·비 올 때", "배관 주변·상시", "창호 주변·비 올 때")
            )
        } else {
            InspectionQuestion(
                id = "surface",
                text = "하자가 가장 뚜렷한 부위를 선택해 주세요.",
                options = listOf("벽", "천장", "바닥")
            )
        }
    }

    private fun buildCandidates(answer: String): List<ClassificationCandidate> {
        val water = answer.contains("물") || answer.contains("배관") || answer.contains("비")
        val primary = if (water) {
            ClassificationCandidate(
                1, "설비", "세대 내부", "배관·천장", "누수 흔적",
                "배관 또는 외부 유입 추정", 0.82f,
                "입주자 의견과 추가 답변에서 수분 관련 단서가 확인되었습니다."
            )
        } else {
            ClassificationCandidate(
                1, "건축 마감", "세대 내부", answer, "표면 손상",
                "시공 또는 재료 수축 추정", 0.76f,
                "선택한 부위와 현장 의견을 함께 반영한 1순위 후보입니다."
            )
        }
        return listOf(
            primary,
            primary.copy(
                rank = 2,
                workKind = "건축",
                defectCause = "원인 미확정·현장 확인 필요",
                confidence = (primary.confidence - 0.18f).coerceAtLeast(0f),
                rationale = "영상 원본과 현장 조건을 추가 확인해야 하는 대안 후보입니다."
            )
        )
    }
}
