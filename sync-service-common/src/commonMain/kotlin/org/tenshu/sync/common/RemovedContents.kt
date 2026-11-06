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
 * The content a removed holder named, by its wire name (CR-2 B4 §2.3, adds before removes): recorded by
 * `SyncObserver`'s pre-delete hook from `edgesOf(row)` while the row still exists, and read by the
 * tombstone and reap drains, which hold a removal while any queued, unacknowledged block adds one of
 * these ids.
 *
 * ⚠⚠ Nothing else knows which content a removed holder named: the tombstone carries a wire name, the
 * reap a wire name and a stamp, and the row is gone by the time either drains.
 *
 * ⚠ Dropped with the tombstone or reap that consumes it. One string: content ids, one per line.
 *
 * ⚠ Device-local, never synced. A **new** composite, so no `SyncService` version moves (HR #9).
 */
interface RemovedContents : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The removed holder's wire name — this row's key. */
    val holder: TString
        get() = getField(Fields.HOLDER)

    /** Its non-null content ids, newline-separated. Never empty (nothing is stored then). */
    val contentIds: TString
        get() = getField(Fields.CONTENT_IDS)

    companion object {
        const val ID = "tenshu/sync/removedContents"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.HOLDER)
                string(Fields.CONTENT_IDS)
            }
        )

        fun create(context: TContext, holder: String, contentIds: Set<String>): RemovedContents =
            context.create(
                type = ID,
                fields = listOf(holder.toTString(context), contentIds.joinToString("\n").toTString(context)),
            )
    }

    object Fields {
        const val HOLDER = "$ID/holder"
        const val CONTENT_IDS = "$ID/contentIds"
    }
}

fun RemovedContents.contentIdSet(): Set<String> = contentIds.get().split("\n").filter { it.isNotEmpty() }.toSet()
