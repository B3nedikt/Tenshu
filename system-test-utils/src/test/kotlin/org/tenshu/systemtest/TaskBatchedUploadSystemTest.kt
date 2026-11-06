package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.equalTo
import org.tenshu.core.file.File
import org.tenshu.core.file.Data
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
import org.tenshu.sync.GenericTaskSpec
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.register
import org.tenshu.sync.client.syncService
import org.tenshu.sync.client.taskService
import org.tenshu.sync.client.withTask
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * TS-5 (`core-background-processing.md` §10.6): the folder-upload shape under
 * ONE task — createFolder + N uploadFiles with REAL bytes inside `withTask`.
 * Proves §10.5 D8 (entity-before-bytes under staging) in anger over the wire:
 * File entities stage into the task's lane and arrive as `DeltaBatchPayload`
 * blocks; the content plane (chunks + manifest) travels independently, and
 * the joiner's OnDemand read still reassembles byte-identical content after
 * the batch lands.
 */
class TaskBatchedUploadSystemTest {

    @Test fun taskWrappedFolderUpload_batchesEntities_bytesFetchOnDemand() = runBlocking<Unit> {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost, pollingIntervalMs = 1_000)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice, pollingIntervalMs = 1_000)

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

            // ---- Folder-upload shape: one task, folder + N files with bytes. ----
            val count = 12
            fun payloadFor(i: Int) = ByteArray(64) { b -> ((b + i * 7) % 251).toByte() }

            val task = host.tenshuClient.taskService.register(
                GenericTaskSpec.create(host.executionContext.context, "drive/folder-upload")
            )
            assertNotNull(task, "TaskService must be available on the host")

            withTask(task) {
                assertTrue(
                    host.tenshuClient.fileService.defaultFileSystem.createFolder("/Batch")
                        is TResult.Success
                )
                repeat(count) { i ->
                    // Root-parented so the D8 probe below can use the same
                    // root.files read chain as FileBytesOnDemandSystemTest;
                    // /Batch covers the folder-entity-in-batch case.
                    val up = host.tenshuClient.fileService.defaultFileSystem.uploadFile(
                        parentPath = "/",
                        fileName = "f$i.bin",
                        data = payloadFor(i)
                    )
                    assertTrue(up is TResult.Success, "uploadFile $i should succeed; got $up")
                }
            }
            assertTrue(task.complete() is TResult.Success)

            // ---- Joiner converges on all File entities (via the batch blocks). ----
            fun joinerHas(path: String): Boolean = joiner.executionContext.database.getData(
                org.tenshu.core.entity.DataId.StringDataId.create(type = File.ID, keyValue = path)
            ) != null

            system.advanceUntil(timeout = 30.seconds) {
                joinerHas("/Batch") && (0 until count).all { joinerHas("/f$it.bin") }
            }

            // ---- D8: bytes still fetch on demand, byte-identical (content
            // plane is independent of the batch; delta HLC preceded the
            // manifest — ADR 0015 amendment). Same read chain as
            // FileBytesOnDemandSystemTest.
            val probePath = "/f7.bin"
            joiner.tenshuClient.fileService.defaultFileSystem.root
                .files
                .select {
                    fields(File.Field.PATH)
                    relations(File.Data)
                }
                .search<File> { this.path equalTo probePath }
                .resolve()

            val localBytes = assertNotNull(
                joiner.fileDatabase.readBytes(probePath),
                "OnDemand resolve must deliver the bytes for $probePath"
            )
            assertContentEquals(payloadFor(7), localBytes.get(), "bytes must round-trip byte-identical")
        } finally {
            system.shutdown()
        }
    }
}
