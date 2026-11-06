package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.File
import org.tenshu.core.file.Size
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.file.client.fileService
import org.tenshu.sync.SyncDelta
import org.tenshu.sync.DeltaBatchPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import org.tenshu.core.collection.list.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.tenshu.core.core.schemaProvider

/**
 * TS-1 wire round trip for the task-scoped sync lane
 * (`core-background-processing.md` §10.2): a hand-built [DeltaBatchPayload]
 * container block — TS-1 has no production producer yet — rides the host's
 * REAL pending-blocks drain, relay push/pull, and the joiner's
 * `SyncProcessor` ingest:
 *
 * - two fresh entity deltas materialise as `File` rows on the joiner (and
 *   surface in the joiner's locally-derived root `FILES` membership — the
 *   §10.1 corrected premise: membership is never on the wire);
 * - a deliberately STALE delta for an organically-uploaded file loses the
 *   per-entity HLC merge to the organic block, proving the inner-HLC
 *   semantics survive the full wire path, not only the unit harness
 *   (`DeltaBatchPayloadIngestTest`).
 */
class DeltaBatchSyncSystemTest {

    @Test fun checkpointContainer_roundTripsToJoiner_withPerDeltaConflictSemantics() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

            // ---- Pair host + joiner (same shape as FolderSyncSystemTest). ----
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
            system.advanceUntil(timeout = 15.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            // ---- Organic upload: the conflict target (Size = 3 bytes). ----
            val conflictPath = "/cp-conflict.txt"
            val uploadResult = host.tenshuClient.fileService.defaultFileSystem
                .uploadFile(parentPath = "/", fileName = "cp-conflict.txt", data = byteArrayOf(7, 8, 9))
            assertTrue(uploadResult is TResult.Success, "organic upload should succeed; got $uploadResult")

            // ---- Hand-build the checkpoint container on the host. ----
            val context = host.executionContext.context
            val realm = host.syncManager.currentRealmContext()
                ?: error("host realm context must exist after bootstrap")

            fun fileEntity(path: String, size: Long): Entity<File> = Entity.create(
                context = context,
                composite = File.create(context, path),
            ) {
                set(File.Size, size)
            }

            val pathA = "/cp-a.txt"
            val pathB = "/cp-b.txt"
            val staleHlc = HybridLogicalClock.create(
                context = context,
                physicalTime = 1_000L, // far older than the organic upload's tick
                logicalCounter = 0L,
                deviceId = "device-host"
            )
            val payload = DeltaBatchPayload.create(
                context = context,
                deltas = listOf(
                    SyncDelta.create(context, host.syncManager.hlcManager.tick(), fileEntity(pathA, 111L), deltaId = "st-a"),
                    SyncDelta.create(context, host.syncManager.hlcManager.tick(), fileEntity(pathB, 222L), deltaId = "st-b"),
                    // Stale delta for the organically-uploaded file: must LOSE
                    // the per-entity merge on the joiner (inner HLC, not the
                    // outer block HLC, drives the compare).
                    SyncDelta.create(context, staleHlc, fileEntity(conflictPath, 555L), deltaId = "st-stale"),
                )
            )
            // Envelope bound as AAD (sync-envelope-authentication §3.2), as the emitter seals it.
            val block = EnvelopeAuth.sealSyncBlock(
                crypto = context.syncCryptoManager(),
                context = context,
                blockId = "delta-batch-system-test-1",
                ownerId = realm.ownerId,
                hlc = host.syncManager.hlcManager.tick(),
                targetEntityIdHash = null, // §10.2 D2: containers carry no wire routing hash
                plaintext = BlockPayloadEnvelope.seal(
                    BinarySerializer().serialize(payload),
                    BlockPayloadEnvelope.stampOf(context.schemaProvider(), context),
                ),
                identityKey = realm.derivedKeys.sharedIdentityKey,
            )

            // Enqueue onto the host's REAL pending-blocks drain (the exact
            // ArrayDatabaseReference shape SyncObserver.generateSyncBlock uses)
            // so relay push, pull, and ingest are all production paths.
            val pendingBlocksRef = ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = context,
                    type = SyncBlock.ID,
                    relationId = SyncService.Collections.PENDING_BLOCKS
                ),
                isEntity = false,
                elementSchemaId = SyncBlock.ID
            )
            host.executionContext.database.addToArray(pendingBlocksRef, block)

            // ---- Joiner converges. ----
            @Suppress("UNCHECKED_CAST")
            fun joinerFile(path: String): Entity<out File>? =
                joiner.executionContext.database.getData(
                    DataId.StringDataId.create(type = File.ID, keyValue = path),
                    asEntity = true
                ) as? Entity<out File>

            system.advanceUntil(timeout = 15.seconds) {
                joinerFile(pathA) != null && joinerFile(pathB) != null && joinerFile(conflictPath) != null
            }

            // Fresh deltas applied with their carried properties.
            assertEquals(111L, assertNotNull(joinerFile(pathA)).get(File.Size)?.get())
            assertEquals(222L, assertNotNull(joinerFile(pathB)).get(File.Size)?.get())

            // The stale delta lost: the organic upload's Size (3) survives.
            assertEquals(
                3L,
                assertNotNull(joinerFile(conflictPath)).get(File.Size)?.get(),
                "stale checkpoint delta must lose the per-entity HLC merge to the organic block"
            )
        } finally {
            system.shutdown()
        }
    }
}
