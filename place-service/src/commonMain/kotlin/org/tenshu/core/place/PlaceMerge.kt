package org.tenshu.core.place

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * "[placeId] is really [mergedInto]" — one venue's duplicate rows folded into one
 * (`docs/proposals/timeline-recognition-and-place-naming.md` §9.4 / F3). The ~74 m identity grid
 * splits a stay that crossed a cell boundary, and before §8's F1 it did so routinely; this is how
 * the rows already written get repaired.
 *
 * Mirrors `Moment.supersededBy` in behaviour — the merged row is **superseded, not tombstoned**, so
 * nothing is rewritten, no `Moment` re-points, and an unmerge is just deleting this row.
 *
 * ⚠⚠ **[mergedInto] is deterministic — the lexicographically smallest `placeId` in the merge set,
 * not "the row the user tapped".** Two devices merging the same pair independently must write the
 * *same* pointer, or the merge plane can only pick the newer of two contradictory ones. This is
 * `MomentReconciler`'s trick (elect the canonical off a deterministic invariant) applied one level
 * down. The user's *name* is not at the mercy of it: merging carries the user label onto the root,
 * where `PlaceMerger` protects it.
 *
 * ⚠⚠ **Why a side row rather than a `Place.mergedInto` field** (as-built, §9.4): `Place` is embedded
 * as a composite parameter of the `recordPlace` **operation**, and E2's chain verify rejects a
 * *nested* reshape — a migrated type's bytes inline into every embedding row, and the overlay
 * orchestration that would rewrite those is not built. So **no type that rides as an op parameter
 * can gain a field today**. A separate keyed row is additive, reshapes nothing, needs no migration —
 * and is arguably the better model anyway, since the overwhelming majority of places are roots and
 * would have carried an empty field forever.
 */
interface PlaceMerge : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The place being folded away — the primary key, so a place has at most one pointer. */
    val placeId: TString
        get() = getField(Fields.PLACE_ID)

    /** The surviving place's id. See the ⚠⚠ determinism note in the type KDoc. */
    val mergedInto: TString
        get() = getField(Fields.MERGED_INTO)

    /** When the merge was recorded — the tie-break if two devices somehow disagree. */
    val mergedAt: UnixInstant
        get() = getField(Fields.MERGED_AT)

    companion object {
        const val ID = "tenshu/place/placeMerge"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                PlaceMergeDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager, ConflictStrategy.Merge),
            ),
            fields = {
                string(Fields.PLACE_ID)
                string(Fields.MERGED_INTO)
                timestamp(Fields.MERGED_AT)
            }
        )

        fun create(
            context: TContext,
            placeId: String,
            mergedInto: String,
            mergedAt: UnixInstant,
        ): PlaceMerge = context.create(
            type = ID,
            fields = listOf(placeId, mergedInto, mergedAt)
        )

        /**
         * Follow the merge pointers to the place that survives. Returns [placeId] itself when it is
         * a root or when the pointer dangles (the target has not synced yet — better to render the
         * row we have than to drop it).
         *
         * ⚠ Defensive by construction: a chain (`A→B→C`) resolves by iterating, and a **cycle** —
         * which devices could only produce by disagreeing, but which must never hang a read — is
         * broken by taking the smallest id seen, the same rule that picks a target in the first
         * place, so the outcome stays deterministic.
         */
        fun resolveRoot(placeId: String, mergedIntoById: Map<String, String>): String {
            var current = placeId
            val seen = mutableSetOf(current)
            while (true) {
                val next = mergedIntoById[current]
                    ?.takeIf { it.isNotBlank() && it != current }
                    ?: return current
                if (!seen.add(next)) return seen.min() // cycle — deterministic escape
                current = next
            }
        }
    }

    object Fields {
        const val PLACE_ID = "$ID/placeId"
        const val MERGED_INTO = "$ID/mergedInto"
        const val MERGED_AT = "$ID/mergedAt"
    }
}

val Entity<out PlaceMerge>.mergedInto: TString
    get() = getFieldValue(PlaceMerge.Fields.MERGED_INTO)

/** Content-addresses a merge by the place it folds away — at most one pointer per place. */
interface PlaceMergeDataIdProvider : CompositeDataIdProvider<PlaceMerge> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/place/placeMerge/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = PlaceMerge.Fields.PLACE_ID
        )
    }
}
