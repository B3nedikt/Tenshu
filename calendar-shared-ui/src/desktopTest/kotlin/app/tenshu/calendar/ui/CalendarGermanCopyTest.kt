package app.tenshu.calendar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.shared.resources.Res
import app.tenshu.calendar.shared.resources.calendar_events_one
import app.tenshu.calendar.shared.resources.calendar_events_other
import app.tenshu.calendar.shared.resources.calendar_new_event
import app.tenshu.calendar.shared.resources.calendar_today
import app.tenshu.calendar.shared.resources.calendar_today_heading
import app.tenshu.calendar.shared.resources.calendar_view_agenda
import app.tenshu.calendar.shared.resources.calendar_view_day
import app.tenshu.calendar.shared.resources.calendar_view_month
import app.tenshu.calendar.shared.resources.calendar_view_week
import app.tenshu.calendar.ui.external.DeviceKind
import app.tenshu.calendar.ui.external.word
import app.tenshu.calendar.ui.i18n.countOf
import app.tenshu.calendar.ui.screens.CalendarSidebar
import app.tenshu.ui.theme.TenshuTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.compose.resources.stringResource
import org.tenshu.ui.test.NoMotion

/**
 * A German launch draws Calendar's chrome in German: the toolbar's view names and *Today*, the
 * agenda's day heading, the sidebar, and a count, all from resources rather than English literals
 * (store-screenshots.md found them English beside German resource text).
 *
 * ⚠ The screenshot walks press *Month* and *Today* and wait for *Manage calendars*; they resolve
 * German through these same keys, so a key renamed here breaks the walk, not just this test.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarGermanCopyTest {

    private fun <T> inLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try { return block() } finally { Locale.setDefault(previous) }
    }

    private fun render(locale: Locale): List<String> = inLocale(locale) {
        var out = emptyList<String>()
        runDesktopComposeUiTest {
            setContent {
                out = listOf(
                    stringResource(Res.string.calendar_view_month),
                    stringResource(Res.string.calendar_view_week),
                    stringResource(Res.string.calendar_view_day),
                    stringResource(Res.string.calendar_view_agenda),
                    stringResource(Res.string.calendar_today),
                    stringResource(Res.string.calendar_today_heading, "20.11.2026"),
                    stringResource(Res.string.calendar_new_event),
                    countOf(1, Res.string.calendar_events_one, Res.string.calendar_events_other),
                    countOf(3, Res.string.calendar_events_one, Res.string.calendar_events_other),
                    DeviceKind.MAC.word(),
                )
            }
            waitForIdle()
        }
        out
    }

    @Test
    fun german() {
        assertEquals(
            listOf(
                "Monat", "Woche", "Tag", "Agenda", "Heute", "Heute · 20.11.2026", "Neuer Termin",
                "1 Termin", "3 Termine", "Mac",
            ),
            render(Locale.GERMANY),
        )
    }

    @Test
    fun english() {
        assertEquals(
            listOf(
                "Month", "Week", "Day", "Agenda", "Today", "Today · 20.11.2026", "New event",
                "1 event", "3 events", "Mac",
            ),
            render(Locale.US),
        )
    }

    @Test
    fun theSidebarReadsGerman() = inLocale(Locale.GERMANY) {
        runDesktopComposeUiTest(width = 400, height = 600, effectContext = NoMotion) {
            setContent {
                TenshuTheme {
                    Box(Modifier.fillMaxSize()) {
                        CalendarSidebar(
                            calendars = listOf(CalendarSummary("work", "", 0xFF2196F3.toInt(), sensitive = false, visible = true)),
                            onToggleVisible = { _, _ -> },
                            onManage = {},
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Kalender").assertIsDisplayed()
            onNodeWithText("Kalender verwalten").assertIsDisplayed()
            onNodeWithText("(Ohne Namen)").assertIsDisplayed()
        }
    }
}
