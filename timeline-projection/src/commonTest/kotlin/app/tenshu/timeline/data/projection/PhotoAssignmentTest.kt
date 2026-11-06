package app.tenshu.timeline.data.projection

import app.tenshu.timeline.data.projection.TimelineProjection.MomentInput
import app.tenshu.timeline.data.projection.TimelineProjection.PhotoInput
import app.tenshu.timeline.data.projection.TimelineProjection.Subject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which row a photo lands on (`timeline-photos.md` T-P3).
 *
 * The assignment is pure, over lightweight inputs, so the rules that decide whether your afternoon
 * shows up on the right moment are testable without a proxy behind them. This is also the join that
 * keeps the feed and the detail in agreement — they read the *same* projection, so a bug here is a
 * bug in both at once.
 */
class PhotoAssignmentTest {

    private val noon = 1_700_000_000_000L

    private fun visit(id: String, startMs: Long, durationSec: Long) = MomentInput(
        id = id,
        timestampMs = startMs,
        kind = TimelineProjection.Kind.VISIT,
        title = id,
        durationSec = durationSec,
        subjectKind = Subject.NONE,
    )

    private fun photo(atMs: Long, path: String = "/Backup/P/Camera/$atMs.jpg", backedUp: Boolean = true) =
        PhotoInput(path = path, capturedAtMs = atMs, backedUp = backedUp)

    private fun momentsOf(vararg inputs: MomentInput, photos: List<PhotoInput>) =
        TimelineProjection.days(inputs.toList(), photos = photos).flatMap { it.moments }

    @Test
    fun aPhotoLandsOnTheMomentWhoseWindowContainsIt() {
        val moments = momentsOf(
            visit("morning", noon, durationSec = 3600),
            visit("evening", noon + 7_200_000, durationSec = 3600),
            photos = listOf(photo(noon + 60_000), photo(noon + 7_260_000)),
        )

        assertEquals(1, moments.single { it.id == "morning" }.photos.size)
        assertEquals(1, moments.single { it.id == "evening" }.photos.size)
    }

    @Test
    fun aPhotoTakenBetweenTwoMomentsBelongsToNeither() {
        // The gaps are real minutes — walking to the car — and §7 Q1 leaves them unclaimed rather
        // than stretching a window until something fits.
        val moments = momentsOf(
            visit("a", noon, durationSec = 600),
            visit("b", noon + 7_200_000, durationSec = 600),
            photos = listOf(photo(noon + 3_600_000)),
        )

        assertTrue(moments.all { it.photos.isEmpty() })
    }

    @Test
    fun aMomentShorterThanTheFloorStillClaimsItsPhotos() {
        // A one-minute Event would otherwise claim a one-minute window, which no photo falls into.
        val moments = momentsOf(
            visit("blink", noon, durationSec = 60),
            photos = listOf(photo(noon + 5 * 60_000)),
        )

        assertEquals(1, moments.single().photos.size)
    }

    @Test
    fun aPhotographYouTookOutranksOneYouDidNot_andTheCapKeepsIt() {
        // Same rule the proxy applies, applied again after assignment because the per-moment cap is
        // what truncates here: a stay with a dozen screenshots and one photograph shows the
        // photograph.
        val screenshots = (1..PhotoSelection.MAX_PER_MOMENT + 4).map {
            photo(noon + it * 1_000L, path = "/Screenshot_$it.png", backedUp = false)
        }
        val taken = photo(noon + 500, path = "/Backup/P/Camera/IMG.jpg", backedUp = true)

        val photos = momentsOf(
            visit("stay", noon, durationSec = 3600),
            photos = screenshots + taken,
        ).single().photos

        assertEquals(PhotoSelection.MAX_PER_MOMENT, photos.size, "a row shows a handful, not a gallery")
        assertEquals("/Backup/P/Camera/IMG.jpg", photos.first().uri)
    }

    @Test
    fun aMergedStayClaimsThePhotosOfItsWholeSpan() {
        // ⚠ The window is the MERGED span, not the representative fragment's duration. A stay the
        // capture layer split into fragments presents as one row, and a photo taken during the
        // second fragment must land on it — otherwise the row's count contradicts the row.
        val moments = momentsOf(
            visit("frag-1", noon, durationSec = 1800),
            visit("frag-2", noon + 1_800_000, durationSec = 1800),
            photos = listOf(photo(noon + 60_000), photo(noon + 2_400_000)),
        )

        val claimed = moments.sumOf { it.photos.size }
        assertEquals(2, claimed, "both photos belong to the stay, however it was fragmented")
    }

    @Test
    fun noPhotosMeansNoChange() {
        // The overwhelmingly common state today, and the one that must stay free: every row renders
        // exactly as it did before photos existed.
        val moments = momentsOf(visit("a", noon, durationSec = 3600), photos = emptyList())
        assertTrue(moments.single().photos.isEmpty())
    }

    @Test
    fun aTripCountsThePhotosOfItsMoments_notOfItsDateRange() {
        // A trip is the moments it contains; the hours between them are somebody else's. Counting
        // over the date range would fold a week of everyday photos into a weekend away.
        val member = visit("day-one", noon, durationSec = 3600)
        val trip = TimelineProjection.trip(
            TimelineProjection.TripInput(
                id = "t", title = "Firenze", dateRange = "", year = "",
                membership = listOf("day-one"),
            ),
            moments = listOf(member),
            photos = listOf(photo(noon + 60_000), photo(noon + 100_000_000)),
        )

        assertEquals(1, trip.photosCount)
    }
}

/**
 * Time ∧ place (`timeline-photos.md` T-P4).
 *
 * Time alone attaches whatever was captured during the window, wherever it happened — the
 * screenshot saved at the café is indistinguishable from the photo *of* the café. A coordinate
 * settles it, and these pin the three cases that matter: it excludes, it out-ranks, and it never
 * punishes a photo for not having one.
 */
class PhotoGeoAssignmentTest {

    private val noon = 1_700_000_000_000L
    private val cafe = 43_770_000L to 11_255_000L        // Firenze, E7
    private val acrossTown = 43_800_000L to 11_400_000L  // ~12 km away

    private fun visitAt(id: String, coordinate: Pair<Long, Long>) = TimelineProjection.MomentInput(
        id = id,
        timestampMs = noon,
        kind = TimelineProjection.Kind.VISIT,
        title = id,
        durationSec = 3600,
        subjectKind = TimelineProjection.Subject.PLACE,
        subjectId = "place-1",
    )

    private fun photoAt(coordinate: Pair<Long, Long>?, path: String, atMs: Long = noon + 60_000) =
        TimelineProjection.PhotoInput(
            path = path,
            capturedAtMs = atMs,
            backedUp = true,
            latE7 = coordinate?.first ?: 0,
            lngE7 = coordinate?.second ?: 0,
        )

    private fun assigned(photos: List<TimelineProjection.PhotoInput>) =
        TimelineProjection.photosWithin(photos, noon, noon + 3_600_000, at = cafe).map { it.uri }

    @Test
    fun aPhotoTakenSomewhereElseIsNotYours() {
        // The only case in the whole feature where a photo is *dropped* rather than out-ranked:
        // it says where it was taken, and it was not here.
        assertTrue(assigned(listOf(photoAt(acrossTown, "/elsewhere.jpg"))).isEmpty())
    }

    @Test
    fun aPhotoTakenThereBelongs() {
        assertEquals(listOf("/here.jpg"), assigned(listOf(photoAt(cafe, "/here.jpg"))))
    }

    @Test
    fun aPhotoWithNoCoordinateIsStillYours() {
        // ⚠⚠ "Unknown" must never read as "elsewhere". Most photos have no coordinate — the
        // permission may be denied, the camera may have had no fix, the file may be a HEIC — and
        // reading absence as exclusion would empty the feed on exactly those devices.
        assertEquals(listOf("/unknown.jpg"), assigned(listOf(photoAt(null, "/unknown.jpg"))))
    }

    @Test
    fun aPhotographTakenThereOutranksOneThatMerelyShareTheMinute() {
        val photos = listOf(
            photoAt(null, "/no-coordinate.jpg", atMs = noon + 100_000),
            photoAt(cafe, "/taken-here.jpg", atMs = noon + 1_000),
        )
        // Newest-first would put the coordinate-less one on top; being taken *there* is stronger
        // evidence than being taken later.
        assertEquals("/taken-here.jpg", assigned(photos).first())
    }

    @Test
    fun aMomentWithNoPlaceFallsBackToTimeAlone() {
        // A movement has no place coordinate to test against, and must keep behaving exactly as it
        // did before T-P4 rather than losing every photo that carries a coordinate.
        val photos = listOf(photoAt(acrossTown, "/elsewhere.jpg"))
        val kept = TimelineProjection.photosWithin(photos, noon, noon + 3_600_000, at = null)
        assertEquals(listOf("/elsewhere.jpg"), kept.map { it.uri })
    }

    @Test
    fun aPhotoAcrossTheSquareStillCounts() {
        // The radius is generous on purpose: a dwell centre plus an indoor fix is already ±100 m.
        val nearby = 43_771_500L to 11_256_500L // ~200 m
        assertEquals(listOf("/nearby.jpg"), assigned(listOf(photoAt(nearby, "/nearby.jpg"))))
    }
}
