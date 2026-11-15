package app.tenshu.photos.edit

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import app.tenshu.photos.data.EditedCopyUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.system.uploadSourceFile
import org.tenshu.file.client.fileService
import java.io.File
import java.util.UUID

/**
 * Android's edited-copy upload (`photos-editing.md` §9.2): the JPEG goes to a file in this app's
 * cache and Control reads it through the descriptor the transport pushes ahead of the call
 * (`sendFile`), the way Drive uploads a picked file.
 *
 * ⚠⚠ Not the inline `UploadFile`: an app → Control request is one Binder transaction, and a
 * multi-megabyte JPEG in it fails with `TransactionTooLargeException` ([EditedCopyUpload]).
 *
 * ⚠ `UploadSourceFile` carries a capture time but no place, so the place travels in the file: a
 * **GPS-only** EXIF block, orientation written as 1 (the pixels are already upright — any other tag
 * would turn them twice). The proxy reads the coordinate from the bytes as it does for every upload.
 * Nothing of the original's own EXIF is copied (`photos-creation-features.md` §9.2).
 */
class AndroidEditedCopyUpload(private val context: Context) : EditedCopyUpload {

    override suspend fun upload(
        client: TenshuClient,
        folder: String,
        fileName: String,
        bytes: ByteArray,
        capturedAtMs: Long,
        latE7: Long?,
        lngE7: Long?,
    ): TResult<Data?> {
        val dir = File(context.cacheDir, "edited-copies").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        try {
            withContext(Dispatchers.IO) {
                file.writeBytes(bytes)
                ExifInterface(file.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    if (latE7 != null && lngE7 != null) {
                        setAttribute(ExifInterface.TAG_GPS_LATITUDE, gpsRational(latE7))
                        setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (latE7 >= 0) "N" else "S")
                        setAttribute(ExifInterface.TAG_GPS_LONGITUDE, gpsRational(lngE7))
                        setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lngE7 >= 0) "E" else "W")
                    }
                    saveAttributes()
                }
            }
            return client.fileService.defaultFileSystem.uploadSourceFile(
                parentPath = folder,
                fileName = fileName,
                uri = Uri.fromFile(file).toString(),
                sourceModifiedAt = capturedAtMs,
            )
        } finally {
            // ⚠ The descriptor was pushed when the call was made; the proxy holds its own dup.
            withContext(Dispatchers.IO) { file.delete() }
        }
    }
}

/**
 * An E7 coordinate as EXIF's degrees/minutes/seconds rationals, e.g. `48/1,51/1,23760/1000`.
 * ⚠ Written by hand: the platform `ExifInterface.setLatLong` is not public at `minSdk` 24.
 */
internal fun gpsRational(e7: Long): String {
    val micro = kotlin.math.abs(e7) // 1e-7 degrees
    val degrees = micro / 10_000_000
    val minutesE7 = (micro % 10_000_000) * 60
    val minutes = minutesE7 / 10_000_000
    val secondsMilli = (minutesE7 % 10_000_000) * 60 / 10_000
    return "$degrees/1,$minutes/1,$secondsMilli/1000"
}
