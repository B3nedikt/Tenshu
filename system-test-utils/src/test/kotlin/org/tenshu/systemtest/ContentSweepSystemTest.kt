package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.content.ContentDeclarations
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.manifestRefOf
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.proxy.WireIdResolver
import org.tenshu.sync.relay.storage.ContentSweepJob
import org.tenshu.sync.startPairing
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * ⭐ CR-2 B5r end to end (`content-plane-cr2-b5-spec.md` §3, last bullet): the relay's own sweep, armed,
 * on the production SQLite + filesystem content plane, with the relay's clock moved by the test.
 *
 * 1. A device deletes a file; the relay's clock starts at its last edge. Held bytes leave the charged
 *    figure at once (D5). Before T the sweep frees nothing; after T it frees the content, the drain deletes
 *    every byte of it, and the owner's stored total drops by exactly that.
 * 2. A device whose move of that file was still queued pushes it after the free **and** the drain: the
 *    relay refuses it (`content_freed`), the device re-uploads from its plaintext and revives through B4's
 *    path, and the content is live again with every ref it names stored.
 */
class ContentSweepSystemTest {

    private companion object {
        const val T = 15 * 60_000L
        const val SRC = "/src"
        const val DST = "/dst"
        const val OLD = "$SRC/x.bin"
        const val NEW = "$DST/y.bin"
    }

    /** The relay's clock: wall time plus whatever the test has advanced it by. */
    private val skew = AtomicLong(0)
    private val relayClock: () -> Long = { System.currentTimeMillis() + skew.get() }

    private suspend fun system(): TestSystem = TestSystem.builder()
        .durableContentPlane(Files.createTempDirectory("b5r-system").toFile(), relayClock, graceMs = T)
        .build()

    private val TestSystem.sweep: ContentSweepJob get() = requireNotNull(contentSweep)

    @Test
    fun aDeletedFile_isFreedByTheSweepAfterT_theDrainDeletesItsBytes_andUsageDrops() = runBlocking<Unit> {
        val system = system()
        try {
            val device = system.client(deviceId = "solo", role = TestClientRole.RealmHost, pollingIntervalMs = 200)
            device.bootstrapAsHost(deviceName = "Solo Laptop")
            val fs = device.tenshuClient.fileService.defaultFileSystem
            assertTrue(fs.uploadFile(parentPath = "/", fileName = "x.bin", data = ByteArray(6_000) { (it % 241).toByte() }) is TResult.Success<*>)
            val owner = requireNotNull(device.syncManager.currentOwnerId)

            lateinit var contentId: String
            system.advanceUntil(timeout = 60.seconds) {
                val id = contentIdAt(device, "/x.bin") ?: return@advanceUntil false
                contentId = id
                system.contentEdges.holdersOf(owner, id).isNotEmpty() && system.contentRefs.refsOf(owner, id).isNotEmpty()
            }
            val refs = system.contentRefs.refsOf(owner, contentId)
            val index = system.contentBlobs.blobIndex(owner)
            assertTrue(refs.all { it in index }, "precondition: every described ref is stored")
            val contentBytes = refs.sumOf { index.getValue(it).byteLen }
            val storedBefore = system.contentBlobs.bytesUsed(owner)
            assertEquals(0L, system.contentFreeing!!.heldBytes(owner))

            assertTrue(fs.deleteFile("/x.bin") is TResult.Success<*>)
            system.advanceUntil(timeout = 60.seconds) { system.contentEdges.unreferencedSince(owner, contentId) != null }
            assertEquals(contentBytes, system.contentFreeing!!.heldBytes(owner), "D5: held at once, so not charged")

            assertEquals(0, system.sweep.runOnce().freed, "before T nothing is freed")
            assertTrue(refs.all { it in system.contentBlobs.blobIndex(owner) })

            skew.addAndGet(T)
            val run = system.sweep.runOnce()
            // ⚠ Not exactly one: the deleted row's regenerable rungs had edges too (never described, no
            // bytes), and they are freed with it, which only marks them FREED (B4 §1.2 step 4).
            assertTrue(run.freed >= 1, "$run")
            assertEquals(setOf(contentId), system.contentFreeing!!.freedAmong(owner, listOf(contentId)))
            val after = system.contentBlobs.blobIndex(owner)
            val manifest = manifestRefOf(contentId)
            assertTrue((refs - manifest).none { it in after }, "the drain deleted every chunk of the content")
            // ⚠ The manifest goes too — unless a device READ it between the free and the drain, which takes it
            // out of the queue (a read proves use, by design: a leak, never a loss). Before B6 the device-side
            // `ContentReclamationSweep` did exactly that: it read a deleted content's manifest to name its
            // chunks, then called `/sync/content/delete` (B5 spec §6, findings). B6 removed both.
            val drained = refs.filter { it !in after }.sumOf { index.getValue(it).byteLen }
            assertEquals(drained, run.drainedBytes, "only the described content had bytes to drain: $run")
            assertEquals(storedBefore - drained, system.contentBlobs.bytesUsed(owner), "usage dropped by what was drained")
            assertEquals(0L, system.contentFreeing!!.heldBytes(owner))
            assertEquals(run.freed.toLong(), system.sweep.status().totals.freed)
        } finally {
            system.shutdown()
        }
    }

    @Test
    fun aMoveThatLandsAfterTheSweepFreedAndDrained_isRevivedFromThePlaintext() = runBlocking<Unit> {
        val system = system()
        val moverPushes = AtomicBoolean(true)
        try {
            val deleter = system.client(deviceId = "deleter", role = TestClientRole.RealmHost, pollingIntervalMs = 200)
            val mover = system.client(
                deviceId = "mover",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 200,
                storageGate = SyncGate { moverPushes.get() },
            )
            pair(system, host = deleter, joiner = mover)
            val owner = requireNotNull(deleter.syncManager.currentOwnerId)

            // The mover uploads, so it holds the plaintext a revive needs.
            val fs = mover.tenshuClient.fileService.defaultFileSystem
            assertTrue(fs.createFolder(SRC) is TResult.Success<*>)
            assertTrue(fs.createFolder(DST) is TResult.Success<*>)
            assertTrue(fs.uploadFile(parentPath = SRC, fileName = "x.bin", data = ByteArray(4_000) { (it % 251).toByte() }) is TResult.Success<*>)
            lateinit var contentId: String
            system.advanceUntil(timeout = 60.seconds) {
                val id = contentIdAt(mover, OLD) ?: return@advanceUntil false
                contentId = id
                rowAt(deleter, OLD) != null && rowAt(deleter, DST) != null &&
                    system.contentEdges.holdersOf(owner, id).isNotEmpty() && system.contentRefs.refsOf(owner, id).isNotEmpty()
            }
            val firstRefs = system.contentRefs.refsOf(owner, contentId)

            // The move is queued on the mover while the deleter's folder delete reaches the relay.
            moverPushes.set(false)
            mover.syncScheduler.drainNow()
            assertTrue(mover.tenshuClient.fileService.defaultFileSystem.moveFile(sourcePath = OLD, targetPath = NEW) is TResult.Success<*>)
            assertTrue(deleter.tenshuClient.fileService.defaultFileSystem.deleteFile(SRC) is TResult.Success<*>)
            system.advanceUntil(timeout = 60.seconds) { system.contentEdges.unreferencedSince(owner, contentId) != null }

            // T passes on the relay's clock; the sweep frees AND drains (unlike B4's test, the bytes are gone).
            skew.addAndGet(T)
            assertTrue(system.sweep.runOnce().freed >= 1)
            assertEquals(setOf(contentId), system.contentFreeing!!.freedAmong(owner, listOf(contentId)))
            val drained = system.contentBlobs.blobIndex(owner)
            // ⚠ Chunks only: the manifest survives if a device read it between the free and the drain (see above).
            assertTrue((firstRefs - manifestRefOf(contentId)).none { it in drained }, "precondition: the content's chunks were deleted")

            moverPushes.set(true)
            val wireNew = WireIdResolver(mover.syncManager).of(idOf(mover, NEW))
            system.advanceUntil(timeout = 90.seconds) {
                system.contentEdges.holdersOf(owner, contentId) == setOf(wireNew) &&
                    system.contentFreeing!!.freedAmong(owner, listOf(contentId)).isEmpty() &&
                    rowAt(deleter, NEW) != null
            }
            val revived = system.contentRefs.refsOf(owner, contentId)
            assertTrue(revived.isNotEmpty(), "the revive restored the description")
            val stored = system.contentBlobs.blobIndex(owner)
            assertTrue(revived.all { it in stored }, "every ref the revived content names is stored (re-uploaded)")
            assertEquals(null, system.contentEdges.unreferencedSince(owner, contentId), "live again: off the clock")

            // And the next sweep leaves it alone.
            skew.addAndGet(T)
            system.sweep.runOnce()
            assertTrue(system.contentFreeing!!.freedAmong(owner, listOf(contentId)).isEmpty())
            val stillStored = system.contentBlobs.blobIndex(owner)
            assertTrue(revived.all { it in stillStored }, "the next sweep leaves live content alone")
        } finally {
            system.shutdown()
        }
    }

    // ---- helpers (the shape of FreedContentResolutionSystemTest's world) ------------------------------

    private fun idOf(device: TestClient, path: String) =
        DataId.StringDataId.create(context = device.executionContext.context, type = File.ID, keyValue = path)

    private fun rowAt(device: TestClient, path: String): Data? =
        device.executionContext.database.getData(idOf(device, path), asEntity = true)

    /** The row's Primary content id (`File.Data`), from the schema's declaration. */
    private fun contentIdAt(device: TestClient, path: String): String? =
        rowAt(device, path)?.let { row ->
            ContentDeclarations.of(device.executionContext.schemaProvider as SchemaRegistry)
                .edgesOf(row).orEmpty().firstOrNull { !it.regenerable }?.contentId
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
}
