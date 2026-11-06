package org.tenshu.core.common.delegate

import org.tenshu.core.common.security.AeadBackend
import org.tenshu.core.common.security.LibsodiumAeadBackend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Unit coverage for the iOS MasterKeyStore (Control-on-iOS C1,
// docs/proposals/ios-control-handover.md). Runs on the iosSimulatorArm64 test gate with the REAL
// libsodium AEAD backend but a FAKE Keychain — the real `Security.framework` Keychain needs a host
// app / simulator entitlements + a device passcode and is out of headless-test scope (same caveat
// the desktop helper carries; SecurityFrameworkKeychain is exercised on the simulator / device).
//
// The properties under test are the bucket discipline (secrets sealed, strings plaintext), the
// single concentrated wrap key, and the Android-parity "decrypt failure → null, never throw" rule.
class IosSecureStorageDelegateTest {

    // Records (data, protection) per account so tests can assert at-rest shape + the access gate.
    private class FakeKeychain : IosKeychain {
        data class Item(val data: ByteArray, val protection: KeychainProtection)

        val items = mutableMapOf<String, Item>()

        override fun put(account: String, data: ByteArray, protection: KeychainProtection) {
            items[account] = Item(data.copyOf(), protection)
        }

        override fun get(account: String): ByteArray? = items[account]?.data?.copyOf()

        override fun delete(account: String) {
            items.remove(account)
        }
    }

    private val aead: AeadBackend = LibsodiumAeadBackend()

    // Deterministic 32-byte wrap key so tests can reason about it; the AEAD nonce is still random
    // (libsodium-internal), so ciphertext is non-deterministic regardless.
    private val fixedWrapKey: (Int) -> ByteArray = { size -> ByteArray(size) { (it + 1).toByte() } }

    private fun delegate(
        keychain: IosKeychain,
        gate: Boolean = false,
    ) = IosSecureStorageDelegate(keychain, aead, gate, fixedWrapKey)

    private fun nonReservedAccounts(keychain: FakeKeychain): Set<String> =
        keychain.items.keys.filterNot { it.startsWith("secret.") || it.startsWith("plain.") }.toSet()

    @Test
    fun secret_roundTrips() {
        val kc = FakeKeychain()
        val store = delegate(kc)
        store.storeSecret("recovery_phrase", "abandon ability able about")
        assertEquals("abandon ability able about", store.getSecret("recovery_phrase"))
    }

    @Test
    fun secret_isSealedAtRest_notPlaintext() {
        val kc = FakeKeychain()
        delegate(kc).storeSecret("owner_id", "owner-1234")

        val secretItem = kc.items.entries.single { it.key.startsWith("secret.") }.value
        // Stored bytes are the AEAD blob, not the plaintext.
        assertFalse(secretItem.data.contentEquals("owner-1234".encodeToByteArray()))
        // nonce(24) || ciphertext(|plaintext|) || tag(16).
        val plaintextLen = "owner-1234".encodeToByteArray().size
        assertEquals(
            AeadBackend.AEAD_NONCE_SIZE + plaintextLen + AeadBackend.AEAD_TAG_SIZE,
            secretItem.data.size,
        )
    }

    @Test
    fun getSecret_returnsNull_whenAbsent() {
        assertNull(delegate(FakeKeychain()).getSecret("missing"))
    }

    @Test
    fun getSecret_returnsNull_onTamperedCiphertext() {
        val kc = FakeKeychain()
        delegate(kc).storeSecret("k", "secret-value")

        // Flip a ciphertext byte → Poly1305 tag mismatch → degrade to "no secret" (Android parity),
        // not a thrown AeadAuthenticationException. Read through a FRESH delegate so the in-memory
        // wrap-key cache doesn't mask anything.
        val account = kc.items.keys.single { it.startsWith("secret.") }
        val blob = kc.items.getValue(account).data
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()

        assertNull(delegate(kc).getSecret("k"))
    }

    @Test
    fun getSecret_returnsNull_whenWrapKeyGone() {
        val kc = FakeKeychain()
        delegate(kc).storeSecret("k", "secret-value")
        // Drop the wrap key (the single account that is neither a secret nor a plain item): the
        // sealed secret is now unrecoverable and must read as "no secret", never crash.
        nonReservedAccounts(kc).forEach { kc.delete(it) }

        assertNull(delegate(kc).getSecret("k"))
    }

    @Test
    fun string_isStoredPlaintext_andRoundTrips() {
        val kc = FakeKeychain()
        val store = delegate(kc)
        store.storeString("device_id", "device-abc")
        assertEquals("device-abc", store.getString("device_id"))

        val plainItem = kc.items.entries.single { it.key.startsWith("plain.") }.value
        // Non-secret bucket is not sealed (the Keychain already encrypts it at rest).
        assertContentEquals("device-abc".encodeToByteArray(), plainItem.data)
    }

    @Test
    fun secret_andString_useSeparateNamespaces() {
        val kc = FakeKeychain()
        val store = delegate(kc)
        store.storeSecret("token", "secret-token")
        store.storeString("token", "plain-token")
        assertEquals("secret-token", store.getSecret("token"))
        assertEquals("plain-token", store.getString("token"))
    }

    @Test
    fun remove_clearsBothBuckets() {
        val kc = FakeKeychain()
        val store = delegate(kc)
        store.storeSecret("k", "s")
        store.storeString("k", "p")
        store.remove("k")
        assertNull(store.getSecret("k"))
        assertNull(store.getString("k"))
    }

    @Test
    fun wrapKey_isCreatedOnce_andReusedAcrossSecrets() {
        val kc = FakeKeychain()
        val store = delegate(kc)
        store.storeSecret("a", "alpha")
        store.storeSecret("b", "bravo")
        // A single wrap-key item backs the whole secret bucket.
        assertEquals(1, nonReservedAccounts(kc).size)
    }

    @Test
    fun secrets_persistAcrossDelegateInstances() {
        val kc = FakeKeychain()
        delegate(kc).storeSecret("phrase", "twelve words here")
        // A fresh instance over the same Keychain (a new process) reads the wrap key back and decrypts.
        assertEquals("twelve words here", delegate(kc).getSecret("phrase"))
    }

    @Test
    fun ungatedWrapKey_usesAfterFirstUnlock_secretsAlwaysAfterFirstUnlock() {
        val kc = FakeKeychain()
        delegate(kc, gate = false).storeSecret("k", "v")

        val wrapAccount = nonReservedAccounts(kc).single()
        assertEquals(KeychainProtection.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, kc.items.getValue(wrapAccount).protection)
        val secretAccount = kc.items.keys.single { it.startsWith("secret.") }
        assertEquals(KeychainProtection.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, kc.items.getValue(secretAccount).protection)
    }

    @Test
    fun gatedMode_gatesWrapKeyWithBiometry_butNotTheSealedSecrets() {
        val kc = FakeKeychain()
        delegate(kc, gate = true).storeSecret("k", "v")

        // The Face ID / passcode gate sits on the single wrap key...
        val wrapAccount = nonReservedAccounts(kc).single()
        assertEquals(KeychainProtection.BIOMETRY_OR_PASSCODE_THIS_DEVICE_ONLY, kc.items.getValue(wrapAccount).protection)
        // ...not on every already-sealed secret item (those need no prompt; the wrap key is the gate).
        val secretAccount = kc.items.keys.single { it.startsWith("secret.") }
        assertNotEquals(KeychainProtection.BIOMETRY_OR_PASSCODE_THIS_DEVICE_ONLY, kc.items.getValue(secretAccount).protection)
    }
}
