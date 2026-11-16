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
 * The owner's note of **which realm key a member was last sent** (GR-7 catch-up): one row per (realm, member),
 * overwritten on every delivery. Any owner device that finds an accepted, unrevoked member without a row for the
 * realm's *current* key sends it — so a member is never stranded on an old key by a rotation that ran on a device
 * which did not yet know them, nor by a drop that failed.
 *
 * `Syncable(Eager)` in the owner's **personal** realm, like [AwaitedAcceptance], so every owner device shares one
 * view and two of them do not keep re-sending. [keyId] is `sha256` of the owner's own wrapped `Kr` — identical on
 * every owner device, since they share one [SharedRealmMembership] row. Holds two ids and a hash — nothing secret.
 * ⚠ A separate type, not a field on [GrantedMembership]: that composite has shipped (HR 9).
 */
interface RealmKeyDelivery : Composite {

    override val schema: CompositeSchema get() = Schema

    /** `"$realmId/$memberId"` — the row's key. */
    val compositeKey: TString get() = getField(Fields.COMPOSITE_KEY)

    val realmId: TString get() = getField(Fields.REALM_ID)

    val memberId: TString get() = getField(Fields.MEMBER_ID)

    /** The key delivered, as `sha256(owner's wrapped Kr)` hex. */
    val keyId: TString get() = getField(Fields.KEY_ID)

    /**
     * The signed `issuedAt` of that delivery. ⚠ The next re-key to this member is issued strictly after it, whichever
     * owner device sends it and whatever its clock says — the member refuses one that is not newer than the last it
     * adopted, which is what stops a replayed, skipped re-key from rolling it back.
     */
    val issuedAt: UnixInstant get() = getField(Fields.ISSUED_AT)

    companion object {
        const val ID = "tenshu/identity/realmKeyDelivery"

        /** ⚠ `/`, never `::`: a `"$realmId::…"` key routes the row INTO the shared realm; this one is the owner's own. */
        fun compositeKey(realmId: String, memberId: String): String = "$realmId/$memberId"

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
                string(Fields.MEMBER_ID)
                string(Fields.KEY_ID)
                timestamp(Fields.ISSUED_AT)
            },
        )

        fun create(context: TContext, realmId: String, memberId: String, keyId: String, issuedAt: Long): RealmKeyDelivery =
            context.create(type = ID, fields = listOf(compositeKey(realmId, memberId), realmId, memberId, keyId, issuedAt))
    }

    object Fields {
        const val COMPOSITE_KEY = "$ID/compositeKey"
        const val REALM_ID = "$ID/realmId"
        const val MEMBER_ID = "$ID/memberId"
        const val KEY_ID = "$ID/keyId"
        const val ISSUED_AT = "$ID/issuedAt"
    }
}
