package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSubscriptionSummary
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.EventDraft
import app.tenshu.calendar.data.IcsImportResult
import app.tenshu.calendar.desktopComposeVmTest
import app.tenshu.calendar.ui.EventEditorViewModel
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The event editor on a phone-wide window (411 dp, a Pixel's width).
 *
 * Found by the Android store-screenshot walk (store-screenshots.md §8): with the fixture's five
 * calendars the Calendar chips were one plain `Row`, so the last one ("Work") was laid out past the
 * right edge and could not be chosen at all — and the Repeats row squeezed "Yearly" onto two lines.
 * Both rows now wrap, as Reminders always did.
 */
@OptIn(ExperimentalTestApi::class)
class EventEditorPhoneWidthTest {

    private val calendars = listOf("Family", "Fitness", "Personal", "Travel", "Work").mapIndexed { i, name ->
        CalendarSummary(name.lowercase(), name, 0x4285F4 + i, sensitive = false, visible = true)
    }

    @Test
    fun every_calendar_and_every_repeat_choice_fits_a_phone() {
        val width = 411
        desktopComposeVmTest(width = width, height = 1600) { vms ->
            setContent {
                TenshuTheme {
                    val vm = remember { vms.own(EventEditorViewModel(Repo(calendars), LocalDate(2026, 10, 22))) }
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        EventEditorScreen(viewModel = vm, onClose = {})
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithText("Work").fetchSemanticsNode() }.isSuccess
            }
            val family = onNodeWithText("Family").fetchSemanticsNode().boundsInRoot
            for (calendar in calendars) {
                val bounds = onNodeWithText(calendar.name).fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.right <= width, "${calendar.name}'s chip ends at ${bounds.right}, past the $width-wide window")
                // ⚠ A plain Row does not push the last chip out so much as crush it: on the phone
                // it drew nothing at all. A chip someone can tap is as tall as the others and wider
                // than its own dot.
                assertTrue(bounds.width >= 48f, "${calendar.name}'s chip is ${bounds.width} wide")
                assertEquals(family.height, bounds.height, "${calendar.name}'s chip is not one line like Family's")
            }
            val never = onNodeWithText("Never").fetchSemanticsNode().boundsInRoot
            for (choice in listOf("Daily", "Weekly", "Monthly", "Yearly")) {
                val bounds = onNodeWithText(choice).fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.right <= width, "$choice ends at ${bounds.right}, past the $width-wide window")
                assertEquals(never.height, bounds.height, "$choice wraps onto more lines than Never")
            }
        }
    }

    private class Repo(private val calendars: List<CalendarSummary>) : CalendarRepository {
        override suspend fun calendars() = calendars
        override suspend fun occurrences(from: LocalDate, to: LocalDate, includeHidden: Boolean) = emptyList<ResolvedOccurrence>()
        override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) = Unit
        override fun observeHiddenCalendars(): Flow<Set<String>> = flowOf(emptySet())
        override fun observeOccurrences(from: LocalDate, to: LocalDate) = flowOf(emptyList<ResolvedOccurrence>())
        override fun observeCalendars(): Flow<List<CalendarSummary>> = flowOf(calendars)
        override fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>> = flowOf(emptyList())
        override fun observeDefaultCalendar(): Flow<String> = flowOf("personal")
        override suspend fun saveEvent(draft: EventDraft): String = "new"
        override suspend fun saveOccurrence(draft: EventDraft, recurrenceId: String) = Unit
        override suspend fun deleteEvent(eventId: String) = Unit
        override suspend fun deleteOccurrence(eventId: String, recurrenceId: String) = Unit
        override suspend fun remindersFor(eventId: String): List<Int> = emptyList()
        override suspend fun setReminders(eventId: String, minutesBefore: List<Int>) = Unit
        override suspend fun defaultReminderMinutes(allDay: Boolean): Int? = null
        override suspend fun saveCalendar(calendarId: String, name: String, colorArgb: Int, sensitive: Boolean): String = calendarId
        override suspend fun deleteCalendar(calendarId: String) = Unit
        override suspend fun defaultCalendar(): String = "personal"
        override suspend fun setDefaultCalendar(calendarId: String) = Unit
        override suspend fun ensureDefaultCalendar() = Unit
        override suspend fun importIcs(text: String, calendarId: String, origin: String) = IcsImportResult(0, 0, 0)
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
        override suspend fun snoozeReminder(reminderId: String, recurrenceId: String, untilEpochMs: Long) = Unit
    }
}
