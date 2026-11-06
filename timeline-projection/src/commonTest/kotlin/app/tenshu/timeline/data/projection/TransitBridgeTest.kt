package app.tenshu.timeline.data.projection

import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED
import app.tenshu.timeline.data.projection.TimelineProjection.MomentInput
import app.tenshu.timeline.data.projection.TimelineProjection.PlaceInput
import app.tenshu.timeline.data.projection.TimelineProjection.TraceInput
import app.tenshu.timeline.model.MovementModes
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TR-3 — **two `transit` legs may bridge a `drive` leg between them**
 * (`timeline-movement-capture.md` §38.4, shape 3).
 *
 * ### What this closes
 *
 * Reassembly required *N* independent classifications to **agree**: `ModeCoalescer` joins runs only
 * on equal modes, and §33.6's shape 2 folds only same-mode legs. So one leg of a journey landing on
 * `drive` — a short stretch where `TransitEvidence.usableLegCount` fell under `MIN_TRANSIT_LEGS`, or
 * one the sweep has not reached — split the whole journey. Measured on the 2026-09-01 Mühldorf ride:
 * `transit 30.0 km`, `drive 8.7 km`, `transit 21.8 km`, contiguous to the millisecond, three rows.
 *
 * ⚠⚠ **The weight of this suite is on what must NOT fold**, because a wrong fold *deletes a row the
 * user travelled*. `aMapRefusalIsNotOverridden` and `aLongDriveBetweenTwoShortHopsIsNotSwallowed` are
 * the two that matter most; the first protects the tier order, the second the evidence balance.
 */
class TransitBridgeTest {

    private val dayStart = 1_799_971_200_000L
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val home = PlaceInput("p-home", "Home", latE7 = 481_500_000, lngE7 = 115_860_000)
    private val work = PlaceInput("p-work", "Work", latE7 = 482_000_000, lngE7 = 115_860_000)

    private fun visit(id: String, atMs: Long, durSec: Long, place: PlaceInput) = MomentInput(
        id, atMs, TimelineProjection.Kind.VISIT, place.label, durSec,
        subjectKind = TimelineProjection.Subject.PLACE, subjectId = place.id,
    )

    private fun leg(id: String, atMs: Long, durationMs: Long) = MomentInput(
        id, atMs, TimelineProjection.Kind.MOVEMENT, "Transit · 20.0 km", durationMs / 1000,
        subjectKind = TimelineProjection.Subject.TRACE, subjectId = "t-$id",
    )

    private fun projected(moments: List<MomentInput>, traces: List<TraceInput>) =
        TimelineProjection.days(
            moments, places = listOf(home, work), traces = traces, zone = TimeZone.UTC,
        ).flatMap { it.moments }

    /**
     * The measured shape: three contiguous legs, `transit` / `drive` / `transit`, no stop anywhere
     * between them. [middleMode], [middleVersion] and [middleDistanceM] are what each test varies.
     */
    private fun journey(
        middleMode: String = MovementModes.DRIVE,
        middleVersion: Int = MOVEMENT_CLASSIFIER_VERSION,
        middleDistanceM: Long = 8_720,
        middleGapMs: Long = 0,
    ): Pair<List<MomentInput>, List<TraceInput>> {
        val aStart = dayStart + hour
        val aEnd = aStart + 28 * minute
        val bStart = aEnd + middleGapMs
        val bEnd = bStart + 12 * minute
        val cStart = bEnd + middleGapMs
        val cEnd = cStart + 14 * minute
        return listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("a", aStart, aEnd - aStart),
            leg("b", bStart, bEnd - bStart),
            leg("c", cStart, cEnd - cStart),
            visit("v-work", cEnd, 3_600, work),
        ) to listOf(
            TraceInput("t-a", MovementModes.TRANSIT, 30_040, classifierVersion = MOVEMENT_CLASSIFIER_VERSION),
            TraceInput("t-b", middleMode, middleDistanceM, classifierVersion = middleVersion),
            TraceInput("t-c", MovementModes.TRANSIT, 21_800, classifierVersion = MOVEMENT_CLASSIFIER_VERSION),
        )
    }

    // ── the case TR-3 shipped for ────────────────────────────────────────────

    @Test
    fun aDriveBetweenTwoTransitLegsIsOneRide() {
        val (moments, traces) = journey()
        val rows = projected(moments, traces)

        assertEquals(listOf("v-home", "a", "v-work"), rows.map { it.id }, "three legs, one row")
        assertEquals(
            30_040L + 8_720L + 21_800L, rows[1].distanceM,
            "the bridged leg's distance must count — a 60.6 km ride reported as 51.8 km is a new lie",
        )
    }

    // ── the guards, which are the point ──────────────────────────────────────

    @Test
    fun aMapRefusalIsNotOverridden() {
        // ⚠⚠ The most important case. RAIL_REFINED on the middle means the map LOOKED and said this
        // is not rail — the one verdict the tier stack lets the map walk back. Bridging it would
        // undo that at read time, which is precisely the authority §38 argued should be respected.
        val (moments, traces) = journey(middleVersion = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED)
        assertEquals(listOf("v-home", "a", "b", "c", "v-work"), projected(moments, traces).map { it.id })
    }

    @Test
    fun aLongDriveBetweenTwoShortHopsIsNotSwallowed() {
        // Train, taxi, train is a real trip shape. The fold claims the whole span was one transit
        // ride, so the unvouched-for middle may not outweigh the rail the map actually confirmed —
        // otherwise the row asserts more than its evidence supports.
        val (moments, traces) = journey(middleDistanceM = 84_000)
        assertEquals(listOf("v-home", "a", "b", "c", "v-work"), projected(moments, traces).map { it.id })
    }

    @Test
    fun aCycleBetweenTwoTransitLegsIsNeverBridged() {
        // "Got off and pedalled" is exactly this shape, and bridging it deletes a leg the user rode.
        // Only `drive` is bridgeable; every pedestrian-band mode stays its own row.
        for (mode in listOf(MovementModes.CYCLE, MovementModes.WALK, MovementModes.RUN)) {
            val (moments, traces) = journey(middleMode = mode)
            assertEquals(
                listOf("v-home", "a", "b", "c", "v-work"),
                projected(moments, traces).map { it.id },
                "a $mode leg must never be swallowed",
            )
        }
    }

    @Test
    fun aGapWideEnoughToChangeVehicleBlocksTheBridge() {
        // ⭐ The load-bearing physical argument: changing vehicle requires stopping. Contiguous legs
        // cannot be two vehicles; legs minutes apart can be, and capture recorded nothing in between
        // to say otherwise. ADJACENT_LEG_MAX_GAP_MS is 60 s, so five minutes is decisively outside.
        val (moments, traces) = journey(middleGapMs = 5 * minute)
        assertEquals(listOf("v-home", "a", "b", "c", "v-work"), projected(moments, traces).map { it.id })
    }

    @Test
    fun anUnresolvableMiddleIsNeverSwallowed() {
        // Unknown ⇒ never silently swallow, the same posture `moveDistanceM` takes. A trace we cannot
        // look up could be anything, and "anything" includes a leg the user would notice losing.
        val (moments, _) = journey()
        val withoutMiddle = listOf(
            TraceInput("t-a", MovementModes.TRANSIT, 30_040, classifierVersion = MOVEMENT_CLASSIFIER_VERSION),
            TraceInput("t-c", MovementModes.TRANSIT, 21_800, classifierVersion = MOVEMENT_CLASSIFIER_VERSION),
        )
        assertEquals(
            listOf("v-home", "a", "b", "c", "v-work"),
            projected(moments, withoutMiddle).map { it.id },
        )
    }

    @Test
    fun theEndsStillHaveToBeTransit() {
        // The bridge widens what may sit BETWEEN two transit legs; it does not widen what may be an
        // end. A drive → drive → transit span is not a ride and must not become one.
        val (moments, traces) = journey()
        val driveEnd = traces.map {
            if (it.id == "t-a") it.copy(mode = MovementModes.DRIVE) else it
        }
        assertEquals(
            listOf("v-home", "a", "b", "c", "v-work"),
            projected(moments, driveEnd).map { it.id },
        )
    }

    @Test
    fun theOrdinarySameModeFoldStillWorks() {
        // Regression guard on §33.6 shape 2: the new branch is reached only when the middle is NOT
        // the same mode, so an all-transit run must fold exactly as it did before TR-3.
        val (moments, traces) = journey(middleMode = MovementModes.TRANSIT)
        val rows = projected(moments, traces)
        assertEquals(listOf("v-home", "a", "v-work"), rows.map { it.id })
        assertEquals(30_040L + 8_720L + 21_800L, rows[1].distanceM)
    }
}
