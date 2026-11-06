@file:OptIn(ExperimentalForeignApi::class)

package app.tenshu.control.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled
import platform.Security.kSecAccessControlUserPresence
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationUI
import platform.Security.kSecUseAuthenticationUISkip
import platform.Security.kSecUseOperationPrompt
import platform.Security.kSecValueData

/**
 * Real iOS [BiometricUnlockDelegate] — the Face ID / Touch ID unlock surface for Control-on-iOS's
 * returning-user unlock screen (`LockedView`), the sibling of [AndroidBiometricUnlockDelegate].
 *
 * ## The wrap-key model (shared with Android)
 * The proxy's local-unlock primitive stores the realm master-key material wrapped under a random
 * 32-byte **wrap-key** (`SyncManager.setUpBiometricUnlock` adds a `BIOMETRIC_WRAPPER` alongside the
 * always-present passphrase `UNLOCK_WRAPPER`). This delegate's only job is to biometric-protect
 * that wrap-key **on the device**. On Android that protection is an `AndroidKeyStore` cipher gated
 * by `BiometricPrompt`; on iOS it is a `Security.framework` generic-password item whose
 * `SecAccessControl` requires user presence — so reading it back presents the OS Face ID / Touch ID
 * (passcode-fallback) sheet in-process, no Swift helper (`LocalAuthentication` + the Keychain are
 * callable directly from Kotlin/Native — see the sibling
 * `org.tenshu.core.common.delegate.IosSecureStorageDelegate`).
 *
 * ## The passcode fallback + enrollment changes
 * The item is created with [kSecAccessControlUserPresence] over
 * [kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly] — the same gate `IosSecureStorageDelegate` uses
 * and the same primitive the soft "Require unlock" app-lock evaluates. That means:
 *  - Face ID / Touch ID is offered first, with the **device passcode** as the fallback — never the
 *    Tenshu passphrase, which stays the universal way back in via `unlockLocal`.
 *  - Unlike Android's `setInvalidatedByBiometricEnrollment(true)` (and iOS `biometryCurrentSet`), a
 *    later biometric enrollment change does **not** invalidate the item, so there is no
 *    [UnwrapResult.KeyInvalidated] path here — the wrap-key survives adding a fingerprint /
 *    re-enrolling a face. Removing the device passcode deletes the item, and the next
 *    [unwrapWrapKey] then reports [UnwrapResult.NotAvailable], falling the UI back to the passphrase.
 *
 * ## Threading
 * `SecItemCopyMatching` on a gated item **blocks** the calling thread while the OS shows the auth
 * sheet, and `SecItemAdd` can touch the Secure Enclave, so every Keychain call runs on
 * [Dispatchers.Default] — the `suspend` methods are invoked from the unlock view model's main scope.
 *
 * ## Not simulator-testable
 * A gated Keychain item needs a device passcode (and biometrics need real hardware), so this is
 * exercised on device; there is no headless unit test — matching the
 * `org.tenshu.core.common.delegate.SecurityFrameworkKeychain` split.
 */
class IosBiometricUnlockDelegate(
    private val service: String = DEFAULT_SERVICE,
) : BiometricUnlockDelegate {

    /**
     * True when the device can present a biometric-or-passcode prompt (a secure lock screen exists).
     * `canEvaluatePolicy` is a cheap, synchronous, non-prompting probe, so it runs inline.
     */
    override suspend fun isAvailable(): Boolean =
        LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)

    /**
     * Whether an enrolled wrap-key blob is present — checked **without** presenting the auth sheet
     * (this runs on every locked-screen entry). [kSecUseAuthenticationUISkip] makes the lookup of a
     * gated item resolve to `errSecInteractionNotAllowed` (present, auth would be required) or
     * `errSecSuccess`, rather than prompting; only `errSecItemNotFound` means "not enrolled".
     */
    override suspend fun hasEnrolled(): Boolean = withContext(Dispatchers.Default) {
        val query = baseQuery() ?: return@withContext false
        CFDictionaryAddValue(query, kSecUseAuthenticationUI, kSecUseAuthenticationUISkip)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val status = SecItemCopyMatching(query, null)
        CFRelease(query)
        status != errSecItemNotFound
    }

    /**
     * Persists [wrapKey] as a user-presence-gated Keychain item (replacing any prior blob). No
     * separate biometric prompt is shown at enrollment — the gate is enforced on the *read* in
     * [unwrapWrapKey]; a failed [SecItemAdd] (e.g. no device passcode set) surfaces as
     * [EnrollResult.Failure] so the caller never records a phantom enrollment.
     */
    override suspend fun enrollWrapKey(
        wrapKey: ByteArray,
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): EnrollResult = withContext(Dispatchers.Default) {
        if (!LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)) {
            return@withContext EnrollResult.NotAvailable
        }
        // Fresh start: never leave a stale blob under the account.
        deleteItem()

        val access = SecAccessControlCreateWithFlags(
            null,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            kSecAccessControlUserPresence,
            null,
        ) ?: return@withContext EnrollResult.Failure("Could not create the biometric access control.")

        val query = baseQuery() ?: run {
            CFRelease(access)
            return@withContext EnrollResult.Failure("Could not build the Keychain query.")
        }
        val cfData = makeCFData(wrapKey)
        CFDictionaryAddValue(query, kSecValueData, cfData)
        CFDictionaryAddValue(query, kSecAttrAccessControl, access)

        val status = SecItemAdd(query, null)

        if (cfData != null) CFRelease(cfData)
        CFRelease(access)
        CFRelease(query)

        when (status) {
            errSecSuccess -> EnrollResult.Success
            errSecUserCanceled -> EnrollResult.CancelledByUser
            else -> EnrollResult.Failure("Keychain enrollment failed (OSStatus $status).")
        }
    }

    /**
     * Presents the Face ID / Touch ID / passcode sheet and, on success, returns the decrypted
     * wrap-key for `SyncManager.unlockBiometric`. Cancellation → [UnwrapResult.CancelledByUser]; a
     * missing blob (never enrolled, or auto-removed because the passcode was cleared) →
     * [UnwrapResult.NotAvailable]; anything else → [UnwrapResult.Failure].
     */
    override suspend fun unwrapWrapKey(
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): UnwrapResult = withContext(Dispatchers.Default) {
        val query = baseQuery() ?: return@withContext UnwrapResult.NotAvailable
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

        // The localized reason shown on the sheet: prefer the full-sentence subtitle, else the title.
        val reason = copy.subtitle ?: copy.title
        val prompt = CFStringCreateWithCString(null, reason, kCFStringEncodingUTF8)
        if (prompt != null) CFDictionaryAddValue(query, kSecUseOperationPrompt, prompt)

        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)

            if (prompt != null) CFRelease(prompt)
            CFRelease(query)

            when (status) {
                errSecSuccess -> {
                    val cfData: CFDataRef? = result.value?.reinterpret()
                    val bytes = cfDataToByteArray(cfData)
                    if (cfData != null) CFRelease(cfData)
                    if (bytes.isEmpty()) UnwrapResult.NotAvailable else UnwrapResult.Success(bytes)
                }
                errSecUserCanceled -> UnwrapResult.CancelledByUser
                errSecItemNotFound -> UnwrapResult.NotAvailable
                else -> UnwrapResult.Failure("Biometric unlock failed (OSStatus $status).")
            }
        }
    }

    /** Removes the stored wrap-key blob. Idempotent — pairs with `SyncService.clearBiometricSetup()`. */
    override fun clear() {
        deleteItem()
    }

    private fun deleteItem() {
        val query = baseQuery() ?: return
        SecItemDelete(query)
        CFRelease(query)
    }

    /** A mutable query dict pre-populated with the class + (service, account) primary key. */
    private fun baseQuery(): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: return null
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)

        val cfService = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8)
        CFDictionaryAddValue(dict, kSecAttrService, cfService)
        if (cfService != null) CFRelease(cfService)

        val cfAccount = CFStringCreateWithCString(null, ACCOUNT, kCFStringEncodingUTF8)
        CFDictionaryAddValue(dict, kSecAttrAccount, cfAccount)
        if (cfAccount != null) CFRelease(cfAccount)

        return dict
    }

    private fun makeCFData(data: ByteArray): CFDataRef? =
        if (data.isEmpty()) {
            CFDataCreate(null, null, 0)
        } else {
            data.usePinned { pinned ->
                CFDataCreate(null, pinned.addressOf(0).reinterpret(), data.size.convert())
            }
        }

    private fun cfDataToByteArray(cfData: CFDataRef?): ByteArray {
        if (cfData == null) return ByteArray(0)
        val length = CFDataGetLength(cfData).toInt()
        if (length <= 0) return ByteArray(0)
        val ptr = CFDataGetBytePtr(cfData) ?: return ByteArray(0)
        val out = ByteArray(length)
        for (i in 0 until length) out[i] = ptr[i].toByte()
        return out
    }

    private companion object {
        const val DEFAULT_SERVICE = "app.tenshu.control.ios.biometric-unlock"
        const val ACCOUNT = "wrap_key"
    }
}
