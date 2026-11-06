package app.tenshu.control.data

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Android [Reauthenticator] — a **keyless** `BiometricPrompt` accepting
 * `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` (`BIOMETRIC_WEAK` on API 28–29, see [promptAuthenticators]),
 * so it falls back to the device PIN on any device with a
 * secure lock screen (`control-keep-device-unlocked.md` §6.1, Ph-reauth).
 *
 * ⚠ No `CryptoObject`: this is a presence check, never a key operation — unlike
 * [AndroidBiometricUnlockDelegate], which unwraps a Keystore-held wrap key through the same API.
 * The two must not be confused: this one changes nothing if it fails.
 *
 * ⚠ `DEVICE_CREDENTIAL` and a negative button are mutually exclusive in `PromptInfo`, which is why
 * [ReauthPromptCopy] carries no negative-button label (the system sheet supplies its own cancel).
 *
 * This is also the **only** prompt implementation for the soft app-lock's gate overlay — the gate
 * calls straight into it rather than keeping a second copy of the same twenty lines.
 */
class AndroidReauthenticator : Reauthenticator {

    override suspend fun authenticate(
        host: BiometricPromptHost,
        copy: ReauthPromptCopy,
    ): AppLockAuthResult = withContext(Dispatchers.Main) {
        val activity = host.activity
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (continuation.isActive) continuation.resume(AppLockAuthResult.Success)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (continuation.isActive) continuation.resume(errorToResult(code))
                    }

                    // onAuthenticationFailed is a single mismatch, NOT a terminal state — the
                    // system sheet stays open and the user retries, so it must not resume.
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(copy.title)
                .setSubtitle(copy.subtitle)
                .setAllowedAuthenticators(promptAuthenticators())
                .build()
            prompt.authenticate(info)
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    /**
     * ⚠ `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` is **unsupported on API 28–29** — `PromptInfo.build()`
     * throws for it there — so those two levels take `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`, which
     * AndroidX supports on every level. This is a keyless presence check, so a class-2 biometric
     * costs nothing: the device PIN behind it is the same either way.
     */
    private fun promptAuthenticators(): Int {
        val biometric = if (appLockPromptNeedsWeakBiometric(Build.VERSION.SDK_INT)) {
            Authenticators.BIOMETRIC_WEAK
        } else {
            Authenticators.BIOMETRIC_STRONG
        }
        return biometric or Authenticators.DEVICE_CREDENTIAL
    }

    private fun errorToResult(code: Int): AppLockAuthResult = when (code) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        -> AppLockAuthResult.Cancelled

        // ⚠ No hardware and no enrolled credential are `Unavailable`, not `Failure`: the caller's
        // pass-through policy (`Reauthenticator.confirm`) turns exactly this into "proceed", which
        // is what keeps a device that cannot prompt from being locked out of its own phrase.
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        -> AppLockAuthResult.Unavailable

        // ⚠⚠ Lockout is a FAILURE, never Unavailable. Too many wrong attempts is precisely when the
        // gate must hold; mapping it to Unavailable would let it be defeated by failing it.
        else -> AppLockAuthResult.Failure
    }
}
