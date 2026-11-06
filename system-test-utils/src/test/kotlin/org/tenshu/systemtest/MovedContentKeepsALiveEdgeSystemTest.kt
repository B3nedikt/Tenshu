package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.file.client.fileService
import org.tenshu.sync.proxy.WireIdResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * After a move, the relay still holds an edge for the moved file's content — under the NEW holder.
 *
 * ⚠⚠ A move whose tombstone removes the old holder's edge while nothing registers one for the new
 * holder leaves every moved file's content at `described ∧ no live edge` — the reclamation candidate
 * predicate itself. No separate declare exists: the moved row's own push carries its edges
 * (`content-plane-cr2-b3-wire-spec.md` §3.1) and the old holder's tombstone removes its register.
 */
class MovedContentKeepsALiveEdgeSystemTest {

    private fun contentIdOf(client: TestClient, path: String): String? =
        client.executionContext.database.getData(
            DataId.StringDataId.create(context = client.executionContext.context, type = File.ID, keyValue = path),
        )?.let { row ->
            (client.executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get()
        }

    @Test
    fun aMovedFilesContentKeepsALiveEdge_underItsNewHolder() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val device = system.client(deviceId = "device", role = TestClientRole.RealmHost, pollingIntervalMs = 200)
            device.bootstrapAsHost(deviceName = "Phone")
            val ownerId = requireNotNull(device.syncManager.currentOwnerId)
            fun holders(contentId: String) = system.contentEdges.holdersOf(ownerId, contentId)

            val payload = ByteArray(6_000) { (it % 251).toByte() }
            assertTrue(
                device.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "a.bin", data = payload) is TResult.Success<*>,
            )
            val contentId = requireNotNull(contentIdOf(device, "/a.bin"))
            system.advanceUntil(timeout = 30.seconds) { holders(contentId).isNotEmpty() }
            val before = holders(contentId)
            assertEquals(1, before.size, "precondition: the upload's push registered exactly one holder")

            assertTrue(
                device.tenshuClient.fileService.defaultFileSystem.moveFile("/a.bin", "/b.bin")
                    is TResult.Success<*>,
            )
            assertEquals(contentId, contentIdOf(device, "/b.bin"), "precondition: a move keeps the content id")

            // ⚠⚠ Wait for the SETTLED set, never for the first change. The drain pushes blocks before
            // tombstones, so the moved row's own push usually registers the new holder before the old
            // path's tombstone removes the retired one: `{old, new}` is a legitimate intermediate state,
            // and a test that asserted on the first change would fail whenever it saw it. The
            // tombstone can also land first, giving a transient `{}`. Either way the
            // relay must end on exactly the new holder.
            val newHolder = WireIdResolver(device.syncManager).of(
                DataId.StringDataId.create(context = device.executionContext.context, type = File.ID, keyValue = "/b.bin"),
            )
            assertTrue(newHolder !in before, "precondition: the move names a different holder")
            system.advanceUntil(timeout = 30.seconds) { holders(contentId) == setOf(newHolder) }

            assertEquals(
                setOf(newHolder),
                holders(contentId),
                "the moved file's content keeps a live edge — the NEW holder's, and only it",
            )
        } finally {
            system.shutdown()
        }
    }
}
