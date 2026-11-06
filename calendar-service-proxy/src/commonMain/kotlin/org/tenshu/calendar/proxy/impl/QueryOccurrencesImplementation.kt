package org.tenshu.calendar.proxy.impl

import kotlinx.datetime.LocalDate
import org.tenshu.calendar.QueryOccurrences
import org.tenshu.calendar.ResolvedEventOccurrence
import org.tenshu.calendar.common.recurrence.OccurrenceResolver
import org.tenshu.calendar.common.recurrence.OverrideInput
import org.tenshu.calendar.common.recurrence.SeriesInput
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.get
import org.tenshu.core.number.TInteger

/**
 * [QueryOccurrences] — scan the series, expand them across the window, apply the overrides, cap.
 *
 * ⭐ The proxy-side half: everything that touches storage here, every *decision* in the pure
 * `OccurrenceResolver` one layer down — the same division `planReminders` already uses, and the
 * reason an agenda in Mia, an agenda in Calendar and a reminder cannot disagree about what recurs.
 *
 * ⚠⚠ **The scan is deliberately unbounded and the *answer* is bounded.** That inversion is the
 * whole reason this op exists: a caller capping the series it reads has no safe order to cap by
 * (a series stores its *first* occurrence), while occurrences inside a window are naturally few
 * and ordered by when they happen.
 */
internal class QueryOccurrencesImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val limit = params?.get(QueryOccurrences.Params.LIMIT)?.let { (it as TInteger).get() } ?: 0L
        if (limit <= 0L) return SimpleTList(emptyList<Data>()) as T

        val from = params?.dateParam(QueryOccurrences.Params.FROM_DATE)
        val to = params?.dateParam(QueryOccurrences.Params.TO_DATE)
        // ⚠ An unparseable or inverted window returns nothing rather than everything: the failure
        // of a caller's date arithmetic must not turn into a full-calendar dump.
        if (from == null || to == null || to < from) return SimpleTList(emptyList<Data>()) as T

        val occurrences = OccurrenceResolver.resolve(
            series = executionContext.allEvents().map { event ->
                SeriesInput(
                    eventId = event.eventId.get(),
                    calendarId = event.calendarId.get(),
                    title = event.title.get(),
                    location = event.location.get(),
                    notes = event.notes.get(),
                    startLocal = event.startLocal.get(),
                    endLocal = event.endLocal.get(),
                    timeZoneId = event.timeZoneId.get(),
                    allDay = event.allDay.get(),
                    recurrenceRule = event.recurrenceRule.get(),
                    exceptionDates = event.exceptionDates.get(),
                )
            },
            overrides = executionContext.allEventOverrides().map { override ->
                OverrideInput(
                    eventId = override.eventId.get(),
                    recurrenceId = override.recurrenceId.get(),
                    // ⚠ `?.get()` preserves absence: coercing to "" here would blank every field
                    // the override did not mention.
                    startLocal = override.startLocal?.get(),
                    endLocal = override.endLocal?.get(),
                    title = override.title?.get(),
                    location = override.location?.get(),
                    notes = override.notes?.get(),
                )
            },
            windowStart = from,
            windowEnd = to,
        )

        // ⚠ The resolver already sorts by *effective* start, so taking the first [limit] keeps the
        // soonest and drops the furthest away — the only truncation a window question makes sense
        // under, and the one the series scan could never offer.
        val rows = occurrences.take(limit.toInt()).map { occurrence ->
            ResolvedEventOccurrence.create(
                context = executionContext.context,
                eventId = occurrence.eventId,
                calendarId = occurrence.calendarId,
                recurrenceId = occurrence.recurrenceId,
                title = occurrence.title,
                location = occurrence.location,
                notes = occurrence.notes,
                startLocal = occurrence.startLocal.toString(),
                endLocal = occurrence.endLocal.toString(),
                zoneId = occurrence.zoneId,
                allDay = occurrence.allDay,
                isOverridden = occurrence.isOverridden,
                recurrenceRule = occurrence.recurrenceRule,
            )
        }

        return SimpleTList(rows) as T
    }

    private fun Map<String, Data>.dateParam(id: String): LocalDate? =
        (get(id) as? TString)?.get()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
