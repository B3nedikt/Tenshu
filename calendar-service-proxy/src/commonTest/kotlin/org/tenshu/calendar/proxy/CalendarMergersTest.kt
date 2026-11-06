package org.tenshu.calendar.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.Event
import org.tenshu.calendar.ReminderState
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.core.Data
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Calendar's semantic mergers (CA-2j).
 *
 * ⚠⚠ Every assertion here is run **both ways round**. A merger sees `existing` = this
 * device's state and `incoming` = the other's, which means the two devices call it with
 * the arguments swapped — so a rule that is not commutative leaves them holding two
 * different answers for good, with no later sync to reconcile them. Asserting only one
 * order would pass for a merger that quietly diverges.
 */
class CalendarMergersTest {

    private fun event(
        exceptionDates: String,
        title: String = "Standup",
        context: org.tenshu.core.core.TContext,
    ): Event = Event.create(
        context = context,
        eventId = "standup",
        calendarId = "work",
        title = title,
        startLocal = "2026-08-03T09:00",
        endLocal = "2026-08-03T09:30",
        recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        exceptionDates = exceptionDates,
    )

    private fun Data.dates(): String =
        (this as? org.tenshu.core.entity.Entity<*>)
            ?.getFieldValue<org.tenshu.core.TString>(Event.Field.EXCEPTION_DATES)?.get()
            .orEmpty()

    /**
     * ⭐⭐ The bug this exists for. `DeleteOccurrence` is a read-modify-write on a
     * delimited set, so two devices deleting *different* occurrences of one series each
     * write a string missing the other's date. Under last-writer-wins one of the two
     * deletions is dropped and **the occurrence the user deleted comes back**.
     */
    @Test
    fun two_devices_deleting_different_occurrences_keep_both_deletions() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val ctx = proxy.executionContext

        val phoneDeletedTheTenth = event("2026-08-10", context = ctx.context)
        val laptopDeletedTheSeventeenth = event("2026-08-17", context = ctx.context)

        val onLaptop = EventMerger().merge(ctx, phoneDeletedTheTenth, laptopDeletedTheSeventeenth)
        val onPhone = EventMerger().merge(ctx, laptopDeletedTheSeventeenth, phoneDeletedTheTenth)

        assertEquals("2026-08-10,2026-08-17", onLaptop.dates())
        assertEquals("2026-08-10,2026-08-17", onPhone.dates(), "the two devices disagree")
    }

    /** ⚠ Order of deletion must not change the string, or the rows never stop disagreeing. */
    @Test
    fun the_union_is_sorted_and_deduplicated() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val ctx = proxy.executionContext

        val merged = EventMerger().merge(
            ctx,
            event("2026-08-17,2026-08-03", context = ctx.context),
            event("2026-08-10,2026-08-17", context = ctx.context),
        )

        assertEquals("2026-08-03,2026-08-10,2026-08-17", merged.dates())
    }

    /**
     * ⚠ The scalar block still resolves last-writer-wins — this merger widens no other
     * field's semantics, and it must not, because there is nothing to order the two
     * versions of a title by.
     */
    @Test
    fun everything_but_the_deletions_comes_from_the_incoming_state() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val ctx = proxy.executionContext

        val merged = EventMerger().merge(
            ctx,
            event("", title = "Old name", context = ctx.context),
            event("", title = "New name", context = ctx.context),
        ) as org.tenshu.core.entity.Entity<*>

        assertEquals(
            "New name",
            merged.getFieldValue<org.tenshu.core.TString>(Event.Field.TITLE).get(),
        )
    }

    // --- reminder state ------------------------------------------------------

    private fun Data.dismissed(): Boolean =
        (this as? org.tenshu.core.entity.Entity<*>)
            ?.getFieldValue<org.tenshu.core.TBoolean>(ReminderState.Field.DISMISSED)?.get() ?: false

    private fun Data.snoozedUntil(): Long =
        (this as? org.tenshu.core.entity.Entity<*>)
            ?.getFieldValue<org.tenshu.core.number.TInteger>(
                ReminderState.Field.SNOOZED_UNTIL_EPOCH_MS,
            )?.get() ?: 0L

    /**
     * ⭐ Dismiss on the phone, snooze on the laptop. The row's readers already treat a
     * dismissal as final ("or 'I am done with this' would lose to 'remind me again'"),
     * but under last-writer-wins the row itself does not — and the user who said *done*
     * is alerted again.
     */
    @Test
    fun a_dismissal_survives_a_concurrent_snooze_whichever_arrives_second() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val ctx = proxy.executionContext

        val dismissed = ReminderState.create(ctx.context, "rem", "2026-08-10T09:00", dismissed = true)
        val snoozed = ReminderState.create(
            ctx.context,
            "rem",
            "2026-08-10T09:00",
            snoozedUntilEpochMs = 1_760_000_000_000,
        )

        val onLaptop = ReminderStateMerger().merge(ctx, dismissed, snoozed)
        val onPhone = ReminderStateMerger().merge(ctx, snoozed, dismissed)

        assertTrue(onLaptop.dismissed(), "the dismissal was undone by a snooze")
        assertTrue(onPhone.dismissed(), "the two devices disagree")
        // ⚠ The snooze is kept rather than dropped: precedence is the reader's job, and
        // discarding the fact would lose it if the dismissal were ever revised.
        assertEquals(1_760_000_000_000, onLaptop.snoozedUntil())
        assertEquals(1_760_000_000_000, onPhone.snoozedUntil())
    }

    @Test
    fun the_later_snooze_wins_over_the_earlier_one() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val ctx = proxy.executionContext

        val early = ReminderState.create(ctx.context, "rem", "r", snoozedUntilEpochMs = 1_000)
        val late = ReminderState.create(ctx.context, "rem", "r", snoozedUntilEpochMs = 9_000)

        assertEquals(9_000, ReminderStateMerger().merge(ctx, early, late).snoozedUntil())
        assertEquals(9_000, ReminderStateMerger().merge(ctx, late, early).snoozedUntil())
    }
}
