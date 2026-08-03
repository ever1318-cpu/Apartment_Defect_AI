package com.axlife.pinset.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.data.ActiveSessionRepo
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectStatus
import com.axlife.pinset.ui.pinset.cleanRecommendation
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.data.repo.DefectRepository
import com.axlife.pinset.sync.SessionCompletionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeViewModel(private val app: PinSetApplication) : ViewModel() {
    private val repo: DefectRepository = app.repository

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val active = repo.activeSession(app)
            _state.update { it.copy(activeSessionId = active.id, unitLabel = active.unitLabel, activeSession = active) }

            combine(repo.allSessions(), ActiveSessionRepo.observe(app)) { sessions, activeId ->
                sessions to activeId
            }.collectLatest { (sessions, activeId) ->
                val current = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()
                _state.update {
                    it.copy(
                        sessions = sessions,
                        activeSessionId = current?.id,
                        activeSession = current,
                        unitLabel = current?.unitLabel ?: it.unitLabel,
                        anchorSet = current?.startXNorm != null &&
                            current.startYNorm != null &&
                            (
                                current.anchorPhotoNearPath != null ||
                                    current.anchorLocationLabel == "거실 중앙" ||
                                    current.anchorLocationLabel == "최초 하자 입력 위치"
                                ),
                        anchorHeadingDeg = current?.startHeadingDeg,
                        anchorLocationLabel = current?.anchorLocationLabel ?: "현관문",
                        anchorPhotoPath = current?.anchorPhotoNearPath
                            ?: current?.anchorPhotoFarPath
                    )
                }
                if (current != null) {
                    launch {
                        repo.observeDefects(current.id).collect { defects ->
                            _state.update {
                                it.copy(
                                    total = defects.size,
                                    pending = defects.count { d -> d.status == DefectStatus.PENDING },
                                    done = defects.count { d -> d.status == DefectStatus.DONE },
                                    defects = defects,
                                    recent = defects.take(6).map { d -> d.toRecent() }
                                )
                            }
                        }
                    }
                    // Load floorplan bitmap once per session so the home mini-map
                    // has a real background image (not just the schematic).
                    launch {
                        val db = com.axlife.pinset.vision.ReferenceDb(app)
                        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val custom = current.customFloorplanPath
                            if (custom != null && java.io.File(custom).exists()) {
                                runCatching { android.graphics.BitmapFactory.decodeFile(custom) }.getOrNull()
                            } else {
                                runCatching {
                                    val meta = db.floorplan(current.floorplanAssetId)
                                    db.loadFloorplanBitmap(current.floorplanAssetId, meta)
                                }.getOrNull()
                            }
                        }
                        _state.update {
                            it.copy(
                                floorplanBitmap = bmp,
                                floorplanIsCustom = current.customFloorplanPath != null
                            )
                        }
                    }
                }
            }
        }
    }

    fun selectSession(id: Long) {
        viewModelScope.launch { ActiveSessionRepo.set(app, id) }
    }

    fun createSession(label: String, floorplanId: String) {
        viewModelScope.launch {
            val id = repo.createSession(label, floorplanId)
            ActiveSessionRepo.set(app, id)
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            repo.deleteSession(id)
            val survivor = repo.allSessions().first().firstOrNull()
            survivor?.let { ActiveSessionRepo.set(app, it.id) }
        }
    }

    /** Closes the current household directly from the quality-inspection home. */
    fun finishInspection(onFinished: (String) -> Unit, onBlocked: (String) -> Unit) {
        val session = _state.value.activeSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(isClosing = true) }
            if (repo.incompleteSyncCount(session.id) > 0) app.syncManager.flush()
            val remaining = repo.incompleteSyncCount(session.id)
            if (remaining > 0) {
                _state.update { it.copy(isClosing = false) }
                onBlocked("미전송 사진 또는 하자 정보가 ${remaining}건 있습니다. 통신 상태를 확인한 뒤 다시 마감해 주세요.")
                return@launch
            }
            val uploader = app.sessionCompletionUploader
            if (uploader == null) {
                _state.update { it.copy(isClosing = false) }
                onBlocked("서버 주소가 설정되지 않아 세대점검을 마감할 수 없습니다.")
                return@launch
            }
            when (val result = uploader.complete(session)) {
                is SessionCompletionResult.Blocked -> {
                    _state.update { it.copy(isClosing = false) }
                    onBlocked(result.message)
                }
                is SessionCompletionResult.Completed -> {
                    repo.finishSession(session.id)
                    ActiveSessionRepo.clear(app)
                    app.pendingCapture = null
                    app.pendingAiLocationHint = null
                    app.pendingAdditionalPhotoDefectId = null
                    _state.update { it.copy(isClosing = false) }
                    onFinished("사진·하자 정보 ${result.total}건 전송 및 세대점검 마감이 완료되었습니다.")
                }
            }
        }
    }

    private fun Defect.toRecent(): RecentDefect {
        // Prefer the confirmed final classification path when set; otherwise
        // fall back to a legacy "room · areaDetail" summary so pre-migration
        // defects still render sensibly.
        val label = finalPathText.takeIf { it.isNotBlank() }?.let(::cleanRecommendation)
            ?: buildString {
                append(roomLabel)
                if (areaDetail.isNotBlank()) append(" · ").append(areaDetail)
            }
        val statusLabel = when (status) {
            DefectStatus.PENDING -> "접수중"
            DefectStatus.DONE -> "완료"
        }
        return RecentDefect(
            id = id,
            index = defectIndex,
            pathLabel = label,
            statusLabel = statusLabel,
            status = status,
            timeText = SimpleDateFormat("MM-dd HH:mm", Locale.KOREAN).format(Date(createdAt))
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PinSetApplication
                HomeViewModel(app)
            }
        }
    }
}
