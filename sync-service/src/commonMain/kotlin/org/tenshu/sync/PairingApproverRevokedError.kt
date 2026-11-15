package org.tenshu.sync

import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * The relay refused an approval because **this** device — the approver — may no longer admit:
 * its A33 admission capability (or a link of its chain) is revoked, or it was removed itself
 * (`403 admission-revoked`).
 *
 * ⭐ The twin of [PairingDeviceRemovedError], for the other `403` an admit route can answer
 * (`admission-revocation-names-the-capability.md` §7j.5). Rendered as the generic error it told the
 * user nothing and every retry was refused identically — what happened on 2026-11-11.
 *
 * ⚠ The fault is **this** device's, the opposite of [PairingDeviceRemovedError]. The remedy is to
 * remove this device and pair it again from another member, which mints it a fresh capability.
 * ⚠ Since §7j an *ancestor's* removal no longer causes this; a revocation still does (Q3).
 *
 * ⚠ A fieldless composite, not a [PairingFailure] member, for the reason [PairingDeviceRemovedError]
 * gives: a new constant owes a `SyncService` version bump that fences every not-yet-updated peer
 * (`core-schema-evolution.md` E2j-1b); a fieldless composite is not in the shape baseline.
 */
interface PairingApproverRevokedError : TError {
    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "${SyncService.ID}/error/pairingApproverRevoked"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {},
        )

        fun create(context: TContext): PairingApproverRevokedError = context.create(
            type = ID,
            fields = emptyList(),
        )
    }
}
