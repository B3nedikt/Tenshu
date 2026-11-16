package org.tenshu.identity

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A member's note of **which contact granted it a realm** (GR-7): the contact whose handshake context the realm's
 * re-key address derives from (`deriveRekeyAddress`), so each poll checks one address per realm rather than one per
 * contact per realm. Written at accept, from the contact the grant was addressed to.
 *
 * `Syncable(Eager)` in the member's **personal** realm, so every device of theirs polls the same address.
 * ⚠ Routing only, never authority: what makes a re-key genuine is the realm master's signature on it (`RealmRekey`).
 */
interface RealmGrantor : Composite {

    override val schema: CompositeSchema get() = Schema

    val realmId: TString get() = getField(Fields.REALM_ID)

    /** The granting contact's A13 fingerprint. */
    val grantorFingerprint: TString get() = getField(Fields.GRANTOR_FINGERPRINT)

    /**
     * The signed key generation (`RealmRekey`) of the newest key this member adopted for the realm. ⚠⚠ An older
     * generation is refused, and the same generation under a different key too: a member can skip a key (two
     * rotations before it polled), and the relay could re-serve that skipped, genuinely signed re-key — or an owner
     * device still behind could answer with the old key — rolling the member onto a key a removed member holds. Its
     * own retired keyring cannot catch either; this does.
     */
    val lastKeyGeneration: UnixInstant get() = getField(Fields.LAST_KEY_GENERATION)

    companion object {
        const val ID = "tenshu/identity/realmGrantor"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.REALM_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.REALM_ID)
                string(Fields.GRANTOR_FINGERPRINT)
                timestamp(Fields.LAST_KEY_GENERATION)
            },
        )

        fun create(context: TContext, realmId: String, grantorFingerprint: String, lastKeyGeneration: Long): RealmGrantor =
            context.create(type = ID, fields = listOf(realmId, grantorFingerprint, lastKeyGeneration))
    }

    object Fields {
        const val REALM_ID = "$ID/realmId"
        const val GRANTOR_FINGERPRINT = "$ID/grantorFingerprint"
        const val LAST_KEY_GENERATION = "$ID/lastKeyGeneration"
    }
}
