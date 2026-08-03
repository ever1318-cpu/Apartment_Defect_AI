package com.axlife.pinset.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.axlife.pinset.data.entity.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads Android's fused ROTATION_VECTOR sensor and exposes:
 *   - pitch (degrees): angle of the camera optical axis vs. horizon.
 *       +90 = pointing straight up   (ceiling)
 *         0 = level                  (wall)
 *       -90 = pointing straight down (floor)
 *   - heading (degrees): azimuth relative to magnetic north, 0..360
 *
 * Drift-free thanks to sensor fusion. Available on virtually every modern
 * Android phone; no user calibration required.
 */
class ImuOrientation(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _pitchDeg = MutableStateFlow(0f)
    val pitchDeg: StateFlow<Float> = _pitchDeg.asStateFlow()

    private val _headingDeg = MutableStateFlow(0f)
    val headingDeg: StateFlow<Float> = _headingDeg.asStateFlow()

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rot = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        // Remap so screen-up axis becomes Z and screen-right stays X.
        // For a phone held in portrait aiming at a wall, pitch is the tilt of
        // the camera's optical axis vs. horizontal.
        val remapped = FloatArray(9)
        SensorManager.remapCoordinateSystem(
            rot,
            SensorManager.AXIS_X, SensorManager.AXIS_Z,
            remapped
        )
        val orient = FloatArray(3)
        SensorManager.getOrientation(remapped, orient)
        val heading = ((Math.toDegrees(orient[0].toDouble()).toFloat()) + 360f) % 360f
        val pitch = Math.toDegrees(orient[1].toDouble()).toFloat()
        _headingDeg.value = heading
        _pitchDeg.value = pitch
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* unused */ }
}

/** Map an IMU pitch (degrees) to the app's Surface enum. */
fun surfaceFromPitch(pitchDeg: Float): Surface = when {
    pitchDeg > 30f  -> Surface.CEILING
    pitchDeg < -30f -> Surface.FLOOR
    else            -> Surface.WALL
}

/** Five camera-angle bands used for field recommendations. Storage retains
 * the compatible three-class Surface value. */
enum class CaptureSurfaceBand(val label: String, val storageSurface: Surface) {
    CEILING("천장", Surface.CEILING),
    CEILING_WALL("\ucc9c\uc7a5/\ubcbd", Surface.CEILING),
    WALL("\ubcbd/\ubcbd", Surface.WALL),
    WALL_FLOOR("\ubcbd/\ubc14\ub2e5", Surface.FLOOR),
    FLOOR("바닥", Surface.FLOOR)
}

fun captureSurfaceBandFromPitch(pitchDeg: Float): CaptureSurfaceBand = when {
    pitchDeg >= 50f -> CaptureSurfaceBand.CEILING
    pitchDeg >= 20f -> CaptureSurfaceBand.CEILING_WALL
    pitchDeg > -20f -> CaptureSurfaceBand.WALL
    pitchDeg > -50f -> CaptureSurfaceBand.WALL_FLOOR
    else -> CaptureSurfaceBand.FLOOR
}
