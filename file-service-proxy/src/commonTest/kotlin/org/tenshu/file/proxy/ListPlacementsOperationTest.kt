package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileService
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.system.listPlacements
import org.tenshu.core.file.system.mountSharedRealm
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3c.2b of `docs/proposals/drive-shared-realm-file-model.md`: `listPlacements`
 * returns the member's mounts — the flat `FileService.Relations.PLACEMENTS`
 * collection the `PlacementRelationMaintainer` derives from every `Placement` save
 * (the real **producer** is `mountSharedRealm`). Drives both ops through the
 * client → proxy chain.
 */
class ListPlacementsOperationTest {

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

    private fun Data.field(id: String): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(id).get()
        is Composite -> getField<TString>(id).get()
        else -> error("unexpected Placement shape: $this")
    }

    @Test
    fun listsAllMountedRealms() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val fs = env(proxy).defaultFileSystem

        assertTrue(fs.mountSharedRealm("realm-a", "/Shared with me", "Alice's photos") is TResult.Success)
        assertTrue(fs.mountSharedRealm("realm-b", "/Shared with me", "Team docs") is TResult.Success)

        val result = fs.listPlacements()
        assertTrue(result is TResult.Success, "listPlacements should succeed")
        val placements = result.value!!.toList()

        assertEquals(
            setOf("realm-a", "realm-b"),
            placements.map { it.field(Placement.Field.SHARED_REALM_ID) }.toSet(),
            "every mounted realm appears in the member's placement list",
        )
        assertEquals(
            setOf("Alice's photos", "Team docs"),
            placements.map { it.field(Placement.Field.NAME) }.toSet(),
        )
    }

    @Test
    fun isEmptyWhenNothingMounted() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val fs = env(proxy).defaultFileSystem

        val result = fs.listPlacements()
        assertTrue(result is TResult.Success)
        assertTrue(result.value!!.toList().isEmpty(), "no mounts → empty list")
    }

    @Test
    fun reMountingARealmKeepsASingleEntry() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val fs = env(proxy).defaultFileSystem

        assertTrue(fs.mountSharedRealm("realm-a", "/Shared with me", "Photos") is TResult.Success)
        assertTrue(fs.mountSharedRealm("realm-a", "/Archive", "Old photos") is TResult.Success)

        val placements = (fs.listPlacements() as TResult.Success).value!!.toList()
        // One mount per realm — the re-mount overwrites rather than duplicating.
        assertEquals(1, placements.size, "re-mounting the same realm keeps a single entry")
        assertEquals("/Archive", placements.single().field(Placement.Field.PARENT_PATH))
    }
}
