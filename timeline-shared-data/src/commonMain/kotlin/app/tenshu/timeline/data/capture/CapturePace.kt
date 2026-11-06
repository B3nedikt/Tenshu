package app.tenshu.timeline.data.capture

/**
 * What the capture loop currently believes the user is doing — the **one input** to how often we ask
 * the platform for a location (S1, `docs/proposals/timeline-movement-capture.md` §17.2).
 *
 * Today's request is one shape for all of life: 60 s, held open continuously for as long as capture
 * is on. That is simultaneously **too coarse while moving** (at 120 km/h one fix per minute is a
 * point every 2 km, so the drawn line is a ~50-point chord polygon across an 80 km route and §4's
 * 15 m Douglas–Peucker epsilon is inert) and **far too expensive while still** (most of a day is
 * stationary and carries no information at all).
 */
enum class CapturePace {
    /** A dwell has been confirmed — the user is somewhere, not going anywhere. */
    STATIONARY,

    /** Walking or running, or we do not know yet. Curvature is not critical at these speeds. */
    ON_FOOT,

    /** Cycling, driving, rail, flight — where the drawn line lives, and where geometry is won. */
    VEHICLE,
}

/**
 * A platform-neutral location-request shape. [maxUpdateDelayMs] is the **batch window**: how long the
 * platform may hold delivered fixes before waking us with them.
 *
 * ⚠⚠ [maxUpdateDelayMs] and [intervalMs] buy *different* things and conflating them is how this gets
 * built wrong (§17.2): the interval buys geometry and costs GNSS duty cycle; batching buys
 * application-processor wakeups and costs **nothing in geometry** (the fixes are still sampled at the
 * interval, just handed over in groups). `0` means "no batching" — deliver each fix as it lands.
 */
data class LocationRequestSpec(
    val intervalMs: Long,
    val maxUpdateDelayMs: Long,
)

/**
 * The **pure** half of S1: [CapturePace] → [LocationRequestSpec], and [CaptureEvent] → the pace it
 * implies. No Android types, so the whole policy is unit-testable with no device.
 *
 * ⚠⚠ **Every interval here is reasoned, not fitted, and awaits an on-device wakeup measurement.**
 * §17.2 is explicit that batching is honoured only where the platform *and the HAL* support it, so
 * the real cadence a device delivers cannot be assumed from the API being present — it has to be
 * measured. Until it is, treat this table as a starting point, not as a calibrated result.
 */
object CapturePacePolicy {

    /**
     * Framework location batching (`LocationRequest.setMaxUpdateDelayMillis`) lands at **API 31**.
     * Below it, [UNBATCHED_MIN_INTERVAL_MS] applies.
     */
    const val BATCHING_MIN_API: Int = 31

    /**
     * ⚠⚠ **A 10 s interval on a device that cannot batch is strictly worse than today's 60 s** — the
     * same GNSS duty cycle *plus* six times the application-processor wakeups, for a geometry gain
     * nobody asked to pay that for. So below [BATCHING_MIN_API] the interval is clamped here rather
     * than at the call site: the clamp is part of the policy, and therefore part of what the tests
     * pin.
     *
     * 30 s (not today's 60 s) because half the interval is still a real geometry win at walking pace
     * and only twice the wakeups; the aggressive vehicle rate is what the clamp exists to refuse.
     */
    const val UNBATCHED_MIN_INTERVAL_MS: Long = 30_000L

    private val STATIONARY = LocationRequestSpec(intervalMs = 5 * 60_000L, maxUpdateDelayMs = 15 * 60_000L)
    private val ON_FOOT = LocationRequestSpec(intervalMs = 30_000L, maxUpdateDelayMs = 2 * 60_000L)
    private val VEHICLE = LocationRequestSpec(intervalMs = 10_000L, maxUpdateDelayMs = 5 * 60_000L)

    /**
     * The request to hold while at [pace], on a device at [apiLevel].
     *
     * ⚠⚠ **[CapturePace.STATIONARY] is deliberately NOT "stop requesting".** §17.2's table proposed
     * dropping to no location request at all while dwelling, and §18.6 corrected it: **dwell *exit*
     * is currently detected by a fix landing outside the anchor radius**, so a device that stops
     * requesting location would also stop noticing that the user left — the stay would run until
     * something else happened to close it. v1 therefore keeps a slow request (the departure detector
     * still works, at one twelfth of today's wakeups) and adds significant motion as a **fast wake**
     * on top. Do not "optimise" this to zero without first moving departure detection off the fix
     * stream.
     */
    fun requestFor(pace: CapturePace, apiLevel: Int): LocationRequestSpec {
        val base = when (pace) {
            CapturePace.STATIONARY -> STATIONARY
            CapturePace.ON_FOOT -> ON_FOOT
            CapturePace.VEHICLE -> VEHICLE
        }
        if (apiLevel >= BATCHING_MIN_API) return base
        return LocationRequestSpec(
            intervalMs = maxOf(base.intervalMs, UNBATCHED_MIN_INTERVAL_MS),
            // Not merely ignored: asking for a batch window the platform will not honour would make
            // the request describe a cadence the device never delivers, which is exactly the
            // assumption §17.2 warns against.
            maxUpdateDelayMs = 0L,
        )
    }

    /**
     * The pace an emitted [CaptureEvent] implies, or `null` when it implies nothing new.
     *
     * The signal is taken from **the segmenter's own output**, not from a second opinion: the thing
     * that decides a dwell has been confirmed is the same thing that decides how fast to sample, so
     * the two can never disagree about what the user is doing.
     *
     * - [CaptureEvent.Dwell] `ongoing = true` — a stay just crossed `minDwellMs` while the user is
     *   still there ⇒ [CapturePace.STATIONARY].
     * - [CaptureEvent.Dwell] `ongoing = false` — they have left ⇒ [CapturePace.ON_FOOT], the
     *   mid-range default. We do not yet know *how* they are travelling; the first
     *   [CaptureEvent.MoveProgress] with a vehicle-ish verdict promotes it.
     * - [CaptureEvent.Move] / [CaptureEvent.MoveProgress] — from the leg's own classified mode.
     *
     * ⚠ [MovementMode.UNKNOWN] maps to [CapturePace.ON_FOOT], the cheaper of the two moving paces:
     * an unclassifiable leg is not evidence for spending the vehicle duty cycle.
     */
    fun paceFor(event: CaptureEvent): CapturePace = when (event) {
        is CaptureEvent.Dwell -> if (event.ongoing) CapturePace.STATIONARY else CapturePace.ON_FOOT
        is CaptureEvent.Move -> paceForMode(event.move.mode)
        // ⚠ The raw bucket, deliberately, not OngoingMove.provisionalMode. That property gates on
        // confidence because it feeds a *label the user reads as fact*; this feeds a sampling rate,
        // where the two errors are not symmetric — a wrong VEHICLE costs some battery for a few
        // minutes, a wrong ON_FOOT costs the geometry of those minutes permanently, and geometry is
        // the entire point of S1. provisionalMode reads UNKNOWN for roughly the first ten legs, i.e.
        // exactly the start of a journey we most want sampled properly.
        is CaptureEvent.MoveProgress -> paceForMode(event.open.classification.mode)
    }

    private fun paceForMode(mode: String): CapturePace = when (mode) {
        MovementMode.CYCLE,
        MovementMode.DRIVE,
        MovementMode.TRANSIT,
        MovementMode.FLIGHT,
        -> CapturePace.VEHICLE
        // WALK, RUN, UNKNOWN and anything a newer peer invented.
        else -> CapturePace.ON_FOOT
    }
}
