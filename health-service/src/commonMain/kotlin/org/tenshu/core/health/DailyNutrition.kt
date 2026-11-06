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
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **per-day nutrition aggregate** — the daily energy-consumed + hydration roll-up behind the Fit
 * app's Nutrition Browse category (`docs/proposals/fit-app.md` §12 B3a). The nutrition sibling of
 * [DailyActivity]: one whole-day total, keyed by day, from a Health Connect / HealthKit dietary
 * import (Fit does not author meals itself — v1 is import-only).
 *
 * **Value shape (SI ints).** [energyKj] is kilojoules (the store's energy unit, as [DailyActivity]
 * `activeEnergyKj`; the UI shows kcal via `FitFormat.kjToKcal`); [hydrationMl] is millilitres. Both
 * `0` when unknown / no source.
 *
 * Keyed by [date] (ISO-8601 `YYYY-MM-DD`) via [DailyNutritionDataIdProvider], so a re-import of the
 * same day folds to one row. `Syncable(Eager)`; `RealmData` deferred (as the sibling health entities
 * also skip it).
 */
interface DailyNutrition : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The calendar day this aggregate is for — ISO-8601 `YYYY-MM-DD`. The primary key. */
    val date: TString
        get() = getField(Fields.DATE)

    /** Energy consumed for the day, in kilojoules; `0` when unknown. */
    val energyKj: TInteger
        get() = getField(Fields.ENERGY_KJ)

    /** Water intake for the day, in millilitres; `0` when unknown. */
    val hydrationMl: TInteger
        get() = getField(Fields.HYDRATION_ML)

    companion object {
        const val ID = "tenshu/health/dailyNutrition"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(DailyNutritionDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DATE)
                int(Fields.ENERGY_KJ)
                int(Fields.HYDRATION_ML)
            }
        )

        fun create(
            context: TContext,
            date: String,
            energyKj: Long = 0,
            hydrationMl: Long = 0,
        ): DailyNutrition = context.create(
            type = ID,
            fields = listOf(
                date,
                energyKj.toTInteger(context),
                hydrationMl.toTInteger(context),
            )
        )
    }

    object Fields {
        const val DATE = "$ID/date"
        const val ENERGY_KJ = "$ID/energyKj"
        const val HYDRATION_ML = "$ID/hydrationMl"
    }
}

val Entity<out DailyNutrition>.date: TString
    get() = getFieldValue(DailyNutrition.Fields.DATE)

/**
 * Content-addresses a [DailyNutrition] by its [DailyNutrition.date], so re-importing the same day
 * folds to a single entity rather than duplicating.
 */
interface DailyNutritionDataIdProvider : CompositeDataIdProvider<DailyNutrition> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/dailyNutrition/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = DailyNutrition.Fields.DATE
        )
    }
}
