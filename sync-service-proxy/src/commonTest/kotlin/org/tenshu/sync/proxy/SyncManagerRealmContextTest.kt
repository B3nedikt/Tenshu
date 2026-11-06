package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the SR-M.0 seam (`docs/proposals/sync-multi-realm-runtime.md`):
 * `SyncManager.currentRealmContext()` is a pure read-model over the existing
 * single-realm state — null before enablement, bundling the realm's keys after,
 * with `masterKeyPair` (A1) present on a host and **absent on a joiner**. No
 * behaviour change: this only proves the carrier reflects the live accessors.
 */
class SyncManagerRealmContextTest {

    private fun syncManager(): SyncManagerImpl {
        val testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }
        return SyncManagerImpl(
            cryptoManager = BootstrapSyncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
        )
    }

    @Test
    fun currentRealmContext_isNullBeforeEnablement() {
        assertNull(syncManager().currentRealmContext())
    }

    @Test
    fun host_bundlesRealmStateIncludingMasterKey() {
        val sm = syncManager()
        sm.initialize("test recovery phrase placeholder words here")

        val ctx = sm.currentRealmContext()
        assertNotNull(ctx)
        assertEquals(sm.currentOwnerId, ctx.ownerId)
        assertEquals(sm.currentDeviceId, ctx.deviceId)
        assertContentEquals(sm.getDerivedKeys()!!.sharedIdentityKey, ctx.derivedKeys.sharedIdentityKey)
        assertContentEquals(sm.getDerivedKeys()!!.sharedVolumeKey, ctx.derivedKeys.sharedVolumeKey)
        assertContentEquals(
            sm.getDeviceSigningKeyPair().privateKey,
            ctx.deviceSigningKeyPair.privateKey,
        )
        // Host (initialize) holds A1.
        assertNotNull(ctx.masterKeyPair)
        assertContentEquals(sm.currentMasterKeyPair!!.publicKey, ctx.masterKeyPair!!.publicKey)
    }

    @Test
    fun joiner_bundlesRealmStateWithoutMasterKey() {
        val sm = syncManager()
        // A joiner gets derived keys + ownerId via the pairing handshake, but
        // never the recovery-derived master keypair (A1 is host-only).
        val keys = BootstrapSyncCryptoManager().deriveKeys("joiner phrase placeholder words go here")
        sm.initializeWithKeys(keys, ownerId = "owner-joiner")

        val ctx = sm.currentRealmContext()
        assertNotNull(ctx)
        assertEquals("owner-joiner", ctx.ownerId)
        assertContentEquals(keys.sharedVolumeKey, ctx.derivedKeys.sharedVolumeKey)
        assertNull(ctx.masterKeyPair, "a joiner has no A1 master keypair")
    }
}
