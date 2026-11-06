package app.tenshu.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The registration point several apps share (CA-5f).
 *
 * ⚠ The one behaviour worth pinning is that a handler which declines really does
 * hand on, and one that claims really does stop the chain — a composite that ran
 * every handler would let two apps both act on one button press.
 */
class CompositeAlertActionHandlerTest {

    private class Recording(private val prefix: String) : AlertActionHandler {
        val seen = mutableListOf<Pair<String, String>>()

        override suspend fun handle(tapPayload: String, actionId: String): Boolean {
            seen += tapPayload to actionId
            return tapPayload.startsWith(prefix)
        }
    }

    @Test
    fun the_first_handler_to_claim_the_payload_wins() = runTest {
        val calendar = Recording("calendar/")
        val routines = Recording("routine/")

        val handled = CompositeAlertActionHandler(listOf(calendar, routines))
            .handle("calendar/occurrence/standup|2026-08-03T09:00", "calendar.dismiss")

        assertTrue(handled)
        assertEquals(1, calendar.seen.size)
        assertTrue(routines.seen.isEmpty(), "a claimed press must not reach a second handler")
    }

    @Test
    fun a_declining_handler_hands_on_to_the_next() = runTest {
        val calendar = Recording("calendar/")
        val routines = Recording("routine/")

        val handled = CompositeAlertActionHandler(listOf(calendar, routines))
            .handle("routine/fired/porch-light", "routine.snooze")

        assertTrue(handled)
        assertEquals(1, calendar.seen.size, "it was offered")
        assertEquals(1, routines.seen.size, "…and passed on")
    }

    @Test
    fun a_payload_nobody_claims_is_reported_unhandled() = runTest {
        val handled = CompositeAlertActionHandler(listOf(Recording("calendar/")))
            .handle("fit/import/done", "fit.dismiss")

        assertFalse(handled, "so the caller leaves the alert on screen rather than dismissing it")
    }

    /** ⚠ No handlers registered at all is not an error — nothing claimed it. */
    @Test
    fun an_empty_composite_declines() = runTest {
        assertFalse(CompositeAlertActionHandler(emptyList()).handle("calendar/x|y", "a"))
    }
}
