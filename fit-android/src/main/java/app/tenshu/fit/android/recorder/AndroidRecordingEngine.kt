package app.tenshu.fit.android.recorder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.tenshu.fit.data.DraftEdits
import app.tenshu.fit.data.FinishedRecording
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.PauseClock
import app.tenshu.fit.data.RecordingDraft
import app.tenshu.fit.data.RecordingEngine
import app.tenshu.fit.data.RecordingJournalCodec
import app.tenshu.fit.data.RecordingState
import app.tenshu.fit.data.RecoveredRecording
import app.tenshu.fit.data.isResumable
import app.tenshu.fit.data.routeDistanceM
import app.tenshu.fit.data.applying
import app.tenshu.fit.data.toDraft
import app.tenshu.fit.data.SensorKind
import app.tenshu.fit.data.SensorState
import app.tenshu.fit.data.SensorStatus
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.GpsFix
import app.tenshu.fit.model.HrPoint
import app.tenshu.fit.model.RecordedTrack
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.util.UUID
import kotlin.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The **first-party Android recorder** (`docs/proposals/fit-app.md` §9.8) — the [RecordingEngine]
 * actual over the platform's own sensors, **zero Google Play Services**:
 *
 *  - **GPS** via [LocationManager] `GPS_PROVIDER` (not the fused Play-Services provider) — real
 *    fixes → the [RecordedTrack] route + a haversine distance accumulator.
 *  - **Heart rate** via a **BLE strap** — GATT Heart Rate Service (`0x180D`) / Measurement
 *    characteristic (`0x2A37`), the standard interoperable profile every chest strap speaks.
 *  - **Steps** via the on-device [Sensor.TYPE_STEP_COUNTER] — a hardware pedometer, no network.
 *
 * Location is acquired **only while a workout is active** (started in [start], torn down in
 * [finish]/[discard] — the "no background location" product constraint). Each source is
 * independently optional: a missing permission or absent sensor degrades that stream to empty
 * rather than failing the recording (mirrors `HealthConnectImporter`'s per-type resilience). On
 * [start] a [RecordingService] foreground service keeps the capture alive if the user leaves the
 * app; [finish] persists real streams via `FitRepository.addWorkout(workout, track)`.
 *
 * Stream truth is SI/real (`GpsFix` decimal degrees, `HrPoint` bpm); the real repository encodes
 * it to the sealed `WorkoutStreams` blob (§9.7). Route + HR are GDPR Art. 9 / home-revealing —
 * threat-model **A34**; nothing here is relay-visible.
 *
 * ⚠ Sensor/BLE/GPS I/O is Android-framework, unverifiable without a device — **on-device pending**
 * (same posture as `HealthConnectImporter`). The capture wiring compiles against the real SDK.
 */
class AndroidRecordingEngine(
    private val appContext: Context,
    private val repository: FitRepository,
    private val scope: CoroutineScope,
    /**
     * Process foregroundedness. ⚠⚠ Load-bearing for [resume]: location is a **while-in-use**
     * permission, so starting the recorder's `location`-typed foreground service from the
     * background throws `SecurityException` and takes the process down with it — measured, 2026-08-15
     * (§13.5e). Battery exemption does not lift that; only `ACCESS_BACKGROUND_LOCATION` would, and
     * Fit deliberately does not hold it. So a killed session is picked up **when the user is looking
     * at the app**, and the watchdog's job is to tell them it needs picking up.
     */
    private val foreground: StateFlow<Boolean> = MutableStateFlow(true),
) : RecordingEngine {

    private val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val sensors = MutableStateFlow(
        listOf(
            SensorStatus(SensorKind.Gps, SensorState.Searching),
            SensorStatus(SensorKind.HeartRate, SensorState.Searching),
            SensorStatus(SensorKind.StepCounter, SensorState.Searching),
        ),
    )

    private val locationManager by lazy { appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }
    private val sensorManager by lazy { appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    private val bluetoothManager by lazy { appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager }

    // ── capture buffers (reset per recording) ──────────────────────────────────────────
    private val route = mutableListOf<GpsFix>()
    private val hr = mutableListOf<HrPoint>()
    private var activity: ActivityType = ActivityType.Other
    private var startWallMs = 0L
    /** Active-time clock (RC-4): paused spans excluded; drives elapsed + every sample's tSec. */
    private val clock = PauseClock()
    private var distanceM = 0.0
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var latestBpm: Int? = null
    private var stepBaseline: Float? = null
    private var steps = 0
    private var tickJob: Job? = null
    private var hrGatt: BluetoothGatt? = null

    // ── RC-5 in-flight journal (fit-app.md §13.5) ──────────────────────────────────────
    private val journal = FileRecordingJournal(File(appContext.filesDir, RECORDING_JOURNAL_FILE))
    private val pendingJournal = mutableListOf<String>()
    private var ticksSinceFlush = 0

    /** One lane for all journal I/O — clears and flushes execute in submission order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val journalDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        restoreJournaledSession()
    }

    override fun state(): Flow<RecordingState> = state.asStateFlow()
    override fun sensors(): Flow<List<SensorStatus>> = sensors.asStateFlow()

    // ── arming (pre-start screen) ───────────────────────────────────────────────────────

    override fun armSensors() {
        setSensor(SensorKind.Gps, if (hasLocationPermission() && isGpsEnabled()) SensorState.Ready else SensorState.Unavailable)
        setSensor(
            SensorKind.StepCounter,
            if (sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null) SensorState.Ready else SensorState.Unavailable,
        )
        // HR: begin scanning for a strap; the scan callback flips it Ready on connect.
        startHeartRateScan()
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override fun start(activityType: ActivityType) {
        // Idle only: starting over a Reviewing draft would silently clobber the unsaved session.
        if (state.value !is RecordingState.Idle) return
        activity = activityType
        route.clear(); hr.clear()
        distanceM = 0.0; lastLat = null; lastLng = null; latestBpm = null; stepBaseline = null; steps = 0
        startWallMs = System.currentTimeMillis()
        clock.start(SystemClock.elapsedRealtime())
        // A fresh session owns the journal: drop any stale file and buffer the new header (all
        // journal I/O rides the tick flush off the main thread).
        synchronized(pendingJournal) {
            pendingJournal.clear()
            pendingJournal += RecordingJournalCodec.header(activityType, startWallMs)
        }
        ticksSinceFlush = 0
        // On the journal lane so it sequences after any still-queued flush of the previous session.
        scope.launch(journalDispatcher) { runCatching { journal.clear() } }

        state.value = RecordingState.Active(
            activityType = activityType, elapsedSec = 0, distanceM = 0,
            paceSecPerKm = null, heartRateBpm = latestBpm, paused = false, steps = 0,
        )

        armCapture()
        RecordingService.start(appContext)
        RecorderWatchdog.schedule(appContext)
        startTicking()
    }

    /**
     * Registers every sensor stream a live session needs. Shared by [start] and [resume] — a resumed
     * session is a live session, and anything armed in only one of the two paths is a stream that
     * silently stops recording after the OS kills the process.
     */
    @SuppressLint("MissingPermission")
    private fun armCapture() {
        if (hasLocationPermission()) runCatching {
            val provider = locationProvider()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // High-accuracy, ~1 Hz fixes. The legacy (provider, minTime, minDist, listener)
                // overload lets the fused provider run in low-power/balanced mode — sparse
                // (~1 per 10 s), wifi-blended, smoothed positions that badly under-track a real
                // walk. QUALITY_HIGH_ACCURACY forces GPS priority; the 1 s interval gives a dense
                // polyline so the haversine sum matches the actual route.
                val request = LocationRequest.Builder(1000L)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(1000L)
                    .setMinUpdateDistanceMeters(0f)
                    .build()
                locationManager?.requestLocationUpdates(provider, request, appContext.mainExecutor, locationListener)
            } else {
                locationManager?.requestLocationUpdates(provider, 1000L, 0f, locationListener)
            }
        }
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager?.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        startHeartRateScan()
    }

    /** The 1 Hz state pump + journal flush. Cancels any previous loop so a resume cannot leave two. */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(1000)
                val current = state.value as? RecordingState.Active ?: return@launch
                if (!current.paused) state.value = advance(current)
                if (!current.paused && ++ticksSinceFlush >= JOURNAL_FLUSH_TICKS) {
                    ticksSinceFlush = 0
                    flushJournal()
                }
            }
        }
    }

    override fun pause() {
        (state.value as? RecordingState.Active)?.let {
            clock.pause(SystemClock.elapsedRealtime())
            state.value = it.copy(paused = true)
        }
    }

    override fun resume() {
        (state.value as? RecordingState.Active)?.let {
            clock.resume(SystemClock.elapsedRealtime())
            // Re-anchor the distance accumulator: the segment walked while paused is not part
            // of the workout, so the first post-resume fix must not add a phantom jump.
            lastLat = null; lastLng = null
            state.value = it.copy(paused = false)
        }
    }

    override fun stop(): RecordingDraft? {
        val current = state.value as? RecordingState.Active ?: return null
        teardown()
        // Mark the journal stopped *before* the final flush: from here the file describes a draft
        // parked for review, and a relaunch must not mistake it for a session the OS interrupted
        // and resume capture behind the user's back (§13.5e).
        synchronized(pendingJournal) { pendingJournal += RecordingJournalCodec.stopped() }
        RecorderWatchdog.cancel(appContext)
        // Final journal flush so a crash during review still recovers the full session; the
        // file itself lives until commit/discard clears it.
        flushJournal()
        val elapsed = current.elapsedSec
        val zone = TimeZone.currentSystemDefault()
        val start = Instant.fromEpochMilliseconds(startWallMs).toLocalDateTime(zone)
        val end = Instant.fromEpochMilliseconds(startWallMs + elapsed * 1000L).toLocalDateTime(zone)
        val distInt = distanceM.roundToInt().takeIf { it > 0 }
        val avgHr = hr.map { it.bpm }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

        val workout = Workout(
            workoutId = "rec-$startWallMs",
            activityType = activity,
            source = Source.Recorded,
            title = "",
            device = "",
            start = start,
            end = end,
            durationSec = elapsed,
            distanceM = distInt,
            activeEnergyKj = (energyKjPerSec(activity) * elapsed).roundToInt().takeIf { it > 0 },
            avgHr = avgHr,
            maxHr = hr.maxOfOrNull { it.bpm },
            steps = steps.takeIf { it > 0 },
            avgPaceSecPerKm = current.paceSecPerKm,
            hasRoute = route.isNotEmpty(),
            hrSeries = hr.toList(),
        )
        val draft = RecordingDraft(workout, RecordedTrack(route = route.toList(), hr = hr.toList()))
        state.value = RecordingState.Reviewing(draft)
        return draft
    }

    override suspend fun commit(edits: DraftEdits): FinishedRecording? {
        val draft = (state.value as? RecordingState.Reviewing)?.draft ?: return null
        // Trim window + title/activity in one shared step, so what persists is exactly what the
        // review screen previewed.
        val applied = draft.applying(edits)
        // Persist the dense, real streams (route + HR) → Workout.Streams on the store. With the
        // durable outbox bound (fit-android does), an unreachable Control journals instead of
        // throwing — the session still ends; the journal replays later. A throw keeps the draft
        // Reviewing for a retry.
        val outcome = repository.addWorkout(applied.workout, applied.track)
        state.value = RecordingState.Idle
        RecorderWatchdog.cancel(appContext)
        // The saved-or-journalled workout no longer needs crash cover (§9.13's outbox owns the
        // save from here); a leftover file would resurrect it as a duplicate draft.
        scope.launch(journalDispatcher) { runCatching { journal.clear() } }
        return FinishedRecording(applied.workout, outcome)
    }

    override fun discard() {
        teardown()
        RecorderWatchdog.cancel(appContext)
        state.value = RecordingState.Idle
        scope.launch(journalDispatcher) { runCatching { journal.clear() } }
    }

    // ── GPS ─────────────────────────────────────────────────────────────────────────────

    private val locationListener = LocationListener { loc -> onLocation(loc) }

    private fun onLocation(loc: Location) {
        // RC-4: paused means paused — no route point, no distance (HR always had this guard).
        val active = state.value as? RecordingState.Active ?: return
        if (active.paused) return
        // RC-4 accuracy gate: a low-quality fix (classically the first ones, indoors) would both
        // spike the distance sum and smear the route; the track starts at the first accepted fix.
        if (loc.hasAccuracy() && loc.accuracy > ACCURACY_LIMIT_M) return
        val fix = GpsFix(
            tSec = clock.activeSec(SystemClock.elapsedRealtime()),
            lat = loc.latitude,
            lng = loc.longitude,
            altM = if (loc.hasAltitude()) loc.altitude else null,
        )
        route += fix
        synchronized(pendingJournal) { pendingJournal += RecordingJournalCodec.fix(fix) }
        val pLat = lastLat; val pLng = lastLng
        if (pLat != null && pLng != null) distanceM += haversineMeters(pLat, pLng, loc.latitude, loc.longitude)
        lastLat = loc.latitude; lastLng = loc.longitude
    }

    // ── step counter ──────────────────────────────────────────────────────────────────

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val cumulative = event.values.firstOrNull() ?: return
            val base = stepBaseline ?: cumulative.also { stepBaseline = it }
            // RC-4: while paused, steps taken must not count — slide the baseline forward so the
            // total stays frozen and resumes from the paused-time-excluded figure.
            if ((state.value as? RecordingState.Active)?.paused == true) {
                stepBaseline = cumulative - steps
                return
            }
            steps = (cumulative - base).roundToInt().coerceAtLeast(0)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ── BLE heart-rate strap (Heart Rate Service 0x180D / Measurement 0x2A37) ───────────

    @SuppressLint("MissingPermission")
    private fun startHeartRateScan() {
        if (!hasBluetoothPermission()) { setSensor(SensorKind.HeartRate, SensorState.Unavailable); return }
        val scanner = bluetoothManager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: run { setSensor(SensorKind.HeartRate, SensorState.Unavailable); return }
        if (hrGatt != null) return // already connected/connecting
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(this)
            hrGatt = device.connectGatt(appContext, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) setSensor(SensorKind.HeartRate, SensorState.Searching)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT) ?: return
            gatt.setCharacteristicNotification(characteristic, true)
            @Suppress("DEPRECATION")
            characteristic.getDescriptor(CLIENT_CONFIG)?.let { cccd ->
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            setSensor(SensorKind.HeartRate, SensorState.Ready)
        }

        // API 33+ delivers the value directly; older devices hit the deprecated 2-arg overload.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onHeartRate(value)
        }

        @Deprecated("Pre-API-33 callback", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { onHeartRate(it) }
        }
    }

    /** Parse a Heart Rate Measurement (`0x2A37`): flags bit0 = 16-bit vs 8-bit BPM format. */
    private fun onHeartRate(value: ByteArray) {
        if (value.isEmpty()) return
        val flags = value[0].toInt()
        val bpm = if (flags and 0x01 == 0) {
            value.getOrNull(1)?.toInt()?.and(0xFF) ?: return
        } else {
            val lo = value.getOrNull(1)?.toInt()?.and(0xFF) ?: return
            val hi = value.getOrNull(2)?.toInt()?.and(0xFF) ?: return
            (hi shl 8) or lo
        }
        latestBpm = bpm
        val active = state.value as? RecordingState.Active
        if (active != null && !active.paused) {
            val point = HrPoint(t = clock.activeSec(SystemClock.elapsedRealtime()), bpm = bpm)
            hr += point
            synchronized(pendingJournal) { pendingJournal += RecordingJournalCodec.hr(point) }
        }
    }

    // ── shared ──────────────────────────────────────────────────────────────────────────

    private fun advance(current: RecordingState.Active): RecordingState.Active {
        val elapsed = clock.activeSec(SystemClock.elapsedRealtime())
        val dist = distanceM.roundToInt()
        return current.copy(
            elapsedSec = elapsed,
            distanceM = dist,
            paceSecPerKm = if (dist >= 25) (elapsed * 1000L / dist).toInt() else null,
            heartRateBpm = latestBpm,
            steps = steps,
        )
    }

    @SuppressLint("MissingPermission")
    private fun teardown() {
        tickJob?.cancel(); tickJob = null
        runCatching { locationManager?.removeUpdates(locationListener) }
        runCatching { sensorManager?.unregisterListener(stepListener) }
        runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { hrGatt?.close() }; hrGatt = null
        RecordingService.stop(appContext)
    }

    // ── RC-5 journal plumbing ───────────────────────────────────────────────────────────

    /**
     * Drains the buffered events plus a clock tick + step total to disk, off the caller's
     * thread. [journalDispatcher] serialises all journal I/O in submission order, so a stop-time
     * flush can never land after a commit-time clear and resurrect a saved workout.
     */
    private fun flushJournal() {
        val lines = synchronized(pendingJournal) {
            if (pendingJournal.isEmpty() && state.value !is RecordingState.Active) return
            val drained = pendingJournal.toList()
            pendingJournal.clear()
            drained
        } + listOf(
            RecordingJournalCodec.tick(clock.activeSec(SystemClock.elapsedRealtime()), System.currentTimeMillis()),
            RecordingJournalCodec.steps(steps),
        )
        scope.launch(journalDispatcher) { runCatching { journal.append(lines) } }
    }

    /**
     * RC-5 recovery: a leftover journal means the process died mid-capture (or mid-review) — the
     * flushed samples rebuild the session as a `recovered` Reviewing draft, and the ordinary
     * review→save flow (with the RC-3 trim card) takes it from there. `compareAndSet(Idle, …)`
     * closes the tiny race with a user hitting Start before the off-thread read lands.
     */
    private fun restoreJournaledSession() {
        scope.launch(journalDispatcher) {
            val lines = runCatching { journal.readAll() }.getOrNull() ?: return@launch
            val recovered = RecordingJournalCodec.decode(lines) ?: return@launch
            if (recovered.isResumable(System.currentTimeMillis()) && foreground.value) {
                withContext(Dispatchers.Main) { resume(recovered) }
            } else if (recovered.isResumable(System.currentTimeMillis())) {
                // Resumable but we are in the background: leave the journal alone and let the
                // watchdog's notification bring the user in. Re-checked on the next construction.
                RecorderWatchdog.schedule(appContext)
            } else {
                state.compareAndSet(RecordingState.Idle, RecordingState.Reviewing(recovered.toDraft()))
            }
        }
    }

    /**
     * Pick a killed session back up where it stopped (§13.5e) — the capture equivalent of Timeline's
     * `CaptureRearm`, and what the RC-5 journal was always able to support but §13.1 could not risk:
     * an OS-restarted service used to front an engine whose session had died with the process, and
     * now the journal can rebuild that session first.
     *
     * The buffers, the distance accumulator and the active-second clock all resume from the journal,
     * so the dead stretch counts as neither distance nor duration — exactly how a pause behaves. The
     * route simply has a hole in it, which is the truth.
     *
     * ⚠ `compareAndSet(Idle, …)` first: if the user has already started a new workout in this
     * process, that session owns the engine and this one is stale.
     */
    private fun resume(recovered: RecoveredRecording) {
        if (state.value !is RecordingState.Idle) return
        activity = recovered.activityType
        startWallMs = recovered.startWallMs
        route.clear(); route += recovered.route
        hr.clear(); hr += recovered.hr
        steps = recovered.steps
        // The pedometer's cumulative count is not ours to keep across a process death — re-baseline
        // on the next reading so resumed steps accrue on top of what the journal held.
        stepBaseline = null
        distanceM = routeDistanceM(recovered.route)?.toDouble() ?: 0.0
        lastLat = recovered.route.lastOrNull()?.lat
        lastLng = recovered.route.lastOrNull()?.lng
        latestBpm = recovered.hr.lastOrNull()?.bpm
        clock.resumeFrom(SystemClock.elapsedRealtime(), recovered.activeSec)
        ticksSinceFlush = 0
        state.value = RecordingState.Active(
            activityType = activity, elapsedSec = recovered.activeSec, distanceM = distanceM.roundToInt(),
            paceSecPerKm = null, heartRateBpm = latestBpm, paused = false, steps = steps,
        )
        armCapture()
        RecordingService.start(appContext)
        // Keep the chain alive: this resume may itself have come from a watchdog tick, and the
        // phone that killed us once will happily do it again ten minutes later.
        RecorderWatchdog.schedule(appContext)
        startTicking()
    }

    private fun setSensor(kind: SensorKind, s: SensorState) {
        sensors.value = sensors.value.map { if (it.kind == kind) it.copy(state = s) else it }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * The location provider to record from. Prefer the platform **fused** provider
     * ([LocationManager.FUSED_PROVIDER], API 31+) — raw `GPS_PROVIDER` is slow to first fix and
     * routinely starved by OEM power management (observed on device: registered + enabled yet zero
     * callbacks over a whole walk), whereas fused blends GPS + sensors + wifi for prompt, reliable
     * fixes. Fused here is the **platform** provider, not Google Play Services — the de-Googled
     * constraint (§9.8) holds. Falls back to `GPS_PROVIDER` below API 31 or if fused is absent.
     */
    private fun locationProvider(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            locationManager?.allProviders?.contains(LocationManager.FUSED_PROVIDER) == true
        ) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }

    private fun isGpsEnabled(): Boolean =
        runCatching { locationManager?.isProviderEnabled(locationProvider()) == true }.getOrDefault(false)

    private fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // pre-S: BLUETOOTH/BLUETOOTH_ADMIN are normal (manifest) permissions
        }

    /**
     * A coarse active-energy estimate — kilojoules per second by activity, matching
     * [MockRecordingEngine]'s constants. Duration-based (no body-weight or HR input yet), so it is
     * an approximation, but it populates the energy stat the recorder otherwise left blank. A
     * weight/HR-aware model is a later refinement.
     */
    private fun energyKjPerSec(type: ActivityType): Double = when (type) {
        ActivityType.Running -> 0.9
        ActivityType.Walking -> 0.3
        ActivityType.Cycling -> 0.65
        ActivityType.Strength -> 0.4
        ActivityType.Other -> 0.35
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object {
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** RC-4: fixes worse than this are dropped (first-fix spikes, indoor smear). */
        const val ACCURACY_LIMIT_M = 50f

        /** RC-5: journal flush cadence in 1 s ticks — the crash-loss window. */
        const val JOURNAL_FLUSH_TICKS = 15
    }
}

/**
 * The RC-5 journal file (fit-app.md §13.5): plain append-only lines in app-private storage,
 * same custody posture as the §9.13 outbox (threat-model A34 — local-only, never a wire
 * format, deleted at commit/discard).
 */
internal class FileRecordingJournal(private val file: File) {

    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        file.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    /** Null when no journal exists — the common case at every healthy launch. */
    fun readAll(): List<String>? = if (file.exists()) file.readLines() else null

    fun clear() {
        file.delete()
    }
}
