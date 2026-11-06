package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSubscriptionSummary
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.EventDraft
import app.tenshu.calendar.data.IcsImportResult
import app.tenshu.calendar.ui.EventEditorViewModel
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.tenshu.calendar.desktopComposeVmTest

/**
 * The conflict dialog (CA-2k) — the screen half.
 *
 * ⚠ What is worth asserting here is not the wording but the **set of ways out**, and
 * that the destructive one is not offered when it makes no sense: an event that is no
 * longer there has no "theirs" to take, so a dialog offering it would be a button that
 * cannot do anything.
 */
@OptIn(ExperimentalTestApi::class)
class EventEditorConflictDialogTest {

    private fun occurrence(title: String) = ResolvedOccurrence(
        eventId = "standup",
        calendarId = "work",
        recurrenceId = "2026-08-10T09:00",
        title = title,
        location = "Room A",
        notes = "",
        startLocal = LocalDateTime(LocalDate(2026, 8, 10), LocalTime(9, 0)),
        endLocal = LocalDateTime(LocalDate(2026, 8, 10), LocalTime(9, 30)),
        zoneId = "Europe/Berlin",
        allDay = false,
        isOverridden = false,
        recurrenceRule = "",
    )

    private inner class Repo(val stored: List<ResolvedOccurrence>) : CalendarRepository {
        val saved = mutableListOf<EventDraft>()
        override suspend fun calendars() = listOf(
            CalendarSummary("work", "Work", 0x4285F4, sensitive = false, visible = true),
        )
        override suspend fun occurrences(from: LocalDate, to: LocalDate, includeHidden: Boolean) =
            stored
        override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) = Unit
        override fun observeHiddenCalendars(): Flow<Set<String>> = flowOf(emptySet())
        override fun observeOccurrences(from: LocalDate, to: LocalDate) = flowOf(stored)
        override fun observeCalendars(): Flow<List<CalendarSummary>> = flowOf(
            listOf(CalendarSummary("work", "Work", 0x4285F4, sensitive = false, visible = true)),
        )
        override fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>> =
            flowOf(emptyList())
        override fun observeDefaultCalendar(): Flow<String> = flowOf("work")
        override suspend fun saveEvent(draft: EventDraft): String {
            saved += draft
            return "standup"
        }
        override suspend fun saveOccurrence(draft: EventDraft, recurrenceId: String) = Unit
        override suspend fun deleteEvent(eventId: String) = Unit
        override suspend fun deleteOccurrence(eventId: String, recurrenceId: String) = Unit
        override suspend fun remindersFor(eventId: String): List<Int> = emptyList()
        override suspend fun setReminders(eventId: String, minutesBefore: List<Int>) = Unit
        override suspend fun defaultReminderMinutes(allDay: Boolean): Int? = null
        override suspend fun saveCalendar(
            calendarId: String,
            name: String,
            colorArgb: Int,
            sensitive: Boolean,
        ): String = calendarId
        override suspend fun deleteCalendar(calendarId: String) = Unit
        override suspend fun defaultCalendar(): String = "work"
        override suspend fun setDefaultCalendar(calendarId: String) = Unit
        override suspend fun ensureDefaultCalendar() = Unit
        override suspend fun importIcs(text: String, calendarId: String, origin: String) =
            IcsImportResult(0, 0, 0)
        override suspend fun exportIcs(calendarId: String): String = ""
        override suspend fun copyExternalCalendar(
            source: app.tenshu.calendar.data.external.ExternalCalendar,
            snapshot: app.tenshu.calendar.data.external.ExternalSnapshot,
        ) = error("unused")
        override suspend fun subscriptions() = emptyList<CalendarSubscriptionSummary>()
        override suspend fun addSubscription(url: String, name: String): String? = null
        override suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) = Unit
        override suspend fun removeSubscription(subscriptionId: String) = Unit
        override suspend fun refreshSubscriptions() = Unit
        override suspend fun dismissReminder(reminderId: String, recurrenceId: String) = Unit
        override suspend fun snoozeReminder(
            reminderId: String,
            recurrenceId: String,
            untilEpochMs: Long,
        ) = Unit
    }

    /** Opens an editor, edits it, and saves (or deletes) into a store that has [stored]. */
    private fun conflicted(
        stored: List<ResolvedOccurrence>,
        deleting: Boolean = false,
        block: androidx.compose.ui.test.ComposeUiTest.(Repo) -> Unit,
    ) {
        val repo = Repo(stored)
        desktopComposeVmTest(width = 900, height = 640) { vms ->
            setContent {
                TenshuTheme {
                    val vm = androidx.compose.runtime.remember {
                        vms.own(EventEditorViewModel(repo, LocalDate(2026, 8, 10))).apply {
                            load(occurrence("Standup"))
                            if (deleting) {
                                delete()
                            } else {
                                setTitle("Standup, moved to 10")
                                save()
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        EventEditorScreen(viewModel = vm, onClose = {})
                    }
                }
            }
            waitForIdle()
            block(repo)
        }
    }

    @Test
    fun a_changed_event_offers_all_three_outcomes() = conflicted(listOf(occurrence("Sprint sync"))) { _ ->
        onNodeWithText("Changed somewhere else").assertIsDisplayed()
        // ⚠ Their version is named, not merely referred to. The time beside it is
        // locale-formatted (`CalendarDateFormat.shortTime`), so only the part that is
        // the same in every locale is asserted — pinning "09:00" here would fail on a
        // 12-hour machine and say nothing about the dialog.
        onNodeWithText("Theirs: Sprint sync", substring = true).assertIsDisplayed()
        onNodeWithText("Keep mine").assertIsDisplayed()
        onNodeWithText("Use theirs").assertIsDisplayed()
        onNodeWithText("Keep both").assertIsDisplayed()
        onNodeWithText("Keep editing").assertIsDisplayed()
    }

    @Test
    fun keeping_both_files_the_edit_as_a_new_event() =
        conflicted(listOf(occurrence("Sprint sync"))) { repo ->
            onNodeWithText("Keep both").performClick()
            waitForIdle()

            assertEquals(listOf("Standup, moved to 10"), repo.saved.map { it.title })
            assertEquals("", repo.saved.single().eventId, "it overwrote theirs instead of adding")
        }

    /**
     * ⚠⚠ No "Use theirs" here, and that is the point of the branch: there is no theirs
     * to take. A dialog that offered it would be offering a button that cannot act.
     */
    @Test
    fun an_event_that_is_gone_offers_only_saving_or_keeping_the_form() = conflicted(emptyList()) { _ ->
        onNodeWithText("This event is gone").assertIsDisplayed()
        onNodeWithText("Save mine anyway").assertIsDisplayed()
        onNodeWithText("Keep editing").assertIsDisplayed()
        assertTrue(
            runCatching { onNodeWithText("Use theirs").assertIsDisplayed() }.isFailure,
            "offered to take a version that does not exist",
        )
    }

    /**
     * ⚠ The delete branch says what a delete costs — "throws that change away too" —
     * and offers *Delete anyway* / *Keep theirs*. No "Keep both": both of a deletion and
     * an edit is just the edit, which is what keeping theirs already does.
     */
    @Test
    fun a_delete_over_someone_elses_change_offers_its_own_two_ways_out() =
        conflicted(listOf(occurrence("Sprint sync")), deleting = true) { _ ->
            onNodeWithText("Delete anyway").assertIsDisplayed()
            onNodeWithText("Keep theirs").assertIsDisplayed()
            onNodeWithText("throws that change away too", substring = true).assertIsDisplayed()
            assertTrue(
                runCatching { onNodeWithText("Keep both").assertIsDisplayed() }.isFailure,
                "offered to keep both a deletion and an edit",
            )
        }

    @Test
    fun nothing_is_written_until_the_question_is_answered() =
        conflicted(listOf(occurrence("Sprint sync"))) { repo ->
            assertTrue(repo.saved.isEmpty(), "the save went through behind the prompt")
        }
}
