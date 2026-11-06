package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `initialize` runs in three phases — derive, persist, seat — and this pins both boundaries.
 *
 * ⚠⚠ **The failure this pins is A1 plaintext left on a device the user sees as locked.**
 * `initialize` used to store `RECOVERY_PHRASE` on its first line and derive on its third.
 * `deriveKeys` runs `Bip39.decode`, which throws on a phrase this build cannot read — so
 * `unlockLocal`, whose whole contract is to report failure as `false`, instead threw *past*
 * its own `catch` with the unwrapped phrase already persisted and no `OWNER_ID` beside it.
 * `clearPlaintextPayloadSlots` never ran, because the throw escaped the function entirely.
 *
 * ⚠ The trigger is narrow today — a phrase minted under the pre-2026-09-15 wordlist, or the
 * next wordlist change (threat model §11.2) — but the invariant is not about BIP-39 at all,
 * which is why [aDerivationFailureLeavesNoTrace] fails derivation directly rather than
 * through a bad mnemonic. `setUpLocalUnlock` states the same rule for its own writes
 * ("plaintext slots deleted last"); `initialize` was the one path that did not honour it.
 */
class InitializeAtomicityTest {

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

    /** Derivation throws → no slot written, no field seated, nothing to tidy up. */
    @Test
    fun aDerivationFailureLeavesNoTrace() {
        val sm = newSyncManager(FailingDeriveCryptoManager())

        assertFailsWith<IllegalStateException> { sm.initialize(PHRASE) }

        assertNull(
            storage.getSecret(RECOVERY_PHRASE_KEY),
            "the phrase must not outlive a failed initialize — this is A1 at rest on a " +
                "device that never unlocked",
        )
        assertNull(storage.getSecret(OWNER_ID_KEY), "no ownerId without keys to derive it from")
        assertFalse(sm.isEnabled(), "isEnabled() is `derivedKeys != null` — nothing was seated")
        assertNull(sm.currentMasterKeyPair)
        assertNull(sm.currentOwnerId)
        assertNull(sm.getRecoveryPhrase(), "not via storage, and not via the session field either")
    }

    /**
     * Persistence throws → nothing seated, so `isEnabled()` stays false and the realm flow
     * retries instead of continuing over custody that was never written down.
     */
    @Test
    fun aStorageFailureLeavesTheManagerDisabled() {
        val refuses = StorageThatRefusesToWrite()
        val sm = SyncManagerImpl(
            cryptoManager = BootstrapSyncCryptoManager(),
            storageDelegate = refuses,
            context = testProxy.tContext,
            autoLoadDispatcher = Dispatchers.Unconfined,
        )

        assertFailsWith<IllegalStateException> { sm.initialize(PHRASE) }

        assertFalse(
            sm.isEnabled(),
            "a manager that could not write its custody down must not report itself enabled — " +
                "`CreateRealm` and `RecoveryClaim` skip `initialize` when `isEnabled()`, so the " +
                "retry would sail past the failure",
        )
        assertNull(sm.currentMasterKeyPair)
        assertNull(sm.currentOwnerId)
        assertNull(sm.getRecoveryPhrase(), "nothing persisted and nothing held in the session")
    }

    /** The success path is unchanged: both slots written, every field seated. */
    @Test
    fun aSuccessfulInitializeStillPersistsBothSlots() {
        val sm = newSyncManager(BootstrapSyncCryptoManager())

        sm.initialize(PHRASE)

        assertEquals(PHRASE, storage.getSecret(RECOVERY_PHRASE_KEY))
        assertNotNull(storage.getSecret(OWNER_ID_KEY))
        assertTrue(sm.isEnabled())
        assertNotNull(sm.currentMasterKeyPair)
        assertEquals(storage.getSecret(OWNER_ID_KEY), sm.currentOwnerId)
        assertEquals(PHRASE, sm.getRecoveryPhrase())
    }

    private fun newSyncManager(crypto: SyncCryptoManager): SyncManagerImpl = SyncManagerImpl(
        cryptoManager = crypto,
        storageDelegate = storage,
        context = testProxy.tContext,
        autoLoadDispatcher = Dispatchers.Unconfined,
    )

    /**
     * Stands in for `DefaultSyncCryptoManager` meeting a phrase it cannot decode. It throws
     * a plain `IllegalStateException` rather than `Bip39InvalidMnemonicException` on purpose:
     * the invariant is "a throw out of derivation writes nothing", whatever the throw is.
     */
    private class FailingDeriveCryptoManager :
        SyncCryptoManager by BootstrapSyncCryptoManager() {
        override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys =
            error("derivation refused this phrase")
    }

    /** Stands in for an Android Keystore error or a desktop disk-write failure. */
    private class StorageThatRefusesToWrite : SecureStorageDelegate by InMemorySecureStorageDelegate() {
        override fun storeSecret(key: String, value: String): Unit = error("secure storage is unavailable")
    }

    private companion object {
        const val RECOVERY_PHRASE_KEY = "sync_recovery_phrase"
        const val OWNER_ID_KEY = "sync_owner_id"
        const val PHRASE = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon art"
    }
}
