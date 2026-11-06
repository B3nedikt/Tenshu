package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list

/**
 * The decrypted payload of a **delta-batch** [SyncBlock] — the wire
 * primitive of the task-scoped sync lane
 * (`core-background-processing.md` §10.2, phase TS-1).
 *
 * A container of per-entity [SyncDelta]s, each carrying its **own
 * HLC**, so one bulk-task checkpoint rides the mailbox as ONE block while
 * merge stays entity-granular on every receiver (batching, not semantic
 * fusion). The third payload variant, a sibling of [DataBlockPayload] and
 * [FileContentManifestPayload] — the manifest is the precedent for "one
 * block referencing N things"; this container generalises it to N
 * metadata deltas. Bulk *bytes* never ride here: the lane is
 * content-plane-first (ADR 0017), the container carries small rows and
 * references only.
 *
 * Naming (maintainer decision, 2026-07-24): the wire type names **what it
 * carries** (a batch of deltas), like its siblings — not why it was
 * emitted. "Checkpoint" is the *lane/task event* that seals a batch
 * (TS-2's `SyncBlockEmitter.checkpointSyncLane`); a future non-task batcher may reuse
 * this container without checkpoint semantics.
 *
 * Wire posture:
 * - The wrapping [SyncBlock]'s outer `hlc` is the checkpoint marker; the
 *   receiver additionally advances its clock through each delta's inner
 *   HLC (`SyncProcessor.applyDeltaBatch`).
 * - The wrapping block's `targetEntityIdHash` is **null** (§10.2 D2): a
 *   container has N targets, and per-delta hashes live inside the
 *   ciphertext ([SyncDelta.targetEntityIdHash]). Lane-carried
 *   entities are therefore not addressable via `/sync/fetch-entity-blocks`
 *   — receivers persist rows on ingest, and the on-demand path serves
 *   *content*, which stays manifest-addressed.
 * - Receiver safety mirrors the manifest's: a pre-TS-1 peer parses the
 *   unknown type to `null` in `decryptAndParse` and **drops** the block —
 *   no crash, but dropped rows are silent data loss on that peer, which
 *   is why shipping a producer owes the `TENSHU_STORAGE_FORMAT_VERSION`
 *   lockstep bump + relay 426 gate (House Rule #9), unlike the
 *   manifest's tolerated no-op window.
 * - E0 (schema-evolution §5): the encrypted plaintext is stamped with the
 *   producing format version (`BlockPayloadEnvelope`) *before* these
 *   composite bytes — one stamp per block, not per delta (one producer,
 *   one version), which also makes the future E2 upcaster run per-block,
 *   the natural unit. So a block's bytes now self-describe their schema;
 *   pre-E0 nothing could tell which version produced them.
 *
 * Size is bounded by the sender-side checkpoint caps
 * (`core-background-processing.md` §10.3: 500 deltas / 256 KiB encoded),
 * keeping the §4.4 block-size budget honest.
 */
interface DeltaBatchPayload : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Ordered per-entity deltas; applied in list order. */
    val deltas: TList<SyncDelta>
        get() = getField(Fields.DELTAS)

    companion object {
        const val ID = "tenshu/sync/deltaBatchPayload"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                list(Fields.DELTAS, of = SyncDelta.Schema)
            }
        )

        fun create(
            context: TContext,
            deltas: List<SyncDelta>,
        ): DeltaBatchPayload = context.create(
            type = ID,
            fields = listOf(
                SimpleTList(deltas)
            )
        )
    }

    object Fields {
        const val DELTAS = "$ID/deltas"
    }
}
