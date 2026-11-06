package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Record the user's out-of-band verification verdict for one key
 * (`contacts-app.md` D4 Option A + Q2a).
 *
 * ⚠⚠ **This is the only path to `VERIFIED`, and it exists only because a human asserted
 * something.** Nothing here is a cryptographic check: [verified] carries the user's answer
 * to *"did the six digits match?"*, compared over a channel they already trust. Verification
 * is never inferred, never a side effect of another action, and never auto-elevated
 * (threat-model A24). A caller that passes `true` without having shown the user
 * [GetContactSas] and had them confirm it is lying to the trust model.
 *
 * ⚠ **Re-verification needs no exchange at all.** `deriveContactSas` is over both public
 * keys and is session-independent, so each side derives the same digits locally and each
 * side records its own verdict. There is no round trip, no relay, no co-presence — this op
 * touches one local row (which then syncs to the user's own devices, like any personal-realm
 * Syncable).
 *
 * **[verified] carries both directions**, unlike D4's elevate-only `sasConfirmed`, because
 * Q2b also requires that manual un-verify be offered — a contact whose keyring went mixed needs a way back as well as forward. `false`
 * therefore means *demote*, not *leave alone*.
 *
 * **Rung handling.** Elevation is `UNVERIFIED → VERIFIED` and demotion is
 * `VERIFIED → UNVERIFIED`. [TenshuIdentity.Verification.ATTESTED] is **never touched** by
 * this op in either direction: it is carried by a Rei attestation chain, so a local
 * human verdict must neither claim it nor overwrite it.
 */
suspend fun ContactService.verifyTenshuIdentity(
    fingerprint: String,
    verified: Boolean,
): TResult<TBoolean> =
    findOperation<VerifyTenshuIdentity>(VerifyTenshuIdentity.ID)
        .verifyTenshuIdentity(fingerprint, verified)
        .asTaskInteractor<TBoolean>()
        .execute()

interface VerifyTenshuIdentity : Operation {

    override val schema get() = Schema

    fun verifyTenshuIdentity(fingerprint: String, verified: Boolean): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/verifyTenshuIdentity"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.FINGERPRINT)
                boolean(Params.VERIFIED)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val FINGERPRINT = "$ID/fingerprint"
        const val VERIFIED = "$ID/verified"
    }
}
