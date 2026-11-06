@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.delegate

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
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecAccessControlRef
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseOperationPrompt
import platform.Security.kSecValueData
import platform.Security.kSecAccessControlUserPresence

/**
 * Real [IosKeychain] over `Security.framework` generic-password items.
 *
 * Each item is a `kSecClassGenericPassword` keyed by ([service], account). [put] replaces any
 * existing item (delete-then-add, so it's idempotent); [get] reads the data back (returning `null`
 * for *any* non-success status, including the Face ID / passcode prompt being cancelled or denied —
 * the caller treats that as "no value", matching [AndroidSecureStorageDelegate]'s decrypt-failure
 * path); [delete] is idempotent.
 *
 * **Not headless-testable.** These calls require a running app / simulator with a Keychain; a plain
 * Kotlin/Native unit test has no Keychain and the gated items additionally need a device passcode
 * to be set. The bucket + seal/unseal logic is therefore unit-tested against a fake [IosKeychain]
 * (see `IosSecureStorageDelegateTest`); this implementation is exercised on the simulator / device.
 * This mirrors the desktop split, where the real-Keychain behaviour is likewise out of the Kotlin
 * fake-helper tests (`docs/proposals/desktop-masterkeystore.md` §8 Slice 2a).
 *
 * CoreFoundation objects created here are `+1`-owned and released after the `Sec*` call retains what
 * it needs (the mutable query dictionary retains every value added to it, so the per-value temporaries
 * are released as soon as they're inserted).
 */
internal class SecurityFrameworkKeychain(
    private val service: String,
) : IosKeychain {

    override fun put(account: String, data: ByteArray, protection: KeychainProtection) {
        // Replace semantics: Keychain forbids two items with the same primary key, so clear first.
        delete(account)

        val query = baseQuery(account) ?: return
        val cfData = makeCFData(data)
        CFDictionaryAddValue(query, kSecValueData, cfData)

        var access: SecAccessControlRef? = null
        when (protection) {
            KeychainProtection.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY ->
                CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)

            KeychainProtection.BIOMETRY_OR_PASSCODE_THIS_DEVICE_ONLY -> {
                // `userPresence` over `WhenPasscodeSetThisDeviceOnly`: presents Face ID / Touch ID
                // and falls back to the device passcode; the item ceases to exist if the passcode is
                // removed. Secure-Enclave-protected, never synced or backed up.
                access = SecAccessControlCreateWithFlags(
                    null,
                    kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                    kSecAccessControlUserPresence,
                    null,
                )
                if (access != null) CFDictionaryAddValue(query, kSecAttrAccessControl, access)
            }
        }

        SecItemAdd(query, null)

        if (cfData != null) CFRelease(cfData)
        if (access != null) CFRelease(access)
        CFRelease(query)
    }

    override fun get(account: String): ByteArray? = memScoped {
        val query = baseQuery(account) ?: return null
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        // A localized reason string shown on the Face ID / passcode sheet when the item is gated.
        val prompt = CFStringCreateWithCString(null, UNLOCK_PROMPT, kCFStringEncodingUTF8)
        CFDictionaryAddValue(query, kSecUseOperationPrompt, prompt)

        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)

        if (prompt != null) CFRelease(prompt)
        CFRelease(query)

        if (status != errSecSuccess) {
            // errSecItemNotFound, errSecUserCanceled, errSecAuthFailed, … → "no value".
            return null
        }
        val cfData: CFDataRef? = result.value?.reinterpret()
        val bytes = cfDataToByteArray(cfData)
        if (cfData != null) CFRelease(cfData)
        bytes
    }

    override fun delete(account: String) {
        val query = baseQuery(account) ?: return
        SecItemDelete(query)
        CFRelease(query)
    }

    /** A mutable query dict pre-populated with the class + (service, account) primary key. */
    private fun baseQuery(account: String): CFMutableDictionaryRef? {
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

        val cfAccount = CFStringCreateWithCString(null, account, kCFStringEncodingUTF8)
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
        const val UNLOCK_PROMPT = "Unlock your Tenshu realm key"
    }
}
