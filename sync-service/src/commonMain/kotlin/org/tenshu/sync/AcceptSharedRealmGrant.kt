package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Accept a pending shared-realm membership grant from the contact whose
 * `TenshuIdentity` key is [grantorFingerprint] (GR-3, D4). Picks up + unseals the
 * sealed grant from the relay mailbox, registers the realm with a fresh per-realm
 * device key, persists the A26 keypair + membership, admits this device, and writes
 * the in-realm `Member` record (the acceptance signal the owner sees). Returns the
 * joined realm's `ownerId`.
 *
 * Decline/ignore is simply not calling this — the mailbox record TTL-expires. The
 * quarantined-pending list + poll UI is GR-4.
 */
suspend fun SyncService.acceptSharedRealmGrant(grantorFingerprint: String): TResult<TString> =
    findOperation<AcceptSharedRealmGrant>(AcceptSharedRealmGrant.ID)
        .acceptSharedRealmGrant(grantorFingerprint)
        .asTaskInteractor<TString>()
        .execute()

interface AcceptSharedRealmGrant : Operation {

    fun acceptSharedRealmGrant(grantorFingerprint: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/acceptSharedRealmGrant"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.GRANTOR_FINGERPRINT)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val GRANTOR_FINGERPRINT = "$ID/grantorFingerprint"
    }
}
