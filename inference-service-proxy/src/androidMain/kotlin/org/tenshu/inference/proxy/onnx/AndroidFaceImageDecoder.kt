package org.tenshu.inference.proxy.onnx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import org.tenshu.inference.proxy.vision.FACE_DECODE_MAX_EDGE
import org.tenshu.inference.proxy.vision.FaceImage
import org.tenshu.inference.proxy.vision.FaceImageDecoder
import java.io.ByteArrayInputStream

/**
 * Android's upright decode — `MediaPipeFaceBackend.decodeUpright`'s body, shared so Pets decodes the
 * frame People does (`photos-pets.md` PT-3): a power-of-two `inSampleSize` to [maxEdge] on the long side,
 * then the bytes' EXIF orientation (or [orientationOverride], 1–8).
 *
 * ⚠⚠ Moved, not changed: the face pipeline's measured numbers (F2m) were taken over exactly this decode.
 */
internal fun decodeUprightBitmap(bytes: ByteArray, orientationOverride: Int?, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
    val decoded = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull() ?: return null
    // ⚠ The bytes' own EXIF unless a caller overrides it (1–8). The indexer never does: a
    // preview rung is stored upright (`ExifUpright`), an original carries its tag.
    val orientation = orientationOverride ?: runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        // ⚠ The two ROTATED mirrors — handling only the plain flips leaves these sideways.
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return decoded
    }
    val rotated = runCatching {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }.getOrNull() ?: return decoded
    if (rotated !== decoded) decoded.recycle()
    return rotated
}

/**
 * The face path's decode as a [FaceImageDecoder], for Pets' `PixelPetBackend`: the same bound
 * ([FACE_DECODE_MAX_EDGE]) and orientation as People's, packed ARGB out.
 */
object AndroidFaceImageDecoder : FaceImageDecoder {
    override fun decodeUpright(bytes: ByteArray, orientation: Int?): FaceImage? {
        if (orientation != null && orientation !in 1..8) return null
        val bitmap = decodeUprightBitmap(bytes, orientation, FACE_DECODE_MAX_EDGE) ?: return null
        return try {
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            FaceImage(bitmap.width, bitmap.height, argb)
        } finally {
            bitmap.recycle()
        }
    }
}
