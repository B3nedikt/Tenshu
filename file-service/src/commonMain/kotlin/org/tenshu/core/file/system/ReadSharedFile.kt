package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Read the bytes of the shared file [fileId] in realm [realmId]
 * (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3c). The
 * shared-realm counterpart of opening a personal file's `File.Data`.
 *
 * On a member that doesn't yet hold the bytes this drives the OnDemand fetch: the
 * impl dispatches `OnDemandResolution.resolve(sharedFileDataId, SharedFile.DATA_PROPERTY_ID)`,
 * which fetches the file's chunks from the **realm's** content store, the
 * realm-aware `FileContentManifestApplier` decrypts (under the realm's keys) +
 * integrity-gates + reassembles them onto the realm byte-path, and the impl reads
 * the materialised bytes back. Returns `null` if the bytes aren't available yet
 * (entity/manifest not synced) — the caller retries.
 *
 * (A dedicated op rather than a `relations(SharedFile.Data)` chain for the same
 * reason as `ListSharedFolder` — `RequestChain` can't root at a runtime realm id.)
 */
suspend fun FileSystem.readSharedFile(
    realmId: String,
    fileId: String,
): TResult<Data?> =
    findOperation<ReadSharedFile>(ReadSharedFile.ID)
        .readSharedFile(realmId, fileId)
        .asTaskInteractor<Data?>()
        .execute()

interface ReadSharedFile : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun readSharedFile(realmId: String, fileId: String): RequestObject

    companion object {
        const val ID = "tenshu/file/system/readSharedFile"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.REALM_ID)
                string(id = Params.FILE_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val REALM_ID = "$ID/realmId"
        const val FILE_ID = "$ID/fileId"
    }
}
