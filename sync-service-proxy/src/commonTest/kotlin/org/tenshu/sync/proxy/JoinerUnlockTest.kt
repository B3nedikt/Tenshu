package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the joiner-device unlock primitive on `SyncManagerImpl`
 * per `docs/proposals/onboarding-low-friction.md` §10.6 (Phase 2d).
 *
 * Contract under test — both halves of the round-trip on the joiner side
 * (the "I have Tenshu on another device" path through `PairingCompletion →
 * initializeWithKeys`):
 *  - `initializeWithKeys(keys, ownerId)` persists `JOINER_PAYLOAD` +
 *    `OWNER_ID` so the joiner's identity survives a process restart even
 *    without an unlock wrapper (the "Skip on SetupUnlock" case).
 *  - `init` of a fresh `SyncManagerImpl` rehydrates `derivedKeys` and
 *    `currentOwnerId` from those slots when no wrapper is present.
 *  - `setUpLocalUnlock` / `setUpBiometricUnlock` on a joiner wrap the
 *    `JOINER_PAYLOAD` instead of failing with "no `RECOVERY_PHRASE`" —
 *    this is the core defect Phase 2d fixes.
 *  - `unlockLocal` / `unlockBiometric` decode the joiner payload back into
 *    `initializeWithKeys` — `derivedKeys` + `currentOwnerId` come back,
 *    `currentMasterKeyPair` stays null (joiner has no master keypair).
 *  - `getUnlockStatus.isLocked` gates on `derivedKeys` rather than
 *    `currentMasterKeyPair` so the joiner reports unlocked correctly.
 */
class JoinerUnlockTest {

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
    fun initializeWithKeysThenRestart_rehydratesIdentity_evenWithoutWrapper() {
        val keys = sampleDerivedKeys()
        val ownerId = "joiner-owner-1"

        val first = newSyncManager()
        first.initializeWithKeys(keys, ownerId)
        assertEquals(ownerId, first.currentOwnerId)
        assertTrue(first.isEnabled(), "derivedKeys populated")

        // Process restart with no wrapper — pre-Phase-2d, this would land the
        // joiner on Welcome because `currentOwnerId` was in-memory only.
        val second = newSyncManager()
        assertEquals(
            ownerId,
            second.currentOwnerId,
            "init rehydrates ownerId from JOINER_PAYLOAD + OWNER_ID slots",
        )
        assertTrue(second.isEnabled(), "init rehydrates derivedKeys")
        assertContentEquals(
            keys.sharedIdentityKey,
            second.getDerivedKeys()!!.sharedIdentityKey,
            "identity key round-trips through encode/decode",
        )
        assertContentEquals(
            keys.sharedVolumeKey,
            second.getDerivedKeys()!!.sharedVolumeKey,
            "volume key round-trips through encode/decode",
        )
        assertNull(
            second.currentMasterKeyPair,
            "joiner has no master keypair — stays null after rehydrate",
        )

        val status = second.getUnlockStatus()
        assertFalse(status.isSetUp, "no wrapper")
        assertFalse(
            status.isLocked,
            "joiner with derivedKeys is unlocked (gated on derivedKeys, not master keypair)",
        )
    }

    @Test
    fun setUpLocalUnlockOnJoiner_succeeds_andSurvivesRestart() {
        val keys = sampleDerivedKeys()
        val ownerId = "joiner-owner-2"

        val first = newSyncManager()
        first.initializeWithKeys(keys, ownerId)
        // Pre-Phase-2d this returned `false` because RECOVERY_PHRASE was null.
        assertTrue(
            first.setUpLocalUnlock("hunter2"),
            "joiner can now set up passphrase unlock without a recovery phrase",
        )
        assertNotNull(storage.getSecret("sync_unlock_wrapper"), "wrapper persisted")
        assertNull(
            storage.getSecret("sync_joiner_payload"),
            "plaintext joiner payload cleared after wrapping",
        )

        // Restart-then-unlock.
        val second = newSyncManager()
        assertFalse(second.isEnabled(), "wrapper present → stays sealed until unlock")
        val sealed = second.getUnlockStatus()
        assertTrue(sealed.isSetUp)
        assertTrue(sealed.isLocked, "joiner is locked when wrapper exists and derivedKeys is null")

        assertTrue(second.unlockLocal("hunter2"))
        assertEquals(ownerId, second.currentOwnerId, "unlock restores ownerId")
        assertTrue(second.isEnabled(), "derivedKeys re-loaded")
        assertContentEquals(keys.sharedIdentityKey, second.getDerivedKeys()!!.sharedIdentityKey)
        assertContentEquals(keys.sharedVolumeKey, second.getDerivedKeys()!!.sharedVolumeKey)
        assertNull(second.currentMasterKeyPair, "joiner has no master keypair")
        assertFalse(second.getUnlockStatus().isLocked)
    }

    @Test
    fun setUpBiometricUnlockOnJoiner_succeeds_andSurvivesRestart() {
        val keys = sampleDerivedKeys()
        val ownerId = "joiner-owner-3"
        val wrapKey = nextSecureRandomBytes(32)

        val first = newSyncManager()
        first.initializeWithKeys(keys, ownerId)
        // Passphrase floor: a joiner sets biometric up atomically with a passphrase.
        assertTrue(
            first.setUpUnlock("hunter2", wrapKey),
            "joiner can set up biometric unlock (with the passphrase floor)",
        )
        assertNotNull(storage.getSecret("sync_biometric_wrapper"))
        assertNotNull(storage.getSecret("sync_unlock_wrapper"), "passphrase floor written")
        assertNull(
            storage.getSecret("sync_joiner_payload"),
            "plaintext cleared once after the atomic setup",
        )

        val second = newSyncManager()
        assertFalse(second.isEnabled())
        val sealed = second.getUnlockStatus()
        assertTrue(sealed.isSetUp)
        assertTrue(sealed.isLocked)

        assertTrue(second.unlockBiometric(wrapKey))
        assertEquals(ownerId, second.currentOwnerId)
        assertTrue(second.isEnabled())
        assertNull(second.currentMasterKeyPair)
        assertFalse(second.getUnlockStatus().isLocked)
    }

    @Test
    fun unlockLocalOnJoiner_withWrongPassphrase_returnsFalse_keysStayNull() {
        val first = newSyncManager()
        first.initializeWithKeys(sampleDerivedKeys(), "joiner-owner-4")
        assertTrue(first.setUpLocalUnlock("correct"))

        val second = newSyncManager()
        assertFalse(second.isEnabled())

        // Symmetric XOR in BootstrapSyncCryptoManager always "decrypts" — the
        // joiner-payload structural decoder is the wrong-key gate (no `J:`
        // prefix on the garbage → returns null).
        assertFalse(second.unlockLocal("wrong"))
        assertFalse(second.isEnabled(), "wrong passphrase must not load keys")
        assertNull(second.currentOwnerId, "wrong passphrase must not restore ownerId")
        assertTrue(second.getUnlockStatus().isLocked)
    }

    @Test
    fun unlockBiometricOnJoiner_withWrongWrapKey_returnsFalse_keysStayNull() {
        val wrapKey = nextSecureRandomBytes(32)
        val first = newSyncManager()
        first.initializeWithKeys(sampleDerivedKeys(), "joiner-owner-5")
        assertTrue(first.setUpUnlock("hunter2", wrapKey))

        val second = newSyncManager()
        assertFalse(second.isEnabled())

        assertFalse(second.unlockBiometric(nextSecureRandomBytes(32)))
        assertFalse(second.isEnabled())
        assertNull(second.currentOwnerId)
        assertTrue(second.getUnlockStatus().isLocked)
    }

    @Test
    fun masterDevice_initializeStillRoundTrips_postPhase2d() {
        // Regression guard: the wrapper-payload generalisation must not
        // break the master device path the existing LocalUnlock /
        // BiometricUnlock tests already cover. This is the duplicate of those
        // assertions on the same setup path but inside the joiner test file —
        // co-located so a future edit that breaks both paths fails here too.
        val first = newSyncManager()
        first.initialize(LEGAL_TEST_PHRASE)
        assertTrue(first.setUpLocalUnlock("hunter2"))
        assertNull(storage.getSecret("sync_recovery_phrase"), "host plaintext cleared")
        assertNull(storage.getSecret("sync_joiner_payload"), "no joiner slot on host path")

        val second = newSyncManager()
        assertTrue(second.unlockLocal("hunter2"))
        assertNotNull(
            second.currentMasterKeyPair,
            "host path still populates currentMasterKeyPair via initialize()",
        )
        assertTrue(second.isEnabled())
    }

    @Test
    fun enableBiometricAfterPassphraseUnlock_onJoiner_reSeedsPlaintext() {
        // The "upgrade path" symmetric to the host case in
        // BiometricUnlockTest.coexistsWithPassphraseWrapper_... :
        //  1. Joiner sets up passphrase first
        //  2. Restarts + unlocks with passphrase
        //  3. Enables biometric → setUpBiometricUnlock must find something to
        //     wrap. After Phase 2d, `unlockLocal` re-seeds JOINER_PAYLOAD on
        //     the joiner side, so setUpBiometricUnlock succeeds.
        val keys = sampleDerivedKeys()
        val ownerId = "joiner-owner-6"
        val first = newSyncManager()
        first.initializeWithKeys(keys, ownerId)
        assertTrue(first.setUpLocalUnlock("hunter2"))

        val second = newSyncManager()
        assertTrue(second.unlockLocal("hunter2"))
        assertNotNull(
            storage.getSecret("sync_joiner_payload"),
            "unlockLocal re-seeds JOINER_PAYLOAD on joiner side",
        )

        val wrapKey = nextSecureRandomBytes(32)
        assertTrue(
            second.setUpBiometricUnlock(wrapKey),
            "biometric setup finds the re-seeded plaintext joiner slot",
        )
        assertNotNull(storage.getSecret("sync_biometric_wrapper"))
        assertNotNull(
            storage.getSecret("sync_unlock_wrapper"),
            "passphrase wrapper survives the biometric add",
        )

        // Both credentials work after a fresh restart.
        val withPassphrase = newSyncManager()
        assertTrue(withPassphrase.unlockLocal("hunter2"))
        assertEquals(ownerId, withPassphrase.currentOwnerId)

        val withBiometric = newSyncManager()
        assertTrue(withBiometric.unlockBiometric(wrapKey))
        assertEquals(ownerId, withBiometric.currentOwnerId)
    }

    @Test
    fun ownerIdContainingPipe_roundTripsThroughEncoder() {
        // Defensive: the joiner-payload encoder uses `|` as a field separator
        // and `decodeJoinerPayload` only treats the first two as delimiters.
        // An ownerId derived via `ownerIdForMasterPublicKey` won't actually
        // contain `|`, but the protocol carries the host's ownerId through
        // the pairing URL — being tolerant here is cheap and prevents a
        // future change to the ownerId encoding from silently corrupting the
        // joiner-side payload.
        val keys = sampleDerivedKeys()
        val ownerId = "owner|with|pipes"

        val first = newSyncManager()
        first.initializeWithKeys(keys, ownerId)

        val second = newSyncManager()
        assertEquals(ownerId, second.currentOwnerId)
    }

    @Test
    fun genesisThenJoin_afterDiscard_restartLoadsJoinedRealm_notOwn() {
        // The genesis-then-join footgun: a device runs its own `CreateRealm` (writes
        // RECOVERY_PHRASE), then joins another realm via pairing. Pre-fix, the leftover
        // own phrase took precedence over the joiner payload in `init`'s rehydrate, so a
        // restart silently reverted the device onto its own (orphaned) realm — it then
        // pushed/pulled the wrong owner's stream and sync looked one-directional.
        val genesisDevice = newSyncManager()
        genesisDevice.initialize(LEGAL_TEST_PHRASE)
        val ownOwner = assertNotNull(genesisDevice.currentOwnerId, "genesis set an owner")
        assertNotNull(storage.getSecret("sync_recovery_phrase"), "genesis wrote the phrase")

        // Join a different realm. `PairingCompletion` runs initializeWithKeys then, on the
        // owner switch, discardSupersededRealm() — modelled directly here.
        val joinedKeys = sampleDerivedKeys()
        genesisDevice.initializeWithKeys(joinedKeys, "joined-owner")
        genesisDevice.discardSupersededRealm()

        assertNull(
            storage.getSecret("sync_recovery_phrase"),
            "the superseded own-genesis phrase must be purged",
        )
        assertNull(genesisDevice.getRecoveryPhrase(), "no phrase after committing to the joined realm")

        // Restart: with the phrase gone, rehydrate takes the joiner-payload branch.
        val restarted = newSyncManager()
        assertEquals(
            "joined-owner",
            restarted.currentOwnerId,
            "restart must commit to the joined realm",
        )
        assertNotEquals(
            ownOwner,
            restarted.currentOwnerId,
            "restart must NOT revert to the device's own genesis realm",
        )
        assertContentEquals(
            joinedKeys.sharedIdentityKey,
            restarted.getDerivedKeys()!!.sharedIdentityKey,
            "joined realm's keys are the ones rehydrated",
        )
        assertNull(restarted.currentMasterKeyPair, "a joiner holds no master keypair")
    }

    @Test
    fun genesisThenJoinWithUnlockWrapper_afterDiscard_restartLoadsJoinedRealm() {
        // The wrapper variant — the more insidious one: the own-genesis phrase is wrapped
        // under a passphrase (plaintext cleared), so `readWrappablePayload`/the unlock path
        // would decode the OWN mnemonic and revert. Discarding the superseded wrapper drops
        // the device to the joined realm's plaintext joiner payload.
        val genesisDevice = newSyncManager()
        genesisDevice.initialize(LEGAL_TEST_PHRASE)
        val ownOwner = assertNotNull(genesisDevice.currentOwnerId)
        assertTrue(genesisDevice.setUpLocalUnlock("pin1234"), "wrap the own-genesis phrase")
        assertNotNull(storage.getSecret("sync_unlock_wrapper"), "wrapper wraps the own phrase")
        assertNull(storage.getSecret("sync_recovery_phrase"), "plaintext cleared once wrapped")

        val joinedKeys = sampleDerivedKeys()
        genesisDevice.initializeWithKeys(joinedKeys, "joined-owner")
        genesisDevice.discardSupersededRealm()

        assertNull(storage.getSecret("sync_unlock_wrapper"), "own-realm passphrase wrapper purged")
        assertNull(storage.getSecret("sync_biometric_wrapper"), "own-realm biometric wrapper purged")

        // Restart: no wrapper ⇒ rehydrate from the plaintext joiner payload ⇒ joined realm.
        val restarted = newSyncManager()
        assertEquals("joined-owner", restarted.currentOwnerId)
        assertNotEquals(ownOwner, restarted.currentOwnerId, "must not revert to the own realm")
        assertTrue(restarted.isEnabled(), "joined realm rehydrated from the joiner payload")
    }

    private fun sampleDerivedKeys(): SyncCryptoManager.DerivedKeys =
        SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = ByteArray(32) { it.toByte() },
            sharedVolumeKey = ByteArray(32) { (255 - it).toByte() },
        )

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
