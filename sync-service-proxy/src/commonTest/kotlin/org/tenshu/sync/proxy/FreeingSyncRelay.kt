package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.core.TContext
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.ContentRefusal
import org.tenshu.sync.common.PushOutcome
import org.tenshu.sync.common.RevivedContent
import org.tenshu.sync.common.SyncRelay

/**
 * A relay fake with B4's freed state on `/sync/push` (`content-plane-cr2-b4-spec.md` §1.3), for the
 * client half's tests: a push whose non-regenerable edge names a [freed] id not covered by a revive
 * answers `409 content_freed`; a revive whose refs [store] does not hold answers `409
 * revive_incomplete`; an accepted revive un-frees its id. Everything else is [inner]'s.
 *
 * It also records the order pushes and tombstones arrived in, for the adds-before-removes tests.
 */
class FreeingSyncRelay(
    private val context: TContext,
    val inner: MockSyncRelay = MockSyncRelay(context),
    private val store: InMemoryContentBlobStore? = null,
) : SyncRelay by inner {

    val freed = mutableSetOf<String>()

    /** Every push attempt, accepted or not: (blockId, revive). */
    val attempts = mutableListOf<Pair<String, List<RevivedContent>>>()

    /** Accepted removals and blocks, in arrival order: "block:<id>" / "tombstone:<holder>". */
    val arrivals = mutableListOf<String>()

    /** Accepted revives, by block id. */
    val revivesAccepted = mutableMapOf<String, List<RevivedContent>>()

    /** When non-null, the next N pushes fail as transport failures (a dropped link, or a crash). */
    var failNextPushes = 0

    /** Block ids whose push fails as a transport failure until removed (an add behind a closed gate). */
    val failingBlocks = mutableSetOf<String>()

    /** The next N pushes that carry a revive fail as transport failures (a crash before the retry lands). */
    var failNextRevivePushes = 0

    /** The next N pushes that carry a revive answer `revive_incomplete` whatever the store holds. */
    var forceReviveIncomplete = 0

    override suspend fun pushBlock(block: SyncBlock, edges: List<ContentEdge>): TResult<Data?> =
        when (val outcome = pushEdged(block, edges)) {
            PushOutcome.Accepted -> TResult.Success.create(context, null)
            is PushOutcome.Failed -> TResult.Failure.create(context, outcome.error)
            is PushOutcome.Refused -> TResult.Failure.create(context, GenericError.create(context, "refused"))
        }

    override suspend fun pushEdged(
        block: SyncBlock,
        edges: List<ContentEdge>,
        revive: List<RevivedContent>,
    ): PushOutcome {
        val blockId = block.blockId.get()
        attempts += blockId to revive
        if (failNextPushes > 0 || blockId in failingBlocks) {
            if (blockId !in failingBlocks) failNextPushes--
            return PushOutcome.Failed(GenericError.create(context, "Network error"))
        }
        if (revive.isNotEmpty() && failNextRevivePushes > 0) {
            failNextRevivePushes--
            return PushOutcome.Failed(GenericError.create(context, "Network error"))
        }
        if (revive.isNotEmpty() && forceReviveIncomplete > 0) {
            forceReviveIncomplete--
            return PushOutcome.Refused(ContentRefusal.ReviveIncomplete(revive.map { it.contentId }))
        }
        val named = edges.filter { !it.regenerable }.mapNotNull { it.contentId }.filter { it in freed }
        val uncovered = named.filter { id -> revive.none { it.contentId == id } }.distinct()
        if (uncovered.isNotEmpty()) return PushOutcome.Refused(ContentRefusal.ContentFreed(uncovered))
        val incomplete = revive.filter { entry -> store == null || entry.refs.any { it !in store.chunks } }
        if (incomplete.isNotEmpty()) {
            return PushOutcome.Refused(ContentRefusal.ReviveIncomplete(incomplete.map { it.contentId }))
        }
        revive.forEach { freed -= it.contentId }
        if (revive.isNotEmpty()) revivesAccepted[blockId] = revive
        inner.pushBlock(block, edges)
        arrivals += "block:$blockId"
        return PushOutcome.Accepted
    }

    override suspend fun pushTombstone(block: DeleteBlock, cascade: List<String>): TResult<Data?> {
        arrivals += "tombstone:${block.targetEntityIdHash.get()}"
        return inner.pushTombstone(block, cascade)
    }
}
