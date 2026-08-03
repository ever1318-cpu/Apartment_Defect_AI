package com.axlife.pinset.ui.intro

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.ai.UrlConnectionAiTransport
import com.axlife.pinset.data.ActiveSessionRepo
import com.axlife.pinset.intro.CatalogHouseholdLookup
import com.axlife.pinset.intro.HouseholdMatch
import com.axlife.pinset.intro.InspectionContext
import com.axlife.pinset.intro.InspectionContextStore
import com.axlife.pinset.vision.FloorplanRoomAnchor
import com.axlife.pinset.vision.FloorplanCatalogEntry
import com.axlife.pinset.vision.ReferenceDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

data class ManagerInspectionStats(
    val startDate: String? = null,
    val totalHouseholds: Int = 0,
    val todayHouseholds: Int = 0,
)

data class HouseholdIntroState(
    val query: String = "Master",
    /** 로그인한 실제 사용자 ID. 서버 인증 연동 시 서버 발급 값으로 대체한다. */
    val inspectorId: String = "Master",
    /** 화면·권한 정책에서 공통으로 사용하는 사용자 등급. */
    val inspectorRole: String = "서버관리자",
    val buildingNo: String = "101",
    val unitNo: String = "1501",
    val buildingOptions: List<String> = (101..120).map(Int::toString),
    val unitOptions: List<String> = (1..25).flatMap { floor ->
        (1..4).map { line -> "%d%02d".format(floor, line) }
    },
    val testMode: Boolean = true,
    val floorplanTypes: List<FloorplanCatalogEntry> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val matches: List<HouseholdMatch> = emptyList(),
    val selected: HouseholdMatch? = null,
    val siteMap: Bitmap? = null,
    val floorplan: Bitmap? = null,
    val rooms: List<FloorplanRoomAnchor> = emptyList(),
    val selectedRoom: FloorplanRoomAnchor? = null,
    val anchorAlreadySet: Boolean = false,
    val anchorLocationLabel: String? = null,
    val nearbyBuildingNo: String? = null,
    val nearbyUnitNo: String? = null,
    val nearbyRecommendationStatus: String? = null,
    val managerStats: ManagerInspectionStats? = null,
    /** Completed local records retained for this selected household. */
    val completedInspectionCount: Int = 0,
    /** Recent actual defect save, distinct from the currently selected household. */
    val recentCapturedHousehold: String? = null,
    val nextRevisionNo: Int = 1,
    val error: String? = null
)

class HouseholdIntroViewModel(private val app: PinSetApplication) : ViewModel() {
    private val referenceDb = ReferenceDb(app)
    private val lookup = CatalogHouseholdLookup(referenceDb::catalog)
    private val _state = MutableStateFlow(HouseholdIntroState())
    val state: StateFlow<HouseholdIntroState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private val statsJson = Json { ignoreUnknownKeys = true }

    init {
        val prefs = app.getSharedPreferences("last_household", android.content.Context.MODE_PRIVATE)
        val building = prefs.getString("building_no", "101").orEmpty().ifBlank { "101" }
        val unit = prefs.getString("unit_no", "1501").orEmpty().ifBlank { "1501" }
        _state.update {
            it.copy(
                buildingNo = building,
                unitNo = unit,
                query = "${building}동 ${unit}호",
                floorplanTypes = referenceDb.catalog().filter { entry ->
                    entry.id in setOf("ulsan_down_84a", "ulsan_down_84b")
                }
            )
        }
        updateQuery(_state.value.query)
        viewModelScope.launch {
            app.database.defectDao().latestCapturedHousehold().collect { unitLabel ->
                _state.update { it.copy(recentCapturedHousehold = unitLabel) }
            }
        }
        refreshManagerStatistics()
    }

    fun updateBuilding(value: String) {
        _state.update {
            it.copy(
                buildingNo = value.filter(Char::isDigit).take(3),
                nearbyBuildingNo = null,
                nearbyUnitNo = null,
                nearbyRecommendationStatus = null
            )
        }
        updateAddressQuery()
    }

    fun selectInspectorRole(role: String, inspectorId: String = role) {
        if (role in USER_ROLES) {
            _state.update { it.copy(inspectorRole = role, inspectorId = inspectorId) }
            if (role in setOf("서버관리자", "총괄매니저", "점검매니저")) refreshManagerStatistics()
        }
    }


    fun refreshManagerStatistics() {
        val baseUrl = com.axlife.pinset.data.FieldEndpointPrefs.load(app)
        if (baseUrl.isBlank()) return
        viewModelScope.launch {
            val response = runCatching {
                UrlConnectionAiTransport().request(
                    "GET",
                    "$baseUrl/v2/field/inspections/manager-stats?inspector_id=" +
                        URLEncoder.encode("Master", Charsets.UTF_8.name()),
                    emptyMap(),
                    null
                )
            }.getOrNull() ?: return@launch
            if (response.status !in 200..299) return@launch
            val body = runCatching { statsJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
                ?: return@launch
            _state.update {
                it.copy(
                    managerStats = ManagerInspectionStats(
                        startDate = body["start_date"]?.jsonPrimitive?.contentOrNull,
                        totalHouseholds = body["total_households"]?.jsonPrimitive?.intOrNull ?: 0,
                        todayHouseholds = body["today_households"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                )
            }
        }
    }

    fun updateUnit(value: String) {
        _state.update {
            it.copy(
                unitNo = value.filter(Char::isDigit).take(4),
                nearbyBuildingNo = null,
                nearbyUnitNo = null,
                nearbyRecommendationStatus = null
            )
        }
        updateAddressQuery()
    }

    fun recommendNearbyHousehold(gpsAvailable: Boolean) {
        val current = _state.value
        val (nextBuilding, nextUnit) = nextInspectionAddress(
            current.buildingNo, current.unitNo, current.buildingOptions
        )
        _state.update {
            it.copy(
                nearbyBuildingNo = nextBuilding,
                nearbyUnitNo = nextUnit,
                nearbyRecommendationStatus = if (gpsAvailable) {
                    "GPS 위치와 현재 점검 순서를 기준으로 인접 세대를 추천했습니다."
                } else {
                    "실내 GPS 신호가 약해 현재 점검 순서를 기준으로 인접 세대를 추천했습니다."
                }
            )
        }
    }

    fun applyNearbyHousehold() {
        val current = _state.value
        val building = current.nearbyBuildingNo ?: return
        val unit = current.nearbyUnitNo ?: return
        _state.update {
            it.copy(
                buildingNo = building,
                unitNo = unit,
                nearbyBuildingNo = null,
                nearbyUnitNo = null,
                nearbyRecommendationStatus = null
            )
        }
        updateQuery("${building}동 ${unit}호")
    }

    private fun updateAddressQuery() {
        val s = _state.value
        if (s.buildingNo.isNotBlank() && s.unitNo.isNotBlank()) {
            updateQuery("${s.buildingNo}동 ${s.unitNo}호")
        }
    }

    fun updateQuery(value: String) {
        _state.update {
            it.copy(
                query = value,
                searched = false,
                selected = null,
                siteMap = null,
                floorplan = null,
                rooms = emptyList(),
                selectedRoom = null,
                anchorAlreadySet = false,
                error = null
            )
        }
        searchJob?.cancel()
        if (value.trim().length < 2) {
            _state.update {
                it.copy(matches = emptyList(), selected = null, siteMap = null, floorplan = null, rooms = emptyList())
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(loading = true) }
            val matches = withContext(Dispatchers.Default) { lookup.search(value.trim()) }
            _state.update { it.copy(loading = false, searched = true, matches = matches) }
            if (matches.size == 1) select(matches.first())
        }
    }

    fun useDefault() = select(lookup.fallback())

    fun select(match: HouseholdMatch) {
        viewModelScope.launch {
            _state.update { it.copy(selected = match, loading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val meta = referenceDb.floorplan(match.floorplanId)
                    val catalogEntry = referenceDb.catalog().firstOrNull { it.id == match.floorplanId }
                    val siteMap = catalogEntry?.siteMapFile?.let(referenceDb::loadSiteMapBitmap)
                    LoadedHouseholdAssets(
                        siteMap = siteMap,
                        floorplan = referenceDb.loadFloorplanBitmap(match.floorplanId, meta),
                        rooms = meta.rooms,
                        firstRoom = meta.rooms.firstOrNull { it.label.contains("거실") }
                            ?: meta.rooms.firstOrNull()
                    )
                }
            }.onSuccess { assets ->
                val householdSessions = app.repository.allSessions().first()
                    .filter { session -> session.unitLabel == match.unitLabel }
                val completedSessions = householdSessions.filter { it.done }
                val anchorSession = householdSessions.firstOrNull { session ->
                    session.anchorPhotoNearPath != null &&
                        session.anchorPhotoFarPath != null
                }
                val anchorAlreadySet = anchorSession?.let { session ->
                    session.startXNorm != null && session.startYNorm != null &&
                        (session.anchorPhotoNearPath != null ||
                            session.anchorLocationLabel == "거실 중앙" ||
                            session.anchorLocationLabel == "최초 하자 입력 위치")
                } == true
                _state.update {
                    it.copy(
                        loading = false,
                        siteMap = assets.siteMap,
                        floorplan = assets.floorplan,
                        rooms = assets.rooms,
                        selectedRoom = assets.firstRoom,
                        anchorAlreadySet = anchorAlreadySet,
                        anchorLocationLabel = anchorSession?.anchorLocationLabel,
                        completedInspectionCount = completedSessions.size,
                        nextRevisionNo = (completedSessions.maxOfOrNull { it.revisionNo } ?: 0) + 1
                    )
                }
            }.onFailure {
                _state.update { state ->
                    state.copy(loading = false, error = "도면을 불러오지 못했습니다. 기본 공간 목록으로 계속할 수 있습니다.")
                }
            }
        }
    }

    private data class LoadedHouseholdAssets(
        val siteMap: Bitmap?,
        val floorplan: Bitmap,
        val rooms: List<FloorplanRoomAnchor>,
        val firstRoom: FloorplanRoomAnchor?
    )

    fun selectRoom(room: FloorplanRoomAnchor) {
        _state.update { it.copy(selectedRoom = room) }
    }

    fun selectFloorplanType(floorplanId: String) {
        if (_state.value.selected?.floorplanId == floorplanId) return
        val current = _state.value
        if (current.buildingNo.isBlank() || current.unitNo.isBlank()) return
        select(
            lookup.forAddress(
                buildingNo = current.buildingNo,
                unitNo = current.unitNo,
                floorplanId = floorplanId
            )
        )
    }

    fun beginInspection(onReady: () -> Unit) {
        val household = _state.value.selected ?: return
        val room = _state.value.selectedRoom ?: return
        viewModelScope.launch {
            val existing = app.repository.allSessions().first()
                .firstOrNull { it.unitLabel == household.unitLabel && !it.done }
            val completed = app.repository.allSessions().first()
                .filter { it.unitLabel == household.unitLabel && it.done }
            val latestCompleted = completed.maxByOrNull { it.createdAt }
            val sessionId = existing?.id ?: if (latestCompleted == null) {
                app.repository.createSession(household.unitLabel, household.floorplanId)
            } else {
                app.repository.createAmendmentSession(
                    household.unitLabel, household.floorplanId, latestCompleted.id,
                    (completed.maxOfOrNull { it.revisionNo } ?: 1) + 1
                )
            }
            // New household sessions always begin at the physical entrance,
            // not at the selected room.  This gives every floorplan a stable
            // "현관문 앞" anchor before the clockwise inspection route starts.
            val hasLegacyBathroomAnchor = existing?.let {
                it.startXNorm == 0.88f && it.startYNorm == 0.48f
            } == true
            if (existing?.startXNorm == null || hasLegacyBathroomAnchor) {
                val entrance = runCatching {
                    ReferenceDb(app).floorplan(household.floorplanId).entrance
                }.getOrNull()
                app.repository.setInitialRoomAnchor(
                    id = sessionId,
                    xNorm = entrance?.cx ?: 0.73f,
                    yNorm = entrance?.cy ?: 0.25f,
                    roomLabel = "현관문 앞"
                )
            }
            ActiveSessionRepo.set(app, sessionId)
            app.getSharedPreferences("last_household", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("building_no", _state.value.buildingNo)
                .putString("unit_no", _state.value.unitNo)
                .apply()
            InspectionContextStore.save(
                app,
                InspectionContext(
                    householdId = household.householdId,
                    unitLabel = household.unitLabel,
                    floorplanId = household.floorplanId,
                    roomCode = room.id,
                    roomLabel = room.label,
                    sourceType = if (household.fallback) "fallback" else "catalog"
                )
            )
            onReady()
        }
    }

    /** Opens report/review for the selected household without requiring a new capture. */
    fun beginReview(onReady: () -> Unit) {
        val current = _state.value
        val household = current.selected ?: return
        viewModelScope.launch {
            val existing = app.repository.allSessions().first()
                .firstOrNull { it.unitLabel == household.unitLabel && !it.done }
            val completed = app.repository.allSessions().first()
                .filter { it.unitLabel == household.unitLabel && it.done }
            val latestCompleted = completed.maxByOrNull { it.createdAt }
            val sessionId = existing?.id ?: if (latestCompleted == null) {
                app.repository.createSession(household.unitLabel, household.floorplanId)
            } else {
                app.repository.createAmendmentSession(
                    household.unitLabel, household.floorplanId, latestCompleted.id,
                    (completed.maxOfOrNull { it.revisionNo } ?: 1) + 1
                )
            }
            ActiveSessionRepo.set(app, sessionId)
            app.getSharedPreferences("last_household", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("building_no", current.buildingNo)
                .putString("unit_no", current.unitNo)
                .apply()
            onReady()
        }
    }

    /** Starts a public/common-area inspection outside a resident household. */
    fun beginCommonArea(locationLabel: String, onReady: () -> Unit) {
        val location = locationLabel.trim().ifBlank { return }
        val unitLabel = "공용부 · $location"
        viewModelScope.launch {
            val existing = app.repository.allSessions().first()
                .firstOrNull { it.unitLabel == unitLabel && !it.done }
            val sessionId = existing?.id ?: app.repository.createSession(
                unitLabel = unitLabel,
                floorplanAssetId = "ulsan_down_84a"
            )
            if (existing?.startXNorm == null) {
                app.repository.setInitialRoomAnchor(
                    id = sessionId,
                    xNorm = 0.5f,
                    yNorm = 0.5f,
                    roomLabel = location
                )
            }
            ActiveSessionRepo.set(app, sessionId)
            InspectionContextStore.save(
                app,
                InspectionContext(
                    householdId = "COMMON-0000",
                    unitLabel = unitLabel,
                    floorplanId = "ulsan_down_84a",
                    roomCode = "common_area",
                    roomLabel = location,
                    sourceType = "common_area"
                )
            )
            onReady()
        }
    }

    /**
     * Start the defect-image workflow without a separate anchor-photo step.
     * - livingRoomAnchor=true: floorplan living-room center becomes the origin.
     * - livingRoomAnchor=false: leave the origin unset; the first confirmed
     *   defect pin becomes the origin in PinPlacementViewModel.
     */
    fun beginDirectDefect(livingRoomAnchor: Boolean, onReady: () -> Unit) {
        val current = _state.value
        val household = current.selected ?: return
        val livingRoom = current.rooms.firstOrNull { it.label.contains("거실") }
        val contextRoom = if (livingRoomAnchor) {
            livingRoom ?: current.selectedRoom ?: current.rooms.firstOrNull()
        } else {
            current.selectedRoom ?: livingRoom ?: current.rooms.firstOrNull()
        } ?: return
        viewModelScope.launch {
            val existing = app.repository.allSessions().first()
                .firstOrNull { it.unitLabel == household.unitLabel && !it.done }
            val completed = app.repository.allSessions().first()
                .filter { it.unitLabel == household.unitLabel && it.done }
            val latestCompleted = completed.maxByOrNull { it.createdAt }
            val sessionId = existing?.id ?: if (latestCompleted == null) {
                app.repository.createSession(household.unitLabel, household.floorplanId)
            } else {
                app.repository.createAmendmentSession(
                    household.unitLabel, household.floorplanId, latestCompleted.id,
                    (completed.maxOfOrNull { it.revisionNo } ?: 1) + 1
                )
            }
            val hasLegacyBathroomAnchor = existing?.let {
                it.startXNorm == 0.88f && it.startYNorm == 0.48f
            } == true
            if (existing?.startXNorm == null || hasLegacyBathroomAnchor) {
                val entrance = runCatching {
                    ReferenceDb(app).floorplan(household.floorplanId).entrance
                }.getOrNull()
                app.repository.setInitialRoomAnchor(
                    id = sessionId,
                    xNorm = entrance?.cx ?: 0.73f,
                    yNorm = entrance?.cy ?: 0.25f,
                    roomLabel = "현관문 앞"
                )
            }
            ActiveSessionRepo.set(app, sessionId)
            app.getSharedPreferences("last_household", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("building_no", current.buildingNo)
                .putString("unit_no", current.unitNo)
                .apply()
            InspectionContextStore.save(
                app,
                InspectionContext(
                    householdId = household.householdId,
                    unitLabel = household.unitLabel,
                    floorplanId = household.floorplanId,
                    roomCode = contextRoom.id,
                    roomLabel = contextRoom.label,
                    sourceType = if (household.fallback) "fallback" else "catalog"
                )
            )
            onReady()
        }
    }

    companion object {
        val USER_ROLES = listOf("서버관리자", "총괄매니저", "점검매니저", "세대 소유주")

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PinSetApplication
                HouseholdIntroViewModel(app)
            }
        }
    }
}

/** Next route address: increase unit within a building; after the last unit,
 * move to the next building and restart at its first unit. */
internal fun nextInspectionAddress(
    buildingNo: String,
    unitNo: String,
    buildingOptions: List<String> = (101..120).map(Int::toString)
): Pair<String, String> {
    val unit = unitNo.toIntOrNull() ?: return buildingNo to unitNo
    val floor = unit / 100
    val line = unit % 100
    if (line in 1..3) return buildingNo to "%d%02d".format(floor, line + 1)
    if (floor < 25) return buildingNo to "%d01".format(floor + 1)
    val currentIndex = buildingOptions.indexOf(buildingNo)
    val nextBuilding = buildingOptions.getOrElse(
        if (currentIndex >= 0) currentIndex + 1 else 0
    ) { buildingOptions.firstOrNull() ?: buildingNo }
    return nextBuilding to "101"
}

internal fun nextInspectionUnit(unitNo: String): String = nextInspectionAddress("101", unitNo).second
