package org.tenshu.calendar.common

import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarDataIdProvider
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.ResolvedEventOccurrence
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventDataIdProvider
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.EventOverrideDataIdProvider
import org.tenshu.calendar.EventSource
import org.tenshu.calendar.EventSourceDataIdProvider
import org.tenshu.calendar.CalendarMirror
import org.tenshu.calendar.CalendarMirrorDataIdProvider
import org.tenshu.calendar.CalendarMirrorStamp
import org.tenshu.calendar.CalendarMirrorStampDataIdProvider
import org.tenshu.calendar.CalendarSubscription
import org.tenshu.calendar.CalendarSubscriptionDataIdProvider
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.ReminderDataIdProvider
import org.tenshu.calendar.ReminderState
import org.tenshu.calendar.ReminderStateDataIdProvider
import org.tenshu.calendar.common.impl.CalendarDataIdProviderImpl
import org.tenshu.calendar.common.impl.CalendarDto
import org.tenshu.calendar.common.impl.CalendarRo
import org.tenshu.calendar.common.impl.EventDataIdProviderImpl
import org.tenshu.calendar.common.impl.EventDto
import org.tenshu.calendar.common.impl.EventOverrideDataIdProviderImpl
import org.tenshu.calendar.common.impl.EventOverrideDto
import org.tenshu.calendar.common.impl.EventOverrideRo
import org.tenshu.calendar.common.impl.EventRo
import org.tenshu.calendar.common.impl.EventSourceDataIdProviderImpl
import org.tenshu.calendar.common.impl.EventSourceDto
import org.tenshu.calendar.common.impl.EventSourceRo
import org.tenshu.calendar.common.impl.CalendarMirrorDataIdProviderImpl
import org.tenshu.calendar.common.impl.CalendarMirrorDto
import org.tenshu.calendar.common.impl.CalendarMirrorRo
import org.tenshu.calendar.common.impl.CalendarMirrorStampDataIdProviderImpl
import org.tenshu.calendar.common.impl.CalendarMirrorStampDto
import org.tenshu.calendar.common.impl.CalendarMirrorStampRo
import org.tenshu.calendar.common.impl.CalendarSubscriptionDataIdProviderImpl
import org.tenshu.calendar.common.impl.CalendarSubscriptionDto
import org.tenshu.calendar.common.impl.ReminderDataIdProviderImpl
import org.tenshu.calendar.common.impl.ReminderDto
import org.tenshu.calendar.common.impl.CalendarSubscriptionRo
import org.tenshu.calendar.common.impl.ReminderRo
import org.tenshu.calendar.common.impl.ReminderStateDataIdProviderImpl
import org.tenshu.calendar.common.impl.ReminderStateDto
import org.tenshu.calendar.common.impl.ReminderStateRo
import org.tenshu.calendar.common.impl.ResolvedEventOccurrenceDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema

/**
 * Registers the calendar schema graph (`calendar-app.md`, phase CA-0).
 *
 * ⚠ Every composite registers **both** a `dtoFactory` and a `roFactory`. The
 * RO half is easy to forget because nothing fails until a chain projects the
 * type, at which point the read dies as an opaque `error/internal` rather than
 * anything naming the missing registration — the `GetWorkoutStreamsRo` lesson
 * from `fit-workout-streams-content-plane.md`.
 */
val calendarServiceCommonModule = tModule {

    composite(
        interfaceClass = Calendar::class,
        dataSchema = Calendar.Schema,
        dtoFactory = ::CalendarDto,
        roFactory = { chain, ctx ->
            CalendarRo(requestChain = chain, context = ctx, schema = Calendar.Schema)
        }
    )

    composite(
        interfaceClass = Event::class,
        dataSchema = Event.Schema,
        dtoFactory = ::EventDto,
        roFactory = { chain, ctx ->
            EventRo(requestChain = chain, context = ctx, schema = Event.Schema)
        }
    )

    composite(
        interfaceClass = EventOverride::class,
        dataSchema = EventOverride.Schema,
        dtoFactory = ::EventOverrideDto,
        roFactory = { chain, ctx ->
            EventOverrideRo(requestChain = chain, context = ctx, schema = EventOverride.Schema)
        }
    )

    composite(
        interfaceClass = EventSource::class,
        dataSchema = EventSource.Schema,
        dtoFactory = ::EventSourceDto,
        roFactory = { chain, ctx ->
            EventSourceRo(requestChain = chain, context = ctx, schema = EventSource.Schema)
        }
    )

    composite(
        interfaceClass = Reminder::class,
        dataSchema = Reminder.Schema,
        dtoFactory = ::ReminderDto,
        roFactory = { chain, ctx ->
            ReminderRo(requestChain = chain, context = ctx, schema = Reminder.Schema)
        }
    )

    composite(
        interfaceClass = CalendarSubscription::class,
        dataSchema = CalendarSubscription.Schema,
        dtoFactory = ::CalendarSubscriptionDto,
        roFactory = { chain, ctx ->
            CalendarSubscriptionRo(
                requestChain = chain,
                context = ctx,
                schema = CalendarSubscription.Schema,
            )
        }
    )

    composite(
        interfaceClass = CalendarMirror::class,
        dataSchema = CalendarMirror.Schema,
        dtoFactory = ::CalendarMirrorDto,
        roFactory = { chain, ctx ->
            CalendarMirrorRo(requestChain = chain, context = ctx, schema = CalendarMirror.Schema)
        }
    )

    // P2-a (v4): the mirror's freshness, per device, off the mirror row.
    composite(
        interfaceClass = CalendarMirrorStamp::class,
        dataSchema = CalendarMirrorStamp.Schema,
        dtoFactory = ::CalendarMirrorStampDto,
        roFactory = { chain, ctx ->
            CalendarMirrorStampRo(requestChain = chain, context = ctx, schema = CalendarMirrorStamp.Schema)
        }
    )

    composite(
        interfaceClass = ReminderState::class,
        dataSchema = ReminderState.Schema,
        dtoFactory = ::ReminderStateDto,
        roFactory = { chain, ctx ->
            ReminderStateRo(requestChain = chain, context = ctx, schema = ReminderState.Schema)
        }
    )

    // CA-8's window read returns this. ⚠ **DTO only, no `Ro`** — the `CapturedPhoto` precedent:
    // it is never stored and never projected, it only crosses the wire whole. It carries no
    // `DataIdProvider` and no `Syncable` trait, so nothing can persist one by accident.
    composite(
        interfaceClass = ResolvedEventOccurrence::class,
        dataSchema = ResolvedEventOccurrence.Schema,
        dtoFactory = ::ResolvedEventOccurrenceDto,
    )

    service(CalendarService.Schema)

    trait(
        dataSchema = Calendar.Schema,
        traitSchema = CalendarDataIdProvider.Schema,
        traitFactory = { context, schema ->
            CalendarDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = Event.Schema,
        traitSchema = EventDataIdProvider.Schema,
        traitFactory = { context, schema ->
            EventDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = EventOverride.Schema,
        traitSchema = EventOverrideDataIdProvider.Schema,
        traitFactory = { context, schema ->
            EventOverrideDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = EventSource.Schema,
        traitSchema = EventSourceDataIdProvider.Schema,
        traitFactory = { context, schema ->
            EventSourceDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = Reminder.Schema,
        traitSchema = ReminderDataIdProvider.Schema,
        traitFactory = { context, schema ->
            ReminderDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = CalendarSubscription.Schema,
        traitSchema = CalendarSubscriptionDataIdProvider.Schema,
        traitFactory = { context, schema ->
            CalendarSubscriptionDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = CalendarMirror.Schema,
        traitSchema = CalendarMirrorDataIdProvider.Schema,
        traitFactory = { context, schema ->
            CalendarMirrorDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = CalendarMirrorStamp.Schema,
        traitSchema = CalendarMirrorStampDataIdProvider.Schema,
        traitFactory = { context, schema ->
            CalendarMirrorStampDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = ReminderState.Schema,
        traitSchema = ReminderStateDataIdProvider.Schema,
        traitFactory = { context, schema ->
            ReminderStateDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
}
