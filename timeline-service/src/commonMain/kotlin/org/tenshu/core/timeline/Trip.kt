package org.tenshu.core.timeline

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **curation** over moments (`docs/proposals/timeline-services.md` §5) — a bounded chapter
 * ("Lisbon, Apr 12–17"). Materialised when the user accepts a per-device `TripSuggestion`
 * (§5.2); the resulting `Trip` syncs, and a `TripMerger` refines its membership at merge time.
 *
 * This scaffold carries the trip's **identity + metadata** only. Deferred a slice (§5, same
 * list-of-composite reasons as elsewhere): **membership** (ordered day-dates / moment refs),
 * **nested-trip ids** (recursion — Lisbon holds a Sintra day-trip), **tags**, and **cover**
 * (a `:file-service` ref). The `Trip.contents` walker the UI consumes is a *projection* over
 * membership + moments, never stored.
 *
 * `Syncable(Eager)` with `ConflictStrategy.Merge`-ready shape (the `TripMerger` seam, §5.2),
 * keyed by [tripId] via [TripDataIdProvider].
 */
interface Trip : Composite {

    override val schema: CompositeSchema get() = Schema

    val tripId: TString
        get() = getField(Fields.TRIP_ID)

    val title: TString
        get() = getField(Fields.TITLE)

    /** Human date range, e.g. "Apr 12 – 17"; `""` when unset. */
    val dateRange: TString
        get() = getField(Fields.DATE_RANGE)

    /** Grouping year, e.g. "2026"; `""` when unset. */
    val year: TString
        get() = getField(Fields.YEAR)

    /**
     * The trip's member moments, as [TripMember] refs (by [Moment.momentId], §5.2). This is the
     * **mergeable collection**: a `TripMerger` unions the membership across devices under
     * `ConflictStrategy.Merge`, so a trip gets more complete as device data converges. Empty when unset.
     */
    val membership: TList<TripMember>
        get() = getField(Fields.MEMBERSHIP)

    companion object {
        const val ID = "tenshu/timeline/trip"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(TripDataIdProvider.Schema, Syncable(SyncStrategy.Eager, ConflictStrategy.Merge)),
            fields = {
                string(Fields.TRIP_ID)
                string(Fields.TITLE)
                string(Fields.DATE_RANGE)
                string(Fields.YEAR)
                // Member moments — the mergeable collection (TripMerger, §5.2). ⚠ appended at the
                // end: adding a field to a synced positional composite is peer-breaking, ship lockstep.
                list(id = Fields.MEMBERSHIP, of = TripMember.Schema)
                // nestedTripIds + tags + cover — next slice, see class KDoc
            }
        )

        fun create(
            context: TContext,
            tripId: String,
            title: String,
            dateRange: String = "",
            year: String = "",
            membership: List<String> = emptyList(),
        ): Trip = context.create(
            type = ID,
            fields = listOf(tripId, title, dateRange, year, membership.map { TripMember.create(context, it) })
        )
    }

    object Fields {
        const val TRIP_ID = "$ID/tripId"
        const val TITLE = "$ID/title"
        const val DATE_RANGE = "$ID/dateRange"
        const val YEAR = "$ID/year"
        const val MEMBERSHIP = "$ID/membership"
    }
}

val Entity<out Trip>.tripId: TString
    get() = getFieldValue(Trip.Fields.TRIP_ID)

/** Content-addresses a [Trip] by [Trip.tripId] so a re-record folds to one row. */
interface TripDataIdProvider : CompositeDataIdProvider<Trip> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/timeline/trip/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Trip.Fields.TRIP_ID
        )
    }
}
