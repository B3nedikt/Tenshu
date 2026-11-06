package org.tenshu.identity

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * List the shared realms this member belongs to — every [SharedRealmMembership]
 * row they hold (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3d.2b).
 * The read surface for the Drive *Shared with me* (inbound) section: callers split
 * outbound (`role = owner`) from inbound (`role = member`) and label each realm by
 * reading its wrapper-root name (3d.2b-i) via `listSharedFolder(sharedRealmId, …)`.
 *
 * **Why an op, not the chain DSL:** the same rationale as `listPlacements` /
 * `listSharedFolder` — dedicated ops are the shared-realm read surface. The
 * memberships are a flat, realm-wide collection the
 * `SharedRealmMembershipRelationMaintainer` derives; this op reads it directly.
 *
 * The op returns the full membership rows (mirroring `getDevices` returning full
 * `Device`s); `wrappedMasterKey` is inert ciphertext to the client (only the
 * proxy-held A13 private key can unwrap it), and the client reads only
 * `sharedRealmId` + `role`.
 */
suspend fun IdentityService.getSharedRealmMemberships(): TResult<TList<SharedRealmMembership>> =
    findOperation<GetSharedRealmMemberships>(GetSharedRealmMemberships.ID)
        .getSharedRealmMemberships()
        .asTaskInteractor<TList<SharedRealmMembership>>()
        .execute()

interface GetSharedRealmMemberships : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getSharedRealmMemberships(): RequestObject

    companion object {
        const val ID = "${IdentityService.ID}/getSharedRealmMemberships"

        val Schema = operation(
            id = ID,
            receiver = IdentityService.Schema.toType(),
            params = {},
            returnType = listType(SharedRealmMembership.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
