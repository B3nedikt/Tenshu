@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.epc

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemDelete
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * Writes the **shared-Keychain `epc-session` item** (Control-on-iOS C3, the original *provisioning
 * bridge*, `docs/proposals/ios-control-handover.md` §C3).
 *
 * ⚠ Its first payload — an opaque **presence token** Drive's and Fit's gates once read to detect a
 * co-resident Control — is **retired** (2026-11-05): route 1 routes on the key material
 * [RealmKeyMaterialBridge] publishes, and nothing mints the token any more. The item survives for
 * one writer, `ControlSurface.publishEpcInvite` (Control's "Connect an app" host publishing its live
 * `tenshu://epc/pair#…` invite), and [clear] still deletes it on every identity-destroying event —
 * which is also what sweeps a token a pre-retirement build left on a test phone.
 *
 * **Coordinates:** class `kSecClassGenericPassword`, service [SERVICE], account [ACCOUNT], access
 * group [ACCESS_GROUP]; value = the string as raw UTF-8 bytes.
 *
 * **Cross-app sharing is signing-gated.** The shared `app.tenshu.shared` access group requires both
 * apps to be signed under the same Team ID and to declare the `keychain-access-groups` entitlement.
 * On the unsigned simulator [provision] gets `errSecMissingEntitlement` for the shared group and
 * falls back to an app-local write.
 */
class ControlSessionProvisioner internal constructor(
    private val keychain: SharedKeychainWriter,
) {
    constructor() : this(SecuritySharedKeychainWriter())

    /** Write (replacing any prior) the `epc-session` item. Returns true on a successful store. */
    fun provision(handle: String): Boolean =
        keychain.write(SERVICE, ACCOUNT, ACCESS_GROUP, handle.encodeToByteArray())

    /** Remove the `epc-session` item (on reset / sign-out / forget — see `ControlSurface.clearDriveSession`). */
    fun clear() = keychain.delete(SERVICE, ACCOUNT, ACCESS_GROUP)

    companion object {
        /** `kSecAttrService`. */
        const val SERVICE = "app.tenshu.control.session"

        /** `kSecAttrAccount`. */
        const val ACCOUNT = "epc-session"

        /**
         * `kSecAttrAccessGroup` — the shared group both apps' entitlements must declare.
         *
         * On a **signed device build** the effective group is Team-prefixed
         * (`<TeamID>.app.tenshu.shared`, `ios-signing.md` §2); the app's Info.plist carries that
         * value under [ACCESS_GROUP_INFO_PLIST_KEY] (threaded from `project.yml`'s
         * `TENSHU_SHARED_KEYCHAIN_GROUP` build setting). The bare fallback is the ad-hoc simulator
         * config, where signing has no team and both apps declare the bare group literally.
         */
        val ACCESS_GROUP: String by lazy {
            (platform.Foundation.NSBundle.mainBundle
                .objectForInfoDictionaryKey(ACCESS_GROUP_INFO_PLIST_KEY) as? String)
                // Guard an unexpanded `$(…)` placeholder (e.g. an Info.plist built without the setting).
                ?.takeIf { it.isNotBlank() && !it.startsWith("$") }
                ?: BARE_ACCESS_GROUP
        }

        /** The un-prefixed group id — the simulator value and the suffix of the device value. */
        const val BARE_ACCESS_GROUP = "app.tenshu.shared"

        /** Info.plist key carrying the build's effective shared Keychain access group. */
        const val ACCESS_GROUP_INFO_PLIST_KEY = "TenshuSharedKeychainGroup"
    }
}

/**
 * The narrow Keychain write seam the provisioner needs — split out so the provisioner's coordinates +
 * handle logic are unit-testable against a fake (the real `Sec*` calls need a running Keychain, exactly
 * as [org.tenshu.core.common.delegate.SecurityFrameworkKeychain] is).
 */
internal interface SharedKeychainWriter {
    /**
     * Add/replace a `kSecClassGenericPassword` item keyed by ([service], [account]) in [accessGroup],
     * with [value] as its data. Returns true on success. Retries app-local (no access group) when the
     * shared-group write fails with a missing-entitlement status (the unsigned-simulator case).
     */
    fun write(service: String, account: String, accessGroup: String, value: ByteArray): Boolean

    /** Delete the item; idempotent. Tries the shared group then app-local, matching [write]. */
    fun delete(service: String, account: String, accessGroup: String)
}

/** Real [SharedKeychainWriter] over `Security.framework`. Not headless-testable (needs a Keychain). */
internal class SecuritySharedKeychainWriter : SharedKeychainWriter {

    override fun write(service: String, account: String, accessGroup: String, value: ByteArray): Boolean {
        deleteInGroup(service, account, accessGroup)
        val status = add(service, account, accessGroup, value)
        if (status == errSecMissingEntitlement) {
            // Unsigned simulator: the process can't claim the shared access group. Fall back to an
            // app-local item so onboarding completes — the cross-app read still needs a signed build.
            deleteInGroup(service, account, null)
            return add(service, account, null, value) == errSecSuccess
        }
        return status == errSecSuccess
    }

    override fun delete(service: String, account: String, accessGroup: String) {
        deleteInGroup(service, account, accessGroup)
        deleteInGroup(service, account, null)
    }

    private fun add(service: String, account: String, accessGroup: String?, value: ByteArray): OSStatus {
        val query = baseQuery(service, account, accessGroup) ?: return errSecMissingEntitlement
        val cfData = makeCFData(value)
        CFDictionaryAddValue(query, kSecValueData, cfData)
        // Device-bound, non-prompting, excluded from iCloud Keychain / device transfer.
        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)

        val status = SecItemAdd(query, null)

        if (cfData != null) CFRelease(cfData)
        CFRelease(query)
        return status
    }

    private fun deleteInGroup(service: String, account: String, accessGroup: String?) {
        val query = baseQuery(service, account, accessGroup) ?: return
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun baseQuery(service: String, account: String, accessGroup: String?): CFMutableDictionaryRef? {
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

        if (accessGroup != null) {
            val cfGroup = CFStringCreateWithCString(null, accessGroup, kCFStringEncodingUTF8)
            CFDictionaryAddValue(dict, kSecAttrAccessGroup, cfGroup)
            if (cfGroup != null) CFRelease(cfGroup)
        }
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
}
