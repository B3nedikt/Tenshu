package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.TByteArray
import org.tenshu.core.file.system.listSharedFolder
import org.tenshu.core.file.system.readSharedFile
import org.tenshu.core.file.system.shareFolderIntoRealm
import org.tenshu.core.file.system.uploadFile
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.file.client.fileService
import org.tenshu.sync.createSharedRealm
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3c.1c + 3c.1d cross-device proof (`docs/proposals/drive-shared-realm-file-model.md`):
 * a host shares a folder into a shared realm; the joiner — who auto-joins via the
 * membership — both **browses the realm's tree** (`ListSharedFolder`, 3c.1c) and **reads
 * a shared file's bytes** (`ReadSharedFile`, 3c.1d) over the dev relay with real crypto.
 * This exercises the SharedFile path end-to-end: 3a placement → realm emit-routing +
 * content fan-out into the realm's content store → joiner per-realm pull → the
 * `SharedFileRelationMaintainer` firing on the pulled saves → the browse op → the
 * realm-routed OnDemand fetch (3c.1d) → the realm-key-aware reassembly (3b.2b) → the read op.
 */
class SharedRealmFileReadSystemTest {

    private fun Data.field(id: String): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(id).get()
        is Composite -> getField<TString>(id).get()
        else -> error("unexpected SharedFile shape: $this")
    }

    @Test
    fun joinerBrowsesAndReadsSharedFileCrossDevice(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairHostAndJoiner(system)
            system.advanceUntil(timeout = 15.seconds) { host.deviceCount() >= 2 }

            val createResult = host.tenshuClient.syncService.createSharedRealm()
            assertTrue(createResult is TResult.Success, "createSharedRealm should succeed")
            val realmId = (createResult.value as TString).get()

            // Host shares a folder with one file into the realm. (Subfolder *drilling*
            // is proven by the local ListSharedFolderOperationTest; this end-to-end test
            // keeps the pre-sync work small so the multi-hop cross-device chain — auto-join
            // → realm pull → SharedFile delivery → content fan-out → reassembly — fits the
            // harness's advance budget.)
            val hostFs = host.tenshuClient.fileService.defaultFileSystem
            val payload = byteArrayOf(0x0B, 0x0A, 0x0D, 0x55, 0x66)
            // Name what failed: a bare `assertTrue(… is Success)` reports only "Expected value
            // to be true", which hides the op's typed error and costs a diagnosis run.
            val folderResult = hostFs.createFolder("/shared")
            assertTrue(folderResult is TResult.Success, "createFolder should succeed; got $folderResult")
            val uploadResult = hostFs.uploadFile("/shared", "doc.bin", payload)
            assertTrue(uploadResult is TResult.Success, "uploadFile should succeed; got $uploadResult")
            val shareResult = hostFs.shareFolderIntoRealm("/shared", realmId)
            assertTrue(
                shareResult is TResult.Success,
                "shareFolderIntoRealm should succeed; got $shareResult" +
                    ((shareResult as? TResult.Failure)?.let { " / error=${it.error}" } ?: ""),
            )

            val joinerFs = joiner.tenshuClient.fileService.defaultFileSystem

            // The joiner auto-joins the realm + pulls the SharedFile tree. The realm root
            // is a single wrapper node named for the shared folder ("shared"); its
            // contents (doc.bin) nest one level under it. Wait until the wrapper's
            // children listing shows the shared file.
            suspend fun wrapperFileId(): String? =
                (joinerFs.listSharedFolder(realmId, SharedFile.ROOT_PARENT) as? TResult.Success)
                    ?.value?.toList()?.firstOrNull()?.field(SharedFile.Field.FILE_ID)
            system.advanceUntil(timeout = 25.seconds) {
                val wrapper = wrapperFileId() ?: return@advanceUntil false
                val r = joinerFs.listSharedFolder(realmId, wrapper)
                r is TResult.Success && r.value!!.toList().isNotEmpty()
            }

            // The realm root carries the shared folder's name (read straight from realm
            // content — the joiner never saw the owner's personal path or Placement).
            val realmRoot = (joinerFs.listSharedFolder(realmId, SharedFile.ROOT_PARENT) as TResult.Success)
                .value!!.toList()
            assertEquals(
                setOf("shared"),
                realmRoot.map { it.field(SharedFile.Field.NAME) }.toSet(),
                "joiner reads the shared folder's name cross-device",
            )
            val root = (joinerFs.listSharedFolder(realmId, realmRoot.single().field(SharedFile.Field.FILE_ID)) as TResult.Success)
                .value!!.toList()
            assertEquals(
                setOf("doc.bin"),
                root.map { it.field(SharedFile.Field.NAME) }.toSet(),
                "joiner browses into the shared folder cross-device",
            )

            // 3c.1d — the cross-device *byte read*. `readSharedFile` drives the
            // realm-routed OnDemand fetch: the joiner has the SharedFile *entity* (it just
            // browsed it) but not its bytes, so `ReadSharedFile` fetches the file's chunks
            // from the *realm's* content store, the realm-key-aware applier reassembles them
            // onto the realm byte-path, and the op reads them back. Retried under
            // `advanceUntil` because the chunk fetch + reassembly is itself a relay round-trip.
            val docFileId = root.single().field(SharedFile.Field.FILE_ID)
            system.advanceUntil(timeout = 25.seconds) {
                val r = joinerFs.readSharedFile(realmId, docFileId)
                r is TResult.Success && r.value != null
            }
            val readBytes = (joinerFs.readSharedFile(realmId, docFileId) as TResult.Success).value
            assertTrue(
                readBytes is TByteArray && readBytes.get().contentEquals(payload),
                "joiner reads the shared file's bytes cross-device (got ${readBytes})",
            )
        } finally {
            system.shutdown()
        }
    }

    private fun TestClient.deviceCount(): Int {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = IdentityService.Relations.DEVICES,
            ),
            isEntity = true,
            elementSchemaId = Device.ID,
        )
        return executionContext.database.getArrayData(ref)?.toList()?.size ?: 0
    }

    private suspend fun pairHostAndJoiner(system: TestSystem): Pair<TestClient, TestClient> {
        val host = system.client("host", TestClientRole.RealmHost)
        val joiner = system.client("joiner", TestClientRole.JoiningDevice)

        // Every step is checked and names what it got: the bare `as TResult.Success` below
        // used to surface a silent upstream failure as an opaque ClassCastException.
        val bootstrap = host.bootstrapAsHost(deviceName = "Host Tablet")
        assertTrue(bootstrap is TResult.Success, "host bootstrap should succeed; got $bootstrap")
        val started = host.tenshuClient.syncService.startPairing()
        assertTrue(started is TResult.Success, "startPairing should succeed; got $started")
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)

        val initiated = joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Joiner Phone",
            otherDevicePublicKey = parsed.hostPairingPublicKey
        )
        assertTrue(initiated is TResult.Success, "initiatePairing should succeed; got $initiated")
        val pending = host.tenshuClient.syncService.getPendingPairingRequests()
        assertTrue(
            pending is TResult.Success,
            "getPendingPairingRequests should succeed; got $pending" +
                ((pending as? TResult.Failure)?.let { " / error=${it.error}" } ?: ""),
        )
        val requests = pending.value!!.toList()
        assertEquals(1, requests.size, "expected exactly one pending pairing request, got $requests")
        val request = requests.single()
        val approved = host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
        assertTrue(approved is TResult.Success, "approvePairingRequest should succeed; got $approved")
        system.advanceUntil(timeout = 15.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            status is TResult.Success && status.value?.get() == true
        }

        return host to joiner
    }
}
