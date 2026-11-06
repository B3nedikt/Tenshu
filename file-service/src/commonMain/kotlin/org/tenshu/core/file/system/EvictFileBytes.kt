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

/**
 * Drop the **local** bytes of the file at [path] while leaving the synced
 * `File` entity (path, size, created/modified, history) untouched — the
 * "Remove download" / make-online-only half of Drive's offline toggle
 * (`docs/proposals/drive-offline-online.md` §2). The file's `data` is
 * `Syncable(OnDemand)`, so a later open simply re-fetches the bytes from the
 * relay's content store; this op only frees the on-disk copy.
 *
 * Contrast with [DeleteFile], which removes the entity from its parent folder
 * and emits a tombstone. Eviction is metadata-preserving and tombstone-free: it
 * must never look like a delete to other realm devices.
 *
 * ⚠ Eviction can be refused: while the bytes are the **only copy** (an upload that has not
 * drained to the relay) it answers [ContentPinnedError] and keeps them. A caller that showed the
 * file as online-only optimistically must revert on that answer.
 */
suspend fun FileSystem.evictFileBytes(path: String): TResult<Data?> =
    findOperation<EvictFileBytes>(EvictFileBytes.ID)
        .evictFileBytes(path)
        .asTaskInteractor<Data?>()
        .execute()

interface EvictFileBytes : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun evictFileBytes(path: String): RequestObject

    companion object {
        const val ID = "tenshu/file/system/evictFileBytes"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.PATH, type = FilePath.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PATH = "$ID/path"
    }
}
