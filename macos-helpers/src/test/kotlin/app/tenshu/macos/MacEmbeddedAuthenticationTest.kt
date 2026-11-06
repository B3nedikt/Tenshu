package app.tenshu.macos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MacEmbeddedAuthentication] without ever putting a view on screen — the test JVM has no window to
 * find, so `authenticate` must answer [MacEmbeddedAuthentication.Result.Unavailable] (the caller's
 * cue for the password sheet) rather than hang or throw. The in-window pass is by hand.
 */
class MacEmbeddedAuthenticationTest {

    private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    private val slot = MacEmbeddedAuthentication.Slot("no such window", 800.0, 600.0, 10.0, 10.0, 64.0, 64.0)

    @Test
    fun resolvesOnMacAndDegradesElsewhere() {
        if (mac && ForeignSupport.isAvailable()) {
            assertTrue(MacEmbeddedAuthentication.isSupported, "LAAuthenticationView did not resolve")
        } else {
            assertFalse(MacEmbeddedAuthentication.isSupported)
            assertFalse(MacEmbeddedAuthentication.isAvailable())
        }
    }

    @Test
    fun noMatchingWindow_isUnavailable() {
        assertEquals(MacEmbeddedAuthentication.Result.Unavailable, MacEmbeddedAuthentication.authenticate(slot, "r", 5))
    }
}
