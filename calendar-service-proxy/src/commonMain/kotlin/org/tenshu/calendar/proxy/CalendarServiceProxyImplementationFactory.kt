package org.tenshu.calendar.proxy

import org.tenshu.calendar.DeleteCalendar
import org.tenshu.calendar.DeleteReminder
import org.tenshu.calendar.DeleteEvent
import org.tenshu.calendar.DeleteOccurrence
import org.tenshu.calendar.SaveCalendar
import org.tenshu.calendar.SaveEvent
import org.tenshu.calendar.SaveEventOverride
import org.tenshu.calendar.SaveEventSource
import org.tenshu.calendar.ApplyExternalEvents
import org.tenshu.calendar.DeleteCalendarMirror
import org.tenshu.calendar.SaveCalendarMirror
import org.tenshu.calendar.SaveCalendarMirrorStamp
import org.tenshu.calendar.DeleteSubscription
import org.tenshu.calendar.RefreshSubscriptions
import org.tenshu.calendar.SaveSubscription
import org.tenshu.calendar.SaveReminder
import org.tenshu.calendar.SetReminderState
import org.tenshu.calendar.proxy.impl.DeleteCalendarImplementation
import org.tenshu.calendar.proxy.impl.DeleteReminderImplementation
import org.tenshu.calendar.proxy.impl.DeleteEventImplementation
import org.tenshu.calendar.proxy.impl.DeleteOccurrenceImplementation
import org.tenshu.calendar.proxy.impl.SaveCalendarImplementation
import org.tenshu.calendar.proxy.impl.SaveEventImplementation
import org.tenshu.calendar.proxy.impl.SaveEventOverrideImplementation
import org.tenshu.calendar.proxy.impl.SaveEventSourceImplementation
import org.tenshu.calendar.proxy.impl.ApplyExternalEventsImplementation
import org.tenshu.calendar.proxy.impl.DeleteCalendarMirrorImplementation
import org.tenshu.calendar.proxy.impl.SaveCalendarMirrorImplementation
import org.tenshu.calendar.proxy.impl.SaveCalendarMirrorStampImplementation
import org.tenshu.calendar.proxy.impl.DeleteSubscriptionImplementation
import org.tenshu.calendar.proxy.impl.RefreshSubscriptionsImplementation
import org.tenshu.calendar.proxy.impl.SaveReminderImplementation
import org.tenshu.calendar.proxy.impl.SaveSubscriptionImplementation
import org.tenshu.calendar.proxy.impl.SubscriptionRefresher
import org.tenshu.calendar.proxy.impl.SetReminderStateImplementation
import org.tenshu.calendar.DeliverDueReminders
import org.tenshu.calendar.QueryOccurrences
import org.tenshu.calendar.proxy.impl.QueryOccurrencesImplementation
import org.tenshu.calendar.proxy.impl.DeliverDueRemindersImplementation
import app.tenshu.notify.NoopNotificationPresenter
import app.tenshu.notify.NotificationPresenter
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.sync.common.TombstoneEmitter

/**
 * @param presenter where a due reminder is shown. ⚠ A constructor parameter with
 *   a no-op default, the `PlatformScheduler` shape — **not** a `TContext`
 *   component lookup, which `docs/agent.md` §"Job B" rules against growing and
 *   which this factory has no need of, since its call site already has Koin
 *   reach. A platform with no surface passes nothing and the op still runs,
 *   arming the next wake and posting nothing.
 * @param zoneId the zone a *floating* event is read in (`ReminderPlanner`).
 * @param tombstoneEmitter what makes a Calendar delete reach every device. ⚠⚠ `null` makes every
 *   delete device-local: `SyncObserver.onDataDeleted` is a deliberate no-op, so a delete that emits
 *   no tombstone never leaves the device, and the next write of the row from anywhere else brings it
 *   back. Every Calendar delete goes through `deleteRow`, which emits it. Acceptable in a sync-less
 *   test proxy; never in production.
 */
fun calendarServiceProxyImplementationFactory(
    id: String,
    presenter: NotificationPresenter = NoopNotificationPresenter,
    /**
     * How a subscription reaches its feed (CA-7).
     *
     * ⚠ No-op by default, so the ~40 tests that predate subscriptions need no
     * argument and a host with no HTTP stack reports a *failure* rather than
     * pretending a feed refreshed.
     */
    fetcher: IcsSubscriptionFetcher = NoopIcsSubscriptionFetcher,
    nowEpochMs: () -> Long = { 0L },
    zoneId: () -> String = { "UTC" },
    tombstoneEmitter: TombstoneEmitter? = null,
): OperationImplementation? = when (id) {
    // CA-8: the window read. ⚠ Takes no constructor argument — it is a pure fold over the
    // store, with no clock and no presenter (`mia-query-layer.md` §15.1).
    QueryOccurrences.ID -> QueryOccurrencesImplementation()
    SaveCalendar.ID -> SaveCalendarImplementation()
    DeleteCalendar.ID -> DeleteCalendarImplementation(tombstoneEmitter)
    SaveEvent.ID -> SaveEventImplementation()
    DeleteEvent.ID -> DeleteEventImplementation(tombstoneEmitter)
    DeleteOccurrence.ID -> DeleteOccurrenceImplementation(tombstoneEmitter)
    SaveEventOverride.ID -> SaveEventOverrideImplementation()
    SaveEventSource.ID -> SaveEventSourceImplementation()
    // CX-0: the batched write a calendar copied off the phone arrives through.
    ApplyExternalEvents.ID -> ApplyExternalEventsImplementation(tombstoneEmitter)
    // CX-1b: the *Keep in sync* binding row.
    SaveCalendarMirror.ID -> SaveCalendarMirrorImplementation()
    SaveCalendarMirrorStamp.ID -> SaveCalendarMirrorStampImplementation()
    DeleteCalendarMirror.ID -> DeleteCalendarMirrorImplementation(tombstoneEmitter)
    SaveSubscription.ID -> SaveSubscriptionImplementation()
    DeleteSubscription.ID -> DeleteSubscriptionImplementation(tombstoneEmitter)
    RefreshSubscriptions.ID ->
        RefreshSubscriptionsImplementation(SubscriptionRefresher(fetcher, nowEpochMs, tombstoneEmitter))
    SaveReminder.ID -> SaveReminderImplementation()
    DeleteReminder.ID -> DeleteReminderImplementation(tombstoneEmitter)
    SetReminderState.ID -> SetReminderStateImplementation()
    DeliverDueReminders.ID ->
        DeliverDueRemindersImplementation(presenter, nowEpochMs, zoneId)
    else -> null
}
