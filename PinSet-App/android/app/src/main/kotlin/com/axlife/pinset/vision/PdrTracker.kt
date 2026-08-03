package com.axlife.pinset.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pedestrian Dead Reckoning tracker.
 *
 * Once the user "anchors" the session (typically on the very first capture),
 * the tracker integrates step-counter deltas along the phone's current heading
 * to estimate a live floorplan position. This is a coarse indoor estimate —
 * about ±1 m for the first few minutes, ±3 m after several rooms — but it is
 * enough to drop live pins onto a floorplan and orient the operator.
 *
 * Inputs:
 *   - TYPE_STEP_COUNTER  (Android)
 *   - Heading (fed in by the caller from ImuOrientation)
 *
 * Configuration:
 *   - strideMeters: 0.7 m default. Passed by the caller because personal
 *     stride varies. Later releases can auto-tune from time-between-steps.
 *
 * Output (as StateFlow):
 *   - relX / relZ (meters, east / north relative to the anchor)
 *   - stepCount   (cumulative steps since the anchor)
 */
class PdrTracker(context: Context) : SensorEventListener {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _relX = MutableStateFlow(0f)   // east meters from anchor
    val relX: StateFlow<Float> = _relX.asStateFlow()

    private val _relZ = MutableStateFlow(0f)   // north meters (screen -y)
    val relZ: StateFlow<Float> = _relZ.asStateFlow()

    private val _steps = MutableStateFlow(0)
    val steps: StateFlow<Int> = _steps.asStateFlow()

    private var lastStepValue: Int = -1
    private var currentHeadingRad: Float = 0f
    private var strideMeters: Float = 0.7f
    private var running = false

    fun start(strideMeters: Float = 0.7f) {
        if (running) return
        this.strideMeters = strideMeters
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        running = true
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        running = false
    }

    /** Reset the anchor to (0, 0) and clear the step baseline. */
    fun reset() {
        lastStepValue = -1
        _relX.value = 0f
        _relZ.value = 0f
        _steps.value = 0
    }

    /** Called continuously by the camera VM with the latest fused heading. */
    fun setHeadingDeg(headingDeg: Float) {
        currentHeadingRad = (headingDeg * (PI / 180.0)).toFloat()
    }

    /** Whether Android reports a step counter on this device. */
    fun available(): Boolean = stepSensor != null

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val v = event.values[0].toInt()
        if (lastStepValue < 0) {
            lastStepValue = v
            return
        }
        val newSteps = v - lastStepValue
        if (newSteps <= 0) return
        lastStepValue = v
        val distMeters = newSteps * strideMeters
        // Integrate along current heading. East = +x, north = -z (screen).
        val dx = distMeters * sin(currentHeadingRad)
        val dz = -distMeters * cos(currentHeadingRad)
        _relX.value = _relX.value + dx
        _relZ.value = _relZ.value + dz
        _steps.value = _steps.value + newSteps
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* unused */ }
}
