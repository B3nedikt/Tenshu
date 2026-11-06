package org.tenshu.core.common.delegate

import org.tenshu.core.common.security.AeadAuthenticationException
import org.tenshu.core.common.security.AeadBackend
import org.tenshu.core.common.util.nextSecureRandomBytes

/**
 * iOS [SecureStorageDelegate] — the iOS analog of the desktop `MasterKeyStore`
 * (see `docs/proposals/desktop-masterkeystore.md` §9 "iOS `MasterKeyStore` backend") and the
 * sibling of [AndroidSecureStorageDelegate]. Closes O6 (key custody) of the Control-on-iOS roadmap
 * (`docs/proposals/ios-control-handover.md` C1): Control-on-iOS holds the realm master key, so it
 * needs at-rest custody that survives app restart.
 *
 * **Two buckets**, mirroring the Android impl:
 *  - **Non-secret strings** (device id, last-sync timestamp) → a plaintext Keychain item, no prompt
 *    ([KeychainProtection.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY]). The Keychain already encrypts these
 *    at rest under hardware keys; we don't seal them again because nothing here is secret.
 *  - **Secrets** (recovery phrase A9, owner id, master-key wrappers over A1/A2) → AEAD-sealed with
 *    [AeadBackend] (libsodium XChaCha20-Poly1305) under a single 32-byte **wrap key**, then stored as
 *    a Keychain item. The wrap key concentrates the access gate on one item, exactly as the desktop
 *    `DesktopMasterKeyStore` + `WrappingKeyStore` does (one prompt unlocks the whole bucket).
 *
 * **The Face ID / passcode gate.** When [gateSecretBucket] is true, the wrap-key Keychain item is
 * created Secure-Enclave / `SecAccessControl`-gated
 * ([KeychainProtection.BIOMETRY_OR_PASSCODE_THIS_DEVICE_ONLY]) so the first read of the wrap key
 * presents the OS Face ID / Touch ID / passcode sheet in-process (no Swift helper — unlike desktop,
 * `LocalAuthentication` is callable directly from Kotlin/Native). The unwrapped key is then cached
 * in memory for the process lifetime, so the gate prompts once per launch rather than once per
 * secret read. C1 ships this **capability**; deciding *when* to flip it on (and threading it through
 * an unlock UI) is the SwiftUI-onboarding concern of C2. The cross-platform passphrase floor
 * (`UNLOCK_WRAPPER`, Argon2id) remains the universal way back in, so the biometric is never the sole
 * credential — the same invariant the desktop store keeps.
 *
 * **Failure handling** matches Android: [getSecret] returns `null` on any decrypt failure (missing
 * wrap key, tampered ciphertext, foreign restore) rather than throwing, so the caller re-onboards /
 * recovers instead of crashing. `ThisDeviceOnly` accessibility also keeps these items out of iCloud
 * Keychain and device-to-device transfer, so a restore onto a new device can't carry stale,
 * undecryptable blobs.
 *
 * Consumed via Koin constructor injection (it is not a `TComponent`), like every other
 * [SecureStorageDelegate]. Production wiring lands with the iOS DI in C2; C1 delivers + unit-tests
 * the backend.
 */
class IosSecureStorageDelegate internal constructor(
    private val keychain: IosKeychain,
    private val aead: AeadBackend,
    private val gateSecretBucket: Boolean,
    private val randomBytes: (Int) -> ByteArray,
) : SecureStorageDelegate {

    /**
     * Production constructor. [aead] is the libsodium-backed [AeadBackend] from `cryptoBackendsModule()`;
     * [serviceName] namespaces the Keychain items (the `kSecAttrService`); [gateSecretBucket] selects
     * the Face ID / passcode gate on the wrap key (see the class doc — default off until C2 wires the
     * unlock flow).
     */
    constructor(
        aead: AeadBackend,
        serviceName: String = DEFAULT_SERVICE,
        gateSecretBucket: Boolean = false,
    ) : this(SecurityFrameworkKeychain(serviceName), aead, gateSecretBucket, ::nextSecureRandomBytes)

    // Cached, decrypted wrap key. Populated on first wrap-key access so the gated mode prompts at
    // most once per process. Never persisted — the Keychain item is the source of truth.
    private var cachedWrapKey: ByteArray? = null

    override fun storeSecret(key: String, value: String) {
        val sealed = aead.encrypt(value.encodeToByteArray(), loadOrCreateWrapKey())
        keychain.put(secretAccount(key), sealed, KeychainProtection.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)
    }

    override fun getSecret(key: String): String? {
        val sealed = keychain.get(secretAccount(key)) ?: return null
        // Don't *create* a wrap key on a read: a sealed secret with no wrap key is unrecoverable, so
        // degrade to "no secret" (re-onboard) exactly like the Android decrypt-failure path.
        val wrapKey = existingWrapKey() ?: return null
        return try {
            aead.decrypt(sealed, wrapKey).decodeToString()
        } catch (_: AeadAuthenticationException) {
            // Tampered ciphertext or a wrap key that no longer matches (e.g. a foreign restore).
            null
        } catch (_: IllegalArgumentException) {
            // Truncated / malformed blob (shorter than nonce+tag).
            null
        }
    }

    override fun storeString(key: String, value: String) {
        keychain.put(plainAccount(key), value.encodeToByteArray(), KeychainProtection.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)
    }

    override fun getString(key: String): String? =
        keychain.get(plainAccount(key))?.decodeToString()

    override fun remove(key: String) {
        keychain.delete(secretAccount(key))
        keychain.delete(plainAccount(key))
    }

    private fun loadOrCreateWrapKey(): ByteArray {
        existingWrapKey()?.let { return it }
        val fresh = randomBytes(AeadBackend.AEAD_KEY_SIZE)
        val protection = if (gateSecretBucket) {
            KeychainProtection.BIOMETRY_OR_PASSCODE_THIS_DEVICE_ONLY
        } else {
            KeychainProtection.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY
        }
        keychain.put(ACCOUNT_WRAP_KEY, fresh, protection)
        cachedWrapKey = fresh
        return fresh
    }

    private fun existingWrapKey(): ByteArray? {
        cachedWrapKey?.let { return it }
        // The first read of a gated wrap key triggers the Face ID / passcode sheet (driven by the
        // Keychain `SecAccessControl` on the item, presented in-process).
        return keychain.get(ACCOUNT_WRAP_KEY)?.also { cachedWrapKey = it }
    }

    private fun secretAccount(key: String) = "$SECRET_PREFIX$key"

    private fun plainAccount(key: String) = "$PLAIN_PREFIX$key"

    companion object {
        /** Default `kSecAttrService` namespace for the Control-on-iOS realm-device custody store. */
        const val DEFAULT_SERVICE = "app.tenshu.control.masterkeystore"

        // Account namespaces keep the three item classes (sealed secrets, plain strings, the wrap
        // key) from colliding within the one service.
        private const val SECRET_PREFIX = "secret."
        private const val PLAIN_PREFIX = "plain."
        private const val ACCOUNT_WRAP_KEY = "__wrap_key__"
    }
}

/**
 * Protection class for a Keychain item. Maps onto `kSecAttrAccessible*` / `kSecAttrAccessControl`.
 */
internal enum class KeychainProtection {
    /**
     * Device-bound, readable after the first unlock since boot, **never prompts**, excluded from
     * iCloud Keychain and device transfer. The at-rest baseline for non-secrets and for the wrap key
     * when the biometric gate is off.
     */
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,

    /**
     * Secure-Enclave / `SecAccessControl`-gated: **every read presents Face ID / Touch ID / passcode**
     * (`kSecAccessControlUserPresence` over `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`). Used
     * for the wrap key when the gate is on; requires a device passcode to be set.
     */
    BIOMETRY_OR_PASSCODE_THIS_DEVICE_ONLY,
}

/**
 * Thin seam over the iOS Keychain so [IosSecureStorageDelegate]'s bucket + seal/unseal logic is
 * unit-testable with an in-memory fake. The real implementation ([SecurityFrameworkKeychain]) calls
 * `Security.framework`, which needs a host app / simulator and cannot run in a plain headless unit
 * test — the same real-Keychain caveat the desktop helper carries.
 */
internal interface IosKeychain {

    /** Store [data] under [account], replacing any existing item, with the given [protection]. */
    fun put(account: String, data: ByteArray, protection: KeychainProtection)

    /** Read the item under [account], or `null` if absent (or the read was cancelled / denied). */
    fun get(account: String): ByteArray?

    /** Delete the item under [account]. Idempotent. */
    fun delete(account: String)
}
