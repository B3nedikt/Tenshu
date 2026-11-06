package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lazy realm genesis (ADR 0019 Phase 2): [RealmRegistrar] performs the deferred
 * `POST /sync/realm` from the in-memory master keypair, is idempotent across
 * calls, and no-ops before a realm is derived.
 */
class RealmRegistrarTest {

    private class Fixture(
        val syncManager: SyncManagerImpl,
        val relay: MockSyncRelay,
        val registrar: RealmRegistrar,
    )

    private fun fixture(): Fixture {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        val relay = MockSyncRelay(proxy.tContext)
        return Fixture(syncManager, relay, RealmRegistrar(syncManager, crypto, relay))
    }

    @Test
    fun notEnabled_returnsNull_withoutContactingRelay() = runTest {
        val f = fixture()
        assertNull(f.registrar.ensureRegistered(), "no realm derived ⇒ nothing to register")
        assertTrue(
            f.relay.createdRealms().isEmpty(),
            "the relay must not be contacted before a realm exists"
        )
    }

    @Test
    fun registersOnce_thenLatches() = runTest {
        val f = fixture()
        f.syncManager.initialize("mock phrase")

        f.registrar.ensureRegistered()
        assertEquals(1, f.relay.createdRealms().size, "first call registers the realm")
        assertTrue(f.syncManager.realmRelayRegistered, "success sets the persistent latch")

        f.registrar.ensureRegistered()
        assertEquals(
            1,
            f.relay.createdRealms().size,
            "a second call is a no-op once the persistent latch is set"
        )
    }

    @Test
    fun skipsWhenAlreadySyncedBefore() = runTest {
        val f = fixture()
        f.syncManager.initialize("mock phrase")
        // A device that has synced before (lastSyncTimestamp > 0) is already
        // registered — covers realms created before the persistent latch existed.
        f.syncManager.lastSyncTimestamp = 42

        f.registrar.ensureRegistered()

        assertTrue(
            f.relay.createdRealms().isEmpty(),
            "a previously-synced realm must not re-run the genesis"
        )
        assertTrue(f.syncManager.realmRelayRegistered, "and the latch is set going forward")
    }
}
