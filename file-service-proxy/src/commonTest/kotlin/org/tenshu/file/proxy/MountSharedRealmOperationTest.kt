package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileService
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.placement.name
import org.tenshu.core.file.placement.parentPath
import org.tenshu.core.file.placement.sharedRealmId
import org.tenshu.core.file.system.mountSharedRealm
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3c.2 of `docs/proposals/drive-shared-realm-file-model.md` (the connector):
 * `mountSharedRealm` creates the member's [Placement] — a personal-realm Syncable
 * keyed by `sharedRealmId` recording where in their FS a shared realm is mounted.
 * The test drives the op through the client → proxy chain and reads the saved record
 * back from the store.
 */
class MountSharedRealmOperationTest {

    private fun env(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val tenshu = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return tenshu.getService(FileService.ID)
    }

    /** The saved [Placement] for [realmId], read from the store by its key. */
    private fun placement(proxy: TestProxy, realmId: String): Entity<Placement>? {
        val dataId = DataId.StringDataId.create(proxy.tContext, Placement.ID, realmId)
        @Suppress("UNCHECKED_CAST")
        return proxy.executionContext.database.getData(dataId, asEntity = true) as? Entity<Placement>
    }

    @Test
    fun mountCreatesAPlacementForTheRealm() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val fs = env(proxy).defaultFileSystem

        val result = fs.mountSharedRealm(
            sharedRealmId = "realm-owner-1",
            parentPath = "/Shared with me",
            name = "Holiday photos",
        )
        assertTrue(result is TResult.Success, "mountSharedRealm should succeed")

        val saved = placement(proxy, "realm-owner-1")
        assertTrue(saved != null, "a Placement is saved keyed by the shared realm id")
        assertEquals("realm-owner-1", saved.sharedRealmId.get())
        assertEquals("/Shared with me", saved.parentPath.get())
        assertEquals("Holiday photos", saved.name.get())
    }

    @Test
    fun remountingTheSameRealmOverwritesTheLocation() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val fs = env(proxy).defaultFileSystem

        assertTrue(fs.mountSharedRealm("realm-owner-1", "/Shared with me", "Photos") is TResult.Success)
        assertTrue(fs.mountSharedRealm("realm-owner-1", "/Archive", "Old photos") is TResult.Success)

        // One mount per realm per member — the key is the realm id, so the second
        // mount overwrites the first rather than creating a duplicate.
        val saved = placement(proxy, "realm-owner-1")
        assertTrue(saved != null)
        assertEquals("/Archive", saved.parentPath.get())
        assertEquals("Old photos", saved.name.get())
    }

    @Test
    fun placementsAreKeyedPerRealm() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val fs = env(proxy).defaultFileSystem

        assertTrue(fs.mountSharedRealm("realm-a", "/Shared with me", "A") is TResult.Success)
        assertTrue(fs.mountSharedRealm("realm-b", "/Shared with me", "B") is TResult.Success)

        assertEquals("A", placement(proxy, "realm-a")?.name?.get())
        assertEquals("B", placement(proxy, "realm-b")?.name?.get())
    }
}
