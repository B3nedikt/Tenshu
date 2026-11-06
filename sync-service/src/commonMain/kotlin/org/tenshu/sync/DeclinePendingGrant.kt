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
 * GR-6: decline (discard) the quarantined `PendingGrant` from [grantorFingerprint]
 * (`shared-realm-membership-grant.md` §12, D4). Tombstones the stored sealed blob —
 * the grant is lost (the owner can re-grant); the card disappears on the user's
 * other devices via the tombstone sync.
 */
suspend fun SyncService.declinePendingGrant(grantorFingerprint: String): TResult<Data?> =
    findOperation<DeclinePendingGrant>(DeclinePendingGrant.ID)
        .declinePendingGrant(grantorFingerprint)
        .asTaskInteractor<Data?>()
        .execute()

interface DeclinePendingGrant : Operation {

    fun declinePendingGrant(grantorFingerprint: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/declinePendingGrant"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.GRANTOR_FINGERPRINT)
            },
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val GRANTOR_FINGERPRINT = "$ID/grantorFingerprint"
    }
}
