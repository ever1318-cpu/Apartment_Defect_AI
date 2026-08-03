package com.axlife.pinset.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as ACol
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult as Cam2CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.util.ImageStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Slot-oriented multi-lens capture controller.
 *
 * Each slot asks for a logical zoom ratio (e.g. 1.0, 0.5, 0.1). The controller:
 *   1. Picks the best physical lens whose native zoom is <= requested zoom
 *      (or the widest lens if none is wide enough).
 *   2. Captures once per unique physical lens (dedup — shared streams).
 *   3. Post-processes each JPEG for its slot: if the requested zoom is finer
 *      than the physical lens can deliver, the image is DIGITALLY DOWNSCALED
 *      with a black letterbox border so the subject appears smaller.
 */
class MultiLensCaptureController(private val context: Context) {
    private val TAG = "MultiLensCap"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    /** Primary (fallback) preview surface. */
    private var previewSurface: Surface? = null
    /** Optional per-physical-camera preview surfaces. Populated by `openDualPreview`. */
    private var teleSurface: Surface? = null
    private var ultraSurface: Surface? = null
    private var teleLens: PhysicalLens? = null
    private var ultraLens: PhysicalLens? = null
    /** True after `openDualPreview` succeeded; controls the preview request builder. */
    private var dualPreviewActive: Boolean = false

    /**
     * Live focus distance in meters, sampled from every preview frame's
     * TotalCaptureResult.LENS_FOCUS_DISTANCE (dioptre = 1/m). Null when the
     * sensor reports UNCALIBRATED or the frame is missing the field.
     * We keep it as a plain volatile — the UI reads it via a periodic tick.
     */
    @Volatile
    private var lastFocusDistanceM: Float? = null
    @Volatile
    private var lastPreviewFrameAtMs: Long = 0L
    @Volatile
    private var flashMode: FlashMode = FlashMode.AUTO
    fun currentFocusDistanceMeters(): Float? = lastFocusDistanceM
    fun isPreviewAlive(maxAgeMs: Long = 5_000L): Boolean =
        lastPreviewFrameAtMs > 0L &&
            android.os.SystemClock.elapsedRealtime() - lastPreviewFrameAtMs <= maxAgeMs

    fun setFlashMode(mode: FlashMode) {
        flashMode = mode
        runCatching { refreshPreviewRequest() }
    }

    /** physicalId → ImageReader */
    private val readers = mutableMapOf<String, ImageReader>()

    /** Reader bound to the LOGICAL camera output — this is the one whose
     *  CaptureRequest respects CONTROL_ZOOM_RATIO. capture() writes into
     *  this reader in sequence, one shot per slot. */
    private var logicalReader: ImageReader? = null

    private var lensInfo: LensInfo? = null

    /** Sensor orientation of the logical camera in degrees (0/90/180/270). */
    private var sensorOrientation: Int = 90

    data class PhysicalLens(
        val physicalId: String,
        val focalMm: Float,
        val zoomRatio: Float,     // relative to main
        val jpegSize: Size
    )

    data class LensInfo(
        val logicalId: String,
        val lenses: List<PhysicalLens>,   // sorted ascending zoomRatio (widest first)
        val zoomMin: Float,               // logical camera zoom lower bound
        val zoomMax: Float                // logical camera zoom upper bound
    ) {
        val ultra: PhysicalLens? get() = lenses.firstOrNull()
        val main: PhysicalLens? get() = lenses.firstOrNull { kotlin.math.abs(it.zoomRatio - 1f) < 0.15f }
        val tele: PhysicalLens? get() = lenses.lastOrNull()
    }

    private var previewZoom: Float = 1f

    fun probeLenses(): LensInfo? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            val isLogical = caps.any {
                it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            }
            if (!isLogical) continue

            val physIds = chars.physicalCameraIds
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            // Record the sensor orientation so JPEG_ORIENTATION can be set to
            // whatever the sensor reports (usually 90° for Samsung phones held
            // in portrait, but reading it from characteristics avoids drift).
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            Log.i(TAG, "Logical back camera $id → physicals=$physIds range=${zoomRange?.lower}..${zoomRange?.upper}")

            val raw = physIds.mapNotNull { pid ->
                val pc = cameraManager.getCameraCharacteristics(pid)
                val focals = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return@mapNotNull null
                val focal = focals.min()
                val jpegSize = pickMaxJpegSize(pc)
                Log.i(TAG, "  physical $pid focal=$focal jpeg=${jpegSize.width}x${jpegSize.height}")
                PhysicalLens(pid, focal, 1f, jpegSize)
            }.sortedBy { it.focalMm }

            if (raw.isEmpty()) continue

            // Reference "main" = the lens closest to a 26mm-equivalent. Since we
            // don't know sensor size, we approximate with the 2nd-shortest focal.
            val mainFocal = when {
                raw.size >= 3 -> raw[1].focalMm
                raw.size == 2 -> raw[1].focalMm
                else -> raw[0].focalMm
            }
            val lenses = raw.map { it.copy(zoomRatio = round1(it.focalMm / mainFocal)) }
            val zMin = zoomRange?.lower ?: lenses.first().zoomRatio
            val zMax = zoomRange?.upper ?: lenses.last().zoomRatio
            Log.i(TAG, "→ lenses: ${lenses.map { "${it.zoomRatio}x@${it.physicalId}" }} zoom=[$zMin,$zMax]")
            return LensInfo(id, lenses, zMin, zMax)
        }
        // Older devices such as Galaxy Note10 expose independent rear cameras
        // rather than a LOGICAL_MULTI_CAMERA.  Use the primary rear camera as
        // a sequential two-shot source: the capture pipeline requests the
        // maximum digital zoom for the close frame and the minimum zoom for
        // the context frame.  This is intentionally not a dual preview.
        val fallback = cameraManager.cameraIdList.firstOrNull { candidateId ->
            cameraManager.getCameraCharacteristics(candidateId)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return null
        val chars = cameraManager.getCameraCharacteristics(fallback)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: 1f
        val jpeg = pickMaxJpegSize(chars)
        val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val minZoom = (range?.lower ?: 1f).coerceAtLeast(1f)
        val maxZoom = (range?.upper
            ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?: 1f).coerceAtLeast(minZoom)
        Log.i(TAG, "Single rear camera fallback $fallback zoom=[$minZoom,$maxZoom]")
        return LensInfo(
            logicalId = fallback,
            lenses = listOf(PhysicalLens(fallback, focal, 1f, jpeg)),
            zoomMin = minZoom,
            zoomMax = maxZoom
        )
    }

    /**
     * Change the live preview zoom (pinch or slider). Silently no-ops when
     * dual-preview is active — those streams are anchored to fixed physical
     * lenses so a logical zoom would not apply.
     */
    fun setPreviewZoom(ratio: Float) {
        val info = lensInfo ?: return
        if (dualPreviewActive) {
            previewZoom = ratio.coerceIn(info.zoomMin, info.zoomMax)
            return
        }
        val clamped = ratio.coerceIn(info.zoomMin, info.zoomMax)
        if (kotlin.math.abs(clamped - previewZoom) < 0.01f) return
        previewZoom = clamped
        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
        builder.addTarget(previewSurface!!)
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
        applyFlash(builder, still = false)
        session?.setRepeatingRequest(builder.build(), focusResultCallback, backgroundHandler)
    }

    fun currentPreviewZoom(): Float = previewZoom

    private fun pickMaxJpegSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return Size(1920, 1080)
        return sizes.filter { it.width.toLong() * it.height <= 12_500_000L }
            .maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)
    }

    private fun round1(v: Float): Float = kotlin.math.round(v * 10f) / 10f

    fun start() {
        backgroundThread = HandlerThread("CameraBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stop() {
        session?.close(); session = null
        cameraDevice?.close(); cameraDevice = null
        readers.values.forEach { it.close() }
        readers.clear()
        logicalReader?.close(); logicalReader = null
        teleSurface = null
        ultraSurface = null
        dualPreviewActive = false
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        lastPreviewFrameAtMs = 0L
    }

    /** Choose the physical lens that best matches the requested zoom. */
    private fun pickLens(info: LensInfo, requested: Float): PhysicalLens {
        val below = info.lenses.filter { it.zoomRatio <= requested + 0.01f }
        return below.maxByOrNull { it.zoomRatio } ?: info.lenses.first()
    }

    /**
     * Open the camera with two per-lens preview streams (telephoto on top,
     * ultra-wide on bottom) plus JPEG readers for capture. Returns `true` if
     * the hardware accepted the dual-preview session, `false` if the caller
     * should fall back to `open(info, singleSurface)`.
     */
    @SuppressLint("MissingPermission")
    suspend fun openDualPreview(info: LensInfo, tele: Surface, ultra: Surface): Boolean {
        val teleLensChoice = info.tele ?: return false
        val ultraLensChoice = info.ultra ?: return false
        this.lensInfo = info
        this.teleSurface = tele
        this.ultraSurface = ultra
        this.teleLens = teleLensChoice
        this.ultraLens = ultraLensChoice
        // Fallback single preview points at the tele surface, so the app always
        // has a valid handle even if one of the two streams dies.
        this.previewSurface = tele

        info.lenses.forEach { l ->
            readers[l.physicalId] = ImageReader.newInstance(
                l.jpegSize.width, l.jpegSize.height, ImageFormat.JPEG, 2
            )
        }

        cameraDevice = suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(info.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Camera error $error"))
                }
            }, backgroundHandler)
        }

        val outputs = mutableListOf<OutputConfiguration>()
        outputs.add(OutputConfiguration(tele).apply { setPhysicalCameraId(teleLensChoice.physicalId) })
        outputs.add(OutputConfiguration(ultra).apply { setPhysicalCameraId(ultraLensChoice.physicalId) })
        info.lenses.forEach { l ->
            outputs.add(OutputConfiguration(readers[l.physicalId]!!.surface).apply {
                setPhysicalCameraId(l.physicalId)
            })
        }

        val executor = Executors.newSingleThreadExecutor()
        val sessionCreated = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    dualPreviewActive = true
                    startDualPreview()
                    sessionCreated.complete(true)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "preview + JPEG session config failed")
                    sessionCreated.completeExceptionally(RuntimeException("session configure failed"))
                }
            }
        )
        cameraDevice!!.createCaptureSession(sessionConfig)
        return sessionCreated.await()
    }

    private fun startDualPreview() {
        val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        teleSurface?.let { builder.addTarget(it) }
        ultraSurface?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        applyFlash(builder, still = false)
        session!!.setRepeatingRequest(builder.build(), focusResultCallback, backgroundHandler)
    }

    /**
     * CaptureCallback that samples LENS_FOCUS_DISTANCE off every preview
     * frame. dioptre = 1/m; 0 means infinity, MIN_FOCUS_DISTANCE is the
     * closest the lens can focus. We publish the reciprocal in meters so the
     * UI just reads a plain distance.
     */
    private val focusResultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastPreviewFrameAtMs = android.os.SystemClock.elapsedRealtime()
            val dioptre = result.get(Cam2CaptureResult.LENS_FOCUS_DISTANCE)
            lastFocusDistanceM = when {
                dioptre == null -> null
                dioptre <= 0f -> null              // infinity → not a distance
                else -> 1f / dioptre               // dioptre → meters
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun open(info: LensInfo, previewSurface: Surface) {
        this.lensInfo = info
        this.previewSurface = previewSurface
        this.dualPreviewActive = false

        // Only the LOGICAL-camera reader is needed for the current capture
        // pipeline. The physical-per-lens readers we used before pushed the
        // total output count past what the driver's mandatory stream set
        // supports, which the driver reports as
        //   endConfigure: Function not implemented (-38).
        // Keep the map for API compatibility but leave it empty.
        val logicalSize = info.main?.jpegSize
            ?: info.lenses.firstOrNull()?.jpegSize
            ?: Size(1920, 1080)
        logicalReader = ImageReader.newInstance(
            logicalSize.width, logicalSize.height, ImageFormat.JPEG, 4
        )

        cameraDevice = suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(info.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Camera error $error"))
                }
            }, backgroundHandler)
        }

        // Session stream set: preview + one JPEG reader. This combination
        // ("PRIV + JPEG at MAX") is in the mandatory list every Camera2 device
        // must accept, which sidesteps the -38 error we hit before.
        val outputs = mutableListOf<OutputConfiguration>()
        outputs.add(OutputConfiguration(previewSurface))
        outputs.add(OutputConfiguration(logicalReader!!.surface))

        val executor = Executors.newSingleThreadExecutor()
        val sessionCreated = kotlinx.coroutines.CompletableDeferred<CameraCaptureSession>()
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    sessionCreated.complete(s)
                    startPreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "preview + JPEG session config failed")
                    sessionCreated.completeExceptionally(RuntimeException("session configure failed"))
                }
            }
        )
        cameraDevice!!.createCaptureSession(sessionConfig)
        sessionCreated.await()
    }

    private fun startPreview() {
        val info = lensInfo
        // Start at the widest available telephoto so the user sees the defect
        // close-up right away (they can pinch out for context).
        previewZoom = info?.tele?.zoomRatio?.coerceAtMost(info.zoomMax) ?: 1f
        val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(previewSurface!!)
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, previewZoom)
        applyFlash(builder, still = false)
        session!!.setRepeatingRequest(builder.build(), focusResultCallback, backgroundHandler)
    }

    private fun refreshPreviewRequest() {
        val cd = cameraDevice ?: return
        val currentSession = session ?: return
        val builder = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        if (dualPreviewActive) {
            teleSurface?.let(builder::addTarget)
            ultraSurface?.let(builder::addTarget)
        } else {
            previewSurface?.let(builder::addTarget)
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, previewZoom)
        }
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        applyFlash(builder, still = false)
        currentSession.setRepeatingRequest(builder.build(), focusResultCallback, backgroundHandler)
    }

    private fun applyFlash(builder: CaptureRequest.Builder, still: Boolean) {
        when (flashMode) {
            FlashMode.AUTO -> builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            )
            FlashMode.ON -> builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                if (still) CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else CameraMetadata.CONTROL_AE_MODE_ON
            )
            FlashMode.OFF -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
        }
    }

    /**
     * Captures one JPEG per slot using the logical camera + CONTROL_ZOOM_RATIO,
     * so what the user sees in the preview (at any user-set zoom) is what gets
     * saved. Slots are shot in sequence — the app switches the logical zoom
     * between shots — which yields perfectly matched preview/output at the
     * cost of a small (~150 ms) inter-shot delay.
     */
    suspend fun capture(spec: CaptureSpec): CaptureResult = suspendCancellableCoroutine { cont ->
        val cd = cameraDevice ?: run { cont.resumeWithException(IllegalStateException("no camera")); return@suspendCancellableCoroutine }
        val s = session ?: run { cont.resumeWithException(IllegalStateException("no session")); return@suspendCancellableCoroutine }
        val info = lensInfo ?: run { cont.resumeWithException(IllegalStateException("no lens info")); return@suspendCancellableCoroutine }

        // Use the logical-camera reader; CONTROL_ZOOM_RATIO on the request will
        // pick the underlying physical lens automatically.
        val slots = spec.slots
        val reader = logicalReader
            ?: run { cont.resumeWithException(IllegalStateException("no logical reader")); return@suspendCancellableCoroutine }

        val shots = java.util.concurrent.CopyOnWriteArrayList<CapturedShot>()
        var currentSlotIndex = 0

        fun finish() {
            if (cont.isActive) {
                val completed = shots.toList().sortedBy { it.slot.ordinal }
                val missing = slots.map { it.first }.filterNot { expected ->
                    completed.any { it.slot == expected }
                }
                if (missing.isNotEmpty()) {
                    cont.resumeWithException(
                        IllegalStateException("Required capture missing: ${missing.joinToString()}")
                    )
                } else {
                    cont.resume(CaptureResult(completed))
                }
            }
        }

        fun continueWithAvailableCloseShot(slot: SlotRole, reason: String) {
            val completed = shots.toList().sortedBy { it.slot.ordinal }
            val closeExists = completed.any { it.slot == SlotRole.A }
            if (slot != SlotRole.A && closeExists && cont.isActive) {
                // The close image is already durable evidence. A missing context
                // frame must not discard it or block the field-inspection flow.
                Log.w(TAG, "Context photo skipped for $slot: $reason")
                cont.resume(CaptureResult(completed))
            } else if (cont.isActive) {
                cont.resumeWithException(IllegalStateException(reason))
            }
        }

        fun shootNext() {
            if (currentSlotIndex >= slots.size) { finish(); return }
            val (slot, requestedZoomRaw) = slots[currentSlotIndex]
            val clampedZoom = requestedZoomRaw.coerceIn(info.zoomMin, info.zoomMax)
            currentSlotIndex++

            // Build a still-capture request that includes the *current* zoom.
            val builder = cd.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            // JPEG_ORIENTATION only writes an EXIF hint; the raw pixels stay in
            // sensor orientation. Our downstream ImageOverlay.stampSensorLine
            // re-encodes the JPEG (destroying that EXIF hint), so we set
            // orientation = 0 here and rotate the bitmap in software during
            // the stamp pass instead — the file on disk ends up upright by
            // pixel, not by tag.
            builder.set(CaptureRequest.JPEG_ORIENTATION, 0)
            applyFlash(builder, still = true)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom)

            val outFile = ImageStore.newCaptureFile(context, "slot_${slot.name}")
            // One listener is installed for one slot.  Do not use
            // acquireLatestImage(): when a wide shot arrives while the driver
            // still has the close frame queued, that method is allowed to drop
            // the older frame.  Reading the next frame keeps the A/B evidence
            // pair in the exact order requested by the operator.
            var delivered = false
            reader.setOnImageAvailableListener({ r ->
                if (delivered) return@setOnImageAvailableListener
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                delivered = true
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    FileOutputStream(outFile).use { it.write(bytes) }
                } finally { image.close() }
                // Was the user asking for something wider than the widest lens?
                // If so, the driver just returned the widest frame and we
                // digitally shrink to letterbox for the "wide context" feel.
                val isDigital = clampedZoom < requestedZoomRaw - 0.02f ||
                                requestedZoomRaw < info.zoomMin - 0.02f
                if (isDigital && requestedZoomRaw < info.zoomMin) {
                    val tmp = ImageStore.newCaptureFile(context, "tmp_${slot.name}")
                    outFile.copyTo(tmp, overwrite = true)
                    digitalDownscale(tmp, outFile, info.zoomMin, requestedZoomRaw)
                    tmp.delete()
                }
                ImageStore.writeExifMeta(
                    file = outFile,
                    deviceLabel = android.os.Build.MODEL ?: "",
                    slot = slot.name,
                    zoomRatio = requestedZoomRaw,
                    isDigital = isDigital
                )
                shots.add(
                    CapturedShot(
                        filePath = outFile.absolutePath,
                        slot = slot,
                        lensTag = if (clampedZoom >= 1f) "MAIN" else "ULTRA",
                        requestedZoom = requestedZoomRaw,
                        effectiveZoom = clampedZoom,
                        isDigital = isDigital
                    )
                )
                // The close image is already secured. Start the context frame
                // immediately so the operator has no time to move the camera.
                // Only the second request performs its short lens-settle wait.
                backgroundHandler?.post { shootNext() }
            }, backgroundHandler)

            // Slot A is already the live preview framing, so fire it at once.
            // Slot B must wait for the camera HAL to apply the optical/logical
            // zoom switch. A short 80 ms delay can return the previous close-up
            // frame on Samsung devices, making close and wide photos identical.
            // 300 ms is still short enough to prevent operator movement while
            // reliably producing a second, wider capture.
            val settleDelayMs = if (currentSlotIndex == 1) 0L else 300L
            setPreviewZoom(clampedZoom)
            backgroundHandler?.postDelayed({
                try {
                    s.capture(builder.build(), null, backgroundHandler)
                } catch (t: Throwable) {
                    continueWithAvailableCloseShot(slot, "Photo capture failed for ${slot.name}: ${t.message}")
                }
            }, settleDelayMs)
            // A driver may omit the second JPEG after zooming out. Keep the
            // already secured close photo and proceed; only Slot A is mandatory.
            backgroundHandler?.postDelayed({
                if (!delivered && cont.isActive) {
                    continueWithAvailableCloseShot(
                        slot,
                        "Photo capture timed out for ${slot.name}"
                    )
                }
            }, 8_000L)
        }

        // Kick off the first slot capture. The chain drives itself from there.
        shootNext()
    }

    private fun lensTagOf(info: LensInfo, lens: PhysicalLens): String = when {
        info.lenses.size == 1 -> "MAIN"
        lens == info.lenses.first() -> "ULTRA"
        lens == info.lenses.last() -> "TELE"
        else -> "MAIN"
    }

    /**
     * Simulate a wider-than-optical zoom by downscaling the source into a black
     * canvas. Canvas keeps the source aspect ratio, so downstream tiles/dialogs
     * that use ContentScale.Fit render the letterbox symmetrically (no half-black
     * artifact from asymmetric cropping).
     */
    private fun digitalDownscale(src: File, dst: File, physicalZoom: Float, requestedZoom: Float): Boolean {
        return try {
            val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return false
            val factor = (requestedZoom / physicalZoom).coerceIn(0.1f, 1f)
            val innerW = (bmp.width * factor).toInt().coerceAtLeast(1)
            val innerH = (bmp.height * factor).toInt().coerceAtLeast(1)
            val inner = Bitmap.createScaledBitmap(bmp, innerW, innerH, true)
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(ACol.BLACK)
            val left = (bmp.width - innerW) / 2
            val top = (bmp.height - innerH) / 2
            c.drawBitmap(inner, null, Rect(left, top, left + innerW, top + innerH), Paint(Paint.FILTER_BITMAP_FLAG))
            FileOutputStream(dst).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out.recycle(); inner.recycle(); bmp.recycle()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "digitalDownscale failed: ${t.message}")
            false
        }
    }
}
