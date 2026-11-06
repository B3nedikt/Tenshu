@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.epc

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
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
import platform.Security.SecItemCopyMatching
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData

/**
 * The realm key material Control publishes into the shared `app.tenshu.shared` Keychain group, and
 * Drive reads, for the **route-1 in-process collapse** (`ios-control-handover.md` §C4e and
 * §"Route-1 write key custody": *durable shared-Keychain* custody). With these in Drive's process,
 * Drive — the app the user is actively touching — can encrypt + upload sync blocks **itself** while
 * it is foreground, so a write syncs instantly instead of waiting for the (suspended) Control to wake
 * (the §11.2/§11.5 wake-window). Holding them makes Drive a **key-holding realm device** for the
 * first-party same-device case only (the "Drive is keyless" rule remains for route 2 / remote-proxy /
 * third-party). Each item is byte-for-byte the value Control already holds — no re-derivation.
 */
class RealmKeyMaterial(
    /** A13-plane `sharedIdentityKey` (encrypts metadata blocks). 32 bytes. */
    val sharedIdentityKey: ByteArray,
    /** Volume key (AEAD-encrypts file content chunks). 32 bytes. */
    val sharedVolumeKey: ByteArray,
    /** A4 device signing key, the exact `Base64(pub)|Base64(priv)` blob, as UTF-8 bytes. */
    val deviceSigningBlob: ByteArray,
    /** Realm owner id (public; the JWT `iss` + a block's `ownerId`). */
    val ownerId: String,
    /**
     * This realm device's id (public; the JWT `sub`). ⚠ Informational: the id is derived from
     * [deviceSigningBlob]'s public key, and consumers derive it from the blob (`keyDerivedDeviceId`)
     * rather than trusting this copy, which a partial publish could leave stale.
     */
    val deviceId: String,
    /**
     * A13 — the realm's X25519 encryption keypair: what seals contact invites and
     * unwraps shared-realm keys. Without it the sharing cluster is read-only on route-1
     * Drive ("Identity key (A13) unavailable"). Null for material published without it;
     * absent → Drive adopts without A13 and sharing write-paths fail loudly.
     */
    val realmEncryptionPublicKey: ByteArray? = null,
    val realmEncryptionPrivateKey: ByteArray? = null,
)

/**
 * Publishes / reads [RealmKeyMaterial] across the shared App-Group Keychain (the durable hand-off the
 * route-1 key-custody decision selected). The cross-app read is the mechanism of
 * `ios-control-handover.md` §C3, which works on the ad-hoc simulator — both apps ad-hoc-signed into
 * the bare `app.tenshu.shared` group — and falls back to an app-local item when the shared group is
 * unavailable (unsigned build), mirroring [ControlSessionProvisioner]. Items are
 * `AfterFirstUnlockThisDeviceOnly`, device-bound, not synced to iCloud.
 *
 * ⚠ This durably stores realm key material in a location a second first-party app reads — see
 * the threat model's A4 custody amendment (route-1 key hand-off).
 */
object RealmKeyMaterialBridge {

    /** Shared `kSecAttrService` for every item. */
    const val SERVICE = "app.tenshu.realm.keys"

    /**
     * The shared access group both apps' entitlements declare (Team-prefixed on device;
     * `ios-control-handover.md` §C3).
     */
    val ACCESS_GROUP: String get() = ControlSessionProvisioner.ACCESS_GROUP

    private const val ACCOUNT_IDENTITY = "shared-identity-key"
    private const val ACCOUNT_VOLUME = "shared-volume-key"
    private const val ACCOUNT_DEVICE = "device-signing-key"
    private const val ACCOUNT_OWNER_ID = "owner-id"
    private const val ACCOUNT_DEVICE_ID = "device-id"
    private const val ACCOUNT_A13_PUBLIC = "realm-encryption-public"
    private const val ACCOUNT_A13_PRIVATE = "realm-encryption-private"

    private val writer: SharedKeychainWriter = SecuritySharedKeychainWriter()
    private val reader: SharedKeychainReader = SecuritySharedKeychainReader()

    /** Write all items into the shared group. Returns true only if every required write succeeds. */
    fun publish(material: RealmKeyMaterial): Boolean {
        val required =
            writer.write(SERVICE, ACCOUNT_IDENTITY, ACCESS_GROUP, material.sharedIdentityKey) &&
                writer.write(SERVICE, ACCOUNT_VOLUME, ACCESS_GROUP, material.sharedVolumeKey) &&
                writer.write(SERVICE, ACCOUNT_DEVICE, ACCESS_GROUP, material.deviceSigningBlob) &&
                writer.write(SERVICE, ACCOUNT_OWNER_ID, ACCESS_GROUP, material.ownerId.encodeToByteArray()) &&
                writer.write(SERVICE, ACCOUNT_DEVICE_ID, ACCESS_GROUP, material.deviceId.encodeToByteArray())
        // A13: optional — write when present, delete when absent (never leave a stale pair).
        val a13Pub = material.realmEncryptionPublicKey
        val a13Priv = material.realmEncryptionPrivateKey
        if (a13Pub != null && a13Priv != null) {
            writer.write(SERVICE, ACCOUNT_A13_PUBLIC, ACCESS_GROUP, a13Pub)
            writer.write(SERVICE, ACCOUNT_A13_PRIVATE, ACCESS_GROUP, a13Priv)
        } else {
            writer.delete(SERVICE, ACCOUNT_A13_PUBLIC, ACCESS_GROUP)
            writer.delete(SERVICE, ACCOUNT_A13_PRIVATE, ACCESS_GROUP)
        }
        return required
    }

    /** Read all items back (cross-app from Drive), or null if any REQUIRED item is absent. */
    fun read(): RealmKeyMaterial? {
        val identity = reader.read(SERVICE, ACCOUNT_IDENTITY, ACCESS_GROUP) ?: return null
        val volume = reader.read(SERVICE, ACCOUNT_VOLUME, ACCESS_GROUP) ?: return null
        val device = reader.read(SERVICE, ACCOUNT_DEVICE, ACCESS_GROUP) ?: return null
        val ownerId = reader.read(SERVICE, ACCOUNT_OWNER_ID, ACCESS_GROUP)?.decodeToString() ?: return null
        val deviceId = reader.read(SERVICE, ACCOUNT_DEVICE_ID, ACCESS_GROUP)?.decodeToString() ?: return null
        return RealmKeyMaterial(
            sharedIdentityKey = identity,
            sharedVolumeKey = volume,
            deviceSigningBlob = device,
            ownerId = ownerId,
            deviceId = deviceId,
            realmEncryptionPublicKey = reader.read(SERVICE, ACCOUNT_A13_PUBLIC, ACCESS_GROUP),
            realmEncryptionPrivateKey = reader.read(SERVICE, ACCOUNT_A13_PRIVATE, ACCESS_GROUP),
        )
    }

    /** Remove all items (e.g. on realm reset / member removal). */
    fun clear() {
        writer.delete(SERVICE, ACCOUNT_IDENTITY, ACCESS_GROUP)
        writer.delete(SERVICE, ACCOUNT_VOLUME, ACCESS_GROUP)
        writer.delete(SERVICE, ACCOUNT_DEVICE, ACCESS_GROUP)
        writer.delete(SERVICE, ACCOUNT_OWNER_ID, ACCESS_GROUP)
        writer.delete(SERVICE, ACCOUNT_DEVICE_ID, ACCESS_GROUP)
        writer.delete(SERVICE, ACCOUNT_A13_PUBLIC, ACCESS_GROUP)
        writer.delete(SERVICE, ACCOUNT_A13_PRIVATE, ACCESS_GROUP)
    }

    /**
     * A short, **non-secret** fingerprint of a key item's bytes — its length plus a 16-bit fold — so a
     * harness can show that Drive read the *same* bytes Control wrote without rendering the key itself.
     * (A 16-bit fold of 32 secret bytes reveals nothing usable; it is a visual match aid only.)
     */
    fun fingerprint(bytes: ByteArray): String {
        var h = 0
        for (b in bytes) h = (h * 31 + (b.toInt() and 0xff)) and 0xffff
        return "${bytes.size}B/" + h.toString(16).padStart(4, '0')
    }
}

/** Narrow read seam mirroring [SharedKeychainWriter] — the real `Sec*` calls need a running Keychain. */
internal interface SharedKeychainReader {
    /** Read the item keyed by ([service], [account]) in [accessGroup], or app-local on fallback. */
    fun read(service: String, account: String, accessGroup: String): ByteArray?
}

/** Real [SharedKeychainReader] over `Security.framework` (`SecItemCopyMatching`). */
internal class SecuritySharedKeychainReader : SharedKeychainReader {

    override fun read(service: String, account: String, accessGroup: String): ByteArray? =
        readInGroup(service, account, accessGroup) ?: readInGroup(service, account, null)

    private fun readInGroup(service: String, account: String, accessGroup: String?): ByteArray? {
        val query = readQuery(service, account, accessGroup) ?: return null
        return memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, out.ptr)
            CFRelease(query)
            if (status != errSecSuccess) return@memScoped null
            val cfData: CFDataRef? = out.value?.reinterpret()
            val bytes = cfDataToByteArray(cfData)
            if (cfData != null) CFRelease(cfData)
            bytes
        }
    }

    private fun readQuery(service: String, account: String, accessGroup: String?): CFMutableDictionaryRef? {
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

        CFDictionaryAddValue(dict, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(dict, kSecMatchLimit, kSecMatchLimitOne)
        return dict
    }

    private fun cfDataToByteArray(cfData: CFDataRef?): ByteArray {
        if (cfData == null) return ByteArray(0)
        val len = CFDataGetLength(cfData).toInt()
        if (len <= 0) return ByteArray(0)
        val ptr = CFDataGetBytePtr(cfData) ?: return ByteArray(0)
        val out = ByteArray(len)
        for (i in 0 until len) out[i] = ptr[i].toByte()
        return out
    }
}
