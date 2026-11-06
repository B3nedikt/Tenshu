package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString

/**
 * A delete tombstone for a Syncable entity. Pushed via the same transport as
 * [SyncBlock]; on the receiver side, [org.tenshu.sync.proxy.SyncProcessor] short-circuits
 * any merge for the target entity at ingest time. The relay treats it as an indexed
 * delete marker for GC purposes (see `docs/adr/0006-sync-tombstones.md`).
 *
 * ⭐ **Authenticated by [authTag]** (`sync-envelope-authentication.md` §3.1): an HMAC under a child
 * key of the realm's identity key, over every envelope field in its **wire** form. Every field stays
 * clear — the relay still reads the Device bit, `revokedDeviceId` and `hlc` — but it can no longer
 * mint or alter a tombstone (threat model T-T-02 / T-K-05). ⚠ It proves *realm-key holder*, not
 * *which device*: an admitted device can still author one (T-K-01).
 */
interface DeleteBlock : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Stable id for this block; same shape as [SyncBlock.blockId]. */
    val blockId: TString
        get() = getField(Fields.BLOCK_ID)

    /** Realm owner id; same as [SyncBlock.ownerId]. */
    val ownerId: TString
        get() = getField(Fields.OWNER_ID)

    /** HLC at which this delete was issued. */
    val hlc: HybridLogicalClock
        get() = getField(Fields.HLC)

    /** The id-hash of the entity being deleted. Indexed by the relay for GC. */
    val targetEntityIdHash: TString
        get() = getField(Fields.TARGET_ENTITY_ID_HASH)

    /**
     * ⭐ EI-4 (`sync-entity-identity.md` §5b): **on the wire, a discriminator** — `Device.ID` for a
     * device tombstone, the `TombstoneWireType.NON_DEVICE` sentinel for every other one, mapped at
     * drain time. Only the emitting device's local copy holds the concrete schema id.
     *
     * ⚠ Never resolve a tombstone's target by this value: receivers resolve it through
     * [targetEntityIdHash] (`TombstoneRegistry.resolveDataId`), whose `DataId` carries the type.
     */
    val targetEntityType: TString
        get() = getField(Fields.TARGET_ENTITY_TYPE)

    /**
     * For Device-entity tombstones (where [targetEntityType] == `Device.ID`), the
     * deviceId being revoked. Populated only on revocation tombstones; absent on every
     * other entity-type tombstone.
     *
     * The relay extracts this on `POST /sync/push-tombstone` and marks the device as
     * revoked in `KnownDevicesStore` so subsequent `/sync/push` from that device's JWT
     * is rejected with 403 (T-K-02 enforcement per `docs/sync/threat-model.md` §8.5).
     * Without this field, the relay would only see `targetEntityIdHash` (a one-way hash)
     * and couldn't reverse it to a deviceId — so push-rejection wouldn't be possible.
     *
     * Threat-model trade-off: this exposes the deviceId of the revoked device to the
     * relay. Acceptable per A6 — the relay already knows every device's `(ownerId,
     * deviceId)` via `KnownDevicesStore` registration during pairing/recovery. Telling
     * it which to remove doesn't widen the metadata surface.
     */
    val revokedDeviceId: TString?
        get() = getOptionalField(Fields.REVOKED_DEVICE_ID)

    /**
     * ⭐ `HMAC-SHA256(K_tomb, tombstoneCanonical(this))` — `org.tenshu.sync.common.EnvelopeAuth`.
     *
     * ⚠⚠ **Absent on the local and queued copies, present on every wire copy.** The tag covers the
     * wire form of [targetEntityType] (EI-4's discriminator), which exists only at drain, so
     * `PushTombstone` computes it there. Ingest treats an absent or empty tag as a forgery.
     *
     * ⚠ Like every optional field here, an absent value may round-trip as an empty array.
     */
    val authTag: TByteArray?
        get() = getOptionalField(Fields.AUTH_TAG)

    companion object {
        const val ID = "tenshu/sync/block/delete"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.BLOCK_ID)
                string(Fields.OWNER_ID)
                composite(Fields.HLC, type = HybridLogicalClock.Schema)
                string(Fields.TARGET_ENTITY_ID_HASH)
                string(Fields.TARGET_ENTITY_TYPE)
                string(Fields.REVOKED_DEVICE_ID, isOptional = true)
                byteArray(Fields.AUTH_TAG, isOptional = true)
            }
        )


        fun create(
            context: TContext,
            blockId: String,
            ownerId: String,
            hlc: HybridLogicalClock,
            targetEntityIdHash: String,
            targetEntityType: String,
            revokedDeviceId: String? = null,
            authTag: ByteArray? = null,
        ): DeleteBlock = context.create(
            type = ID,
            fields = listOf(
                blockId.toTString(context),
                ownerId.toTString(context),
                hlc,
                targetEntityIdHash.toTString(context),
                targetEntityType.toTString(context),
                revokedDeviceId?.toTString(context),
                authTag?.toTByteArray(context),
            )
        )
    }

    object Fields {
        const val BLOCK_ID = "$ID/blockId"
        const val OWNER_ID = "$ID/ownerId"
        const val HLC = "$ID/hlc"
        const val TARGET_ENTITY_ID_HASH = "$ID/targetEntityIdHash"
        const val TARGET_ENTITY_TYPE = "$ID/targetEntityType"
        const val REVOKED_DEVICE_ID = "$ID/revokedDeviceId"
        const val AUTH_TAG = "$ID/authTag"
    }
}
