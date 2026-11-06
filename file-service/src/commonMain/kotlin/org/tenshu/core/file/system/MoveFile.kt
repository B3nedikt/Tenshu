package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FilePath
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun FileSystem.moveFile(sourcePath: String, targetPath: String): TResult<Data?> =
    findOperation<MoveFile>(MoveFile.ID)
        .moveFile(sourcePath, targetPath)
        .asTaskInteractor<Data?>()
        .execute()

/**
 * Move or rename the file or folder at `sourcePath` to `targetPath`.
 *
 * A move is pure metadata: the file keeps its `contentId`, so its bytes never move, are never
 * re-encrypted, and this device need not hold them (`file-content-addressing.md` §8.5).
 *
 * Refused, before the first mutation and for the whole subtree:
 *  - with [StillSyncingError] while any part of the subtree is a provisional slice-snapshot row or
 *    a folder whose member list this store does not hold whole — retry once it has caught up;
 *  - with [MoveNeedsLocalBytesError] when a file in the subtree has no local bytes and the device
 *    is offline, or the relay does not confirm a write (`content-plane-cr2-b6-spec.md` D3) —
 *    download it first, or move it once online.
 */
interface MoveFile : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun moveFile(sourcePath: String, targetPath: String): RequestObject

    companion object {
        const val ID = "tenshu/file/system/moveFile"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.SOURCE_PATH, type = FilePath.Schema)
                string(id = Params.TARGET_PATH, type = FilePath.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val SOURCE_PATH = "$ID/sourcePath"
        const val TARGET_PATH = "$ID/targetPath"
    }
}
