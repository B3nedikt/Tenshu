package app.tenshu.control.data

/**
 * UI-side biometric protection for the wrap-key half of Control's
 * local-unlock primitive. The proxy stores the recovery phrase wrapped
 * under a 32-byte wrap-key; this delegate biometric-protects the
 * wrap-key on the device.
 *
 * Per [`docs/tech-debt.md`](../../../../../../../docs/tech-debt.md)
 * §"Android Biometric Unlock — Missing, Undermines the Onboarding-Low-
 * Friction UX" + proposal §4.1: biometric is the primary unlock surface
 * on Android; passphrase is the secondary fallback. Desktop has no
 * platform biometric primitive today, so its impl returns
 * `isAvailable=false` — the UI falls back to passphrase-only.
 *
 * Caller contract for setup is **enroll-first**: the UI generates a
 * random wrap-key, calls [enrollWrapKey], and only on `Success` calls
 * `setUpBiometricUnlock(wrapKey)` on the proxy. A delegate failure
 * leaves the proxy untouched and the user can retry.
 *
 * Lives in `:control-shared-logic` (Compose-free) so the iOS app can bind the
 * unlock VMs; the Compose-tree resolver [rememberBiometricPromptHost] stays in the
 * Compose `:control-shared`.
 */
interface BiometricUnlockDelegate {

    /**
     * Whether the device exposes a biometric authenticator strong enough
     * for the wrap-key Keystore key (Android: `BIOMETRIC_STRONG`). Does
     * **not** imply the user has enrolled — see [hasEnrolled].
     *
     * `suspend` because the desktop impl shells out to the Swift keychain
     * helper (a subprocess round-trip); callers must probe off the UI
     * thread so composing a biometric-gated screen never stalls. Android's
     * impl is main-safe but conforms to the same shape.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Whether [enrollWrapKey] has completed successfully on this device
     * and the wrap-key blob is still readable (Keystore key not
     * invalidated by re-enrollment). True only if both the platform-
     * specific stored blob exists and the underlying Keystore key is
     * intact at check time.
     *
     * `suspend` for the same reason as [isAvailable] — the desktop helper
     * shell-out must not block the UI thread.
     */
    suspend fun hasEnrolled(): Boolean

    /**
     * Presents a `BiometricPrompt` and, on successful authentication,
     * persists [wrapKey] encrypted under a Keystore-backed AES/GCM key.
     * Idempotent: re-enrolling overwrites any previously stored blob.
     *
     * On Android the Keystore key is created with
     * `setUserAuthenticationRequired(true)` +
     * `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` +
     * `setInvalidatedByBiometricEnrollment(true)`.
     */
    suspend fun enrollWrapKey(
        wrapKey: ByteArray,
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): EnrollResult

    /**
     * Presents a `BiometricPrompt` and, on successful authentication,
     * decrypts the stored wrap-key blob and returns the plaintext
     * wrap-key. Returns [UnwrapResult.NotAvailable] when no blob is
     * stored, [UnwrapResult.KeyInvalidated] when the Keystore key was
     * invalidated by a biometric re-enrollment, [UnwrapResult.CancelledByUser]
     * when the user cancels the prompt.
     */
    suspend fun unwrapWrapKey(
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): UnwrapResult

    /**
     * Removes the Keystore key + stored blob. Idempotent. Pairs with
     * `SyncService.clearBiometricSetup()` (proxy side) when the user
     * disables biometric unlock or recovers from an invalidated key.
     */
    fun clear()
}

/**
 * Copy bundle for the `BiometricPrompt` UI. Title is mandatory; the
 * other strings are optional. [negativeButton] is the label the user
 * taps to cancel out of biometric and fall back to passphrase
 * (Android requires a non-empty label here when the prompt isn't
 * device-credential-fallback).
 */
data class BiometricPromptCopy(
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val negativeButton: String,
)

/**
 * Outcome of [BiometricUnlockDelegate.enrollWrapKey]. [Failure.message]
 * surfaces the platform-reported error string for diagnostic UI.
 */
sealed interface EnrollResult {
    data object Success : EnrollResult
    data object CancelledByUser : EnrollResult
    data object NotAvailable : EnrollResult
    data class Failure(val message: String? = null) : EnrollResult
}

/**
 * Outcome of [BiometricUnlockDelegate.unwrapWrapKey]. [Success.wrapKey]
 * carries the decrypted bytes the caller passes to
 * `SyncService.unlockBiometric(wrapKey)`.
 */
sealed interface UnwrapResult {
    data class Success(val wrapKey: ByteArray) : UnwrapResult {
        // ByteArray's structural equality is reference-based; spell it
        // out so test assertions and reflection-based equality (e.g.
        // `assertEquals(Success(a), Success(b))`) behave as expected.
        override fun equals(other: Any?): Boolean =
            other is Success && wrapKey.contentEquals(other.wrapKey)

        override fun hashCode(): Int = wrapKey.contentHashCode()
    }

    data object CancelledByUser : UnwrapResult
    data object NotAvailable : UnwrapResult
    data object KeyInvalidated : UnwrapResult
    data class Failure(val message: String? = null) : UnwrapResult
}

/**
 * Platform-specific carrier for the activity/window context that
 * `BiometricPrompt` needs to attach to. On Android this wraps a
 * `FragmentActivity`; on desktop and iOS it is an inert no-arg marker.
 * Constructed by `rememberBiometricPromptHost` (the Compose resolver in
 * `:control-shared`) from the Compose tree.
 *
 * The actual's constructor is **public** (the `:control-shared` resolver, a
 * separate module, builds it) while any backing field stays `internal` to this
 * module (only the delegate impl here reads it).
 */
expect class BiometricPromptHost
