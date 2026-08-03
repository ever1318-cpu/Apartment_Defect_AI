package com.axlife.pinset.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateInspectionRequest(
    @SerialName("resident_opinion") val residentOpinion: String,
    @SerialName("image_ids") val imageIds: List<String> = emptyList(),
    @SerialName("location_hint") val locationHint: String? = null
)

@Serializable
data class InspectionQuestion(
    val id: String,
    val text: String,
    val options: List<String>
)

@Serializable
data class ClassificationCandidate(
    val rank: Int,
    @SerialName("work_kind") val workKind: String,
    val location: String,
    @SerialName("defect_part") val defectPart: String,
    @SerialName("part_detail") val partDetail: String,
    @SerialName("defect_cause") val defectCause: String,
    val confidence: Float,
    val rationale: String,
    val codes: Map<String, String> = emptyMap()
)

enum class InspectionStage {
    DRAFT, ANALYZING, QUESTION, PROPOSAL, CONFIRMED, ERROR
}

data class AiInspectionUiState(
    val apiMode: String = "Fake API",
    val stage: InspectionStage = InspectionStage.DRAFT,
    val inspectionId: String? = null,
    val residentOpinion: String = "",
    val question: InspectionQuestion? = null,
    val questionCount: Int = 0,
    val candidates: List<ClassificationCandidate> = emptyList(),
    val selectedCandidate: ClassificationCandidate? = null,
    val confirmedSummary: String? = null,
    val errorMessage: String? = null
)
