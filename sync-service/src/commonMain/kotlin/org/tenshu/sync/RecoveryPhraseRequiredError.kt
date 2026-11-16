package org.tenshu.sync

import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * Removing **another** device needs the realm's master key (A1), which this device does not hold —
 * so the user must enter the recovery phrase (`sync-entity-identity.md` §5b, maintainer decision A).
 *
 * ⚠⚠ Refused **before** anything is deleted. Since relay protocol 2 a `Device` tombstone no longer
 * revokes at the relay; only `POST /sync/device-access/revoke` does, and that needs an A1 signature
 * (or the device's own, for itself). Removing the row locally and emitting the tombstone without it
 * would show "removed" here while the device kept its access: a silent partial removal.
 *
 * ⚠ There is deliberately no queued "remove when an owner device comes online": A1 is resident only
 * where the phrase was entered, so a queue would not remove anything sooner (decision A). A device
 * removing **itself** never sees this — it signs with its own key.
 *
 * Fieldless: the remedy is the same whatever the device was.
 */
interface RecoveryPhraseRequiredError : TError {
    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "${SyncService.ID}/error/recoveryPhraseRequired"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {},
        )

        fun create(context: TContext): RecoveryPhraseRequiredError = context.create(
            type = ID,
            fields = emptyList(),
        )
    }
}
