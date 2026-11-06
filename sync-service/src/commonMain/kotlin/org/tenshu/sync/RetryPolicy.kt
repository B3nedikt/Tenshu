package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * Phase 5 (docs/proposals/core-background-processing.md §3.3): the retry shape
 * of a [ScheduleItem]. Stored for observability + future `PlatformScheduler`
 * (Phase 6) consumption; the Phase 5 in-process loop does not yet act on
 * backoff (a failed dispatch simply stays `PENDING` for the next tick).
 */
interface RetryPolicy : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val maxRetries: TInteger
        get() = getField(Fields.MAX_RETRIES)

    /** [BACKOFF_LINEAR] or [BACKOFF_EXPONENTIAL]. */
    val backoff: TString
        get() = getField(Fields.BACKOFF)

    val initialBackoffMs: TInteger
        get() = getField(Fields.INITIAL_BACKOFF_MS)

    companion object {
        const val ID = "tenshu/sync/schedule/retryPolicy"

        const val BACKOFF_LINEAR = "LINEAR"
        const val BACKOFF_EXPONENTIAL = "EXPONENTIAL"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                int(Fields.MAX_RETRIES)
                string(Fields.BACKOFF)
                int(Fields.INITIAL_BACKOFF_MS)
            }
        )

        fun create(
            context: TContext,
            maxRetries: Int = 0,
            backoff: String = BACKOFF_EXPONENTIAL,
            initialBackoffMs: Long = 1000
        ): RetryPolicy = context.create(
            type = ID,
            fields = listOf(
                maxRetries.toTInteger(context),
                backoff.toTString(context),
                initialBackoffMs.toTInteger(context)
            )
        )
    }

    object Fields {
        const val MAX_RETRIES = "$ID/maxRetries"
        const val BACKOFF = "$ID/backoff"
        const val INITIAL_BACKOFF_MS = "$ID/initialBackoffMs"
    }
}
