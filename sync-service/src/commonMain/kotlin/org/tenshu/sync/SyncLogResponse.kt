package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.list
import org.tenshu.core.toTBoolean

/**
 * One page of the **merged sync log** — the union of the block and tombstone logs, served
 * from a single ordered query under a single cursor (`sync-lane-cursor-split.md` Option C
 * / L3).
 *
 * ## Why this type exists rather than a wider [ChangesResponse]
 *
 * The two lanes were never two logs. The relay assigns every push — block *and* tombstone —
 * a seq from **one** `arrivalCounter`, so a single total order over both already exists
 * server-side; `/sync/changes` and `/sync/tombstones` were two *views* of it, each with its
 * own cursor. That split is what let a dense lane throttle a sparse one (§2), and what L1
 * had to paper over with per-lane cursors. Serving the union restores the thing the relay
 * always had.
 *
 * A new type rather than fields on [ChangesResponse] because composites serialise
 * **positionally**: appending a field garbles every peer built against the old shape rather
 * than erroring (schema-evolution §2). A new id is additive — old peers never ask for it —
 * which is also why this slice owes no `TENSHU_STORAGE_FORMAT_VERSION` bump and no
 * `RELAY_PROTOCOL_VERSION` bump (`RelayProtocol.kt`: purely additive routes do not bump).
 *
 * ## ⚠ The page is a PREFIX of the merged log — that is the whole correctness property
 *
 * [nextCursor] may be advanced past **both** kinds only because the relay pages the *union*:
 * it takes the first `limit` entries above the cursor in seq order, whatever mix of blocks
 * and tombstones that happens to be, and reports the max seq of exactly that prefix. Nothing
 * with a lower seq is left unsent in either kind, so one cursor is sound.
 *
 * That is precisely what two independent `take(limit)` queries could NOT promise: each
 * returned its own first-500, whose max seqs differ by tens of thousands, and no single
 * number covered both.
 *
 * ## ⚠ [blocks] and [tombstones] are two lists, and that is deliberate
 *
 * The page is a prefix of one ordered log, but it is *delivered* kind-separated rather than
 * interleaved. Nothing consumes the interleaving:
 *
 * - application is **HLC-guarded in both directions** — `resolveConflictAndSave` drops an
 *   update whose HLC is at or below a recorded tombstone, and `processDeleteBlock` drops a
 *   tombstone older than the row it targets — so the outcome of applying a page does not
 *   depend on the order within it;
 * - the client already applies a whole page of blocks then a whole page of tombstones, so
 *   preserving that is *not a new risk*; it is the existing behaviour, unchanged.
 *
 * The alternative — a heterogeneous `TList` of a block/tombstone union — would buy an
 * ordering guarantee with no consumer, at the cost of a new union type on the wire. The
 * cursor is what needed fixing, not the interleaving.
 *
 * ## [hasMore]
 *
 * Explicit, rather than inferred from `rows.size >= SYNC_CHANGES_PAGE_LIMIT`. That inference
 * (proposal L2) is sound — both sides compile against the same constant — but reports an
 * exactly-full final page as truncated, costing one wasted tick. It was deliberately not
 * worth its own protocol bump; this slice reshapes the response anyway, so it rides along
 * for free, which is exactly the carrier L2 was deferred to wait for.
 */
interface SyncLogResponse : Composite {

    override val schema: CompositeSchema get() = Schema

    val blocks: TList<SyncBlock> get() = getField(Fields.BLOCKS)
    val tombstones: TList<DeleteBlock> get() = getField(Fields.TOMBSTONES)
    val peerTips: TList<SignedHlcTip> get() = getField(Fields.PEER_TIPS)

    /**
     * Max seq of this page — i.e. of the merged prefix actually returned, never the log's
     * true tip. Stays at the request's `since` when the page is empty, so a caller that
     * assigns it unconditionally never regresses and never skips.
     */
    val nextCursor: TInteger get() = getField(Fields.NEXT_CURSOR)

    /**
     * Whether the relay holds anything above [nextCursor] for this owner. Authoritative —
     * the relay knows, where the client could only guess from the row count.
     */
    val hasMore: TBoolean get() = getField(Fields.HAS_MORE)

    companion object {
        const val ID = "tenshu/sync/syncLogResponse"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                list(Fields.BLOCKS, of = SyncBlock.Schema)
                list(Fields.TOMBSTONES, of = DeleteBlock.Schema)
                list(Fields.PEER_TIPS, of = SignedHlcTip.Schema)
                int(Fields.NEXT_CURSOR)
                boolean(Fields.HAS_MORE)
            }
        )

        fun create(
            context: TContext,
            blocks: List<SyncBlock>,
            tombstones: List<DeleteBlock>,
            peerTips: List<SignedHlcTip>,
            nextCursor: Long,
            hasMore: Boolean
        ): SyncLogResponse = context.create(
            type = ID,
            fields = listOf(
                SimpleTList(blocks),
                SimpleTList(tombstones),
                SimpleTList(peerTips),
                nextCursor.toTInteger(context),
                hasMore.toTBoolean(context)
            )
        )
    }

    object Fields {
        const val BLOCKS = "$ID/blocks"
        const val TOMBSTONES = "$ID/tombstones"
        const val PEER_TIPS = "$ID/peerTips"
        const val NEXT_CURSOR = "$ID/nextCursor"
        const val HAS_MORE = "$ID/hasMore"
    }
}
