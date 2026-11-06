@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.fold
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordWorkout
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.RoleSource
import org.tenshu.core.place.recordPlace
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.SnappedRoute
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.recordMoment
import org.tenshu.core.timeline.recordMovementTrace
import org.tenshu.core.timeline.recordSnappedRoute
import org.tenshu.health.client.healthService
import org.tenshu.place.client.placeService
import org.tenshu.timeline.client.timelineService

/**
 * Boundary-contract tests (`docs/testing.md` §9.3) for the projected half of `Timeline` —
 * web-app-suite Wave 2, Timeline run A — against the **real** in-process runtime, with moments,
 * trips, places and workouts seeded through the real service ops.
 *
 * ⚠ `GlobalScope.promise`, not `runTest`: the seeding calls the service ops directly, and on
 * `runTest`'s test dispatcher they never complete ("This job has not completed yet").
 *
 * ⚠ The jsTest suite shares one `JsRuntime`, so every test seeds its own uniquely-named rows and
 * looks only at those. ⚠ Coordinates are chosen so tests cannot disturb each other: every
 * "ordinary" place sits within ~50 km of (10°, 10°) on its own ~1 km cell (no cross-test
 * reconciliation, and never "away" from any home), and only the trip-suggestion test seeds far away.
 *
 * They assert the keys `web/sdk/src/timeline.ts` publishes and the values the native
 * `TimelineProjectionRepository` would produce — not "returns something".
 */
class TimelineWebBridgeTest : SdkRuntimeTest() {

    private val client get() = JsRuntime.tenshuClient
    private val ctx get() = client.environment.tContext

    private fun stamp() = "${Date.now().toLong()}-${(0..99999).random()}"

    /** A fresh ~1 km cell near (10°, 10°): far enough from every other test's for no fold. */
    private fun cell(): Pair<Long, Long> =
        (100_000_000L + (0..400).random() * 10_000L) to (100_000_000L + (0..400).random() * 10_000L)

    private fun assertOk(result: TResult<Data?>, what: String) =
        result.fold(onSuccess = {}, onFailure = { throw AssertionError("$what failed: $it") })

    private suspend fun seedPlace(
        id: String,
        label: String = "",
        at: Pair<Long, Long> = cell(),
        labelSource: String = LabelSource.GEOCODED,
    ) = assertOk(
        client.placeService.recordPlace(
            Place.create(ctx, id, at.first, at.second, UnixInstantDto(0L), label = label, labelSource = labelSource),
        ),
        "recordPlace $id",
    )

    private suspend fun seedMoment(
        id: String,
        atMs: Long,
        kind: String,
        subjectKind: String,
        subjectId: String,
        durationSec: Long = 3600,
    ) = assertOk(
        client.timelineService.recordMoment(
            Moment.create(
                ctx, id, UnixInstantDto(atMs), kind, title = kind,
                subject = SubjectRef.create(ctx, subjectKind, subjectId),
                durationSec = durationSec, sources = listOf("capture"),
            ),
        ),
        "recordMoment $id",
    )

    private suspend fun seedVisit(id: String, atMs: Long, placeId: String, durationSec: Long = 3600) =
        seedMoment(id, atMs, "Visit", SubjectKind.PLACE, placeId, durationSec)

    private suspend fun feed(zone: String = "UTC"): dynamic {
        val result = Timeline.feed(zone).await()
        assertEquals(true, result.ok, "feed() must succeed; got ${JSON.stringify(result)}")
        return result
    }

    private fun rowsOf(feed: dynamic): List<dynamic> =
        feed.days.unsafeCast<Array<dynamic>>().flatMap { it.moments.unsafeCast<Array<dynamic>>().toList() }

    private suspend fun row(momentId: String, zone: String = "UTC"): dynamic =
        rowsOf(feed(zone)).firstOrNull { it.momentId == momentId }

    private suspend fun places(): List<dynamic> {
        val result = Timeline.places().await()
        assertEquals(true, result.ok, "places() must succeed; got ${JSON.stringify(result)}")
        return result.places.unsafeCast<Array<dynamic>>().toList()
    }

    private suspend fun placeDetail(placeId: String): dynamic {
        val result = Timeline.place(placeId).await()
        assertEquals(true, result.ok, "place() must succeed; got ${JSON.stringify(result)}")
        return result.place
    }

    // 2026-03-10T06:30Z = 23:30 on the 9th in Los Angeles (PDT); +1 h is 00:30 on the 10th.
    private val lateEvening = 1_773_124_200_000L

    @Test
    fun dayGrouping_splitsAtLocalMidnight_inANonUtcZone() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val p = "pl-day-$s"
        val q = "pl-day2-$s"
        seedPlace(p, "Bar $s")
        seedPlace(q, "Diner $s")
        seedVisit("mo-eve-$s", lateEvening, p, durationSec = 7200)
        seedVisit("mo-late-$s", lateEvening + 3_600_000, q)

        val la = feed("America/Los_Angeles")
        val dayOf = { id: String ->
            la.days.unsafeCast<Array<dynamic>>().first { d ->
                d.moments.unsafeCast<Array<dynamic>>().any { it.momentId == id }
            }.date as String
        }
        assertEquals("2026-03-09", dayOf("mo-eve-$s"), "a stay across midnight belongs to the day it began")
        assertEquals("2026-03-10", dayOf("mo-late-$s"))

        val utc = feed("UTC").days.unsafeCast<Array<dynamic>>()
        val utcDay = utc.first { d -> d.moments.unsafeCast<Array<dynamic>>().any { it.momentId == "mo-eve-$s" } }
        assertEquals("2026-03-10", utcDay.date, "the same rows in UTC fall on one day — the zone is load-bearing")
        val ids = utcDay.moments.unsafeCast<Array<dynamic>>().map { it.momentId as String }
        assertTrue(ids.indexOf("mo-eve-$s") < ids.indexOf("mo-late-$s"), "oldest first within a day: $ids")
        val dates = utc.map { it.date as String }
        assertEquals(dates.sortedDescending(), dates, "newest day first")
    }

    @Test
    fun aVisitResolvesItsPlaceName_andARenameReachesTheDayList_byPush() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val p = "pl-name-$s"
        seedPlace(p, "Corner Cafe $s", labelSource = LabelSource.GEOCODED)
        seedVisit("mo-name-$s", lateEvening - 30 * 86_400_000L, p)

        val before = assertNotNull(row("mo-name-$s"), "the seeded visit must be in the feed")
        assertEquals("Visit", before.kind)
        assertEquals("place", before.subjectKind)
        assertEquals(true, before.subjectResolved)
        assertEquals(p, before.placeId)
        assertEquals("Corner Cafe $s", before.placeName)
        assertEquals(null, before.activityType, "another kind's field is null, not ''")
        assertEquals(null, before.placeRole)

        val pushed = CompletableDeferred<String>()
        val stop = Timeline.observeFeed(
            "UTC",
            { result ->
                if (result.ok == true) {
                    val name = rowsOf(result).firstOrNull { it.momentId == "mo-name-$s" }?.placeName as String?
                    if (name == "Home office $s") pushed.complete(name)
                }
            },
            { message -> pushed.completeExceptionally(AssertionError("feed stream ended: $message")) },
        )
        try {
            val renamed = Timeline.renamePlace(p, "  Home office $s  ").await()
            assertEquals(true, renamed.ok, "rename must succeed; got ${JSON.stringify(renamed)}")
            assertEquals("Home office $s", withTimeout(10.seconds) { pushed.await() }, "the push carries the rename")
        } finally {
            stop()
        }
        assertEquals("Home office $s", row("mo-name-$s").placeName, "and a fresh read agrees (name trimmed)")
        val place = placeDetail(p)
        assertEquals(true, place.place.nameIsUsers, "a rename is user-authored, so no enricher overwrites it")

        val blank = Timeline.renamePlace(p, "   ").await()
        assertEquals(false, blank.ok)
        assertEquals("error_timeline_blank_name", blank.error.kind)
    }

    @Test
    fun setPlaceRole_persistsTheUserAnswer_andNoInferredRoleIsEverWritten() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val answered = "pl-role-$s"
        val nightly = "pl-night-$s"
        seedPlace(answered, "Office $s")
        seedPlace(nightly, "Flat $s")
        // Eight recent 23:00Z–05:00Z stays at one place: what the inference pass would call Home
        // (PlaceRoleDetector: ≥ 5 distinct nights, ≥ 30 % of night time) — if a tab ran it.
        val today = (Date.now().toLong() / 86_400_000L) * 86_400_000L
        for (n in 1..8) {
            seedVisit("mo-night-$s-$n", today - n * 86_400_000L - 3_600_000L, nightly, durationSec = 6 * 3600)
        }
        seedVisit("mo-office-$s", today - 2 * 86_400_000L + 10 * 3_600_000L, answered, durationSec = 8 * 3600)

        val set = Timeline.setPlaceRole(answered, "work").await()
        assertEquals(true, set.ok, "setPlaceRole must succeed; got ${JSON.stringify(set)}")

        val detail = placeDetail(answered)
        assertEquals("work", detail.place.role)
        assertEquals(false, detail.place.roleIsSuggestion, "the user's answer is a fact, not a question")
        assertEquals("work", row("mo-office-$s").placeRole, "a confirmed role reaches the row")

        val roles = client.placeService.roles
            .select { fields(PlaceRole.Fields.PLACE_ID, PlaceRole.Fields.ROLE_SOURCE) }
            .getAll()
            .fold(onSuccess = { it.toList() }, onFailure = { throw AssertionError("roles read failed: $it") })
            .associate {
                it.getFieldValue<TString>(PlaceRole.Fields.PLACE_ID).get() to
                    it.getFieldValue<TString>(PlaceRole.Fields.ROLE_SOURCE).get()
            }
        assertEquals(RoleSource.USER, roles[answered], "written user-authored")
        assertEquals(null, roles[nightly], "a tab infers nothing: the nightly place has no role row at all")
        assertEquals(null, placeDetail(nightly).place.role)

        val bad = Timeline.setPlaceRole(answered, "gym").await()
        assertEquals("error_timeline_invalid_role", bad.error.kind)
        assertEquals("work", placeDetail(answered).place.role, "a refused answer changes nothing")
    }

    @Test
    fun merge_foldsAndCarriesTheName_thenUnmerge_restoresBoth() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val a = "pl-m-a-$s" // the smaller id: the deterministic survivor
        val b = "pl-m-b-$s"
        seedPlace(a, "")
        seedPlace(b, "Bakery $s", labelSource = LabelSource.USER)
        seedVisit("mo-m-$s", lateEvening - 40 * 86_400_000L, b)

        val merged = Timeline.mergePlaces(b, a).await()
        assertEquals(true, merged.ok, "merge must succeed; got ${JSON.stringify(merged)}")
        assertEquals(a, merged.survivorId, "the survivor is min(a, b), not the caller's order")

        val ids = places().map { it.placeId as String }
        assertTrue(a in ids && b !in ids, "the folded place leaves the list: $ids")
        assertEquals("Bakery $s", places().first { it.placeId == a }.name, "the user's name travels to the survivor")
        val visit = row("mo-m-$s")
        assertEquals(a, visit.subjectId, "the folded place's visit counts for the survivor")
        assertEquals("Bakery $s", visit.placeName)

        val followed = placeDetail(b)
        assertEquals(a, followed.place.placeId, "a stale link is followed to the survivor")
        assertEquals(b, followed.redirectedFrom)
        assertEquals(1, placeDetail(a).mergedPlaceCount)

        val unmerged = Timeline.unmergePlace(b).await()
        assertEquals(true, unmerged.ok, "unmerge must succeed; got ${JSON.stringify(unmerged)}")
        val after = places().map { it.placeId as String }
        assertTrue(a in after && b in after, "both places are back: $after")
        assertEquals(b, row("mo-m-$s").subjectId, "the visit points at its own place again")
        assertEquals(null, placeDetail(b).redirectedFrom)
        assertEquals(0, placeDetail(a).mergedPlaceCount)

        assertEquals("error_timeline_not_merged", Timeline.unmergePlace(b).await().error.kind)
        assertEquals("error_timeline_invalid_merge", Timeline.mergePlaces(a, a).await().error.kind)
    }

    @Test
    fun unmerge_isReachableFromTheDetailRead_alone() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        seedPlace("pl-u-a-$s", "Park $s")
        seedPlace("pl-u-b-$s", "Park gate $s")
        val survivor = Timeline.mergePlaces("pl-u-a-$s", "pl-u-b-$s").await().survivorId as String

        // From here on, every id comes from a read — what a UI holding only the survivor's page has.
        val detail = placeDetail(survivor)
        val folded = detail.foldedPlaces.unsafeCast<Array<dynamic>>()
        assertEquals(1, folded.size, "the detail names what is folded into it")
        assertEquals(detail.mergedPlaceCount, folded.size)
        assertEquals("Park gate $s", folded[0].name, "by its own name, not the survivor's")
        assertEquals(
            "error_timeline_not_merged",
            Timeline.unmergePlace(detail.place.placeId as String).await().error.kind,
            "the survivor has no merge row — the UI must unmerge a folded place",
        )

        val unmerged = Timeline.unmergePlace(folded[0].placeId as String).await()
        assertEquals(true, unmerged.ok, "unmerging the folded id from the detail works; got ${JSON.stringify(unmerged)}")
        assertEquals(0, placeDetail(survivor).foldedPlaces.length)
        assertTrue(places().any { it.placeId == folded[0].placeId }, "the folded place is its own place again")
    }

    @Test
    fun merge_withAStaleFoldedId_mergesRootIntoRoot_andKeepsTheExistingPointer() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val r = "pl-st-a-$s" // smallest: the root of everything below
        val other = "pl-st-b-$s"
        val f = "pl-st-c-$s"
        seedPlace(r, "Station $s")
        seedPlace(other, "Kiosk $s")
        seedPlace(f, "Platform $s")
        assertEquals(r, Timeline.mergePlaces(f, r).await().survivorId, "F → R")

        // A stale link still holds F: merging it with `other` must merge R with `other`, not move F.
        val merged = Timeline.mergePlaces(f, other).await()
        assertEquals(true, merged.ok, "merge must succeed; got ${JSON.stringify(merged)}")
        assertEquals(r, merged.survivorId, "min(root(F), root(other)) = R")
        assertEquals(r, placeDetail(f).place.placeId, "F → R still holds — the old pointer was not overwritten")
        assertEquals(r, placeDetail(other).place.placeId)
        val folded = placeDetail(r).foldedPlaces.unsafeCast<Array<dynamic>>().map { it.placeId as String }.toSet()
        assertEquals(setOf(f, other), folded)

        assertEquals(
            "error_timeline_already_merged",
            Timeline.mergePlaces(f, other).await().error.kind,
            "two ids that already share a root are refused, not rewritten",
        )
    }

    @Test
    fun anAcceptedSuggestion_staysGoneAfterAReload() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val home = "pl-rl-home-$s"
        seedPlace(home, "Home $s")
        assertEquals(true, Timeline.setPlaceRole(home, "home").await().ok)
        // Before every other test's rows (see the dismiss/accept test), so it is the first offered.
        val base = 300_000_000_000L - (Date.now().toLong() % 100_000_000L) * 1_000L
        seedPlace("pl-rl-away-$s", "Madeira $s", at = -320_000_000L to 1_170_000_000L)
        seedVisit("mo-rl-a-$s", base, "pl-rl-away-$s")
        seedVisit("mo-rl-b-$s", base + 20 * 3_600_000L, "pl-rl-away-$s")

        val offered = assertNotNull(feed().suggestion, "a suggestion must be offered")
        assertEquals(listOf("mo-rl-a-$s", "mo-rl-b-$s"), offered.momentIds.unsafeCast<Array<String>>().toList())
        val accepted = Timeline.acceptTripSuggestion(offered.suggestionId as String, "UTC").await()
        assertEquals(true, accepted.ok, "accept must succeed; got ${JSON.stringify(accepted)}")

        Timeline.resolvedSuggestions.value = emptySet() // what a reload does to the tab's memory
        val after = feed()
        assertTrue(
            after.suggestion == null || after.suggestion.suggestionId != offered.suggestionId,
            "the accepted suggestion must not be offered again after a reload",
        )
        assertTrue(after.trips.unsafeCast<Array<dynamic>>().any { it.tripId == accepted.tripId }, "while its trip stays bound")
    }

    @Test
    fun tripSuggestion_dismissThenAccept_byTheIdTheUiShowed() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val home = "pl-home-$s"
        seedPlace(home, "Home $s")
        assertEquals(true, Timeline.setPlaceRole(home, "home").await().ok)
        // Two away clusters (> 100 km from home, ≥ 2 visits over ≥ 12 h, > 36 h apart), placed
        // before any other test's rows so they are the first suggestions offered.
        val base = 400_000_000_000L - (Date.now().toLong() % 100_000_000L) * 1_000L
        val first = listOf("mo-t1a-$s", "mo-t1b-$s")
        val second = listOf("mo-t2a-$s", "mo-t2b-$s")
        seedPlace("pl-away1-$s", "Lisbon $s", at = -300_000_000L to 1_200_000_000L)
        seedPlace("pl-away2-$s", "Porto $s", at = -301_000_000L to 1_201_000_000L)
        seedVisit(first[0], base, "pl-away1-$s")
        seedVisit(first[1], base + 20 * 3_600_000L, "pl-away2-$s")
        seedVisit(second[0], base + 10 * 86_400_000L, "pl-away1-$s")
        seedVisit(second[1], base + 10 * 86_400_000L + 20 * 3_600_000L, "pl-away1-$s")

        val offered = assertNotNull(feed().suggestion, "a suggestion must be offered")
        assertEquals(first, offered.momentIds.unsafeCast<Array<String>>().toList(), "the earliest cluster first")
        assertEquals(2, offered.placesCount)
        assertEquals(1, offered.daysSpan)
        assertTrue((offered.dateRange as String).isNotEmpty())

        assertEquals(true, Timeline.dismissTripSuggestion(offered.suggestionId as String).await().ok)
        val next = assertNotNull(feed().suggestion, "dismissing one offers the next")
        assertEquals(second, next.momentIds.unsafeCast<Array<String>>().toList())

        val accepted = Timeline.acceptTripSuggestion(next.suggestionId as String, "UTC").await()
        assertEquals(true, accepted.ok, "accept must succeed; got ${JSON.stringify(accepted)}")
        val after = feed()
        val trip = assertNotNull(
            after.trips.unsafeCast<Array<dynamic>>().firstOrNull { it.tripId == accepted.tripId },
            "the accepted suggestion is a bound (synced) trip",
        )
        assertEquals("Lisbon $s", trip.title, "titled by its dominant place")
        // ⚠ A set: the stored membership is a mergeable collection, not an ordered list.
        assertEquals(second.toSet(), trip.momentIds.unsafeCast<Array<String>>().toSet())
        assertTrue(
            after.suggestion == null || after.suggestion.suggestionId != next.suggestionId,
            "an accepted suggestion is no longer offered",
        )

        val detail = Timeline.trip(accepted.tripId as String, "UTC").await()
        assertEquals(true, detail.ok)
        val members = detail.trip.days.unsafeCast<Array<dynamic>>()
            .flatMap { d -> d.moments.unsafeCast<Array<dynamic>>().map { it.momentId as String } }
        assertEquals(second.toSet(), members.toSet(), "every member, grouped by day like the feed (newest day first)")
        assertEquals(0, detail.trip.missingMomentCount)

        assertEquals(
            "error_timeline_suggestion_gone",
            Timeline.acceptTripSuggestion(next.suggestionId as String, "UTC").await().error.kind,
            "a resolved suggestion cannot be accepted twice — nor can another stand in for it",
        )
    }

    @Test
    fun activitiesAndMovementsResolve_andTheRouteIsDecodedSnappedFirst() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val at = lateEvening - 50 * 86_400_000L
        assertOk(
            client.healthService.recordWorkout(
                Workout.create(ctx, "wo-$s", "cycling", "manual", UnixInstantDto(at), UnixInstantDto(at + 3_600_000), 3600),
            ),
            "recordWorkout",
        )
        seedMoment("mo-act-$s", at, "Activity", SubjectKind.WORKOUT, "wo-$s")
        listOf("tr-a-$s", "tr-b-$s").forEach { id ->
            assertOk(
                client.timelineService.recordMovementTrace(
                    MovementTrace.create(
                        ctx, id, "walk", 1, 80, "", 1400, 2500, UnixInstantDto(at), UnixInstantDto(at + 60_000),
                        3, 3, 0, 0, 0, 0, 0, 0, 0, 0, GOLDEN,
                    ),
                ),
                "recordMovementTrace $id",
            )
        }
        // Leg b has a corrected route: a single point, so the substitution is visible.
        assertOk(
            client.timelineService.recordSnappedRoute(
                SnappedRoute.create(ctx, "tr-b-$s", SnappedRoute.Sources.RAIL, 1, 10_000, SNAPPED_ONE_POINT),
            ),
            "recordSnappedRoute",
        )
        seedMoment("mo-move-$s", at + 7_200_000, "Movement", SubjectKind.TRACE, "tr-a-$s")

        val activity = assertNotNull(row("mo-act-$s"))
        assertEquals("cycling", activity.activityType)
        assertEquals(true, activity.subjectResolved)
        assertEquals(null, activity.placeName)
        val move = assertNotNull(row("mo-move-$s"))
        assertEquals("walk", move.movementMode)
        assertEquals(2500.0, move.distanceM)
        assertEquals("tr-a-$s", move.traceId)

        val route = Timeline.movementRoute(arrayOf("tr-a-$s", "tr-b-$s", "tr-unknown-$s")).await()
        assertEquals(true, route.ok, "route must succeed; got ${JSON.stringify(route)}")
        val points = route.points.unsafeCast<Array<dynamic>>()
        assertEquals(4, points.size, "3 captured points of leg a, then leg b's 1 snapped point; unknown adds none")
        assertEquals(48.2, points[0].latitude)
        assertEquals(11.5, points[0].longitude)
        assertEquals(1_700_000_000_000.0, points[0].timestampMs)
        assertEquals(10.0, points[3].latitude, "leg b is drawn from its snapped route")
    }

    @Test
    fun movementRoutes_givesEachLegItsOwnPointsInOrder_inOneNarrowedRead() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val at = lateEvening - 55 * 86_400_000L
        listOf("tr-l1-$s", "tr-l2-$s", "tr-l3-$s").forEach { id ->
            assertOk(
                client.timelineService.recordMovementTrace(
                    MovementTrace.create(
                        ctx, id, "walk", 1, 80, "", 1400, 2500, UnixInstantDto(at), UnixInstantDto(at + 60_000),
                        3, 3, 0, 0, 0, 0, 0, 0, 0, 0, GOLDEN,
                    ),
                ),
                "recordMovementTrace $id",
            )
        }
        assertOk(
            client.timelineService.recordSnappedRoute(
                SnappedRoute.create(ctx, "tr-l2-$s", SnappedRoute.Sources.RAIL, 1, 10_000, SNAPPED_ONE_POINT),
            ),
            "recordSnappedRoute",
        )

        val result = Timeline.movementRoutes(
            arrayOf(arrayOf("tr-l1-$s"), arrayOf("tr-l2-$s", "tr-l3-$s"), arrayOf("tr-unknown-$s")),
        ).await()
        assertEquals(true, result.ok, "routes must succeed; got ${JSON.stringify(result)}")
        val legs = result.legs.unsafeCast<Array<Array<dynamic>>>()
        assertEquals(3, legs.size, "one entry per leg asked for, the unknown one included")
        assertEquals(3, legs[0].size, "leg 1: its own three captured points, nothing of leg 2's")
        assertEquals(48.2, legs[0][0].latitude)
        assertEquals(1 + 3, legs[1].size, "leg 2: tr-l2's one snapped point, then tr-l3's three, in that order")
        assertEquals(10.0, legs[1][0].latitude, "tr-l2 is drawn from its snapped route")
        assertEquals(48.2, legs[1][1].latitude)
        assertEquals(0, legs[2].size, "an unknown trace gives an empty leg, not a missing one")

        // ⚠⚠ The read is narrowed in the store, not after it: asked for tr-l1 alone, nothing else
        // comes back, though tr-l2 / tr-l3 (and every other test's traces) are in the same relations.
        // Mutation-checked: dropping either `oneOf` filter puts the other traces' blobs in here.
        val narrowed = assertIs<RouteBlobs.Ok>(readRouteBlobs(setOf("tr-l1-$s")), "the narrowed read succeeds")
        assertEquals(setOf("tr-l1-$s"), narrowed.byTrace.keys, "only the requested polyline is read")
        val snappedOnly = assertIs<RouteBlobs.Ok>(readRouteBlobs(setOf("tr-l2-$s")))
        assertEquals(setOf("tr-l2-$s"), snappedOnly.byTrace.keys)
        assertEquals(SNAPPED_ONE_POINT.toList(), snappedOnly.byTrace.getValue("tr-l2-$s").toList(), "snapped wins over captured")
    }

    @Test
    fun aWorkoutArrivingAfterItsActivityMoment_rehydratesThePushedFeed() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val at = lateEvening - 60 * 86_400_000L
        // The sync order that broke the one-shot lookup: the moment first, its workout later.
        seedMoment("mo-late-wo-$s", at, "Activity", SubjectKind.WORKOUT, "wo-late-$s")
        assertEquals(false, row("mo-late-wo-$s").subjectResolved, "no workout yet")
        // ⚠ Let the tab's `MomentReconciliationPass` flush (1 s coalesce) land first: its derived
        // writes re-emit the moments relation, which would re-hydrate the row for an unrelated reason.
        delay(2_500)

        val firstPush = CompletableDeferred<Unit>()
        val pushed = CompletableDeferred<String>()
        val stop = Timeline.observeFeed(
            "UTC",
            { result ->
                if (result.ok == true) {
                    val row = rowsOf(result).firstOrNull { it.momentId == "mo-late-wo-$s" }
                    if (row != null && row.subjectResolved == false) firstPush.complete(Unit)
                    val type = row?.activityType as String?
                    if (type != null) pushed.complete(type)
                }
            },
            { message ->
                firstPush.completeExceptionally(AssertionError("feed stream ended: $message"))
                pushed.completeExceptionally(AssertionError("feed stream ended: $message"))
            },
        )
        try {
            // ⚠ Only after the stream has pushed the UNRESOLVED row: otherwise its first snapshot can
            // simply be taken after the workout landed, and the test passes without any re-hydration
            // (mutation-checked — that is exactly how the first version of this test passed).
            withTimeout(10.seconds) { firstPush.await() }
            assertOk(
                client.healthService.recordWorkout(
                    Workout.create(ctx, "wo-late-$s", "rowing", "manual", UnixInstantDto(at), UnixInstantDto(at + 600_000), 600),
                ),
                "recordWorkout",
            )
            assertEquals("rowing", withTimeout(10.seconds) { pushed.await() }, "the workout's arrival alone re-hydrates the row")
        } finally {
            stop()
        }
    }

    @Test
    fun placeVisitSummary_carriesTheDetailsTwoNumbers_followsAFold_andNoMoments() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val a = "pl-vs-a-$s" // the smaller id: the survivor
        val b = "pl-vs-b-$s"
        seedPlace(a, "Kiosk $s")
        seedPlace(b, "Kiosk door $s")
        val day = 86_400_000L
        seedVisit("mo-vs-1-$s", lateEvening - 30 * day, a)
        seedVisit("mo-vs-2-$s", lateEvening - 10 * day, a)
        seedVisit("mo-vs-3-$s", lateEvening - 20 * day, b)

        val own = Timeline.placeVisitSummary(a).await()
        assertEquals(true, own.ok, "placeVisitSummary() must succeed; got ${JSON.stringify(own)}")
        val detail = placeDetail(a)
        assertEquals(detail.place.visitCount, own.summary.visitCount, "the same count as place()")
        assertEquals(detail.place.lastVisitMs, own.summary.lastVisitMs, "the same last visit as place()")
        assertEquals(2, own.summary.visitCount)
        assertEquals((lateEvening - 10 * day).toDouble(), own.summary.lastVisitMs)
        assertEquals(a, own.summary.placeId)
        assertEquals(null, own.summary.redirectedFrom)
        // ⚠ The point of the read: no moments cross the bridge.
        assertEquals(undefined, own.summary.visits, "a summary carries no visit list")
        assertEquals(undefined, own.visits)

        assertEquals(a, Timeline.mergePlaces(b, a).await().survivorId)
        val followed = Timeline.placeVisitSummary(b).await().summary
        assertEquals(a, followed.placeId, "a folded id is followed to its survivor")
        assertEquals(b, followed.redirectedFrom)
        assertEquals(3, followed.visitCount, "the folded place's visit counts for the survivor")
        assertEquals(placeDetail(a).place.visitCount, followed.visitCount)

        val missing = Timeline.placeVisitSummary("pl-vs-missing-$s").await()
        assertTrue(missing.ok == true && missing.summary === null, "unknown is an answer, not a failure: ${JSON.stringify(missing)}")
    }

    @Test
    fun missingIds_answerNull_andWritesOnThemAreTypedFailures() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        val moment = Timeline.momentDetail("mo-missing-$s").await()
        assertEquals(true, moment.ok, "not-found is not a failed read")
        assertTrue(moment.moment === null, "exactly null; got ${JSON.stringify(moment.moment)}")
        val trip = Timeline.trip("trip-missing-$s", "UTC").await()
        assertTrue(trip.ok == true && trip.trip === null)
        val place = Timeline.place("pl-missing-$s").await()
        assertTrue(place.ok == true && place.place === null)
        val route = Timeline.movementRoute(arrayOf("tr-missing-$s")).await()
        assertEquals(0, route.points.length, "an unknown trace contributes no points")

        assertEquals("error_timeline_place_not_found", Timeline.renamePlace("pl-missing-$s", "x").await().error.kind)
        assertEquals("error_timeline_place_not_found", Timeline.setPlaceRole("pl-missing-$s", "home").await().error.kind)
        assertEquals("error_timeline_suggestion_gone", Timeline.dismissTripSuggestion("suggested-trip:$s").await().error.kind)
        assertEquals("error_timeline_invalid_zone", Timeline.feed("Not/AZone").await().error.kind)
    }

    // ── the feed is folded by the shared projection (`:timeline-projection`, §Web scope 4a) ──

    @Test
    fun feed_isFolded_aSplitStayIsOneRow_aStationStopCollapses_andTheRideHasEnds() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = stamp()
        // A day of its own in 2020, so no other test's rows sit beside these (endpoints and merges
        // read the immediate neighbours across the whole store).
        val base = 1_577_836_800_000L + (0..1500).random() * 86_400_000L + 8 * 3_600_000L
        val min = 60_000L
        val home = "pl-fh-$s"
        val station = "pl-fs-$s"
        val work = "pl-fw-$s"
        val library = "pl-fl-$s"
        seedPlace(home, "Flat $s")
        seedPlace(station, "Hauptbahnhof $s")
        seedPlace(work, "Office $s")
        seedPlace(library, "Library $s")
        for ((id, km) in listOf("tr-f1-$s" to 20_000L, "tr-f2-$s" to 14_200L)) {
            assertOk(
                client.timelineService.recordMovementTrace(
                    MovementTrace.create(
                        ctx, id, "transit", 0, 80, "", 9000, km, UnixInstantDto(base), UnixInstantDto(base),
                        3, 3, 0, 0, 0, 0, 0, 0, 0, 0, GOLDEN,
                    ),
                ),
                "recordMovementTrace $id",
            )
        }
        // Home → train → 8-min station stop → train → Work, then a stay split in two.
        seedVisit("mo-fh-$s", base, home)
        seedMoment("mo-f1-$s", base + 60 * min, "Movement", SubjectKind.TRACE, "tr-f1-$s", durationSec = 40 * 60)
        seedVisit("mo-fs-$s", base + 100 * min, station, durationSec = 8 * 60)
        seedMoment("mo-f2-$s", base + 108 * min, "Movement", SubjectKind.TRACE, "tr-f2-$s", durationSec = 35 * 60)
        seedVisit("mo-fw-$s", base + 143 * min, work, durationSec = 60 * 60)
        // ⚠ 15:00 and 16:15 — two different 2-hour reconciler buckets, so `MomentReconciler` keeps
        // both stored rows and it is the projection's §2b merge that folds them, not a dedupe.
        seedVisit("mo-fl1-$s", base + 7 * 60 * min, library, durationSec = 70 * 60)
        seedVisit("mo-fl2-$s", base + 7 * 60 * min + 75 * min, library, durationSec = 50 * 60)

        val ids = rowsOf(feed()).map { it.momentId as String }.filter { it.endsWith(s) }
        assertEquals(
            listOf("mo-fh-$s", "mo-f1-$s", "mo-fw-$s", "mo-fl1-$s"),
            ids,
            "the station row folds into the ride and the split stay is one row",
        )
        val ride = assertNotNull(row("mo-f1-$s"))
        assertEquals("Transit · 34.2 km", ride.displayTitle)
        assertEquals(34_200.0, ride.spanDistanceM)
        assertEquals(83, ride.spanDurationMin)
        assertEquals(listOf("tr-f1-$s", "tr-f2-$s"), ride.traceIds.unsafeCast<Array<String>>().toList())
        assertEquals("Flat $s", ride.originPlace)
        assertEquals("Office $s", ride.destinationPlace)
        val stay = assertNotNull(row("mo-fl1-$s"))
        assertEquals(2, stay.mergedStayCount)
        assertEquals(125, stay.spanDurationMin)
    }

    private companion object {
        // `MovementTracePolylineGoldenTest.GOLDEN`: (48.2, 11.5) and two more fixes.
        val GOLDEN = byteArrayOf(
            3, -128, -14, -43, -53, 3, -128, -117, -42, 109, -128, -96, -85, -2, -7, 98, 10,
            -84, 2, -40, 4, -112, 78, 4, -13, 3, -25, 7, -80, 109, 5,
        )

        // One fix at (10.0, 10.0), t = 0, accuracy 0 — the codec's encoding of that single point.
        val SNAPPED_ONE_POINT = byteArrayOf(1, -128, -124, -81, 95, -128, -124, -81, 95, 0, 0)
    }
}
