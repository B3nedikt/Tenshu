package org.tenshu.file.proxy.jvm

import kotlinx.coroutines.test.runTest
import org.tenshu.file.proxy.ThumbnailClasses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **One decode per upload** — P1a's claim (`media-processing-pipeline-sweep.md` §4.1, §6), pinned
 * where it can regress: the real `UploadFile` op, the real minter, the real JVM decoder, counted.
 *
 * Before P1a every rung was its own `GetPreview`, and each of those decoded the original at full size:
 * four decodes per photo, and one more per quality step for a frame over a JPEG cap. The second test
 * keeps that baseline measured rather than remembered, so the first one's `1` is read against it.
 */
class UploadDecodesOnceTest {

    @Test
    fun aPhotoUploadDecodesTheOriginalOnce() = runTest {
        val decoder = LadderFixtures.CountingDecoder()

        val rungs = LadderFixtures.upload(
            backgroundScope, "photo.jpg", LadderFixtures.photo(2_400, 1_800), ladder = true, decoder = decoder,
        )

        // ⚠ Anti-vacuity: a ladder that minted nothing would decode once too.
        assertTrue(rungs.values.all { it != null }, "every still rung is minted: ${rungs.filterValues { it == null }.keys.map { it.id }}")
        assertEquals(1, decoder.decodes.get(), "one decode feeds every rung")
    }

    @Test
    fun aFrameThatWalksTheStepDownStillDecodesOnce() = runTest {
        val decoder = LadderFixtures.CountingDecoder()

        LadderFixtures.upload(backgroundScope, "noise.png", LadderFixtures.noise(2_400, 2_400), ladder = true, decoder = decoder)

        assertEquals(1, decoder.decodes.get(), "a quality step re-encodes the scaled bitmap; it never decodes again")
    }

    @Test
    fun withoutTheLadder_everyRungDecodesTheOriginalAgain() = runTest {
        val decoder = LadderFixtures.CountingDecoder()

        LadderFixtures.upload(
            backgroundScope, "photo.jpg", LadderFixtures.photo(2_400, 1_800), ladder = false, decoder = decoder,
        )

        assertEquals(
            ThumbnailClasses.STILLS.size,
            decoder.decodes.get(),
            "the per-rung path (Android and iOS today; desktop before P1a) decodes once per rung",
        )
    }

    @Test
    fun withoutTheLadder_eachQualityStepDecodesAgain() = runTest {
        val decoder = LadderFixtures.CountingDecoder()

        LadderFixtures.upload(backgroundScope, "noise.png", LadderFixtures.noise(2_400, 2_400), ladder = false, decoder = decoder)

        println("[decodes] per-rung path, step-down frame: ${decoder.decodes.get()}")
        assertTrue(
            decoder.decodes.get() > ThumbnailClasses.STILLS.size,
            "a step-down re-renders from the original on the per-rung path: ${decoder.decodes.get()} decodes",
        )
    }
}
