package org.tenshu.calendar.proxy

import org.tenshu.calendar.Event
import org.tenshu.calendar.ReminderState
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.number.TInteger
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.Entity
import org.tenshu.sync.common.SemanticMerger

/**
 * Calendar's semantic mergers (CA-2j).
 *
 * ## ⚠⚠ What these deliberately do *not* do
 *
 * They do **not** merge an event field by field. Two reasons, and both are the reason
 * `Event`'s schema comment says a merger "has nothing useful to contribute":
 *
 * 1. **There are no per-field versions to merge on.** A [SemanticMerger] receives two
 *    whole states and nothing that says *which field* moved when. `FolderMerger` can
 *    tie-break per child because a `File` carries `LastModifiedAt` as a value property;
 *    an `Event` carries no such stamp, so "keep the newer title" is not a question this
 *    layer can answer.
 * 2. **Start, end, all-day and timezone are one fact, not four.** Merging them
 *    field-wise can produce an event that ends before it begins — a state neither
 *    device ever held. Losing one device's edit is recoverable; inventing a third
 *    version that nobody wrote is not.
 *
 * ⚠⚠ And a constraint that rules out cleverness generally: **a merger must converge.**
 * `existing` is whatever *this* device holds, so it differs per device; any rule that is
 * not commutative — "prefer the incoming title", "prefer the non-blank one" — leaves two
 * devices with two different answers, permanently, with no further sync to fix it. Every
 * merge below is a set union, a boolean OR or a max: commutative, idempotent, and
 * therefore convergent whatever order blocks arrive in.
 *
 * So the scalar block keeps last-writer-wins, exactly as before. What these mergers add
 * is the two facts where LWW **loses data** rather than merely picking a version.
 */

/**
 * Union of the dates deleted from a recurring series.
 *
 * ⚠⚠ **This fixes a resurrection, not a preference.** `DeleteOccurrence` is a
 * read-modify-write: it reads `exceptionDates`, appends one date, and writes the whole
 * delimited string back. Two devices each deleting a *different* occurrence of the same
 * series therefore write two strings, each missing the other's date — and under
 * last-writer-wins one of the two deleted occurrences **comes back**. A deletion is a
 * fact the user asserted; a sync that undoes it is data loss, and set union is the
 * standard answer.
 *
 * ⚠ Everything else is taken from [incoming], which is precisely today's behaviour: this
 * merger widens no other field's semantics.
 */
class EventMerger : SemanticMerger {
    override val typeId: String = Event.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data {
        val merged = (existing.exceptionDates() + incoming.exceptionDates())
            // ⚠ Sorted and de-duplicated, matching what `DeleteOccurrence` writes — two
            // devices that deleted the same dates in a different order must not produce
            // two different strings for one set, or the rows never stop disagreeing.
            .distinct()
            .sorted()
            .joinToString(",")

        return Entity.create<Event>(
            context = executionContext.context,
            composite = Event.create(
                context = executionContext.context,
                eventId = incoming.string(Event.Field.EVENT_ID),
                calendarId = incoming.string(Event.Field.CALENDAR_ID),
                title = incoming.string(Event.Field.TITLE),
                startLocal = incoming.string(Event.Field.START_LOCAL),
                endLocal = incoming.string(Event.Field.END_LOCAL),
                timeZoneId = incoming.string(Event.Field.TIME_ZONE_ID),
                allDay = incoming.boolean(Event.Field.ALL_DAY),
                location = incoming.string(Event.Field.LOCATION),
                notes = incoming.string(Event.Field.NOTES),
                recurrenceRule = incoming.string(Event.Field.RECURRENCE_RULE),
                exceptionDates = merged,
            ),
        )
    }

    private fun Data.exceptionDates(): List<String> =
        string(Event.Field.EXCEPTION_DATES)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

/**
 * A dismissal is terminal; a snooze only ever moves later.
 *
 * ⚠⚠ **Without this, a stale snooze can un-dismiss a reminder.** The row already
 * documents the precedence its *readers* apply — "a dismissal always wins, or 'I am
 * done with this' would lose to 'remind me again'" — but under last-writer-wins the row
 * itself does not obey it: dismiss on the phone, snooze on the laptop, and whichever
 * block lands second is the whole truth. The user who said *done* gets alerted again.
 *
 * ⚠ `dismissed` is a one-way flag (OR) and `snoozedUntilEpochMs` takes the max, so both
 * halves are monotone. Neither can be walked backwards by a block arriving late, which
 * is the property that makes this safe to apply in any order.
 */
class ReminderStateMerger : SemanticMerger {
    override val typeId: String = ReminderState.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data =
        Entity.create<ReminderState>(
            context = executionContext.context,
            composite = ReminderState.create(
                context = executionContext.context,
                reminderId = incoming.string(ReminderState.Field.REMINDER_ID),
                recurrenceId = incoming.string(ReminderState.Field.RECURRENCE_ID),
                snoozedUntilEpochMs = maxOf(
                    existing.long(ReminderState.Field.SNOOZED_UNTIL_EPOCH_MS),
                    incoming.long(ReminderState.Field.SNOOZED_UNTIL_EPOCH_MS),
                ),
                dismissed = existing.boolean(ReminderState.Field.DISMISSED) ||
                    incoming.boolean(ReminderState.Field.DISMISSED),
            ),
        )
}

// ⚠ `Entity` or bare `Composite`: a merger is handed the local state (often a lazy
// database reference wrapped in an entity) and the incoming one (materialised from the
// wire), and the two are not the same shape. `FolderMerger` carries the same pair of
// accessors for the same reason.

private fun Data.field(fieldId: String): Data? = when (this) {
    is Entity<*> -> getFieldValue<Data>(fieldId)
    is Composite -> getField<Data>(fieldId)
    else -> null
}

private fun Data.string(fieldId: String): String = (field(fieldId) as? TString)?.get().orEmpty()

private fun Data.boolean(fieldId: String): Boolean =
    (field(fieldId) as? TBoolean)?.get() ?: false

private fun Data.long(fieldId: String): Long = (field(fieldId) as? TInteger)?.get() ?: 0L
