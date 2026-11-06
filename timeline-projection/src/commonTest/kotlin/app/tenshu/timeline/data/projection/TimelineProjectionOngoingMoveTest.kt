package app.tenshu.timeline.data.projection

import app.tenshu.timeline.data.projection.TimelineProjection.MomentInput
import app.tenshu.timeline.data.projection.TimelineProjection.OngoingMoveInput
import app.tenshu.timeline.data.projection.TimelineProjection.PlaceInput
import app.tenshu.timeline.data.projection.TimelineProjection.TraceInput
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.MovementModes
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **in-progress ride in the feed** (`timeline-movement-capture.md` §12.6's deferred half),
 * projection side.
 *
 * The row is synthetic by necessity: §9's emit-on-close decision keeps movement moments immutable,
 * so an in-flight leg has no stored `Moment`/`MovementTrace` at all. Everything here is therefore
 * about the two ways that can go wrong — the live row failing to appear, and the live row failing
 * to *disappear* into the real one.
 */
class TimelineProjectionOngoingMoveTest {

    private val dayStart = 1_800_000_000_000L // a UTC midnight-ish anchor, as in TimelineProjectionTest
    private val hour = 3_600_000L
    private val minute = 60_000L

    private val home = PlaceInput("p-home", "Home", latE7 = 525_000_000, lngE7 = 134_000_000)
    private val work = PlaceInput("p-work", "Work", latE7 = 525_900_000, lngE7 = 134_900_000)

    private fun visit(id: String, atMs: Long, durSec: Long, place: PlaceInput) = MomentInput(
        id, atMs, TimelineProjection.Kind.VISIT, "Visit", durSec,
        subjectKind = TimelineProjection.Subject.PLACE, subjectId = place.id,
    )

    private fun leg(id: String, atMs: Long, durationMs: Long, traceId: String = "t-$id") = MomentInput(
        id, atMs, TimelineProjection.Kind.MOVEMENT, "Transit · 20.0 km", durationMs / 1000,
        subjectKind = TimelineProjection.Subject.TRACE, subjectId = traceId,
    )

    private fun project(
        moments: List<MomentInput>,
        traces: List<TraceInput> = emptyList(),
        ongoingMove: OngoingMoveInput? = null,
        nowMs: Long = dayStart + 2 * hour,
        places: List<PlaceInput> = listOf(home, work),
    ) = TimelineProjection.days(
        moments, places = places, traces = traces, zone = TimeZone.UTC,
        nowMs = nowMs, ongoingMove = ongoingMove,
    ).flatMap { it.moments }

    // ── it shows up ───────────────────────────────────────────────────────────

    @Test
    fun anOpenJourneySurfacesAsAnOngoingRow() {
        val departed = dayStart + hour
        val now = departed + 45 * minute

        val rows = project(
            moments = listOf(visit("v-home", dayStart, 3_600, home)),
            ongoingMove = OngoingMoveInput(startMs = departed, distanceM = 12_400, mode = MovementModes.TRANSIT),
            nowMs = now,
        )

        assertEquals(listOf("v-home", TimelineProjection.ONGOING_MOVE_ID), rows.map { it.id })
        val ride = rows[1]
        assertEquals(MomentKind.Movement, ride.kind)
        assertTrue(ride.isCurrent, "the ride you are on is marked current (● now)")
        assertEquals(MovementModes.TRANSIT, ride.movementMode)
        assertEquals(12_400L, ride.distanceM)
        assertEquals(45, ride.durationMin, "duration runs to now, not to a stored value")

        // It brackets against the visit before it exactly like a stored movement would — which is
        // the whole reason it is injected into the sequence rather than appended afterwards.
        assertEquals("Home", ride.originPlace)
        assertNull(ride.destinationPlace, "you have not arrived anywhere yet")
    }

    @Test
    fun theLiveDurationAdvancesWithoutAnythingBeingWritten() {
        // §11.2's mechanism, inherited: the same inputs projected at two different `now`s give two
        // different durations. Nothing about the stored moment list changes between them — that is
        // what "no per-minute writes" means, made observable.
        val departed = dayStart + hour
        val moments = listOf(visit("v-home", dayStart, 3_600, home))
        val ongoing = OngoingMoveInput(departed, 4_000, MovementModes.WALK)

        val at10 = project(moments, ongoingMove = ongoing, nowMs = departed + 10 * minute)
        val at40 = project(moments, ongoingMove = ongoing, nowMs = departed + 40 * minute)

        assertEquals(10, at10.single { it.isCurrent }.durationMin)
        assertEquals(40, at40.single { it.isCurrent }.durationMin)
    }

    @Test
    fun anUnsettledModeRendersAsAPlainMovement() {
        // The provisional-mode decision: below the confidence gate the caller hands us UNKNOWN, and
        // the row reads as the same plain "Movement" any other unknown-mode leg does — the live
        // distance and duration still carry the information.
        val departed = dayStart + hour
        val rows = project(
            moments = emptyList(),
            ongoingMove = OngoingMoveInput(departed, 900, MovementModes.UNKNOWN),
            nowMs = departed + 8 * minute,
        )
        val ride = rows.single()
        assertEquals(MovementModes.UNKNOWN, ride.movementMode)
        assertTrue(ride.isCurrent)
        assertEquals(900L, ride.distanceM)
    }

    // ── it goes away ──────────────────────────────────────────────────────────

    @Test
    fun theLiveRowDisappearsIntoTheRealOneWithNoDuplicate() {
        // The flush lands. A captured leg is content-addressed by its startMs, so the stored row
        // carries exactly the start the live overlay had — and the overlay must yield to it.
        val departed = dayStart + hour
        val stored = leg("cap-move:$departed", departed, 45 * minute, traceId = "t-real")
        val ongoing = OngoingMoveInput(departed, 12_400, MovementModes.TRANSIT)

        // Precondition: with no stored row, that same input does produce a live row.
        assertEquals(
            1,
            project(emptyList(), ongoingMove = ongoing).count { it.isCurrent },
            "precondition: the live row appears when nothing is stored",
        )

        val rows = project(
            moments = listOf(stored),
            traces = listOf(TraceInput("t-real", MovementModes.TRANSIT, 12_400)),
            ongoingMove = ongoing,
            nowMs = departed + 50 * minute,
        )

        assertEquals(listOf("cap-move:$departed"), rows.map { it.id }, "exactly one movement row")
        assertFalse(rows.single().isCurrent, "and it is the finished, stored one")
        assertEquals("t-real", rows.single().traceId)
    }

    @Test
    fun theLiveRowExposesNoFetchableTrace() {
        // ⚠ There is no MovementTrace row yet, so a detail hero pointed at the synthetic subject
        // would load nothing. The mode/distance still resolve — only the fetchable id is withheld.
        val ride = project(
            moments = emptyList(),
            ongoingMove = OngoingMoveInput(dayStart + hour, 4_000, MovementModes.WALK),
        ).single()

        assertEquals(MovementModes.WALK, ride.movementMode, "precondition: the subject did resolve")
        assertNull(ride.traceId)
        assertEquals(emptyList(), ride.traceIds)
    }

    // ── interaction with the B2 station-stop collapse ─────────────────────────

    @Test
    fun anOngoingLegAfterAStationStopCollapsesIntoOneCurrentRide() {
        // The shape B2 exists for, with the second leg still in progress: train → short stop →
        // still riding. It must read as ONE ride, and that ride must still be current — a collapse
        // that dropped the ongoing flag would silently un-mark the journey you are on.
        val leg1Start = dayStart + hour
        val leg1End = leg1Start + 40 * minute
        val stopEnd = leg1End + 8 * minute
        val now = stopEnd + 20 * minute

        val rows = project(
            moments = listOf(
                visit("v-home", dayStart, 3_600, home),
                leg("l1", leg1Start, leg1End - leg1Start),
                visit("v-stop", leg1End, 8 * 60, PlaceInput("p-stn", "Hauptbahnhof")),
            ),
            traces = listOf(TraceInput("t-l1", MovementModes.TRANSIT, 20_000)),
            ongoingMove = OngoingMoveInput(stopEnd, 14_200, MovementModes.TRANSIT),
            nowMs = now,
            places = listOf(home, work, PlaceInput("p-stn", "Hauptbahnhof")),
        )

        assertEquals(listOf("v-home", "l1"), rows.map { it.id }, "the station row is collapsed away")
        val ride = rows[1]
        assertTrue(ride.isCurrent, "the collapsed ride is still the one you are on")
        assertEquals(34_200L, ride.distanceM, "both legs' distance — 20.0 + 14.2 km")
        assertEquals(68, ride.durationMin, "40 + 8 (the stop) + 20 so far")
        assertEquals("Transit · 34.2 km", ride.title, "the title is regenerated for the whole ride")
        // Only the real leg has a trace to draw; the live one has none yet.
        assertEquals(listOf("t-l1"), ride.traceIds)
    }

    @Test
    fun anOngoingLegIsNeverAbsorbedIntoAMergedVisit() {
        // Defensive: an ongoing leg is normally the newest moment, so nothing follows it to close a
        // same-place run — but a peer's synced visit can sort after it, and being absorbed would
        // take the whole "you are moving right now" signal with it.
        // ⚠ The two visits must sit inside MERGE_MAX_GAP_MS (15 min) of each other, or the merge
        // never engages and the "precondition" would prove nothing.
        val firstVisit = visit("v-home", dayStart, 600, home)          // 00:00–00:10
        val departed = dayStart + 11 * minute
        val peerVisit = visit("v-peer", dayStart + 16 * minute, 600, home)
        val now = dayStart + 20 * minute

        // Precondition: a *stored* short movement in that position really is absorbed, so this
        // fixture does reach the merge decision rather than passing by never getting there.
        val absorbed = project(
            moments = listOf(firstVisit, leg("m-short", departed, 4 * minute, traceId = "t-short"), peerVisit),
            traces = listOf(TraceInput("t-short", MovementModes.WALK, 120)),
            nowMs = now,
        )
        assertFalse(
            absorbed.any { it.id == "m-short" },
            "precondition: a stored short move between two same-place visits is absorbed, got ${absorbed.map { it.id }}",
        )

        val rows = project(
            moments = listOf(firstVisit, peerVisit),
            ongoingMove = OngoingMoveInput(departed, 260, MovementModes.WALK),
            nowMs = now,
        )
        assertTrue(
            rows.any { it.id == TimelineProjection.ONGOING_MOVE_ID && it.isCurrent },
            "the live row survives the merge, rows were ${rows.map { it.id }}",
        )
    }

    @Test
    fun withoutAnOngoingMoveNothingIsFabricated() {
        // The null case, pinned: no live state ⇒ the projection is byte-identical to what it was.
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", dayStart + hour, 30 * minute),
        )
        val traces = listOf(TraceInput("t-l1", MovementModes.TRANSIT, 20_000))

        val rows = project(moments, traces, ongoingMove = null)

        assertEquals(listOf("v-home", "l1"), rows.map { it.id })
        assertTrue(rows.none { it.isCurrent }, "nothing is current when capture says nothing is")
    }
}
