package org.tenshu.identity

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
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A Publisher-signed grant of a paid tier to an `ownerId`
 * (`docs/proposals/tenshu-publisher.md` §10.2 + `storage-entitlement-sync.md`).
 *
 * **Synced, owner-scoped.** Unlike `StorageStatus` (a device-local *provision*
 * derivation), the entitlement is the authoritative *entitlement* fact and is a
 * [Syncable] record: the Publisher writes it into the owner's realm and it
 * propagates to every paired device via the normal sync mechanism. This is what
 * lets a 2nd device learn of a subscription the user bought on the first — the
 * cross-device gap [storage-entitlement-sync.md] resolves. The companion
 * [StorageProvider][org.tenshu.storage.proxy.StorageProvider] swap (slice 3)
 * derives `hasStorage` / the quota limit from this record rather than the v1
 * device-local mock.
 *
 * **Keyed by [ownerId]** — one entitlement per owner in v1 (a stacked-tier list is
 * a deliberate non-goal until tiers compose; see the proposal §6). The
 * [publisherSignature] is verified against the known publisher-key registry on
 * merge — that verification entry is slice 4's concern (it needs a real signer);
 * this slice lands the wire-registered, syncable schema only.
 *
 * The two validity instants are wall-clock [UnixInstant]s (ms since epoch): a
 * record whose [validUntil] is in the past is expired and confers no storage.
 */
interface Entitlement : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The owner this entitlement is granted to — the master-key-derived `ownerId`. */
    val ownerId: TString
        get() = getField(Fields.OWNER_ID)

    /** Signing-key id of the Publisher that minted this record (dev key id in v1). */
    val publisherId: TString
        get() = getField(Fields.PUBLISHER_ID)

    /** Product / tier identifier, e.g. `"tenshu.drive.basic"`. */
    val productId: TString
        get() = getField(Fields.PRODUCT_ID)

    val validFrom: UnixInstant
        get() = getField(Fields.VALID_FROM)

    val validUntil: UnixInstant
        get() = getField(Fields.VALID_UNTIL)

    /** Publisher signature over the record; verified against the publisher-key registry on merge. */
    val publisherSignature: TByteArray
        get() = getField(Fields.PUBLISHER_SIGNATURE)

    companion object {
        const val ID = "tenshu/identity/entitlement"

        /**
         * Keys the entity by [ownerId] — one entitlement per owner (v1). Lets
         * `executionContext.saveData(entitlement)` compute the right `StringDataId`
         * without manual construction, and the proxy upsert dedupe to one row.
         */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.OWNER_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.OWNER_ID)
                string(Fields.PUBLISHER_ID)
                string(Fields.PRODUCT_ID)
                timestamp(Fields.VALID_FROM)
                timestamp(Fields.VALID_UNTIL)
                byteArray(Fields.PUBLISHER_SIGNATURE)
            },
        )

        fun create(
            context: TContext,
            ownerId: String,
            publisherId: String,
            productId: String,
            validFrom: Long,
            validUntil: Long,
            publisherSignature: ByteArray,
        ): Entitlement = context.create(
            type = ID,
            fields = listOf(
                ownerId,
                publisherId,
                productId,
                validFrom,
                validUntil,
                publisherSignature,
            ),
        )
    }

    object Fields {
        const val OWNER_ID = "$ID/ownerId"
        const val PUBLISHER_ID = "$ID/publisherId"
        const val PRODUCT_ID = "$ID/productId"
        const val VALID_FROM = "$ID/validFrom"
        const val VALID_UNTIL = "$ID/validUntil"
        const val PUBLISHER_SIGNATURE = "$ID/publisherSignature"
    }
}
