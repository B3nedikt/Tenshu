package app.tenshu.control.data

/**
 * Whether Control's soft app-lock can actually gate the UI on this device — i.e.
 * the OS can present a biometric **or** device-credential (PIN/pattern/password)
 * prompt. The soft lock (`docs/proposals/control-keep-device-unlocked.md` §4.2)
 * accepts `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`, so a device with only a secure
 * lock screen and no enrolled biometric still qualifies.
 *
 * When this is false the "Require unlock" toggle is shown disabled — there is no
 * secure lock screen to fall back on, so a gate would either be unenforceable or
 * lock the user out entirely. The gate passes through in the same state
 * ([AppLockController.passIfUnenforceable]): the preference defaults on whether
 * or not this is true, so the disabled toggle alone does not keep the gate away.
 */
interface AppLockAvailability {
    suspend fun isAvailable(): Boolean
}

/**
 * Inert binding for platforms where the soft app-lock is not wired yet
 * (desktop, iOS — Ph1 is Control-on-Android only; the mirrors are a later phase).
 */
object UnavailableAppLock : AppLockAvailability {
    override suspend fun isAvailable(): Boolean = false
}

/**
 * Whether Android's app-lock prompt must ask for `BIOMETRIC_WEAK` instead of `BIOMETRIC_STRONG`
 * alongside `DEVICE_CREDENTIAL`: AndroidX rejects the strong combination on API 28–29 only.
 * In common code so the rule is pinned by a JVM test; `AndroidReauthenticator` is its one caller.
 */
internal fun appLockPromptNeedsWeakBiometric(sdkInt: Int): Boolean = sdkInt in 28..29
