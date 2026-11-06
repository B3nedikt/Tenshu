package app.tenshu.takeout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a Google Photos sidecar (B5, TB-D4) — the file that knows where a photo was taken when
 * the image itself no longer does.
 */
class PhotoSidecarTest {

    private fun sidecar(json: String) = readPhotoSidecar(json.encodeToByteArray())

    @Test
    fun readsTheTakenTimeAndTheCoordinate() {
        val parsed = sidecar(
            """
            {
              "title": "IMG_1234.jpg",
              "photoTakenTime": { "timestamp": "1612345678", "formatted": "3 Feb 2021, 08:27:58 UTC" },
              "geoData": { "latitude": 48.1374, "longitude": 11.5755, "altitude": 519.0 }
            }
            """.trimIndent(),
        )!!

        // ⚠ Seconds as a STRING, and the sibling "formatted" is a localised date nothing can parse.
        assertEquals(1_612_345_678_000L, parsed.takenAtMs)
        assertEquals(481_374_000L, parsed.latE7)
        assertEquals(115_755_000L, parsed.lngE7)
    }

    @Test
    fun zeroZeroIsNoCoordinate() {
        // ⚠⚠ Takeout writes (0,0) for every photo it has no location for. Reading it literally
        // would put a decade of someone's photos off the coast of Africa — and, worse, inside the
        // radius of any place they ever visited at null island.
        val parsed = sidecar(
            """
            {
              "photoTakenTime": { "timestamp": "1612345678" },
              "geoData": { "latitude": 0.0, "longitude": 0.0, "altitude": 0.0 }
            }
            """.trimIndent(),
        )!!

        assertTrue(!parsed.hasCoordinate)
        assertEquals(1_612_345_678_000L, parsed.takenAtMs, "the time is still good")
    }

    @Test
    fun theAuthoredLocationOutranksTheExifOne() {
        // A user who corrected a photo's place in Google Photos meant the correction.
        val parsed = sidecar(
            """
            {
              "geoDataExif": { "latitude": 52.52, "longitude": 13.405 },
              "geoData": { "latitude": 48.1374, "longitude": 11.5755 }
            }
            """.trimIndent(),
        )!!

        assertEquals(481_374_000L, parsed.latE7)
    }

    @Test
    fun anExifOnlySidecarStillYieldsItsCoordinate() {
        val parsed = sidecar("""{ "geoDataExif": { "latitude": 48.1374, "longitude": 11.5755 } }""")!!

        assertEquals(481_374_000L, parsed.latE7)
        assertNull(parsed.takenAtMs, "unknown, not 1970")
    }

    @Test
    fun aSouthWesternCoordinateStaysNegative() {
        // ⚠ A sign error puts the user on the wrong side of the planet and no "is it null" check
        // would catch it — the same reason `ExifGpsTest` asserts both hemispheres.
        val parsed = sidecar("""{ "geoData": { "latitude": -33.8688, "longitude": -70.6693 } }""")!!

        assertEquals(-338_688_000L, parsed.latE7)
        assertEquals(-706_693_000L, parsed.lngE7)
    }

    @Test
    fun theCreationTimeIsTheFallback() {
        val parsed = sidecar("""{ "creationTime": { "timestamp": "1612345678" } }""")!!

        assertEquals(1_612_345_678_000L, parsed.takenAtMs)
    }

    @Test
    fun aSidecarWithNothingUsefulIsNull() {
        assertNull(sidecar("""{ "title": "IMG_1234.jpg", "imageViews": "0" }"""))
    }

    @Test
    fun garbageNeverThrows() {
        assertNull(sidecar("not json at all"))
        assertNull(sidecar(""))
        assertNull(sidecar("""{ "photoTakenTime": { "timestamp": "not a number" } }"""))
    }
}
