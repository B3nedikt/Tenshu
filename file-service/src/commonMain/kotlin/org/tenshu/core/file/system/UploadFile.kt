package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun FileSystem.uploadFile(
    parentPath: String,
    fileName: String,
    data: ByteArray,
    sourceModifiedAt: Long? = null,
    captureLatE7: Long? = null,
    captureLngE7: Long? = null,
): TResult<Data?> {
    return findOperation<UploadFile>(UploadFile.ID)
        .uploadFile(
            parentPath = parentPath,
            fileName = fileName,
            data = data,
            sourceModifiedAt = sourceModifiedAt,
            captureLatE7 = captureLatE7,
            captureLngE7 = captureLngE7,
        )
        .asTaskInteractor<Data?>()
        .execute()
}

interface UploadFile : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun uploadFile(
        parentPath: String,
        fileName: String,
        data: ByteArray,
        sourceModifiedAt: Long? = null,
        captureLatE7: Long? = null,
        captureLngE7: Long? = null,
    ): RequestObject

    companion object {
        const val ID = "tenshu/file/system/uploadFiles"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.PARENT_PATH, type = FilePath.Schema)
                string(id = Params.FILE_NAME, type = FilePath.Schema)
                byteArray(id = Params.DATA)
                // Optional: the source file's own last-modified time (epoch millis),
                // read client-side (e.g. desktop `File.lastModified()`) so the proxy
                // seeds File.CreatedAt and File.ContentModifiedAt from it rather than the
                // import moment. Absent → the proxy falls back to `now`.
                int(id = Params.SOURCE_MODIFIED_AT, isOptional = true)
                // ⚠⚠ Optional: where the photograph was taken, when the CLIENT knows better than
                // the bytes do (`takeout-import-and-recall.md` TB-D4). A Google Photos export
                // keeps the coordinate in a JSON sidecar beside the image and frequently strips or
                // reduces the image's own EXIF — so `readExifLocation` finds nothing, and the only
                // thing that knows is the caller holding the sidecar.
                //
                // ⚠ The only caller is the one holding a sidecar: Android hands the proxy unredacted
                // bytes (`setRequireOriginal`), so the proxy reads the coordinate from EXIF itself.
                int(id = Params.CAPTURE_LAT_E7, isOptional = true)
                int(id = Params.CAPTURE_LNG_E7, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PARENT_PATH = "${ID}/path"
        const val FILE_NAME = "${ID}/uri"
        const val DATA = "${ID}/data"
        const val SOURCE_MODIFIED_AT = "${ID}/sourceModifiedAt"
        const val CAPTURE_LAT_E7 = "${ID}/captureLatE7"
        const val CAPTURE_LNG_E7 = "${ID}/captureLngE7"
    }
}
