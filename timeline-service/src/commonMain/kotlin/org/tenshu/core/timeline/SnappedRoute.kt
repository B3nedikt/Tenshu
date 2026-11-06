package org.tenshu.core.timeline

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.toTByteArray
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A [MovementTrace]'s path **corrected onto the map geometry it ran on** — B4,
 * `docs/proposals/timeline-movement-capture.md` §14.
 *
 * A recorded train ride is drawn from raw GPS, and raw GPS is not where the train was: it stops in
 * tunnels, wanders in urban canyons, and gets its corners cut by decimation. We ship the exact rail
 * linework, so the drawn route can be corrected to the track the train actually ran on. This row
 * holds that corrected path.
 *
 * ## ⚠⚠ Derived, and never a replacement
 *
 * [MovementTrace.polyline] is **untouched** by snapping and stays the record of what the device
 * actually observed. That is the §12.0 doctrine applied verbatim: capture loss is permanent, so a
 * classifier's *inputs* are preserved, not merely its verdict. A snap is a verdict — it depends on
 * which map regions happened to be downloaded, on the tile zoom, on the match tolerance, and on
 * [snapVersion]'s algorithm — and every one of those can improve later. Overwriting the raw path
 * would make the improvement unrepresentable and the mistake unrecoverable.
 *
 * So the two are separate rows, and the raw one is the one that can never be regenerated.
 *
 * ## ⚠ Display-only
 *
 * Nothing derives distance, duration or mode from this path. Those are computed at capture time over
 * the **raw, pre-decimation** fixes (§3), and re-deriving them here would silently rewrite recorded
 * history — a stored 34.2 km journey becoming 34.6 km because a later basemap release moved a
 * curve. The snapped path exists to be *drawn*.
 *
 * ## ⚠⚠ Why a sibling row and not a field on [MovementTrace]
 *
 * `MovementTrace` rides as a composite parameter of `RecordMovementTrace`, so its bytes inline into
 * every embedding row, and E2's chain verify rejects the nested reshape outright ("Nested reshapes
 * are not yet supported") until overlay orchestration lands. This is the same wall `PlaceMerge` and
 * `PlaceRole` hit; the general rule is on `SchemaMigrationRegistry.verify`: **no type that appears
 * as an operation parameter can gain a field.** A separately-keyed row is immune,
 * and here it is the better shape anyway: most traces are walks and drives that will never carry a
 * snap, and a sibling row costs them nothing.
 *
 * `Syncable(Eager)` and keyed by [traceId], so a re-snap of the same trace folds to one row. Syncing
 * a *derived* row earns its keep because the derivation is not reproducible everywhere: the snap
 * needs the covering map region downloaded, and the phone that recorded the ride may be the only
 * device that has it.
 */
interface SnappedRoute : Composite {

    /** The trace this corrects — the primary key, so a trace has at most one snapped route. */
    val traceId: TString
        get() = getField(Fields.TRACE_ID)

    /**
     * The corrected path, in the **same `MovementTracePolyline` codec as [MovementTrace.polyline]**
     * (delta + zig-zag varint over lat/lng/**timestamp**/accuracy), decimated to the same bound.
     *
     * ⚠⚠ Not `LonLatPolyline`, which is the lon/lat-only *wire* codec for rail linework coming back
     * from the proxy. Decoding one with the other yields plausible-looking garbage rather than an
     * error, and a previous slice did exactly that. Timestamps are what makes this the same *journey*
     * rather than a shape, so they ride through snapping: a point that landed on the track keeps its
     * source fix's own timestamp, and a track vertex walked between two landings gets one
     * interpolated by along-track distance.
     */
    val polyline: TByteArray
        get() = getField(Fields.POLYLINE)

    /** Which geometry the path was snapped to — [Sources.RAIL] today. A plain string, not an enum, so a newer peer can add road snapping with no schema change. */
    val source: TString
        get() = getField(Fields.SOURCE)

    /**
     * Which snapper produced this. Load-bearing for the same reason
     * [MovementTrace.classifierVersion] is: without it a retuned snapper could never be applied
     * retroactively, and old/new rows would be silently incomparable.
     */
    val snapVersion: TInteger
        get() = getField(Fields.SNAP_VERSION)

    /**
     * The exclusive-track coverage that justified snapping, in **basis points** (0–10000; the schema
     * has no float kind). Stored per §12.0 "keep the inputs, not just the verdict": a later tuning
     * pass can tell a marginal snap from a decisive one without re-running the match.
     */
    val coverageBp: TInteger
        get() = getField(Fields.COVERAGE_BP)

    override val schema: CompositeSchema get() = Schema

    /** Geometry a route can be snapped to. */
    object Sources {
        /** Track a road vehicle physically cannot occupy — heavy rail, subway, narrow gauge. */
        const val RAIL = "rail"
    }

    companion object {
        const val ID = "tenshu/timeline/snappedRoute"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                SnappedRouteDataIdProvider.Schema,
                // Eager, like the trace it corrects: the rows are small and bounded by the same
                // decimation cap, and a second device may not hold the map region needed to derive
                // this for itself.
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Fields.TRACE_ID)
                string(Fields.SOURCE)
                int(Fields.SNAP_VERSION)
                int(Fields.COVERAGE_BP)
                byteArray(Fields.POLYLINE)
            }
        )

        /**
         * ⚠ [fields] is **positional** and must match the `fields { }` declaration order above
         * exactly — the declaration order decides the wire layout, not this function's parameter list.
         */
        fun create(
            context: TContext,
            traceId: String,
            source: String,
            snapVersion: Long,
            coverageBp: Long,
            polyline: ByteArray,
        ): SnappedRoute = context.create(
            type = ID,
            fields = listOf(
                traceId,
                source,
                snapVersion.toTInteger(context),
                coverageBp.toTInteger(context),
                polyline.toTByteArray(context),
            )
        )
    }

    object Fields {
        const val TRACE_ID = "$ID/traceId"
        const val SOURCE = "$ID/source"
        const val SNAP_VERSION = "$ID/snapVersion"
        const val COVERAGE_BP = "$ID/coverageBp"
        const val POLYLINE = "$ID/polyline"
    }
}

val Entity<out SnappedRoute>.snappedTraceId: TString
    get() = getFieldValue(SnappedRoute.Fields.TRACE_ID)

/** Content-addresses a [SnappedRoute] by [SnappedRoute.traceId] so a re-snap folds to one row. */
interface SnappedRouteDataIdProvider : CompositeDataIdProvider<SnappedRoute> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/timeline/snappedRoute/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = SnappedRoute.Fields.TRACE_ID
        )
    }
}
