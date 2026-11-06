package app.tenshu.macos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MacLocalAuthentication] against the real machine — **without ever presenting a sheet**.
 *
 * ⚠ `authenticate` puts a real Touch ID / password dialog on screen and waits for a person, so it is
 * deliberately not exercised here on macOS; it was verified by hand (a sheet presented, the reply
 * block decoding `appCancel` after an `invalidate`). What a test can pin is that the class and the
 * `canEvaluatePolicy:error:` descriptor resolve — a wrong BOOL descriptor reads a register the call
 * never wrote, and the answer would be noise rather than an exception.
 *
 * Off macOS the documented degradation is pinned rather than skipped: unsupported, unavailable, and
 * `authenticate` answering [MacLocalAuthentication.Result.Unavailable] without touching anything.
 */
class MacLocalAuthenticationTest {

    /** Mirrors the bridge's own `isMac`; spelled the same way on purpose. */
    private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    @Test
    fun resolvesOnMacAndDegradesElsewhere() {
        if (mac && ForeignSupport.isAvailable()) {
            assertTrue(MacLocalAuthentication.isSupported, "LAContext did not resolve through the bridge")
            // Every Mac has a login password, so `.deviceOwnerAuthentication` can always be evaluated
            // for a logged-in user — `false` here means the call itself is mis-declared.
            assertTrue(MacLocalAuthentication.isAvailable(), "canEvaluatePolicy:error: answered no")
        } else {
            assertFalse(MacLocalAuthentication.isSupported)
            assertFalse(MacLocalAuthentication.isAvailable())
            assertEquals(MacLocalAuthentication.Result.Unavailable, MacLocalAuthentication.authenticate("r"))
        }
    }
}
