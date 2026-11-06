package org.tenshu.health.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.number.TInteger

/**
 * The **per-field fold for [DailyActivity]** — the one place that decides what happens when a
 * second row arrives for a day that already has one.
 *
 * [DailyActivity] is content-addressed by `date` alone (`DailyActivityDataIdProvider`), so every
 * write for a day targets the *same* `DataId` and a plain `saveData` **replaces the whole row**.
 * That is correct for the identity ("one row per day") and wrong for the content, because the
 * sources that write a day are *partial in different ways*:
 *
 * - a **Google Takeout** import carries `distanceM` / `activeEnergyKj` / `activeMinutes` from
 *   Google's own daily aggregate;
 * - a **Health Connect** refresh, on a device where only the platform pedometer writes to HC,
 *   carries `steps` and literally nothing else — `DistanceRecord` / `TotalCaloriesBurnedRecord`
 *   read back empty, so the importer maps them to `0`.
 *
 * Whole-row replace therefore let the steps-only HC row **clobber** the rich Takeout row on the
 * next launch: distance, energy and active minutes silently reverted to `0` for every day the
 * two sources overlapped. The user sees a day they know they walked read as "0 m, 0 kcal".
 *
 * **The rule: a field is only overwritten by a value that says something.** [DailyActivity]'s own
 * schema defines `0` as "unknown / no source" for all four stats, so `0` is precisely the value
 * that must not win. Each stat takes the incoming figure when it is non-zero and otherwise keeps
 * what is already stored. Fields no source has ever populated stay `0`.
 *
 * ⚠ **This is a fold, not a join-semilattice** — unlike `RealmSchemaEpochMerger`'s MAX, it is not
 * commutative when *both* sides carry a non-zero value for the same stat: the incoming one wins,
 * so the effective policy is "last writer wins per contested field, union over uncontested ones".
 * That is deliberate. MAX would converge unconditionally but would also make a legitimate
 * *downward correction* (a source revising a day's steps down) impossible to express, and it would
 * pin every day to the highest figure any source ever reported. Ordering is not a correctness
 * problem here because both seams are already ordered — the local path folds onto whatever is
 * committed, and the sync path only consults a merger for an HLC-newer block — so two devices
 * applying the same set of blocks land on the same row.
 */
internal object DailyActivityFold {

    /**
     * The stored row for [incoming]'s day, folded with [incoming]. Returns [incoming] verbatim when
     * nothing is stored for that day yet (the ordinary first-write case) or when either side is
     * unreadable — a malformed row must never be able to erase a good one.
     *
     * Reads through [ExecutionContext.database] by the same `date`-keyed [DataId] the save will
     * target, mirroring `HealthProxyInitialization`'s `upsertIfNewer` lookup.
     */
    fun foldOntoStored(executionContext: ExecutionContext, incoming: Data): Data {
        val date = statsOf(incoming)?.date ?: return incoming
        val storedId = DataId.StringDataId.create(
            context = executionContext.context,
            type = DailyActivity.ID,
            keyValue = date,
        )
        val stored = executionContext.database.getData(storedId, asEntity = false) ?: return incoming
        return fold(executionContext.context, existing = stored, incoming = incoming)
    }

    /**
     * [existing] and [incoming] folded per the rule in the class KDoc. Falls back to [incoming]
     * whenever the fold cannot be performed honestly (either side unreadable, or the two rows
     * describe different days — which would mean the caller paired rows that are not the same
     * entity).
     */
    fun fold(context: TContext, existing: Data, incoming: Data): Data {
        val incomingStats = statsOf(incoming) ?: return incoming
        val existingStats = statsOf(existing) ?: return incoming
        if (existingStats.date != incomingStats.date) return incoming

        return DailyActivity.create(
            context = context,
            date = incomingStats.date,
            steps = incomingStats.steps.orKeep(existingStats.steps),
            distanceM = incomingStats.distanceM.orKeep(existingStats.distanceM),
            activeEnergyKj = incomingStats.activeEnergyKj.orKeep(existingStats.activeEnergyKj),
            activeMinutes = incomingStats.activeMinutes.orKeep(existingStats.activeMinutes),
        )
    }

    /** Take this incoming stat when the source actually reported one; otherwise keep [stored]. */
    private fun Long.orKeep(stored: Long): Long = if (this > 0) this else stored

    /**
     * The row's four stats plus its day, whether it arrived as a typed [DailyActivity] (the op
     * params and the merger's operands), an [Entity] or a bare [Composite] (shapes a store read
     * can produce). `null` when the day is unreadable — the one field without which the fold is
     * meaningless. Mirrors `RealmSchemaEpochMerger`'s multi-shape accessor discipline.
     */
    private fun statsOf(data: Data): Stats? {
        if (data is DailyActivity) {
            val date = runCatching { data.date.get() }.getOrNull() ?: return null
            return Stats(
                date = date,
                steps = runCatching { data.steps.get() }.getOrNull() ?: 0L,
                distanceM = runCatching { data.distanceM.get() }.getOrNull() ?: 0L,
                activeEnergyKj = runCatching { data.activeEnergyKj.get() }.getOrNull() ?: 0L,
                activeMinutes = runCatching { data.activeMinutes.get() }.getOrNull() ?: 0L,
            )
        }
        val date = asString(rawField(data, DailyActivity.Fields.DATE)) ?: return null
        return Stats(
            date = date,
            steps = longField(data, DailyActivity.Fields.STEPS),
            distanceM = longField(data, DailyActivity.Fields.DISTANCE_M),
            activeEnergyKj = longField(data, DailyActivity.Fields.ACTIVE_ENERGY_KJ),
            activeMinutes = longField(data, DailyActivity.Fields.ACTIVE_MINUTES),
        )
    }

    /** An unreadable or absent stat reads as `0` — "unknown", exactly what the schema means by it. */
    private fun longField(data: Data, fieldId: String): Long =
        asLong(rawField(data, fieldId)) ?: 0L

    private fun rawField(data: Data, fieldId: String): Any? = runCatching {
        when (data) {
            is Entity<*> -> data.getFieldValue<Any?>(fieldId)
            is Composite -> data.getField<Data>(fieldId)
            else -> null
        }
    }.getOrNull()

    /** Values reach us either wrapped in their `T*` type or as the raw Kotlin scalar. */
    private fun asString(value: Any?): String? = when (value) {
        is TString -> runCatching { value.get() }.getOrNull()
        is String -> value
        else -> null
    }

    private fun asLong(value: Any?): Long? = when (value) {
        is TInteger -> runCatching { value.get() }.getOrNull()
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }

    private class Stats(
        val date: String,
        val steps: Long,
        val distanceM: Long,
        val activeEnergyKj: Long,
        val activeMinutes: Long,
    )
}
