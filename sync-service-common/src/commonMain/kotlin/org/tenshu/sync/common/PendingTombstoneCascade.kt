package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * The descendants a recursive delete removed along with one **pending tombstone**, persisted beside
 * it until the drain has handled that tombstone's push (`content-plane-cr2-quorumless.md` §10.7 B3b,
 * B2 audit W5).
 *
 * ## ⚠⚠ Why it is persisted at all
 *
 * The tombstone itself is durable (`SyncService.Collections.PENDING_TOMBSTONES`), but its cascade —
 * the wire names of every descendant the delete took with it — lived only in memory
 * (`TombstoneCascades`). A process death between the delete and the push lost the names, so the
 * descendants' edges were never undeclared: a leak today, and under CR-2 candidate B, where the
 * tombstone push must carry its cascade (§10.2 G1), a tombstone that could not name its subtree.
 *
 * ⚠ Device-local and never synced: no `Syncable` trait, and it is only ever written through the
 * `Database` array API, never `ExecutionContext.saveData`. It holds wire names (HMACs), never paths.
 *
 * ⚠ A **new** composite, so no `SyncService` version moves (HR #9 bumps on a *reshape*;
 * `core-schema-evolution.md` §9.3's `UnsupportedOperationError` precedent). Its fields are positional
 * and persisted, so **any later change to them is a reshape** and owes the bump and a migration.
 */
interface PendingTombstoneCascade : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The pending tombstone's `blockId` — this row's key. */
    val blockId: TString
        get() = getField(Fields.BLOCK_ID)

    /** The cascaded holders' wire names, joined by [SEPARATOR]. Never empty (nothing is stored then). */
    val holderNames: TString
        get() = getField(Fields.HOLDER_NAMES)

    companion object {
        const val ID = "tenshu/sync/pendingTombstoneCascade"

        /**
         * Joins the wire names in [holderNames]. ⚠ Safe because a wire name is an HMAC in hex
         * (`WireEntityId`) and can never contain a newline.
         */
        const val SEPARATOR = "\n"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.BLOCK_ID)
                string(Fields.HOLDER_NAMES)
            }
        )

        fun create(context: TContext, blockId: String, holderNames: List<String>): PendingTombstoneCascade =
            context.create(
                type = ID,
                fields = listOf(
                    blockId.toTString(context),
                    holderNames.joinToString(SEPARATOR).toTString(context),
                ),
            )
    }

    object Fields {
        const val BLOCK_ID = "$ID/blockId"
        const val HOLDER_NAMES = "$ID/holderNames"
    }
}

/** The wire names [PendingTombstoneCascade.holderNames] carries. */
fun PendingTombstoneCascade.holderNameList(): List<String> =
    holderNames.get().split(PendingTombstoneCascade.SEPARATOR).filter { it.isNotEmpty() }
