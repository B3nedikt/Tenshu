package app.tenshu.timeline.data.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The **Android live [LocationCaptureSource]** (I3b-2 + movement capture M3 + adaptive sensing S1,
 * `docs/proposals/timeline-movement-capture.md` §6, §17.2): a raw fix stream from the platform
 * [LocationManager], segmented into dwells **and** moves by the shared [segment] operator.
 * **Deliberately `LocationManager`, not Play-Services fused location** — the de-Googled stance: no
 * `com.google.android.gms` dependency, works on de-Googled devices; fused location is a battery
 * optimisation we can add later behind this same seam.
 *
 * ### Why these sensing parameters (the on-device findings, §11.1 — do not regress these)
 *  - **[minDistanceM] = 0**, not a metres gate. `LocationManager` only broadcasts a fix once you have
 *    *moved* [minDistanceM] — so any positive value means a **stationary device gets no fixes at all**,
 *    and dwell detection (which needs fixes *while you stay put*) records nothing. Updates are paced by
 *    the interval alone.
 *  - **All enabled providers** ([activeProviders]), not GPS-only. Indoors GPS often never gets a fix
 *    while `NETWORK` (wifi/cell) does; listening on both is what makes an indoor place record.
 *  - **Recent last-known seed.** Seeding from `getLastKnownLocation` lets a dwell start without waiting
 *    for the first live fix — but only if the fix is fresh ([SEED_MAX_AGE_MS]); a stale one carries an
 *    old timestamp that would mis-date the dwell's arrival.
 *
 * ### Adaptive pacing (S1, §17.2)
 * The interval is no longer one shape for all of life. It follows a [CapturePace] derived from **the
 * segmenter's own output** — a genuine feedback cycle: fixes → [segment] → events →
 * [CapturePacePolicy.paceFor] → a new [LocationRequestSpec] → re-registered request. Nothing observes
 * the user twice, so nothing can hold two opinions about what they are doing.
 *
 * ⚠⚠ **Stationary is a slow request, not the absence of one** (§18.6). Dwell *departure* is detected
 * by a fix landing outside the anchor radius, so a device that stopped requesting location while
 * dwelling would also stop noticing that the user left. Significant motion is layered on as a *fast
 * wake*, not as a replacement.
 *
 * Runs only while the capture foreground service holds it (off by default, threat-model A35). The fix
 * flow is cold; nothing samples location until [events] is collected, and it stops the moment the
 * collector is cancelled ([awaitClose] → `removeUpdates`). All per-collection state (the pace cell,
 * the listener, the armed trigger) lives inside that collection, so nothing leaks between two.
 */
class AndroidLocationCaptureSource(
    private val context: Context,
    private val config: VisitDetectionConfig = VisitDetectionConfig(),
    private val minDistanceM: Float = 0f,
    private val moveConfig: MovementDetectionConfig = MovementDetectionConfig(),
    /**
     * Durable open-move journal. Defaults to the app-private file store so the segmenter's
     * in-progress journey survives the foreground service being force-killed (§11.4 / §12.0); pass
     * [OpenMoveJournal.None] to opt out.
     */
    private val journal: OpenMoveJournal = androidOpenMoveJournal(context),
    private val dwellJournal: OpenMoveJournal = androidOpenDwellJournal(context),
    /**
     * Debug-only raw-fix corpus (§18.9). Defaults to [RawFixJournal.None] so it records nothing
     * unless a build explicitly binds it — release never does.
     */
    private val rawFixJournal: RawFixJournal = RawFixJournal.None,
    /**
     * Hardware pedometer (S3, §19). Defaults to [StepCounter.None] so a device without one — or a
     * build that has not granted `ACTIVITY_RECOGNITION` — degrades to exactly the pre-S3 speed-only
     * classification rather than to a different one.
     */
    private val stepCounter: StepCounter = StepCounter.None,
    /** The device's API level. Injected so the [CapturePacePolicy] batching clamp is exercisable. */
    private val apiLevel: Int = Build.VERSION.SDK_INT,
) : LocationCaptureSource {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override fun isAvailable(): Boolean =
        hasFineLocationPermission() && activeProviders().isNotEmpty()

    /** The event stream, and the pace feedback loop that drives its own sampling rate. */
    override fun events(): Flow<CaptureEvent> {
        // ON_FOOT, not STATIONARY: at the moment capture starts we know nothing, and the mid-range
        // request is the one that can discover either answer. Starting stationary would sample a walk
        // at one fix per five minutes until a dwell it cannot see confirms.
        val pace = MutableStateFlow(CapturePace.ON_FOOT)
        return fixes(pace)
            // ⚠ Stamped here, on the way OUT of the platform layer and BEFORE the journal and the
            // segmenter, so every downstream consumer — corpus, classifier, splitter — sees the same
            // per-fix step delta. Reading the counter later would attribute steps to the wrong leg.
            .map { it.copy(stepsSinceLastFix = stepCounter.stepsSinceLast()) }
            // ⚠ Recorded BEFORE `segment`, deliberately: the corpus must be the fix stream as the
            // platform delivered it, not what survived clustering. Anything downstream of the
            // segmenter has already had the very decisions the replay wants to re-run applied to it.
            .onEach { rawFixJournal.record(it) }
            // §22: GPS and NETWORK are two independent listeners whose fixes interleave by ARRIVAL,
            // not by timestamp — 6.1 % of consecutive pairs ran backwards in real captured data,
            // violating the segmenter's documented ordering assumption. ⚠ Applied AFTER the corpus
            // records, so the corpus keeps the stream exactly as the platform delivered it.
            .inTimeOrder()
            .segment(config, moveConfig, journal, dwellJournal)
            .onEach { pace.value = CapturePacePolicy.paceFor(it) }
    }

    private fun fixes(pace: MutableStateFlow<CapturePace>): Flow<CapturedFix> = callbackFlow {
        val lm = locationManager
        val providers = activeProviders()
        if (lm == null || providers.isEmpty() || !hasFineLocationPermission()) {
            close()
            return@callbackFlow
        }
        // Explicit object (not a SAM lambda): pre-API-30 LocationListener declares the extra
        // callbacks as abstract, so a lambda would AbstractMethodError on API 24–29.
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                trySend(location.toFix())
            }

            override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        fun register(spec: LocationRequestSpec) {
            val request = LocationRequestCompat.Builder(spec.intervalMs)
                // ⚠ 0 — the §11.1 finding. A positive distance gate silences a stationary device.
                .setMinUpdateDistanceMeters(minDistanceM)
                // The batch window. 0 (below API 31) means "deliver as they land", the old behaviour.
                .setMaxUpdateDelayMillis(spec.maxUpdateDelayMs)
                .build()
            providers.forEach { p ->
                LocationManagerCompat.requestLocationUpdates(
                    lm, p, request, listener, Looper.getMainLooper(),
                )
            }
        }

        /**
         * `TYPE_SIGNIFICANT_MOTION` — a **hardware one-shot** that fires when the device has probably
         * changed location. It is the fast wake that pairs with the slow stationary request (§18.6):
         * a hardware trigger does not jitter, so it is a strictly better departure hint than another
         * coarse fix would be.
         *
         * ⚠ **One-shot: the platform unregisters the listener as it delivers**, so a further trigger
         * needs a re-arm. Here the trigger promotes the pace to [CapturePace.ON_FOOT], which by
         * construction leaves the stationary branch — so the re-arm point is the *next* transition
         * back into [CapturePace.STATIONARY], and there is deliberately no re-arm inside the callback
         * that would leave a trigger live while moving.
         *
         * ⚠ **No runtime permission, and none was added to `timeline-android`'s manifest.**
         * `ACTIVITY_RECOGNITION` gates `TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR` (API 29+);
         * significant motion is not in that group. (Checked rather than assumed: a permission that is
         * required in code but undeclared in the manifest fails *silently*.) A device without the
         * sensor gets no fast wake and the slow request still detects departure on its own.
         */
        var armedListener: TriggerEventListener? = null
        var armedSensor: Sensor? = null

        fun cancelSignificantMotion() {
            val sm = sensorManager ?: return
            val l = armedListener ?: return
            armedSensor?.let { runCatching { sm.cancelTriggerSensor(l, it) } }
            armedListener = null
            armedSensor = null
        }

        fun armSignificantMotion() {
            val sm = sensorManager ?: return
            if (armedListener != null) return
            val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
            val l = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent?) {
                    armedListener = null // the platform has already unregistered us
                    armedSensor = null
                    pace.value = CapturePace.ON_FOOT
                }
            }
            if (runCatching { sm.requestTriggerSensor(l, sensor) }.getOrDefault(false)) {
                armedListener = l
                armedSensor = sensor
            }
        }

        stepCounter.start()
        try {
            providers.forEach { p ->
                // Seed with a *recent* last-known so a dwell can start without waiting for a live fix.
                lm.getLastKnownLocation(p)?.let {
                    if (System.currentTimeMillis() - it.time in 0..SEED_MAX_AGE_MS) trySend(it.toFix())
                }
            }
            register(CapturePacePolicy.requestFor(pace.value, apiLevel))
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        // Re-register on every pace change. `drop(1)` because the initial pace is already live above,
        // and re-registering it would restart the interval for nothing.
        launch {
            pace.drop(1).collect { p ->
                runCatching {
                    // removeUpdates first: requesting again with the same listener does replace the
                    // request per provider, but an explicit removal makes the transition legible and
                    // covers a provider that has since been disabled.
                    lm.removeUpdates(listener)
                    register(CapturePacePolicy.requestFor(p, apiLevel))
                }
                if (p == CapturePace.STATIONARY) armSignificantMotion() else cancelSignificantMotion()
            }
        }

        awaitClose {
            stepCounter.stop()
            lm.removeUpdates(listener)
            cancelSignificantMotion()
        }
    }

    /** Every enabled provider we can use (GPS + network), most-precise first. Empty = none available. */
    private fun activeProviders(): List<String> = locationManager?.let { lm ->
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    } ?: emptyList()

    private fun hasFineLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Location.toFix(): CapturedFix = CapturedFix(
        latE7 = (latitude * 1e7).roundToLong(),
        lngE7 = (longitude * 1e7).roundToLong(),
        timestampMs = time,
        accuracyM = if (hasAccuracy()) accuracy.roundToInt() else 0,
    )

    private companion object {
        /** Last-known seeds older than this are skipped — a stale timestamp would mis-date the dwell. */
        const val SEED_MAX_AGE_MS = 2 * 60 * 1000L
    }
}
