package com.axlife.pinset.ui.pinset

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.camera.CaptureResult
import com.axlife.pinset.camera.CapturedShot
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectPhoto
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Lens
import com.axlife.pinset.data.entity.PinSource
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.intro.InspectionContextStore
import com.axlife.pinset.vision.AutoPin
import com.axlife.pinset.vision.FloorplanMeta
import com.axlife.pinset.vision.FloorplanRoomAnchor
import com.axlife.pinset.vision.PoseToRoom
import com.axlife.pinset.vision.ReferenceDb
import com.axlife.pinset.vision.RoomMatcher
import com.axlife.pinset.vision.surfaceFromPitch
import com.axlife.pinset.vision.CaptureSurfaceBand
import com.axlife.pinset.vision.captureSurfaceBandFromPitch
import com.axlife.pinset.vision.SurfaceFusionAnalyzer
import com.axlife.pinset.vision.AiInput
import com.axlife.pinset.vision.RuleBasedAiClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PinPlacementState(
    val matching: Boolean = true,
    val floorplanBitmap: Bitmap? = null,
    val floorplanIsCustom: Boolean = false,
    val floorplan: FloorplanMeta? = null,
    val capture: CaptureResult? = null,
    val roomId: String? = null,
    val roomLabel: String? = null,
    val confidence: Float = 0f,
    val alternatives: List<Pair<String, Float>> = emptyList(),
    /** Estimated defect target pin. */
    val pin: PinPos? = null,
    /** Camera/inspector position at the instant of capture. */
    val capturePin: PinPos? = null,
    val pinSource: PinSource = PinSource.AUTO,
    val error: String? = null,
    /** Surface auto-detected from the IMU pitch at capture time. */
    val autoSurface: com.axlife.pinset.data.entity.Surface = com.axlife.pinset.data.entity.Surface.WALL,
    /** Camera angle classification: ceiling / ceiling-wall / wall / wall-floor / floor. */
    val autoSurfaceBand: CaptureSurfaceBand = CaptureSurfaceBand.WALL,
    /** Confidence of the IMU + image-boundary surface fusion, 0..1. */
    val surfaceFusionConfidence: Float = 0f,
    /** Normalized photo Y coordinate for a detected ceiling/wall evidence line. */
    val surfaceBoundaryYNorm: Float? = null,
    /** Trade label supplied by the PostgreSQL taxonomy for the top detail candidate. */
    val suggestedTradeLabel: String = "",
    val suggestedDetails: List<String> = emptyList(),
    /** PostgreSQL master or last-known-good local cache. */
    val taxonomyStatus: String? = null,
    val positionDraft: String = "",
    val clockwiseRoute: List<String> = emptyList(),
    /** AI assistant's initial suggestion. Regenerated when the resident opinion changes. */
    val aiPathText: String = "",
    val aiConfidence: Float = 0f,
    val aiRationale: String = "",
    /** Every defect already recorded in this session — drawn as background
     *  pins so the operator can see the growing set. Loaded once when the
     *  screen opens; not observed since the new defect isn't saved yet. */
    val existingDefects: List<com.axlife.pinset.data.entity.Defect> = emptyList(),
    /** Entrance anchor point on the floorplan (0..1) — start of the nav
     *  trail. Null when the session hasn't been anchored yet. */
    val anchorX: Float? = null,
    val anchorY: Float? = null
)

data class PinPos(val x: Float, val y: Float)

class PinPlacementViewModel(private val app: PinSetApplication) : ViewModel() {

    private val referenceDb = ReferenceDb(app)
    private val matcher = RoomMatcher(referenceDb)
    private val repo = app.repository
    private val ai = RuleBasedAiClassifier()
    // Latest AI prefs snapshot — refreshed via [collectAiPrefs] below so the
    // vision classifier can be constructed on demand each time the sheet
    // fires an analyze request.
    private var aiPrefs: com.axlife.pinset.data.AiPrefs.Snapshot =
        com.axlife.pinset.data.AiPrefs.Snapshot(false, "", "gemini-2.0-flash-exp")

    init {
        viewModelScope.launch {
            com.axlife.pinset.data.AiPrefs.observe(app).collect { aiPrefs = it }
        }
    }

    private val _state = MutableStateFlow(PinPlacementState())
    val state: StateFlow<PinPlacementState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val capture = app.pendingCapture ?: com.axlife.pinset.camera.CaptureResult(emptyList())
            if (false) {
                _state.update { it.copy(matching = false, error = "촬영 데이터가 없습니다") }
                return@launch
            }
            val session = repo.activeSession(app)
            val meta = withContext(Dispatchers.IO) { referenceDb.floorplan(session.floorplanAssetId) }
            val bmp: android.graphics.Bitmap? = withContext(Dispatchers.IO) {
                val custom = session.customFloorplanPath
                if (custom != null && java.io.File(custom).exists()) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(custom) }.getOrNull()
                } else {
                    runCatching { referenceDb.loadFloorplanBitmap(session.floorplanAssetId, meta) }.getOrNull()
                }
            }
            val isCustom = session.customFloorplanPath != null
            // Pre-load previously-saved defects so the operator sees the
            // whole accumulated set on the floorplan while placing the new
            // pin. Read once — this list stays static for the screen's life.
            val existing = withContext(Dispatchers.IO) {
                app.database.defectDao().observeBySession(session.id).first()
            }

            // Priority 1: use the AR pose from ARCore (if the user set an anchor
            // and tracking was live). This yields room + exact floorplan point.
            val pose = capture.pose
            val arRoom: FloorplanRoomAnchor?
            val arPin: PinPos?
            val arConfidence: Float
            if (pose.arWorldX != null && pose.arWorldZ != null) {
                val cal = PoseToRoom.DEFAULT_101_1502
                val (x, y) = PoseToRoom.toFloorplan(pose.arWorldX, pose.arWorldZ, cal)
                arRoom = PoseToRoom.roomAt(x, y, meta)
                arPin = PinPos(x, y)
                arConfidence = if (arRoom != null) 0.95f else 0.6f
            } else if (pose.pdrRelXMeters != null && pose.pdrRelZMeters != null) {
                val anchorX = session.startXNorm ?: meta.entrance?.cx ?: 0.5f
                val anchorY = session.startYNorm ?: meta.entrance?.cy ?: 0.5f
                val calibration = PoseToRoom.DEFAULT_101_1502.copy(anchorXNorm = anchorX, anchorYNorm = anchorY)
                val (x, y) = PoseToRoom.toFloorplan(pose.pdrRelXMeters, pose.pdrRelZMeters, calibration)
                arRoom = PoseToRoom.roomAt(x, y, meta)
                arPin = PinPos(x, y)
                arConfidence = if (arRoom != null) 0.82f else 0.45f
            } else {
                arRoom = null; arPin = null; arConfidence = 0f
            }

            // Priority 2: image feature matching (works even without AR).
            val matchSource = capture.forSlot(SlotRole.A) ?: capture.primary
            val visionResult = withContext(Dispatchers.Default) {
                if (matchSource != null) matcher.matchFromPath(matchSource.filePath)
                else RoomMatcher.MatchResult(null, null, 0f, emptyList())
            }

            // Priority 3: AutoPin from IMU heading + focus distance, anchored
            // to the session's start anchor. This produces a plausible pin
            // even without AR pose or matched reference photos.
            val autoPin: PinPos? = run {
                val startX = arPin?.x ?: session.startXNorm ?: return@run null
                val startY = arPin?.y ?: session.startYNorm ?: return@run null
                val focus = pose.focusDistanceM ?: return@run null
                val (x, y) = AutoPin.estimate(
                    startX = startX,
                    startY = startY,
                    headingDeg = pose.imuHeadingDeg,
                    subjectDistM = focus
                )
                PinPos(x, y)
            }
            val autoRoom: FloorplanRoomAnchor? = autoPin?.let {
                PoseToRoom.roomAt(it.x, it.y, meta)
            }

            // Reconcile all sources: AR wins, then AutoPin, then image matching,
            // then room-center default. Only fall back to (0.5, 0.5) if the
            // session hasn't been anchored yet AND every sensor is silent.
            val commonAreaContext = InspectionContextStore.load(app)
                ?.takeIf { it.sourceType == "common_area" }
            val roomAnchor: FloorplanRoomAnchor? = autoRoom
                ?: arRoom
                ?: meta.rooms.firstOrNull { it.id == visionResult.roomId }
            val resolvedLabel = commonAreaContext?.roomLabel
                ?: roomAnchor?.label
                ?: visionResult.roomLabel?.takeIf { it.isNotBlank() }
            val resolvedConfidence = when {
                arRoom != null -> arConfidence
                autoRoom != null -> 0.75f
                else -> visionResult.confidence
            }
            val resolvedSource = if (
                arRoom != null || autoPin != null || visionResult.roomId != null
            ) PinSource.AUTO else PinSource.MANUAL
            val resolvedPin = commonAreaContext?.let { PinPos(0.5f, 0.5f) }
                ?: autoPin
                ?: arPin
                ?: roomAnchor?.let { PinPos(it.cx, it.cy) }
                ?: PinPos(0.5f, 0.5f)
            // Fuse shutter-time IMU tilt with horizontal-boundary evidence in
            // the captured photo. This runs locally and never blocks input.
            val surfaceFusion = withContext(Dispatchers.Default) {
                SurfaceFusionAnalyzer.analyze(matchSource?.filePath, pose.imuPitchDeg)
            }
            val surfaceBand = surfaceFusion.band
            val autoSurface = surfaceBand.storageSurface
            val route = PoseToRoom.clockwiseRoute(meta).map { it.label }
            val anchorDistance = when {
                pose.arWorldX != null && pose.arWorldZ != null ->
                    kotlin.math.sqrt((pose.arWorldX * pose.arWorldX + pose.arWorldZ * pose.arWorldZ).toDouble()).toFloat()
                pose.pdrRelXMeters != null && pose.pdrRelZMeters != null ->
                    kotlin.math.sqrt((pose.pdrRelXMeters * pose.pdrRelXMeters + pose.pdrRelZMeters * pose.pdrRelZMeters).toDouble()).toFloat()
                else -> null
            }
            val positionDraft = buildString {
                append(resolvedLabel ?: "위치 추정")
                append(".").append(surfaceBand.label)
                anchorDistance?.let { append(". 현관 앵커에서 ").append(String.format("%.1f", it)).append("m") }
                append(". 촬영방향 ").append(pose.imuHeadingDeg.toInt()).append("도")
            }
            // Run the AI stub with what we have so far. Resident opinion is
            // still blank at this point; when the user types one in the
            // sheet we could re-run this — for now the first-pass path is
            // enough to seed the UI.
            val aiSuggestion = ai.classify(
                AiInput(
                    roomLabel = resolvedLabel,
                    surface = autoSurface,
                    residentOpinion = "",
                    focusDistanceM = pose.focusDistanceM,
                    headingDeg = pose.imuHeadingDeg,
                    pitchDeg = pose.imuPitchDeg
                )
            )
            _state.update {
                it.copy(
                    matching = false,
                    capture = capture,
                    floorplan = meta,
                    floorplanBitmap = bmp,
                    floorplanIsCustom = isCustom,
                    roomId = commonAreaContext?.roomCode ?: roomAnchor?.id ?: visionResult.roomId,
                    roomLabel = resolvedLabel,
                    confidence = resolvedConfidence,
                    alternatives = visionResult.topCandidates,
                    pin = resolvedPin,
                    capturePin = arPin,
                    pinSource = resolvedSource,
                    autoSurface = autoSurface,
                    autoSurfaceBand = surfaceBand,
                    surfaceFusionConfidence = surfaceFusion.confidence,
                    surfaceBoundaryYNorm = surfaceFusion.boundaryYNorm,
                    suggestedDetails = detailCandidates(resolvedLabel, surfaceBand),
                    positionDraft = positionDraft,
                    clockwiseRoute = route,
                    aiPathText = aiSuggestion.pathText,
                    aiConfidence = aiSuggestion.confidence,
                    aiRationale = aiSuggestion.rationale,
                    existingDefects = existing,
                    anchorX = session.startXNorm,
                    anchorY = session.startYNorm
                )
            }
            refreshTaxonomy(
                floorplanType = meta.id,
                roomCode = commonAreaContext?.roomCode ?: roomAnchor?.id ?: visionResult.roomId,
                surfaceCode = surfaceBand.name
            )
        }
    }

    /**
     * Synchronous classify — always returns the rule-based fallback so the
     * UI has SOMETHING immediately. The Gemini call (if configured) runs
     * asynchronously via [classifyOpinionAsync] and replaces the result in
     * [PinPlacementState.aiPathText] once ready.
     */
    fun classifyOpinion(residentOpinion: String): com.axlife.pinset.vision.AiSuggestion? {
        val s = _state.value
        val pose = s.capture?.pose ?: return null
        val stub = ai.classify(
            AiInput(
                roomLabel = s.roomLabel,
                surface = s.autoSurface,
                residentOpinion = residentOpinion,
                focusDistanceM = pose.focusDistanceM,
                headingDeg = pose.imuHeadingDeg,
                pitchDeg = pose.imuPitchDeg
            )
        )
        // Kick off the cloud call if configured. Fire-and-forget: the state
        // flow will emit the updated suggestion when it lands.
        if (aiPrefs.isConfigured) {
            classifyOpinionAsync(residentOpinion)
        }
        return stub
    }

    private var visionJob: kotlinx.coroutines.Job? = null
    private fun classifyOpinionAsync(residentOpinion: String) {
        val prefs = aiPrefs
        if (!prefs.isConfigured) return
        val s = _state.value
        val pose = s.capture?.pose ?: return
        val photoPath = s.capture.forSlot(com.axlife.pinset.data.entity.SlotRole.A)?.filePath
            ?: s.capture.primary?.filePath
        visionJob?.cancel()
        visionJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    aiPathText = "AI 분석 중…",
                    aiConfidence = 0f,
                    aiRationale = "Gemini Vision 호출 중"
                )
            }
            val vision = com.axlife.pinset.vision.GeminiVisionClassifier(
                apiKey = prefs.apiKey,
                model = prefs.model
            )
            val out = vision.classifyAsync(
                AiInput(
                    roomLabel = s.roomLabel,
                    surface = s.autoSurface,
                    residentOpinion = residentOpinion,
                    focusDistanceM = pose.focusDistanceM,
                    headingDeg = pose.imuHeadingDeg,
                    pitchDeg = pose.imuPitchDeg
                ),
                photoPath = photoPath
            )
            _state.update {
                if (out.pathText.isBlank() || out.pathText.startsWith("❌")) {
                    // Diagnostic message — either the classifier surfaced an
                    // error string in pathText, or path came back empty.
                    // Show the rationale so the operator can act on it.
                    it.copy(
                        aiPathText = out.pathText.ifBlank { "❌ AI 오류" },
                        aiConfidence = 0f,
                        aiRationale = out.rationale.ifBlank { "원인 미상" }
                    )
                } else {
                    it.copy(
                        aiPathText = out.pathText,
                        aiConfidence = out.confidence,
                        aiRationale = out.rationale.ifBlank { "Gemini Vision 분석 결과" }
                    )
                }
            }
        }
    }

    /**
     * Refresh the AI suggestion when the resident opinion changes. Called by
     * the tag sheet so the "AI 어시스턴트" card can react to what the user
     * just typed.
     */
    fun refreshAi(residentOpinion: String) {
        val s = _state.value
        val pose = s.capture?.pose ?: return
        val out = ai.classify(
            AiInput(
                roomLabel = s.roomLabel,
                surface = s.autoSurface,
                residentOpinion = residentOpinion,
                focusDistanceM = pose.focusDistanceM,
                headingDeg = pose.imuHeadingDeg,
                pitchDeg = pose.imuPitchDeg
            )
        )
        _state.update {
            it.copy(
                aiPathText = out.pathText,
                aiConfidence = out.confidence,
                aiRationale = out.rationale
            )
        }
    }

    private fun detailCandidates(roomLabel: String?, band: CaptureSurfaceBand): List<String> {
        val base = when (band) {
            CaptureSurfaceBand.CEILING -> listOf("천장 마감재", "도배지", "도장면", "조명·점검구", "배관 흔적")
            CaptureSurfaceBand.CEILING_WALL -> listOf("\ucc9c\uc7a5 \ubab0\ub529", "\ucc9c\uc7a5/\ubcbd \uc811\ud569\ubd80", "\ucf54\ub108 \ub3c4\ubc30", "\uc2e4\ub9ac\ucf58", "\uade0\uc5f4\ubd80")
            CaptureSurfaceBand.WALL -> listOf("벽지", "도장면", "타일", "문틀·창틀 주변", "콘센트·스위치")
            CaptureSurfaceBand.WALL_FLOOR -> listOf("\uac78\ub808\ubc1b\uc774", "\ubcbd/\ubc14\ub2e5 \uc811\ud569\ubd80", "\ubc14\ub2e5 \ud0c0\uc77c", "\ub9c8\ub8e8 \ub05d\ub2e8", "\uc2e4\ub9ac\ucf58")
            CaptureSurfaceBand.FLOOR -> listOf("마루·바닥재", "바닥 타일", "문턱", "배수구 주변", "난방·들뜸 부위")
        }
        return if (roomLabel?.contains("욕실") == true) {
            (listOf("배수구 주변", "타일 줄눈") + base).distinct().take(5)
        } else base
    }
    private fun refreshTaxonomy(floorplanType: String, roomCode: String?, surfaceCode: String) {
        if (roomCode.isNullOrBlank()) return
        viewModelScope.launch {
            val catalog = withContext(Dispatchers.IO) {
                app.fieldTaxonomyRepository.load(floorplanType, roomCode, surfaceCode)
            } ?: return@launch
            if (catalog.details.isNotEmpty()) {
                _state.update {
                    it.copy(
                        suggestedDetails = catalog.details.map { detail -> detail.label }.take(5),
                        suggestedTradeLabel = catalog.details.firstOrNull()?.tradeLabel.orEmpty(),
                        taxonomyStatus = if (catalog.cached) "?????: ?? ??" else "?????: ??"
                    )
                }
            }
        }
    }

    fun selectRoom(label: String) {
        val meta = _state.value.floorplan ?: return
        val anchor = meta.rooms.firstOrNull { it.label == label } ?: return
        _state.update {
            it.copy(
                roomId = anchor.id,
                roomLabel = anchor.label,
                pin = PinPos(anchor.cx, anchor.cy),
                pinSource = PinSource.MANUAL
            )
        }
        refreshTaxonomy(meta.id, anchor.id, _state.value.autoSurfaceBand.name)
    }

    fun movePin(x: Float, y: Float) {
        _state.update { it.copy(pin = PinPos(x, y), pinSource = PinSource.MANUAL) }
    }

    fun prepareAiAssistant() {
        app.pendingAiLocationHint = _state.value.roomLabel
    }

    fun save(sub: TagSubmission, onSaved: (Long, String, String) -> Unit) {
        val s = _state.value
        val pin = s.pin ?: return
        val capture = s.capture ?: return
        viewModelScope.launch {
            val session = repo.activeSession(app)
            val pose = capture.pose
            // First capture in this session? Register the entrance anchor so
            // the floorplan can later show a navigation trail starting there.
            val isFirstCapture = session.startXNorm == null
            if (isFirstCapture) {
                // The inspection route always starts at the entrance, not at
                // the first defect. This keeps distance/direction estimates
                // and the accumulated floorplan trail on one common origin.
                val entrance = s.floorplan?.entrance
                val startX = entrance?.cx ?: 0.5f
                val startY = entrance?.cy ?: 0.5f
                repo.setStartAnchor(
                    session.id,
                    startX,
                    startY,
                    pose.imuHeadingDeg,
                    locationLabel = entrance?.label ?: "현관"
                )
            }
            // Persist the 3-Source classification fields. If the user didn't
            // pick a final path, fall back to the AI suggestion so the row in
            // the list is never blank.
            val inspectionDetail = sub.areaDetail.takeIf { it.isNotBlank() }
                ?: when (sub.surface) {
                    com.axlife.pinset.data.entity.Surface.CEILING -> "천정"
                    com.axlife.pinset.data.entity.Surface.FLOOR -> "바닥"
                    com.axlife.pinset.data.entity.Surface.WALL -> "벽체"
                }
            val defaultRecommendation = listOf(
                s.roomLabel ?: "위치 미확인",
                inspectionDetail,
                sub.type.name,
                sub.trade.name
            ).joinToString(".")
            val effectiveOpinion = sub.residentOpinion.trim().takeIf { it.isNotBlank() }
                ?: "$defaultRecommendation 기본 추천"
            val effectiveFinal = sub.finalPathText.takeIf { it.isNotBlank() }
                ?: sub.aiPathText.takeIf { it.isNotBlank() }
                ?: defaultRecommendation
            withContext(Dispatchers.IO) {
                capture.shots.forEach { shot ->
                    val photoFile = java.io.File(shot.filePath)
                    com.axlife.pinset.util.ImageOverlay.stampInspectionLabel(
                        file = photoFile,
                        unitLabel = session.unitLabel,
                        roomLabel = s.roomLabel ?: "-",
                        detailLabel = inspectionDetail,
                        capturedAtMillis = photoFile.lastModified()
                            .takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                }
            }
            val defect = Defect(
                sessionId = session.id,
                roomId = s.roomId ?: "unknown",
                roomLabel = s.roomLabel ?: "미지정",
                xNorm = pin.x,
                yNorm = pin.y,
                defectType = sub.type,
                severity = sub.severity,
                trade = sub.trade,
                surface = sub.surface,
                areaDetail = sub.areaDetail,
                note = sub.note,
                source = s.pinSource,
                confidence = s.confidence,
                imuPitchDeg = pose.imuPitchDeg,
                imuHeadingDeg = pose.imuHeadingDeg,
                arWorldX = pose.arWorldX,
                arWorldY = pose.arWorldY,
                arWorldZ = pose.arWorldZ,
                focusDistanceM = pose.focusDistanceM,
                measuredGapMm = sub.measuredGapMm,
                measurementMethod = sub.measurementMethod,
                measurementStatus = sub.measurementStatus,
                status = if (sub.finalize) com.axlife.pinset.data.entity.DefectStatus.DONE
                    else com.axlife.pinset.data.entity.DefectStatus.PENDING,
                residentOpinion = effectiveOpinion,
                aiPathText = sub.aiPathText,
                aiConfidence = sub.aiConfidence,
                finalPathText = effectiveFinal
            )
            val capturedPhotos = capture.shots.map { shot ->
                DefectPhoto(
                    defectId = 0,
                    filePath = shot.filePath,
                    lens = shot.toLens(),
                    slot = shot.slot,
                    zoomRatio = shot.requestedZoom,
                    isDigital = shot.isDigital,
                    isPrimary = shot.slot == SlotRole.A
                )
            } + sub.memoPhotoPath.takeIf { it.isNotBlank() }?.let { path ->
                DefectPhoto(
                    defectId = 0,
                    filePath = path,
                    lens = com.axlife.pinset.data.entity.Lens.MAIN,
                    slot = SlotRole.C,
                    zoomRatio = 1f,
                    isDigital = false,
                    isPrimary = false
                )
            }.let { memo -> if (memo == null) emptyList() else listOf(memo) }
            // An opinion-only defect must still be synchronizable. Store a
            // labelled reference illustration instead of pretending a real
            // field photograph exists; the image itself says so prominently.
            val photos = if (capturedPhotos.isNotEmpty()) {
                capturedPhotos
            } else {
                val referencePath = withContext(Dispatchers.IO) {
                    com.axlife.pinset.util.VirtualDefectImage.create(app, defect)
                }
                listOf(
                    DefectPhoto(
                        defectId = 0,
                        filePath = referencePath,
                        lens = Lens.MAIN,
                        slot = SlotRole.A,
                        zoomRatio = 1f,
                        isDigital = true,
                        isPrimary = true,
                    )
                )
            }
            val id = repo.addDefect(defect, photos)
            withContext(Dispatchers.IO) { repo.normalizeLocalPhotoFileNames() }
            app.syncManager.trigger()
            // Give an immediately available connection a short chance to finish.
            // Local Room records and image files remain authoritative until then.
            kotlinx.coroutines.delay(900L)
            val sync = app.database.syncQueueDao().getForDefect(id)
            val storedFiles = app.database.defectPhotoDao().getByDefect(id)
                .map { java.io.File(it.filePath).name }
            val localFolder = "앱 내부 저장소/captures"
            val deliveryNotice = if (sync?.state == com.axlife.pinset.data.entity.SyncState.COMPLETED) {
                "전송 완료"
            } else {
                "임시로 로컬에 저장합니다. 통신망이 회복되면 전송을 이어갑니다." +
                    "\n저장 위치: $localFolder" +
                    "\n파일: ${storedFiles.joinToString(", ").ifBlank { "메타데이터만 저장" }}"
            }
            app.pendingCapture = null
            val idx = repo.countBySessionNow(session.id)
            val summary = buildSummary(idx, defect, sub, isFirstCapture)
            onSaved(id, summary, deliveryNotice)
        }
    }

    private fun buildSummary(
        indexNumber: Int,
        defect: Defect,
        sub: TagSubmission,
        firstCapture: Boolean
    ): String {
        val type = when (sub.type) {
            com.axlife.pinset.data.entity.DefectType.CRACK -> "균열"
            com.axlife.pinset.data.entity.DefectType.LEAK -> "누수"
            com.axlife.pinset.data.entity.DefectType.FINISH -> "마감 불량"
            com.axlife.pinset.data.entity.DefectType.OTHER -> "기타"
        }
        val sev = when (sub.severity) {
            com.axlife.pinset.data.entity.Severity.MAJOR -> "중대"
            com.axlife.pinset.data.entity.Severity.NORMAL -> "보통"
            com.axlife.pinset.data.entity.Severity.MINOR -> "경미"
        }
        val trade = when (sub.trade) {
            com.axlife.pinset.data.entity.Trade.WALL -> "벽체"
            com.axlife.pinset.data.entity.Trade.WALLPAPER -> "도배"
            com.axlife.pinset.data.entity.Trade.TILE -> "타일"
            com.axlife.pinset.data.entity.Trade.FLOOR -> "바닥"
            com.axlife.pinset.data.entity.Trade.WINDOW -> "창호"
            com.axlife.pinset.data.entity.Trade.ELECTRIC -> "전기"
            com.axlife.pinset.data.entity.Trade.PLUMBING -> "배관"
            com.axlife.pinset.data.entity.Trade.OTHER -> "기타"
        }
        val surface = when (sub.surface) {
            com.axlife.pinset.data.entity.Surface.CEILING -> "천장"
            com.axlife.pinset.data.entity.Surface.WALL -> "벽"
            com.axlife.pinset.data.entity.Surface.FLOOR -> "바닥"
        }
        val location = "${defect.roomLabel} · $surface"
        val startTag = if (firstCapture) "  [시작 앵커 등록]" else ""
        return "#$indexNumber · $type($sev) · $trade · $location$startTag"
    }

    private fun CapturedShot.toLens(): Lens = when (lensTag) {
        "ULTRA" -> Lens.ULTRA
        "TELE" -> Lens.TELE
        else -> Lens.MAIN
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PinSetApplication
                PinPlacementViewModel(app)
            }
        }
    }
}
