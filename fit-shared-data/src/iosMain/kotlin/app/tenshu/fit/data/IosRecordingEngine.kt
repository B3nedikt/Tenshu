@file:OptIn(ExperimentalForeignApi::class)

package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.GpsFix
import app.tenshu.fit.model.HrPoint
import app.tenshu.fit.model.RecordedTrack
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreMotion.CMPedometer
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

/**
 * The **first-party iOS recorder** (`docs/proposals/fit-app.md` §11.1, R-iOS-1) — the
 * [RecordingEngine] actual over Apple's own sensor frameworks, the platform twin of
 * [app.tenshu.fit.android.recorder.AndroidRecordingEngine] and the same de-Googled three-source
 * model:
 *
 *  - **GPS** via [CLLocationManager] (`CoreLocation`) — real fixes → the [RecordedTrack] route + a
 *    haversine distance accumulator (the exact form the Android engine uses).
 *  - **Steps** via [CMPedometer] (`CoreMotion`) — the on-device motion coprocessor, the iOS twin of
 *    Android's `TYPE_STEP_COUNTER`.
 *  - **Heart rate** via a **BLE strap** ([CBCentralManager]/`CoreBluetooth`) — GATT Heart Rate
 *    Service (`0x180D`) / Measurement characteristic (`0x2A37`), the **same** interoperable
 *    chest-strap profile Android speaks (the flags-bit0 8/16-bit parse ports verbatim). BLE, not
 *    HealthKit, keeps parity with Android and avoids the watchOS-only `HKLiveWorkoutBuilder`.
 *
 * Location is acquired **only while a workout is active** ([start] → [teardown]) — the "no
 * background location" product constraint. Each source is independently optional: a missing
 * authorization or absent sensor degrades that stream to empty rather than failing the recording
 * (mirrors [HealthKitImporter]'s per-type resilience). On [finish] it builds the summary [Workout]
 * + a [RecordedTrack] and persists real streams via `FitRepository.addWorkout(workout, track)` —
 * the real repository encodes them onto the sealed `Workout.Streams` blob (§9.7).
 *
 * ⚠ Route + HR are GDPR Art. 9 / home-revealing — threat-model **A34**; nothing here is
 * relay-visible.
 *
 * ⚠ **Sensor/BLE/GPS I/O is Apple-framework, unverifiable without a device — on-device pending**
 * (R-iOS-4; the same posture as [HealthKitImporter] and the Android engine). Constructing the
 * engine is side-effect-free (the managers are lazy); a CLLocationManager / CBCentralManager is
 * created only when [armSensors]/[start] runs, so assembly and the reactive flows are safe in a
 * host without the usage-description plist. Background capture (`allowsBackgroundLocationUpdates`)
 * and the just-in-time authorization UX are R-iOS-2.
 */
class IosRecordingEngine(
    private val repository: FitRepository,
    // CoreLocation / CoreBluetooth managers and their delegate callbacks want a run loop; the
    // SwiftUI Record flow drives this on the main queue. Main-confined state also sidesteps a
    // capture-buffer data race between the delegates and the tick loop.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    // R-iOS-6: the Apple Health courtesy export. Constructing it is side-effect-free (its store is
    // lazy), preserving the sim-safe assembly property.
    private val hkWorkoutWriter: IosHealthKitWorkoutWriter = IosHealthKitWorkoutWriter(),
) : RecordingEngine {

    private val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val sensors = MutableStateFlow(
        listOf(
            SensorStatus(SensorKind.Gps, SensorState.Searching),
            SensorStatus(SensorKind.HeartRate, SensorState.Searching),
            SensorStatus(SensorKind.StepCounter, SensorState.Searching),
        ),
    )

    // ── managers (lazy: constructing the engine must not prompt for permission) ──────────
    private val locationManager: CLLocationManager by lazy {
        CLLocationManager().apply {
            delegate = locationDelegate
            desiredAccuracy = kCLLocationAccuracyBest
        }
    }
    private val pedometer: CMPedometer by lazy { CMPedometer() }

    // The central is created **only** when the user opts into a strap ([connectHeartRate]) —
    // constructing a CBCentralManager triggers the Bluetooth permission prompt, so it must never
    // fire on entering the Record tab (§11.2). Nullable (not `by lazy`) so [start] can tell whether
    // HR was opted in and [teardown] can skip an untouched radio.
    private var centralManager: CBCentralManager? = null

    // ── capture buffers (reset per recording) ────────────────────────────────────────────
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
    private var steps = 0
    /** RC-4: pedometer total accrued while paused, excluded from [steps]. */
    private var stepOffset = 0
    private var tickJob: Job? = null
    private var hrPeripheral: CBPeripheral? = null

    // ── RC-5 in-flight journal (fit-app.md §13.5, iOS twin) ──────────────────────────────
    private val journal = IosRecordingJournal()
    private var ticksSinceFlush = 0

    /** One lane for all journal I/O — clears and snapshot writes execute in submission order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val journalLane = Dispatchers.Default.limitedParallelism(1)

    init {
        restoreJournaledSession()
    }

    override fun state(): Flow<RecordingState> = state.asStateFlow()
    override fun sensors(): Flow<List<SensorStatus>> = sensors.asStateFlow()

    // ── arming (pre-start screen) ─────────────────────────────────────────────────────────

    override fun armSensors() {
        // Reflect current authorization only — DO NOT prompt here. Entering the Record tab must not
        // cold-request Location or Bluetooth (§11.2); the just-in-time requests happen at [start]
        // (location + motion) and on the HR "Connect" tap ([connectHeartRate], Bluetooth). Creating
        // the CLLocationManager and reading `authorizationStatus` are silent — only
        // `requestWhenInUseAuthorization` prompts.
        updateGpsSensorState()
        setSensor(SensorKind.StepCounter, if (CMPedometer.isStepCountingAvailable()) SensorState.Ready else SensorState.Unavailable)
    }

    override fun connectHeartRate() {
        // The Bluetooth prompt fires here (constructing the central), behind the explicit Connect
        // tap. Once powered on, `centralManagerDidUpdateState` begins the scan.
        if (centralManager == null) centralManager = CBCentralManager(centralDelegate, null)
        else startHeartRateScan()
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────────

    override fun start(activityType: ActivityType) {
        // Idle only: starting over a Reviewing draft would silently clobber the unsaved session.
        if (state.value !is RecordingState.Idle) return
        activity = activityType
        route.clear(); hr.clear()
        distanceM = 0.0; lastLat = null; lastLng = null; latestBpm = null; steps = 0; stepOffset = 0
        startWallMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        clock.start(uptimeMs())
        ticksSinceFlush = 0
        // On the journal lane so it sequences after any still-queued flush of a previous session.
        scope.launch(journalLane) { runCatching { journal.clear() } }

        state.value = RecordingState.Active(
            activityType = activityType, elapsedSec = 0, distanceM = 0,
            paceSecPerKm = null, heartRateBpm = latestBpm, paused = false, steps = 0,
        )

        // The just-in-time Location request (after the Swift rationale, §11.2). Background updates
        // keep GPS alive if the user leaves the app mid-workout — requires UIBackgroundModes:location
        // in the Info.plist (R-iOS-2); setting it true without that mode would throw, but a recording
        // only ever starts from the Fit app, whose plist declares it.
        locationManager.requestWhenInUseAuthorization()
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.startUpdatingLocation()
        if (CMPedometer.isStepCountingAvailable()) {
            // Starting pedometer updates triggers the Motion & Fitness prompt (bundled with location
            // as the "Motion" capability, matching Android's §9.11 grouping).
            pedometer.startPedometerUpdatesFromDate(NSDate()) { data, _ ->
                data?.let {
                    val cumulative = it.numberOfSteps.intValue
                    // RC-4: while paused, steps must not count — grow the offset so the total
                    // stays frozen and resumes from the paused-time-excluded figure.
                    if ((state.value as? RecordingState.Active)?.paused == true) {
                        stepOffset = cumulative - steps
                    } else {
                        steps = (cumulative - stepOffset).coerceAtLeast(0)
                    }
                }
            }
        }
        startHeartRateScan() // no-op unless a strap was connected pre-start (connectHeartRate)

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
            clock.pause(uptimeMs())
            state.value = it.copy(paused = true)
        }
    }

    override fun resume() {
        (state.value as? RecordingState.Active)?.let {
            clock.resume(uptimeMs())
            // Re-anchor the distance accumulator: the segment moved while paused is not part of
            // the workout, so the first post-resume fix must not add a phantom jump.
            lastLat = null; lastLng = null
            state.value = it.copy(paused = false)
        }
    }

    override fun stop(): RecordingDraft? {
        val current = state.value as? RecordingState.Active ?: return null
        teardown()
        // Final journal snapshot so a crash during review still recovers the full session; the
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
        val workout = applied.workout
        // Persist the dense, real streams (route + HR) → Workout.Streams. With a durable outbox
        // bound (R-iOS-3) an unreachable Control journals instead of throwing; until then the
        // no-journal repository throws and the engine keeps the draft for a retry.
        val outcome = repository.addWorkout(workout, applied.track)
        // R-iOS-6: courtesy-export to Apple Health — fire-and-forget so the UI's navigation to the
        // summary isn't blocked on the HealthKit write (best-effort, consent-gated inside). The
        // wall window follows the (possibly trimmed) start: a leading trim shifts it forward.
        val exportStartMs = startWallMs + (edits.trimStartSec ?: 0) * 1000L
        val exportEndMs = exportStartMs + workout.durationSec * 1000L
        scope.launch { runCatching { hkWorkoutWriter.export(workout, exportStartMs, exportEndMs) } }
        state.value = RecordingState.Idle
        // The saved-or-journalled workout no longer needs crash cover (the §11.3 outbox owns the
        // save from here); a leftover file would resurrect it as a duplicate draft.
        scope.launch(journalLane) { runCatching { journal.clear() } }
        return FinishedRecording(workout, outcome)
    }

    override fun discard() {
        teardown()
        state.value = RecordingState.Idle
        scope.launch(journalLane) { runCatching { journal.clear() } }
    }

    // ── GPS ───────────────────────────────────────────────────────────────────────────────

    private val locationDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            didUpdateLocations.filterIsInstance<CLLocation>().forEach { onLocation(it) }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            updateGpsSensorState()
        }
    }

    private fun onLocation(loc: CLLocation) {
        // RC-4: paused means paused — no route point, no distance (HR always had this guard).
        val active = state.value as? RecordingState.Active ?: return
        if (active.paused) return
        // RC-4 accuracy gate: horizontalAccuracy < 0 is an invalid fix; a low-quality one
        // (classically the first fixes) would spike the distance sum and smear the route.
        if (loc.horizontalAccuracy < 0 || loc.horizontalAccuracy > ACCURACY_LIMIT_M) return
        val tSec = clock.activeSec(uptimeMs())
        loc.coordinate.useContents {
            val lat = latitude
            val lng = longitude
            // verticalAccuracy < 0 signals an invalid altitude fix.
            val altM = if (loc.verticalAccuracy >= 0) loc.altitude else null
            route += GpsFix(tSec = tSec, lat = lat, lng = lng, altM = altM)
            val pLat = lastLat; val pLng = lastLng
            if (pLat != null && pLng != null) distanceM += haversineMeters(pLat, pLng, lat, lng)
            lastLat = lat; lastLng = lng
        }
    }

    private fun updateGpsSensorState() {
        val status = locationManager.authorizationStatus
        val ready = status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways
        setSensor(SensorKind.Gps, if (ready) SensorState.Ready else SensorState.Searching)
    }

    // ── BLE heart-rate strap (Heart Rate Service 0x180D / Measurement 0x2A37) ──────────────

    private fun startHeartRateScan() {
        val c = centralManager ?: return // HR not opted into (no Connect tap)
        if (c.state != CBManagerStatePoweredOn) return // scan begins from didUpdateState
        if (hrPeripheral != null) return // already connected/connecting
        c.scanForPeripheralsWithServices(listOf(HEART_RATE_SERVICE), null)
    }

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) startHeartRateScan()
            else setSensor(SensorKind.HeartRate, SensorState.Unavailable)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            central.stopScan()
            hrPeripheral = didDiscoverPeripheral
            didDiscoverPeripheral.delegate = peripheralDelegate
            central.connectPeripheral(didDiscoverPeripheral, null)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.discoverServices(listOf(HEART_RATE_SERVICE))
        }

        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            setSensor(SensorKind.HeartRate, SensorState.Searching)
        }
    }

    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            peripheral.services?.filterIsInstance<CBService>()?.forEach { svc ->
                peripheral.discoverCharacteristics(listOf(HEART_RATE_MEASUREMENT), svc)
            }
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            didDiscoverCharacteristicsForService.characteristics?.filterIsInstance<CBCharacteristic>()?.forEach { ch ->
                peripheral.setNotifyValue(true, ch)
            }
            setSensor(SensorKind.HeartRate, SensorState.Ready)
        }

        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            didUpdateValueForCharacteristic.value?.let { onHeartRate(it.toByteArray()) }
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
            hr += HrPoint(t = clock.activeSec(uptimeMs()), bpm = bpm)
        }
    }

    // ── shared ──────────────────────────────────────────────────────────────────────────────

    private fun advance(current: RecordingState.Active): RecordingState.Active {
        val elapsed = clock.activeSec(uptimeMs())
        val dist = distanceM.roundToInt()
        return current.copy(
            elapsedSec = elapsed,
            distanceM = dist,
            paceSecPerKm = if (dist >= 25) (elapsed * 1000L / dist).toInt() else null,
            heartRateBpm = latestBpm,
            steps = steps,
        )
    }

    private fun teardown() {
        tickJob?.cancel(); tickJob = null
        locationManager.allowsBackgroundLocationUpdates = false // safe unconditionally (only true throws)
        locationManager.stopUpdatingLocation()
        pedometer.stopPedometerUpdates()
        centralManager?.let { c ->
            c.stopScan()
            hrPeripheral?.let { c.cancelPeripheralConnection(it) }
        }
        hrPeripheral = null
    }

    private fun setSensor(kind: SensorKind, s: SensorState) {
        sensors.value = sensors.value.map { if (it.kind == kind) it.copy(state = s) else it }
    }

    /**
     * A coarse active-energy estimate — kilojoules per second by activity, matching
     * [app.tenshu.fit.android.recorder.AndroidRecordingEngine]'s constants. Duration-based (no
     * body-weight or HR input yet), an approximation that at least populates the energy stat. A
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
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    /** The monotonic now the [PauseClock] runs on (survives wall-clock adjustments). */
    private fun uptimeMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()

    // ── RC-5 journal plumbing ──────────────────────────────────────────────────────────────

    /**
     * Snapshots the whole session (the engine is main-confined, so the buffers are coherent
     * here) and atomically replaces the journal file off-thread — see [IosRecordingJournal] for
     * why iOS rewrites rather than appends.
     */
    private fun flushJournal() {
        val lines = buildList {
            add(RecordingJournalCodec.header(activity, startWallMs))
            route.forEach { add(RecordingJournalCodec.fix(it)) }
            hr.forEach { add(RecordingJournalCodec.hr(it)) }
            add(RecordingJournalCodec.steps(steps))
            // Parked-for-review marker: iOS does not resume a killed session today, but the journal
            // format is shared and a file without this line means "the process died mid-capture".
            if (state.value is RecordingState.Reviewing) add(RecordingJournalCodec.stopped())
            add(
                RecordingJournalCodec.tick(
                    clock.activeSec(uptimeMs()),
                    (NSDate().timeIntervalSince1970 * 1000).toLong(),
                ),
            )
        }
        scope.launch(journalLane) { runCatching { journal.write(lines) } }
    }

    /**
     * RC-5 recovery: a leftover journal means the process died mid-capture (or mid-review) — the
     * snapshot rebuilds the session as a `recovered` Reviewing draft; the SwiftUI review surface
     * takes it from there. `compareAndSet(Idle, …)` closes the tiny race with a user hitting
     * Start before the off-thread read lands.
     */
    private fun restoreJournaledSession() {
        scope.launch(journalLane) {
            val lines = runCatching { journal.readAll() }.getOrNull() ?: return@launch
            val recovered = RecordingJournalCodec.decode(lines) ?: return@launch
            state.compareAndSet(RecordingState.Idle, RecordingState.Reviewing(recovered.toDraft()))
        }
    }

    /** Copies an [NSData] payload into a [ByteArray] (the HR characteristic value). */
    private fun NSData.toByteArray(): ByteArray {
        val len = length.toInt()
        if (len == 0) return ByteArray(0)
        val out = ByteArray(len)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
        return out
    }

    private companion object {
        val HEART_RATE_SERVICE: CBUUID = CBUUID.UUIDWithString("180D")
        val HEART_RATE_MEASUREMENT: CBUUID = CBUUID.UUIDWithString("2A37")

        /** RC-4: fixes worse than this are dropped (first-fix spikes, indoor smear). */
        const val ACCURACY_LIMIT_M = 50.0

        /** RC-5: journal snapshot cadence in 1 s ticks — the crash-loss window. */
        const val JOURNAL_FLUSH_TICKS = 15
    }
}
