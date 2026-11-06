package org.tenshu.routine

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTString

/**
 * Durable, **local-only** journal marker for one in-flight firing
 * (`routines-engine.md` RE-D4 / §4.9 step 7).
 *
 * ⭐ **Two dedupe mechanisms, two jobs.** The synced [RoutineRun] receipt dedupes
 * across *devices*; this marker dedupes across *restarts*. Neither can do the
 * other's job — sync is far too slow to guard a restart, and a local journal is
 * invisible to peers.
 *
 * The engine appends one **before** running any action and clears it when the
 * run finishes. A marker still present at startup means a run was interrupted
 * mid-flight, and the engine deliberately **does not re-run it**: at-most-once,
 * matching the hub's `ConsumedCommandJournal` posture and for the same reason —
 * ⚠ *a repeated physical actuation is worse than a missed one, and there is no
 * rollback for a light bulb.*
 *
 * ⚠ Never `Syncable`: it carries no [org.tenshu.core.trait.Syncable] trait, so
 * it is never serialised into a block or pushed. Mirrors `RekeyMigrationMarker`.
 */
interface RoutineFiringMarker : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The `firingId` this marker guards — the row key. */
    val firingId: TString
        get() = getField(Fields.FIRING_ID)

    val routineId: TString
        get() = getField(Fields.ROUTINE_ID)

    /** When the engine claimed this firing (diagnostics for an interrupted run). */
    val startedAtEpochMs: TInteger
        get() = getField(Fields.STARTED_AT_EPOCH_MS)

    companion object {
        const val ID = "tenshu/routine/firingMarker"

        /** The local array the journal lives in (the `ScheduleStore` pattern). */
        const val COLLECTION = "$ID/pending"

        /** Keys the marker by [Fields.FIRING_ID]. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.FIRING_ID
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema),
            fields = {
                string(Fields.FIRING_ID)
                string(Fields.ROUTINE_ID)
                int(Fields.STARTED_AT_EPOCH_MS)
            }
        )

        fun create(
            context: TContext,
            firingId: String,
            routineId: String,
            startedAtEpochMs: Long,
        ): RoutineFiringMarker = context.create(
            type = ID,
            fields = listOf(
                firingId.toTString(context),
                routineId.toTString(context),
                startedAtEpochMs.toTInteger(context),
            )
        )
    }

    object Fields {
        const val FIRING_ID = "$ID/firingId"
        const val ROUTINE_ID = "$ID/routineId"
        const val STARTED_AT_EPOCH_MS = "$ID/startedAtEpochMs"
    }
}
