package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.list

/**
 * Response shape for `POST /sync/changes` after the HLC-piggyback design landed
 * (`docs/proposals/sync-backend-relay-plan.md` §5.1). Replaces the legacy bare
 * `TList<SyncBlock>` body with a wrapper that also carries [peerTips] — the
 * relay's view of every other realm device's signed HLC tip, used by the receiver
 * to detect divergence (forked view, peer regression) per `threat-model.md` T-T-04.
 *
 * [nextCursor] is the relay's arrival-time cursor (`memory/project_cursor_advancement_bug`,
 * "general fix"): the receiver advances `lastSyncTimestamp` from this value, NOT from
 * `block.hlc.physicalTime`. The relay assigns a strictly-monotonic per-mailbox arrival
 * sequence on every `pushBlock` and filters subsequent pulls by `arrivalSeq > since`;
 * returning the cursor in-band lets the receiver close the same-millisecond HLC race
 * that previously stranded late-pushed blocks (e.g. the byte block in `UploadFile` when
 * its tick collided with the entity tick in one wall-clock ms).
 */
interface ChangesResponse : Composite {

    override val schema: CompositeSchema get() = Schema

    val blocks: TList<SyncBlock> get() = getField(Fields.BLOCKS)
    val peerTips: TList<SignedHlcTip> get() = getField(Fields.PEER_TIPS)
    val nextCursor: TInteger get() = getField(Fields.NEXT_CURSOR)

    companion object {
        const val ID = "tenshu/sync/changesResponse"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                list(Fields.BLOCKS, of = SyncBlock.Schema)
                list(Fields.PEER_TIPS, of = SignedHlcTip.Schema)
                int(Fields.NEXT_CURSOR)
            }
        )

        fun create(
            context: TContext,
            blocks: List<SyncBlock>,
            peerTips: List<SignedHlcTip>,
            nextCursor: Long
        ): ChangesResponse = context.create(
            type = ID,
            fields = listOf(
                SimpleTList(blocks),
                SimpleTList(peerTips),
                nextCursor.toTInteger(context)
            )
        )
    }

    object Fields {
        const val BLOCKS = "$ID/blocks"
        const val PEER_TIPS = "$ID/peerTips"
        const val NEXT_CURSOR = "$ID/nextCursor"
    }
}
