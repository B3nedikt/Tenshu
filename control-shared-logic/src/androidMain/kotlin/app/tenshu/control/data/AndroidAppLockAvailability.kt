package app.tenshu.control.data

import android.app.KeyguardManager
import android.content.Context

/**
 * Android [AppLockAvailability] — the soft app-lock can gate the UI when the device has a **secure
 * lock screen** (PIN, pattern or password; biometrics always sit on top of one). That is exactly
 * when [AndroidReauthenticator]'s `… | DEVICE_CREDENTIAL` prompt can be satisfied, so a PIN-only
 * device qualifies. Unlike [AndroidBiometricUnlockDelegate.isAvailable] it needs no enrolled
 * biometric, because it wraps no Keystore key.
 *
 * ⚠⚠ Asked of `KeyguardManager`, never of `BiometricManager.canAuthenticate(STRONG | DEVICE_CREDENTIAL)`:
 * AndroidX does not support that combination on API 28–29 and reports it as non-success **whatever
 * the device has enrolled**. Since the gate passes through when this is false
 * ([AppLockController.passIfUnenforceable]), the `canAuthenticate` form opened Control without any
 * prompt on every Android 9/10 phone that *has* a PIN.
 */
class AndroidAppLockAvailability(
    private val context: Context,
) : AppLockAvailability {

    override suspend fun isAvailable(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
}
