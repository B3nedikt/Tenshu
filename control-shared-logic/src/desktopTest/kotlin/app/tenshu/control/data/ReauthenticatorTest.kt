package app.tenshu.control.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The pass-through policy of [confirm] is the whole safety argument for the re-auth check
 * (`control-keep-device-unlocked.md` §6.1), so it is tested directly rather than through a screen.
 *
 * ⚠⚠ The load-bearing case is `Unavailable ⇒ proceed`. The action being gated is *reading the
 * recovery phrase*; failing closed on a device that cannot prompt would not merely hide it, it
 * would make it **unrecoverable there** — and that is the common path on desktop (the
 * macOS helper is opt-in + signing-gated; Windows and Linux have no backend at all).
 *
 * ⚠ The mirror case matters just as much: `Failure ⇒ refuse`. `BiometricPrompt` reports lockout
 * after too many wrong attempts as an error, and a gate that opened on failure could be defeated
 * by failing it.
 */
class ReauthenticatorTest {

    private val copy = ReauthPromptCopy(title = "Confirm it's you", subtitle = "why")

    private class Fixed(private val result: AppLockAuthResult) : Reauthenticator {
        var calls = 0
        override suspend fun authenticate(
            host: BiometricPromptHost,
            copy: ReauthPromptCopy,
        ): AppLockAuthResult {
            calls++
            return result
        }
    }

    @Test
    fun provingPresence_proceeds() = runTest {
        assertTrue(Fixed(AppLockAuthResult.Success).confirm(BiometricPromptHost(), copy))
    }

    @Test
    fun aDeviceThatCannotAsk_proceeds_ratherThanSealingThePhraseAway() = runTest {
        assertTrue(
            Fixed(AppLockAuthResult.Unavailable).confirm(BiometricPromptHost(), copy),
            "an unaskable device must not lose access to its own recovery phrase",
        )
    }

    @Test
    fun cancelling_refuses() = runTest {
        assertFalse(Fixed(AppLockAuthResult.Cancelled).confirm(BiometricPromptHost(), copy))
    }

    @Test
    fun failing_refuses_soLockoutCannotDefeatTheGate() = runTest {
        assertFalse(Fixed(AppLockAuthResult.Failure).confirm(BiometricPromptHost(), copy))
    }

    /**
     * A null host is "this Compose tree cannot carry a prompt" (Android UI not hosted by a
     * `FragmentActivity`) — the same shape of unaskable as `Unavailable`, and it must not even
     * attempt the prompt.
     */
    @Test
    fun noHost_proceedsWithoutPrompting() = runTest {
        val reauthenticator = Fixed(AppLockAuthResult.Cancelled)
        assertTrue(reauthenticator.confirm(host = null, copy = copy))
        assertEquals(0, reauthenticator.calls, "a missing host must not reach the backend")
    }

    /** No binding at all is the third unaskable shape — inert platforms bind nothing meaningful. */
    @Test
    fun noReauthenticator_proceeds() = runTest {
        val absent: Reauthenticator? = null
        assertTrue(absent.confirm(BiometricPromptHost(), copy))
    }

    @Test
    fun theInertBinding_reportsUnavailable_notFailure() = runTest {
        assertEquals(
            AppLockAuthResult.Unavailable,
            UnavailableReauthenticator.authenticate(BiometricPromptHost(), copy),
            "reporting Failure here would refuse the action on every inert platform",
        )
    }
}
