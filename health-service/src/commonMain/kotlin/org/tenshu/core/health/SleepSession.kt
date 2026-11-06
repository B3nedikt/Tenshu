package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **single night's sleep** — the per-day sleep session the Fit app's Sleep category reads
 * (`docs/proposals/fit-app.md` §5 `SleepSession`; `timeline-services.md` §4). The second
 * deferred `:health-service` entity beyond [Workout] / [DailyActivity], following the same
 * 5-module pattern, and the exemplar the remaining vitals/body/medical stores will copy.
 *
 * **Stage shape (scalar, not a list-of-composite).** The mock's `SleepSession` carries
 * `stages: List<SleepStage>` — minutes in each of the four fixed [awakeMin]/[remMin]/[lightMin]/
 * [deepMin] stages. Because the stage set is a **fixed enum** (`SleepStageKind`), those four
 * minute totals are stored as four plain `int` fields rather than an embedded array — mirroring
 * [DailyActivity]'s all-scalar shape and deliberately sidestepping the laps-style
 * "list-of-composite needs a per-element `DataIdProvider`" decision [Workout] explicitly deferred.
 * The projector reconstructs the `List<SleepStage>` (dropping zero-minute stages) on the way out.
 *
 * Keyed by [date] (ISO-8601 `YYYY-MM-DD`) via [SleepSessionDataIdProvider] — one session per
 * night, so a re-import of the same day (incl. concurrently on two devices) folds to one row,
 * matching the app's `maxByOrNull { it.date }` "last night" read. `Syncable(Eager)`; `RealmData`
 * deferred (as [Workout] / [DailyActivity] also skip it) — opt in when sleep should surface in
 * Control's Data section.
 */
interface SleepSession : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The night this session is attributed to — ISO-8601 `YYYY-MM-DD`. The primary key. */
    val date: TString
        get() = getField(Fields.DATE)

    val start: UnixInstant
        get() = getField(Fields.START)

    val end: UnixInstant
        get() = getField(Fields.END)

    /** Total time asleep in seconds. */
    val durationSec: TInteger
        get() = getField(Fields.DURATION_SEC)

    /** Where it came from — `HealthKit` / `HealthConnect` / `Fit` / `Manual`. */
    val source: TString
        get() = getField(Fields.SOURCE)

    /** Minutes spent awake during the session; `0` when unknown / no stage breakdown. */
    val awakeMin: TInteger
        get() = getField(Fields.AWAKE_MIN)

    /** Minutes in REM sleep; `0` when unknown. */
    val remMin: TInteger
        get() = getField(Fields.REM_MIN)

    /** Minutes in light sleep; `0` when unknown. */
    val lightMin: TInteger
        get() = getField(Fields.LIGHT_MIN)

    /** Minutes in deep sleep; `0` when unknown. */
    val deepMin: TInteger
        get() = getField(Fields.DEEP_MIN)

    companion object {
        const val ID = "tenshu/health/sleepSession"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(SleepSessionDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DATE)
                timestamp(Fields.START)
                timestamp(Fields.END)
                int(Fields.DURATION_SEC)
                string(Fields.SOURCE)
                int(Fields.AWAKE_MIN)
                int(Fields.REM_MIN)
                int(Fields.LIGHT_MIN)
                int(Fields.DEEP_MIN)
            }
        )

        fun create(
            context: TContext,
            date: String,
            start: UnixInstant,
            end: UnixInstant,
            durationSec: Long,
            source: String,
            awakeMin: Long = 0,
            remMin: Long = 0,
            lightMin: Long = 0,
            deepMin: Long = 0,
        ): SleepSession = context.create(
            type = ID,
            fields = listOf(
                date,
                start,
                end,
                durationSec.toTInteger(context),
                source,
                awakeMin.toTInteger(context),
                remMin.toTInteger(context),
                lightMin.toTInteger(context),
                deepMin.toTInteger(context),
            )
        )
    }

    object Fields {
        const val DATE = "$ID/date"
        const val START = "$ID/start"
        const val END = "$ID/end"
        const val DURATION_SEC = "$ID/durationSec"
        const val SOURCE = "$ID/source"
        const val AWAKE_MIN = "$ID/awakeMin"
        const val REM_MIN = "$ID/remMin"
        const val LIGHT_MIN = "$ID/lightMin"
        const val DEEP_MIN = "$ID/deepMin"
    }
}

val Entity<out SleepSession>.date: TString
    get() = getFieldValue(SleepSession.Fields.DATE)

/**
 * Content-addresses a [SleepSession] by its [SleepSession.date], so re-importing the same
 * night — including concurrently on two devices — folds to a single entity rather than duplicating.
 */
interface SleepSessionDataIdProvider : CompositeDataIdProvider<SleepSession> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/sleepSession/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = SleepSession.Fields.DATE
        )
    }
}
