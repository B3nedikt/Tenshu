package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.toTBoolean
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **timestamped reading of a single health metric** — the body/vitals sample behind the Fit
 * app's Body + Vitals Browse categories (`docs/proposals/fit-app.md` §5 `SampleLatest` + §12 B2;
 * `timeline-services.md` §4). The third deferred `:health-service` entity beyond [Workout] /
 * [DailyActivity] / [SleepSession], same 5-module pattern.
 *
 * **Time-series by construction (fit-app.md §12 B2).** Keyed by [sampleId] = `"$metric::$timeMs"`
 * via [HealthSampleDataIdProvider], so distinct-time readings of the same metric **accrue as
 * separate rows** (the Body/Vitals detail charts the history), while re-recording the *same*
 * metric at the *same* instant folds onto one row (idempotent re-import). The "latest per metric"
 * surface the Browse teaser + the detail's current-value header read is *derived* — newest by
 * [time] per metric — not a store invariant. (v1 kept one row per metric, keyed by [metric] alone;
 * B2 re-keyed to retain history — hence the `TENSHU_STORAGE_FORMAT_VERSION` bump.) [resting] rides
 * along per reading (v1's single use: resting heart rate).
 *
 * **Value shape (string, not a native float).** The schema DSL has no floating-point value kind
 * (only `int` = `Long`), and readings are fractional (weight `74.2`), so [value] is stored as its
 * plain decimal string — lossless, no scaling convention — and the projector parses it to a
 * `Double`. Mirrors how `date` rides as an ISO string. `Syncable(Eager)`; `RealmData` deferred
 * (as the sibling health entities also skip it).
 */
interface HealthSample : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The content-address key — `"$metric::$timeMs"` — so distinct-time readings retain as separate rows. */
    val sampleId: TString
        get() = getField(Fields.SAMPLE_ID)

    /** The metric this reading is of — `HeartRate` / `Weight` / `Height` / `BloodOxygen` / `RespiratoryRate`. */
    val metric: TString
        get() = getField(Fields.METRIC)

    /** The reading value, as a plain decimal string (e.g. `"74.2"`); the projector parses to `Double`. */
    val value: TString
        get() = getField(Fields.VALUE)

    /** The unit label — `bpm` / `kg` / `%` / `/min`. */
    val unit: TString
        get() = getField(Fields.UNIT)

    /** When the reading was taken. */
    val time: UnixInstant
        get() = getField(Fields.TIME)

    /** Where it came from — `HealthKit` / `HealthConnect` / `Fit` / `Manual`. */
    val source: TString
        get() = getField(Fields.SOURCE)

    /** Whether this is a resting reading (e.g. resting heart rate); `false` otherwise. */
    val resting: TBoolean
        get() = getField(Fields.RESTING)

    companion object {
        const val ID = "tenshu/health/healthSample"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(HealthSampleDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.SAMPLE_ID)
                string(Fields.METRIC)
                string(Fields.VALUE)
                string(Fields.UNIT)
                timestamp(Fields.TIME)
                string(Fields.SOURCE)
                boolean(Fields.RESTING)
            }
        )

        /** The content-address key: a metric read at a distinct instant is a distinct row (§12 B2). */
        fun keyFor(metric: String, timeMs: Long): String = "$metric::$timeMs"

        fun create(
            context: TContext,
            metric: String,
            value: String,
            unit: String,
            time: UnixInstant,
            source: String,
            resting: Boolean = false,
        ): HealthSample = context.create(
            type = ID,
            fields = listOf(
                keyFor(metric, time.toEpochMilliseconds()),
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
        const val SAMPLE_ID = "$ID/sampleId"
        const val METRIC = "$ID/metric"
        const val VALUE = "$ID/value"
        const val UNIT = "$ID/unit"
        const val TIME = "$ID/time"
        const val SOURCE = "$ID/source"
        const val RESTING = "$ID/resting"
    }
}

val Entity<out HealthSample>.metric: TString
    get() = getFieldValue(HealthSample.Fields.METRIC)

/**
 * Content-addresses a [HealthSample] by its [HealthSample.sampleId] (`"$metric::$timeMs"`), so
 * distinct-time readings of a metric retain as separate rows (the time-series the Body/Vitals
 * detail charts) while a re-import of the same reading folds onto one row.
 */
interface HealthSampleDataIdProvider : CompositeDataIdProvider<HealthSample> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/healthSample/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = HealthSample.Fields.SAMPLE_ID
        )
    }
}
