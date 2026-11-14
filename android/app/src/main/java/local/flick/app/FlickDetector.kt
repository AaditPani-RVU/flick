package local.flick.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

/**
 * Fires when the phone is snapped forward with the wrist.
 *
 * A wrist flick is a fast rotation, mostly about the phone's X axis (the top edge tipping
 * away from you), so the gyroscope sees it far more cleanly than the accelerometer does.
 */
class FlickDetector(context: Context, private val onFlick: (power: Float) -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Rotation speed in rad/s that counts as a flick. Lower is more sensitive. */
    var threshold = 5.0f

    val available: Boolean get() = gyroscope != null

    private var lastFlickAt = 0L
    private var peak = 0f
    private var peakStartedAt = 0L

    fun start() {
        gyroscope?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensors.unregisterListener(this)
        peak = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlickAt < COOLDOWN_MS) return

        val pitch = abs(event.values[0])
        val other = max(abs(event.values[1]), abs(event.values[2]))
        val strength = max(pitch, other * 0.7f)

        if (strength >= threshold) {
            // Follow the swing up to its fastest point so a harder flick reports more power.
            if (peak == 0f) peakStartedAt = now
            peak = max(peak, strength)
            if (now - peakStartedAt < PEAK_WINDOW_MS) return
        }
        if (peak > 0f) {
            val power = ((peak - threshold) / (threshold * 1.5f)).coerceIn(0f, 1f)
            peak = 0f
            lastFlickAt = now
            onFlick(0.3f + 0.7f * power)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val COOLDOWN_MS = 1500L
        const val PEAK_WINDOW_MS = 60L
    }
}
