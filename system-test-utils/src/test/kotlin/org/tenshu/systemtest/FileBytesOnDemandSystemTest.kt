package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.equalTo
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins down the production-shape `downloadFile` chain from
 * `:drive-shared/TenshuFilesRepository`:
 *
 * ```kotlin
 * client.fileService.defaultFileSystem.root
 *     .files
 *     .select { relations(File.Relation.DataProperty) }
 *     .search<File> { this.path equalTo path }
 *     .get()
 * ```
 *
 * `File.Relation.Data` carries `Syncable(SyncStrategy.OnDemand)`
 * (file-service/.../File.kt:72), so on the joiner side the bytes are
 * fetched from the sync relay when this chain materialises
 * `DataProperty`. The wiring is in
 * `:core-common/proxy/SelectImplementation` (writes a
 * `PendingOnDemandProperty` placeholder when local resolve yields null on
 * a `Syncable(OnDemand)` relation) +
 * `:core-common/proxy/MaybeGetImplementation` (terminal that dispatches
 * `OnDemandResolution.resolve(…)` via the registered
 * `OnDemandResolution` component, then re-resolves the property to
 * replace the placeholder).
 *
 * Round-trip wiring (host push + joiner read) is the **two-plane content path**
 * (`docs/adr/0017-file-content-two-plane-channel.md`), the same one production runs: the host's
 * `UploadFileImplementation` calls `FileContentSyncEmitter.emitFileContent(...)`,
 * which chunks the file, AEADs each chunk, `PUT`s them to the content plane, and
 * emits a `FileContentManifestPayload` block. That manifest is **applied
 * OnDemand-only**: `SyncProcessor.processSyncBlock` no-ops a manifest on the
 * regular drain (by design — the chunk fetch is suspending IO), and
 * `processSyncBlockAsync` applies it when `OnDemandResolution.resolve(…)` fires —
 * fetching + decrypting + integrity-gating + reassembling the chunks into
 * `FileDatabase`. So there is **no proactive file-byte push** on this path; bytes
 * arrive when (and only when) the joiner opens the file. (The legacy whole-file
 * `emitPropertyBlock` path is only taken when no `fileContentEmitter` is wired —
 * never in production for file bytes — so the harness, now wired with a personal
 * `ContentBlobStore` to mirror production, no longer exercises it.)
 */
class FileBytesOnDemandSystemTest {

    @Test fun joinerCanFetchHostUploadedBytesOnDemand() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 1_000
            )
            val joiner = system.client(
                deviceId = "joiner",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 1_000
            )

            host.bootstrapAsHost(deviceName = "Host Tablet")
            host.tenshuClient.syncService.startPairing()
            val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)

            joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            val request = host.tenshuClient.syncService.getPendingPairingRequests()
                .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
            host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
            system.advanceUntil(timeout = 30.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            val fileName = "image.bin"
            val filePath = "/$fileName"
            val payload = byteArrayOf(0x42, 0x13, 0x37, 0x55, 0x66, 0x77)

            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = fileName, data = payload)
                        is TResult.Success
            )

            // Wait for the entity to propagate (existing entity-sync path
            // already covers this — `FileEntitySyncSystemTest`).
            system.advanceUntil(timeout = 30.seconds) {
                joiner.executionContext.database.getData(
                    org.tenshu.core.entity.DataId.StringDataId.create(
                        type = File.ID,
                        keyValue = filePath
                    )
                ) != null
            }

            // Drive the OnDemand fetch via the same chain shape the Drive
            // app's `downloadFile` uses, with `File.Field.PATH` added so
            // search can match. The chain returns a projected entity (cast
            // to `org.tenshu.core.Composite` because select produces
            // `Entity<Composite>`, not `File`); we don't assert on its
            // shape — the real assertion is that the bytes the host
            // uploaded are now present in the joiner's local
            // `FileDatabase`, proving the OnDemand resolver fired.
            joiner.tenshuClient.fileService.defaultFileSystem.root
                .files
                .select {
                    fields(File.Field.PATH)
                    relations(File.Data)
                }
                .search<File> { this.path equalTo filePath }
                .resolve()

            // Bytes should now be in the joiner's local FileDatabase as a
            // side-effect of `OnDemandResolutionImpl.resolve` writing the
            // fetched `SyncBlock` through `SyncProcessor`.
            val localBytes = joiner.fileDatabase.readBytes(filePath)
            assertTrue(
                localBytes != null,
                "OnDemandResolution should have written bytes for $filePath to joiner's FileDatabase"
            )
        } finally {
            system.shutdown()
        }
    }

    // NB (SR-file-model 3c.1d): `joinerReceivesProactivelyPushedBytes_withoutExplicitOnDemandFetch`
    // was removed here. It pinned the legacy proactive whole-file `emitPropertyBlock`
    // push — a path production never takes for file bytes (production wires
    // `fileContentEmitter`, so `UploadFileImplementation` always uses the two-plane
    // `emitFileContent`, whose manifest is applied OnDemand-only). Now that the
    // harness mirrors production (personal `ContentBlobStore` wired), there is no
    // proactive file-byte push to assert, and OnDemand delivery is already covered
    // by `joinerCanFetchHostUploadedBytesOnDemand` above.
}
