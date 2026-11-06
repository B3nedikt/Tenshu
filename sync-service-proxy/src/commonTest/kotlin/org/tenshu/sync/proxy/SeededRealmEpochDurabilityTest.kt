package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F5a: the out-of-band realm-epoch seed must survive a process restart.
 *
 * ⚠⚠ The epoch is seeded after a revocation so later admissions mint above it. Held only in
 * memory, a restart drops it: the device falls back to the tombstone-derived count and mints its
 * cert *below* the revocation naming it, and an ordinary pairing fails with **403 "Admission
 * capability has been revoked"** (§7b.1).
 *
 * ⚠⚠ The seed cannot simply be deleted although revocations are key-scoped (§7e): it also feeds `Device.admittedAtEpoch`, which **T-K-02 enforces at merge on every
 * peer**. So durability is required, not merely tidy.
 */
class SeededRealmEpochDurabilityTest {

    private lateinit var testProxy: TestProxy
    private lateinit var storage: InMemorySecureStorageDelegate

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        // ⭐ Shared across both managers below — this models the DISK, which is the whole point.
        // A per-instance delegate would make the restart test pass for the wrong reason.
        storage = InMemorySecureStorageDelegate()
    }

    private fun newManager() = SyncManagerImpl(
        cryptoManager = testProxy.tContext.syncCryptoManager(),
        storageDelegate = storage,
        context = testProxy.tContext,
    )

    @Test
    fun theSeedSurvivesARestart() {
        newManager().seedCurrentRealmEpoch(7L)

        // A fresh SyncManagerImpl over the same storage — i.e. the next process launch.
        val afterRestart = newManager()

        assertEquals(
            7L,
            afterRestart.currentRealmEpoch,
            "a seed lost on restart makes the next admission mint BELOW the revocation that " +
                "caused it — the live 403 of §7b.1",
        )
    }

    @Test
    fun theSeedOnlyEverMovesUp() {
        val manager = newManager()
        manager.seedCurrentRealmEpoch(9L)
        manager.seedCurrentRealmEpoch(4L)

        assertEquals(
            9L,
            manager.currentRealmEpoch,
            "a lower out-of-band value must not walk the epoch backwards — that would re-open " +
                "the window the seed exists to close",
        )
    }

    /**
     * ⚠ The provider still wins once tombstones catch up: the seed is a floor, not an override.
     * Without this the seed would pin the epoch permanently at whatever the last revocation set.
     */
    @Test
    fun theProviderWinsOnceItPassesTheSeed() {
        val manager = newManager()
        manager.seedCurrentRealmEpoch(3L)
        manager.setRealmEpochProvider { 11L }

        assertEquals(11L, manager.currentRealmEpoch)
    }

    /**
     * ⚠⚠ The seed is **realm** state, not device state. Carrying it into the next realm would stamp
     * a fresh admission with a stranger's epoch — and T-K-02's merge check would then act on it.
     */
    @Test
    fun forgettingTheDeviceClearsTheSeed() {
        val manager = newManager()
        manager.seedCurrentRealmEpoch(5L)

        manager.forgetThisDevice()

        assertEquals(
            0L,
            newManager().currentRealmEpoch,
            "a forgotten device must not carry its old realm's epoch into the next one",
        )
    }
}
