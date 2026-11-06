package org.tenshu.calendar.client.impl

import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.CalendarMirror
import org.tenshu.calendar.CalendarMirrorStamp
import org.tenshu.calendar.CalendarSubscription
import org.tenshu.calendar.EventSource
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.ReminderState
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain

internal class CalendarServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : CalendarService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val calendars: TList<Calendar>
        get() = serviceRelation(CalendarService.Relations.CALENDARS, Calendar.Schema)

    override val events: TList<Event>
        get() = serviceRelation(CalendarService.Relations.EVENTS, Event.Schema)

    override val eventOverrides: TList<EventOverride>
        get() = serviceRelation(CalendarService.Relations.EVENT_OVERRIDES, EventOverride.Schema)

    override val eventSources: TList<EventSource>
        get() = serviceRelation(CalendarService.Relations.EVENT_SOURCES, EventSource.Schema)

    override val subscriptions: TList<CalendarSubscription>
        get() = serviceRelation(
            CalendarService.Relations.SUBSCRIPTIONS,
            CalendarSubscription.Schema,
        )

    override val mirrors: TList<CalendarMirror>
        get() = serviceRelation(CalendarService.Relations.MIRRORS, CalendarMirror.Schema)

    override val mirrorStamps: TList<CalendarMirrorStamp>
        get() = serviceRelation(CalendarService.Relations.MIRROR_STAMPS, CalendarMirrorStamp.Schema)

    override val reminders: TList<Reminder>
        get() = serviceRelation(CalendarService.Relations.REMINDERS, Reminder.Schema)

    override val reminderStates: TList<ReminderState>
        get() = serviceRelation(CalendarService.Relations.REMINDER_STATES, ReminderState.Schema)
}
