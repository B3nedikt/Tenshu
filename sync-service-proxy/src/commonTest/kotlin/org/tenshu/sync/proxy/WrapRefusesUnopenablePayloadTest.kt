package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wrapping is destructive, so it must refuse a payload that would not open again.
 *
 * ⚠⚠ **`setUpLocalUnlock` / `setUpUnlock` clear the plaintext slots on success.** Sealing a
 * phrase this build cannot decode therefore turned a readable problem into an unopenable
 * wrapper *and reported `true` while doing it* — the device looked set up, and `unlockLocal`
 * then threw `ChecksumMismatch` straight out of a `Boolean`-returning function. Refusing
 * leaves the plaintext intact and the device un-wrapped, which is recoverable.
 *
 * ⚠ `looksLikeMnemonic`, the gate the unlock half uses, counts words and checks they are
 * letters — it consults neither the wordlist nor the checksum, so it cannot catch this. The
 * reachable case today is a phrase minted under the pre-2026-09-15 wordlist (threat model
 * §11.2); the next wordlist change is the one that will be.
 */
class WrapRefusesUnopenablePayloadTest {

    private lateinit var testProxy: TestProxy
    private lateinit var storage: SecureStorageDelegate

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        storage = InMemorySecureStorageDelegate()
    }

    @Test
    fun setUpLocalUnlockRefusesAPhraseThatWouldNotDecode() {
        seedPlaintext(UNDECODABLE)
        val sm = newSyncManager()

        assertFalse(sm.setUpLocalUnlock("hunter2"), "a wrapper nothing can open is not a success")

        assertNull(storage.getSecret(UNLOCK_WRAPPER_KEY), "no wrapper written")
        assertEquals(
            UNDECODABLE, storage.getSecret(RECOVERY_PHRASE_KEY),
            "the plaintext must survive the refusal — clearing it is what made this " +
                "unrecoverable rather than merely wrong",
        )
    }

    @Test
    fun setUpUnlockRefusesIt_andWritesNeitherWrapper() {
        seedPlaintext(UNDECODABLE)
        val sm = newSyncManager()

        assertFalse(sm.setUpUnlock("hunter2", nextSecureRandomBytes(32)))

        assertNull(storage.getSecret(UNLOCK_WRAPPER_KEY))
        assertNull(storage.getSecret(BIOMETRIC_WRAPPER_KEY), "the biometric half goes too")
        assertEquals(UNDECODABLE, storage.getSecret(RECOVERY_PHRASE_KEY))
    }

    /**
     * ⚠ The "enable biometric later" path reads the same plaintext, so it refuses too — even
     * with a passphrase wrapper already present, which is its own precondition.
     */
    @Test
    fun setUpBiometricUnlockRefusesIt() {
        seedPlaintext(UNDECODABLE)
        storage.storeSecret(UNLOCK_WRAPPER_KEY, "a-pre-existing-wrapper")
        val sm = newSyncManager()

        assertFalse(sm.setUpBiometricUnlock(nextSecureRandomBytes(32)))

        assertNull(storage.getSecret(BIOMETRIC_WRAPPER_KEY))
    }

    /** A phrase that *does* decode is unaffected — this is a filter, not a new floor. */
    @Test
    fun aDecodablePhraseStillWraps() {
        seedPlaintext(LEGAL_TEST_PHRASE)
        val sm = newSyncManager()

        assertTrue(sm.setUpLocalUnlock("hunter2"))

        assertNotNull(storage.getSecret(UNLOCK_WRAPPER_KEY))
        assertNull(storage.getSecret(RECOVERY_PHRASE_KEY), "plaintext cleared on success, as before")
    }

    /**
     * ⚠⚠ All five BIP-39 lengths stay wrappable. Rejecting anything but 24 words here would
     * lock every existing short-phrase realm out of unlock setup — a separate problem with
     * its own `tech-debt.md` entry, and not this filter's to decide.
     */
    @Test
    fun aTwelveWordPhraseIsStillWrappable() {
        seedPlaintext("abandon ".repeat(11) + "about")
        val sm = newSyncManager()

        assertTrue(sm.setUpLocalUnlock("hunter2"), "12 words is a valid BIP-39 length")
        assertNotNull(storage.getSecret(UNLOCK_WRAPPER_KEY))
    }

    /** The joiner arm is untouched: a tagged payload is wrappable without decoding anything. */
    @Test
    fun aJoinerPayloadStillWraps() {
        val joiner = newSyncManager()
        joiner.initializeWithKeys(sampleKeys(), "joined-owner")
        assertNotNull(storage.getSecret(JOINER_PAYLOAD_KEY), "precondition: the joiner slot is seeded")

        assertTrue(joiner.setUpLocalUnlock("hunter2"))
        assertNotNull(storage.getSecret(UNLOCK_WRAPPER_KEY))
    }

    private fun seedPlaintext(phrase: String) {
        storage.storeSecret(RECOVERY_PHRASE_KEY, phrase)
    }

    private fun sampleKeys() = BootstrapSyncCryptoManager().deriveKeys(LEGAL_TEST_PHRASE)

    private fun newSyncManager(): SyncManagerImpl = SyncManagerImpl(
        cryptoManager = BootstrapSyncCryptoManager(),
        storageDelegate = storage,
        context = testProxy.tContext,
        // Unconfined so the constructor's auto-load runs inline; the seeded plaintext has no
        // wrapper beside it in most of these, so the gate fires. `BootstrapSyncCryptoManager`
        // never decodes, so it seats happily even on the undecodable phrase — which is the
        // point: this test is about the *wrap* gate, not derivation.
        autoLoadDispatcher = Dispatchers.Unconfined,
    )

    private companion object {
        const val RECOVERY_PHRASE_KEY = "sync_recovery_phrase"
        const val JOINER_PAYLOAD_KEY = "sync_joiner_payload"
        const val UNLOCK_WRAPPER_KEY = "sync_unlock_wrapper"
        const val BIOMETRIC_WRAPPER_KEY = "sync_biometric_wrapper"

        /** 24 valid wordlist entries, wrong checksum — the shape of a pre-swap phrase. */
        const val UNDECODABLE = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon arrow"

        const val LEGAL_TEST_PHRASE = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon art"
    }
}
