package org.tenshu.core.file.system

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * List the member's shared-realm mounts — every [Placement] they hold
 * (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3c.2b). The read
 * surface for the Drive *Shared* / *Shared with me* sections: each entry names a
 * mounted realm (`sharedRealmId`) and where it sits in the personal FS
 * (`parentPath` + `name`); open one by browsing it with
 * `listSharedFolder(sharedRealmId, …)`.
 *
 * Grouping into the two sections (outbound vs inbound, by `SharedRealmMembership`
 * role) is a UI concern finalised at Phase 3d; this op returns the flat set.
 *
 * **Why an op, not the chain DSL:** the same rationale as `listSharedFolder` —
 * dedicated ops are the shared-realm read surface. The placements are a flat,
 * realm-wide collection the `PlacementRelationMaintainer` derives; this op reads it
 * directly rather than exposing a `fileService.placements` chain projection nothing
 * else consumes.
 */
suspend fun FileSystem.listPlacements(): TResult<TList<Placement>> =
    findOperation<ListPlacements>(ListPlacements.ID)
        .listPlacements()
        .asTaskInteractor<TList<Placement>>()
        .execute()

interface ListPlacements : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun listPlacements(): RequestObject

    companion object {
        const val ID = "tenshu/file/system/listPlacements"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {},
            returnType = listType(Placement.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
