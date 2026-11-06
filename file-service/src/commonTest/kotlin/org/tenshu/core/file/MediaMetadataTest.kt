package org.tenshu.core.file

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a file says about itself (`photos-app.md` PH-7 / PH-G4).
 *
 * ⚠⚠ **The HEIC cases are the reason this exists.** The parser was JPEG-only, and its KDoc
 * justified that with "iOS has no gallery backup" — which stopped being true when
 * `IosMediaSourceProvider` shipped. Every iPhone photo landed with no coordinate and the Places
 * section was silently empty for iOS users: no error, no log line, just a blank tab. So these
 * assert the *value*, never merely "not null" — a parser that returns nothing looks exactly like a
 * file that carries nothing, which is the whole failure mode.
 */
class MediaMetadataTest {

    private val firenze = GpsDms(43, 46, 12, 'N', 11, 15, 18, 'E')

    private fun assertCloseTo(expected: Double, actualE7: Long, label: String) {
        val actual = actualE7 / 1e7
        assertTrue(abs(expected - actual) < 1e-5, "$label: expected ~$expected, got $actual")
    }

    // ── JPEG ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aJpegYieldsDimensionsCameraAndOrientation() {
        val meta = readMediaMetadata(
            jpeg(
                sofWidth = 4032, sofHeight = 3024,
                tiff = tiffBlock(
                    make = "Google", model = "Pixel 8 Pro", orientation = 6, gps = firenze,
                ),
            )
        )

        assertEquals(4032, meta.pixelWidth)
        assertEquals(3024, meta.pixelHeight)
        assertEquals(6, meta.orientation)
        assertEquals("Google", meta.cameraMake)
        assertEquals("Pixel 8 Pro", meta.cameraModel)
        assertCloseTo(43.77, meta.location!!.latE7, "latitude")
        assertCloseTo(11.255, meta.location!!.lngE7, "longitude")
    }

    @Test
    fun theFrameHeaderBeatsAStaleExifDimension() {
        // ⚠⚠ A re-encoder resizes the image and leaves `PixelXDimension` at the original value.
        // SOF is the encoded truth; trusting Exif here would report a size the file does not have.
        val meta = readMediaMetadata(
            jpeg(
                sofWidth = 1024, sofHeight = 768,
                tiff = tiffBlock(exifPixelWidth = 4032, exifPixelHeight = 3024),
            )
        )

        assertEquals(1024, meta.pixelWidth)
        assertEquals(768, meta.pixelHeight)
    }

    @Test
    fun exifDimensionsAreTheFallbackWhenThereIsNoFrameHeader() {
        val meta = readMediaMetadata(
            jpeg(tiff = tiffBlock(exifPixelWidth = 800, exifPixelHeight = 600))
        )

        assertEquals(800, meta.pixelWidth)
        assertEquals(600, meta.pixelHeight)
    }

    @Test
    fun aDimensionTagIsReadAtWhateverWidthItWasWrittenAt() {
        // ⚠⚠ SHORT on some cameras, LONG on others. Reading one as the other picks up two bytes of
        // the next field — a plausible, enormous, wrong number, which is worse than absence
        // because a wrong width still renders.
        val asShort = readMediaMetadata(
            jpeg(tiff = tiffBlock(exifPixelWidth = 800, exifPixelHeight = 600, exifPixelsAsLong = false))
        )
        val asLong = readMediaMetadata(
            jpeg(tiff = tiffBlock(exifPixelWidth = 800, exifPixelHeight = 600, exifPixelsAsLong = true))
        )

        assertEquals(800 to 600, asShort.pixelWidth to asShort.pixelHeight)
        assertEquals(800 to 600, asLong.pixelWidth to asLong.pixelHeight)
    }

    @Test
    fun aShortCameraNameIsStoredInTheEntryItself() {
        // ⚠ Four bytes or fewer live in the value field, not at an offset. "LGE" plus its NUL is
        // exactly four — the boundary case, and a real manufacturer.
        val meta = readMediaMetadata(jpeg(tiff = tiffBlock(make = "LGE", model = "Nexus 5X")))

        assertEquals("LGE", meta.cameraMake)
        assertEquals("Nexus 5X", meta.cameraModel)
    }

    @Test
    fun aProgressiveJpegStillReportsItsSize() {
        // SOF2 rather than SOF0 — the marker a progressive encoder writes.
        val meta = readMediaMetadata(jpeg(sofWidth = 640, sofHeight = 480, sofMarker = 0xC2))

        assertEquals(640, meta.pixelWidth)
        assertEquals(480, meta.pixelHeight)
    }

    @Test
    fun aNonsenseOrientationIsAbsentRatherThanStored() {
        // ⚠ Only 1–8 mean anything. A viewer handed 0 cannot interpret it, so "the file said
        // nothing" is the honest answer.
        val meta = readMediaMetadata(jpeg(tiff = tiffBlock(orientation = 0)))
        assertNull(meta.orientation)
    }

    @Test
    fun littleEndianExifReadsTheSame() {
        val meta = readMediaMetadata(
            jpeg(tiff = tiffBlock(littleEndian = true, make = "Canon", orientation = 3, gps = firenze))
        )

        assertEquals("Canon", meta.cameraMake)
        assertEquals(3, meta.orientation)
        assertCloseTo(43.77, meta.location!!.latE7, "latitude")
    }

    // ── HEIC ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aHeicYieldsTheCoordinateThatUsedToBeLost() {
        // ⚠⚠ The regression this phase exists for: before PH-7 this returned null for every
        // iPhone photo in the realm, and the Places tab was empty with nothing to explain it.
        val meta = readMediaMetadata(
            heic(
                tiff = tiffBlock(make = "Apple", model = "iPhone 15 Pro", orientation = 1, gps = firenze),
                ispe = listOf(4032 to 3024),
            )
        )

        assertCloseTo(43.77, meta.location!!.latE7, "latitude")
        assertCloseTo(11.255, meta.location!!.lngE7, "longitude")
        assertEquals("Apple", meta.cameraMake)
        assertEquals("iPhone 15 Pro", meta.cameraModel)
        assertEquals(4032, meta.pixelWidth)
        assertEquals(3024, meta.pixelHeight)
    }

    @Test
    fun theLargestImagePropertyWinsOverTheEmbeddedThumbnail() {
        // ⚠ A HEIC holds several `ispe` boxes — the photo and its thumbnail. Resolving properly
        // means following `pitm` and `ipma`; taking the largest is the documented heuristic, and
        // this is what holds it.
        val meta = readMediaMetadata(heic(ispe = listOf(320 to 240, 4032 to 3024, 160 to 120)))

        assertEquals(4032, meta.pixelWidth)
        assertEquals(3024, meta.pixelHeight)
    }

    @Test
    fun anIlocWithoutConstructionMethodsIsAlsoRead() {
        // Version 0 has no construction_method field at all. Reading one as if it did shifts every
        // subsequent field by two bytes and yields an offset into the middle of nowhere.
        val meta = readMediaMetadata(heic(tiff = tiffBlock(gps = firenze), ilocVersion = 0))

        assertCloseTo(43.77, meta.location!!.latE7, "latitude")
    }

    @Test
    fun anAvifIsReadLikeAHeic() {
        // Same container, different brand — and the parser dispatches on `ftyp` being present,
        // not on which brand it names.
        val meta = readMediaMetadata(heic(ispe = listOf(1920 to 1080), brand = "avif"))

        assertEquals(1920, meta.pixelWidth)
        assertEquals(1080, meta.pixelHeight)
    }

    @Test
    fun aHeicWithNoExifItemIsSilentRatherThanWrong() {
        val meta = readMediaMetadata(heic(ispe = listOf(100 to 100)))

        assertNull(meta.location)
        assertNull(meta.cameraMake)
        assertEquals(100, meta.pixelWidth)
    }

    // ── MP4 / MOV ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aVideoReportsItsDurationAndTheVideoTracksSize() {
        // 15 s at a 600-unit timescale, the value QuickTime writes.
        val meta = readMediaMetadata(
            mp4(durationUnits = 9000, timescale = 600, tracks = listOf(1920 to 1080))
        )

        assertEquals(15_000L, meta.durationMs)
        assertEquals(1920, meta.pixelWidth)
        assertEquals(1080, meta.pixelHeight)
    }

    @Test
    fun anAudioTrackDoesNotBecomeTheVideosSize() {
        // ⚠ An audio track's `tkhd` carries 0 × 0. Taking the first track rather than the largest
        // would report a video with no dimensions at all.
        val meta = readMediaMetadata(
            mp4(durationUnits = 3000, timescale = 1000, tracks = listOf(0 to 0, 1280 to 720))
        )

        assertEquals(1280, meta.pixelWidth)
        assertEquals(720, meta.pixelHeight)
    }

    @Test
    fun aSixtyFourBitHeaderIsReadToo() {
        val meta = readMediaMetadata(
            mp4(durationUnits = 48_000, timescale = 1000, mvhdVersion = 1, tkhdVersion = 1,
                tracks = listOf(3840 to 2160))
        )

        assertEquals(48_000L, meta.durationMs)
        assertEquals(3840, meta.pixelWidth)
    }

    @Test
    fun anUnknownDurationIsAbsentRatherThanAbsurd() {
        // ⚠ `0xFFFFFFFF` is the sentinel a v0 header writes while still recording. Folded through
        // the timescale it becomes a plausible-looking 49-day video.
        val meta = readMediaMetadata(mp4(durationUnits = -1, timescale = 1000))

        assertNull(meta.durationMs)
    }

    @Test
    fun aZeroTimescaleDoesNotDivideByZero() {
        val meta = readMediaMetadata(mp4(durationUnits = 9000, timescale = 0))
        assertNull(meta.durationMs)
    }

    // ── PNG ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aScreenshotReportsItsSize() {
        // Worth having for one reason: a screenshot is a PNG, and the screenshot bucket is one of
        // the folders people back up most.
        val meta = readMediaMetadata(png(1179, 2556))

        assertEquals(1179, meta.pixelWidth)
        assertEquals(2556, meta.pixelHeight)
        assertNull(meta.location)
    }

    // ── refusing to guess ────────────────────────────────────────────────────────────────────

    @Test
    fun anythingUnreadableIsEmptyRatherThanAThrow() {
        // ⚠⚠ This runs on whatever the user uploaded. A parse failure must not fail an upload.
        for (case in listOf(
            ByteArray(0),
            ByteArray(64) { it.toByte() },
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 2),
            "not a media file at all".encodeToByteArray(),
        )) {
            assertTrue(readMediaMetadata(case).isEmpty, "expected nothing from ${case.size} bytes")
        }
    }

    @Test
    fun aTruncatedFileIsEmptyRatherThanAThrow() {
        val full = heic(tiff = tiffBlock(gps = firenze), ispe = listOf(4032 to 3024))
        for (cut in listOf(4, 12, full.size / 3, full.size / 2, full.size - 1)) {
            readMediaMetadata(full.copyOfRange(0, cut)) // must not throw
        }
        val video = mp4(durationUnits = 9000, timescale = 600, tracks = listOf(1920 to 1080))
        for (cut in listOf(8, 20, video.size / 2, video.size - 1)) {
            readMediaMetadata(video.copyOfRange(0, cut))
        }
    }

    // ── the display-size derivation ──────────────────────────────────────────────────────────

    @Test
    fun aQuarterTurnSwapsTheDisplayedAxes() {
        // ⚠ Orientation 6 is the one every portrait iPhone photo carries: the pixels are stored
        // landscape and the viewer is expected to rotate them.
        val rotated = MediaMetadata(pixelWidth = 4032, pixelHeight = 3024, orientation = 6)
        assertEquals(3024, rotated.displayWidth)
        assertEquals(4032, rotated.displayHeight)

        val mirrored = MediaMetadata(pixelWidth = 4032, pixelHeight = 3024, orientation = 2)
        assertEquals(4032, mirrored.displayWidth)
        assertEquals(3024, mirrored.displayHeight)

        val unstated = MediaMetadata(pixelWidth = 4032, pixelHeight = 3024)
        assertEquals(4032, unstated.displayWidth)
    }
}
