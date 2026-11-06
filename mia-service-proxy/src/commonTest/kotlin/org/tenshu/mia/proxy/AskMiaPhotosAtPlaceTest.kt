package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.file.File
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.place.Place
import org.tenshu.file.test.exifJpegAt
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.askMia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Show me my pictures from Munich" — the Track A join, end to end.
 *
 * ⭐ **Two reads, and the join is Mia's.** `:file-service` never learns what a `Place` is: stage one
 * resolves the name against the places the user has actually been, stage two asks for photos near
 * those coordinates. No model, no geocoder, no egress — the answer is assembled out of two things
 * the realm already knows.
 *
 * ⚠ Which is also the honest limit, pinned below: a photo taken in a city the user never dwelt in
 * has a coordinate but no `Place` to match, so it is not found. Mia names the place it searched
 * around rather than implying it swept a city.
 */
class AskMiaPhotosAtPlaceTest {

    private val now = 1_784_116_800_000L
    private val zone = "UTC"

    // Marienplatz, and the Hauptbahnhof ~1.3 km west of it.
    private val marienplatzLat = 48.137_4
    private val marienplatzLng = 11.575_5
    private val hauptbahnhofLat = 48.140_2
    private val hauptbahnhofLng = 11.558_6

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, zone)
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun MiaAnswer.hitList(): List<MiaHit> = hits.toList().filterIsInstance<MiaHit>()

    private fun TestProxy.seedPlace(id: String, label: String, lat: Double, lng: Double) {
        executionContext.saveData(
            Place.create(
                context = tContext,
                placeId = id,
                latE7 = (lat * 1e7).toLong(),
                lngE7 = (lng * 1e7).toLong(),
                capturedAt = UnixInstantDto(now),
                label = label,
                city = "München",
            )
        )
    }

    private suspend fun TestProxy.seedPhoto(name: String, lat: Double, lng: Double) {
        assertTrue(
            fileService().defaultFileSystem.uploadFile("/", name, exifJpegAt(lat, lng))
                is TResult.Success,
            "seeding /$name should succeed",
        )
    }

    @Test
    fun findsThePhotosTakenAtAPlaceTheUserHasBeen() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPlace("p1", "Marienplatz", marienplatzLat, marienplatzLng)
        proxy.seedPhoto("fountain.jpg", marienplatzLat, marienplatzLng)
        proxy.seedPhoto("elsewhere.jpg", 52.520_0, 13.405_0) // Berlin

        val answer = ask(proxy, "show me my pictures from marienplatz")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf("fountain.jpg"), answer.hitList().map { it.title.get() })
        // ⚠ The citations are FILES, not the place that located them — the question was about
        // photos, and a place card among the results would be an answer to a different one.
        assertEquals(File.ID, answer.hitList().single().targetType)
    }

    @Test
    fun theAnswerNamesThePlaceItSearchedAround() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPlace("p1", "Marienplatz", marienplatzLat, marienplatzLng)
        proxy.seedPhoto("fountain.jpg", marienplatzLat, marienplatzLng)

        // ⚠⚠ The whole reason MQ-D1 cites rather than narrates: "near Marienplatz" is a claim the
        // user can check, where "photos from Munich" would be one they cannot.
        val explanation = ask(proxy, "photos at marienplatz").explanation.get()
        assertTrue(explanation.contains("Marienplatz"), explanation)
    }

    @Test
    fun aPhotoOutsideTheRadiusOfEveryKnownPlaceIsNotFound() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPlace("p1", "Marienplatz", marienplatzLat, marienplatzLng)
        proxy.seedPhoto("station.jpg", hauptbahnhofLat, hauptbahnhofLng)

        val answer = ask(proxy, "pictures at marienplatz")

        // Answered, and empty: the place exists, the photos are simply not there.
        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(emptyList(), answer.hitList())
    }

    @Test
    fun aPlaceTheUserHasNeverBeenIsSaidSoRatherThanAnsweredEmpty() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPhoto("lisbon.jpg", 38.722_2, -9.139_3)

        val answer = ask(proxy, "show me my photos from lisbon")

        // ⚠⚠ "I found nothing" and "I don't know that place" are different answers, and conflating
        // them is what makes an assistant feel like it is lying: the photo IS there.
        //
        // ⚠⚠ **This assertion used to read `VERDICT_ANSWERED`, and that is how the bug shipped
        // past it (§14.1).** The sentence was right, the verdict was not, and the chat surface
        // renders the *verdict*: it appends " — nothing matched." to any ANSWERED with no hits, so
        // the user was shown both answers glued together. A test that pins the explanation while
        // letting the verdict say something else is checking the half that does not decide.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertEquals(emptyList(), answer.hitList())
        assertTrue(answer.explanation.get().contains("don't know"), answer.explanation.get())
    }

    @Test
    fun searchesAroundEveryPlaceThatMatchesTheName() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        // One name, two rows — a venue and the station district. Picking one would silently answer
        // about a corner of the city.
        proxy.seedPlace("p1", "München Marienplatz", marienplatzLat, marienplatzLng)
        proxy.seedPlace("p2", "München Hauptbahnhof", hauptbahnhofLat, hauptbahnhofLng)
        proxy.seedPhoto("a.jpg", marienplatzLat, marienplatzLng)
        proxy.seedPhoto("b.jpg", hauptbahnhofLat, hauptbahnhofLng)

        val titles = ask(proxy, "photos in münchen").hitList().map { it.title.get() }

        assertEquals(setOf("a.jpg", "b.jpg"), titles.toSet())
        assertEquals(titles.size, titles.toSet().size, "a photo near two places is cited once")
    }

    @Test
    fun aBarePhotoRequestStillMeansEveryPhoto() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPlace("p1", "Marienplatz", marienplatzLat, marienplatzLng)
        proxy.seedPhoto("anywhere.jpg", 52.520_0, 13.405_0)

        // ⚠ The place branch is gated on an anchor word ("in"/"at"/"near"/"around"). Without one
        // this is the ordinary file search, and it must not start filtering by location.
        val answer = ask(proxy, "show me my photos")

        assertEquals(listOf("anywhere.jpg"), answer.hitList().map { it.title.get() })
    }
}
