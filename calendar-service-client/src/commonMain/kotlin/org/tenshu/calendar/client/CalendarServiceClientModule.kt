package org.tenshu.calendar.client

import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.DeleteCalendar
import org.tenshu.calendar.DeliverDueReminders
import org.tenshu.calendar.QueryOccurrences
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
import org.tenshu.calendar.SaveSubscription
import org.tenshu.calendar.DeleteSubscription
import org.tenshu.calendar.RefreshSubscriptions
import org.tenshu.calendar.SaveReminder
import org.tenshu.calendar.SetReminderState
import org.tenshu.calendar.client.impl.CalendarServiceRo
import org.tenshu.calendar.client.impl.DeleteCalendarRo
import org.tenshu.calendar.client.impl.DeliverDueRemindersRo
import org.tenshu.calendar.client.impl.QueryOccurrencesRo
import org.tenshu.calendar.client.impl.DeleteReminderRo
import org.tenshu.calendar.client.impl.DeleteEventRo
import org.tenshu.calendar.client.impl.DeleteOccurrenceRo
import org.tenshu.calendar.client.impl.SaveCalendarRo
import org.tenshu.calendar.client.impl.SaveEventOverrideRo
import org.tenshu.calendar.client.impl.SaveEventSourceRo
import org.tenshu.calendar.client.impl.ApplyExternalEventsRo
import org.tenshu.calendar.client.impl.DeleteCalendarMirrorRo
import org.tenshu.calendar.client.impl.SaveCalendarMirrorRo
import org.tenshu.calendar.client.impl.SaveCalendarMirrorStampRo
import org.tenshu.calendar.client.impl.SaveSubscriptionRo
import org.tenshu.calendar.client.impl.DeleteSubscriptionRo
import org.tenshu.calendar.client.impl.RefreshSubscriptionsRo
import org.tenshu.calendar.client.impl.SaveReminderRo
import org.tenshu.calendar.client.impl.SetReminderStateRo
import org.tenshu.calendar.client.impl.SaveEventRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule

/**
 * Client-side registration for the calendar operation surface (CA-1).
 *
 * ⚠⚠ Every op needs its RO registered here, not just the schema in
 * `:calendar-service`. The dynamic-property dispatch goes through the *client*
 * module's `roFactory`, and a missing registration does not fail at startup —
 * it surfaces later as an opaque `error/internal` on whichever call happens to
 * need it first (the `GetWorkoutStreamsRo` lesson,
 * `fit-workout-streams-content-plane.md`).
 */
val calendarServiceClientModule = tModule {

    operation(
        interfaceClass = SaveCalendar::class,
        protocolSchema = SaveCalendar.Schema,
        roFactory = ::SaveCalendarRo,
    )

    operation(
        interfaceClass = DeleteCalendar::class,
        protocolSchema = DeleteCalendar.Schema,
        roFactory = ::DeleteCalendarRo,
    )

    operation(
        interfaceClass = SaveEvent::class,
        protocolSchema = SaveEvent.Schema,
        roFactory = ::SaveEventRo,
    )

    operation(
        interfaceClass = DeleteEvent::class,
        protocolSchema = DeleteEvent.Schema,
        roFactory = ::DeleteEventRo,
    )

    operation(
        interfaceClass = DeleteOccurrence::class,
        protocolSchema = DeleteOccurrence.Schema,
        roFactory = ::DeleteOccurrenceRo,
    )

    operation(
        interfaceClass = SaveEventOverride::class,
        protocolSchema = SaveEventOverride.Schema,
        roFactory = ::SaveEventOverrideRo,
    )

    service(
        interfaceClass = CalendarService::class,
        serviceSchema = CalendarService.Schema,
        roFactory = ::CalendarServiceRo,
    )

    operation(
        interfaceClass = SaveSubscription::class,
        protocolSchema = SaveSubscription.Schema,
        roFactory = ::SaveSubscriptionRo,
    )

    operation(
        interfaceClass = DeleteSubscription::class,
        protocolSchema = DeleteSubscription.Schema,
        roFactory = ::DeleteSubscriptionRo,
    )

    operation(
        interfaceClass = RefreshSubscriptions::class,
        protocolSchema = RefreshSubscriptions.Schema,
        roFactory = ::RefreshSubscriptionsRo,
    )

    operation(
        interfaceClass = SaveReminder::class,
        protocolSchema = SaveReminder.Schema,
        roFactory = ::SaveReminderRo,
    )
    operation(
        interfaceClass = SaveEventSource::class,
        protocolSchema = SaveEventSource.Schema,
        roFactory = ::SaveEventSourceRo,
    )
    operation(
        interfaceClass = ApplyExternalEvents::class,
        protocolSchema = ApplyExternalEvents.Schema,
        roFactory = ::ApplyExternalEventsRo,
    )
    operation(
        interfaceClass = SaveCalendarMirror::class,
        protocolSchema = SaveCalendarMirror.Schema,
        roFactory = ::SaveCalendarMirrorRo,
    )
    operation(
        interfaceClass = SaveCalendarMirrorStamp::class,
        protocolSchema = SaveCalendarMirrorStamp.Schema,
        roFactory = ::SaveCalendarMirrorStampRo,
    )
    operation(
        interfaceClass = DeleteCalendarMirror::class,
        protocolSchema = DeleteCalendarMirror.Schema,
        roFactory = ::DeleteCalendarMirrorRo,
    )

    operation(
        interfaceClass = DeleteReminder::class,
        protocolSchema = DeleteReminder.Schema,
        roFactory = ::DeleteReminderRo,
    )

    operation(
        interfaceClass = SetReminderState::class,
        protocolSchema = SetReminderState.Schema,
        roFactory = ::SetReminderStateRo,
    )

    operation(
        interfaceClass = DeliverDueReminders::class,
        protocolSchema = DeliverDueReminders.Schema,
        roFactory = ::DeliverDueRemindersRo,
    )

    // CA-8: the window read (`mia-query-layer.md` §15.1).
    operation(
        interfaceClass = QueryOccurrences::class,
        protocolSchema = QueryOccurrences.Schema,
        roFactory = ::QueryOccurrencesRo,
    )
}
