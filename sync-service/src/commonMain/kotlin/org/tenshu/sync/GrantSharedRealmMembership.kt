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
 * Grant a completed contact membership of a shared realm (GR-3,
 * `shared-realm-membership-grant.md` §§3–4). The owner mints the per-(member,
 * realm) authority key, signs its capability with the realm master, wraps Kr to the
 * contact's A13, and drops the sealed grant on the relay grant mailbox. Returns the
 * minted **member-authority public key** (hex) — the owner needs it to
 * [RemoveSharedRealmMember] the member later.
 *
 * Requires the realm to be registered as OWNER this session (the master key lives
 * only in the in-memory `RealmContext`) and the contact to carry a grant seed (a
 * handshake completed since GR-3). [label] is the advisory folder name for the
 * accept prompt.
 */
suspend fun SyncService.grantSharedRealmMembership(
    realmId: String,
    personId: String,
    label: String = "",
): TResult<TString> =
    findOperation<GrantSharedRealmMembership>(GrantSharedRealmMembership.ID)
        .grantSharedRealmMembership(realmId, personId, label)
        .asTaskInteractor<TString>()
        .execute()

interface GrantSharedRealmMembership : Operation {

    fun grantSharedRealmMembership(realmId: String, personId: String, label: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/grantSharedRealmMembership"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.REALM_ID)
                string(Params.PERSON_ID)
                string(Params.LABEL, isOptional = true)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val REALM_ID = "$ID/realmId"
        const val PERSON_ID = "$ID/personId"
        const val LABEL = "$ID/label"
    }
}
