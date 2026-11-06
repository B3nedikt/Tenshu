@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.WorkoutChanges
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.health.Workout
import org.tenshu.core.place.LabelSource
import org.tenshu.core.timeline.SubjectKind
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules `TimelineBook.kt` copies from `:timeline-shared-data` (web-app-suite Wave 2, Timeline
 * run A), pinned without the runtime. ⚠⚠ This is the second reader of the same relations: change a
 * native rule and the matching case here must change with it. The runtime-backed contract is
 * `TimelineWebBridgeTest`.
 */
class TimelineBookMappingTest {

    private fun visit(id: String, atMs: Long, placeId: String, durationSec: Long = 3600) = MomentRow(
        id = id, timestampMs = atMs, kind = "Visit", title = "Visit", durationSec = durationSec,
        subjectKind = SubjectKind.PLACE, subjectId = placeId, supersededBy = "", sources = listOf("capture"),
    )

    private fun place(id: String, label: String = "", latE7: Long = 100_000_000, lngE7: Long = 100_000_000) =
        PlaceRow(id, label, latE7, lngE7, city = "", countryCode = "", labelSource = LabelSource.GEOCODED)

    private fun snapshot(
        moments: List<MomentRow>,
        places: List<PlaceRow>,
        merges: Map<String, String> = emptyMap(),
        roles: Map<String, RoleRow> = emptyMap(),
    ) = TimelineSnapshot(moments, places, merges, roles, traces = emptyList(), trips = emptyList())

    // 2026-03-10T06:30Z is 23:30 on the 9th in Los Angeles (PDT, UTC-7 since 8 March), and
    // 07:30Z is 00:30 on the 10th. In UTC both are the 10th.
    private val lateEvening = 1_773_124_200_000L
    private val afterMidnight = lateEvening + 3_600_000L

    @Test
    fun aVisitBelongsToTheLocalDayItBegan_inTheZoneAsked() {
        val s = snapshot(
            listOf(visit("a", lateEvening, "p", durationSec = 7200), visit("b", afterMidnight, "q")),
            listOf(place("p"), place("q", latE7 = 101_000_000)),
        )
        val la = groupDays(s.liveMoments, s, assertNotNull(LocalDates.of("America/Los_Angeles")))
        assertEquals(listOf("2026-03-10", "2026-03-09"), la.map { it.date }, "newest day first, split at LOCAL midnight")
        assertEquals(listOf("b"), la[0].moments.map { it.momentId })
        assertEquals(listOf("a"), la[1].moments.map { it.momentId }, "a stay across midnight stays on its start day")

        val utc = groupDays(s.liveMoments, s, assertNotNull(LocalDates.of("UTC")))
        assertEquals(listOf("2026-03-10"), utc.map { it.date }, "the same rows in UTC are one day — the zone is load-bearing")
        assertEquals(listOf("a", "b"), utc[0].moments.map { it.momentId }, "oldest first within a day")
    }

    @Test
    fun anUnknownZoneIsRefused_notSilentlyUtc() {
        assertNull(LocalDates.of("Not/AZone"))
        assertNotNull(LocalDates.of(""), "'' is the browser's own zone")
    }

    @Test
    fun theSuggestionDateRange_matchesTheNativeFormat() {
        val zone = assertNotNull(LocalDates.of("UTC"))
        val oct3 = 1_791_000_000_000L // 2026-10-03T04:00Z
        assertEquals("Oct 3 – 7", zone.dateRange(oct3, oct3 + 4 * 86_400_000L))
        assertEquals("Oct 3 – Nov 2", zone.dateRange(oct3, oct3 + 30 * 86_400_000L))
        assertEquals("2026", zone.year(oct3))
    }

    @Test
    fun aVisitResolvesItsPlace_andOnlyAConfirmedRoleReachesTheRow() {
        val s = snapshot(
            listOf(visit("a", lateEvening, "home"), visit("b", afterMidnight, "office", durationSec = 60)),
            listOf(place("home", "Flat"), place("office", "Desk", latE7 = 102_000_000)),
            roles = mapOf("home" to RoleRow("home", isSuggestion = false), "office" to RoleRow("work", isSuggestion = true)),
        )
        val rows = s.liveMoments.associate { it.id to resolveMoment(it, s) }
        assertEquals("Flat", rows.getValue("a").placeName)
        assertEquals("home", rows.getValue("a").placeRole)
        assertEquals("Desk", rows.getValue("b").placeName)
        assertNull(rows.getValue("b").placeRole, "D9: a suggested role must never rename a row")
        assertNull(rows.getValue("a").activityType, "fields of other subject kinds are null, not ''")
    }

    @Test
    fun aDanglingPlaceIsUnresolved_notAnUnnamedPlace() {
        val s = snapshot(listOf(visit("a", lateEvening, "gone")), emptyList())
        val row = resolveMoment(s.liveMoments.single(), s)
        assertEquals(false, row.subjectResolved)
        assertNull(row.placeName, "unknown place is null; '' would claim it exists and is unnamed")
    }

    @Test
    fun mergeFolding_hidesTheFoldedPlace_andRepointsItsVisits() {
        val s = snapshot(
            listOf(visit("v", lateEvening, "p-b")),
            listOf(place("p-a", "Survivor"), place("p-b", "Folded", latE7 = 103_000_000)),
            merges = mapOf("p-b" to "p-a"),
        )
        assertEquals(listOf("p-a"), s.places.map { it.id })
        val row = resolveMoment(s.liveMoments.single(), s)
        assertEquals("p-a", row.subjectId)
        assertEquals("Survivor", row.placeName)
        // An unmerge is a BLANK target, not a deleted row.
        val unmerged = snapshot(s.rawMoments, s.rawPlaces, merges = mapOf("p-b" to ""))
        assertEquals(listOf("p-a", "p-b"), unmerged.places.map { it.id })
    }

    @Test
    fun reconciliation_dropsSupersededRows_andFoldsSameCellSameBucketVisits() {
        val dup = visit("dup", lateEvening + 60_000, "p").copy(sources = listOf("google"))
        val superseded = visit("old", lateEvening - 86_400_000, "p").copy(supersededBy = "x")
        val s = snapshot(listOf(visit("cap", lateEvening, "p"), dup, superseded), listOf(place("p")))
        assertEquals(listOf("cap"), s.liveMoments.map { it.id }, "capture beats google, superseded is hidden")
        assertEquals(3, s.moments.size, "detail reads still see every row")
    }

    @Test
    fun thePolylineDecoder_readsTheGoldenBytesTheJvmEncoderWrites() {
        val points = decodePolyline(GOLDEN)
        assertContentEquals(
            listOf(
                Triple(482_000_000L, 115_000_000L, 1_700_000_000_000L),
                Triple(482_000_150L, 115_000_300L, 1_700_000_005_000L),
                Triple(481_999_900L, 114_999_800L, 1_700_000_012_000L),
            ),
            points,
        )
        assertTrue(decodePolyline(ByteArray(0)).isEmpty())
        assertFailsWith<IllegalArgumentException>("a truncated blob is an error, not a shorter route") {
            decodePolyline(GOLDEN.copyOf(GOLDEN.size - 3))
        }
    }

    // 2026 in New York: EST→EDT at 07:00Z on 8 March, EDT→EST at 06:00Z on 1 November. Each pair
    // straddles a local midnight on one side of a transition, so ANY fixed offset (−5 or −4)
    // misfiles one of them.
    @Test
    fun dayGrouping_followsTheZoneAcrossBothDstTransitions() {
        val at = mapOf(
            "a" to 1_772_944_200_000L, // 03-08T04:30Z = 03-07 23:30 EST
            "b" to 1_772_947_800_000L, // 03-08T05:30Z = 03-08 00:30 EST
            "c" to 1_773_027_000_000L, // 03-09T03:30Z = 03-08 23:30 EDT
            "d" to 1_773_030_600_000L, // 03-09T04:30Z = 03-09 00:30 EDT
            "e" to 1_793_503_800_000L, // 11-01T03:30Z = 10-31 23:30 EDT
            "f" to 1_793_507_400_000L, // 11-01T04:30Z = 11-01 00:30 EDT
            "g" to 1_793_593_800_000L, // 11-02T04:30Z = 11-01 23:30 EST
            "h" to 1_793_597_400_000L, // 11-02T05:30Z = 11-02 00:30 EST
        )
        // One place per row, far apart, so reconciliation folds nothing.
        val s = snapshot(
            at.map { (id, ms) -> visit(id, ms, "p-$id", durationSec = 60) },
            at.keys.mapIndexed { i, id -> place("p-$id", latE7 = 100_000_000L + i * 10_000_000L) },
        )
        val ny = groupDays(s.liveMoments, s, assertNotNull(LocalDates.of("America/New_York")))
        assertEquals(
            listOf(
                "2026-11-02" to listOf("h"), "2026-11-01" to listOf("f", "g"), "2026-10-31" to listOf("e"),
                "2026-03-09" to listOf("d"), "2026-03-08" to listOf("b", "c"), "2026-03-07" to listOf("a"),
            ),
            ny.map { d -> d.date to d.moments.map { it.momentId } },
            "split at the LOCAL midnight on both sides of each transition",
        )
    }

    // ── suggestions, silent sources, workout hydration ──────────────────────

    @Test
    fun anAcceptedSuggestion_isNotOfferedAgain_evenWithNoTabMemory() {
        // Two away visits > 100 km from a confirmed home: one suggestion.
        val s = snapshot(
            listOf(visit("t1", 400_000_000_000L, "away"), visit("t2", 400_000_000_000L + 20 * 3_600_000L, "away")),
            listOf(place("home", "Home"), place("away", "Lisbon", latE7 = -300_000_000, lngE7 = 1_200_000_000)),
            roles = mapOf("home" to RoleRow("home", isSuggestion = false)),
        )
        val offered = suggestions(s, resolved = emptySet()).single()
        val accepted = TimelineSnapshot(
            s.rawMoments, s.rawPlaces, s.merges, s.roles, traces = emptyList(),
            trips = listOf(TripRow(offered.suggestedTripId, "Lisbon", "", "", offered.memberMomentIds)),
        )
        // A reload: the tab's resolved set is empty again; the bound trip is the only memory.
        assertTrue(suggestions(accepted, resolved = emptySet()).isEmpty(), "an accepted suggestion must not come back")
    }

    @Test
    fun aSourceThatNeverAnswers_isATypedFailure_notAHang() = GlobalScope.promise {
        val never: Flow<Read<List<Pair<String, String>>>> = flow { awaitCancellation() }
        val sources = TimelineSources(
            moments = flowOf(Read.Ok(emptyList())), places = flowOf(Read.Ok(emptyList())),
            merges = never, roles = flowOf(Read.Ok(emptyList())),
            traces = flowOf(Read.Ok(emptyList())), trips = flowOf(Read.Ok(emptyList())),
        )
        val read = withTimeout(5.seconds) { timelineFlowOf(sources, silentAfter = 50.milliseconds).first() }
        val silent = assertIs<TimelineRead.Silent>(read)
        assertEquals("merges", silent.source)
        assertEquals("error_timeline_source_silent", read.failure().error.kind)
    }

    @Test
    fun workoutHydration_readsOnlyTheWindow_andOnlyWhatChanged() = GlobalScope.promise {
        fun activity(id: String, at: Long, workout: String, supersededBy: String = "") = MomentRow(
            id, at, "Activity", "Activity", 600, SubjectKind.WORKOUT, workout, supersededBy, listOf("capture"),
        )
        val moments = MutableStateFlow<Read<List<MomentRow>>>(
            Read.Ok(listOf(activity("m1", 1_000, "w1"), activity("m2", 2_000_000, "w2"), activity("m3", 3_000_000, "w3", supersededBy = "m2"))),
        )
        val version = MutableStateFlow(0L)
        var changed: Set<String>? = emptySet()
        val reads = mutableListOf<String>()
        val hydrator = WorkoutHydrator(
            window = { it.liveMoments }, changes = version, changedSince = { changed },
            read = { id -> reads += id; "type-$id" },
        )
        val sources = TimelineSources(
            moments, flowOf(Read.Ok(emptyList())), flowOf(Read.Ok(emptyList())), flowOf(Read.Ok(emptyList())),
            flowOf(Read.Ok(emptyList())), flowOf(Read.Ok(emptyList())),
        )
        val out = timelineFlowOf(sources, hydrator = hydrator).produceIn(this)
        suspend fun next(): TimelineSnapshot = assertIs<TimelineRead.Ok>(withTimeout(5.seconds) { out.receive() }).snapshot
        try {
            val first = next()
            assertEquals(listOf("w1", "w2"), reads.sorted(), "only the window's (live) Activity subjects; m3 is superseded")
            assertEquals(mapOf("w1" to "type-w1", "w2" to "type-w2"), first.workoutActivity)

            reads.clear()
            moments.value = Read.Ok((moments.value as Read.Ok).value + activity("m4", 9_000_000, "w4"))
            assertEquals("type-w4", next().workoutActivity["w4"])
            assertEquals(listOf("w4"), reads, "a moment change reads only the new id")

            reads.clear()
            changed = setOf("w2")
            version.value = 1
            next()
            assertEquals(listOf("w2"), reads, "a workout save re-reads that workout alone")

            reads.clear()
            changed = null
            version.value = 2
            next()
            assertEquals(listOf("w1", "w2", "w4"), reads.sorted(), "an unnamed change re-reads the window — never past it")
        } finally {
            out.cancel()
        }
    }

    @Test
    fun workoutChanges_movesOnlyForWorkouts_andNamesTheWorkout() {
        val before = WorkoutChanges.version.value
        WorkoutChanges.onDataDeleted(DataId.StringDataId.create(type = "tenshu/place/place", keyValue = "pl-x"), WriteOrigin.SYNC_INGEST)
        assertEquals(before, WorkoutChanges.version.value, "a delete of another type is not a workout change")
        WorkoutChanges.onDataDeleted(DataId.StringDataId.create(type = Workout.ID, keyValue = "wo-x"), WriteOrigin.SYNC_INGEST)
        assertEquals(before + 1, WorkoutChanges.version.value)
        assertEquals(setOf("wo-x"), WorkoutChanges.changedSince(before), "the delete names the workout it removed")
    }

    // ── the feed is folded by the SHARED projection (`:timeline-projection`, §Web scope 4a) ──
    //
    // ⚠ These pin that the web feed IS the native projection over the web's rows — the rules
    // themselves are pinned once, on JVM and JS, by `TimelineProjectionTest` & co. in that module.

    private val nineUtc = 1_773_133_200_000L // 2026-03-10T09:00Z
    private val minute = 60_000L

    private fun leg(id: String, atMs: Long, minutes: Long, traceId: String) = MomentRow(
        id = id, timestampMs = atMs, kind = "Movement", title = "Transit · 20.0 km", durationSec = minutes * 60,
        subjectKind = SubjectKind.TRACE, subjectId = traceId, supersededBy = "", sources = listOf("capture"),
    )

    private fun feedOf(
        moments: List<MomentRow>,
        places: List<PlaceRow>,
        traces: List<TraceRow> = emptyList(),
        roles: Map<String, RoleRow> = emptyMap(),
    ): List<TimelineDayMomentJs> {
        val s = TimelineSnapshot(moments, places, emptyMap(), roles, traces, trips = emptyList())
        return feedDays(s, assertNotNull(projectionZone("UTC"))).flatMap { it.moments.toList() }
    }

    @Test
    fun feed_aSplitStayIsOneRow() {
        // One stay the capture layer split in two (a 5-minute gap), at the same place. ⚠ 09:00 and
        // 10:15 sit in different 2-hour reconciler buckets, so this is the §2b merge, not a dedupe.
        val rows = feedOf(
            listOf(visit("v1", nineUtc, "p", durationSec = 70 * 60), visit("v2", nineUtc + 75 * minute, "p", durationSec = 50 * 60)),
            listOf(place("p", "Library")),
        )
        assertEquals(listOf("v1"), rows.map { it.momentId }, "two fragments → one row, the longest dwell representing it")
        val row = rows.single()
        assertEquals(2, row.mergedStayCount)
        assertEquals(125, row.spanDurationMin, "the whole span: 09:00 → 11:05")
        assertEquals(70.0 * 60, row.durationSec, "durationSec still describes the representative stored moment")
        assertEquals("Library", row.placeName)
    }

    /** Home → train → 8-minute station stop → train → Work, the native §15 fixture's shape. */
    private fun rideThroughAStop(): Pair<List<MomentRow>, List<TraceRow>> {
        val l1 = nineUtc + 60 * minute
        val stop = l1 + 40 * minute
        val l2 = stop + 8 * minute
        val work = l2 + 35 * minute
        return listOf(
            visit("v-home", nineUtc, "home"),
            leg("l1", l1, 40, "t-l1"),
            visit("v-stop", stop, "station", durationSec = 8 * 60),
            leg("l2", l2, 35, "t-l2"),
            visit("v-work", work, "work"),
        ) to listOf(TraceRow("t-l1", "transit", 20_000), TraceRow("t-l2", "transit", 14_200))
    }

    private val ridePlaces = listOf(
        place("home", "Flat"),
        place("station", "Hauptbahnhof", latE7 = 101_000_000),
        place("work", "Office", latE7 = 102_000_000),
    )

    @Test
    fun feed_aStationStopInsideATrainRideCollapses_andTheRideIsNamedByItsEnds() {
        val (moments, traces) = rideThroughAStop()
        val rows = feedOf(moments, ridePlaces, traces)

        assertEquals(listOf("v-home", "l1", "v-work"), rows.map { it.momentId }, "ride / stay / ride reads as ONE ride")
        val ride = rows[1]
        assertEquals("Transit · 34.2 km", ride.displayTitle, "the whole ride's title, regenerated")
        assertEquals("Transit · 20.0 km", ride.title, "title stays the representative's stored one")
        assertEquals(34_200.0, ride.spanDistanceM)
        assertEquals(20_000.0, ride.distanceM, "distanceM still describes the first leg's trace")
        assertEquals(83, ride.spanDurationMin, "40 + 8 (the stop) + 35 min")
        assertContentEquals(arrayOf("t-l1", "t-l2"), ride.traceIds)
        assertEquals("Flat", ride.originPlace)
        assertEquals("Office", ride.destinationPlace)
    }

    @Test
    fun feed_endpointsCarryOnlyConfirmedRoles_andARoleBearingStopIsNeverSwallowed() {
        val (moments, traces) = rideThroughAStop()
        val rows = feedOf(
            moments, ridePlaces, traces,
            roles = mapOf(
                "home" to RoleRow("home", isSuggestion = false),
                "work" to RoleRow("work", isSuggestion = true),
                // A SUGGESTED role on the stop still vetoes the collapse (native `rolePlaceIds`).
                "station" to RoleRow("work", isSuggestion = true),
            ),
        )
        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.momentId })
        assertEquals("home", rows[0].placeRole)
        assertEquals("home", rows[1].originRole, "a confirmed role travels with its endpoint")
        assertNull(rows[3].destinationRole, "D9: a suggested role never reaches a row")
        assertEquals("Office", rows[3].destinationPlace)
    }

    @Test
    fun feed_theRailRefinedUndergroundStampIsRead_soATransferStaysTwoRows() {
        // §32.12: a metro leg the sweep confirmed underground beside a surface train is a TRANSFER.
        val (moments, traces) = rideThroughAStop()
        // RAIL_REFINED (23) surface train → RAIL_REFINED_UNDERGROUND (24) metro: native
        // `theSweepConfirmedMetroAlsoBlocksTheFold`. With both stamps unread (0) the same rows fold.
        val metro = traces.map { it.copy(classifierVersion = if (it.id == "t-l2") 24 else 23) }
        assertEquals(
            listOf("v-home", "l1", "v-stop", "l2", "v-work"),
            feedOf(moments, ridePlaces, metro).map { it.momentId },
            "the web must read classifierVersion, or this folds where native does not",
        )
    }

    @Test
    fun aTripOrPlaceRow_restatesItsOneMoment() {
        val s = snapshot(listOf(visit("a", nineUtc, "p", durationSec = 90 * 60)), listOf(place("p", "Cafe")))
        val row = resolveMoment(s.liveMoments.single(), s)
        assertEquals(row.title, row.displayTitle)
        assertEquals(90, row.spanDurationMin)
        assertEquals(1, row.mergedStayCount)
        assertNull(row.originPlace)
    }

    private companion object {
        // ⚠ Byte-identical to `MovementTracePolylineGoldenTest.GOLDEN` (`:timeline-shared-data`).
        val GOLDEN = byteArrayOf(
            3, -128, -14, -43, -53, 3, -128, -117, -42, 109, -128, -96, -85, -2, -7, 98, 10,
            -84, 2, -40, 4, -112, 78, 4, -13, 3, -25, 7, -80, 109, 5,
        )
    }
}
