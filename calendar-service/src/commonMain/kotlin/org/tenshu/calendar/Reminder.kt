package org.tenshu.calendar

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * "Remind me 15 minutes before" — one alert attached to an [Event]
 * (`calendar-app.md` §4).
 *
 * ⚠⚠ **A reminder belongs to the series, not to an occurrence.** "Remind me
 * before the standup" means before *every* standup; a row per occurrence would
 * be an unbounded expansion of a rule, which is the thing this schema refuses to
 * store anywhere (§3 D2). Per-occurrence state — dismissed, snoozed — is
 * [ReminderState], which exists only where a user has actually acted.
 *
 * ⚠ [minutesBefore] is **signed**, and the negative half is not an edge case:
 * an all-day event starts at 00:00, so "on the day at 9 a.m." — what every stock
 * calendar offers for an all-day reminder — is *minus 540*, i.e. 540 minutes
 * **after** the start. Modelling only "before" would make all-day reminders
 * either unrepresentable or wrong at midnight. This is RFC 5545's `TRIGGER`
 * with its sign flipped to match how people say it.
 *
 * ⚠ No `method` field (RFC 5545's `DISPLAY`/`EMAIL`/`AUDIO`). We can only do
 * one of those, and a field nothing can act on is a decision made too early —
 * the same argument `CalendarPreferences` makes about the reminder policy. HR#9
 * covers adding it later.
 */
interface Reminder : Composite {

    override val schema: CompositeSchema get() = Schema

    /**
     * The row key, **derived from the content**: `"$eventId|$minutesBefore"`.
     *
     * ⭐ Deliberately not minted at random. Two devices that independently add
     * "15 minutes before" to the same event converge on one row rather than two,
     * and re-saving a reminder is idempotent — which matters because a
     * reminder's *identity* really is "this event, this offset". Changing the
     * offset is a delete plus a create, which is honest: it is a different
     * reminder.
     */
    val reminderId: TString get() = getField(Field.REMINDER_ID)

    /** The [Event.eventId] this fires for. */
    val eventId: TString get() = getField(Field.EVENT_ID)

    /** Minutes **before** the occurrence starts; negative means after it. */
    val minutesBefore: TInteger get() = getField(Field.MINUTES_BEFORE)

    companion object {
        const val ID = "tenshu/calendar/reminder"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(ReminderDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.REMINDER_ID)
                string(Field.EVENT_ID)
                int(Field.MINUTES_BEFORE)
            }
        )

        /** The content-derived key; see [reminderId]. */
        fun idFor(eventId: String, minutesBefore: Int): String = "$eventId|$minutesBefore"

        fun create(
            context: TContext,
            eventId: String,
            minutesBefore: Int,
        ): Reminder = context.create(
            type = ID,
            // ⚠ `.toLong()` — `TInteger` is Long-backed and the data factory
            // rejects a bare `Int` at runtime, not at compile time. The
            // `Calendar.colorArgb` incident, which cost a CA-1 debugging session.
            fields = listOf(idFor(eventId, minutesBefore), eventId, minutesBefore.toLong()),
        )
    }

    object Field {
        const val REMINDER_ID = "$ID/reminderId"
        const val EVENT_ID = "$ID/eventId"
        const val MINUTES_BEFORE = "$ID/minutesBefore"
    }
}

/** Field accessors for a projected [Reminder] row; the `Calendar` precedent. */
val Entity<out Reminder>.reminderId: TString get() = getFieldValue(Reminder.Field.REMINDER_ID)

val Entity<out Reminder>.eventId: TString get() = getFieldValue(Reminder.Field.EVENT_ID)

val Entity<out Reminder>.minutesBefore: TInteger get() = getFieldValue(Reminder.Field.MINUTES_BEFORE)

interface ReminderDataIdProvider : CompositeDataIdProvider<Reminder> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/calendar/reminder/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Reminder.Field.REMINDER_ID,
        )
    }
}
