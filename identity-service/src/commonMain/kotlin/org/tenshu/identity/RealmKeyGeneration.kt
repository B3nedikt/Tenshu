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
 * The owner's record of a realm key's **generation** (GR-7, `RealmRekey`): the order members adopt keys in. Written by
 * the owner device that creates the key, strictly after the generation of the key it replaces, and synced to every
 * owner device — so a device still on an older key signs that key's older generation, never a fresher one.
 *
 * ⚠ Explicit, not derived from [RetainedRealmKey.retiredAt]: a recovery rotation re-retires a key keeping its first
 * `retiredAt` (so other stuck devices' 24 h wait does not restart), which would leave the new key with the old key's
 * generation — and members rightly refuse a different key of a generation they hold.
 * `Syncable(Eager)` in the owner's **personal** realm, keyed `"$realmId/$keyId"` (⚠ never `::`, which routes a row
 * into the shared realm), with `keyId = sha256(owner's wrapped Kr)`.
 */
interface RealmKeyGeneration : Composite {

    override val schema: CompositeSchema get() = Schema

    val compositeKey: TString get() = getField(Fields.COMPOSITE_KEY)

    val realmId: TString get() = getField(Fields.REALM_ID)

    val keyId: TString get() = getField(Fields.KEY_ID)

    val generation: UnixInstant get() = getField(Fields.GENERATION)

    companion object {
        const val ID = "tenshu/identity/realmKeyGeneration"

        fun compositeKey(realmId: String, keyId: String): String = "$realmId/$keyId"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.COMPOSITE_KEY,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.COMPOSITE_KEY)
                string(Fields.REALM_ID)
                string(Fields.KEY_ID)
                timestamp(Fields.GENERATION)
            },
        )

        fun create(context: TContext, realmId: String, keyId: String, generation: Long): RealmKeyGeneration =
            context.create(type = ID, fields = listOf(compositeKey(realmId, keyId), realmId, keyId, generation))
    }

    object Fields {
        const val COMPOSITE_KEY = "$ID/compositeKey"
        const val REALM_ID = "$ID/realmId"
        const val KEY_ID = "$ID/keyId"
        const val GENERATION = "$ID/generation"
    }
}
