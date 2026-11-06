package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * The edges a queued block owes its push (CR-2 B3d, `content-plane-cr2-b3-wire-spec.md` §5): the
 * complete `contentEdgesOf` set of the row the block carries, computed **from the payload at emit
 * time** and kept beside the block, keyed by its `blockId`, until the drain pushes both as one
 * `EdgedPush`.
 *
 * ## ⚠⚠ Why a sidecar, and why computed at emit time
 *
 * The drain sees only the sealed block, and re-reading the row there would read the STORE, which may
 * hold a later save or no row at all after a move (§10.11's as-built warning, audit §2.1). So the set
 * is fixed when the block is built and persisted **before** the block becomes pushable, exactly like
 * [PendingTombstoneCascade]. The pending queue's element type stays `SyncBlock`, so nothing that reads
 * the queue changes.
 *
 * ⚠ One string, like [PendingTombstoneCascade.holderNames]: one line per edge,
 * `propertyId \t contentId \t regenerable`, an empty content id for **none**. Safe because property
 * ids are schema ids and content ids are hex or generated ids, neither of which holds a tab or a
 * newline.
 *
 * ⭐ CR-2 B4 (§2.3): the same string also carries the content ids the save **cleared** (the replaced
 * row named them, the new edges do not), one line each as `!cleared \t contentId \t false`. The
 * three-column shape is kept on purpose, and `!` begins no schema id, so [decode] tells them apart and
 * a row written before B4 decodes exactly as it did. No field moved, so this is no reshape.
 *
 * ⚠ Device-local, never synced. A **new** composite, so no `SyncService` version moves (HR #9 bumps
 * on a reshape); its fields are positional and persisted, so any later change owes the bump.
 */
interface PendingBlockEdges : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The queued block's `blockId` — this row's key. */
    val blockId: TString
        get() = getField(Fields.BLOCK_ID)

    /** The encoded edges; see the class KDoc. Never empty (nothing is stored then). */
    val edges: TString
        get() = getField(Fields.EDGES)

    companion object {
        const val ID = "tenshu/sync/pendingBlockEdges"

        private const val LINE = "\n"
        private const val COLUMN = "\t"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.BLOCK_ID)
                string(Fields.EDGES)
            }
        )

        /** The marker column of a cleared-content line (B4 §2.3); never a schema id. */
        const val CLEARED = "!cleared"

        fun create(
            context: TContext,
            blockId: String,
            edges: List<ContentEdge>,
            cleared: Set<String> = emptySet(),
        ): PendingBlockEdges =
            context.create(
                type = ID,
                fields = listOf(blockId.toTString(context), encode(edges, cleared).toTString(context)),
            )

        fun encode(edges: List<ContentEdge>, cleared: Set<String> = emptySet()): String =
            (edges.map { listOf(it.propertyId, it.contentId.orEmpty(), it.regenerable.toString()) } +
                cleared.map { listOf(CLEARED, it, false.toString()) })
                .joinToString(LINE) { it.joinToString(COLUMN) }

        fun decode(encoded: String): List<ContentEdge> = lines(encoded).filter { it[0] != CLEARED }.map { (propertyId, contentId, regenerable) ->
            ContentEdge(
                propertyId = propertyId,
                contentId = contentId.ifEmpty { null },
                regenerable = regenerable.toBoolean(),
            )
        }

        /** The content ids the save cleared (B4 §2.3); empty for a row written before B4. */
        fun decodeCleared(encoded: String): Set<String> =
            lines(encoded).filter { it[0] == CLEARED }.map { it[1] }.toSet()

        private fun lines(encoded: String): List<List<String>> =
            encoded.split(LINE).filter { it.isNotEmpty() }.map { it.split(COLUMN, limit = 3) }
    }

    object Fields {
        const val BLOCK_ID = "$ID/blockId"
        const val EDGES = "$ID/edges"
    }
}

fun PendingBlockEdges.edgeList(): List<ContentEdge> = PendingBlockEdges.decode(edges.get())

fun PendingBlockEdges.clearedContentIds(): Set<String> = PendingBlockEdges.decodeCleared(edges.get())
