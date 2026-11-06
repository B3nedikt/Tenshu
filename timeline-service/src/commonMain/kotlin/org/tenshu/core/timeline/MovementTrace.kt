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
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * The **path of a captured movement** — the walk / run / ride / drive between two dwells
 * (`docs/proposals/timeline-movement-capture.md` §2.1). The subject a `MOVEMENT`-kind [Moment]
 * points at (via [SubjectRef] with [SubjectKind.TRACE]), exactly as a `Visit` points at a `Place`.
 *
 * **Summary on the sync plane, path as a bounded blob (§4).** The scalar summary ([mode],
 * [distanceM], the span, [pointCount], the bounding box) rides the metadata plane so the feed
 * query stays light and a map can frame the route without decoding; the [polyline] is a
 * **decimated, delta-encoded** blob (`MovementTracePolyline`, kept under the resident-store size
 * tier). The split is deliberate: if traces later densify, the [polyline] alone relocates to the
 * content plane (fetched lazily) with no change to this summary or to [Moment].
 *
 * `Syncable(Eager)` so a walk recorded on one device converges to all paired devices (§9 decision:
 * eager now, content-plane escape hatch pre-built), keyed by [traceId] via [MovementTraceDataIdProvider]
 * so a re-emit of the same segment folds to one row.
 */
interface MovementTrace : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Our stable, self-owned id — content-addressed `cap-trace:<startMs>`. The only primary key. */
    val traceId: TString
        get() = getField(Fields.TRACE_ID)

    /** Heuristic travel mode — a `MovementMode` string (`walk` / `run` / `cycle` / `drive` / `unknown`). */
    val mode: TString
        get() = getField(Fields.MODE)

    /** Path length in metres (summed great-circle over the raw fixes, rounded). */
    val distanceM: TInteger
        get() = getField(Fields.DISTANCE_M)

    val startAt: UnixInstant
        get() = getField(Fields.START_AT)

    val endAt: UnixInstant
        get() = getField(Fields.END_AT)

    /**
     * Which classifier produced [mode] (`MOVEMENT_CLASSIFIER_VERSION`). ⚠ Load-bearing: the mode
     * thresholds are acknowledged first guesses, so **without this a retuned classifier could never be
     * applied retroactively** and old/new traces would be silently incomparable in one feed (§12.1).
     */
    val classifierVersion: TInteger
        get() = getField(Fields.CLASSIFIER_VERSION)

    /** 0–100 confidence in [mode] — boundary margin × sample size × fix accuracy (§12.1). */
    val modeConfidence: TInteger
        get() = getField(Fields.MODE_CONFIDENCE)

    /**
     * The **source's own travel-type label**, verbatim, when the trace came from an import that had
     * one (`IN_PASSENGER_VEHICLE`, `IN_SUBWAY`, `walking`, …). Empty for a live-captured trace.
     *
     * Retained because it is strictly richer than [mode] can express: Google distinguishes bus from
     * tram from subway from long-distance rail, while our own classifier cannot separate any of them
     * from a car (§13.1). It is a *classification category*, not Google **content** — the ToS line the
     * parser draws (`GoogleTimelineParser` KDoc §5) blocks persisting `transitPath`'s line names and
     * stop lists, not the motion type. Doubles as the labelled corpus §9 wants for tuning T0.
     */
    val sourceActivityType: TString
        get() = getField(Fields.SOURCE_ACTIVITY_TYPE)

    /**
     * The median per-leg speed the classifier bucketed, in **millimetres/second** (no float kind).
     *
     * ⚠ Since classifier v14 this is a **time-weighted** median — each leg counts for the span it
     * covers, not one vote apiece (`timeline-movement-capture.md` §37). The field's shape and units
     * are unchanged; what moved is the statistic, which is exactly what [classifierVersion] is here
     * to say. Comparing a v13 figure with a v14 one on the same journey is meaningless.
     */
    val medianSpeedMmPerS: TInteger
        get() = getField(Fields.MEDIAN_SPEED_MM_PER_S)

    /** Number of points in the stored [polyline] (after decimation). */
    val pointCount: TInteger
        get() = getField(Fields.POINT_COUNT)

    /**
     * Number of fixes in the **raw** path, before decimation. Tells a reader how much was discarded,
     * so a sparse Doze-starved trace is distinguishable from a dense one (§12.1).
     */
    val rawPointCount: TInteger
        get() = getField(Fields.RAW_POINT_COUNT)

    /** Bounding box (E7) — cheap map framing without decoding [polyline]. */
    val minLatE7: TInteger get() = getField(Fields.MIN_LAT_E7)
    val minLngE7: TInteger get() = getField(Fields.MIN_LNG_E7)
    val maxLatE7: TInteger get() = getField(Fields.MAX_LAT_E7)
    val maxLngE7: TInteger get() = getField(Fields.MAX_LNG_E7)

    /**
     * First and last point of the path (E7) — **direction of travel**.
     *
     * ⚠ The bounding box above is `minOf`/`maxOf` and therefore order-agnostic: a walk A→B and the
     * return walk B→A produce *identical* boxes. Direction previously survived only inside [polyline],
     * which may be empty — so the summary plane could not render an origin pin, a destination pin, or
     * an arrow, defeating §2.1's own "frame the route without decoding" goal (§12.1).
     */
    val startLatE7: TInteger get() = getField(Fields.START_LAT_E7)
    val startLngE7: TInteger get() = getField(Fields.START_LNG_E7)
    val endLatE7: TInteger get() = getField(Fields.END_LAT_E7)
    val endLngE7: TInteger get() = getField(Fields.END_LNG_E7)

    /** The decimated, delta+varint-encoded path (`MovementTracePolyline`); empty when dropped. */
    val polyline: TByteArray
        get() = getField(Fields.POLYLINE)

    /**
     * Total pedometer steps over the move (FA-4, `fit-automatic-activity-detection.md`), or
     * [STEPS_NOT_COUNTED] (`-1`) when nobody counted — no pedometer, an imported trace, or any
     * contributing fix that reported none.
     *
     * ⚠⚠ `-1` is not `0`: the schema has no nullable int, so `-1` carries `null`. `0` is a *counted*
     * zero (wheels, or a phone left in a bag) and must never be read for "unknown". Cadence is not
     * stored — it is [steps] over the span.
     */
    val steps: TInteger
        get() = getField(Fields.STEPS)

    companion object {
        const val ID = "tenshu/timeline/movementTrace"

        /** [steps] when nobody counted — see its KDoc. */
        const val STEPS_NOT_COUNTED = -1L

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(MovementTraceDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.TRACE_ID)
                string(Fields.MODE)
                int(Fields.CLASSIFIER_VERSION)
                int(Fields.MODE_CONFIDENCE)
                string(Fields.SOURCE_ACTIVITY_TYPE)
                int(Fields.MEDIAN_SPEED_MM_PER_S)
                int(Fields.DISTANCE_M)
                timestamp(Fields.START_AT)
                timestamp(Fields.END_AT)
                int(Fields.POINT_COUNT)
                int(Fields.RAW_POINT_COUNT)
                int(Fields.MIN_LAT_E7)
                int(Fields.MIN_LNG_E7)
                int(Fields.MAX_LAT_E7)
                int(Fields.MAX_LNG_E7)
                int(Fields.START_LAT_E7)
                int(Fields.START_LNG_E7)
                int(Fields.END_LAT_E7)
                int(Fields.END_LNG_E7)
                byteArray(Fields.POLYLINE)
                int(Fields.STEPS)
            }
        )

        fun create(
            context: TContext,
            traceId: String,
            mode: String,
            classifierVersion: Long,
            modeConfidence: Long,
            sourceActivityType: String,
            medianSpeedMmPerS: Long,
            distanceM: Long,
            startAt: UnixInstant,
            endAt: UnixInstant,
            pointCount: Long,
            rawPointCount: Long,
            minLatE7: Long,
            minLngE7: Long,
            maxLatE7: Long,
            maxLngE7: Long,
            startLatE7: Long,
            startLngE7: Long,
            endLatE7: Long,
            endLngE7: Long,
            polyline: ByteArray,
            steps: Long = STEPS_NOT_COUNTED,
        ): MovementTrace = context.create(
            type = ID,
            fields = listOf(
                traceId,
                mode,
                classifierVersion.toTInteger(context),
                modeConfidence.toTInteger(context),
                sourceActivityType,
                medianSpeedMmPerS.toTInteger(context),
                distanceM.toTInteger(context),
                startAt,
                endAt,
                pointCount.toTInteger(context),
                rawPointCount.toTInteger(context),
                minLatE7.toTInteger(context),
                minLngE7.toTInteger(context),
                maxLatE7.toTInteger(context),
                maxLngE7.toTInteger(context),
                startLatE7.toTInteger(context),
                startLngE7.toTInteger(context),
                endLatE7.toTInteger(context),
                endLngE7.toTInteger(context),
                polyline.toTByteArray(context),
                steps.toTInteger(context),
            )
        )
    }

    object Fields {
        const val TRACE_ID = "$ID/traceId"
        const val MODE = "$ID/mode"
        const val CLASSIFIER_VERSION = "$ID/classifierVersion"
        const val MODE_CONFIDENCE = "$ID/modeConfidence"
        const val SOURCE_ACTIVITY_TYPE = "$ID/sourceActivityType"
        const val MEDIAN_SPEED_MM_PER_S = "$ID/medianSpeedMmPerS"
        const val DISTANCE_M = "$ID/distanceM"
        const val START_AT = "$ID/startAt"
        const val END_AT = "$ID/endAt"
        const val POINT_COUNT = "$ID/pointCount"
        const val RAW_POINT_COUNT = "$ID/rawPointCount"
        const val MIN_LAT_E7 = "$ID/minLatE7"
        const val MIN_LNG_E7 = "$ID/minLngE7"
        const val MAX_LAT_E7 = "$ID/maxLatE7"
        const val MAX_LNG_E7 = "$ID/maxLngE7"
        const val START_LAT_E7 = "$ID/startLatE7"
        const val START_LNG_E7 = "$ID/startLngE7"
        const val END_LAT_E7 = "$ID/endLatE7"
        const val END_LNG_E7 = "$ID/endLngE7"
        const val POLYLINE = "$ID/polyline"
        const val STEPS = "$ID/steps"
    }
}

val Entity<out MovementTrace>.traceId: TString
    get() = getFieldValue(MovementTrace.Fields.TRACE_ID)

/** Content-addresses a [MovementTrace] by [MovementTrace.traceId] so a re-record folds to one row. */
interface MovementTraceDataIdProvider : CompositeDataIdProvider<MovementTrace> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/timeline/movementTrace/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = MovementTrace.Fields.TRACE_ID
        )
    }
}
