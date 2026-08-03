package com.axlife.pinset.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectStatus
import com.axlife.pinset.data.repo.DefectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.axlife.pinset.sync.SessionCompletionResult

data class HistoryState(
    val defects: List<Defect> = emptyList(),
    val session: com.axlife.pinset.data.entity.Session? = null,
    val floorplanBitmap: android.graphics.Bitmap? = null,
    val floorplanIsCustom: Boolean = false,
    val isClosing: Boolean = false
)

class HistoryViewModel(private val app: PinSetApplication) : ViewModel() {
    private val repo: DefectRepository = app.repository
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Re-run when the user switches session on Home. `collectLatest`
            // cancels the previous inner collector, so we don't leak flows.
            com.axlife.pinset.data.ActiveSessionRepo.observe(app).collectLatest { _ ->
                val session = repo.activeSession(app)
                val custom = session.customFloorplanPath
                val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (custom != null && java.io.File(custom).exists()) {
                        android.graphics.BitmapFactory.decodeFile(custom)
                    } else {
                        val db = com.axlife.pinset.vision.ReferenceDb(app)
                        runCatching {
                            val meta = db.floorplan(session.floorplanAssetId)
                            db.loadFloorplanBitmap(session.floorplanAssetId, meta)
                        }.getOrNull()
                    }
                }
                _state.update {
                    it.copy(
                        session = session,
                        floorplanBitmap = bitmap,
                        floorplanIsCustom = custom != null
                    )
                }
                repo.observeDefects(session.id).collect { list ->
                    _state.update { it.copy(defects = list) }
                }
            }
        }
    }

    fun toggleStatus(d: Defect) {
        viewModelScope.launch {
            val next = if (d.status == DefectStatus.DONE) DefectStatus.PENDING else DefectStatus.DONE
            repo.updateDefect(d.copy(status = next))
        }
    }

    fun finishInspection(onFinished: (String) -> Unit, onBlocked: (String) -> Unit) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(isClosing = true) }
            if (repo.incompleteSyncCount(session.id) > 0) {
                app.syncManager.flush()
            }
            val remaining = repo.incompleteSyncCount(session.id)
            if (remaining > 0) {
                _state.update { it.copy(isClosing = false) }
                onBlocked("미전송 사진 또는 하자 정보가 ${remaining}건 있습니다. 통신 상태를 확인한 뒤 다시 마감해 주세요.")
                return@launch
            }
            val uploader = app.sessionCompletionUploader
            if (uploader == null) {
                _state.update { it.copy(isClosing = false) }
                onBlocked("서버 주소가 설정되지 않아 세션을 마감할 수 없습니다.")
                return@launch
            }
            when (val result = uploader.complete(session)) {
                is SessionCompletionResult.Blocked -> {
                    _state.update { it.copy(isClosing = false) }
                    onBlocked(result.message)
                    return@launch
                }
                is SessionCompletionResult.Completed -> {
                    repo.finishSession(session.id)
                    prepareNextHousehold(session.unitLabel)
                    com.axlife.pinset.data.ActiveSessionRepo.clear(app)
                    app.pendingCapture = null
                    app.pendingAiLocationHint = null
                    app.pendingAdditionalPhotoDefectId = null
                    _state.update { it.copy(isClosing = false) }
                    val next = result.nextUnitNo?.let { " 다음 추천 호수: $it" }.orEmpty()
                    onFinished("사진·하자 정보 ${result.total}건 전송 및 서버 세션 마감이 완료되었습니다.$next")
                }
            }
        }
    }

    /**
     * Move the intro default to the next unit after a household is closed.
     * Ulsan Down's default catalog uses four lines per floor: 1501 → 1502 →
     * 1503 → 1504 → 1601. Unknown labels are left unchanged for manual entry.
     */
    private fun prepareNextHousehold(unitLabel: String) {
        val match = Regex("(\\d{3}).*?(\\d{3,4})").find(unitLabel) ?: return
        val building = match.groupValues[1]
        val unit = match.groupValues[2]
        val buildingOptions = (101..120).map(Int::toString)
        val (nextBuilding, nextUnit) = com.axlife.pinset.ui.intro.nextInspectionAddress(
            building, unit, buildingOptions
        )
        app.getSharedPreferences("last_household", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("building_no", nextBuilding)
            .putString("unit_no", nextUnit)
            .apply()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PinSetApplication
                HistoryViewModel(app)
            }
        }
    }
}
