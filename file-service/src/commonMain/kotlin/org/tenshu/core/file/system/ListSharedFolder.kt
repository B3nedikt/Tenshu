package org.tenshu.core.file.system

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * List the children of folder [parentFileId] inside the shared realm [realmId]
 * (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3c). Returns the
 * realm's `SharedFile` children at that parent — the navigable tree level the
 * browse UI renders; pass a child folder's `fileId` as [parentFileId] to drill in
 * (the realm root is [SharedFile.ROOT_PARENT]).
 *
 * **Why an op, not the chain DSL:** a shared realm is browsed as its own file
 * system, but `RequestChain` only addresses entities by *static* relation ids
 * (`addReference`), so it can't root a chain at a runtime realm id without a
 * platform extension (parameterised-service resolution) — for no UX gain. This op
 * carries `realmId` as a parameter instead and returns the children array the
 * `SharedFileRelationMaintainer` derives; the UI drills by `parentFileId` exactly
 * as a personal folder browse walks parent→children.
 */
suspend fun FileSystem.listSharedFolder(
    realmId: String,
    parentFileId: String,
): TResult<TList<SharedFile>> =
    findOperation<ListSharedFolder>(ListSharedFolder.ID)
        .listSharedFolder(realmId, parentFileId)
        .asTaskInteractor<TList<SharedFile>>()
        .execute()

interface ListSharedFolder : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun listSharedFolder(realmId: String, parentFileId: String): RequestObject

    companion object {
        const val ID = "tenshu/file/system/listSharedFolder"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                string(id = Params.REALM_ID)
                string(id = Params.PARENT_FILE_ID)
            },
            returnType = listType(SharedFile.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val REALM_ID = "$ID/realmId"
        const val PARENT_FILE_ID = "$ID/parentFileId"
    }
}
