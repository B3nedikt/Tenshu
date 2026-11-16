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
 * ⚠⚠ [revokedMembers] is the T-GR-02 revocation window's fix: the members revoked as of this key, carried in the
 * same row as its generation. An owner device must hold this row to hand the key out (its generation is signed into
 * every re-key), so it holds the revocations with it, whatever order the personal stream's blocks reached the relay
 * in. Never infer "not revoked" from a missing row.
 *
 * `Syncable(Eager)` in the owner's **personal** realm, keyed `"$realmId/$keyId"` (⚠ never `::`, which routes a row
 * into the shared realm), with `keyId = sha256(owner's wrapped Kr)`.
 */
interface RealmKeyGeneration : Composite {

    override val schema: CompositeSchema get() = Schema

    val compositeKey: TString get() = getField(Fields.COMPOSITE_KEY)

    val realmId: TString get() = getField(Fields.REALM_ID)

    val keyId: TString get() = getField(Fields.KEY_ID)

    val generation: UnixInstant get() = getField(Fields.GENERATION)

    /**
     * The member ids (`sha256(memberAuthorityPub)`, hex) revoked as of this key: sorted, comma-separated. Optional only
     * for a row written before the field existed (a four-field row on a dev store); every row written now carries it.
     */
    val revokedMembers: TString? get() = getOptionalField(Fields.REVOKED_MEMBERS)

    /**
     * [revokedMembers] as a set, or **null when unknown** (a row from before the field). ⚠⚠ Never read null as "nobody
     * was revoked": a key whose row cannot say whom it was rotated away from is handed out to nobody.
     */
    fun revokedMemberIds(): Set<String>? = revokedMembers?.get()?.let(::decodeMemberIds)

    companion object {
        const val ID = "tenshu/identity/realmKeyGeneration"

        fun compositeKey(realmId: String, keyId: String): String = "$realmId/$keyId"

        /** Member ids are hex digests, so a comma never occurs inside one. */
        fun encodeMemberIds(memberIds: Set<String>): String = memberIds.sorted().joinToString(",")

        fun decodeMemberIds(encoded: String): Set<String> = encoded.split(',').filter { it.isNotEmpty() }.toSet()

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
                string(Fields.REVOKED_MEMBERS, isOptional = true)
            },
        )

        fun create(
            context: TContext,
            realmId: String,
            keyId: String,
            generation: Long,
            revokedMembers: Set<String>,
        ): RealmKeyGeneration = context.create(
            type = ID,
            fields = listOf(compositeKey(realmId, keyId), realmId, keyId, generation, encodeMemberIds(revokedMembers)),
        )
    }

    object Fields {
        const val COMPOSITE_KEY = "$ID/compositeKey"
        const val REALM_ID = "$ID/realmId"
        const val KEY_ID = "$ID/keyId"
        const val GENERATION = "$ID/generation"
        const val REVOKED_MEMBERS = "$ID/revokedMembers"
    }
}
