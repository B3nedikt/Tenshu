package app.tenshu.calendar.ui

import app.tenshu.error.errorCopyKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry

/**
 * The typed problems the Calendar view models report, and the English the SwiftUI shell reads.
 *
 * ⚠⚠ No error's own message ever reaches either: a `TenshuException`'s `message` is its id, and
 * `TError.messageOrNull()` is developer diagnostics (docs/agent.md, *Errors*).
 */
class CalendarProblemTest {

    @Test
    fun theEnglishIsWhatTheScreensSay() {
        assertEquals("Give the calendar a name", CalendarProblem.NameMissing.english())
        assertEquals("You already have a calendar called \"Work\"", CalendarProblem.NameTaken("Work").english())
        assertEquals("Paste a calendar address", CalendarProblem.AddressMissing.english())
        assertEquals(
            "Only https:// and webcal:// addresses can be subscribed to",
            CalendarProblem.AddressNotSupported.english(),
        )
        assertEquals("Could not load calendars", CalendarProblem.Failed(CalendarAction.LOAD_CALENDARS).english())
        assertEquals("Could not stop syncing", CalendarProblem.Failed(CalendarAction.STOP_SYNCING).english())
    }

    @Test
    fun aPlainThrowableCarriesNoKeyAndNoMessage() {
        val problem = CalendarProblem.failure(CalendarAction.SAVE, IllegalStateException("no proxy"))
        assertNull(problem.errorKey)
        assertEquals("Could not save", problem.english())
    }

    @Test
    fun aTenshuExceptionCarriesItsCopyKeyNeverItsSentenceOrId() {
        val ctx = TestProxy(schemaRegistry = createSyntheticTestRegistry()).tContext
        val error = GenericError.create(ctx, "developer detail")

        val problem = CalendarProblem.failure(CalendarAction.DELETE, TenshuException(error))

        assertEquals(error.errorCopyKey(), problem.errorKey)
        assertEquals("Could not delete", problem.english())
        assertFalse("developer detail" in problem.toString())
    }
}
