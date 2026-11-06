package app.tenshu.control.ui.screens.sync

import app.tenshu.control.data.AppLockAuthResult
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.Reauthenticator

/**
 * Ph-reauth double (`control-keep-device-unlocked.md` §6.1): a fixed verdict plus a call count, so
 * a test can assert the screen **asked** rather than only that it honoured an answer nobody
 * requested — the difference between a guard and a coincidence.
 *
 * Shared by [RecoveryPhraseScreenTest] and [SyncDashboardScreenTest]; both gate on the same
 * primitive, and two private copies in one package do not even compile.
 */
internal class FakeReauthenticator(private val result: AppLockAuthResult) : Reauthenticator {
    var calls = 0
        private set

    override suspend fun authenticate(
        host: BiometricPromptHost,
        copy: ReauthPromptCopy,
    ): AppLockAuthResult {
        calls++
        return result
    }
}
