package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.equalTo
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.resolve
import org.tenshu.core.schema.propertyRef
import org.tenshu.file.client.fileService
import org.tenshu.sync.SyncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.PendingContentEmit
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A **peer** moves a file whose upload is still parked on the device that holds its bytes.
 *
 * ⚠⚠ Why this is a system test. The move never runs `MoveFileImplementation` on the uploader: it
 * arrives as block application — a create at the new path, a tombstone for the old — so no local
 * operation is there to fix the journal up. Only two real devices on a real relay can put the
 * uploader in that state, and a double for either half would have to model the one thing under test.
 *
 * ⚠⚠ What it pins. The uploader's journal names the retired row and the old path. Ingest never moves
 * bytes, so the bytes are still exactly there and the drain's `gone` branch does not fire. A drain
 * that resolved the content id through the **retired** row would get null, take the no-content-id
 * branch — a mailbox manifest keyed on a dead entity, with no edge — return `true`, and clear the
 * intent: the journal reports the upload done, no reader can ever resolve the content, and the
 * old-path bytes are an orphan `OrphanedBytesSweep` deletes an hour later.
 */
class PeerMoveOfAParkedUploadSystemTest {

    private fun idOf(client: TestClient, path: String): DataId =
        DataId.StringDataId.create(
            context = client.executionContext.context,
            type = File.ID,
            keyValue = path,
        )

    private fun contentIdOf(client: TestClient, path: String): String? =
        client.executionContext.database.getData(idOf(client, path))?.let { row ->
            (client.executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get()
        }

    private fun journal(client: TestClient): List<PendingContentEmit> =
        client.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = client.executionContext.context,
                    type = PendingContentEmit.ID,
                    relationId = SyncService.Collections.PENDING_CONTENT_EMITS,
                ),
                isEntity = false,
                elementSchemaId = PendingContentEmit.ID,
            )
        )?.filterIsInstance<PendingContentEmit>()?.toList().orEmpty()

    @Test
    fun aFileMovedByAPeerWhileItsUploadIsParkedStillReachesThePeer() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            // The uploader is on cellular: metadata flows, file bytes wait for Wi-Fi.
            var uploaderOnWifi = false
            val uploader = system.client(
                deviceId = "uploader",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 200,
                contentGate = SyncGate { uploaderOnWifi },
            )
            val mover = system.client(
                deviceId = "mover",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 200,
            )
            pair(system, host = uploader, joiner = mover)

            val payload = ByteArray(6_000) { (it % 251).toByte() }
            assertTrue(
                uploader.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "photo.bin", data = payload) is TResult.Success<*>
            )
            val contentId = contentIdOf(uploader, "/photo.bin")!!
            assertEquals(1, journal(uploader).count { it.propertyId.get() == File.Data.id }, "precondition: parked")

            // The peer sees the row, and renames it. ⚠ A rename IS a move — a personal `File`'s
            // identity is its path, so the row retires exactly as it would crossing folders — and it
            // keeps the file at the root, where `root.files` below can find it.
            system.advanceUntil(timeout = 30.seconds) {
                mover.executionContext.database.getData(idOf(mover, "/photo.bin")) != null
            }
            assertTrue(
                mover.tenshuClient.fileService.defaultFileSystem.moveFile("/photo.bin", "/renamed.bin")
                    is TResult.Success<*>
            )

            // The uploader applies that move as blocks: the new row arrives, the old one retires.
            system.advanceUntil(timeout = 30.seconds) {
                uploader.executionContext.database.getData(idOf(uploader, "/renamed.bin")) != null &&
                    uploader.executionContext.database.getData(idOf(uploader, "/photo.bin")) == null
            }
            assertEquals(contentId, contentIdOf(uploader, "/renamed.bin"), "a move keeps the content id")

            // Wi-Fi. The parked upload drains.
            uploaderOnWifi = true
            system.advanceUntil(timeout = 30.seconds) {
                journal(uploader).none { it.propertyId.get() == File.Data.id }
            }

            // ⚠⚠ The journal emptying is NOT the property — it also empties after publishing a
            // manifest no reader can resolve. The property is that the peer can read it.
            mover.tenshuClient.fileService.defaultFileSystem.root
                .files
                .select {
                    fields(File.Field.PATH)
                    relations(File.Data)
                }
                .search<File> { this.path equalTo "/renamed.bin" }
                .resolve()
            assertContentEquals(
                payload,
                mover.fileDatabase.readBytes("/renamed.bin")?.getOrNull(),
                "the peer that moved the file can read its content — the upload was published under " +
                    "the content id the moved row names, not under the retired row's identity",
            )
        } finally {
            system.shutdown()
        }
    }

    private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
        host.bootstrapAsHost(deviceName = "Uploader Phone")
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Mover Laptop",
            otherDevicePublicKey = parsed.hostPairingPublicKey,
        )
        val request = host.tenshuClient.syncService.getPendingPairingRequests()
            .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
        host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
        system.advanceUntil(timeout = 30.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            status is TResult.Success && status.value?.get() == true
        }
    }
}
