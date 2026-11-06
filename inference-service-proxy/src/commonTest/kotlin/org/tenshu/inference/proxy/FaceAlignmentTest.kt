package org.tenshu.inference.proxy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The shared 5-point alignment (IN-6 F1).
 *
 * ⚠⚠ This is the half that makes "our model everywhere" mean anything: without a shared alignment,
 * each platform crops to its own detector's framing and the same face lands in a different region of
 * the same model's space — silently, because every downstream arithmetic still succeeds.
 */
class FaceAlignmentTest {

    /** A square test frame: a uniform division, which the similarity fit absorbs exactly. */
    private val FRAME = 1000

    /**
     * A template with deliberately round numbers, not a real checkpoint's.
     *
     * ⚠ A test that used a published constellation would assert against numbers nobody here
     * measured, and would have to change when the checkpoint does. The *maths* is what is pinned.
     */
    private val template = FaceAlignmentTemplate(
        leftEye = FacePoint(40f, 50f),
        rightEye = FacePoint(72f, 50f),
        noseTip = FacePoint(56f, 70f),
        mouthLeft = FacePoint(42f, 92f),
        mouthRight = FacePoint(70f, 92f),
        side = 112,
    )

    /** The template's own points, expressed as a face — the identity case. */
    private val alreadyAligned = FaceLandmarks(
        leftEye = template.leftEye,
        rightEye = template.rightEye,
        noseTip = template.noseTip,
        mouthLeft = template.mouthLeft,
        mouthRight = template.mouthRight,
    )

    private fun FaceLandmarks.transformed(
        scale: Float = 1f,
        degrees: Double = 0.0,
        dx: Float = 0f,
        dy: Float = 0f,
    ): FaceLandmarks {
        val radians = degrees * PI / 180.0
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        fun move(p: FacePoint) = FacePoint(
            x = scale * (c * p.x - s * p.y) + dx,
            y = scale * (s * p.x + c * p.y) + dy,
        )
        return FaceLandmarks(move(leftEye), move(rightEye), move(noseTip), move(mouthLeft), move(mouthRight))
    }

    /**
     * A face that is rotated *and* scaled away from the template, so a fit against it has a genuine
     * rotation and a scale ≠ 1 — the only shape in which a wrong matrix inverse is distinguishable.
     */
    private fun turnedBy(scale: Float): FaceLandmarks =
        alreadyAligned.transformed(scale = scale, degrees = 25.0, dx = 3f, dy = -4f)

    /**
     * The fixtures are written in SOURCE PIXELS, because that is the space [FaceCrop] works in and
     * what [assertLandsOnTemplate] checks. `alignFace` takes normalised fractions, so this divides
     * by the frame it is told to pretend the face was found in.
     *
     * ⚠ The default frame is SQUARE, which divides both axes by the same number — just another
     * similarity, and therefore invisible to the fit. Every test that does not care about aspect
     * ratio keeps its old meaning exactly.
     */
    private fun fit(
        face: FaceLandmarks,
        width: Int = FRAME,
        height: Int = FRAME,
    ): FaceCrop {
        fun f(p: FacePoint) = FacePoint(p.x / width, p.y / height)
        return alignFace(
            FaceLandmarks(f(face.leftEye), f(face.rightEye), f(face.noseTip), f(face.mouthLeft), f(face.mouthRight)),
            template,
            width,
            height,
        )
    }

    private fun assertLandsOnTemplate(crop: FaceCrop, face: FaceLandmarks, tolerance: Float = 0.01f) {
        val mapped = listOf(
            crop.map(face.leftEye) to template.leftEye,
            crop.map(face.rightEye) to template.rightEye,
            crop.map(face.noseTip) to template.noseTip,
            crop.map(face.mouthLeft) to template.mouthLeft,
            crop.map(face.mouthRight) to template.mouthRight,
        )
        mapped.forEach { (got, want) ->
            assertTrue(
                abs(got.x - want.x) < tolerance && abs(got.y - want.y) < tolerance,
                "landed at (${got.x}, ${got.y}), template says (${want.x}, ${want.y})",
            )
        }
    }

    @Test
    fun aFaceAlreadyOnTheTemplateNeedsNoTransform() {
        val crop = fit(alreadyAligned)

        assertTrue(abs(crop.scale - 1f) < 1e-4f, "scale was ${crop.scale}")
        assertTrue(abs(crop.sin) < 1e-4f, "no rotation, sin was ${crop.sin}")
        assertLandsOnTemplate(crop, alreadyAligned)
    }

    @Test
    fun aTranslatedFaceIsMovedBack() {
        val face = alreadyAligned.transformed(dx = 30f, dy = -12f)

        assertLandsOnTemplate(fit(face), face)
    }

    @Test
    fun aScaledFaceIsResized() {
        val face = alreadyAligned.transformed(scale = 3f)

        val crop = fit(face)

        assertTrue(abs(crop.scale - (1f / 3f)) < 1e-3f, "scale was ${crop.scale}")
        assertLandsOnTemplate(crop, face)
    }

    /** ⚠⚠ A tilted head is the case an eyes-only or box-only crop gets wrong. */
    @Test
    fun aRotatedFaceIsDerotated() {
        val face = alreadyAligned.transformed(degrees = 30.0)

        val crop = fit(face)

        assertLandsOnTemplate(crop, face)
        // ⚠⚠ And the decomposition itself must be a rotation. `map` only ever uses the PRODUCTS
        // `scale · cos` and `scale · sin`, so a fit that mis-splits the two — an inflated scale with a
        // non-unit "rotation" — maps every point correctly and is still wrong: a backend drives its
        // resampler from these fields, often by handing an angle to a platform API. A mutation that
        // dropped the cross term from the magnitude was invisible until this assertion existed.
        val unit = crop.cos * crop.cos + crop.sin * crop.sin
        assertTrue(abs(unit - 1f) < 1e-3f, "cos² + sin² was $unit, so this is not a rotation")
        assertTrue(abs(crop.scale - 1f) < 1e-3f, "a pure rotation must not rescale; was ${crop.scale}")
        // ⚠ NEGATIVE: the fixture rotates the template by +30° to make the face, so the fit that
        // carries it back is the inverse. Getting this sign backwards is how an aligner ends up
        // rotating every face the wrong way by twice the angle.
        assertTrue(abs(crop.sin + 0.5f) < 1e-3f, "expected sin -30° = -0.5, was ${crop.sin}")
    }

    @Test
    fun rotationScaleAndTranslationTogether() {
        val face = alreadyAligned.transformed(scale = 0.4f, degrees = -63.0, dx = 11f, dy = 5f)

        assertLandsOnTemplate(fit(face), face)
    }

    /**
     * ⚠⚠ **A similarity fit, never a full affine.** A face stretched along one axis must NOT be
     * un-stretched onto the template: shearing a person to fit hands the model a distorted face,
     * which is a worse input than a slightly misplaced crop. So the fit absorbs what it can and
     * leaves the rest as residual — the thing this test pins is that it does not become exact.
     */
    @Test
    fun aStretchedFaceIsNotShearedOntoTheTemplate() {
        val stretched = FaceLandmarks(
            leftEye = FacePoint(40f, 50f),
            rightEye = FacePoint(72f, 50f),
            noseTip = FacePoint(56f, 110f),
            mouthLeft = FacePoint(42f, 170f),
            mouthRight = FacePoint(70f, 170f),
        )

        val crop = fit(stretched)

        // ⚠ Measured over the whole constellation, not one point: a full affine fit would carry all
        // five onto the template exactly, and that is the substitution this test exists to catch.
        val residual = listOf(
            crop.map(stretched.leftEye) to template.leftEye,
            crop.map(stretched.rightEye) to template.rightEye,
            crop.map(stretched.noseTip) to template.noseTip,
            crop.map(stretched.mouthLeft) to template.mouthLeft,
            crop.map(stretched.mouthRight) to template.mouthRight,
        ).sumOf { (got, want) -> (abs(got.x - want.x) + abs(got.y - want.y)).toDouble() }

        assertTrue(residual > 5.0, "a similarity fit must leave a residual here; it was $residual")
    }

    /** ⚠ A degenerate constellation has no rotation to recover — answer defined values, not NaN. */
    @Test
    fun aDegenerateFaceProducesNoNaNs() {
        val single = FacePoint(10f, 10f)
        val degenerate = FaceLandmarks(single, single, single, single, single)

        val crop = fit(degenerate)

        listOf(crop.scale, crop.cos, crop.sin, crop.translateX, crop.translateY).forEach {
            assertTrue(it == it, "produced a NaN")
        }
    }

    @Test
    fun theCropCarriesTheModelsInputSize() {
        assertEquals(112, fit(alreadyAligned).side)
    }

    // ---- the coordinate space the caller must supply -------------------------------------------

    /**
     * ⚠⚠⚠ **Normalised detector coordinates are NOT a valid input on a non-square photograph**, and
     * this is the test that says so out loud.
     *
     * Dividing x by width and y by height *separately* is an anisotropic map. A similarity fit has a
     * single scale and cannot undo one, so the fit silently absorbs the `w/h` ratio as a stretch —
     * and because it still succeeds, nothing downstream complains. Portrait and landscape frames
     * stretch opposite ways, so the *same* face embeds into two unrelated regions of the model's
     * space depending on how the phone was held.
     *
     * It cost a false finding against the embedder before the cause was found — see `FaceCrop`'s
     * KDoc. ⚠ A square image hides it completely, which is how a synthetic ground-truth set scored
     * a perfect purity straight over the top of it.
     */
    /**
     * ⚠⚠⚠ **The same face must align identically whatever the frame's aspect ratio**, and this is
     * the property the whole `imageWidth`/`imageHeight` signature exists to buy.
     *
     * Fitting straight against normalised landmarks absorbed the aspect ratio as a `w/h` stretch, so
     * a portrait frame and a landscape frame put the same person in two unrelated regions of the
     * model's space. That produced a false finding against the embedder — one person's selfies split
     * into clusters whose centroids were orthogonal — before the cause was found. It is invisible on
     * a square input, so this test is deliberately NOT square.
     */
    @Test
    fun theSameFaceAlignsIdenticallyInEveryAspectRatio() {
        val face = alreadyAligned.transformed(scale = 3f, degrees = 17.0, dx = 120f, dy = 90f)

        val square = fit(face, 2000, 2000)
        val landscape = fit(face, 4000, 3000)
        val portrait = fit(face, 3000, 4000)

        listOf(landscape, portrait).forEach { other ->
            assertEquals(square.scale, other.scale, 1e-3f, "scale must not depend on the frame")
            assertEquals(square.cos, other.cos, 1e-3f, "nor the rotation")
            assertEquals(square.sin, other.sin, 1e-3f, "nor the rotation")
            assertEquals(square.translateX, other.translateX, 1e-2f)
            assertEquals(square.translateY, other.translateY, 1e-2f)
        }
        // ⚠ And it is a real fit, not a degenerate one that would match trivially.
        assertLandsOnTemplate(landscape, face)
    }

    /** ⚠ An image has a size; zero would divide every landmark into nothing and fit a point. */
    @Test
    fun anImageWithNoSizeIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            alignFace(alreadyAligned, template, imageWidth = 0, imageHeight = 100)
        }
    }

    /**
     * ⚠⚠ **The arithmetic `alignFace`'s signature now prevents**, shown directly.
     *
     * These landmarks have had one axis divided and the other not — exactly what normalising by
     * width and height does to a non-square frame. A similarity fit has one scale and cannot
     * represent that, so the five points do **not** land on the template however the fit is done.
     * This is the defect in its pure form: no exception, no NaN, a perfectly plausible crop of a
     * stretched person — which is why the conversion had to move inside `alignFace` rather than be
     * left to each caller.
     */
    @Test
    fun anAnisotropicallyScaledFaceCannotFitTheTemplate() {
        val aspect = 4f / 3f
        val squashed = FaceLandmarks(
            leftEye = FacePoint(alreadyAligned.leftEye.x * 20f / aspect, alreadyAligned.leftEye.y * 20f),
            rightEye = FacePoint(alreadyAligned.rightEye.x * 20f / aspect, alreadyAligned.rightEye.y * 20f),
            noseTip = FacePoint(alreadyAligned.noseTip.x * 20f / aspect, alreadyAligned.noseTip.y * 20f),
            mouthLeft = FacePoint(alreadyAligned.mouthLeft.x * 20f / aspect, alreadyAligned.mouthLeft.y * 20f),
            mouthRight = FacePoint(alreadyAligned.mouthRight.x * 20f / aspect, alreadyAligned.mouthRight.y * 20f),
        )

        val crop = fit(squashed)

        val residual = listOf(
            crop.map(squashed.leftEye) to template.leftEye,
            crop.map(squashed.rightEye) to template.rightEye,
            crop.map(squashed.noseTip) to template.noseTip,
            crop.map(squashed.mouthLeft) to template.mouthLeft,
            crop.map(squashed.mouthRight) to template.mouthRight,
        ).sumOf { (got, want) -> (abs(got.x - want.x) + abs(got.y - want.y)).toDouble() }

        assertTrue(residual > 5.0, "a 4:3 squash must not fit a similarity; residual was $residual")
    }

    /** ⚠ A UNIFORM division is just another similarity, so it must still be accepted and exact. */
    @Test
    fun aUniformlyScaledFaceIsStillFittedExactly() {
        val scaled = alreadyAligned.transformed(scale = 20f, dx = 900f, dy = 700f)

        assertLandsOnTemplate(fit(scaled), scaled)
    }

    // ---- unmap: the direction a resampler reads in ---------------------------------------------

    /**
     * ⚠⚠ **A rotated, scaled fit is the only case that separates a correct inverse from a wrong
     * one.** Against an identity-ish transform `1/scale` with the rotation transposed, negated, or
     * dropped entirely all agree, so a round trip through [FaceCrop.unmap] proves nothing unless the
     * crop actually rotates *and* rescales — which is why this fixture does both.
     */
    @Test
    fun unmapInvertsMapThroughARotatedScaledCrop() {
        val rotated = turnedBy(0.4f)
        val crop = fit(rotated)
        assertTrue(abs(crop.sin) > 0.1f, "the fixture must actually rotate, or the inverse is untested")
        assertTrue(abs(crop.scale - 1f) > 0.1f, "and actually rescale")

        listOf(
            FacePoint(0.1f, 0.9f),
            FacePoint(0.73f, 0.22f),
            template.noseTip,
        ).forEach { source ->
            val there = crop.unmap(crop.map(source))
            assertEquals(source.x, there.x, 1e-3f, "x round-trips")
            assertEquals(source.y, there.y, 1e-3f, "y round-trips")
        }
    }

    /**
     * ⚠ The property that actually matters downstream: the destination pixel where the template puts
     * an eye must read from the source pixel where that eye *is*. A resampler built on a wrong
     * inverse still produces a crop of a face — just not the one the template describes.
     */
    @Test
    fun theTemplatesEyeReadsFromTheFacesEye() {
        val rotated = turnedBy(0.4f)
        val crop = fit(rotated)

        val source = crop.unmap(template.leftEye)

        assertEquals(rotated.leftEye.x, source.x, 2e-2f)
        assertEquals(rotated.leftEye.y, source.y, 2e-2f)
    }

    /** ⚠ A degenerate fit divides by nothing rather than producing infinities downstream. */
    @Test
    fun unmapOfADegenerateCropIsFiniteRatherThanInfinite() {
        val degenerate = FaceCrop(scale = 0f, cos = 1f, sin = 0f, translateX = 5f, translateY = 5f, side = 112)

        assertEquals(FacePoint(0f, 0f), degenerate.unmap(FacePoint(10f, 10f)))
    }

    /** ⚠ A template with no square to read is a mistake, not a cautious default. */
    @Test
    fun aTemplateWithNoSideIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            FaceAlignmentTemplate(
                leftEye = FacePoint(0f, 0f),
                rightEye = FacePoint(1f, 0f),
                noseTip = FacePoint(0.5f, 0.5f),
                mouthLeft = FacePoint(0f, 1f),
                mouthRight = FacePoint(1f, 1f),
                side = 0,
            )
        }
    }
}
