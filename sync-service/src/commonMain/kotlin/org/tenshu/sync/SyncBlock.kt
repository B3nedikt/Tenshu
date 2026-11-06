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
 * Represents an encrypted block of data to be synchronized.
 */
interface SyncBlock : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /**
     * Unique identifier for this sync block.
     */
    val blockId: TString
        get() = getField(Fields.BLOCK_ID)

    /**
     * Identifier of the user who owns this data.
     */
    val ownerId: TString
        get() = getField(Fields.OWNER_ID)

    /**
     * HLC timestamp when this block was created.
     */
    val hlc: HybridLogicalClock
        get() = getField(Fields.HLC)

    /**
     * The encrypted payload (metadata or binary blob).
     *
     * The plaintext inside is a [org.tenshu.sync.common.BlockPayloadEnvelope]:
     * the producing `TENSHU_STORAGE_FORMAT_VERSION` as a varint, then the
     * serialized payload composite (schema-evolution E0). The stamp rides
     * inside the ciphertext — the relay never sees it (House Rule #1); the
     * block itself still carries no plaintext version field.
     */
    val encryptedPayload: TByteArray
        get() = getField(Fields.ENCRYPTED_PAYLOAD)

    /**
     * The hash of the entity this block belongs to. Scopes relay-side
     * fetches without revealing the entity's contents — the relay
     * indexes by this opaque hash. Optional only for legacy blocks;
     * new blocks always set it via `SyncObserver.generateSyncBlock`.
     *
     * The property identity for property-block updates lives *inside*
     * [encryptedPayload] (as [org.tenshu.sync.DataBlockPayload.propertyId]),
     * not on the wire — see `docs/sync/threat-model.md`
     * §"Property-ID exposure to relay".
     */
    val targetEntityIdHash: TString?
        get() = getOptionalField(Fields.TARGET_ENTITY_ID_HASH)

    companion object {
        const val ID = "tenshu/sync/block"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.BLOCK_ID)
                string(Fields.OWNER_ID)
                composite(Fields.HLC, type = HybridLogicalClock.Schema)
                byteArray(Fields.ENCRYPTED_PAYLOAD)
                string(Fields.TARGET_ENTITY_ID_HASH, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            blockId: String,
            ownerId: String,
            hlc: HybridLogicalClock,
            encryptedPayload: ByteArray,
            targetEntityIdHash: String? = null
        ): SyncBlock = context.create(
            type = ID,
            fields = listOf(
                blockId.toTString(context),
                ownerId.toTString(context),
                hlc,
                encryptedPayload.toTByteArray(context),
                targetEntityIdHash?.toTString(context)
            )
        )
    }

    object Fields {
        const val BLOCK_ID = "$ID/blockId"
        const val OWNER_ID = "$ID/ownerId"
        const val HLC = "$ID/hlc"
        const val ENCRYPTED_PAYLOAD = "$ID/encryptedPayload"
        const val TARGET_ENTITY_ID_HASH = "$ID/targetEntityIdHash"
    }
}
