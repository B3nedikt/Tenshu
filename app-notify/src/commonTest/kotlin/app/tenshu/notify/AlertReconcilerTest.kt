package app.tenshu.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The add/remove diff (`calendar-app.md` §4.6).
 *
 * ⭐ The only logic in this module, and deliberately so: everything else is a
 * thin call into a platform API that no unit test can meaningfully exercise.
 * Putting the diff here is what stops Android and macOS writing two of them.
 */
class AlertReconcilerTest {

    private val channel = AlertChannel("reminders", "Reminders", AlertImportance.HIGH)

    private fun alert(
        id: String,
        title: String = "Standup",
        body: String = "Room A",
        atMillis: Long = 1_000_000L,
    ) = ScheduledAlert(
        alert = AppAlert(id = id, channel = channel, title = title, body = body),
        deliverAt = Instant.fromEpochMilliseconds(atMillis),
    )

    // --- content-aware diff --------------------------------------------------

    @Test
    fun an_empty_desired_set_against_nothing_is_no_work() {
        assertTrue(AlertReconciler.diff(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun a_new_alert_is_scheduled() {
        val diff = AlertReconciler.diff(listOf(alert("a")), emptyList())

        assertEquals(listOf("a"), diff.schedule.map { it.id })
        assertTrue(diff.cancel.isEmpty())
    }

    @Test
    fun an_alert_that_left_the_desired_set_is_cancelled() {
        val diff = AlertReconciler.diff(emptyList(), listOf(alert("a")))

        assertTrue(diff.schedule.isEmpty())
        assertEquals(listOf("a"), diff.cancel)
    }

    /**
     * ⚠⚠ **The property the whole class exists for.** Re-scheduling an unchanged
     * alert can re-deliver one the user has already seen on Apple platforms, and
     * wastes an alarm on Android. A reconciler that returned everything every
     * tick would be worse than none.
     */
    @Test
    fun an_unchanged_alert_is_left_alone() {
        val diff = AlertReconciler.diff(listOf(alert("a")), listOf(alert("a")))

        assertTrue(diff.isEmpty, "nothing changed, so nothing should be written")
    }

    /** ⚠ …but a changed *time* must be re-scheduled, or a snooze never takes. */
    @Test
    fun a_changed_time_is_rescheduled_under_the_same_id() {
        val diff = AlertReconciler.diff(
            desired = listOf(alert("a", atMillis = 2_000_000L)),
            known = listOf(alert("a", atMillis = 1_000_000L)),
        )

        assertEquals(listOf("a"), diff.schedule.map { it.id })
        assertTrue(diff.cancel.isEmpty(), "a re-schedule replaces; it is not a cancel plus an add")
    }

    /** ⚠ …and a changed *text*, or an edited event's reminder still says the old title. */
    @Test
    fun changed_text_is_rescheduled() {
        assertEquals(
            listOf("a"),
            AlertReconciler.diff(
                desired = listOf(alert("a", title = "Standup (moved)")),
                known = listOf(alert("a", title = "Standup")),
            ).schedule.map { it.id },
        )
        assertEquals(
            listOf("a"),
            AlertReconciler.diff(
                desired = listOf(alert("a", body = "Room B")),
                known = listOf(alert("a", body = "Room A")),
            ).schedule.map { it.id },
        )
    }

    @Test
    fun a_mixed_set_is_split_correctly() {
        val diff = AlertReconciler.diff(
            desired = listOf(alert("keep"), alert("change", title = "New"), alert("add")),
            known = listOf(alert("keep"), alert("change", title = "Old"), alert("drop")),
        )

        assertEquals(listOf("change", "add"), diff.schedule.map { it.id })
        assertEquals(listOf("drop"), diff.cancel)
    }

    /** ⚠ Deterministic output, so a log is readable and a test is not flaky. */
    @Test
    fun the_cancel_list_is_sorted() {
        val diff = AlertReconciler.diff(
            desired = emptyList(),
            known = listOf(alert("c"), alert("a"), alert("b")),
        )

        assertEquals(listOf("a", "b", "c"), diff.cancel)
    }

    // --- the id-only platform ------------------------------------------------

    /**
     * ⚠⚠ Given ids alone there is no way to tell whether an alert's text
     * changed, so everything desired must be re-scheduled. That redundancy is
     * the honest cost of a platform that will only report identities — and it is
     * strictly better than silently keeping a stale reminder.
     */
    @Test
    fun an_id_only_platform_reschedules_everything_it_still_wants() {
        val diff = AlertReconciler.diffAgainstIds(
            desired = listOf(alert("a"), alert("b")),
            knownIds = setOf("a", "b"),
        )

        assertEquals(listOf("a", "b"), diff.schedule.map { it.id })
        assertTrue(diff.cancel.isEmpty())
    }

    @Test
    fun an_id_only_platform_still_cancels_what_is_gone() {
        val diff = AlertReconciler.diffAgainstIds(
            desired = listOf(alert("a")),
            knownIds = setOf("a", "stale"),
        )

        assertEquals(listOf("stale"), diff.cancel)
    }

    @Test
    fun an_id_only_diff_of_nothing_is_still_no_work() {
        assertTrue(AlertReconciler.diffAgainstIds(emptyList(), emptySet()).isEmpty)
    }
}
