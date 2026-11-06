package app.tenshu.photos

import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.model.distanceMetresE7
import app.tenshu.photos.model.groupPhotosByPlace
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Places section's arithmetic (`photos-app.md` PH-3) — pure, so it is tested without a store.
 *
 * ⚠⚠ The distance test is the load-bearing one: a flat approximation is *right at the equator and
 * wrong in Europe*, which is the class of bug that ships because nobody's test data left the
 * Atlantic.
 */
class PlaceGroupingTest {

    // Two points 1° of longitude apart at three latitudes. A degree of longitude is ~111 km at the
    // equator and ~71 km at 50°N — the fact a flat approximation gets wrong.
    @Test
    fun aDegreeOfLongitudeShrinksWithLatitude() {
        val atEquator = distanceMetresE7(0, 0, 0, 10_000_000)
        val at50North = distanceMetresE7(500_000_000, 0, 500_000_000, 10_000_000)

        assertTrue(abs(atEquator - 111_320.0) < 500.0, "expected ~111.3 km, got $atEquator")
        assertTrue(abs(at50North - 71_700.0) < 1_000.0, "expected ~71.7 km, got $at50North")
        assertTrue(at50North < atEquator * 0.7, "the whole point: longitude is not a fixed distance")
    }

    private fun photoAt(id: String, latE7: Long?, lngE7: Long?) = Photo(
        path = "/$id.jpg",
        capturedAtMs = 1_000L,
        isVideo = false,
        contentId = id,
        latE7 = latE7,
        lngE7 = lngE7,
    )

    private val cafe = PhotoPlace("p1", "Café", "Munich", 481_370_000, 116_760_000)
    // ~120 m away — inside the default radius, so two places compete for a photo between them.
    private val bakery = PhotoPlace("p2", "Bakery", "Munich", 481_381_000, 116_760_000)

    @Test
    fun aPhotoGoesToItsNearestPlace_notToEveryPlaceInRange() {
        // Sits nearer the café. Both are within the radius, and only one may claim it: a user
        // reading the bakery's photos must not find a picture they took at the café.
        val photo = photoAt("a", 481_372_000, 116_760_000)

        val groups = groupPhotosByPlace(listOf(photo), listOf(cafe, bakery))

        assertEquals(listOf("Café"), groups.map { it.place.label })
        assertEquals(1, groups.single().photos.size)
    }

    @Test
    fun aPhotoWithNoCoordinateBelongsNowhere() {
        val groups = groupPhotosByPlace(
            listOf(photoAt("a", null, null), photoAt("b", 481_370_000, 116_760_000)),
            listOf(cafe),
        )

        assertEquals(listOf("b"), groups.single().photos.map { it.contentId })
    }

    @Test
    fun aPhotoOutsideTheRadiusBelongsNowhere() {
        // ~1.6 km north of the café.
        val faraway = photoAt("a", 481_514_000, 116_760_000)

        assertTrue(groupPhotosByPlace(listOf(faraway), listOf(cafe)).isEmpty())
    }

    @Test
    fun anUnnamedPlaceIsNotASection() {
        // ⚠ `Place.label` is empty until something geocodes it, and "Unnamed place (14)" tells the
        // user nothing they can act on — see photos-app.md §13.2 for why Photos cannot name it.
        val unnamed = PhotoPlace("p3", "", "", 481_370_000, 116_760_000)

        assertTrue(groupPhotosByPlace(listOf(photoAt("a", 481_370_000, 116_760_000)), listOf(unnamed)).isEmpty())
    }

    @Test
    fun placesWithNoPhotosAreDropped_andTheRestAreMostPhotographedFirst() {
        val photos = listOf(
            photoAt("a", 481_370_000, 116_760_000),
            photoAt("b", 481_370_100, 116_760_000),
            photoAt("c", 481_381_000, 116_760_000),
        )
        val park = PhotoPlace("p9", "Park", "Munich", 500_000_000, 116_760_000)

        val groups = groupPhotosByPlace(photos, listOf(cafe, bakery, park))

        assertEquals(listOf("Café", "Bakery"), groups.map { it.place.label })
        assertEquals(listOf(2, 1), groups.map { it.photos.size })
    }

    @Test
    fun noPlacesMeansNoSections_ratherThanOneBigUnknown() = assertTrue(
        groupPhotosByPlace(listOf(photoAt("a", 481_370_000, 116_760_000)), emptyList()).isEmpty()
    )
}
