package com.aicamera.photo.core.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * @param quarter how far the phone is rotated counter-clockwise from portrait, snapped to 0/90/180/270.
 * @param tiltDegrees residual roll relative to [quarter] (positive = rotated counter-clockwise, so the
 *   horizon slopes down to the right). Null when the phone is pointing nearly straight up or down.
 * @param stability 0..1, from the gyroscope (1 = perfectly still).
 */
data class DeviceOrientation(
    val quarter: Int = 0,
    val tiltDegrees: Float? = null,
    val stability: Float = 1f,
) {
    /** Clockwise rotation to apply to a display-upright frame to make it world-upright. */
    val worldRotationCw: Int get() = (360 - quarter) % 360

    val surfaceRotation: Int
        get() = when (quarter) {
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
}

class DeviceOrientationMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _state = MutableStateFlow(DeviceOrientation())
    val state: StateFlow<DeviceOrientation> = _state

    private var quarter = 0
    private var gyroEma = 0f
    private var rollEma: Float? = null

    fun start() {
        gravity?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> onGravity(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_GYROSCOPE -> {
                val mag = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                gyroEma += 0.15f * (mag - gyroEma)
                val stability = (1f - gyroEma / 0.8f).coerceIn(0f, 1f)
                _state.value = _state.value.copy(stability = stability)
            }
        }
    }

    private fun onGravity(x: Float, y: Float, z: Float) {
        val planar = sqrt(x * x + y * y)
        val g = sqrt(planar * planar + z * z).coerceAtLeast(0.1f)
        if (planar / g < 0.45f) {
            // Pointing at the sky or the floor: roll is not meaningful.
            rollEma = null
            _state.value = _state.value.copy(tiltDegrees = null)
            return
        }
        val roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat() // CCW positive, -180..180
        val smoothed = rollEma?.let { prev ->
            var d = roll - prev
            if (d > 180) d -= 360
            if (d < -180) d += 360
            prev + 0.3f * d
        } ?: roll
        rollEma = smoothed

        // Snap to a quarter with hysteresis (switch only when clearly past 45 degrees).
        val normalized = ((smoothed % 360) + 360) % 360
        val distance = angularDistance(normalized, quarter.toFloat())
        if (distance > 55f) {
            quarter = listOf(0, 90, 180, 270).minBy { angularDistance(normalized, it.toFloat()) }
        }
        var residual = normalized - quarter
        if (residual > 180) residual -= 360
        if (residual < -180) residual += 360
        _state.value = _state.value.copy(quarter = quarter, tiltDegrees = residual)
    }

    private fun angularDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
