package org.tenshu.inference.proxy

/**
 * MediaPipe Face Landmarker's 468-point mesh reduced to what the rest of IN-6 consumes — lifted out
 * of the F2m harness, where it was measured, so the shipped backend and the measurement cannot
 * drift apart (`photos-people-and-pets.md` §12).
 *
 * ⚠⚠ **Indices are named by IMAGE position, never by the subject's anatomy.** OpenCV and InsightFace
 * call the same point opposite things, and a review once turned that into an instruction to swap
 * the order — which mirrors every crop without any arithmetic noticing (§10 Q2). `alignFace` wants
 * the eye on the image's left first, so that is what these names say.
 */
object FaceMesh {
    const val POINTS = 468

    private const val EYE_IMAGE_LEFT_OUTER = 33
    private const val EYE_IMAGE_LEFT_INNER = 133
    private const val EYE_IMAGE_RIGHT_INNER = 362
    private const val EYE_IMAGE_RIGHT_OUTER = 263
    private const val NOSE_TIP = 1
    private const val MOUTH_IMAGE_LEFT = 61
    private const val MOUTH_IMAGE_RIGHT = 291

    /**
     * The five points the shared alignment wants, in image fractions — `FacePoint`'s contract, and
     * what MediaPipe reports. `alignFace` converts to pixels itself, so no caller can forget to.
     *
     * ⚠ Eye centres are corner midpoints: the mesh has no pupil point outside the iris extension,
     * and the midpoint is what the measured pipeline used.
     *
     * @return `null` for a mesh too short to carry these indices — a model that is not the one
     *   measured, which must not be guessed around.
     */
    fun landmarks(mesh: List<FacePoint>): FaceLandmarks? {
        if (mesh.size < POINTS) return null
        fun mid(a: Int, b: Int) = FacePoint((mesh[a].x + mesh[b].x) / 2f, (mesh[a].y + mesh[b].y) / 2f)
        return FaceLandmarks(
            leftEye = mid(EYE_IMAGE_LEFT_OUTER, EYE_IMAGE_LEFT_INNER),
            rightEye = mid(EYE_IMAGE_RIGHT_INNER, EYE_IMAGE_RIGHT_OUTER),
            noseTip = mesh[NOSE_TIP],
            mouthLeft = mesh[MOUTH_IMAGE_LEFT],
            mouthRight = mesh[MOUTH_IMAGE_RIGHT],
        )
    }

    /** The mesh's extent, in the same fractions. ⚠ Position, not identity — never used to align. */
    fun box(mesh: List<FacePoint>): FaceBox = FaceBox(
        left = mesh.minOf { it.x },
        top = mesh.minOf { it.y },
        right = mesh.maxOf { it.x },
        bottom = mesh.maxOf { it.y },
    )
}
