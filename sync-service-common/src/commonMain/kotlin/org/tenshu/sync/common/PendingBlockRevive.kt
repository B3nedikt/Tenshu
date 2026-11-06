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
 * The revive a refused block owes its retry (CR-2 B4, `content-plane-cr2-b4-spec.md` §2.2): for each
 * freed content its row names, the refs a fresh re-upload produced (chunks + manifest), keyed by the
 * block's `blockId`.
 *
 * ## ⚠⚠ Why it is persisted before the retry
 *
 * Each re-upload mints a fresh CEK, so its chunks are new blobs. A crash between the re-upload and the
 * push that carries the revive would, without this row, re-upload again on the next tick and orphan the
 * first set (a leak, by G4). With it, the retry sends the refs already on the relay. Dropped with the
 * block (`deleteRetiredBlock`), and on a `revive_incomplete` (the next tick re-uploads).
 *
 * ⚠ One string, like [PendingBlockEdges]: one line per content, `contentId \t ref,ref,…`. Content ids
 * and `blob-<hex>` refs hold no tab, comma or newline.
 *
 * ⚠ Device-local, never synced. A **new** composite, so no `SyncService` version moves (HR #9 bumps on
 * a reshape); its fields are positional and persisted, so any later change owes the bump.
 */
interface PendingBlockRevive : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The refused block's `blockId` — this row's key. */
    val blockId: TString
        get() = getField(Fields.BLOCK_ID)

    /** The encoded revive entries; see the class KDoc. Never empty (nothing is stored then). */
    val revive: TString
        get() = getField(Fields.REVIVE)

    companion object {
        const val ID = "tenshu/sync/pendingBlockRevive"

        private const val LINE = "\n"
        private const val COLUMN = "\t"
        private const val REF = ","

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.BLOCK_ID)
                string(Fields.REVIVE)
            }
        )

        fun create(context: TContext, blockId: String, revive: List<RevivedContent>): PendingBlockRevive =
            context.create(
                type = ID,
                fields = listOf(blockId.toTString(context), encode(revive).toTString(context)),
            )

        fun encode(revive: List<RevivedContent>): String =
            revive.joinToString(LINE) { it.contentId + COLUMN + it.refs.joinToString(REF) }

        fun decode(encoded: String): List<RevivedContent> = encoded.split(LINE).filter { it.isNotEmpty() }.map { line ->
            val (contentId, refs) = line.split(COLUMN, limit = 2)
            RevivedContent(contentId, refs.split(REF).filter { it.isNotEmpty() })
        }
    }

    object Fields {
        const val BLOCK_ID = "$ID/blockId"
        const val REVIVE = "$ID/revive"
    }
}

fun PendingBlockRevive.reviveList(): List<RevivedContent> = PendingBlockRevive.decode(revive.get())
