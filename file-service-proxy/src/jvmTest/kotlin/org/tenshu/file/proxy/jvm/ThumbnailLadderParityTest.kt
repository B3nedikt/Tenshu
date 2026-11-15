package org.tenshu.file.proxy.jvm

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.file.impl.ThumbnailClassMinter
import org.tenshu.file.proxy.ThumbnailClasses
import org.tenshu.file.proxy.ThumbnailLadderRenderer
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * ⚠⚠ **The ladder mints what the per-rung path mints** (`media-processing-pipeline-sweep.md`
 * §4.1). Each fixture is uploaded twice through the real op — once rung by rung through `GetPreview`,
 * once through [JvmThumbnailLadderRenderer] — and every rung is compared:
 *
 * - the same rungs stored and the same ones declined;
 * - JPEG, as the upload asks;
 * - dimensions **to the pixel** — the edge rule reads the original size on both paths;
 * - both under the rung's cap;
 * - the picture itself within tolerance. The pixels may differ below the top rung: the ladder
 *   cascades rung to rung, where the per-rung path scales each rung straight from the full decode.
 *
 * ⚠ Each fixture is sized from [TOP_RUNG_PX] to the boundary it is about, and no larger: the decode,
 * the scales and the encodes all cost by the pixel, and the only edges production branches on are
 * "is the original larger than this rung" (`JvmThumbnailRaster.fitWithin`) and each rung's byte cap.
 */
class ThumbnailLadderParityTest {

    @Test
    fun aLargeLandscapePhoto() = parity("wide.jpg", LadderFixtures.photo(WIDE_EDGE, WIDE_EDGE * 3 / 5))

    @Test
    fun aPortraitPhotoBetweenTheTopRungs() = parity("tall.jpg", LadderFixtures.photo(1_500, 2_600))

    @Test
    fun aSmallPngNoRungMayUpscale() = parity("icon.png", LadderFixtures.photo(80, 60, format = "png"))

    @Test
    fun aNoiseFrame_theStepDownAndTheCapsDeclineTheSameRungs() = runTest {
        val bytes = LadderFixtures.noise(NOISE_EDGE, NOISE_EDGE)
        val perRung = LadderFixtures.upload(backgroundScope, "noise.png", bytes, ladder = false)
        val ladder = LadderFixtures.upload(backgroundScope, "noise.png", bytes, ladder = true)
        // ⚠ Anti-vacuity: the fixture has to actually reach both a decline and a stored rung.
        assertTrue(perRung.values.any { it == null }, "the fixture must exceed a cap at some rung")
        assertTrue(perRung.values.any { it != null }, "and fit at another")
        assertSameShape(perRung, ladder, pixelTolerance = null)
    }

    /**
     * ⚠⚠ An unfiltered subsampled read of 1 px stripes keeps one phase, and the top rung comes out
     * **solid black** — under every cap, so nothing downstream can tell, and it would be stored and
     * synced. The ladder decodes at full resolution: every rung keeps the stripes'
     * mean, and the top rung is the per-rung path's render.
     */
    @Test
    fun finePeriodicDetailIsNeverAliasedAway() = runTest(timeout = 5.minutes) {
        val bytes = LadderFixtures.stripes(STRIPES_EDGE)
        val perRung = LadderFixtures.upload(backgroundScope, "stripes.png", bytes, ladder = false)
        val ladder = LadderFixtures.upload(backgroundScope, "stripes.png", bytes, ladder = true)

        for (rung in ThumbnailClasses.STILLS) {
            val image = LadderFixtures.decode(assertNotNull(ladder[rung], "${rung.id} is stored"))
            val mean = LadderFixtures.meanBrightness(image)
            println("[stripes] ${rung.id}: ladder mean $mean, per-rung mean ${perRung[rung]?.let { LadderFixtures.meanBrightness(LadderFixtures.decode(it)) }}")
            assertTrue(mean in 100.0..155.0, "${rung.id}: mean $mean — the stripes average to 127.5, not to one phase")
        }
        val top = ThumbnailClasses.XXLARGE
        val diff = LadderFixtures.meanAbsoluteDifference(
            LadderFixtures.decode(assertNotNull(perRung[top])),
            LadderFixtures.decode(assertNotNull(ladder[top])),
        )
        assertTrue(diff <= 1.0, "the top rung is the per-rung path's render from the same full decode: $diff")
    }

    @Test
    fun anUndecodableImageIsDeclinedAtEveryRung_notFailed() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val minter = ThumbnailClassMinter(
            fileDatabase = InMemoryFileDatabase(),
            thumbnailLadderRenderer = JvmThumbnailLadderRenderer(),
        )

        val renders = minter.renderStills(
            proxy.executionContext, "/broken.jpg", ByteArray(4_096) { it.toByte() },
            ThumbnailClasses.STILLS, GetPreview.Params.FORMAT_JPEG,
        )

        // ⚠ The backfill records a decline for good and retries a failure. An undecodable original
        // is a decline on the per-rung path; it must stay one.
        renders.forEach { assertIs<ThumbnailClassMinter.Render.Declined>(it) }
    }

    @Test
    fun pdfsVideosAndOtherFilesAreLeftToThePerRungPath() {
        val rungs = ThumbnailClasses.STILLS.map {
            ThumbnailLadderRenderer.Rung(it.px, GetPreview.Params.FORMAT_JPEG, listOf(null), it.maxBytes)
        }
        val renderer = JvmThumbnailLadderRenderer()
        for (name in listOf("doc.pdf", "clip.mp4", "notes.txt")) {
            assertNull(renderer.render(name, byteArrayOf(1, 2, 3), rungs), "$name is not the ladder's to render")
        }
    }

    private fun parity(fileName: String, bytes: ByteArray) = runTest {
        val perRung = LadderFixtures.upload(backgroundScope, fileName, bytes, ladder = false)
        val ladder = LadderFixtures.upload(backgroundScope, fileName, bytes, ladder = true)
        assertTrue(perRung.values.all { it != null }, "the fixture must mint every rung on the per-rung path")
        assertSameShape(perRung, ladder, pixelTolerance = PIXEL_TOLERANCE)
    }

    private fun assertSameShape(
        perRung: Map<org.tenshu.file.proxy.ThumbnailClass.Still, ByteArray?>,
        ladder: Map<org.tenshu.file.proxy.ThumbnailClass.Still, ByteArray?>,
        pixelTolerance: Double?,
    ) {
        for (rung in ThumbnailClasses.STILLS) {
            val expected = perRung[rung]
            val actual = ladder[rung]
            if (expected == null) {
                assertNull(actual, "${rung.id}: the per-rung path declined it, so must the ladder")
                continue
            }
            if (actual == null) fail("${rung.id}: the per-rung path minted it, the ladder declined it")
            assertTrue(LadderFixtures.isJpeg(actual), "${rung.id}: JPEG, as the upload asks")
            assertTrue(actual.size <= rung.maxBytes, "${rung.id}: ${actual.size} B over the ${rung.maxBytes} B cap")
            val e = LadderFixtures.decode(expected)
            val a = LadderFixtures.decode(actual)
            assertEquals(e.width to e.height, a.width to a.height, "${rung.id}: dimensions to the pixel")
            if (pixelTolerance != null) {
                val diff = LadderFixtures.meanAbsoluteDifference(e, a)
                assertTrue(diff <= pixelTolerance, "${rung.id}: mean pixel difference $diff over $pixelTolerance")
            }
        }
    }

    private companion object {
        /** The largest still rung's edge; every fixture below is sized from it. */
        const val TOP_RUNG_PX = GetStoredThumbnail.XXLARGE_PX

        /**
         * Past the top rung, so every rung is a downscale on both paths, and landscape, so
         * `fitWithin` takes its width-bound branch (the portrait fixture takes the height-bound one).
         * A quarter over rather than just over, so the top rung is a real resample, not a 1 % nudge.
         */
        const val WIDE_EDGE = TOP_RUNG_PX + TOP_RUNG_PX / 4

        /** Past the top rung, so each rung meets its cap at its own size rather than the original's. */
        const val NOISE_EDGE = TOP_RUNG_PX + 64

        /**
         * ⚠ Not shrunk: the smallest edge at which a subsampled read of the top rung would pick factor
         * 2 is twice the rung, and factor 2 is what turns 1 px stripes solid black. Below it this test
         * could not catch a subsampled read coming back.
         */
        const val STRIPES_EDGE = 2 * TOP_RUNG_PX + 104

        /**
         * Mean absolute difference per channel (0–255). Two JPEG encodes of one picture differ by ~1;
         * cascaded against straight-from-full measured under 2 on these fixtures. 4
         * leaves room for codec drift and still fails a misplaced, flipped or wrongly-cropped rung,
         * which lands in the tens.
         */
        const val PIXEL_TOLERANCE = 4.0
    }
}
