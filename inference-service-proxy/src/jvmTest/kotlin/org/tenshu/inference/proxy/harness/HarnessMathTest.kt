package org.tenshu.inference.proxy.harness

import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The harness's arithmetic (`inference-service.md` §13).
 *
 * ⚠⚠ These exist because the harness itself **cannot run in CI** — it needs weights nobody has
 * chosen. A preprocessing bug or a forgotten normalisation would not fail anything; it would
 * quietly produce plausible numbers and a wrong verdict about a model. Measuring badly is worse
 * than not measuring, because it *looks* like evidence.
 */
class HarnessMathTest {

    private fun image(width: Int, height: Int, paint: (Int, Int) -> Color): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) out.setRGB(x, y, paint(x, y).rgb)
        return out
    }

    @Test
    fun preprocessProducesChwOfTheRightSize() {
        val out = preprocess(image(400, 300) { _, _ -> Color.WHITE }, size = 224)

        assertEquals(3 * 224 * 224, out.size)
    }

    @Test
    fun whiteAndBlackLandWhereTheNormalisationSaysTheyShould() {
        val white = preprocess(image(64, 64) { _, _ -> Color.WHITE }, size = 8)
        val black = preprocess(image(64, 64) { _, _ -> Color.BLACK }, size = 8)

        // ⚠⚠ SigLIP's constants are SPELLED OUT here rather than read back from the enum. A test
        // that asserts `mean` equals `mean` passes whatever the value is — which is exactly how
        // the harness ran two sessions on CLIP's statistics against a SigLIP checkpoint.
        assertTrue(abs(white[0] - 1f) < 1e-4, "white is +1 under mean=std=0.5")
        assertTrue(abs(black[0] - -1f) < 1e-4, "black is -1")

        val clipWhite = preprocess(
            image(64, 64) { _, _ -> Color.WHITE }, size = 8, profile = ImagePreprocessing.Profile.CLIP,
        )
        assertTrue(abs(clipWhite[0] - 1.9303f) < 1e-3, "and CLIP's are different, which is the point")
    }

    @Test
    fun theChannelsAreSeparatedInPlaneOrder() {
        // A pure-red image: plane 0 is "1", planes 1 and 2 are "0" — under their own constants.
        val out = preprocess(image(16, 16) { _, _ -> Color.RED }, size = 4)
        val plane = 4 * 4

        assertTrue(out[0] > 0f, "red plane holds the red channel")
        assertTrue(out[plane] < 0f, "green plane holds green (zero → negative after normalisation)")
        assertTrue(out[2 * plane] < 0f, "blue plane holds blue")
    }

    @Test
    fun siglipSquashesAndClipCrops() {
        // Left third red, middle third green, right third blue. A centre crop keeps only green; a
        // squash keeps all three.
        val wide = image(300, 100) { x, _ ->
            when {
                x < 100 -> Color.RED
                x < 200 -> Color.GREEN
                else -> Color.BLUE
            }
        }
        val plane = 4 * 4

        // ⚠⚠ The profile decides, and the two disagree on every non-square photo in a library.
        // This used to assert the crop unconditionally, with a well-argued comment about not
        // judging a model on images no camera produced — sound reasoning, and wrong: SigLIP's own
        // processor squashes, so the crop was the distortion.
        val squashed = preprocess(wide, size = 4, profile = ImagePreprocessing.Profile.SIGLIP)
        assertTrue(squashed[0] > 0f, "the left edge survives a squash, so the red plane is lit")
        assertTrue(squashed[2 * plane + 3] > 0f, "and so does the right edge, in blue")

        val cropped = preprocess(wide, size = 4, profile = ImagePreprocessing.Profile.CLIP)
        assertTrue(cropped[plane] > cropped[0], "the centre is green, so green dominates the crop")
        assertTrue(cropped[plane] > cropped[2 * plane])
    }

    @Test
    fun siglipProbabilityIsNotTheCosine() {
        // The pinned checkpoint's calibration: logits = 111.00 · cos − 12.89 (docs/models.md §3.7.2).
        val scale = 110.995995f
        val bias = -12.885268f

        // ⚠⚠ The decision boundary sits at cos ≈ 0.116 — a number that looks like noise if you are
        // used to reading cosines directly, which is precisely why a raw-cosine threshold is not a
        // threshold at all.
        assertTrue(HarnessMath.siglipProbability(0.116f, scale, bias) < 0.5f)
        assertTrue(HarnessMath.siglipProbability(0.117f, scale, bias) > 0.5f)
        assertTrue(HarnessMath.siglipProbability(0.05f, scale, bias) < 0.01f, "0.05 is a firm no")
    }

    @Test
    fun theLeafRuleDegradesToTheParentWhenSiblingsAreClose() {
        val close = HarnessMath.resolveSiblings(mapOf("corgi" to 0.31f, "shiba inu" to 0.305f), margin = 0.01f)

        assertEquals("corgi", close?.best)
        assertTrue(close?.degrade == true, "a confidently wrong breed is worse than no breed")

        val clear = HarnessMath.resolveSiblings(mapOf("corgi" to 0.31f, "shiba inu" to 0.10f), margin = 0.01f)

        assertTrue(clear?.degrade == false)
    }

    @Test
    fun aLoneSiblingNeverDegrades() {
        // ⚠ No runner-up means no margin to test. Returning "degrade" here would silently erase
        // every single-child branch in the vocabulary.
        val only = HarnessMath.resolveSiblings(mapOf("corgi" to 0.02f), margin = 0.5f)

        assertTrue(only?.degrade == false)
        assertEquals(null, HarnessMath.resolveSiblings(emptyMap(), margin = 0.01f))
    }

    @Test
    fun normalisationMakesCosineADotProduct() {
        val a = ImagePreprocessing.normalise(floatArrayOf(3f, 4f))

        assertTrue(abs(HarnessMath.cosine(a, a) - 1f) < 1e-5, "a unit vector is 1 against itself")
        assertTrue(abs(HarnessMath.cosine(a, ImagePreprocessing.normalise(floatArrayOf(-4f, 3f)))) < 1e-5)
    }

    @Test
    fun aZeroVectorDoesNotDivideByZero() {
        val zero = ImagePreprocessing.normalise(floatArrayOf(0f, 0f))

        assertEquals(0f, zero[0])
    }

    @Test
    fun topKIsHighestFirst() {
        val scores = mapOf("cat" to 0.2f, "receipt" to 0.9f, "beach" to 0.5f)

        assertEquals(listOf("receipt", "beach"), HarnessMath.topK(scores, k = 2).map { it.first })
    }

    @Test
    fun percentilesDescribeTheShapeAMeanWouldHide() {
        // One very slow first inference among fast ones — the shape every ORT run has.
        val timings = listOf(900L, 20L, 21L, 19L, 22L, 20L, 18L, 23L, 21L, 20L)

        assertTrue(HarnessMath.percentile(timings, 50) < 30, "the median is what a pass costs")
        assertEquals(900L, timings.max(), "and the max is what the user notices")
    }
}
