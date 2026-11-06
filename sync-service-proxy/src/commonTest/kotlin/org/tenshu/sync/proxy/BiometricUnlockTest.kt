package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the biometric-unlock primitive on `SyncManagerImpl` per the
 * tech-debt entry "Android Biometric Unlock — Missing, Undermines the
 * Onboarding-Low-Friction UX". The proxy side deals only in wrap-keys; the
 * platform `BiometricUnlockDelegate` (Phase 2) gates the wrap-key behind
 * `BiometricPrompt` + an AndroidKeyStore AES key.
 *
 * Contract under test:
 *  - `setUpBiometricUnlock(wrapKey)` wraps the persisted plaintext recovery
 *    phrase under [wrapKey], persists `BIOMETRIC_WRAPPER`, and (when no
 *    passphrase wrapper exists) deletes the plaintext.
 *  - `unlockBiometric(wrapKey)` decrypts the wrapper, re-derives the master
 *    keys, and re-populates `currentMasterKeyPair`.
 *  - Biometric + passphrase setups coexist — `setUpBiometricUnlock` after
 *    `setUpLocalUnlock` adds the biometric slot without touching the
 *    passphrase wrapper.
 *  - `clearBiometricSetup()` removes only the biometric slot.
 *  - The `init` block stays sealed when a biometric wrapper is present even
 *    without a passphrase wrapper.
 *  - `getUnlockStatus` reports `isSetUp` whenever **either** wrapper exists.
 */
class BiometricUnlockTest {

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
    fun setUpThenRestart_staysLocked_untilBiometricUnlock() {
        val wrapKey = nextSecureRandomBytes(32)
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)
        // Passphrase floor: biometric is set up atomically alongside a passphrase.
        assertTrue(first.setUpUnlock("hunter2", wrapKey), "setUp should succeed")
        assertNull(
            storage.getSecret("sync_recovery_phrase"),
            "plaintext deleted after setUp",
        )
        assertNotNull(
            storage.getSecret("sync_biometric_wrapper"),
            "biometric wrapper persisted",
        )
        assertNotNull(
            storage.getSecret("sync_unlock_wrapper"),
            "passphrase wrapper (the floor) persisted alongside biometric",
        )

        // Simulate process restart.
        val second = newSyncManager()
        assertNull(
            second.currentMasterKeyPair,
            "post-restart with only a biometric wrapper: stays sealed",
        )
        val sealedStatus = second.getUnlockStatus()
        assertTrue(sealedStatus.isSetUp, "biometric wrapper counts as set-up")
        assertTrue(sealedStatus.isLocked, "no keys loaded")

        assertTrue(second.unlockBiometric(wrapKey), "unlock with correct wrap-key")
        assertNotNull(second.currentMasterKeyPair, "keys re-loaded after unlock")
        assertFalse(second.getUnlockStatus().isLocked)
    }

    @Test
    fun unlockBiometric_withWrongWrapKey_returnsFalse_keysStayNull() {
        val wrapKey = nextSecureRandomBytes(32)
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)
        assertTrue(first.setUpUnlock("hunter2", wrapKey))

        val second = newSyncManager()
        assertNull(second.currentMasterKeyPair)

        val wrongKey = nextSecureRandomBytes(32)
        assertFalse(second.unlockBiometric(wrongKey))
        assertNull(
            second.currentMasterKeyPair,
            "wrong wrap-key must not load keys",
        )
        assertTrue(second.getUnlockStatus().isLocked, "still locked")
    }

    @Test
    fun setUpBiometricUnlock_requiresPassphraseFloor() {
        // No passphrase wrapper present → standalone biometric setup is refused
        // (the passphrase floor: biometric is only ever an add-on).
        val sm = newSyncManager()
        sm.initialize(LEGAL_TEST_PHRASE)
        assertFalse(
            sm.setUpBiometricUnlock(nextSecureRandomBytes(32)),
            "biometric-only setup is refused without a passphrase wrapper",
        )
        assertNull(storage.getSecret("sync_biometric_wrapper"))
    }

    @Test
    fun setUpUnlockFails_whenNoPhrasePersisted() {
        val sm = newSyncManager()
        assertFalse(sm.setUpUnlock("hunter2", nextSecureRandomBytes(32)))
        assertNull(storage.getSecret("sync_unlock_wrapper"))
        assertNull(storage.getSecret("sync_biometric_wrapper"))
    }

    @Test
    fun coexistsWithPassphraseWrapper_biometricDoesNotDeletePlaintext_orRemovePassphrase() {
        // Passphrase setup first (the upgrade-path order).
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)
        assertTrue(first.setUpLocalUnlock("hunter2"))
        // After passphrase setup, plaintext is already gone — but
        // `setUpBiometricUnlock` needs it. Mirroring the real upgrade
        // path: user unlocks with passphrase first (which calls
        // `initialize` → re-persists plaintext), then enrolls biometric.
        assertTrue(first.unlockLocal("hunter2"))
        assertNotNull(storage.getSecret("sync_recovery_phrase"), "unlock re-persists plaintext")

        val wrapKey = nextSecureRandomBytes(32)
        assertTrue(first.setUpBiometricUnlock(wrapKey))
        assertNotNull(
            storage.getSecret("sync_unlock_wrapper"),
            "passphrase wrapper survives biometric enrollment (per §4.1 fallback)",
        )
        assertNotNull(
            storage.getSecret("sync_biometric_wrapper"),
            "biometric wrapper added alongside",
        )
        // Plaintext is intentionally left in place here because the
        // passphrase wrapper is already the persistent representation;
        // the upgrade path's `unlockLocal` re-persists it and we don't
        // want to fight that. Either wrapper can re-derive the phrase
        // on the next restart.

        // Restart-then-unlock works with both credentials.
        val secondPassphrase = newSyncManager()
        assertTrue(secondPassphrase.unlockLocal("hunter2"))

        val secondBiometric = newSyncManager()
        assertTrue(secondBiometric.unlockBiometric(wrapKey))
    }

    @Test
    fun clearBiometricSetup_removesOnlyTheBiometricSlot() {
        // Set up both, then clear biometric.
        val sm = newSyncManager()
        sm.initialize(LEGAL_TEST_PHRASE)
        assertTrue(sm.setUpLocalUnlock("hunter2"))
        assertTrue(sm.unlockLocal("hunter2")) // re-persists plaintext
        val wrapKey = nextSecureRandomBytes(32)
        assertTrue(sm.setUpBiometricUnlock(wrapKey))

        sm.clearBiometricSetup()
        assertNull(storage.getSecret("sync_biometric_wrapper"))
        assertNotNull(
            storage.getSecret("sync_unlock_wrapper"),
            "passphrase wrapper untouched",
        )

        // Idempotent — second call doesn't fail.
        sm.clearBiometricSetup()

        // Passphrase unlock still works.
        val restart = newSyncManager()
        assertTrue(restart.unlockLocal("hunter2"))
    }

    @Test
    fun statusReportsBiometricPlusPassphraseSetup() {
        val sm = newSyncManager()
        sm.initialize(LEGAL_TEST_PHRASE)
        sm.setUpUnlock("hunter2", nextSecureRandomBytes(32))

        val unsealed = sm.getUnlockStatus()
        assertEquals(
            SyncManager.UnlockStatus(isSetUp = true, isLocked = false, hasPassphrase = true),
            unsealed,
        )

        val restarted = newSyncManager()
        val sealed = restarted.getUnlockStatus()
        assertEquals(
            SyncManager.UnlockStatus(isSetUp = true, isLocked = true, hasPassphrase = true),
            sealed,
        )
    }

    @Test
    fun initBlock_staysSealed_whenWrappersPresent() {
        // Set up (passphrase + biometric), simulate restart. The init-block's
        // legacy auto-load must NOT pick up stale plaintext — only a fresh
        // install with no wrapper should trigger auto-load.
        val sm = newSyncManager()
        sm.initialize(LEGAL_TEST_PHRASE)
        assertTrue(sm.setUpUnlock("hunter2", nextSecureRandomBytes(32)))
        // Force a stray plaintext entry; the wrapper gate must still prevent auto-load.
        storage.storeSecret("sync_recovery_phrase", LEGAL_TEST_PHRASE)

        val restart = newSyncManager()
        assertNull(
            restart.currentMasterKeyPair,
            "wrappers gate the legacy auto-load",
        )
    }

    private fun newSyncManager(): SyncManagerImpl = SyncManagerImpl(
        cryptoManager = BootstrapSyncCryptoManager(),
        storageDelegate = storage,
        context = testProxy.tContext,
        autoLoadDispatcher = Dispatchers.Unconfined,
    )

    private companion object {
        const val LEGAL_TEST_PHRASE =
            "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon art"
    }
}
