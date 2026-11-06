package org.tenshu.routine

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.genericData
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.core.trait.RealmData
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One firing of one [Routine], keyed by its deterministic [firingId]
 * (`routines-engine.md` RE-D3).
 *
 * ⭐ **The receipt and the history row are the same row.** A receipt that
 * carries its outcome is exactly what Home's Activity tab needs, so there is no
 * second type and no risk of the two disagreeing. The same row is:
 *
 *  - the **cross-device dedupe receipt** a grace-fallback executor checks
 *    before firing late (RE-D2), and
 *  - the **history row** the Activity tab renders, from which `lastRunAt` is
 *    *derived*.
 *
 * ⚠⚠ A synced receipt **cannot prevent a race, only record one** — the sync
 * round trip is longer than the action it would suppress. Mutual exclusion
 * comes from RE-D2's deterministic claim; this row is what makes a *late*
 * fallback stand down, and what makes a genuine race survivable (the actions
 * are absolute — RE-D8).
 *
 * ⚠ `lastRunAt` is deliberately **not** a mutable field on [Routine]: it would
 * make every firing a write to the row the user edits — racing a rename into an
 * LWW clobber, and turning a cold config row into a hot one that emits a block
 * on every sunset.
 *
 * ⚠ **Retention** (RE-D10): one row per firing is unbounded growth on a synced
 * type. The executing device prunes via ordinary tombstones — keep the most
 * recent [RETAIN_ROWS] rows or [RETAIN_MS], whichever is smaller. Pruning is
 * the executor's job (it already holds the claim) and is best-effort: a missed
 * prune costs rows, never correctness.
 */
interface RoutineRun : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Deterministic firing id — the row key, and the unit of idempotence (RE-D3). */
    val firingId: TString
        get() = getField(Fields.FIRING_ID)

    val routineId: TString
        get() = getField(Fields.ROUTINE_ID)

    /**
     * The instant the trigger was *for*, not when it ran. Both devices compute
     * this identically because it derives from the routine's own timezone and
     * coordinates (RE-D3), never the executor's.
     */
    val triggerInstantEpochMs: TInteger
        get() = getField(Fields.TRIGGER_INSTANT_EPOCH_MS)

    /** Realm device that claimed and ran this firing. */
    val executorDeviceId: TString
        get() = getField(Fields.EXECUTOR_DEVICE_ID)

    val claimedAtEpochMs: TInteger
        get() = getField(Fields.CLAIMED_AT_EPOCH_MS)

    /** [NOT_FINISHED] while the run is in flight — the "interrupted" signal. */
    val finishedAtEpochMs: TInteger
        get() = getField(Fields.FINISHED_AT_EPOCH_MS)

    /** [STATE_CLAIMED] | [STATE_RAN] | [STATE_FAILED] | [STATE_SKIPPED] | [STATE_MISSED] | [STATE_THROTTLED]. */
    val state: TString
        get() = getField(Fields.STATE)

    /** True when a non-primary device fired after the RE-D2 grace window. */
    val wasFallback: TBoolean
        get() = getField(Fields.WAS_FALLBACK)

    /** Root of the chain this firing belongs to; equals [firingId] for a root firing. */
    val chainRootFiringId: TString
        get() = getField(Fields.CHAIN_ROOT_FIRING_ID)

    /** 0 for a root firing; bounded by the RE-D8 recursion guard. */
    val depth: TInteger
        get() = getField(Fields.DEPTH)

    /**
     * What each condition read and how fresh it was (RE-D7), as a bounded
     * string. ⚠ A wrong outcome that cites its inputs is debuggable; one that
     * does not is a support ticket.
     */
    val conditionCitations: TString
        get() = getField(Fields.CONDITION_CITATIONS)

    /** Per-action outcomes (RE-D8), as a bounded string. */
    val actionOutcomes: TString
        get() = getField(Fields.ACTION_OUTCOMES)

    /**
     * How many further firings this row absorbed while throttled.
     *
     * ⚠⚠ Load-bearing, not bookkeeping: RE-D9's token bucket is also a **sync**
     * brake, and if every refused firing wrote its own row the brake would only
     * change a state field — a flapping sensor would flood the realm with
     * `THROTTLED` rows instead of `RAN` rows. The first refusal in a window
     * writes one row and the rest coalesce into this counter, so the row rate is
     * bounded by *windows*, not by firings (`routines-engine.md` §4.9).
     */
    val coalescedCount: TInteger
        get() = getField(Fields.COALESCED_COUNT)

    /** Forward-compatibility escape hatch. */
    val extras: Data?
        get() = getOptionalField(Fields.EXTRAS)

    companion object {
        const val ID = "tenshu/routine/run"

        /** Journalled and claimed; actions not finished. Rendered as "interrupted" if it never advances. */
        const val STATE_CLAIMED = "claimed"

        /** Every action reported success. */
        const val STATE_RAN = "ran"

        /** At least one action failed. ⚠ There is no rollback for a light bulb (RE-D8). */
        const val STATE_FAILED = "failed"

        /** Conditions were not met, or were `Unknown` and therefore failed closed (RE-D7). */
        const val STATE_SKIPPED = "skipped"

        /** Woke too late — outside the engine's catch-up window (RE-D6). */
        const val STATE_MISSED = "missed"

        /** Refused by the RE-D9 safety gate; see [coalescedCount]. */
        const val STATE_THROTTLED = "throttled"

        /** [finishedAtEpochMs] sentinel while a run is in flight. */
        const val NOT_FINISHED = -1L

        /** Retention (class KDoc): rows, and age in ms. */
        const val RETAIN_ROWS = 200
        const val RETAIN_MS = 30L * 24 * 60 * 60 * 1000

        /** Bound on [conditionCitations] / [actionOutcomes] — a `Syncable(Eager)` row. */
        const val MAX_DETAIL_BYTES = 4 * 1024

        /** Keys the row by [Fields.FIRING_ID]. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.FIRING_ID
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(
                DataIdProviderSchema,
                Syncable(SyncStrategy.Eager),
                RealmData.Schema,
            ),
            fields = {
                string(Fields.FIRING_ID)
                string(Fields.ROUTINE_ID)
                int(Fields.TRIGGER_INSTANT_EPOCH_MS)
                string(Fields.EXECUTOR_DEVICE_ID)
                int(Fields.CLAIMED_AT_EPOCH_MS)
                int(Fields.FINISHED_AT_EPOCH_MS)
                string(Fields.STATE)
                boolean(Fields.WAS_FALLBACK)
                string(Fields.CHAIN_ROOT_FIRING_ID)
                int(Fields.DEPTH)
                string(Fields.CONDITION_CITATIONS)
                string(Fields.ACTION_OUTCOMES)
                int(Fields.COALESCED_COUNT)
                genericData(Fields.EXTRAS, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            firingId: String,
            routineId: String,
            triggerInstantEpochMs: Long,
            executorDeviceId: String = "",
            claimedAtEpochMs: Long,
            finishedAtEpochMs: Long = NOT_FINISHED,
            state: String,
            wasFallback: Boolean = false,
            chainRootFiringId: String = firingId,
            depth: Long = 0,
            conditionCitations: String = "",
            actionOutcomes: String = "",
            coalescedCount: Long = 0,
            extras: Data? = null,
        ): RoutineRun = context.create(
            type = ID,
            fields = listOf(
                firingId.toTString(context),
                routineId.toTString(context),
                triggerInstantEpochMs.toTInteger(context),
                executorDeviceId.toTString(context),
                claimedAtEpochMs.toTInteger(context),
                finishedAtEpochMs.toTInteger(context),
                state.toTString(context),
                wasFallback.toTBoolean(context),
                chainRootFiringId.toTString(context),
                depth.toTInteger(context),
                conditionCitations.toTString(context),
                actionOutcomes.toTString(context),
                coalescedCount.toTInteger(context),
                extras,
            )
        )
    }

    object Fields {
        const val FIRING_ID = "$ID/firingId"
        const val ROUTINE_ID = "$ID/routineId"
        const val TRIGGER_INSTANT_EPOCH_MS = "$ID/triggerInstantEpochMs"
        const val EXECUTOR_DEVICE_ID = "$ID/executorDeviceId"
        const val CLAIMED_AT_EPOCH_MS = "$ID/claimedAtEpochMs"
        const val FINISHED_AT_EPOCH_MS = "$ID/finishedAtEpochMs"
        const val STATE = "$ID/state"
        const val WAS_FALLBACK = "$ID/wasFallback"
        const val CHAIN_ROOT_FIRING_ID = "$ID/chainRootFiringId"
        const val DEPTH = "$ID/depth"
        const val CONDITION_CITATIONS = "$ID/conditionCitations"
        const val ACTION_OUTCOMES = "$ID/actionOutcomes"
        const val COALESCED_COUNT = "$ID/coalescedCount"
        const val EXTRAS = "$ID/extras"
    }
}
