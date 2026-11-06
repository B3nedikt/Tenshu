package app.tenshu.timeline.data.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * **The hardware pedometer, read as steps-between-fixes** (S3, `timeline-movement-capture.md` §19).
 *
 * `TYPE_STEP_COUNTER` reports a **monotonic total since boot**, not a delta, so this holds the last
 * reading and answers differences. That shape is deliberate on Android's side and useful here: the
 * counter keeps advancing while the app sleeps, so a batched delivery that arrives late still carries
 * every step that happened — nothing is lost by not being listening at the moment a step is taken.
 *
 * ⚠⚠ **Absent sensor ⇒ `null`, never `0`.** [LegMode] treats the two as opposite claims: `0` is
 * evidence of wheels, `null` is no evidence at all. A device with no pedometer that reported `0`
 * would have every drive, every train and every walk-with-the-phone-in-a-bag read as cycling.
 *
 * ⚠ Registered with a **batch latency** (`maxReportLatencyUs`), so the sensor hub buffers and the
 * application processor is woken as rarely as the platform allows — the same posture S1 takes for
 * location. The pedometer is one of the cheapest sensors on the die; the wakeups were always the
 * cost, not the counting.
 *
 * ⚠ Needs `ACTIVITY_RECOGNITION` (API 29+) **declared and granted**. Undeclared, `getDefaultSensor`
 * returns non-null on some devices and simply never delivers — a silent degradation with no crash and
 * no log, which is the failure mode this codebase has been bitten by before. [isAvailable] answers
 * the declaration+presence question so a caller can distinguish "no pedometer" from "no steps".
 */
class AndroidStepCounter(context: Context) : StepCounter {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    /** Total steps since boot at the last delivery, or `null` before the first one. */
    @Volatile
    private var totalSteps: Float? = null

    /** The reading at the previous [stepsSinceLast] call — the subtrahend for the next delta. */
    private var lastConsumed: Float? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            event.values.firstOrNull()?.let { totalSteps = it }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun isAvailable(): Boolean = sensor != null

    override fun start() {
        val s = sensor ?: return
        runCatching {
            sensorManager?.registerListener(listener, s, SAMPLING_PERIOD_US, BATCH_LATENCY_US)
        }
    }

    override fun stop() {
        runCatching { sensorManager?.unregisterListener(listener) }
        totalSteps = null
        lastConsumed = null
    }

    /**
     * Steps since the previous call, or `null` when the sensor is absent or has not reported yet.
     *
     * ⚠ The **first** call after [start] returns `null` rather than "everything since boot": there is
     * no previous reading to subtract, and a boot-total attributed to one leg would read as a
     * marathon. Cadence simply begins one fix later.
     *
     * ⚠ A counter that went *backwards* (a reboot resets it) yields `null` and re-baselines, rather
     * than a negative delta or a huge positive one on the next call.
     */
    override fun stepsSinceLast(): Int? {
        if (sensor == null) return null
        val current = totalSteps ?: return null
        val previous = lastConsumed
        lastConsumed = current
        if (previous == null) return null
        val delta = current - previous
        if (delta < 0) return null // rebooted mid-capture; this call re-baselines
        return delta.toInt()
    }

    private companion object {
        /**
         * The pedometer emits on step events, not on a clock, so the sampling period is close to
         * advisory. `SENSOR_DELAY_NORMAL`'s ~200 ms is far finer than anything needed here.
         */
        const val SAMPLING_PERIOD_US = 200_000

        /** Batch for a minute: the AP wakes at most once per minute for steps, and usually less. */
        const val BATCH_LATENCY_US = 60_000_000
    }
}
