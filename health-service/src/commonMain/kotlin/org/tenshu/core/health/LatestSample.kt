package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.toTBoolean
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp

/**
 * The **maintained latest-per-metric rollup** over the [HealthSample] time-series
 * (`fit-read-scaling.md` Phases 3/4): one row per `(metric, resting)` pair, holding the newest
 * reading — so the Browse teasers + detail current-value headers read ≤ a handful of rows
 * instead of scanning the whole sample history (unbounded: a 10-year import is tens of
 * thousands of samples; that scan was the read-side OOM candidate `fit-read-scaling.md`
 * Phase 4 predicted).
 *
 * **Locally derived, never synced.** Deliberately NOT `Syncable`: like relation membership
 * ("not a synced primitive — each receiver derives it locally", [HealthService] maintainer
 * doctrine), every device maintains its own rollup from the [HealthSample] saves it observes —
 * which include rows arriving via sync, because the maintainer is a `DataObserver` on the save
 * path. Syncing a derived row would only add convergence noise on data every peer can derive
 * identically.
 *
 * Content-addressed by [latestKey] = `"$metric::$resting"`, so a newer reading of the same
 * logical metric folds onto one row (resting vs non-resting heart rate are distinct logical
 * metrics, mirroring `FitProjection.latestPerMetric`). The maintainer only overwrites when the
 * incoming reading's [time] is >= the stored one — out-of-order arrivals (a historical import
 * after live captures) must not regress the teaser.
 *
 * ⚠ Known limit: deleting the sample that *was* the latest leaves this rollup stale until the
 * next write of that metric. Samples are append-mostly (no delete surface exists); recomputing
 * on delete would need exactly the scan this type removes.
 */
interface LatestSample : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The content-address key — `"$metric::$resting"` — one row per logical metric. */
    val latestKey: TString
        get() = getField(Fields.LATEST_KEY)

    /** The metric this reading is of — `HeartRate` / `Weight` / `Height` / `BloodOxygen` / `RespiratoryRate`. */
    val metric: TString
        get() = getField(Fields.METRIC)

    /** The reading value, as a plain decimal string (the [HealthSample.value] convention). */
    val value: TString
        get() = getField(Fields.VALUE)

    /** The unit label — `bpm` / `kg` / `%` / `/min`. */
    val unit: TString
        get() = getField(Fields.UNIT)

    /** When the (newest) reading was taken. */
    val time: UnixInstant
        get() = getField(Fields.TIME)

    /** Where it came from — `HealthKit` / `HealthConnect` / `Fit` / `Manual`. */
    val source: TString
        get() = getField(Fields.SOURCE)

    /** Whether this is the resting flavour of the metric (v1: resting heart rate). */
    val resting: TBoolean
        get() = getField(Fields.RESTING)

    companion object {
        const val ID = "tenshu/health/latestSample"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // No Syncable trait — locally derived (see class KDoc).
            traits = listOf(LatestSampleDataIdProvider.Schema),
            fields = {
                string(Fields.LATEST_KEY)
                string(Fields.METRIC)
                string(Fields.VALUE)
                string(Fields.UNIT)
                timestamp(Fields.TIME)
                string(Fields.SOURCE)
                boolean(Fields.RESTING)
            }
        )

        /** One rollup row per logical metric: resting and non-resting key separately. */
        fun keyFor(metric: String, resting: Boolean): String = "$metric::$resting"

        fun create(
            context: TContext,
            metric: String,
            value: String,
            unit: String,
            time: UnixInstant,
            source: String,
            resting: Boolean = false,
        ): LatestSample = context.create(
            type = ID,
            fields = listOf(
                keyFor(metric, resting),
                metric,
                value,
                unit,
                time,
                source,
                resting.toTBoolean(context),
            )
        )
    }

    object Fields {
        const val LATEST_KEY = "$ID/latestKey"
        const val METRIC = "$ID/metric"
        const val VALUE = "$ID/value"
        const val UNIT = "$ID/unit"
        const val TIME = "$ID/time"
        const val SOURCE = "$ID/source"
        const val RESTING = "$ID/resting"
    }
}

/** Content-addresses a [LatestSample] by `"$metric::$resting"` — newer readings fold on. */
interface LatestSampleDataIdProvider : CompositeDataIdProvider<LatestSample> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/latestSample/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = LatestSample.Fields.LATEST_KEY
        )
    }
}
