package org.tenshu.calendar.proxy.impl

import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.calendar.CalendarSubscription
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.SaveEvent
import org.tenshu.calendar.SaveEventOverride
import org.tenshu.calendar.SaveEventSource
import org.tenshu.calendar.SaveSubscription
import org.tenshu.calendar.common.ics.CalendarEventIds
import org.tenshu.calendar.common.ics.IcsImport
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.calendar.proxy.IcsFetchOutcome
import org.tenshu.calendar.proxy.IcsSubscriptionFetcher
import org.tenshu.calendar.proxy.normalizeIcsUrl
import org.tenshu.core.Data
import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.entity.DataId
import org.tenshu.core.get
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/**
 * Brings each subscribed calendar back into step with its feed (CA-7).
 *
 * ⚠⚠ **A refresh is a *sync*, not an import, and that is the decision this class
 * exists to make.** CA-6's import is additive — every `saveEvent` is an upsert, so
 * importing the same file twice converges but importing a *shorter* file leaves
 * the extra events behind. A feed is authoritative: a fixture that was cancelled
 * upstream has to disappear here, so anything in the calendar that the fetch did
 * not name is **deleted**.
 *
 * ⚠ That is only safe because a subscribed calendar is read-only — every event in
 * it came from the feed and none of them is the user's. The same sweep over an
 * ordinary calendar would destroy their work, which is why `importIcs` does not
 * do it and this does.
 */
internal class SubscriptionRefresher(
    private val fetcher: IcsSubscriptionFetcher,
    private val nowEpochMs: () -> Long,
    /** ⚠ The sweep's deletes reach every device only with it — see [deleteRow]. */
    private val tombstoneEmitter: TombstoneEmitter? = null,
) {

    /** @return how many feeds were fetched and applied. */
    suspend fun refreshAll(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
    ): Int {
        var applied = 0
        // ⚠ Snapshotted before the first fetch: the loop writes rows of the same
        // type it is iterating, and re-reading mid-loop would be a live collection
        // under mutation.
        val subscriptions = executionContext.allSubscriptions()
        for (subscription in subscriptions) {
            if (!subscription.enabled.get()) continue
            val url = normalizeIcsUrl(subscription.url.get()) ?: continue
            // ⚠ One feed's failure must not stop the others — a publisher being
            // down is the ordinary case, not an error state for the whole app.
            val outcome = runCatchingCancellable { fetcher.fetch(url, subscription.etag.get()) }
                .getOrElse { IcsFetchOutcome.Failed(it.message.orEmpty()) }

            when (outcome) {
                is IcsFetchOutcome.Fetched -> {
                    apply(executionContext, implementationFactory, subscription, outcome.text)
                    stamp(
                        executionContext,
                        implementationFactory,
                        subscription,
                        etag = outcome.etag,
                    )
                    applied++
                }
                // ⚠⚠ Unchanged ⇒ **touch nothing but the timestamp**. Applying an
                // empty calendar here would delete every event the feed holds.
                IcsFetchOutcome.NotModified -> {
                    stamp(
                        executionContext,
                        implementationFactory,
                        subscription,
                        etag = subscription.etag.get(),
                    )
                }
                // ⚠ A failure leaves `lastFetchedEpochMs` alone, so the UI can say
                // how stale the calendar actually is rather than claiming it just
                // refreshed.
                is IcsFetchOutcome.Failed -> Unit
            }
        }
        return applied
    }

    private fun apply(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        subscription: CalendarSubscription,
        text: String,
    ) {
        val calendarId = subscription.calendarId.get()
        val parsed = IcsImport.read(text)
        val (series, exceptions) = parsed.events.partition { it.recurrenceId == null }

        // ⚠⚠ Namespaced by this calendar (`CalendarEventIds`), so two feeds that share a `UID`
        // each keep their own row instead of taking turns owning one. Snapshotted before the
        // writes, so a series and its overrides resolve to the same id.
        val inCalendar = executionContext.allEvents()
            .filter { it.calendarId.get() == calendarId }
            .mapTo(mutableSetOf()) { it.eventId.get() }
        val idOf = { event: ImportedEvent -> CalendarEventIds.inCalendar(calendarId, idFor(event), inCalendar) }

        val seen = mutableSetOf<String>()
        series.forEach { event ->
            val eventId = idOf(event)
            seen += eventId
            write(executionContext, implementationFactory, eventId, calendarId, event)
        }

        // ⚠⚠ The sweep. Anything in this calendar the feed no longer names is gone
        // — see the class KDoc for why that is safe here and nowhere else.
        executionContext.allEvents()
            .filter { it.calendarId.get() == calendarId && it.eventId.get() !in seen }
            .forEach { deleteEventCascade(executionContext, it.eventId.get(), tombstoneEmitter) }

        // ⚠ Overrides last, and *after* the sweep: an override whose series was
        // just deleted has nothing to modify, and one whose series survived must
        // not be swept along with the removals.
        exceptions.forEach { event ->
            writeOverride(executionContext, implementationFactory, idOf(event), event)
        }
    }

    /**
     * The event's `UID`, or a content hash when it has none — the same key CA-6's import uses.
     *
     * ⚠ Namespaced per calendar before it is stored ([apply]). CA-7a once kept it bare so a
     * calendar first *imported* and later *subscribed* to "converged" — but a subscription always
     * gets a calendar of its own, so that was a *move* out of the user's calendar into one that
     * unsubscribing deletes (`calendar-external-sources.md` §7).
     */
    private fun idFor(event: ImportedEvent): String =
        event.uid.trim().ifBlank {
            "ics-" + (event.title + event.startLocal + event.recurrenceRule)
                .hashCode().toUInt().toString(16)
        }

    private fun write(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        eventId: String,
        calendarId: String,
        event: ImportedEvent,
    ) {
        val implementation =
            implementationFactory(SaveEvent.ID) as? SyncOperationImplementation ?: return
        val context = executionContext.context
        implementation.execute<Data?>(
            executionContext = executionContext,
            operationContext = null,
            implementationFactory = implementationFactory,
            caller = null,
            params = buildMap {
                put(SaveEvent.Params.EVENT_ID, eventId.toTString(context))
                put(SaveEvent.Params.TITLE, event.title.toTString(context))
                put(SaveEvent.Params.START_LOCAL, event.startLocal.toTString(context))
                put(SaveEvent.Params.CALENDAR_ID, calendarId.toTString(context))
                put(SaveEvent.Params.ALL_DAY, event.allDay.toTBoolean(context))
                if (event.endLocal.isNotBlank()) {
                    put(SaveEvent.Params.END_LOCAL, event.endLocal.toTString(context))
                }
                if (event.timeZoneId.isNotBlank()) {
                    put(SaveEvent.Params.TIME_ZONE_ID, event.timeZoneId.toTString(context))
                }
                put(SaveEvent.Params.LOCATION, event.location.toTString(context))
                put(SaveEvent.Params.NOTES, event.notes.toTString(context))
                put(SaveEvent.Params.RECURRENCE_RULE, event.recurrenceRule.toTString(context))
                put(SaveEvent.Params.EXCEPTION_DATES, event.exceptionDates.toTString(context))
            },
        )
        // ⚠ The sidecar rides along, so exporting a subscribed calendar gives back
        // whatever the publisher wrote — CA-6a's D1 promise does not stop at the
        // import button.
        (implementationFactory(SaveEventSource.ID) as? SyncOperationImplementation)?.execute<Data?>(
            executionContext = executionContext,
            operationContext = null,
            implementationFactory = implementationFactory,
            caller = null,
            params = mapOf(
                SaveEventSource.Params.EVENT_ID to eventId.toTString(context),
                SaveEventSource.Params.RAW to event.raw.toTString(context),
                SaveEventSource.Params.ORIGIN to "subscription".toTString(context),
            ),
        )
    }

    private fun writeOverride(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        eventId: String,
        event: ImportedEvent,
    ) {
        val implementation = implementationFactory(SaveEventOverride.ID)
            as? SyncOperationImplementation ?: return
        val context = executionContext.context
        implementation.execute<Data?>(
            executionContext = executionContext,
            operationContext = null,
            implementationFactory = implementationFactory,
            caller = null,
            params = buildMap {
                put(
                    SaveEventOverride.Params.EVENT_ID,
                    eventId.toTString(context),
                )
                put(
                    SaveEventOverride.Params.RECURRENCE_ID,
                    event.recurrenceId.orEmpty().toTString(context),
                )
                put(
                    SaveEventOverride.Params.START_LOCAL,
                    event.startLocal.toTString(context),
                )
                put(
                    SaveEventOverride.Params.TITLE,
                    event.title.toTString(context),
                )
                if (event.endLocal.isNotBlank()) {
                    put(
                        SaveEventOverride.Params.END_LOCAL,
                        event.endLocal.toTString(context),
                    )
                }
            },
        )
    }

    private fun stamp(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        subscription: CalendarSubscription,
        etag: String,
    ) {
        val implementation =
            implementationFactory(SaveSubscription.ID) as? SyncOperationImplementation ?: return
        val context = executionContext.context
        implementation.execute<Data?>(
            executionContext = executionContext,
            operationContext = null,
            implementationFactory = implementationFactory,
            caller = null,
            params = mapOf(
                SaveSubscription.Params.SUBSCRIPTION_ID to
                    subscription.subscriptionId.get().toTString(context),
                SaveSubscription.Params.CALENDAR_ID to
                    subscription.calendarId.get().toTString(context),
                SaveSubscription.Params.URL to subscription.url.get().toTString(context),
                SaveSubscription.Params.ENABLED to subscription.enabled.get().toTBoolean(context),
                SaveSubscription.Params.LAST_FETCHED_EPOCH_MS to
                    nowEpochMs().toTInteger(context),
                SaveSubscription.Params.ETAG to etag.toTString(context),
            ),
        )
    }
}

internal fun ExecutionContext.allSubscriptions(): List<CalendarSubscription> {
    val arrayRef = resolveServiceData(
        service = ServiceTypeReferenceDto(CalendarService.ID),
        ref = EntityListReferenceDto(CalendarService.Relations.SUBSCRIPTIONS),
    )
    return resolveList(arrayRef)?.filterIsInstance<CalendarSubscription>()?.toList().orEmpty()
}

/** The subscription that owns [calendarId], if any — the read-only test. */
internal fun ExecutionContext.subscriptionFor(calendarId: String): CalendarSubscription? =
    allSubscriptions().firstOrNull { it.calendarId.get() == calendarId }

internal fun ExecutionContext.deleteSubscriptionRow(subscriptionId: String, tombstoneEmitter: TombstoneEmitter?) {
    deleteRow(CalendarSubscription.ID, subscriptionId, tombstoneEmitter)
}
