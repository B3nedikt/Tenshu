package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.core.health.Workout
import org.tenshu.core.place.Place
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.home.device.HomeDevice
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.askMia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MQ-2's four new sources, each driven end to end through the op — utterance → plan →
 * chain → validator → a real row of that service's own type → a citation.
 *
 * ⭐ Three of the four are **pure time windows**, and none of them was expressible before
 * `inRange` landed (`core-operation-dispatch.md` §5): the chain DSL had only `equalTo` and
 * `contains`, so "what did I do last Saturday" had to be refused outright. These tests are
 * the payoff for that predicate as much as for the source adapters.
 */
class AskMiaSourcesTest {

    /** Wednesday 15 July 2026, 12:00 UTC. */
    private val now = 1_784_116_800_000L
    private val zone = "UTC"

    /** Tuesday 14 July 2026, 09:00 UTC — inside "yesterday". */
    private val yesterdayMorning = now - 27 * 60 * 60 * 1000L

    /** 1 January 2026 — comfortably outside every window these tests ask for. */
    private val longAgo = 1_767_225_600_000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, zone)
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun MiaAnswer.hitList(): List<MiaHit> = hits.toList().filterIsInstance<MiaHit>()

    private fun MiaAnswer.titles(): List<String> = hitList().map { it.title.get() }

    // ---- Timeline moments -------------------------------------------------------

    @Test
    fun aBareTimeWindowRecallsWhatYouDid() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedMoment("m1", yesterdayMorning, "Blue Bottle", "Visit")
        proxy.seedMoment("m2", longAgo, "Somewhere else", "Visit")

        // ⭐ No noun at all — a window on its own IS the "what did I do then" question,
        // and it is the most natural phrasing there is.
        val answer = ask(proxy, "yesterday")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf("Blue Bottle"), answer.titles())
        assertEquals(Moment.ID, answer.hitList().single().targetType)
        assertTrue(answer.explanation.get().contains("yesterday"), answer.explanation.get())
    }

    @Test
    fun aNamedWeekdayResolvesToTheMostRecentOneThatHasPassed() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        // 15 July 2026 is a Wednesday, so "saturday" is 11 July.
        val saturday = now - 4 * 24 * 60 * 60 * 1000L
        proxy.seedMoment("m1", saturday, "The market", "Visit")
        proxy.seedMoment("m2", now - 1_000, "Today's thing", "Visit")

        assertEquals(listOf("The market"), ask(proxy, "what did I do last saturday").titles())
    }

    // ---- Fit workouts -----------------------------------------------------------

    @Test
    fun aFitnessWordWithAWindowFindsTheWorkout() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedWorkout("w1", yesterdayMorning, "running", "Morning run")
        proxy.seedWorkout("w2", longAgo, "running", "Ancient run")

        val answer = ask(proxy, "how far did I run yesterday")

        assertEquals(listOf("Morning run"), answer.titles())
        assertEquals(Workout.ID, answer.hitList().single().targetType)
    }

    @Test
    fun aWorkoutIsFiledByWhenItSTARTED() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        // ⚠ Starts inside yesterday and ends today. It belongs to the day you set out on;
        // filtering on `end` would move it to today, and filtering on either end would put
        // it in both windows.
        proxy.seedWorkout("w1", now - 13 * 60 * 60 * 1000L, "hiking", "Overnight hike")

        assertEquals(listOf("Overnight hike"), ask(proxy, "workouts yesterday").titles())
        assertEquals(emptyList(), ask(proxy, "workouts today").titles())
    }

    // ---- Places -----------------------------------------------------------------

    @Test
    fun aPlaceIsFoundByPartOfItsLabel() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPlace("p1", "Blue Bottle Coffee", "Berlin")
        proxy.seedPlace("p2", "Some Bakery", "Berlin")

        val answer = ask(proxy, "where was that café blue bottle")

        assertEquals(listOf("Blue Bottle Coffee"), answer.titles())
        val hit = answer.hitList().single()
        assertEquals(Place.ID, hit.targetType)
        assertEquals("p1", hit.targetKey, "the citation carries the owning service's own id")
        assertEquals("Berlin", hit.subtitle.get())
    }

    @Test
    fun aPlaceWordWithNothingToSearchForIsRefusedHonestly() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPlace("p1", "Blue Bottle Coffee", "Berlin")

        // ⚠ Returning every place would look like an answer to a question nobody asked.
        val answer = ask(proxy, "show me places")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
    }

    // ---- Home devices -----------------------------------------------------------

    @Test
    fun aDeviceIsFoundByItsName() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen light", "Kitchen")
        proxy.seedDevice("d2", "Bedroom lamp", "Bedroom")

        val answer = ask(proxy, "is the kitchen light on")

        assertEquals(listOf("Kitchen light"), answer.titles())
        val hit = answer.hitList().single()
        assertEquals(HomeDevice.ID, hit.targetType)
        assertEquals("Kitchen", hit.subtitle.get())
        // ⚠ A device row carries no time of its own — unknown, not 1970.
        assertEquals(MiaHit.UNKNOWN_AT_MS, hit.at.get())
    }

    @Test
    fun aDeviceQuestionNeverReachesTheOtherSources() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedDevice("d1", "Kitchen light", "Kitchen")
        proxy.seedMoment("m1", yesterdayMorning, "Kitchen light", "Visit")

        // The routing order is specific-noun-first: a device word names exactly one source
        // and is almost never incidental, so a same-named moment must not surface here.
        assertEquals(
            listOf(HomeDevice.ID),
            ask(proxy, "is the kitchen light on").hitList().map { it.targetType },
        )
    }

    // ---- seeding ----------------------------------------------------------------
    //
    // ⚠ Rows go in through `saveData`, and the generic relation maintainer installed by
    // `createMiaProxy` derives their membership — the same path production takes, and the
    // reason no per-service init is needed here.

    private fun TestProxy.seedMoment(id: String, atMs: Long, title: String, kind: String) {
        executionContext.saveData(
            Moment.create(
                context = tContext,
                momentId = id,
                timestamp = UnixInstantDto(atMs),
                kind = kind,
                title = title,
                subject = SubjectRef.create(tContext, kind = "place", id = "k-$id"),
            )
        )
    }

    private fun TestProxy.seedWorkout(id: String, startMs: Long, activity: String, title: String) {
        executionContext.saveData(
            Workout.create(
                context = tContext,
                workoutId = id,
                activityType = activity,
                source = "test",
                start = UnixInstantDto(startMs),
                end = UnixInstantDto(startMs + 3_600_000),
                durationSec = 3_600,
                title = title,
            )
        )
    }

    private fun TestProxy.seedPlace(id: String, label: String, city: String) {
        executionContext.saveData(
            Place.create(
                context = tContext,
                placeId = id,
                latE7 = 0,
                lngE7 = 0,
                capturedAt = UnixInstantDto(now),
                label = label,
                city = city,
            )
        )
    }

    private fun TestProxy.seedDevice(id: String, name: String, room: String) {
        executionContext.saveData(
            HomeDevice.create(
                context = tContext,
                deviceId = id,
                bridgeId = "bridge",
                kind = "light",
                name = name,
                room = room,
                joinedAtEpochMs = now,
            )
        )
    }
}
