package org.tenshu.sync

import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * The relay refused to admit the device being added because it was removed from this realm
 * before and is trying to come back under the same identity (F2′, `403 device-removed`).
 *
 * ⭐ This is the approver's half of `admission-revocation-names-the-capability.md` §7h (F6-2): the
 * refusal reaches the device doing the adding, so it is the one moment the system can tell the
 * person deciding *"this is a device you removed"*. Rendered as the generic error it said nothing,
 * and every retry was refused identically.
 *
 * ⚠ The device at fault is the **other** one, never this one — unlike
 * [PairingFailure.AdmitterUnrecognised]. The remedy is on that device: forget it, then pair again
 * under a new identity. §7h.2 is why the approver is told before that remedy is offered.
 *
 * ⚠ A separate error type rather than a [PairingFailure] member on purpose. A new constant is a
 * wire-vocabulary change that owes a `SyncService` version bump, which would fence every
 * not-yet-updated peer's sync over an error that never enters a block (`core-schema-evolution.md`
 * E2j-1b). A new composite does not. It is also where §7h.5's F6-1 argument (the removal date)
 * belongs once the relay returns it — and adding that field then is a reshape that owes the bump.
 */
interface PairingDeviceRemovedError : TError {
    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "${SyncService.ID}/error/pairingDeviceRemoved"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {},
        )

        fun create(context: TContext): PairingDeviceRemovedError = context.create(
            type = ID,
            fields = emptyList(),
        )
    }
}
