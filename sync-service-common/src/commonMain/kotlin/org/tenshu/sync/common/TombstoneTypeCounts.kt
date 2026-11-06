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
 * Durable, local-only per-type tombstone counts — the one thing [PersistedTombstone]'s per-key
 * rows cannot answer, because counting them would need an enumeration the store does not offer.
 *
 * `SyncManager.currentRealmEpoch` derives the realm epoch from the `Device.ID` count, so this
 * must survive restart: a count that reset to zero would walk the realm epoch **backwards**.
 *
 * ⚠ A single row, keyed on a constant ([SINGLETON_KEY]) so the upsert always dedupes to it —
 * the `SchemaFenceStatus` shape. The counts ride a `,`-delimited `type=count` string rather than
 * a map or list field, for the same reason `SchemaFenceStatus.fencedServiceIds` does: a `TList`
 * member needs a `DataIdProvider`, and there is nothing to key these on.
 *
 * Local-only: never serialised into a block or pushed to the relay.
 */
interface TombstoneTypeCounts : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Always [SINGLETON_KEY] — the constant this single row is keyed under. */
    val key: TString
        get() = getField(Fields.KEY)

    /** `type=count` pairs joined by [SEPARATOR]; empty string when nothing is tombstoned. */
    val counts: TString
        get() = getField(Fields.COUNTS)

    companion object {
        const val ID = "tenshu/sync/tombstoneTypeCounts"

        /** The constant this single row is keyed under. */
        const val SINGLETON_KEY = "tombstone-type-counts-v2"
        const val SEPARATOR = ","
        private const val PAIR = "="

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.KEY)
                string(Fields.COUNTS)
            }
        )

        fun create(context: TContext, counts: Map<String, Int>): TombstoneTypeCounts =
            context.create(
                type = ID,
                fields = listOf(
                    SINGLETON_KEY.toTString(context),
                    encode(counts).toTString(context),
                )
            )

        /** ⚠ Sorted, so the encoded row is stable and a no-change write stays a no-change write. */
        fun encode(counts: Map<String, Int>): String = counts.entries
            .sortedBy { it.key }
            .joinToString(SEPARATOR) { "${it.key}$PAIR${it.value}" }

        fun decode(encoded: String): Map<String, Int> = encoded
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { pair ->
                // ⚠ substringBeforeLast: a type id is a path (`tenshu/…`) and could in principle
                // contain the pair separator; the count never can.
                val count = pair.substringAfterLast(PAIR).toIntOrNull() ?: return@mapNotNull null
                pair.substringBeforeLast(PAIR) to count
            }
            .toMap()
    }

    object Fields {
        const val KEY = "$ID/key"
        const val COUNTS = "$ID/counts"
    }
}
