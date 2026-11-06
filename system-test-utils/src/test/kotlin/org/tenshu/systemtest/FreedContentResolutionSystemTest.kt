package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.content.ContentDeclarations
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.notifications.ChangeLostNotification
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.proxy.WireIdResolver
import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.startPairing
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * ⭐ The freed-content refusal end to end (`content-plane-cr2-b4-spec.md` §2.5, last bullet; the
 * delete-vs-move test of `content-plane-cr2-quorumless.md` §10.7): a device's queued move lands
 * **after** the content it names was freed, and the relay's refusal is resolved one of the two
 * decided ways.
 *
 * Shape, both tests: a `mover` holds a move `/src/x.bin → /dst/y.bin` in its durable queue (its pushes
 * are partitioned with the production `storageGate`) while a `deleter` deletes `/src`. The relay then has
 * zero edges for the content, and the test frees it through `ContentFreeing.free` — standing in for the
 * relay's sweep, its only production caller. Then the mover is released.
 *
 *  - **It holds the plaintext** (it uploaded the file): the push is refused with `content_freed`, the mover
 *    re-uploads under a fresh CEK and retries with a REVIVE, and the relay ends with the content live
 *    again, its one holder the moved row, on both devices.
 *  - **It does not** (a peer uploaded, the bytes were never fetched): the mover retires `/dst/y.bin`
 *    locally with no tombstone, and the change-lost notice reaches both devices (it is a synced row).
 */
class FreedContentResolutionSystemTest {

    private companion object {
        const val SRC = "/src"
        const val DST = "/dst"
        const val OLD = "$SRC/x.bin"
        const val NEW = "$DST/y.bin"
    }

    @Test
    fun aMoveThatLandsAfterTheFree_isRevivedFromThePlaintextTheMoverHolds() = runBlocking {
        val world = World.build(uploader = World.Uploader.MOVER)
        try {
            world.moveWhileTheFolderIsDeletedAndFreed()
            val system = world.system
            val contentId = world.contentId

            system.advanceUntil(timeout = 90.seconds) {
                system.contentEdges.holdersOf(world.ownerId, contentId) == setOf(world.wireName(NEW)) &&
                    world.freeing.freedAmong(world.ownerId, listOf(contentId)).isEmpty() &&
                    world.rowAt(world.deleter, NEW) != null
            }

            assertTrue(
                system.contentRefs.refsOf(world.ownerId, contentId).isNotEmpty(),
                "the revive restored the content's ref set, so the next sweep sees described, live content",
            )
            assertEquals(contentId, world.contentIdAt(world.deleter, NEW), "the peer holds the moved row, same content")
            assertTrue(world.changeLostPaths(world.mover).isEmpty(), "a repaired change raises no notice")
        } finally {
            world.system.shutdown()
        }
    }

    @Test
    fun aMoveThatLandsAfterTheFree_withNoPlaintext_isRetiredWithANoticeOnBothDevices() = runBlocking {
        val world = World.build(uploader = World.Uploader.DELETER)
        try {
            assertTrue(!world.moverHoldsTheBytes(OLD), "precondition: the mover never fetched the bytes")
            world.moveWhileTheFolderIsDeletedAndFreed()
            val system = world.system
            val contentId = world.contentId

            system.advanceUntil(timeout = 90.seconds) {
                world.rowAt(world.mover, NEW) == null &&
                    NEW in world.changeLostPaths(world.mover) &&
                    NEW in world.changeLostPaths(world.deleter)
            }

            assertNull(world.rowAt(world.deleter, NEW), "the refused row never reached the peer")
            assertEquals(
                emptySet(),
                system.contentEdges.holdersOf(world.ownerId, contentId),
                "and never gained an edge at the relay",
            )
            assertEquals(
                setOf(contentId),
                world.freeing.freedAmong(world.ownerId, listOf(contentId)),
                "the content stays freed: nothing revived it",
            )
        } finally {
            world.system.shutdown()
        }
    }

    // ---- the shared world ----------------------------------------------------------------------

    private class World(
        val system: TestSystem,
        val deleter: TestClient,
        val mover: TestClient,
        private val moverPushes: AtomicBoolean,
    ) {
        enum class Uploader { MOVER, DELETER }

        val ownerId: String get() = requireNotNull(deleter.syncManager.currentOwnerId)
        val freeing: ContentFreeing get() = requireNotNull(system.contentFreeing)
        lateinit var contentId: String

        fun wireName(path: String): String = WireIdResolver(mover.syncManager).of(idOf(mover, path))

        /**
         * The move is queued on the mover, the deleter's folder delete reaches the relay (zero edges),
         * and the content is freed — then the mover is released.
         */
        suspend fun moveWhileTheFolderIsDeletedAndFreed() {
            moverPushes.set(false)
            mover.syncScheduler.drainNow()
            val moved = mover.tenshuClient.fileService.defaultFileSystem.moveFile(sourcePath = OLD, targetPath = NEW)
            assertTrue(moved is TResult.Success<*>, "the move: $moved")

            assertTrue(deleter.tenshuClient.fileService.defaultFileSystem.deleteFile(SRC) is TResult.Success<*>)
            system.advanceUntil(timeout = 60.seconds) { system.contentEdges.holdersOf(ownerId, contentId).isEmpty() }

            assertEquals(
                ContentFreeing.FreeResult.FREED,
                freeing.free(ownerId, contentId),
                "precondition: with zero edges the content can be freed (what the relay's sweep does after T)",
            )
            moverPushes.set(true)
        }

        fun rowAt(device: TestClient, path: String): Data? =
            device.executionContext.database.getData(idOf(device, path), asEntity = true)

        /** The row's Primary content id (`File.Data`), from the schema's declaration. */
        fun contentIdAt(device: TestClient, path: String): String? =
            rowAt(device, path)?.let { row ->
                ContentDeclarations.of(device.executionContext.schemaProvider as SchemaRegistry)
                    .edgesOf(row).orEmpty().firstOrNull { !it.regenerable }?.contentId
            }

        fun moverHoldsTheBytes(path: String): Boolean = mover.fileDatabase.fileSize(path) != null

        fun changeLostPaths(device: TestClient): Set<String> {
            val ctx = device.executionContext
            val entries = ctx.database.getArrayData(
                ArrayDatabaseReference(
                    arrayId = DataId.RelationDataId.create(
                        context = ctx.context,
                        type = TList.ID,
                        relationId = NotificationService.Relations.NOTIFICATIONS,
                    ),
                    isEntity = false,
                    elementSchemaId = Notification.ID,
                )
            )?.toList().orEmpty()
            val pathField = ChangeLostNotification.Fields.PATH
            return entries.mapNotNull { entry ->
                when (entry) {
                    is Entity<*> -> runCatching { entry.getFieldValue<TString>(pathField).get() }.getOrNull()
                    else -> runCatching { (entry as org.tenshu.core.Composite).getField<TString>(pathField).get() }.getOrNull()
                }
            }.toSet()
        }

        companion object {
            suspend fun build(uploader: Uploader): World {
                val system = TestSystem.builder().build()
                val moverPushes = AtomicBoolean(true)
                val deleter = system.client(deviceId = "deleter", role = TestClientRole.RealmHost, pollingIntervalMs = 200)
                val mover = system.client(
                    deviceId = "mover",
                    role = TestClientRole.JoiningDevice,
                    pollingIntervalMs = 200,
                    storageGate = SyncGate { moverPushes.get() },
                )
                pair(system, host = deleter, joiner = mover)
                val world = World(system, deleter, mover, moverPushes)

                val writer = if (uploader == Uploader.MOVER) mover else deleter
                val fs = writer.tenshuClient.fileService.defaultFileSystem
                assertTrue(fs.createFolder(SRC) is TResult.Success<*>)
                assertTrue(fs.createFolder(DST) is TResult.Success<*>)
                assertTrue(fs.uploadFile(parentPath = SRC, fileName = "x.bin", data = ByteArray(4_000) { (it % 251).toByte() }) is TResult.Success<*>)
                val other = if (writer === mover) deleter else mover
                system.advanceUntil(timeout = 60.seconds) {
                    world.rowAt(other, OLD) != null && world.rowAt(other, DST) != null &&
                        world.contentIdAt(writer, OLD)?.let { system.contentEdges.holdersOf(world.ownerId, it).isNotEmpty() } == true
                }
                world.contentId = requireNotNull(world.contentIdAt(writer, OLD))
                return world
            }

            private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
                host.bootstrapAsHost(deviceName = "Deleter Tablet")
                host.tenshuClient.syncService.startPairing()
                val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
                joiner.tenshuClient.syncService.initiatePairing(
                    ownerId = parsed.ownerId,
                    pairingSecret = parsed.secret,
                    deviceName = "Mover Phone",
                    otherDevicePublicKey = parsed.hostPairingPublicKey,
                )
                val request = host.tenshuClient.syncService.getPendingPairingRequests()
                    .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
                host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
                system.advanceUntil(timeout = 60.seconds) {
                    val status = joiner.tenshuClient.syncService.getPairingStatus(
                        pairingSecret = parsed.secret,
                        otherDevicePublicKey = parsed.hostPairingPublicKey,
                    )
                    status is TResult.Success && status.value?.get() == true
                }
            }

            private fun idOf(device: TestClient, path: String) =
                DataId.StringDataId.create(context = device.executionContext.context, type = File.ID, keyValue = path)
        }

        private fun idOf(device: TestClient, path: String) =
            DataId.StringDataId.create(context = device.executionContext.context, type = File.ID, keyValue = path)
    }
}
