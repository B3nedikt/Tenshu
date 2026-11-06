package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
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
 * Point one key at a different contact (`docs/proposals/contacts-app.md` D7).
 *
 * It has two callers. One fixes a mis-link: the handshake completion prompt offers *"link to
 * an existing contact"*, and picking the wrong one otherwise has no remedy short of unlinking
 * the key and re-doing the handshake. The other is merge, which this is a piece of.
 *
 * ⚠⚠ **Re-pointing a key never moves its verification with it, because verification is *on*
 * the key.** That is not an implementation detail to preserve carefully — it falls out of the
 * model: `TenshuIdentity` carries its own `verification`, and this op writes only `personId`.
 * A contact does not become verified by acquiring a verified key any more than they become
 * unverified by acquiring an unverified one; the Q2b rule reads the whole keyring afterwards
 * and answers accordingly.
 *
 * [personId] `""` detaches the key without deleting it — the *not-yet-linked candidate* state.
 * ⚠ Do not use that as "deliberately detached": `UnlinkTenshuIdentity` deletes the row, and
 * the two states must not collapse.
 */
suspend fun ContactService.linkTenshuIdentity(
    fingerprint: String,
    personId: String,
): TResult<TBoolean> =
    findOperation<LinkTenshuIdentity>(LinkTenshuIdentity.ID)
        .linkTenshuIdentity(fingerprint, personId)
        .asTaskInteractor<TBoolean>()
        .execute()

interface LinkTenshuIdentity : Operation {

    override val schema get() = Schema

    fun linkTenshuIdentity(fingerprint: String, personId: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/linkTenshuIdentity"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.FINGERPRINT)
                string(Params.PERSON_ID)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val FINGERPRINT = "$ID/fingerprint"
        const val PERSON_ID = "$ID/personId"
    }
}

/**
 * Fold [sourcePersonId] into [targetPersonId]: every key, source record and photo the source
 * holds becomes the target's, and the source row is deleted (D7). Fails when the two ids are
 * equal or either is `""` (the not-yet-linked marker), or when the target does not exist.
 *
 * ⚠⚠ **This is only ever called on an explicit user confirmation.** D7's rule is *never
 * auto-merge*, and the reason is not tidiness: everything a handshake delivers is
 * self-asserted, so a system that merged on a name match would let an attacker's key inherit
 * a known person's contact card. There is deliberately no heuristic anywhere in this service
 * — candidate *suggestion* lives in the app, where a human sees it and decides.
 *
 * **Why merge is safe to get wrong:** trust lives on the key, so a mis-merge is a cosmetic
 * error, not a security one — the merged contact's badge is whatever its combined keyring
 * says (a verified key beside an unverified one demotes to *key changed*, Q2b, which is
 * exactly right for a contact that just acquired an unfamiliar key).
 *
 * **What is kept:**
 *  - the target's petname — the user picked the target, so they picked the name;
 *  - **both** contacts' source records, since a person legitimately has several and the
 *    detail screen already renders their union. Nothing imported is discarded.
 *  - the target's photo if it has one, otherwise the source's.
 *
 * ⚠ **There is no un-merge.** The source `Person` row is gone, and the rows that moved carry
 * no memory of where they came from. Recovering means re-creating a contact and re-pointing
 * the keys by hand — possible, since [LinkTenshuIdentity] exists, but not a button. That is
 * the cost of the sibling-row model being genuinely simple, and it is why the confirmation
 * has to show what is about to be combined rather than just naming two people.
 */
suspend fun ContactService.mergePersons(
    sourcePersonId: String,
    targetPersonId: String,
): TResult<TBoolean> =
    findOperation<MergePersons>(MergePersons.ID)
        .mergePersons(sourcePersonId, targetPersonId)
        .asTaskInteractor<TBoolean>()
        .execute()

interface MergePersons : Operation {

    override val schema get() = Schema

    fun mergePersons(sourcePersonId: String, targetPersonId: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/mergePersons"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.SOURCE_PERSON_ID)
                string(Params.TARGET_PERSON_ID)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val SOURCE_PERSON_ID = "$ID/sourcePersonId"
        const val TARGET_PERSON_ID = "$ID/targetPersonId"
    }
}
