package org.tenshu.sync.proxy

import org.tenshu.core.core.TContext
import org.tenshu.identity.Device
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.common.EnvelopeAuth

/**
 * ⭐ The tombstone's `targetEntityType` on the wire is a
 * **discriminator**, not a type — [Device.ID] for a device tombstone, [NON_DEVICE] for everything
 * else (`sync-entity-identity.md` §5b).
 *
 * ## Why the value narrows and the shape does not
 *
 * `delete_block` stores the field in the clear, so a concrete type told the relay *what kind of thing*
 * died on every delete: labels, workouts, places, `FileModification` trims, each with its cadence.
 * Every reader asks one bit — the relay's revocation and realm-epoch count (`== Device.ID`), and the
 * client's only production count (`tombstoneCountForType(Device.ID)`). Receivers never need the type:
 * `SyncProcessor.processDeleteBlock` resolves the target through `targetEntityIdHash` and
 * `TombstoneRegistry.resolveDataId`, and the resolved `DataId` carries it. So the same `TString` in
 * the same position carries two constants: no reshape, no HR 9 bump, no protocol move, and old and
 * new peers read each other's tombstones correctly in both directions.
 *
 * ## ⚠⚠ Mapped at DRAIN time, never at enqueue
 *
 * A `PENDING_TOMBSTONES` queue filled before an upgrade would otherwise leak concrete types after it.
 * The locally recorded copy (`TombstoneRegistry.record`, `PersistedTombstone`) keeps the concrete type
 * on the emitting device: it never leaves the device.
 *
 * ⚠ The guarantee is **forward-only**: tombstones already on a relay before the mapping keep their
 * concrete types.
 */
object TombstoneWireType {

    /**
     * The one non-device value. ⚠⚠ Must equal **no** registered schema id, or a delete of that type
     * would read as something it is not: `TombstoneSentinelCollisionTest` (`:system-test-utils`)
     * asserts it over the full system graph, so a future type cannot take the name.
     */
    const val NON_DEVICE: String = "tenshu/entity"

    /** The wire value for a tombstone whose concrete target type is [entityType]. */
    fun of(entityType: String): String = if (entityType == Device.ID) Device.ID else NON_DEVICE

    /** [block] with its type narrowed: itself when already narrowed, else a copy with the value mapped. */
    fun narrowed(context: TContext, block: DeleteBlock): DeleteBlock {
        val type = block.targetEntityType.get()
        val wire = of(type)
        if (wire == type) return block
        return DeleteBlock.create(
            context = context,
            blockId = block.blockId.get(),
            ownerId = block.ownerId.get(),
            hlc = block.hlc,
            targetEntityIdHash = block.targetEntityIdHash.get(),
            targetEntityType = wire,
            revokedDeviceId = block.revokedDeviceId?.get(),
        )
    }

    /**
     * [block] as it goes on the wire: [narrowed], then authenticated under [identityKey]
     * (`sync-envelope-authentication.md` §3.1). ⚠⚠ The order is the contract — the tag covers the
     * narrowed type, because that is what every receiver sees and recomputes over.
     */
    fun forWire(context: TContext, block: DeleteBlock, identityKey: ByteArray): DeleteBlock =
        EnvelopeAuth.tagTombstone(context, narrowed(context, block), identityKey)
}
