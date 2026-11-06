package app.tenshu.macos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ The guard that keeps an unbundled JVM alive: with no calendar usage string in the running
 * bundle (a test JVM, `gradle run`), TCC aborts on first EventKit use — so nothing may reach it.
 */
class MacEventKitTest {

    @Test
    fun an_unbundled_jvm_never_touches_eventkit() {
        assertFalse(MacEventKit.isUsable)
        assertEquals(-1, MacEventKit.authorizationStatus())
        assertFalse(MacEventKit.hasFullAccess())
        assertFalse(MacEventKit.requestFullAccess(timeoutSeconds = 1))
        assertTrue(MacEventKit.calendars().isEmpty())
        assertNull(MacEventKit.occurrences("any", 0, 1))
    }
}
