package org.tenshu.calendar

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * What the user did about one reminder on one occurrence — dismissed it, or
 * snoozed it (`calendar-app.md` §4.3 gap 3).
 *
 * ⭐⭐ **This row is why dismissal syncs.** Dismissing a reminder on the phone
 * has to clear it on the laptop, and on this platform that is not a push
 * problem — it is an ordinary `Syncable` row, and the existing machinery carries
 * it (§4.5). The `:notification-service` `DismissedRequest` pattern, in
 * Calendar's own vocabulary.
 *
 * ⚠⚠ **Written only when the user acts.** The common case — a reminder fires
 * and is ignored — writes nothing. A row per (reminder × occurrence) would be
 * the unbounded expansion of a recurrence rule that this schema refuses
 * everywhere else; absence means "not yet acted on", which is both the default
 * and free.
 *
 * ⚠ [snoozedUntilEpochMs] is an **instant**, and that is not a violation of §3
 * D3's "never store an instant as truth". D3 is about *event* times, which are
 * civil rules whose instant can move when the tz database changes. "Remind me
 * again in ten minutes" is not a rule — it is one absolute moment, chosen by the
 * user, that no timezone update can legitimately shift.
 */
interface ReminderState : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Key: `"$reminderId|$recurrenceId"` — see [idFor]. */
    val stateId: TString get() = getField(Field.STATE_ID)

    val reminderId: TString get() = getField(Field.REMINDER_ID)

    /** The occurrence's **original** start, as [EventOverride] keys it. */
    val recurrenceId: TString get() = getField(Field.RECURRENCE_ID)

    /**
     * When a snooze expires, in epoch milliseconds; `0` for "not snoozed".
     *
     * ⚠ `0` rather than an optional field. Optionality in this schema is a
     * *migration* affordance (the `TenshuIdentity.grantSeed` precedent), and
     * "the epoch" is not a time a snooze can legitimately expire at, so the
     * sentinel is unambiguous.
     */
    val snoozedUntilEpochMs: TInteger get() = getField(Field.SNOOZED_UNTIL_EPOCH_MS)

    /**
     * True once the user has dismissed this occurrence's reminder.
     *
     * ⚠ Independent of [snoozedUntilEpochMs] rather than folded into it, so a
     * row can say both things at once — which means the *precedence* has to be
     * decided by whoever reads them. `ReminderPlanner` settles it: a dismissal
     * always wins, or "I am done with this" would lose to "remind me again".
     *
     * ⚠⚠ **The row itself did not obey that precedence until CA-2j.** Under
     * last-writer-wins, dismissing on the phone and snoozing on the laptop left
     * whichever block landed second as the whole truth — so a stale snooze could
     * un-dismiss, and the user who said *done* was alerted again.
     * `ReminderStateMerger` makes this flag one-way (OR) and the snooze monotone
     * (max), so neither can be walked backwards by a block arriving late.
     */
    val dismissed: TBoolean get() = getField(Field.DISMISSED)

    companion object {
        const val ID = "tenshu/calendar/reminderState"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(ReminderStateDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.STATE_ID)
                string(Field.REMINDER_ID)
                string(Field.RECURRENCE_ID)
                int(Field.SNOOZED_UNTIL_EPOCH_MS)
                boolean(Field.DISMISSED)
            }
        )

        fun idFor(reminderId: String, recurrenceId: String): String = "$reminderId|$recurrenceId"

        fun create(
            context: TContext,
            reminderId: String,
            recurrenceId: String,
            snoozedUntilEpochMs: Long = 0L,
            dismissed: Boolean = false,
        ): ReminderState = context.create(
            type = ID,
            fields = listOf(
                idFor(reminderId, recurrenceId),
                reminderId,
                recurrenceId,
                snoozedUntilEpochMs,
                dismissed,
            ),
        )
    }

    object Field {
        const val STATE_ID = "$ID/stateId"
        const val REMINDER_ID = "$ID/reminderId"
        const val RECURRENCE_ID = "$ID/recurrenceId"
        const val SNOOZED_UNTIL_EPOCH_MS = "$ID/snoozedUntilEpochMs"
        const val DISMISSED = "$ID/dismissed"
    }
}

/** Field accessors for a projected [ReminderState] row. */
val Entity<out ReminderState>.stateId: TString get() = getFieldValue(ReminderState.Field.STATE_ID)

val Entity<out ReminderState>.reminderId: TString
    get() = getFieldValue(ReminderState.Field.REMINDER_ID)

val Entity<out ReminderState>.recurrenceId: TString
    get() = getFieldValue(ReminderState.Field.RECURRENCE_ID)

val Entity<out ReminderState>.snoozedUntilEpochMs: TInteger
    get() = getFieldValue(ReminderState.Field.SNOOZED_UNTIL_EPOCH_MS)

val Entity<out ReminderState>.dismissed: TBoolean
    get() = getFieldValue(ReminderState.Field.DISMISSED)

interface ReminderStateDataIdProvider : CompositeDataIdProvider<ReminderState> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/calendar/reminderState/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = ReminderState.Field.STATE_ID,
        )
    }
}
