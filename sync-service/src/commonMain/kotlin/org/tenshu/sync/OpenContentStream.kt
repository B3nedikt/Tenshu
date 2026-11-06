package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Open a seekable content stream for an OnDemand byte property and return a
 * [ContentStreamHandle] (D2b, `docs/proposals/drive-streaming-preview.md` §3.5). The pull
 * counterpart to a whole-file `getRemoteFile`/`readSharedFile`: it resolves the file's manifest
 * (no reassemble to disk) and registers a proxy-side session; subsequent [ReadContentRange] calls
 * decrypt only the chunks a range touches.
 *
 * **Addressed generically** by the OnDemand resolve triple `(entityType, entityKey, propertyId)`
 * rather than a path, because `:sync-service` deliberately cannot see the `:file-service` `File`
 * type (commonMain layering — the same reason `FileContentManifestApplier` routes via the
 * `ExternalPropertyWriter` port). The file-aware caller fills the triple:
 *  - personal file:  `(File.ID, path, File.Data.id)`
 *  - shared file:    `(SharedFile.ID, SharedFile.compositeKey(realmId, fileId), SharedFile.DATA_PROPERTY_ID)`
 * so this one op covers both cases (no separate shared variant needed). A friendly
 * `openStreamingContent(path)` wrapper lives client-side (Drive), see §6 / SQ8.
 */
suspend fun SyncService.openContentStream(
    entityType: String,
    entityKey: String,
    propertyId: String,
): TResult<ContentStreamHandle> =
    findOperation<OpenContentStream>(OpenContentStream.ID)
        .openContentStream(entityType, entityKey, propertyId)
        .asTaskInteractor<ContentStreamHandle>()
        .execute()

interface OpenContentStream : Operation {

    fun openContentStream(entityType: String, entityKey: String, propertyId: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/openContentStream"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.ENTITY_TYPE)
                string(Params.ENTITY_KEY)
                string(Params.PROPERTY_ID)
            },
            returnType = ContentStreamHandle.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val ENTITY_TYPE = "$ID/entityType"
        const val ENTITY_KEY = "$ID/entityKey"
        const val PROPERTY_ID = "$ID/propertyId"
    }
}
