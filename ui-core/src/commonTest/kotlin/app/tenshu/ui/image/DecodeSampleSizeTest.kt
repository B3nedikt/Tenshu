package app.tenshu.ui.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The rule that must never undershoot** (`drive-xxlarge-thumbnail-rung.md` §4.5).
 *
 * Decode sampling is powers of two, so the obvious loop — halve until under the ceiling — lands
 * *below* the size being drawn and the viewer upscales a picture it was handed sharp. That is the
 * blur the whole rung ladder exists to remove, arriving by the back door, and it would look exactly
 * like a rung that failed to pull. These cases are the reason the arithmetic is a named function
 * with a test instead of a loop inside the Android actual.
 */
class DecodeSampleSizeTest {

    @Test
    fun samplingNeverLandsBelowTheSizeBeingDrawn() {
        // The case that motivated it: the 2048 px rung on a ~1440 px phone viewport. Halving would
        // give 1024 — an upscale — so it must not sample at all.
        assertEquals(1, decodeSampleSize(sourceEdge = 2048, maxEdge = 1440))
        // Exactly the drawn size: nothing to do.
        assertEquals(1, decodeSampleSize(sourceEdge = 2048, maxEdge = 2048))
        // Twice the drawn size is the first point where one halving still clears it.
        assertEquals(2, decodeSampleSize(sourceEdge = 2048, maxEdge = 1024))
        assertEquals(4, decodeSampleSize(sourceEdge = 4096, maxEdge = 1024))
        // A tile: a 2048 px rung drawn at 256 px samples hard, and still not past it.
        assertEquals(8, decodeSampleSize(sourceEdge = 2048, maxEdge = 256))
    }

    /**
     * ⚠⚠ **The bound wins over the target, and this is the case that proves it.** The first version
     * of this rule knew only the target, so a caller passing the *default* — a ceiling, not a size it
     * draws — decoded a 6000 px original at full resolution to land "just above" 4096, then held a
     * scaled copy beside it: ~141 MB peak where the code it replaced managed 24 MB, on a `minSdk` 24
     * heap with no `largeHeap` in this repo. Drive's Android image preview hands whole originals to
     * the default, so a 50 MP photo was an `OutOfMemoryError` nothing catches.
     *
     * ⚠ And the earlier version of *this test* asserted the broken behaviour (`sampled >= 4096` for a
     * 6000 px source), so it blessed the defect instead of catching it.
     */
    @Test
    fun theHardCeilingIsNeverExceeded_evenWhenThatUndershootsTheTarget() {
        // The regression band: a source between the ceiling and twice it, with the ceiling as target.
        assertEquals(2, decodeSampleSize(sourceEdge = 6000, maxEdge = 4096))
        assertEquals(2, decodeSampleSize(sourceEdge = 8191, maxEdge = 4096))
        assertEquals(1, decodeSampleSize(sourceEdge = 4096, maxEdge = 4096))
        // Which is exactly what the ladder-free code did before any of this existed.
        for (source in listOf(3000, 4096, 4097, 6000, 8160, 8191, 12000)) {
            val decoded = source / decodeSampleSize(source, MAX_DECODE_EDGE)
            assertTrue(
                decoded <= MAX_DECODE_EDGE,
                "a $source px source decoded to $decoded px — above the hard ceiling",
            )
        }
    }

    @Test
    fun aDrawnSizeIsStillNeverUndershot_whileTheCeilingAllowsIt() {
        // ⚠ The large sources are load-bearing: with only sources <= the ceiling, the ceiling
        // assertion below is true for *any* implementation — including the broken one — because a
        // sample can only shrink. A guard that cannot fail is the defect this file exists to catch.
        for (source in listOf(100, 256, 1024, 1440, 2048, 2600, 4096, 6000, 8191, 12000)) {
            for (target in listOf(100, 256, 512, 1024, 1440, 2048, 4096)) {
                val sample = decodeSampleSize(source, target)
                val sampled = source / sample
                assertTrue(
                    sampled <= MAX_DECODE_EDGE,
                    "decoding $source px for a $target px view gave $sampled px — above the ceiling",
                )
                // ⚠⚠ The floor is conditional, and saying so is the whole point: an undershoot is
                // allowed **only** when the ceiling forced it — when sampling one step less would
                // have breached the bound. Asserting the floor unconditionally is wrong (the first
                // version of this loop did, and went red the moment sources above the ceiling were
                // restored to it); asserting only the ceiling is the tautology it replaced.
                val forcedByTheCeiling = sample > 1 && source / (sample / 2) > MAX_DECODE_EDGE
                assertTrue(
                    sampled >= minOf(source, target) || forcedByTheCeiling,
                    "decoding $source px for a $target px view gave $sampled px — an upscale the ceiling did not force",
                )
            }
        }
    }

    /**
     * ⚠⚠ A non-positive `maxEdge` means "no target", **not** "no bound". The first version returned
     * 1 immediately for it, so a 20000 px source would have decoded whole — the original defect,
     * reachable by a second route — and this test pinned that answer rather than catching it.
     */
    @Test
    fun noTargetStillMeansTheCeilingApplies() {
        assertEquals(1, decodeSampleSize(sourceEdge = 0, maxEdge = 1024))
        assertEquals(1, decodeSampleSize(sourceEdge = -1, maxEdge = -1))
        // Under the ceiling: nothing to do either way.
        assertEquals(1, decodeSampleSize(sourceEdge = 2048, maxEdge = 0))
        // Above it: the bound still binds.
        assertEquals(8, decodeSampleSize(sourceEdge = 20_000, maxEdge = 0))
        assertTrue(
            20_000 / decodeSampleSize(sourceEdge = 20_000, maxEdge = 0) <= MAX_DECODE_EDGE,
            "a caller with no target must still not decode above the ceiling",
        )
        // And an explicit "no ceiling" is the only way to opt out.
        assertEquals(1, decodeSampleSize(sourceEdge = 20_000, maxEdge = 0, ceiling = 0))
    }
}
