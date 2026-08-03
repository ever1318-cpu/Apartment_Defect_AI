package com.axlife.pinset.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axlife.pinset.BuildConfig
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.data.entity.SlotRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiInspectionViewModel(
    private val repository: AiInspectionRepository,
    private val mediaUploader: AiMediaUploader? = null,
    private val captureContext: () -> AiCaptureContext? = { null }
) : ViewModel() {
    private val apiMode = if (repository is RealAiInspectionRepository) "Real API" else "Fake API"
    private val _state = MutableStateFlow(AiInspectionUiState(apiMode = apiMode))
    val state: StateFlow<AiInspectionUiState> = _state.asStateFlow()

    fun updateOpinion(value: String) {
        if (_state.value.stage == InspectionStage.DRAFT) {
            _state.update { it.copy(residentOpinion = value, errorMessage = null) }
        }
    }

    fun startAnalysis() {
        val opinion = _state.value.residentOpinion.trim()
        if (opinion.isBlank()) {
            _state.update { it.copy(errorMessage = "입주자 의견을 입력해 주세요.") }
            return
        }
        _state.update { it.copy(stage = InspectionStage.ANALYZING, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val context = captureContext()
                val imageIds = if (repository is RealAiInspectionRepository) {
                    val uploader = mediaUploader
                        ?: error("실제 API 미디어 업로더가 구성되지 않았습니다.")
                    val capture = context
                        ?: error("원거리·근거리 촬영 이미지가 필요합니다.")
                    uploader.upload(capture)
                } else {
                    emptyList()
                }
                repository.create(
                    CreateInspectionRequest(
                        residentOpinion = opinion,
                        imageIds = imageIds,
                        locationHint = context?.locationHint
                    )
                )
            }.onSuccess { session ->
                _state.update {
                    it.copy(
                        stage = if (session.question != null) InspectionStage.QUESTION else InspectionStage.PROPOSAL,
                        inspectionId = session.inspectionId,
                        question = session.question,
                        candidates = session.candidates
                    )
                }
            }.onFailure(::showError)
        }
    }

    fun answerQuestion(answer: String) {
        val snapshot = _state.value
        val id = snapshot.inspectionId ?: return
        val question = snapshot.question ?: return
        _state.update { it.copy(stage = InspectionStage.ANALYZING, errorMessage = null) }
        viewModelScope.launch {
            runCatching { repository.answer(id, question.id, answer) }
                .onSuccess { session ->
                    _state.update {
                        it.copy(
                            stage = if (session.question != null) InspectionStage.QUESTION else InspectionStage.PROPOSAL,
                            question = session.question,
                            questionCount = it.questionCount + 1,
                            candidates = session.candidates,
                            selectedCandidate = session.candidates.firstOrNull()
                        )
                    }
                }.onFailure(::showError)
        }
    }

    fun selectCandidate(candidate: ClassificationCandidate) {
        _state.update { it.copy(selectedCandidate = candidate) }
    }

    fun confirm() {
        val snapshot = _state.value
        val id = snapshot.inspectionId ?: return
        val candidate = snapshot.selectedCandidate ?: snapshot.candidates.firstOrNull() ?: return
        _state.update { it.copy(stage = InspectionStage.ANALYZING, errorMessage = null) }
        viewModelScope.launch {
            runCatching { repository.confirm(id, candidate) }
                .onSuccess { confirmed ->
                    _state.update {
                        it.copy(
                            stage = InspectionStage.CONFIRMED,
                            selectedCandidate = confirmed,
                            confirmedSummary = "${confirmed.workKind} · ${confirmed.defectPart} · ${confirmed.partDetail}"
                        )
                    }
                }.onFailure(::showError)
        }
    }

    fun restart() {
        _state.value = AiInspectionUiState(apiMode = apiMode)
    }

    private fun showError(error: Throwable) {
        _state.update {
            it.copy(
                stage = InspectionStage.ERROR,
                errorMessage = error.message ?: "분석 중 오류가 발생했습니다."
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PinSetApplication
                val baseUrl = BuildConfig.AI_API_BASE_URL
                AiInspectionViewModel(
                    AiInspectionRepositoryProvider.create(
                        baseUrl,
                        allowLocalHttp = BuildConfig.DEBUG
                    ),
                    mediaUploader = baseUrl.takeIf { it.isNotBlank() }
                        ?.let { RealAiMediaUploader(it, allowLocalHttp = BuildConfig.DEBUG) },
                    captureContext = captureContext@{
                        val capture = app.pendingCapture ?: return@captureContext null
                        val wide = capture.forSlot(SlotRole.C)
                            ?: capture.shots.lastOrNull()
                            ?: return@captureContext null
                        val close = capture.forSlot(SlotRole.A)
                            ?: capture.primary
                            ?: return@captureContext null
                        AiCaptureContext(
                            widePath = wide.filePath,
                            closePath = close.filePath,
                            locationHint = app.pendingAiLocationHint
                        )
                    }
                )
            }
        }
    }
}
