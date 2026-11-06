package org.tenshu.calendar

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Every occurrence falling in a **civil date window**, earliest first, capped at [limit]
 * (`calendar-app.md` CA-8, `mia-query-layer.md` §15.1).
 *
 * ⭐⭐ **This op exists because a window is not expressible against the stored rows at all.** A
 * weekly stand-up created in 2024 has one row, dated 2024; asked what is on tomorrow, no predicate
 * over any field of that row can match — *and yet* "Team standup, 10:00" is the honest answer.
 * **The rule is stored; the occurrence is computed.** So a caller either scans every series and
 * expands them itself, or asks the service that owns the rule. This is the second.
 *
 * ⚠⚠ **The bound moved from the scan to the answer, and that is the whole point.** A caller doing
 * its own scan must cap the number of *series* it reads, and **no ordering makes that truncation
 * safe**: a series stores its **first** occurrence, so "the newest 200" drops precisely the old
 * weekly meeting that still happens tomorrow. Here the scan is unbounded on the inside and the cap
 * is on **occurrences within the window**, which are naturally few and ordered by when they
 * happen — so truncating keeps the earliest and drops the furthest away, which is what a caller
 * asking about a window actually means.
 *
 * ⚠ **A scan, like `photosNear`** — O(series in the realm) per call, in-process. That is the honest
 * trade for storing nothing, backfilling nothing and invalidating nothing.
 *
 * ⚠ Inherited from `OccurrenceResolver` and not made better or worse here: an occurrence an
 * override moves *into* the window is missed, because the expansion runs before the override
 * applies.
 *
 * @param fromDate inclusive first day, ISO local date (`2026-07-16`)
 * @param toDate inclusive last day, ISO local date; a [toDate] before [fromDate] returns nothing
 * @param limit maximum occurrences; `0` or less returns nothing rather than everything.
 *   ⚠ A caller that needs to know whether it saturated should ask for one more than it will show
 *   and compare — the list carries no "there is more" flag, and inferring saturation from
 *   `size == limit` is wrong at exactly `limit`.
 */
suspend fun CalendarService.queryOccurrences(
    fromDate: String,
    toDate: String,
    limit: Int,
): TResult<TList<ResolvedEventOccurrence>> =
    findOperation<QueryOccurrences>(QueryOccurrences.ID)
        .queryOccurrences(fromDate, toDate, limit)
        .asTaskInteractor<TList<ResolvedEventOccurrence>>()
        .execute()

interface QueryOccurrences : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun queryOccurrences(fromDate: String, toDate: String, limit: Int): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/queryOccurrences"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.FROM_DATE)
                string(id = Params.TO_DATE)
                int(id = Params.LIMIT)
            },
            returnType = listType(ResolvedEventOccurrence.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val FROM_DATE = "$ID/fromDate"
        const val TO_DATE = "$ID/toDate"
        const val LIMIT = "$ID/limit"
    }
}
