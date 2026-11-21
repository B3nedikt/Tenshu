package app.tenshu.calendar.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.calendar.ui.i18n.text
import app.tenshu.error.ErrorCopyKey
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The view models' problems, worded by the Compose shell in the app's language. ⚠ A typed failure
 * adds its error's catalogue copy, and a catalogue miss the generic sentence — never a message.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarProblemGermanTest {

    private val problems = listOf(
        CalendarProblem.NameMissing,
        CalendarProblem.NameTaken("Arbeit"),
        CalendarProblem.AddressMissing,
        CalendarProblem.AddressNotSupported,
        CalendarProblem.Failed(CalendarAction.SAVE),
        CalendarProblem.Failed(CalendarAction.DELETE, ErrorCopyKey("error_not_found")),
        CalendarProblem.Failed(CalendarAction.DELETE, ErrorCopyKey("error_no_such_entry_anywhere")),
    )

    private fun render(locale: Locale): List<String> {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            var out = emptyList<String>()
            runDesktopComposeUiTest {
                setContent { out = problems.map { it.text() } }
                waitForIdle()
            }
            return out
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun german() {
        assertEquals(
            listOf(
                "Gib dem Kalender einen Namen",
                "Du hast bereits einen Kalender namens „Arbeit“",
                "Füge eine Kalenderadresse ein",
                "Nur https://- und webcal://-Adressen können abonniert werden",
                "Speichern fehlgeschlagen",
                "Löschen fehlgeschlagen: Dieses Element ist nicht mehr verfügbar.",
                "Löschen fehlgeschlagen: Etwas ist schiefgelaufen. Bitte versuche es erneut.",
            ),
            render(Locale.GERMANY),
        )
    }

    @Test
    fun english() {
        assertEquals(
            listOf(
                "Give the calendar a name",
                "You already have a calendar called “Arbeit”",
                "Paste a calendar address",
                "Only https:// and webcal:// addresses can be subscribed to",
                "Could not save",
                "Could not delete: That item is no longer available.",
                "Could not delete: Something went wrong. Please try again.",
            ),
            render(Locale.US),
        )
    }
}
