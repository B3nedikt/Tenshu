package org.tenshu.identity

import org.tenshu.core.schema.boolean
import org.tenshu.core.toTBoolean

import org.tenshu.core.TBoolean

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A member's sealed acceptance **not yet delivered** to the grantor's mailbox (`shared-realm-read-only-members.md`
 * D7) — a durable outbox, so a failed drop is retried on the next grant poll instead of leaving a joined member
 * who never appears in the owner's member list. Deleted once the relay accepted the drop.
 *
 * `Syncable(Eager)` in the member's **personal** realm, so any of the member's devices can deliver it. Holds
 * only the sealed blob (sealed to the grantor's A13) and its address — nothing a reader could open.
 */
interface OutboundAcceptance : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The shared realm accepted — one outstanding acceptance per realm. */
    val realmId: TString get() = getField(Fields.REALM_ID)

    /** The hex acceptance address (`deriveAcceptanceAddress`). */
    val address: TString get() = getField(Fields.ADDRESS)

    val sealedAcceptance: TByteArray get() = getField(Fields.SEALED_ACCEPTANCE)

    /**
     * The relay refused the last drop for want of an entitlement (`402`, `ENTITLEMENT_ENFORCE`): the member
     * must create an account (the free tier counts) before the owner can learn of the acceptance. ⚠ The row
     * stays queued and is retried on every poll; this flag only lets the app say so instead of leaving the
     * member silently "Invited" (maintainer decision 2026-11-12).
     */
    val needsAccount: TBoolean get() = getField(Fields.NEEDS_ACCOUNT)

    companion object {
        const val ID = "tenshu/identity/outboundAcceptance"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.REALM_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.REALM_ID)
                string(Fields.ADDRESS)
                byteArray(Fields.SEALED_ACCEPTANCE)
                boolean(Fields.NEEDS_ACCOUNT)
            },
        )

        fun create(
            context: TContext,
            realmId: String,
            address: String,
            sealedAcceptance: ByteArray,
            needsAccount: Boolean = false,
        ): OutboundAcceptance =
            context.create(
                type = ID,
                fields = listOf(
                    realmId.toTString(context),
                    address.toTString(context),
                    sealedAcceptance.toTByteArray(context),
                    needsAccount.toTBoolean(context),
                ),
            )
    }

    object Fields {
        const val REALM_ID = "$ID/realmId"
        const val ADDRESS = "$ID/address"
        const val SEALED_ACCEPTANCE = "$ID/sealedAcceptance"
        const val NEEDS_ACCOUNT = "$ID/needsAccount"
    }
}
