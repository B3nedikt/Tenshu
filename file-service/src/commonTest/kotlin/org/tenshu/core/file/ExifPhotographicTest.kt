package org.tenshu.core.file

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the photograph was taken (`photos-viewer-about-screen.md` PH-11).
 *
 * ⚠⚠ Every assertion here is on a **value**, never on "not null" — the rule `MediaMetadataTest`
 * already states and for the same reason: a parser that reads a rational wrongly returns a
 * plausible number, and a plausible wrong aperture renders exactly like a right one.
 */
class ExifPhotographicTest {

    private fun assertClose(expected: Double, actual: Double?, label: String) {
        assertTrue(actual != null && abs(expected - actual) < 1e-6, "$label: expected $expected, got $actual")
    }

    /** A camera writes 1/250, f/1.8, 26 mm as ratios — so the fixture does too. */
    private fun fullyDescribedTiff(littleEndian: Boolean = false) = tiffBlock(
        littleEndian = littleEndian,
        make = "Apple", model = "iPhone 15 Pro", orientation = 6,
        software = "18.1",
        exposureTime = 1 to 250,
        fNumber = 18 to 10,
        iso = 64,
        focalLength = 2600 to 100,
        focalLength35mm = 24,
        lensModel = "iPhone 15 Pro back triple camera 6.86mm f/1.78",
        dateTimeOriginal = "2026:04:09 21:26:31",
        exposureBias = 0 to 3,
        flash = 16,
    )

    @Test
    fun aJpegYieldsEverySettingTheCameraRecorded() {
        val meta = readMediaMetadata(jpeg(sofWidth = 4284, sofHeight = 5712, tiff = fullyDescribedTiff()))

        assertClose(0.004, meta.exposureTimeSeconds, "exposure")
        assertClose(1.8, meta.fNumber, "f-number")
        assertEquals(64, meta.isoSpeed)
        assertClose(26.0, meta.focalLengthMm, "focal length")
        assertEquals(24, meta.focalLength35mm)
        assertEquals("iPhone 15 Pro back triple camera 6.86mm f/1.78", meta.lensModel)
        assertEquals("2026:04:09 21:26:31", meta.dateTimeOriginal)
        assertEquals("18.1", meta.software)
        assertEquals(16, meta.flash)
        // ⚠ The PH-7 fields must still be there — the new fold must not displace the old one.
        assertEquals("Apple", meta.cameraMake)
        assertEquals(6, meta.orientation)
        assertEquals(4284, meta.pixelWidth)
    }

    @Test
    fun littleEndianReadsTheSame() {
        // ⚠⚠ Byte order is decided per file by the TIFF header, and a rational is two words — so
        // this is the path where getting endianness wrong yields a number rather than a failure.
        val meta = readMediaMetadata(jpeg(tiff = fullyDescribedTiff(littleEndian = true)))

        assertClose(0.004, meta.exposureTimeSeconds, "exposure")
        assertClose(1.8, meta.fNumber, "f-number")
        assertEquals(64, meta.isoSpeed)
        assertEquals("2026:04:09 21:26:31", meta.dateTimeOriginal)
    }

    @Test
    fun aHeicYieldsThemToo() {
        // ⚠⚠ The lockstep this file exists to protect. `MediaMetadata.kt` says it out loud: one
        // walk, not one per container, because "a tag read correctly from a JPEG and wrongly from a
        // HEIC looks exactly like an iPhone that never recorded it". Every iPhone photo is a HEIC.
        val meta = readMediaMetadata(heic(tiff = fullyDescribedTiff(), ispe = listOf(4284 to 5712)))

        assertClose(0.004, meta.exposureTimeSeconds, "exposure")
        assertClose(1.8, meta.fNumber, "f-number")
        assertEquals(64, meta.isoSpeed)
        assertClose(26.0, meta.focalLengthMm, "focal length")
        assertEquals("2026:04:09 21:26:31", meta.dateTimeOriginal)
    }

    @Test
    fun aNegativeExposureBiasSurvivesAsNegative() {
        // ⚠⚠ SRATIONAL, and the only signed field here. Read unsigned, −1/3 EV becomes roughly
        // 1.4 billion — a number that renders happily and is nonsense.
        val meta = readMediaMetadata(jpeg(tiff = tiffBlock(exposureBias = -1 to 3)))

        assertClose(-1.0 / 3.0, meta.exposureBiasEv, "exposure bias")
    }

    @Test
    fun zeroIsKeptWhereZeroIsAnAnswer() {
        // ⚠⚠ 0 EV is the *default* exposure compensation and "flash did not fire" is 0. Filtering
        // falsy values — the reflex the `> 0` guards on exposure and focal length invite — would
        // turn the most common reading of both fields into "the file did not say".
        val meta = readMediaMetadata(jpeg(tiff = tiffBlock(exposureBias = 0 to 3, flash = 0)))

        assertEquals(0.0, meta.exposureBiasEv)
        assertEquals(0, meta.flash)
    }

    @Test
    fun aZeroDenominatorIsAbsenceNotInfinity() {
        // ⚠ A malformed rational must read as "did not say", never as Infinity or NaN — both of
        // which format into a row and put the word "Infinity" under someone's photograph.
        val meta = readMediaMetadata(jpeg(tiff = tiffBlock(exposureTime = 1 to 0, fNumber = 18 to 10)))

        assertNull(meta.exposureTimeSeconds)
        // ⚠ …and the neighbouring tag still reads: one bad value must not abandon the walk.
        assertClose(1.8, meta.fNumber, "f-number")
    }

    @Test
    fun aFileThatSaysNothingIsStillEmpty() {
        assertTrue(readMediaMetadata(jpeg()).isEmpty)
        assertTrue(readMediaMetadata(ByteArray(0)).isEmpty)
    }

    @Test
    fun oneNewFieldAloneIsEnoughToNotBeEmpty() {
        // ⚠⚠ The guard on `isEmpty` being equality-based. When it was a hand-written conjunction,
        // a field added to the constructor and forgotten here made `isEmpty` answer true for a file
        // that had said something — silently, and only for the tag nobody remembered.
        assertFalse(MediaMetadata(isoSpeed = 64).isEmpty)
        assertFalse(MediaMetadata(lensModel = "a lens").isEmpty)
        assertFalse(MediaMetadata(exposureBiasEv = 0.0).isEmpty)
        assertFalse(readMediaMetadata(jpeg(tiff = tiffBlock(iso = 64))).isEmpty)
    }
}
