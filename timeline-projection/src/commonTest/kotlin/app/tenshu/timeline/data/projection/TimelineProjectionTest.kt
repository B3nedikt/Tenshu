package app.tenshu.timeline.data.projection

import app.tenshu.timeline.data.projection.TimelineProjection.MomentInput
import app.tenshu.timeline.data.projection.TimelineProjection.PlaceInput
import app.tenshu.timeline.data.projection.TimelineProjection.TraceInput
import app.tenshu.timeline.data.projection.TimelineProjection.TripInput
import app.tenshu.timeline.data.projection.TimelineProjection.ActivityCountInput
import app.tenshu.timeline.data.projection.TimelineProjection.WorkoutInput
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.MovementModes
import app.tenshu.timeline.model.TripEntry
import kotlinx.datetime.TimeZone
import org.tenshu.core.place.LabelSource
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineProjectionTest {

    // Midnight UTC of an arbitrary day; +5h stays the same calendar day, +1 day rolls over.
    private val dayStart = 1_799_971_200_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    @Test
    fun daysGroupMomentsByCalendarDayNewestFirst() {
        val a = MomentInput("a", dayStart, TimelineProjection.Kind.VISIT, "Café", 4_920)      // 82 min
        val b = MomentInput("b", dayStart + 5 * hour, TimelineProjection.Kind.ACTIVITY, "Run", 3_600)
        val c = MomentInput("c", dayStart + day, TimelineProjection.Kind.QUIET, "Home", 0)

        val days = TimelineProjection.days(listOf(a, b, c), zone = TimeZone.UTC)

        assertEquals(2, days.size)
        // Newest day first: the +1-day moment 'c' is its own, single-moment day.
        assertEquals(listOf("c"), days[0].moments.map { it.id })
        // The earlier day holds a + b, oldest-first within the day.
        assertEquals(listOf("a", "b"), days[1].moments.map { it.id })
        // Field mapping.
        assertEquals(MomentKind.Visit, days[1].moments[0].kind)
        assertEquals(82, days[1].moments[0].durationMin)     // 4920s → 82m
        assertEquals(MomentKind.Quiet, days[0].moments[0].kind)
        assertNull(days[0].moments[0].durationMin)           // 0s → null
        assertEquals("00:00", days[1].moments[0].time)       // dayStart is midnight UTC
        assertEquals("05:00", days[1].moments[1].time)
    }

    // ── M5-lite: movement moments become their own kind, carrying mode + distance ─────

    @Test
    fun movementMomentResolvesItsTraceSubject() {
        // Before M5-lite this fell through `else -> Event` and the trace was never read, so a walk
        // rendered as a music-note Event card with a blank subtitle.
        val move = MomentInput(
            "m", dayStart, TimelineProjection.Kind.MOVEMENT, "Ride · 12.4 km", 2_280,
            subjectKind = TimelineProjection.Subject.TRACE, subjectId = "trace-1",
        )
        val trace = TraceInput("trace-1", MovementModes.CYCLE, 12_400)

        val moment = TimelineProjection.days(listOf(move), traces = listOf(trace), zone = TimeZone.UTC)
            .single().moments.single()

        assertEquals(MomentKind.Movement, moment.kind)
        assertEquals(MovementModes.CYCLE, moment.movementMode)
        assertEquals(12_400L, moment.distanceM)
        assertEquals(38, moment.durationMin)
        // A movement has no place subject — the card supplies the mode label for that slot.
        assertEquals("", moment.place)
        // M5: the trace subject id rides along so the detail can fetch the route polyline.
        assertEquals("trace-1", moment.traceId)
        assertNull(moment.placeId)
    }

    @Test
    fun movementWithDanglingOrMissingTraceStillRenders() {
        // Same degradation contract as a dangling place reference: null fields, never a failure.
        val move = MomentInput(
            "m", dayStart, TimelineProjection.Kind.MOVEMENT, "Walk", 600,
            subjectKind = TimelineProjection.Subject.TRACE, subjectId = "gone",
        )
        val moment = TimelineProjection.days(
            listOf(move), traces = listOf(TraceInput("other", MovementModes.WALK, 900)), zone = TimeZone.UTC,
        ).single().moments.single()

        assertEquals(MomentKind.Movement, moment.kind)
        assertNull(moment.movementMode)
        assertNull(moment.distanceM)
    }

    @Test
    fun nonMovementKindsCarryNoTraceFields() {
        val visit = MomentInput(
            "v", dayStart, TimelineProjection.Kind.VISIT, "Café", 600,
            subjectKind = TimelineProjection.Subject.PLACE, subjectId = "p",
        )
        val moment = TimelineProjection.days(listOf(visit), zone = TimeZone.UTC).single().moments.single()
        assertNull(moment.movementMode)
        assertNull(moment.distanceM)
    }

    @Test
    fun unknownKindFromANewerPeerStillRendersAsEvent() {
        val odd = MomentInput("x", dayStart, "SomethingNew", "?", 0)
        assertEquals(
            MomentKind.Event,
            TimelineProjection.days(listOf(odd), zone = TimeZone.UTC).single().moments.single().kind,
        )
    }

    // ── §2b: consecutive same-place visit merge ───────────────────────────────

    // Munich ~50 m apart; `faraway` ~5.5 km off. E7 degrees.
    private val ringStreet = PlaceInput("p-street", "Street", latE7 = 481_500_000, lngE7 = 115_860_000)
    private val forum = PlaceInput("p-forum", "Building", latE7 = 481_504_500, lngE7 = 115_860_000)
    private val faraway = PlaceInput("p-far", "Elsewhere", latE7 = 482_000_000, lngE7 = 115_860_000)

    private fun visit(id: String, atMs: Long, durSec: Long, place: PlaceInput) = MomentInput(
        id, atMs, TimelineProjection.Kind.VISIT, place.label, durSec,
        subjectKind = TimelineProjection.Subject.PLACE, subjectId = place.id,
    )

    @Test
    fun consecutiveSamePlaceVisitsMergeIntoOneSpanningStay() {
        // The reported bug: one stay split into three overlapping/contiguous fragments metres apart.
        val v1 = visit("v1", dayStart, 300, ringStreet)              // 5 min
        val v2 = visit("v2", dayStart, 660, forum)               // 11 min, overlaps v1, ~50 m away
        val v3 = visit("v3", dayStart + 11 * 60_000, 300, forum) // 5 min

        val moments = TimelineProjection.days(
            listOf(v1, v2, v3), places = listOf(ringStreet, forum, faraway), zone = TimeZone.UTC,
        ).single().moments

        assertEquals(1, moments.size) // three fragments → one row
        val merged = moments.single()
        assertEquals("v2", merged.id) // representative = the longest dwell
        assertEquals("Building", merged.place)
        assertEquals(3, merged.mergedStayCount)
        // Span: first arrival (dayStart) → last departure (dayStart + 11 min + 5 min) = 16 min.
        assertEquals(16, merged.durationMin)
    }

    @Test
    fun realTravelBetweenSamePlaceVisitsBreaksTheMerge() {
        val v1 = visit("v1", dayStart, 600, forum)
        val trip = MomentInput(
            "trip", dayStart + 600_000, TimelineProjection.Kind.MOVEMENT, "Drive", 300,
            subjectKind = TimelineProjection.Subject.TRACE, subjectId = "t1",
        )
        val v2 = visit("v2", dayStart + 900_000, 600, forum) // same place, but a real trip came between
        val moments = TimelineProjection.days(
            listOf(v1, trip, v2),
            places = listOf(forum),
            traces = listOf(TraceInput("t1", MovementModes.DRIVE, 8_000)), // 8 km ≥ absorb threshold
            zone = TimeZone.UTC,
        ).single().moments
        assertEquals(listOf("v1", "trip", "v2"), moments.map { it.id }) // three rows, chronological
    }

    @Test
    fun visitsFartherThanTheRadiusStaySeparate() {
        val v1 = visit("v1", dayStart, 600, forum)
        val v2 = visit("v2", dayStart + 5 * 60_000, 600, faraway) // ~5.5 km away, contiguous in time
        val moments = TimelineProjection.days(
            listOf(v1, v2), places = listOf(forum, faraway), zone = TimeZone.UTC,
        ).single().moments
        assertEquals(listOf("v1", "v2"), moments.map { it.id })
    }

    @Test
    fun shortMoveBetweenSamePlaceVisitsIsAbsorbedAndSurfaced() {
        val v1 = visit("v1", dayStart, 600, ringStreet)
        val stroll = MomentInput(
            "stroll", dayStart + 600_000, TimelineProjection.Kind.MOVEMENT, "Walk", 120,
            subjectKind = TimelineProjection.Subject.TRACE, subjectId = "t1",
        )
        val v2 = visit("v2", dayStart + 720_000, 600, forum)
        val moments = TimelineProjection.days(
            listOf(v1, stroll, v2),
            places = listOf(ringStreet, forum),
            traces = listOf(TraceInput("t1", MovementModes.WALK, 150)), // 150 m < absorb threshold
            zone = TimeZone.UTC,
        ).single().moments
        assertEquals(1, moments.size) // the stroll folds in, not its own row
        val merged = moments.single()
        assertEquals(2, merged.mergedStayCount)
        assertEquals(1, merged.containedMoves.size)
        assertEquals(150L, merged.containedMoves.single().distanceM)
        assertEquals(MovementModes.WALK, merged.containedMoves.single().mode)
    }

    @Test
    fun subjectHydrationFillsMomentSubtitleFromPlaceAndWorkout() {
        val visit = MomentInput(
            "v", dayStart, TimelineProjection.Kind.VISIT, "Coffee", 1_080,
            subjectKind = TimelineProjection.Subject.PLACE, subjectId = "place-1",
        )
        val activity = MomentInput(
            "a", dayStart + hour, TimelineProjection.Kind.ACTIVITY, "Morning run", 1_920,
            subjectKind = TimelineProjection.Subject.WORKOUT, subjectId = "workout-1",
        )
        val eventless = MomentInput("e", dayStart + 2 * hour, TimelineProjection.Kind.EVENT, "Note", 0)

        val days = TimelineProjection.days(
            moments = listOf(visit, activity, eventless),
            places = listOf(PlaceInput("place-1", "Blue Bottle")),
            workouts = listOf(WorkoutInput("workout-1", "Running")),
            zone = TimeZone.UTC,
        )
        val byId = days.flatMap { it.moments }.associateBy { it.id }

        assertEquals("Blue Bottle", byId.getValue("v").place)   // Visit → place name
        assertEquals("Running", byId.getValue("a").place)       // Activity → workout activity
        assertEquals("", byId.getValue("e").place)              // subject-less → empty
    }

    @Test
    fun danglingSubjectReferenceResolvesToEmpty() {
        val visit = MomentInput(
            "v", dayStart, TimelineProjection.Kind.VISIT, "Coffee", 0,
            subjectKind = TimelineProjection.Subject.PLACE, subjectId = "missing",
        )
        val days = TimelineProjection.days(listOf(visit), places = emptyList(), zone = TimeZone.UTC)
        assertEquals("", days.single().moments.single().place)
    }

    @Test
    fun placeDerivesVisitHistoryFromMoments() {
        val early = MomentInput(
            "m-early", dayStart, TimelineProjection.Kind.VISIT, "Lunch", 4_800,   // 80 min
            subjectKind = TimelineProjection.Subject.PLACE, subjectId = "place-1",
        )
        val late = MomentInput(
            "m-late", dayStart + day, TimelineProjection.Kind.VISIT, "Dinner", 7_200,   // 120 min
            subjectKind = TimelineProjection.Subject.PLACE, subjectId = "place-1",
        )

        val place = TimelineProjection.place(
            p = PlaceInput("place-1", "Mogg"),
            visits = listOf(early, late),
            zone = TimeZone.UTC,
        )

        assertEquals(2, place.visitsCount)
        // History is newest-first.
        assertEquals(listOf("m-late", "m-early"), place.history.map { it.id })
        assertEquals("Dinner", place.history[0].label)
        assertEquals("2 h", place.history[0].duration)          // 7200s → 120m
        assertEquals("1 h 20 min", place.history[1].duration)   // 4800s → 80m
        assertEquals("1 h 40 min", place.avgDuration)           // (80+120)/2 = 100m
    }

    @Test
    fun placeWithoutVisitsHasHonestEmpties() {
        val place = TimelineProjection.place(PlaceInput("place-1", "Blue Bottle"))
        assertEquals("place-1", place.id)
        assertEquals("Blue Bottle", place.name)
        assertEquals(0, place.visitsCount)
        assertEquals(emptyList(), place.history)
        assertNull(place.avgDuration)
        assertEquals("", place.firstVisit)
    }

    // ── trip projection ──────────────────────────────────────────────────────

    // Airport (day 1), Ramiro (day 1), Belém (day 2) — two calendar days.
    private fun tripMoments() = listOf(
        MomentInput("m-airport", dayStart + 14 * hour, TimelineProjection.Kind.VISIT, "Arrival", 3_600,
            subjectKind = TimelineProjection.Subject.PLACE, subjectId = "p-airport"),
        MomentInput("m-ramiro", dayStart + 20 * hour, TimelineProjection.Kind.VISIT, "Dinner", 5_400,
            subjectKind = TimelineProjection.Subject.PLACE, subjectId = "p-ramiro"),
        MomentInput("m-belem", dayStart + day + 11 * hour, TimelineProjection.Kind.VISIT, "Belém", 6_000,
            subjectKind = TimelineProjection.Subject.PLACE, subjectId = "p-belem"),
    )

    private fun tripPlaces() = listOf(
        PlaceInput("p-airport", "Aeroporto", latE7 = 387_756_000, lngE7 = -91_354_000),
        PlaceInput("p-ramiro", "Cervejaria Ramiro", latE7 = 387_267_000, lngE7 = -91_357_000),
        PlaceInput("p-belem", "Belém Tower", latE7 = 386_916_000, lngE7 = -92_160_000),
    )

    @Test
    fun multiDayTripBuildsAscendingDayChaptersWithCounts() {
        val trip = TimelineProjection.trip(
            TripInput("lisbon", "Lisbon", "Apr 12 – 13", "2026", membership = listOf("m-airport", "m-ramiro", "m-belem")),
            moments = tripMoments(),
            places = tripPlaces(),
            zone = TimeZone.UTC,
        )

        assertEquals("2026", trip.year)
        assertEquals(2, trip.daysSpan)
        assertEquals(3, trip.momentsCount)
        assertEquals(3, trip.placesCount)          // three distinct place subjects
        assertEquals(0, trip.photosCount)

        // Two ascending day chapters; Day 1 holds the two same-day moments oldest-first.
        val days = trip.contents.filterIsInstance<TripEntry.DayEntry>()
        assertEquals(2, days.size)
        assertEquals(listOf("Day 1", "Day 2"), days.map { it.dayLabel })
        assertEquals(listOf("m-airport", "m-ramiro"), days[0].moments.map { it.id })
        assertEquals(listOf("m-belem"), days[1].moments.map { it.id })
        assertEquals("Cervejaria Ramiro", days[0].moments[1].place)   // subject hydration flows through
    }

    @Test
    fun singleDayTripListsMomentsFlat() {
        val trip = TimelineProjection.trip(
            TripInput("sintra", "Sintra", "Apr 14", "2026", membership = listOf("m-airport", "m-ramiro")),
            moments = tripMoments(),
            places = tripPlaces(),
            zone = TimeZone.UTC,
        )

        assertEquals(1, trip.daysSpan)
        // Single-day trips list their moments flat, oldest-first — no DayEntry chapters.
        assertTrue(trip.contents.all { it is TripEntry.MomentItem })
        assertEquals(
            listOf("m-airport", "m-ramiro"),
            trip.contents.filterIsInstance<TripEntry.MomentItem>().map { it.moment.id },
        )
    }

    @Test
    fun tripRouteFollowsChronologicalVisitPlaceCoordinates() {
        val trip = TimelineProjection.trip(
            TripInput("lisbon", "Lisbon", "Apr 12 – 13", "2026", membership = listOf("m-airport", "m-ramiro", "m-belem")),
            moments = tripMoments(),
            places = tripPlaces(),
            zone = TimeZone.UTC,
        )

        assertEquals(
            listOf(
                GeoPoint(38.7756, -9.1354),
                GeoPoint(38.7267, -9.1357),
                GeoPoint(38.6916, -9.2160),
            ),
            trip.route,
        )
    }

    @Test
    fun tripRouteDropsCoordinatelessPlacesAndCollapsesConsecutiveDuplicates() {
        val moments = listOf(
            MomentInput("a", dayStart, TimelineProjection.Kind.VISIT, "A", 0,
                subjectKind = TimelineProjection.Subject.PLACE, subjectId = "p-a"),
            MomentInput("b", dayStart + hour, TimelineProjection.Kind.VISIT, "B again", 0,
                subjectKind = TimelineProjection.Subject.PLACE, subjectId = "p-a"),   // same place → collapsed
            MomentInput("c", dayStart + 2 * hour, TimelineProjection.Kind.VISIT, "C", 0,
                subjectKind = TimelineProjection.Subject.PLACE, subjectId = "p-nocoord"), // (0,0) → dropped
            MomentInput("d", dayStart + 3 * hour, TimelineProjection.Kind.VISIT, "D", 0,
                subjectKind = TimelineProjection.Subject.PLACE, subjectId = "p-b"),
        )
        val places = listOf(
            PlaceInput("p-a", "A", latE7 = 100_000_000, lngE7 = 100_000_000),
            PlaceInput("p-nocoord", "No coord"),   // latE7/lngE7 default 0
            PlaceInput("p-b", "B", latE7 = 200_000_000, lngE7 = 200_000_000),
        )

        val trip = TimelineProjection.trip(
            TripInput("t", "T", "", "", membership = listOf("a", "b", "c", "d")),
            moments = moments, places = places, zone = TimeZone.UTC,
        )

        assertEquals(listOf(GeoPoint(10.0, 10.0), GeoPoint(20.0, 20.0)), trip.route)
    }

    @Test
    fun tripDropsDanglingMembersAndEmptyMembershipHasHonestEmpties() {
        val withDangling = TimelineProjection.trip(
            TripInput("t", "T", "", "", membership = listOf("m-airport", "ghost")),
            moments = tripMoments(), places = tripPlaces(), zone = TimeZone.UTC,
        )
        assertEquals(1, withDangling.momentsCount)     // "ghost" has no moment → dropped

        val empty = TimelineProjection.trip(TripInput("t", "T", "", ""))
        assertEquals(0, empty.daysSpan)
        assertEquals(0, empty.momentsCount)
        assertEquals(0, empty.placesCount)
        assertEquals(emptyList(), empty.contents)
        assertEquals(emptyList(), empty.route)
        assertNull(empty.year)
    }

    @Test
    fun activityRollupsAggregateByTypeMostSessionsFirst() {
        val rollups = TimelineProjection.activityRollups(
            listOf(
                ActivityCountInput("Running", 2),
                ActivityCountInput("Cycling", 1),
            )
        )

        assertEquals(2, rollups.size)
        assertEquals("Running", rollups[0].kind)   // 2 sessions → first
        assertEquals(2, rollups[0].count)
        assertEquals("Cycling", rollups[1].kind)
        assertEquals(1, rollups[1].count)
    }

    /**
     * A type whose workouts were all deleted decrements to zero; it must leave the surface rather
     * than linger as a "0 sessions" card (the maintained counts are the only signal — there is no
     * workout list left to notice the absence).
     */
    @Test
    fun activityRollupsDropZeroedOutTypes() {
        val rollups = TimelineProjection.activityRollups(
            listOf(
                ActivityCountInput("Running", 3),
                ActivityCountInput("Swimming", 0),
            )
        )

        assertEquals(1, rollups.size)
        assertEquals("Running", rollups[0].kind)
    }

    // ── movement endpoints: the route a movement row renders ("Home → Work") ───

    private val home = PlaceInput("p-home", "Home", latE7 = 481_500_000, lngE7 = 115_860_000)
    private val work = PlaceInput("p-work", "Work", latE7 = 482_000_000, lngE7 = 115_860_000)

    /** A movement whose trace is long enough never to be absorbed into a surrounding merged stay. */
    private fun move(id: String, atMs: Long, durSec: Long, traceId: String = "t-$id") = MomentInput(
        id, atMs, TimelineProjection.Kind.MOVEMENT, "Ride · 8.0 km", durSec,
        subjectKind = TimelineProjection.Subject.TRACE, subjectId = traceId,
    )

    private fun trace(id: String) = TraceInput(id, MovementModes.CYCLE, 8_000)

    private fun momentsOf(
        moments: List<MomentInput>,
        places: List<PlaceInput> = listOf(home, work),
        traces: List<TraceInput> = emptyList(),
        roles: Map<String, String> = emptyMap(),
    ) = TimelineProjection.days(
        moments, places = places, traces = traces, zone = TimeZone.UTC, roles = roles,
    )
        .flatMap { it.moments }
        .associateBy { it.id }

    @Test
    fun movementBetweenTwoVisitsCarriesBothEndpoints() {
        // Home 00:00–01:00, ride 01:00–01:30, Work 01:30–02:30 — contiguous, so both brackets hold.
        val byId = momentsOf(
            listOf(
                visit("v1", dayStart, 3_600, home),
                move("m", dayStart + hour, 1_800),
                visit("v2", dayStart + hour + 1_800_000, 3_600, work),
            ),
            traces = listOf(trace("t-m")),
        )

        assertEquals("Home", byId.getValue("m").originPlace)
        assertEquals("Work", byId.getValue("m").destinationPlace)
        // Visits themselves never carry endpoints — only a movement has a route.
        assertNull(byId.getValue("v1").originPlace)
        assertNull(byId.getValue("v1").destinationPlace)
    }

    @Test
    fun endpointsResolveAcrossADayBoundary() {
        // The night drive that motivated computing endpoints before the day grouping: the movement
        // lands on one calendar day and its destination visit on the next. Bracketing per day would
        // silently drop exactly this case.
        val byId = momentsOf(
            listOf(
                visit("v1", dayStart + 22 * hour, 5_400, home),          // ends 23:30
                move("m", dayStart + 23 * hour + 1_800_000, 3_600),      // 23:30 → 00:30
                visit("v2", dayStart + day + 1_800_000, 3_600, work),    // next day, 00:30
            ),
            traces = listOf(trace("t-m")),
        )

        assertEquals("Home", byId.getValue("m").originPlace)
        assertEquals("Work", byId.getValue("m").destinationPlace)
    }

    @Test
    fun aCaptureHoleLeavesTheEndpointUnknownRatherThanGuessing() {
        // The visit on the far side of a two-hour hole is not where this trip started (an OEM killed
        // the collector, GPS dropped indoors). Naming it anyway would be a confident wrong answer,
        // so the row falls back to its bare mode instead.
        val byId = momentsOf(
            listOf(
                visit("v1", dayStart, 3_600, home),                        // ends 01:00
                move("m", dayStart + 3 * hour, 1_800),                     // starts 03:00 — 2 h gap
                visit("v2", dayStart + 3 * hour + 1_800_000, 3_600, work), // contiguous on the far side
            ),
            traces = listOf(trace("t-m")),
        )

        assertNull(byId.getValue("m").originPlace)
        assertEquals("Work", byId.getValue("m").destinationPlace)
    }

    @Test
    fun consecutiveMovementsResolveNoEndpointOnTheirInnerSides() {
        // Home → (ride) → (ride) → Work. Scanning past the intervening movement would collapse an
        // unrecorded stop into "Home → Work"; each leg only claims the bracket it actually touches.
        val byId = momentsOf(
            listOf(
                visit("v1", dayStart, 3_600, home),
                move("m1", dayStart + hour, 1_800, traceId = "t1"),
                move("m2", dayStart + hour + 1_800_000, 1_800, traceId = "t2"),
                visit("v2", dayStart + 2 * hour, 3_600, work),
            ),
            traces = listOf(trace("t1"), trace("t2")),
        )

        assertEquals("Home", byId.getValue("m1").originPlace)
        assertNull(byId.getValue("m1").destinationPlace)
        assertNull(byId.getValue("m2").originPlace)
        assertEquals("Work", byId.getValue("m2").destinationPlace)
    }

    @Test
    fun anUnnamedBracketingPlaceCountsAsUnknown() {
        // A not-yet-geocoded place resolves to "" — a route rendered "→ " reads worse than the mode.
        val unnamed = PlaceInput("p-blank", "", latE7 = 482_000_000, lngE7 = 115_860_000)
        val byId = momentsOf(
            listOf(
                visit("v1", dayStart, 3_600, home),
                move("m", dayStart + hour, 1_800),
                visit("v2", dayStart + hour + 1_800_000, 3_600, unnamed),
            ),
            places = listOf(home, unnamed),
            traces = listOf(trace("t-m")),
        )

        assertEquals("Home", byId.getValue("m").originPlace)
        assertNull(byId.getValue("m").destinationPlace)
    }

    @Test
    fun anIsolatedMovementHasNoEndpointsAtAll() {
        val byId = momentsOf(listOf(move("m", dayStart, 1_800)), traces = listOf(trace("t-m")))

        assertNull(byId.getValue("m").originPlace)
        assertNull(byId.getValue("m").destinationPlace)
    }

    // ── place roles: a confirmed role stands in for the place's name (D9) ─────

    @Test
    fun aConfirmedRoleReplacesThePlaceNameOnAVisit() {
        val byId = momentsOf(
            listOf(visit("v1", dayStart, 3_600, home)),
            places = listOf(home),
            roles = mapOf(home.id to "home"),
        )

        // The token, not a display name — the UI localises it. The label stays available as the
        // fallback for every place that has no role.
        assertEquals("home", byId.getValue("v1").placeRole)
        assertEquals("Home", byId.getValue("v1").place)
    }

    @Test
    fun movementEndpointsCarryTheirRolesAlongsideTheirNames() {
        val byId = momentsOf(
            listOf(
                visit("v1", dayStart, 3_600, home),
                move("m", dayStart + hour, 1_800),
                visit("v2", dayStart + hour + 1_800_000, 3_600, work),
            ),
            traces = listOf(trace("t-m")),
            roles = mapOf(home.id to "home", work.id to "work"),
        )

        val m = byId.getValue("m")
        assertEquals("home", m.originRole)
        assertEquals("work", m.destinationRole)
        // ⚠ The names survive too — the UI falls back to them for any role it cannot name.
        assertEquals("Home", m.originPlace)
        assertEquals("Work", m.destinationPlace)
    }

    @Test
    fun aPlaceWithNoRoleCarriesNone() {
        val byId = momentsOf(
            listOf(visit("v1", dayStart, 3_600, home)),
            places = listOf(home),
            roles = emptyMap(),
        )

        assertNull(byId.getValue("v1").placeRole)
    }

    // ── in-ride station-stop collapse: one train ride, not ride/stay/ride ──────
    //
    // `minDwellMs` is 5 min and a station stop is routinely exactly that, so capture writes a Visit
    // AND splits the journey into two traces nothing ever rejoins. Every negative test below asserts
    // its own precondition — it re-runs the fixture with the one guard restored and proves that twin
    // DOES collapse, so a test can never pass merely by failing to reach the collapse decision.

    private val minute = 60_000L
    private val stationA = PlaceInput("p-stn-a", "Hauptbahnhof")
    private val stationB = PlaceInput("p-stn-b", "Ostbahnhof")

    private fun leg(id: String, atMs: Long, durationMs: Long, traceId: String = "t-$id") = MomentInput(
        id, atMs, TimelineProjection.Kind.MOVEMENT,
        // The stored, per-leg title — what a collapsed ride must NOT keep rendering.
        "Transit · 20.0 km", durationMs / 1000,
        subjectKind = TimelineProjection.Subject.TRACE, subjectId = traceId,
    )

    /**
     * Home → train → [stop] → train → Work. Each parameter varies exactly one collapse guard, so a
     * negative test and its precondition twin differ in one place only.
     */
    private fun rideThroughAStop(
        stopMin: Long = 8,
        stopPlace: PlaceInput = stationA,
        secondLegMode: String = MovementModes.TRANSIT,
        holeBeforeStopMin: Long = 0,
        holeAfterStopMin: Long = 0,
    ): Pair<List<MomentInput>, List<TraceInput>> {
        val leg1Start = dayStart + hour                    // 01:00
        val leg1End = leg1Start + 40 * minute              // 01:40
        val stopStart = leg1End + holeBeforeStopMin * minute
        val stopEnd = stopStart + stopMin * minute
        val leg2Start = stopEnd + holeAfterStopMin * minute
        val leg2End = leg2Start + 35 * minute
        return listOf(
            visit("v-home", dayStart, 3_600, home),        // 00:00–01:00
            leg("l1", leg1Start, leg1End - leg1Start),
            visit("v-stop", stopStart, stopMin * 60, stopPlace),
            leg("l2", leg2Start, leg2End - leg2Start),
            visit("v-work", leg2End, 3_600, work),
        ) to listOf(
            TraceInput("t-l1", MovementModes.TRANSIT, 20_000),
            TraceInput("t-l2", secondLegMode, 14_200),
        )
    }

    private fun projected(
        fixture: Pair<List<MomentInput>, List<TraceInput>>,
        places: List<PlaceInput> = listOf(home, work, stationA, stationB),
        rolePlaceIds: Set<String> = emptySet(),
        ongoingArrivalMs: Long? = null,
    ) = TimelineProjection.days(
        fixture.first, places = places, traces = fixture.second, zone = TimeZone.UTC,
        rolePlaceIds = rolePlaceIds, ongoingArrivalMs = ongoingArrivalMs,
        nowMs = dayStart + 4 * hour,
    ).flatMap { it.moments }

    @Test
    fun aShortAnonymousStopBetweenTwoTransitLegsCollapsesIntoOneRide() {
        val rows = projected(rideThroughAStop(stopMin = 8))

        // Home, ONE ride, Work — the station row is gone.
        assertEquals(listOf("v-home", "l1", "v-work"), rows.map { it.id })
        val ride = rows[1]
        assertEquals(MomentKind.Movement, ride.kind)
        assertEquals(MovementModes.TRANSIT, ride.movementMode)

        // ⚠⚠ The title is REGENERATED, not inherited: keeping the first leg's stored title would
        // print one leg's distance on the card beside the combined ride's meta line.
        assertEquals("Transit · 34.2 km", ride.title)
        assertEquals(34_200L, ride.distanceM)               // 20.0 + 14.2 km
        assertEquals(83, ride.durationMin)                  // 40 + 8 (the stop) + 35 min

        // ⚠ The hero must draw the whole journey — both legs, in time order.
        assertEquals(listOf("t-l1", "t-l2"), ride.traceIds)
        assertEquals("t-l1", ride.traceId)

        // Endpoints bracket the ride against the real origin/destination, not against the station —
        // which only works because the collapse runs BEFORE movementEndpoints.
        assertEquals("Home", ride.originPlace)
        assertEquals("Work", ride.destinationPlace)
    }

    // ── §33.6: two legs with NO stop between them ──────────────────────────────

    /**
     * Home → train → train → Work, the two legs **contiguous**, no dwell between.
     *
     * ⚠⚠ This is the shape `ModeCoalescer` leaves behind. It decides whether to join runs at CAPTURE
     * time, before any tile is available, so a ride whose runs classify `drive` then `transit` is
     * split — and the rail sweep, which later agrees both are `transit`, can relabel a trace but
     * cannot un-split one. Measured on the 2026-08-14 Mühldorf → München Ost journey.
     */
    private fun rideSplitWithNoStop(
        gapMin: Long = 0,
        secondLegMode: String = MovementModes.TRANSIT,
        secondLegVersion: Int = 0,
        firstLegVersion: Int = 0,
    ): Pair<List<MomentInput>, List<TraceInput>> {
        val leg1Start = dayStart + hour
        val leg1End = leg1Start + 40 * minute
        val leg2Start = leg1End + gapMin * minute
        val leg2End = leg2Start + 35 * minute
        return listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", leg1Start, leg1End - leg1Start),
            leg("l2", leg2Start, leg2End - leg2Start),
            visit("v-work", leg2End, 3_600, work),
        ) to listOf(
            TraceInput("t-l1", MovementModes.TRANSIT, 20_000, classifierVersion = firstLegVersion),
            TraceInput("t-l2", secondLegMode, 14_200, classifierVersion = secondLegVersion),
        )
    }

    @Test
    fun twoContiguousLegsOfTheSameModeCollapseIntoOneRide() {
        val rows = projected(rideSplitWithNoStop())

        assertEquals(listOf("v-home", "l1", "v-work"), rows.map { it.id })
        val ride = rows[1]
        assertEquals("Transit · 34.2 km", ride.title)
        assertEquals(34_200L, ride.distanceM)
        assertEquals(75, ride.durationMin)                 // 40 + 35, no stop to add
        assertEquals(listOf("t-l1", "t-l2"), ride.traceIds)
        assertEquals("Home", ride.originPlace)
        assertEquals("Work", ride.destinationPlace)
    }

    @Test
    fun legsSeparatedByACaptureHoleAreNotCollapsed() {
        // Precondition: contiguous, the same fixture collapses.
        assertEquals(3, projected(rideSplitWithNoStop()).size, "precondition: contiguous collapses")

        // ⚠ A long hole with no dwell recorded is capture missing something, not one ride. Folding
        // across it would invent a continuity nobody observed.
        val rows = projected(rideSplitWithNoStop(gapMin = 90))
        assertEquals(listOf("v-home", "l1", "l2", "v-work"), rows.map { it.id })
    }

    @Test
    fun twoContiguousLegsOfDifferentModesAreNotCollapsed() {
        assertEquals(3, projected(rideSplitWithNoStop()).size, "precondition: same mode collapses")

        // A walk that begins the instant a train leg ends is a different thing, not more of the ride.
        val rows = projected(rideSplitWithNoStop(secondLegMode = MovementModes.WALK))
        assertEquals(listOf("v-home", "l1", "l2", "v-work"), rows.map { it.id })
    }

    @Test
    fun aVehicleChangeIsStillTwoRowsEvenWithNoStopBetween() {
        // ⚠⚠ §32.12 / D-T1 survives the new shape: an underground leg beside a surface one is a
        // TRANSFER, and folding it would hide the interchange exactly as the old bug did — now with
        // no station row left to notice its absence.
        assertEquals(3, projected(rideSplitWithNoStop()).size, "precondition: same-tier collapses")

        val rows = projected(
            rideSplitWithNoStop(
                firstLegVersion = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED,
                secondLegVersion = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND,
            ),
        )
        assertEquals(listOf("v-home", "l1", "l2", "v-work"), rows.map { it.id })
    }

    @Test
    fun aStopAndAnAdjacentLegFoldTogetherInOneWalk() {
        // ⭐ The real journey's shape: leg → stop → leg → leg. The walk must handle both strides in
        // one pass, which is what the old `(i..last step 2)` leg collection could not express.
        val leg1Start = dayStart + hour
        val leg1End = leg1Start + 20 * minute
        val stopEnd = leg1End + 6 * minute
        val leg2End = stopEnd + 20 * minute
        val leg3End = leg2End + 30 * minute
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", leg1Start, leg1End - leg1Start),
            visit("v-stop", leg1End, 6 * 60, stationA),
            leg("l2", stopEnd, leg2End - stopEnd),
            leg("l3", leg2End, leg3End - leg2End),
            visit("v-work", leg3End, 3_600, work),
        )
        val traces = listOf(
            TraceInput("t-l1", MovementModes.TRANSIT, 10_000),
            TraceInput("t-l2", MovementModes.TRANSIT, 10_000),
            TraceInput("t-l3", MovementModes.TRANSIT, 14_200),
        )
        val rows = projected(moments to traces)

        assertEquals(listOf("v-home", "l1", "v-work"), rows.map { it.id })
        assertEquals(34_200L, rows[1].distanceM)
        assertEquals(listOf("t-l1", "t-l2", "t-l3"), rows[1].traceIds)
    }

    /**
     * §36.5 — **a folded ride ends where its LAST leg ended.**
     *
     * ⚠⚠ The reported defect, and it survived §33.6 because [CollapsedRide] recomputes the mode, the
     * distance and the trace ids but took its *endpoints* from `representative`, which is only the
     * **first** leg. On the maintainer's Mühldorf → München Ost journey that produced the whole
     * journey's length under the name of the first station it passed:
     * `"Mühldorf (Oberbay) → Schwindegg · 82.5 km"`.
     *
     * ⚠ The origin must keep coming from the **first** leg, which is why both ends are asserted — a
     * fix that simply moved `representative` to the last leg would pass a destination-only test and
     * break the origin in exactly the mirror-image way.
     */
    @Test
    fun aFoldedRideIsNamedByItsFirstOriginAndItsLastDestination() {
        val leg1Start = dayStart + hour
        val leg1End = leg1Start + 20 * minute
        val stopEnd = leg1End + 6 * minute
        val leg2End = stopEnd + 20 * minute
        val leg3End = leg2End + 30 * minute
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", leg1Start, leg1End - leg1Start),
            visit("v-stop", leg1End, 6 * 60, stationA),
            leg("l2", stopEnd, leg2End - stopEnd),
            leg("l3", leg2End, leg3End - leg2End),
            visit("v-work", leg3End, 3_600, work),
        )
        val traces = listOf(
            TraceInput(
                "t-l1", MovementModes.TRANSIT, 10_000,
                originStop = "Mühldorf (Oberbay)", destinationStop = "Schwindegg",
            ),
            TraceInput(
                "t-l2", MovementModes.TRANSIT, 10_000,
                originStop = "Schwindegg", destinationStop = "Markt Schwaben",
            ),
            TraceInput(
                "t-l3", MovementModes.TRANSIT, 14_200,
                originStop = "Markt Schwaben", destinationStop = "Ostbahnhof",
            ),
        )

        val rows = projected(moments to traces)

        assertEquals(listOf("v-home", "l1", "v-work"), rows.map { it.id }, "precondition: one ride")
        val ride = rows[1]
        assertEquals(34_200L, ride.distanceM, "the whole journey's length")
        assertEquals("Mühldorf (Oberbay)", ride.originPlace)
        assertEquals("Ostbahnhof", ride.destinationPlace)
    }

    @Test
    fun anUncollapsedLegStillNamesItsOwnTwoEnds() {
        // ⚠ The other half of the same change: with `ride == null` both ends resolve to the one
        // trace, exactly as before. Without this an "always read the last leg" fix would look
        // correct on the fold and quietly break every ordinary row.
        val leg1Start = dayStart + hour
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", leg1Start, 20 * minute),
            visit("v-work", leg1Start + 20 * minute, 3_600, work),
        )
        val traces = listOf(
            TraceInput(
                "t-l1", MovementModes.TRANSIT, 10_000,
                originStop = "Mühldorf (Oberbay)", destinationStop = "Schwindegg",
            ),
        )

        val ride = projected(moments to traces).single { it.id == "l1" }

        assertEquals("Mühldorf (Oberbay)", ride.originPlace)
        assertEquals("Schwindegg", ride.destinationPlace)
    }

    @Test
    fun aStopLongerThanTheStationBoundIsNotCollapsed() {
        // Precondition: the identical fixture with a short stop reaches the decision and collapses.
        assertEquals(3, projected(rideThroughAStop(stopMin = 8)).size, "precondition: short stop collapses")

        val rows = projected(rideThroughAStop(stopMin = 25))

        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.id })
        assertEquals(20_000L, rows[1].distanceM)
        assertEquals(14_200L, rows[3].distanceM)
    }

    @Test
    fun twoLegsOfDifferentModesAreNotCollapsed() {
        // Precondition: the identical fixture with both legs on transit collapses.
        assertEquals(3, projected(rideThroughAStop()).size, "precondition: two transit legs collapse")

        // v1 is transit-only. A 5-minute stop during a DRIVE is far more likely a real errand.
        val rows = projected(rideThroughAStop(secondLegMode = MovementModes.DRIVE))

        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.id })
        assertEquals(MovementModes.DRIVE, rows[3].movementMode)
    }

    @Test
    fun twoDriveLegsAroundAShortStopAreNotCollapsed() {
        // The symmetric case: same mode on both legs, but not transit. Nothing here is a station.
        val bothDrive = rideThroughAStop(secondLegMode = MovementModes.DRIVE).let { (moments, traces) ->
            moments to listOf(TraceInput("t-l1", MovementModes.DRIVE, 20_000), traces[1])
        }
        // Precondition: flipping BOTH legs to transit — and nothing else — collapses.
        assertEquals(3, projected(rideThroughAStop()).size, "precondition: the transit twin collapses")

        assertEquals(5, projected(bothDrive).size)
    }

    @Test
    fun aStopAtAUserNamedPlaceIsNotCollapsed() {
        // Precondition: the same place with a geocoded label collapses.
        assertEquals(
            3, projected(rideThroughAStop(stopPlace = stationA)).size,
            "precondition: the geocoded twin collapses",
        )

        // The user named this place themselves — it is meaningful by definition.
        val named = stationA.copy(labelSource = LabelSource.USER)
        val rows = projected(
            rideThroughAStop(stopPlace = named),
            places = listOf(home, work, named, stationB),
        )

        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.id })
    }

    @Test
    fun aStopAtAPlaceCarryingARoleIsNotCollapsed() {
        // Precondition: without the role, the identical fixture collapses.
        assertEquals(3, projected(rideThroughAStop()).size, "precondition: the role-free twin collapses")

        // ⚠ Any role, confirmed or still only a suggestion — never swallow the user's Home.
        val rows = projected(rideThroughAStop(), rolePlaceIds = setOf(stationA.id))

        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.id })
    }

    @Test
    fun aStopAtAnUnknownPlaceIsNotCollapsed() {
        // Fail closed: with no place row we cannot prove the stop is a nowhere, so it survives.
        assertEquals(3, projected(rideThroughAStop()).size, "precondition: the known-place twin collapses")

        val rows = projected(rideThroughAStop(), places = listOf(home, work))

        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.id })
    }

    @Test
    fun theStopTheUserIsCurrentlyAtIsNotCollapsed() {
        val (moments, _) = rideThroughAStop()
        val stopArrival = moments.single { it.id == "v-stop" }.timestampMs
        // Precondition: with no ongoing state, the identical fixture collapses.
        assertEquals(3, projected(rideThroughAStop()).size, "precondition: the not-ongoing twin collapses")

        // Swallowing "you are here now" would be the worst available failure.
        val rows = projected(rideThroughAStop(), ongoingArrivalMs = stopArrival)

        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.id })
        assertTrue(rows[2].isCurrent)
    }

    @Test
    fun aCaptureHoleAroundTheStopBlocksTheCollapse() {
        // 45 minutes of missing fixes between the leg and the stop: we cannot claim this stay is the
        // *same journey's* station rather than a separate errand, so the legs stay separate. Same
        // tolerance movementEndpoints uses to refuse to name an endpoint across a hole.
        assertEquals(3, projected(rideThroughAStop()).size, "precondition: the contiguous twin collapses")

        val rows = projected(rideThroughAStop(holeBeforeStopMin = 45))

        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.id })
    }

    @Test
    fun aCaptureHoleAfterTheStopBlocksTheCollapseToo() {
        // The symmetric half: fixes resume 45 minutes after the stay ended. The second leg is not
        // demonstrably the continuation of the first, so it stays its own ride.
        assertEquals(3, projected(rideThroughAStop()).size, "precondition: the contiguous twin collapses")

        val rows = projected(rideThroughAStop(holeAfterStopMin = 45))

        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.id })
    }

    @Test
    fun aGenuineStayBetweenTwoRidesSurvives() {
        // The case the guards exist for: a real 45-minute lunch, at a café the user named, between
        // two train legs. Both legs keep their own identity, distance and trace.
        val cafe = PlaceInput("p-cafe", "Kaffeehaus", labelSource = LabelSource.USER)
        assertEquals(3, projected(rideThroughAStop()).size, "precondition: an anonymous short stop collapses")

        val rows = projected(
            rideThroughAStop(stopMin = 45, stopPlace = cafe),
            places = listOf(home, work, cafe),
        )

        assertEquals(listOf("v-home", "l1", "v-stop", "l2", "v-work"), rows.map { it.id })
        assertEquals("Kaffeehaus", rows[2].place)
        assertEquals(45, rows[2].durationMin)
        assertEquals(listOf("t-l1"), rows[1].traceIds)
        assertEquals(listOf("t-l2"), rows[3].traceIds)
    }

    @Test
    fun threeLegsWithTwoPassThroughStopsCollapseIntoOneRide() {
        val l1Start = dayStart + hour
        val l1End = l1Start + 30 * minute
        val s1End = l1End + 6 * minute
        val l2End = s1End + 25 * minute
        val s2End = l2End + 7 * minute
        val l3End = s2End + 20 * minute
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", l1Start, l1End - l1Start),
            visit("s1", l1End, 6 * 60, stationA),
            leg("l2", s1End, l2End - s1End),
            visit("s2", l2End, 7 * 60, stationB),
            leg("l3", s2End, l3End - s2End),
            visit("v-work", l3End, 3_600, work),
        )
        val traces = listOf(
            TraceInput("t-l1", MovementModes.TRANSIT, 10_000),
            TraceInput("t-l2", MovementModes.TRANSIT, 30_000),
            TraceInput("t-l3", MovementModes.TRANSIT, 5_500),
        )

        val rows = projected(moments to traces)

        assertEquals(listOf("v-home", "l1", "v-work"), rows.map { it.id })
        val ride = rows[1]
        assertEquals(45_500L, ride.distanceM)
        assertEquals("Transit · 45.5 km", ride.title)
        assertEquals(88, ride.durationMin)                        // 30 + 6 + 25 + 7 + 20
        assertEquals(listOf("t-l1", "t-l2", "t-l3"), ride.traceIds)
        assertEquals("Home", ride.originPlace)
        assertEquals("Work", ride.destinationPlace)
    }

    // ── §32.14: a transit leg is named by the stops it ran between ─────────────

    @Test
    fun aTransitLegIsNamedByItsStopsNotByTheDwellsThatBracketIt() {
        // ⚠⚠ The reported shape. The bracketing dwells were a signal hold 2.5 km short of Ostbahnhof
        // and a stay 200 m from Odeonsplatz — so the row read "Transit to <street>", naming
        // the leg after a street the rider walked down rather than the stations they rode between.
        val l1Start = dayStart + hour
        val l1End = l1Start + 15 * minute
        val moments = listOf(
            visit("v-a", dayStart, 3_600, stationA),
            leg("l1", l1Start, l1End - l1Start),
            visit("v-b", l1End, 3_600, work),
        )
        val traces = listOf(
            TraceInput(
                "t-l1", MovementModes.TRANSIT, 4_000,
                originStop = "München Ost",
                destinationStop = "Odeonsplatz",
            ),
        )

        val ride = projected(moments to traces).single { it.id == "l1" }
        assertEquals("München Ost", ride.originPlace)
        assertEquals("Odeonsplatz", ride.destinationPlace)
    }

    @Test
    fun aWalkIsNeverRenamedAfterANearbyStation() {
        // ⚠⚠ The twin, and the reason the 300 m radius is survivable at all: measured, an office sits
        // 252 m from a station. Only the transit gate keeps a walk that finishes near a station
        // from being named after it — so a non-transit leg must ignore stops even when they are set.
        val l1Start = dayStart + hour
        val l1End = l1Start + 15 * minute
        val moments = listOf(
            visit("v-a", dayStart, 3_600, home),
            leg("l1", l1Start, l1End - l1Start),
            visit("v-b", l1End, 3_600, work),
        )
        val traces = listOf(
            TraceInput(
                "t-l1", MovementModes.WALK, 400,
                originStop = "München Ost",
                destinationStop = "Odeonsplatz",
            ),
        )

        val walk = projected(moments to traces).single { it.id == "l1" }
        assertEquals("Home", walk.originPlace)
        assertEquals("Work", walk.destinationPlace)
    }

    @Test
    fun aTransitLegWithNoStopInRangeKeepsItsDwellEndpoints() {
        // The fallback the maintainer chose: blank stops change nothing.
        val l1Start = dayStart + hour
        val l1End = l1Start + 15 * minute
        val moments = listOf(
            visit("v-a", dayStart, 3_600, home),
            leg("l1", l1Start, l1End - l1Start),
            visit("v-b", l1End, 3_600, work),
        )
        val traces = listOf(TraceInput("t-l1", MovementModes.TRANSIT, 40_000))

        val ride = projected(moments to traces).single { it.id == "l1" }
        assertEquals("Home", ride.originPlace)
        assertEquals("Work", ride.destinationPlace)
    }

    // ── §32.12: a change of vehicle is two rows (maintainer decision) ──────────

    @Test
    fun aTransferToTheMetroIsTwoRowsNotOne() {
        // ⚠⚠ The measured defect. With an ordinary 6-minute connection wait, train + wait + U-Bahn
        // folded into ONE row and the interchange row was deleted outright. Both legs are `transit`,
        // so the mode cannot separate them; the classifier version can.
        val l1Start = dayStart + hour
        val l1End = l1Start + 60 * minute
        val sEnd = l1End + 6 * minute
        val l2End = sEnd + 9 * minute
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", l1Start, l1End - l1Start),
            visit("s1", l1End, 6 * 60, stationA),
            leg("l2", sEnd, l2End - sEnd),
            visit("v-work", l2End, 3_600, work),
        )
        val traces = listOf(
            TraceInput("t-l1", MovementModes.TRANSIT, 70_000, classifierVersion = MOVEMENT_CLASSIFIER_VERSION),
            TraceInput(
                "t-l2",
                MovementModes.TRANSIT,
                4_000,
                classifierVersion = MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND,
            ),
        )

        val rows = projected(moments to traces)

        assertEquals(listOf("v-home", "l1", "s1", "l2", "v-work"), rows.map { it.id })
        assertEquals(70_000L, rows[1].distanceM)
        assertEquals(4_000L, rows[3].distanceM)
    }

    @Test
    fun theSweepConfirmedMetroAlsoBlocksTheFold() {
        // The same, once the rail sweep has been over both legs: it stamps the surface leg
        // RAIL_REFINED and the covered one RAIL_REFINED_UNDERGROUND, so the distinction survives
        // refinement rather than being erased by it.
        val l1Start = dayStart + hour
        val l1End = l1Start + 60 * minute
        val sEnd = l1End + 6 * minute
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", l1Start, l1End - l1Start),
            visit("s1", l1End, 6 * 60, stationA),
            leg("l2", sEnd, 9 * minute),
            visit("v-work", sEnd + 9 * minute, 3_600, work),
        )
        val traces = listOf(
            TraceInput(
                "t-l1",
                MovementModes.TRANSIT,
                70_000,
                classifierVersion = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED,
            ),
            TraceInput(
                "t-l2",
                MovementModes.TRANSIT,
                4_000,
                classifierVersion = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND,
            ),
        )

        assertEquals(listOf("v-home", "l1", "s1", "l2", "v-work"), projected(moments to traces).map { it.id })
    }

    @Test
    fun twoSurfaceLegsStillCollapseAcrossAPassThroughStop() {
        // ⚠⚠ The twin, and the one that keeps Schwindegg working: same shape, same stop, both legs
        // surface rail. A guard that blocked on "the versions differ at all" — or on nothing being
        // known — would fold this into two rows and undo the whole point of the collapse.
        val l1Start = dayStart + hour
        val l1End = l1Start + 30 * minute
        val sEnd = l1End + 7 * minute
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", l1Start, l1End - l1Start),
            visit("s1", l1End, 7 * 60, stationA),
            leg("l2", sEnd, 25 * minute),
            visit("v-work", sEnd + 25 * minute, 3_600, work),
        )
        val traces = listOf(
            TraceInput("t-l1", MovementModes.TRANSIT, 22_300, classifierVersion = MOVEMENT_CLASSIFIER_VERSION),
            TraceInput(
                "t-l2",
                MovementModes.TRANSIT,
                48_900,
                classifierVersion = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED,
            ),
        )

        val rows = projected(moments to traces)
        assertEquals(listOf("v-home", "l1", "v-work"), rows.map { it.id })
        assertEquals(71_200L, rows[1].distanceM)
    }

    @Test
    fun anUnknownVersionFoldsExactlyAsItDidBefore() {
        // ⚠ Fails toward folding: a leg the sweep has not reached, or a row from before §32.12, must
        // not change the feed it was already producing. Only a POSITIVE disagreement blocks.
        val l1Start = dayStart + hour
        val l1End = l1Start + 30 * minute
        val sEnd = l1End + 6 * minute
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", l1Start, l1End - l1Start),
            visit("s1", l1End, 6 * 60, stationA),
            leg("l2", sEnd, 25 * minute),
            visit("v-work", sEnd + 25 * minute, 3_600, work),
        )
        val traces = listOf(
            TraceInput("t-l1", MovementModes.TRANSIT, 22_300), // classifierVersion defaults to 0
            TraceInput(
                "t-l2",
                MovementModes.TRANSIT,
                48_900,
                classifierVersion = MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND,
            ),
        )

        assertEquals(listOf("v-home", "l1", "v-work"), projected(moments to traces).map { it.id })
    }

    @Test
    fun aStationStopCaptureSplitIntoFragmentsStillCollapses() {
        // Ordering proof: the visit merge runs FIRST, so two dwell fragments at one station present
        // as a single 11-minute span to the collapse — under the bound. Judged fragment-by-fragment
        // this would have been two stops that never even form the movement/visit/movement shape.
        val l1Start = dayStart + hour
        val l1End = l1Start + 30 * minute
        val frag2Start = l1End + 6 * minute
        val stopEnd = frag2Start + 5 * minute
        val l2End = stopEnd + 25 * minute
        val moments = listOf(
            visit("v-home", dayStart, 3_600, home),
            leg("l1", l1Start, l1End - l1Start),
            visit("f1", l1End, 6 * 60, stationA),
            visit("f2", frag2Start, 5 * 60, stationA),
            leg("l2", stopEnd, l2End - stopEnd),
            visit("v-work", l2End, 3_600, work),
        )
        val traces = listOf(
            TraceInput("t-l1", MovementModes.TRANSIT, 10_000),
            TraceInput("t-l2", MovementModes.TRANSIT, 30_000),
        )

        val rows = projected(moments to traces)

        assertEquals(listOf("v-home", "l1", "v-work"), rows.map { it.id })
        assertEquals(40_000L, rows[1].distanceM)
        assertEquals(66, rows[1].durationMin)                     // 30 + 11 (both fragments) + 25
    }

    @Test
    fun anOrdinaryMovementCarriesItsSingleTraceAsAList() {
        // The hero reads `traceIds` for every movement, not only collapsed ones.
        val byId = momentsOf(listOf(move("m", dayStart, 1_800)), traces = listOf(trace("t-m")))

        assertEquals(listOf("t-m"), byId.getValue("m").traceIds)
        assertEquals("t-m", byId.getValue("m").traceId)
    }
}
