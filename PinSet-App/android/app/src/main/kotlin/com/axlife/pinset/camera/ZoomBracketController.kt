package com.axlife.pinset.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.util.ImageStore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Sequential-zoom fallback used when Logical Multi-Camera is unavailable.
 * For each slot (A, B, C), ramps the CameraX zoom to the requested ratio
 * and takes one photo. Not currently wired into the main capture path but
 * kept in place so a future device that lacks multi-camera support can
 * fall back gracefully.
 */
class ZoomBracketController(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    suspend fun bind(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider): Boolean {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val provider = suspendCancellableCoroutine { cont ->
            providerFuture.addListener({
                try { cont.resume(providerFuture.get()) }
                catch (e: Exception) { cont.resumeWithException(e) }
            }, ContextCompat.getMainExecutor(context))
        }

        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        provider.unbindAll()
        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        imageCapture = capture
        return true
    }

    fun deviceZoomRange(): Pair<Float, Float>? {
        val zoom: ZoomState = camera?.cameraInfo?.zoomState?.value ?: return null
        return zoom.minZoomRatio to zoom.maxZoomRatio
    }

    suspend fun capture(spec: CaptureSpec): CaptureResult {
        val cam = camera ?: throw IllegalStateException("camera not bound")
        val cap = imageCapture ?: throw IllegalStateException("imageCapture not bound")

        val ctrl = cam.cameraControl
        val zoomState: ZoomState? = cam.cameraInfo.zoomState.value
        val minZ = zoomState?.minZoomRatio ?: 1f
        val maxZ = zoomState?.maxZoomRatio ?: 10f

        val shots = mutableListOf<CapturedShot>()
        for ((slot, requested) in spec.slots) {
            val effective = requested.coerceIn(minZ, maxZ)
            val isDigital = kotlin.math.abs(effective - requested) > 0.01f
            suspendCancellableCoroutine<Unit> { cont ->
                ctrl.setZoomRatio(effective).addListener({
                    if (cont.isActive) cont.resume(Unit)
                }, ContextCompat.getMainExecutor(context))
            }
            delay(180)
            val file = ImageStore.newCaptureFile(context, "slot_${slot.name}")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            val done = CompletableDeferred<Unit>()
            cap.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { done.complete(Unit) }
                override fun onError(exception: ImageCaptureException) { done.completeExceptionally(exception) }
            })
            done.await()
            shots.add(
                CapturedShot(
                    filePath = file.absolutePath,
                    slot = slot,
                    lensTag = lensTagFor(effective),
                    requestedZoom = requested,
                    effectiveZoom = effective,
                    isDigital = isDigital
                )
            )
        }
        return CaptureResult(shots.sortedBy { it.requestedZoom })
    }

    private fun lensTagFor(zoom: Float): String = when {
        zoom < 0.9f -> "ULTRA"
        zoom > 2.5f -> "TELE"
        else -> "MAIN"
    }
}
