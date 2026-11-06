package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pure, device-free coverage of **S1a** (`docs/proposals/timeline-movement-capture.md` §17.2): the
 * pace → request table, the below-API-31 batching clamp, and the mapping from the segmenter's own
 * events to a pace.
 *
 * The Android half (re-registering `requestLocationUpdates`, arming significant motion) is framework
 * code and is *not* covered here — see the PR's NOT-verified list.
 */
class CapturePacePolicyTest {

    private val modernApi = CapturePacePolicy.BATCHING_MIN_API
    private val legacyApi = CapturePacePolicy.BATCHING_MIN_API - 1

    @Test
    fun theV1Table() {
        assertEquals(
            LocationRequestSpec(5 * 60_000L, 15 * 60_000L),
            CapturePacePolicy.requestFor(CapturePace.STATIONARY, modernApi),
        )
        assertEquals(
            LocationRequestSpec(30_000L, 2 * 60_000L),
            CapturePacePolicy.requestFor(CapturePace.ON_FOOT, modernApi),
        )
        assertEquals(
            LocationRequestSpec(10_000L, 5 * 60_000L),
            CapturePacePolicy.requestFor(CapturePace.VEHICLE, modernApi),
        )
    }

    @Test
    fun stationaryStillRequestsLocation() {
        // ⚠⚠ §18.6: dwell *departure* is detected by a fix landing outside the anchor radius, so a
        // stationary pace that requested nothing would stop noticing the user had left. If this
        // assertion is ever "optimised" away, move departure detection off the fix stream first.
        val spec = CapturePacePolicy.requestFor(CapturePace.STATIONARY, modernApi)
        assertTrue(spec.intervalMs > 0, "stationary must keep a slow request, not drop to none")
        assertNotEquals(Long.MAX_VALUE, spec.intervalMs)
    }

    @Test
    fun everyPaceIsCheaperInWakeupsThanItsRawInterval() {
        // The batch window is what buys the AP wakeups back; without it the aggressive intervals are
        // a pure cost. Each pace must therefore batch at a multiple of its own interval.
        for (pace in CapturePace.entries) {
            val spec = CapturePacePolicy.requestFor(pace, modernApi)
            assertTrue(
                spec.maxUpdateDelayMs >= 2 * spec.intervalMs,
                "$pace batches only ${spec.maxUpdateDelayMs} ms against a ${spec.intervalMs} ms interval",
            )
        }
    }

    @Test
    fun belowApi31TheAggressiveIntervalIsClamped() {
        // ⚠⚠ A 10 s interval on a device that cannot batch is strictly worse than today's 60 s: the
        // same GNSS duty cycle plus six times the wakeups.
        val vehicle = CapturePacePolicy.requestFor(CapturePace.VEHICLE, legacyApi)
        assertEquals(CapturePacePolicy.UNBATCHED_MIN_INTERVAL_MS, vehicle.intervalMs)
        assertEquals(0L, vehicle.maxUpdateDelayMs, "an unhonoured batch window must not be requested")

        // Twin, and the precondition for the claim above: at API 31 the same pace is NOT clamped, so
        // the clamp is really keying off the API level and not off something incidental.
        assertEquals(10_000L, CapturePacePolicy.requestFor(CapturePace.VEHICLE, modernApi).intervalMs)
    }

    @Test
    fun theClampNeverMakesAPaceSampleFasterOrSlowerThanItsOwnRate() {
        for (pace in CapturePace.entries) {
            val modern = CapturePacePolicy.requestFor(pace, modernApi)
            val legacy = CapturePacePolicy.requestFor(pace, legacyApi)
            assertTrue(legacy.intervalMs >= modern.intervalMs, "$pace: the clamp only ever slows down")
            assertTrue(
                legacy.intervalMs >= CapturePacePolicy.UNBATCHED_MIN_INTERVAL_MS,
                "$pace: nothing may sample faster than the unbatched floor",
            )
        }
        // The already-slow paces are untouched by it.
        assertEquals(
            CapturePacePolicy.requestFor(CapturePace.STATIONARY, modernApi).intervalMs,
            CapturePacePolicy.requestFor(CapturePace.STATIONARY, legacyApi).intervalMs,
        )
    }

    // ---- event → pace --------------------------------------------------------------------------

    private fun fix(minute: Int) = CapturedFix(525_000_000L, 134_000_000L, minute * 60_000L)

    private fun visit() = CapturedVisit(525_000_000L, 134_000_000L, 0L, 300_000L)

    private fun move(mode: String) = CapturedMove(
        fixes = listOf(fix(0), fix(1), fix(2)),
        startMs = 0L,
        endMs = 120_000L,
        distanceM = 900.0,
        classification = ModeClassification(mode, confidence = 70, medianSpeedMmPerS = 9_000),
    )

    @Test
    fun aConfirmedDwellIsStationaryAndADepartureIsNot() {
        assertEquals(
            CapturePace.STATIONARY,
            CapturePacePolicy.paceFor(CaptureEvent.Dwell(visit(), ongoing = true)),
        )
        assertEquals(
            CapturePace.ON_FOOT,
            CapturePacePolicy.paceFor(CaptureEvent.Dwell(visit(), ongoing = false)),
        )
    }

    @Test
    fun theLegsModeChoosesBetweenTheTwoMovingPaces() {
        val onFoot = listOf(MovementMode.WALK, MovementMode.RUN)
        val vehicle =
            listOf(MovementMode.CYCLE, MovementMode.DRIVE, MovementMode.TRANSIT, MovementMode.FLIGHT)
        for (m in onFoot) {
            assertEquals(CapturePace.ON_FOOT, CapturePacePolicy.paceFor(CaptureEvent.Move(move(m))), m)
        }
        for (m in vehicle) {
            assertEquals(CapturePace.VEHICLE, CapturePacePolicy.paceFor(CaptureEvent.Move(move(m))), m)
        }
        // ⚠ An unclassifiable leg is not evidence for spending the vehicle duty cycle — and neither
        // is a mode a newer peer invented that this build has never heard of.
        assertEquals(
            CapturePace.ON_FOOT,
            CapturePacePolicy.paceFor(CaptureEvent.Move(move(MovementMode.UNKNOWN))),
        )
        assertEquals(
            CapturePace.ON_FOOT,
            CapturePacePolicy.paceFor(CaptureEvent.Move(move("hyperloop"))),
        )
    }

    @Test
    fun anOpenLegPacesOnItsRawBucketNotItsNameableMode() {
        // A low-confidence in-flight drive: OngoingMove.provisionalMode reads UNKNOWN (it gates a
        // *label*), but the pace must already be VEHICLE — that early stretch is exactly the geometry
        // S1 exists to capture, and it cannot be recovered later.
        val open = OngoingMove(
            startMs = 0L,
            lastFixMs = 120_000L,
            distanceM = 900.0,
            classification = ModeClassification(MovementMode.DRIVE, confidence = 5, medianSpeedMmPerS = 20_000),
        )
        assertEquals(MovementMode.UNKNOWN, open.provisionalMode)
        assertEquals(CapturePace.VEHICLE, CapturePacePolicy.paceFor(CaptureEvent.MoveProgress(open)))
    }
}
