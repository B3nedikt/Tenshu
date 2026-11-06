package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * Phase 5 (docs/proposals/core-background-processing.md §3.2(b)): a persisted,
 * queryable scheduled invocation — the unit the `ScheduleService` owns and the
 * in-process drain loop drains. The persisted set *is* the §6.3
 * `BackgroundJobRegistry`.
 *
 * Keyed by [dedupKey] via [DataIdProvider] (mirrors `SyncBlock`'s
 * `blockId` keying): re-scheduling the same key replaces the pending item
 * (§3.3 dedup) and cancel-by-key is a single `removeFromArray`.
 *
 * **Scope note (this slice):** [opId] is the operation reference; the
 * scheduled invocation is **param-less** in Phase 5 — that matches the
 * canonical "sync drain" / periodic example and the Phase 5 acceptance tests.
 * §3.2(b)'s "serialized args" is deferred until a consumer needs it
 * (deferred-upload-with-args also needs `PlatformScheduler`, Phase 6/7).
 */
interface ScheduleItem : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Stable identity + platform-scheduler dedup key (§3.3). */
    val dedupKey: TString
        get() = getField(Fields.DEDUP_KEY)

    /** The operation reference to invoke head-less. Must be `isHeadlessSafe()`. */
    val opId: TString
        get() = getField(Fields.OP_ID)

    val scheduleSpec: ScheduleSpec
        get() = getField(Fields.SCHEDULE_SPEC)

    val constraintSpec: ConstraintSpec
        get() = getField(Fields.CONSTRAINT_SPEC)

    val retryPolicy: RetryPolicy
        get() = getField(Fields.RETRY_POLICY)

    /** [STATE_PENDING] / [STATE_RUNNING] / [STATE_DONE] / [STATE_FAILED]. */
    val state: TString
        get() = getField(Fields.STATE)

    /** Dispatch attempts so far (observability; retry/backoff is Phase 6+). */
    val attempts: TInteger
        get() = getField(Fields.ATTEMPTS)

    companion object {
        const val ID = "tenshu/sync/schedule/scheduleItem"

        const val STATE_PENDING = "PENDING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_DONE = "DONE"
        const val STATE_FAILED = "FAILED"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.DEDUP_KEY)
                string(Fields.OP_ID)
                composite(Fields.SCHEDULE_SPEC, type = ScheduleSpec.Schema)
                composite(Fields.CONSTRAINT_SPEC, type = ConstraintSpec.Schema)
                composite(Fields.RETRY_POLICY, type = RetryPolicy.Schema)
                string(Fields.STATE)
                int(Fields.ATTEMPTS)
            }
        )

        fun create(
            context: TContext,
            dedupKey: String,
            opId: String,
            scheduleSpec: ScheduleSpec,
            constraintSpec: ConstraintSpec,
            retryPolicy: RetryPolicy,
            state: String = STATE_PENDING,
            attempts: Int = 0
        ): ScheduleItem = context.create(
            type = ID,
            fields = listOf(
                dedupKey.toTString(context),
                opId.toTString(context),
                scheduleSpec,
                constraintSpec,
                retryPolicy,
                state.toTString(context),
                attempts.toTInteger(context)
            )
        )
    }

    object Fields {
        const val DEDUP_KEY = "$ID/dedupKey"
        const val OP_ID = "$ID/opId"
        const val SCHEDULE_SPEC = "$ID/scheduleSpec"
        const val CONSTRAINT_SPEC = "$ID/constraintSpec"
        const val RETRY_POLICY = "$ID/retryPolicy"
        const val STATE = "$ID/state"
        const val ATTEMPTS = "$ID/attempts"
    }
}
