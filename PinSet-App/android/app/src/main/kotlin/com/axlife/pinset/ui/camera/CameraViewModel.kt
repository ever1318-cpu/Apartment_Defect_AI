package com.axlife.pinset.ui.camera

import android.content.Context
import android.media.MediaActionSound
import android.util.Log
import android.view.SurfaceView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.camera.CaptureMode
import com.axlife.pinset.camera.CapturePoseSnapshot
import com.axlife.pinset.camera.CaptureResult
import com.axlife.pinset.camera.CaptureSpec
import com.axlife.pinset.camera.MultiLensCaptureController
import com.axlife.pinset.camera.FlashMode
import com.axlife.pinset.data.SlotPrefs
import com.axlife.pinset.data.SlotPrefsRepo
import com.axlife.pinset.data.entity.DefectPhoto
import com.axlife.pinset.data.entity.Lens
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.vision.ArTracker
import com.axlife.pinset.vision.ImuOrientation
import com.axlife.pinset.vision.PdrTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DefectPhotoMode {
    CLOSE_ONLY,
    CLOSE_AND_WIDE,
}

data class CameraState(
    val mode: CaptureMode = CaptureMode.SIMULTANEOUS,
    val locating: Boolean = true,
    val capturing: Boolean = false,
    /** Field hint displayed while an automatic close + wide evidence pair is captured. */
    val captureStatus: String = "",
    val flashMode: FlashMode = FlashMode.AUTO,
    /** Optional in-app shutter feedback. Silent is the field-work default. */
    val shutterSoundEnabled: Boolean = false,
    /** Close evidence is always captured; the context image is selectable. */
    val defectPhotoMode: DefectPhotoMode = DefectPhotoMode.CLOSE_AND_WIDE,
    /** Requires a physical reference marker or feeler gauge at capture time. */
    val precisionMeasurementEnabled: Boolean = false,
    /** Operator confirms the 40 mm printed reference marker is visible in the frame. */
    val referenceMarkerEnabled: Boolean = false,
    val recoveringPreview: Boolean = false,
    val result: CaptureResult? = null,
    val error: String? = null,
    /** Unsupported camera path; briefly notify then continue without a physical photo. */
    val skipToOpinion: Boolean = false,
    val hardwareOk: Boolean = false,
    val slotPrefs: SlotPrefs = SlotPrefs(),
    val previewZoom: Float = 1f,
    val previewZoomMin: Float = 0.5f,
    val previewZoomMax: Float = 10f,
    /** True when the hardware accepted a dual-lens live preview session. */
    val dualPreviewActive: Boolean = false,
    /** Physical zoom ratios of the two anchored preview lenses, for badge display. */
    val telePreviewZoom: Float = 3f,
    val ultraPreviewZoom: Float = 0.5f,
    /** IMU snapshot, updated live from the rotation-vector sensor. */
    val imuPitchDeg: Float = 0f,
    val imuHeadingDeg: Float = 0f,
    /** Live focus distance in meters (Camera2 LENS_FOCUS_DISTANCE, dioptre → m). */
    val focusDistanceM: Float? = null,
    /** Live pedestrian-dead-reckoning offsets in meters relative to anchor. */
    val pdrRelXMeters: Float = 0f,
    val pdrRelZMeters: Float = 0f,
    val pdrSteps: Int = 0,
    /** Live-projected floorplan position (0..1) for the mini-map. */
    val liveXNorm: Float? = null,
    val liveYNorm: Float? = null,
    /** Current session so the mini-map has an anchor to render against. */
    val activeSession: com.axlife.pinset.data.entity.Session? = null,
    val liveDefects: List<com.axlife.pinset.data.entity.Defect> = emptyList(),
    val liveFloorplan: android.graphics.Bitmap? = null,
    val liveFloorplanIsCustom: Boolean = false,
    /** True after ARCore accepted a session and is currently tracking. */
    val arAvailable: Boolean = false,
    val arTracking: Boolean = false,
    val arAnchorSet: Boolean = false,
    /** Radius of the on-preview defect-region marker, as a fraction of the
     *  preview's shorter side (0.05..0.45). The circle is always centered
     *  on the preview; users adjust size only. Baked into the saved JPEG
     *  on capture. */
    val markerRadiusFrac: Float = 0.18f,
    /** Optional composition grid. It is off by default so it is not confused with real boundaries. */
    val showGrid: Boolean = false,
    /** Selected anchor-location label (used by anchor mode only). Presets:
     *  "현관문", "거실", "베란다", "기타". Users can override with free text. */
    val anchorLocationLabel: String = "현관문"
)

class CameraViewModel(private val app: PinSetApplication) : ViewModel() {
    private val TAG = "CameraVM"

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val multi = MultiLensCaptureController(app)
    private val imu = ImuOrientation(app)
    private val arTracker = ArTracker(app)
    private val pdr = PdrTracker(app)
    private val shutterSound = MediaActionSound().also {
        it.load(MediaActionSound.SHUTTER_CLICK)
    }
    private var teleSurfaceView: SurfaceView? = null
    private var ultraSurfaceView: SurfaceView? = null
    private var opened = false
    private var arTickJob: kotlinx.coroutines.Job? = null
    private var focusTickJob: kotlinx.coroutines.Job? = null
    private var pdrJob: kotlinx.coroutines.Job? = null
    private var defectsJob: kotlinx.coroutines.Job? = null
    private var previewWatchdogJob: kotlinx.coroutines.Job? = null
    private var restartingPreview = false
    private var selectedRoomXNorm: Float? = null
    private var selectedRoomYNorm: Float? = null

    init {
        val info = multi.probeLenses()
        _state.update {
            it.copy(
                hardwareOk = info != null,
                previewZoomMin = info?.zoomMin ?: 0.5f,
                previewZoomMax = info?.zoomMax ?: 10f,
                // Two-shot workflow: user aims at the defect at 20x for maximum
                // detail; system also captures a 0.5x wide-context frame in the
                // same shutter press.
                previewZoom = 20.0f
            )
        }
        viewModelScope.launch {
            SlotPrefsRepo.observe(app).collect { prefs ->
                _state.update { it.copy(slotPrefs = prefs) }
            }
        }
        // Live IMU stream — always available on modern phones.
        imu.start()
        viewModelScope.launch {
            imu.pitchDeg.collect { p -> _state.update { it.copy(imuPitchDeg = p) } }
        }
        viewModelScope.launch {
            imu.headingDeg.collect { h ->
                _state.update { it.copy(imuHeadingDeg = h) }
                pdr.setHeadingDeg(h)
            }
        }

        // Pedestrian dead reckoning — integrate steps along the heading so
        // the live mini-map on the camera screen can show the user's position.
        pdr.start()
        pdrJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(pdr.relX, pdr.relZ, pdr.steps) { x, z, s ->
                Triple(x, z, s)
            }.collect { (x, z, s) ->
                _state.update {
                    val session = it.activeSession
                    val (nx, ny) = liveNormalized(session, x, z)
                    it.copy(
                        pdrRelXMeters = x,
                        pdrRelZMeters = z,
                        pdrSteps = s,
                        liveXNorm = nx,
                        liveYNorm = ny
                    )
                }
            }
        }

        // Load the active session + floorplan so the mini-map on the camera
        // screen has something to render onto.
        viewModelScope.launch {
            val session = app.repository.activeSession(app)
            val db = com.axlife.pinset.vision.ReferenceDb(app)
            val bmp: android.graphics.Bitmap? =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val custom = session.customFloorplanPath
                    if (custom != null && java.io.File(custom).exists()) {
                        runCatching { android.graphics.BitmapFactory.decodeFile(custom) }.getOrNull()
                    } else {
                        runCatching {
                            val meta = db.floorplan(session.floorplanAssetId)
                            db.loadFloorplanBitmap(session.floorplanAssetId, meta)
                        }.getOrNull()
                    }
                }
            val context = com.axlife.pinset.intro.InspectionContextStore.load(app)
            _state.update {
                it.copy(
                    activeSession = session,
                    liveFloorplan = bmp,
                    liveFloorplanIsCustom = session.customFloorplanPath != null,
                    anchorLocationLabel = session.anchorLocationLabel.ifBlank { "현관문 앞" },
                    // Public-area evidence needs both a detail frame and its
                    // surrounding context by default. The user can still change
                    // this from the camera settings dialog.
                    defectPhotoMode = if (context?.sourceType == "common_area") {
                        DefectPhotoMode.CLOSE_AND_WIDE
                    } else {
                        it.defectPhotoMode
                    }
                )
            }
            val selectedRoom = runCatching {
                db.floorplan(session.floorplanAssetId).rooms.firstOrNull { room ->
                    room.id == context?.roomCode
                }
            }.getOrNull()
            selectedRoomXNorm = selectedRoom?.cx
            selectedRoomYNorm = selectedRoom?.cy
            if (session.startXNorm == null && selectedRoom != null) {
                _state.update {
                    it.copy(liveXNorm = selectedRoom.cx, liveYNorm = selectedRoom.cy)
                }
            }
            defectsJob = viewModelScope.launch {
                app.repository.observeDefects(session.id).collect { list ->
                    _state.update { it.copy(liveDefects = list) }
                }
            }
        }
        // Best-effort ARCore session — fine if it fails, we just miss pose data.
        val arOk = arTracker.tryStart()
        _state.update { it.copy(arAvailable = arOk) }
        if (arOk) {
            arTickJob = viewModelScope.launch {
                while (true) {
                    arTracker.tick()
                    _state.update {
                        it.copy(
                            arTracking = arTracker.tracking.value,
                            arAnchorSet = arTracker.hasAnchor()
                        )
                    }
                    kotlinx.coroutines.delay(100L)
                }
            }
        }
    }

    /**
     * Poll the camera controller for the latest LENS_FOCUS_DISTANCE and push
     * it into the UI state. Runs every 200 ms — fast enough for the sensor
     * bar to look live, cheap enough not to churn the state flow.
     */
    private fun startFocusPolling() {
        focusTickJob?.cancel()
        focusTickJob = viewModelScope.launch {
            while (true) {
                _state.update { it.copy(focusDistanceM = multi.currentFocusDistanceMeters()) }
                kotlinx.coroutines.delay(200L)
            }
        }
    }

    fun setArAnchorHere() {
        if (arTracker.setAnchorHere()) {
            _state.update { it.copy(arAnchorSet = true) }
        }
    }

    fun clearArAnchor() {
        arTracker.clearAnchor()
        _state.update { it.copy(arAnchorSet = false) }
    }

    fun toggleMode() {
        _state.update {
            it.copy(mode = if (it.mode == CaptureMode.SIMULTANEOUS) CaptureMode.SEQUENTIAL else CaptureMode.SIMULTANEOUS)
        }
    }

    fun setDefectPhotoMode(mode: DefectPhotoMode) {
        _state.update { it.copy(defectPhotoMode = mode) }
    }

    fun setPrecisionMeasurementEnabled(enabled: Boolean) {
        _state.update { it.copy(precisionMeasurementEnabled = enabled) }
    }

    fun setReferenceMarkerEnabled(enabled: Boolean) {
        _state.update { it.copy(referenceMarkerEnabled = enabled) }
    }

    fun updateSlots(prefs: SlotPrefs) {
        viewModelScope.launch { SlotPrefsRepo.save(app, prefs) }
    }

    fun setAnchorLocationLabel(label: String) {
        _state.update { it.copy(anchorLocationLabel = label) }
    }

    fun setMarkerRadius(fraction: Float) {
        _state.update { it.copy(markerRadiusFrac = fraction.coerceIn(0.05f, 0.45f)) }
    }

    fun toggleGrid() {
        _state.update { it.copy(showGrid = !it.showGrid) }
    }

    fun cycleFlashMode() {
        val next = _state.value.flashMode.next()
        multi.setFlashMode(next)
        _state.update { it.copy(flashMode = next) }
    }

    fun setPreviewZoom(ratio: Float) {
        multi.setPreviewZoom(ratio)
        _state.update { it.copy(previewZoom = multi.currentPreviewZoom()) }
    }

    fun pinchPreviewZoom(factor: Float) {
        val s = _state.value
        val next = (s.previewZoom * factor).coerceIn(s.previewZoomMin, s.previewZoomMax)
        setPreviewZoom(next)
    }

    /**
     * Called by the Composable when its two SurfaceViews (top = tele,
     * bottom = ultra-wide) are created. Both must exist before we can open a
     * dual-lens preview.
     */
    fun setPreviewSurfaces(tele: SurfaceView, ultra: SurfaceView) {
        val teleChanged = teleSurfaceView !== tele
        val ultraChanged = ultraSurfaceView !== ultra
        if (!teleChanged && !ultraChanged) {
            maybeOpen()
            return
        }
        teleSurfaceView = tele
        ultraSurfaceView = ultra
        // Portrait-orientation buffers that match the on-screen 3:4 halves.
        tele.holder.setFixedSize(1080, 720)
        ultra.holder.setFixedSize(1080, 720)
        maybeOpen()
    }

    fun onPreviewSurfaceDestroyed(surfaceView: SurfaceView) {
        if (teleSurfaceView === surfaceView) teleSurfaceView = null
        if (ultraSurfaceView === surfaceView) ultraSurfaceView = null
        opened = false
        multi.stop()
        previewWatchdogJob?.cancel()
        _state.update {
            it.copy(locating = true, recoveringPreview = true, error = null)
        }
    }

    fun pausePreview() {
        opened = false
        multi.stop()
        previewWatchdogJob?.cancel()
    }

    fun resumePreview() {
        maybeOpen()
    }

    private fun maybeOpen() {
        if (opened) return
        val tele = teleSurfaceView ?: return
        val ultra = ultraSurfaceView ?: return
        opened = true
        viewModelScope.launch {
            try {
                multi.start()
                val info = multi.probeLenses()
                if (info == null) {
                    // Do not block field inspection on older devices. The notice is shown
                    // briefly, then the opinion flow creates a virtual reference image.
                    _state.update {
                        it.copy(
                            locating = false,
                            error = "다중 렌즈 촬영 미지원 · 사진 없이 하자의견 입력으로 진행합니다.",
                            skipToOpinion = true
                        )
                    }
                    return@launch
                }
                val teleZoom = info.tele?.zoomRatio ?: 3f
                val ultraZoom = info.ultra?.zoomRatio ?: 0.5f

                // Dual preview locks each SurfaceView to a physical lens's native
                // zoom (tele ≈ 3x, ultra ≈ 0.5x), so what the user sees can't
                // match a user-selected slot zoom (e.g. 40x). Force single-preview
                // mode so the slider truly drives what's on screen AND what
                // gets saved. Dual preview stays reachable in the controller for
                // future work but is disabled here.
                val dualOk = false

                if (dualOk) {
                    _state.update {
                        it.copy(
                            locating = false,
                            hardwareOk = true,
                            dualPreviewActive = true,
                            telePreviewZoom = teleZoom,
                            ultraPreviewZoom = ultraZoom,
                            previewZoomMin = info.zoomMin,
                            previewZoomMax = info.zoomMax
                        )
                    }
                    startFocusPolling()
                    startPreviewWatchdog()
                } else {
                    // Fall back to a single-preview session on the tele surface;
                    // ultra half will just stay black.
                    multi.setPreviewZoom(20.0f)
                    multi.open(info, tele.holder.surface)
                    _state.update {
                        it.copy(
                            locating = false,
                            hardwareOk = true,
                            dualPreviewActive = false,
                            telePreviewZoom = teleZoom,
                            ultraPreviewZoom = ultraZoom,
                            previewZoomMin = info.zoomMin,
                            previewZoomMax = info.zoomMax,
                            previewZoom = multi.currentPreviewZoom()
                        )
                    }
                    startFocusPolling()
                    startPreviewWatchdog()
                }
                _state.update { it.copy(recoveringPreview = false) }
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                opened = false
                _state.update { it.copy(locating = false, error = t.message) }
            }
        }
    }


    private fun startPreviewWatchdog() {
        previewWatchdogJob?.cancel()
        previewWatchdogJob = viewModelScope.launch {
            kotlinx.coroutines.delay(6_000L)
            while (true) {
                if (opened && !multi.isPreviewAlive()) {
                    restartPreview("카메라 화면을 자동 복구했습니다.")
                }
                kotlinx.coroutines.delay(3_000L)
            }
        }
    }

    fun restartPreview(reason: String = "카메라 다시 연결") {
        if (restartingPreview) return
        restartingPreview = true
        viewModelScope.launch {
            _state.update {
                it.copy(locating = true, recoveringPreview = true, error = reason)
            }
            opened = false
            multi.stop()
            kotlinx.coroutines.delay(350L)
            restartingPreview = false
            maybeOpen()
        }
    }

    fun capture(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (_state.value.capturing) return
        if (_state.value.shutterSoundEnabled) {
            runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        }
        _state.update {
            it.copy(
                capturing = true,
                error = null,
                captureStatus = if (it.defectPhotoMode == DefectPhotoMode.CLOSE_AND_WIDE) {
                    "근경 촬영 후 원경을 자동 촬영합니다. 카메라를 고정하세요."
                } else "하자사진을 저장 중입니다."
            )
        }
        val s = _state.value
        // The close-up (slot A) is always required. The wide-context frame
        // (slot B) is captured only when the operator selected that option.
        // Dual-preview: slot A = the tele lens's native zoom (whatever S25U's
        //   longest optical is, e.g. 3x/5x), slot B = ultra-wide native zoom.
        // Single-preview fallback: slot A = the live preview zoom the user set.
        // In close+wide mode the two files are evidence of the same defect.
        // Force the widest supported separation regardless of the preview
        // slider: Slot A is the highest-detail close frame, Slot B the widest
        // context frame.  The controller still clamps to each device's real
        // Camera2 zoom range.
        val automaticPair = s.defectPhotoMode == DefectPhotoMode.CLOSE_AND_WIDE
        // Slot A is exactly the active live-preview framing.  The operator's
        // close-up evidence must never be silently changed to a maximum zoom.
        val slotA = when {
            s.dualPreviewActive -> s.telePreviewZoom
            else -> s.previewZoom
        }
        val slotB = when {
            automaticPair -> s.previewZoomMin
            s.dualPreviewActive -> s.ultraPreviewZoom
            else -> 0.5f
        }
        viewModelScope.launch {
            try {
                val raw = multi.capture(
                    CaptureSpec(
                        slotA = slotA,
                        slotB = slotB.takeIf { s.defectPhotoMode == DefectPhotoMode.CLOSE_AND_WIDE },
                        slotC = null
                    )
                )
                val ar = arTracker.currentRelativePose()
                val pose = CapturePoseSnapshot(
                    imuPitchDeg = _state.value.imuPitchDeg,
                    imuHeadingDeg = _state.value.imuHeadingDeg,
                    arWorldX = ar?.get(0),
                    arWorldY = ar?.get(1),
                    arWorldZ = ar?.get(2),
                    focusDistanceM = multi.currentFocusDistanceMeters(),
                    pdrRelXMeters = _state.value.pdrRelXMeters,
                    pdrRelZMeters = _state.value.pdrRelZMeters
                )
                // Stamp sensor info onto each captured JPEG so the metadata is
                // visible even outside the app. Prefer LENS_FOCUS_DISTANCE (the
                // subject-to-lens distance) since that's what the user cares
                // about; fall back to the AR anchor distance if focus is unset.
                val focus = pose.focusDistanceM
                val distanceLabel = when {
                    focus != null -> String.format("%.1fm", focus)
                    ar != null -> {
                        val d = kotlin.math.sqrt(
                            (ar[0] * ar[0] + ar[2] * ar[2]).toDouble()
                        ).toFloat()
                        String.format("앵커 %.1fm", d)
                    }
                    else -> "—"
                }
                // Bake the on-preview defect-region marker into the close-up
                // (Slot A) only — the 0.5x wide shot stays clean so it can
                // serve as the untouched context frame.
                val markerFrac = _state.value.markerRadiusFrac
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    raw.shots.forEach { shot ->
                        com.axlife.pinset.util.ImageOverlay.stampSensorLine(
                            java.io.File(shot.filePath),
                            pose.imuPitchDeg,
                            pose.imuHeadingDeg,
                            distanceLabel,
                            rotationDeg = 90
                        )
                        if (shot.slot == com.axlife.pinset.data.entity.SlotRole.A) {
                            com.axlife.pinset.util.ImageOverlay.stampDefectMarker(
                                java.io.File(shot.filePath),
                                radiusFraction = markerFrac
                            )
                        }
                    }
                }
                val result = raw.copy(
                    pose = pose,
                    precisionMeasurement = s.precisionMeasurementEnabled,
                    referenceMarkerCaptured = s.referenceMarkerEnabled
                )
                _state.update { it.copy(result = result, capturing = false, captureStatus = "") }
            } catch (t: Throwable) {
                Log.e(TAG, "capture failed", t)
                _state.update { it.copy(error = t.message, capturing = false, captureStatus = "") }
            }
        }
    }

    fun toggleShutterSound() {
        _state.update { it.copy(shutterSoundEnabled = !it.shutterSoundEnabled) }
    }

    fun storeAndAdvance(navigate: () -> Unit) {
        val r = _state.value.result ?: return
        app.pendingCapture = r
        _state.update { it.copy(result = null) }
        navigate()
    }

    fun storeAdditionalPhotosAndReturn(defectId: Long, navigate: () -> Unit) {
        val capture = _state.value.result ?: return
        viewModelScope.launch {
            val photos = capture.shots.map { shot ->
                DefectPhoto(
                    defectId = defectId,
                    filePath = shot.filePath,
                    lens = when (shot.lensTag) {
                        "ULTRA" -> Lens.ULTRA
                        "TELE" -> Lens.TELE
                        else -> Lens.MAIN
                    },
                    slot = shot.slot,
                    zoomRatio = shot.requestedZoom,
                    isDigital = shot.isDigital,
                    isPrimary = shot.slot == SlotRole.A
                )
            }
            app.repository.addPhotos(defectId, photos)
            app.pendingAdditionalPhotoDefectId = null
            app.syncManager.trigger()
            _state.update { it.copy(result = null) }
            navigate()
        }
    }

    fun discardCapture() {
        val capture = _state.value.result ?: return
        _state.update { it.copy(result = null, capturing = false, error = null) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            capture.shots.forEach { shot ->
                runCatching { java.io.File(shot.filePath).delete() }
            }
        }
    }

    /**
     * Anchor-mode variant of [storeAndAdvance]: persists the current 2-shot
     * capture as the session's entrance anchor photos, sets the session start
     * point to the floorplan's entrance metadata, then navigates home.
     *
     * This never touches [PinSetApplication.pendingCapture] because the anchor
     * flow does NOT lead into pin placement — the operator's next action is
     * always to shoot the first real defect.
     */
    fun storeAnchorAndAdvance(navigate: () -> Unit) {
        val r = _state.value.result ?: return
        val shots = r.shots
        if (shots.isEmpty()) return
        val near = shots.firstOrNull { it.slot == com.axlife.pinset.data.entity.SlotRole.A }
            ?: shots.first()
        val far = shots.firstOrNull { it.slot == com.axlife.pinset.data.entity.SlotRole.B }
            ?: shots.last()
        viewModelScope.launch {
            val session = app.repository.activeSession(app)
            val capturedAt = System.currentTimeMillis()
            val anchorLabel = _state.value.anchorLocationLabel.ifBlank {
                session.anchorLocationLabel.ifBlank { "최초 촬영 위치" }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                shots.forEach { shot ->
                    com.axlife.pinset.util.ImageOverlay.stampAnchorLabel(
                        file = java.io.File(shot.filePath),
                        unitLabel = session.unitLabel,
                        locationLabel = anchorLabel,
                        capturedAtMillis = capturedAt
                    )
                }
            }
            // The intro already stored the operator-selected first room.
            // Preserve that position instead of replacing it with "entrance".
            val entranceX = session.startXNorm ?: 0.5f
            val entranceY = session.startYNorm ?: 0.5f
            app.repository.setAnchorPhotos(
                id = session.id,
                nearPath = near.filePath,
                farPath = far.filePath,
                headingDeg = r.pose.imuHeadingDeg,
                entranceX = entranceX,
                entranceY = entranceY,
                locationLabel = anchorLabel
            )
            _state.update { it.copy(result = null) }
            navigate()
        }
    }

    private fun liveNormalized(
        session: com.axlife.pinset.data.entity.Session?,
        relX: Float, relZ: Float
    ): Pair<Float?, Float?> {
        val ax = session?.startXNorm
        val ay = session?.startYNorm
        if (ax == null || ay == null) return selectedRoomXNorm to selectedRoomYNorm
        // 12 m x 9 m default apartment extents. Later we'll pull this from
        // the floorplan JSON per-unit.
        val metersPerNormX = 12f
        val metersPerNormY = 9f
        val x = (ax + relX / metersPerNormX).coerceIn(0f, 1f)
        val y = (ay + relZ / metersPerNormY).coerceIn(0f, 1f)
        return x to y
    }

    override fun onCleared() {
        shutterSound.release()
        multi.stop()
        imu.stop()
        pdr.stop()
        arTickJob?.cancel()
        focusTickJob?.cancel()
        pdrJob?.cancel()
        defectsJob?.cancel()
        previewWatchdogJob?.cancel()
        arTracker.stop()
        arTracker.release()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PinSetApplication
                CameraViewModel(app)
            }
        }
    }
}
