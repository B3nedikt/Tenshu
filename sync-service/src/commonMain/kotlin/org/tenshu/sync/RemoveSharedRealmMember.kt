package org.tenshu.sync

import org.tenshu.core.Data
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
 * Remove the member with in-realm id [memberId] (`hash(memberAuthorityPub)` — the
 * `Member.memberId` the roster shows) from shared realm [realmId] (GR-3/GR-6,
 * D6/D9-a). The op resolves the member-authority public key from the owner-side
 * `GrantedMembership` roster (GR-6), posts a master-signed revocation (the relay
 * stops admitting that authority + purges its devices — the immediate cutoff), and
 * tombstones the member's `Member` row + the roster row. **Kr rotation is GR-7**
 * (the content layer has no key-versioning yet). Owner-only (needs the realm master
 * key — now durable across sessions, GR-6).
 */
suspend fun SyncService.removeSharedRealmMember(
    realmId: String,
    memberId: String,
): TResult<Data?> =
    findOperation<RemoveSharedRealmMember>(RemoveSharedRealmMember.ID)
        .removeSharedRealmMember(realmId, memberId)
        .asTaskInteractor<Data?>()
        .execute()

interface RemoveSharedRealmMember : Operation {

    fun removeSharedRealmMember(realmId: String, memberId: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/removeSharedRealmMember"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.REALM_ID)
                string(Params.MEMBER_ID)
            },
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val REALM_ID = "$ID/realmId"
        const val MEMBER_ID = "$ID/memberId"
    }
}
