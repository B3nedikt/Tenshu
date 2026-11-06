package org.tenshu.systemtest

import kotlinx.coroutines.delay
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
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.proxy.PendingBlockEdgeStore
import org.tenshu.sync.proxy.ReapRemovalOutbox
import org.tenshu.sync.proxy.WireIdResolver
import org.tenshu.sync.startPairing
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * ⭐ CR-2 B3, end to end (`content-plane-cr2-b3-wire-spec.md` §8 "System"): **after a delete races a
 * recreate on two devices, the relay's live edges are exactly `contentEdgesOf` of the rows both devices
 * hold** — §10.13's invariant, asserted rather than assumed.
 *
 * ## ⚠⚠ Needs B3c
 *
 * This test drives the client half (B3d) against the in-repo relay, and only passes once the relay
 * half (B3c) is merged: before it, `/sync/push` does not accept the `EdgedPush` body at all.
 *
 * ## Shape
 *
 * Both devices hold `/f/a.bin` (content c1). Their pushes are then partitioned (the production
 * `storageGate`, as in `SelfEchoConvergenceSystemTest`):
 *  - `deleter` deletes the folder `/f` — one tombstone at T, its cascade naming `a.bin`;
 *  - `writer`, which has not seen the delete, overwrites `/f/a.bin` (a new content above T) and creates
 *    `/f/b.bin` — the recreate.
 *
 * `deleter` is released first, so its tombstone and cascade reach the relay and `writer` reaps under
 * them; then `writer` is released, and its writes above T land after them.
 *
 * ⚠ **The restart is not modelled here.** `TestSystem` has no process restart (a client's store,
 * keys and runtime are built once). What a restart would lose is the durable client state B3d
 * persists — the edge sidecar, the cascade and the reap outbox — and each is pinned surviving a
 * fresh read of the same store at unit level (`EdgesOnPushTest`, `TombstoneCascadeOnTheWireTest`,
 * `ReapRemovalOutboxTest`). The partition stands in for the window a restart would fall into: every
 * write is queued durably while the device cannot push. This test asserts the end state the relay
 * must reach, whatever the clients' rows converge to.
 */
class RelayEdgesMatchTheRowsSystemTest {

    private companion object {
        const val FOLDER = "/f"
        val PATHS = listOf(FOLDER, "$FOLDER/a.bin", "$FOLDER/b.bin")
        val SETTLE = 3.seconds
    }

    @Test
    fun afterADeleteRacesARecreate_theRelaysEdgesAreTheRowsEdges_onBothDevices() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val deleterPushes = AtomicBoolean(true)
            val writerPushes = AtomicBoolean(true)
            val deleter = system.client(
                deviceId = "deleter",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 200,
                storageGate = SyncGate { deleterPushes.get() },
            )
            val writer = system.client(
                deviceId = "writer",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 200,
                storageGate = SyncGate { writerPushes.get() },
            )
            pair(system, host = deleter, joiner = writer)
            val ownerId = requireNotNull(deleter.syncManager.currentOwnerId)

            // ---- Shared starting state.
            val deleterFs = deleter.tenshuClient.fileService.defaultFileSystem
            assertTrue(deleterFs.createFolder(FOLDER) is TResult.Success<*>)
            assertTrue(deleterFs.uploadFile(parentPath = FOLDER, fileName = "a.bin", data = payload(0)) is TResult.Success<*>)
            system.advanceUntil(timeout = 60.seconds) { rowAt(writer, "$FOLDER/a.bin") != null }

            // ---- The partition: everything below is queued durably, nothing reaches the relay.
            deleterPushes.set(false)
            writerPushes.set(false)
            deleter.syncScheduler.drainNow()
            writer.syncScheduler.drainNow()

            assertTrue(deleterFs.deleteFile(FOLDER) is TResult.Success<*>, "the delete")
            delay(50)
            val writerFs = writer.tenshuClient.fileService.defaultFileSystem
            assertTrue(writerFs.replaceFile(parentPath = FOLDER, fileName = "a.bin", data = payload(1)) is TResult.Success<*>, "the overwrite above T")
            assertTrue(writerFs.uploadFile(parentPath = FOLDER, fileName = "b.bin", data = payload(2)) is TResult.Success<*>, "the recreate above T")
            assertTrue(durableClientState(writer).edges > 0, "precondition: the writer's rows wait, edges and all, in its durable queue")

            // ---- Release the deleter first, then the writer.
            deleterPushes.set(true)
            deleter.syncScheduler.drainNow()
            writer.syncScheduler.drainNow()
            writerPushes.set(true)

            // ---- Converge: both devices hold the same rows, every queue has drained.
            system.advanceUntil(timeout = 90.seconds) {
                PATHS.all { contentsOf(deleter, it) == contentsOf(writer, it) } &&
                    durableClientState(deleter).isEmpty() && durableClientState(writer).isEmpty()
            }
            delay(SETTLE)

            for (device in listOf(deleter, writer)) {
                val expected = expectedHolders(device)
                val named = expected.keys + everContentIds(deleter, writer)
                for (contentId in named) {
                    assertEquals(
                        expected[contentId].orEmpty(),
                        system.contentEdges.holdersOf(ownerId, contentId),
                        "on ${device.deviceId}: the relay's live holders of $contentId must be exactly the rows naming it",
                    )
                }
            }
        } finally {
            system.shutdown()
        }
    }

    // ---- what the rows say ---------------------------------------------------------------------

    private val seenContentIds = mutableSetOf<String>()

    /** Every content id any device's row has named during the test, so a dropped one is checked too. */
    private fun everContentIds(vararg devices: TestClient): Set<String> {
        devices.forEach { device -> PATHS.forEach { path -> seenContentIds += contentsOf(device, path).filterNotNull() } }
        return seenContentIds
    }

    /** Content id → the wire names of the rows on [device] that name it, from `contentEdgesOf` of each row. */
    private fun expectedHolders(device: TestClient): Map<String, Set<String>> {
        val names = WireIdResolver(device.syncManager)
        val out = mutableMapOf<String, MutableSet<String>>()
        PATHS.forEach { path ->
            val row = rowAt(device, path) ?: return@forEach
            val holder = names.of(idOf(device, path))
            edgesOf(device, row).forEach { contentId -> out.getOrPut(contentId) { mutableSetOf() } += holder }
        }
        seenContentIds += out.keys
        return out
    }

    private fun edgesOf(device: TestClient, row: Data): List<String> =
        ContentDeclarations.of(device.executionContext.schemaProvider as SchemaRegistry)
            .edgesOf(row).orEmpty().mapNotNull { it.contentId }

    private fun contentsOf(device: TestClient, path: String): List<String?> =
        rowAt(device, path)?.let { row ->
            ContentDeclarations.of(device.executionContext.schemaProvider as SchemaRegistry)
                .edgesOf(row).orEmpty().map { it.contentId }
        }.orEmpty()

    private fun idOf(device: TestClient, path: String) =
        DataId.StringDataId.create(context = device.executionContext.context, type = File.ID, keyValue = path)

    private fun rowAt(device: TestClient, path: String): Data? =
        device.executionContext.database.getData(idOf(device, path), asEntity = true)

    // ---- what the client still owes ------------------------------------------------------------

    private data class Owed(val edges: Int, val reaps: Int) {
        fun isEmpty() = edges == 0 && reaps == 0
    }

    /** B3d's durable outbound state: blocks that still owe their edges, and reaps the relay has not taken. */
    private fun durableClientState(device: TestClient): Owed {
        val ctx = device.executionContext
        val queued = ctx.database.getArrayData(
            org.tenshu.core.common.proxy.ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = ctx.context,
                    type = org.tenshu.sync.SyncBlock.ID,
                    relationId = org.tenshu.sync.SyncService.Collections.PENDING_BLOCKS,
                ),
                isEntity = false,
                elementSchemaId = org.tenshu.sync.SyncBlock.ID,
            )
        )?.toList()?.filterIsInstance<org.tenshu.sync.SyncBlock>().orEmpty()
        val withEdges = queued.count { PendingBlockEdgeStore(ctx).edgesOf(it.blockId.get()).isNotEmpty() }
        return Owed(edges = withEdges, reaps = ReapRemovalOutbox(ctx).pending().size)
    }

    private fun payload(seed: Int) = ByteArray(4_000) { ((it + seed * 7) % 251).toByte() }

    private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
        host.bootstrapAsHost(deviceName = "Deleter Tablet")
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Writer Phone",
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
