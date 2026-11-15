package org.tenshu.sync

import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * The relay refused to admit **this** device: it was removed from the realm it is joining and is
 * trying to come back under the same identity (F2′, `403 device-removed`). The **joiner's** half of
 * [PairingDeviceRemovedError] — same refusal, the opposite perspective.
 *
 * Reached on the offer flow's fetch (`GET /sync/pairing/offer-response`), whose delivery re-checks
 * F2′. ⚠ Usually the refusal lands on the approver's post instead, and the offerer is never told
 * (`admission-revocation-names-the-capability.md` §7i.5): the copy for this one is on *this*
 * device — choose *Forget this device* here, then pair again.
 *
 * ⚠ A fieldless composite, not a [PairingFailure] member, for [PairingDeviceRemovedError]'s reason:
 * a new constant owes a `SyncService` bump; a fieldless composite is outside the shape baseline.
 */
interface PairingThisDeviceRemovedError : TError {
    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "${SyncService.ID}/error/pairingThisDeviceRemoved"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {},
        )

        fun create(context: TContext): PairingThisDeviceRemovedError = context.create(
            type = ID,
            fields = emptyList(),
        )
    }
}
