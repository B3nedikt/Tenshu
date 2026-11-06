package org.tenshu.inference.proxy

import kotlin.math.sqrt

/**
 * Where an identity model expects the five points to land in its own input square.
 *
 * ⚠⚠ **No default, and no production instance yet** — the same refusal [FaceSimilarityThreshold]
 * makes. A template is a property of the *checkpoint*: ArcFace-class models are trained at 112×112
 * against one published constellation, others differ, and a template remembered rather than read
 * produces crops that are subtly off for every photograph on every device. F2 supplies it from the
 * checkpoint's own documentation when a model is pinned.
 *
 * ⚠ Points are in the destination square's pixels, not fractions, because that is how every
 * published template is stated — converting them by hand is how a transcription error enters.
 */
data class FaceAlignmentTemplate(
    val leftEye: FacePoint,
    val rightEye: FacePoint,
    val noseTip: FacePoint,
    val mouthLeft: FacePoint,
    val mouthRight: FacePoint,
    /** The square the model consumes — 112 for ArcFace-class, 160 for FaceNet-class. */
    val side: Int,
) {
    init {
        require(side > 0) { "an alignment template needs a positive side, was $side" }
    }

    internal val points: List<FacePoint>
        get() = listOf(leftEye, rightEye, noseTip, mouthLeft, mouthRight)
}

/**
 * The crop to take: rotate, scale and translate the source image so the face's five points land on
 * the template's, then read [FaceAlignmentTemplate.side] pixels square.
 *
 * ⚠ A **similarity** transform — rotation, uniform scale, translation — never a full affine. Letting
 * the fit shear or scale the axes independently would stretch a face to match the template and hand
 * the model a distorted person, which is worse than a slightly misplaced crop.
 *
 * The matrix maps a source point `(x, y)` to a destination pixel:
 * ```
 * dstX = scale * (cos * x - sin * y) + translateX
 * dstY = scale * (sin * x + cos * y) + translateY
 * ```
 *
 * ⚠⚠ **This transform works in SOURCE PIXELS**, while [FaceLandmarks] are normalised fractions of
 * the image — [alignFace] converts between the two, and takes the image's size for exactly that
 * reason. A caller resampling through [unmap] therefore gets source pixels back and needs no
 * conversion of its own.
 *
 * ⚠⚠⚠ **Why the conversion is not the caller's job.** A similarity transform has ONE scale, which
 * is only meaningful in an isotropic space. Normalised coordinates divide x by width and y by
 * height *separately*, so on a non-square photograph the two axes carry different pixel scales and
 * that single scale silently becomes a `w/h` stretch — portrait and landscape frames stretching
 * opposite ways. Fitting straight against normalised landmarks cost a **false finding against the
 * model**: one person's selfies split into two clusters whose centroids were orthogonal (-0.014,
 * where *different people* scored +0.008), written up as a domain gap in the embedder before the
 * cause was found. Converting first merged them into one cluster of 29, stable across a 0.20–0.52
 * threshold range (`photos-people-and-pets.md` §12.5).
 *
 * ⚠ It is invisible on a square input, which is why a synthetic 336×336 ground-truth set scored
 * purity 1.000 straight over the top of it — and why the conversion lives here, where no backend
 * can forget it, rather than in five of them.
 */
data class FaceCrop(
    val scale: Float,
    val cos: Float,
    val sin: Float,
    val translateX: Float,
    val translateY: Float,
    val side: Int,
) {
    /** Where [point] lands in the aligned square. ⚠ The backend's own resampler does the pixels. */
    fun map(point: FacePoint): FacePoint = FacePoint(
        x = scale * (cos * point.x - sin * point.y) + translateX,
        y = scale * (sin * point.x + cos * point.y) + translateY,
    )

    /**
     * Which source point — in the same space [map] was given — a destination pixel reads from.
     * The inverse of [map]. ⚠⚠ Pixels, not fractions: see the class KDoc.
     *
     * ⚠⚠ **This is the direction a resampler actually needs**, and it lives here rather than in each
     * backend for [alignFace]'s own reason: inverting a similarity transform by hand is four lines
     * that are easy to get subtly wrong (transposing the rotation is not the same as negating its
     * angle once a scale is involved), and a backend that got it wrong would still produce a
     * plausible crop of a face — just not the one the template describes. Every platform inverting
     * it privately is the divergence `alignFace` exists to prevent, one step later.
     */
    fun unmap(point: FacePoint): FacePoint {
        // ⚠ `scale` is the fit's magnitude ratio and is zero only for a degenerate constellation,
        // which `alignFace` already guards; this keeps a caller from dividing by it regardless.
        if (scale == 0f) return FacePoint(0f, 0f)
        val dx = point.x - translateX
        val dy = point.y - translateY
        return FacePoint(
            x = (cos * dx + sin * dy) / scale,
            y = (-sin * dx + cos * dy) / scale,
        )
    }
}

/**
 * Fit the similarity transform that best carries [landmarks] onto [template] (IN-6, F1).
 *
 * ⚠⚠ **This is the half that makes "our model everywhere" mean anything.** Vision's boxes are tight
 * and any detector we ship elsewhere frames differently, so a backend cropping to its own taste
 * would land the same face in a different region of the same model's space on each platform —
 * silently, because the arithmetic succeeds either way. Sharing the weights buys comparability only
 * if the alignment is shared too, which is why this lives in `commonMain` and is tested on the JVM
 * rather than sitting inside each backend.
 *
 * ⚠ Least-squares over all five points rather than the two eyes: an eyes-only fit is exact but
 * throws away three measurements, and it rotates a turned head to the wrong roll. This is the closed
 * form of Umeyama's similarity fit, which for the 2-D case reduces to the arithmetic below.
 *
 * ⚠⚠ [imageWidth] and [imageHeight] are **required, not conveniences**: [FaceLandmarks] are
 * fractions of the image (every detector reports them that way — MediaPipe's `NormalizedLandmark`,
 * Vision's `pointsInImage`), and fractions are anisotropic on a non-square frame. Taking the size
 * here makes the conversion impossible for a backend to forget; see [FaceCrop] for what forgetting
 * it cost.
 *
 * @param imageWidth the source image's width in pixels — of the bitmap the landmarks were produced
 *   from, *after* any EXIF rotation or downscale the caller applied.
 */
fun alignFace(
    landmarks: FaceLandmarks,
    template: FaceAlignmentTemplate,
    imageWidth: Int,
    imageHeight: Int,
): FaceCrop {
    require(imageWidth > 0 && imageHeight > 0) {
        "an image has a positive size; was ${imageWidth}x$imageHeight"
    }
    val w = imageWidth.toFloat()
    val h = imageHeight.toFloat()
    // ⚠ Fractions → pixels, once, before anything is fitted.
    val source = listOf(
        landmarks.leftEye,
        landmarks.rightEye,
        landmarks.noseTip,
        landmarks.mouthLeft,
        landmarks.mouthRight,
    ).map { FacePoint(it.x * w, it.y * h) }
    val destination = template.points
    val n = source.size

    val sourceMean = centroid(source)
    val destinationMean = centroid(destination)

    var covarianceA = 0f // Σ (src · dst) — the rotation-aligned part
    var covarianceB = 0f // Σ (src × dst) — the cross term that carries the angle
    var sourceVariance = 0f
    for (i in 0 until n) {
        val sx = source[i].x - sourceMean.x
        val sy = source[i].y - sourceMean.y
        val dx = destination[i].x - destinationMean.x
        val dy = destination[i].y - destinationMean.y
        covarianceA += sx * dx + sy * dy
        covarianceB += sx * dy - sy * dx
        sourceVariance += sx * sx + sy * sy
    }

    // ⚠ A degenerate constellation — every point identical — has no rotation to recover. Answering
    // "no rotation, no scale" keeps the caller on a defined path; a NaN matrix would sail through
    // every arithmetic check downstream and produce a black crop.
    if (sourceVariance == 0f) {
        return FaceCrop(
            scale = 0f,
            cos = 1f,
            sin = 0f,
            translateX = destinationMean.x,
            translateY = destinationMean.y,
            side = template.side,
        )
    }

    val magnitude = sqrt(covarianceA * covarianceA + covarianceB * covarianceB)
    val scale = magnitude / sourceVariance
    val cos = if (magnitude == 0f) 1f else covarianceA / magnitude
    val sin = if (magnitude == 0f) 0f else covarianceB / magnitude

    return FaceCrop(
        scale = scale,
        cos = cos,
        sin = sin,
        translateX = destinationMean.x - scale * (cos * sourceMean.x - sin * sourceMean.y),
        translateY = destinationMean.y - scale * (sin * sourceMean.x + cos * sourceMean.y),
        side = template.side,
    )
}

private fun centroid(points: List<FacePoint>): FacePoint {
    var x = 0f
    var y = 0f
    points.forEach { x += it.x; y += it.y }
    return FacePoint(x / points.size, y / points.size)
}
