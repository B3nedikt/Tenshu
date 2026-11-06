package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * The task row `TaskService` ops return (`core-task-composite.md` §2/§5) —
 * the data mirror of one registered bulk task. Client-side, the typed handle
 * (`BackgroundTaskHandle<R>` in `:sync-service-client`) wraps the [taskId]
 * and delegates lifecycle to the generic ops; the phantom result type lives
 * on the handle/spec, never here (no `typeVar` field — see `TaskSpec`).
 *
 * Progress is deliberately NOT on this row (see `RenewTaskLease` KDoc) —
 * the generic task API models lifecycle + lease only; progress is a domain
 * concern.
 *
 * v1 rows are **in-memory registry state only** (Layer 2 — they die with the
 * proxy; the lane journal already preserves staged deltas). Not `Syncable`,
 * not persisted; cross-device task visibility is deferred (TS-6).
 */
interface BackgroundTask : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Client-minted task id (the Phase-L2 precedent) — also the sync-lane key. */
    val taskId: TString
        get() = getField(Fields.TASK_ID)

    /** The registering [org.tenshu.core.TaskSpec] subtype's schema id (the task kind). */
    val specType: TString
        get() = getField(Fields.SPEC_TYPE)

    /** One of [States]. */
    val state: TString
        get() = getField(Fields.STATE)

    object States {
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }

    companion object {
        const val ID = "tenshu/task/backgroundTask"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.TASK_ID)
                string(Fields.SPEC_TYPE)
                string(Fields.STATE)
            }
        )

        fun create(
            context: TContext,
            taskId: String,
            specType: String,
            state: String,
        ): BackgroundTask = context.create(
            type = ID,
            fields = listOf(
                taskId.toTString(context),
                specType.toTString(context),
                state.toTString(context),
            )
        )
    }

    object Fields {
        const val TASK_ID = "$ID/taskId"
        const val SPEC_TYPE = "$ID/specType"
        const val STATE = "$ID/state"
    }
}
