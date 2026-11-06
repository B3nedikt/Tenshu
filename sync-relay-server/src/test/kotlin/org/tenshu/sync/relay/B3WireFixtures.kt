package org.tenshu.sync.relay

import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.core.TContext
import org.tenshu.sync.CascadedTombstonePush
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.EdgedPush
import org.tenshu.sync.PushedEdge
import org.tenshu.sync.ReviveEntry
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry

/*
 * CR-2 B3's request bodies for relay tests (`content-plane-cr2-b3-wire-spec.md` §3): `/sync/push`
 * takes an [EdgedPush] and `/sync/push-tombstone` a [CascadedTombstonePush]. A test that only cares
 * about the block wraps it with no edges and no cascade.
 */

internal fun edgedPush(
    ctx: TContext,
    block: SyncBlock,
    edges: List<PushedEdge> = emptyList(),
    relayProtocolVersion: Int = RELAY_PROTOCOL_VERSION,
    revive: List<ReviveEntry> = emptyList(),
): EdgedPush = EdgedPush.create(ctx, relayProtocolVersion, block, edges, revive)

internal fun cascadedTombstone(
    ctx: TContext,
    block: DeleteBlock,
    cascade: List<String> = emptyList(),
    relayProtocolVersion: Int = RELAY_PROTOCOL_VERSION,
): CascadedTombstonePush = CascadedTombstonePush.create(ctx, relayProtocolVersion, block, cascade)

/**
 * Seed one live register directly — what a push of [holderHash]'s row naming [contentId] at
 * [physicalTime] would leave. For tests about the edge set's CONSUMERS (reclaim, inventory), which
 * used the deleted `declare` before B3.
 */
internal fun ContentEdgeRegistry.seedEdge(
    ownerId: String,
    holderHash: String,
    propertyId: String,
    contentId: String,
    physicalTime: Long = 1L,
) {
    writeEdges(
        ownerId,
        holderHash,
        HlcStamp(physicalTime, 0, "seed-device"),
        listOf(ContentEdgeRegistry.EdgeWrite(propertyId, contentId)),
    )
}
