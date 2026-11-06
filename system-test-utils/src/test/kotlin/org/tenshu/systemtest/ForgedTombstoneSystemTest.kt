package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.file.client.fileService
import org.tenshu.identity.Device
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.proxy.EnvelopeRejections
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * ⭐ Threat model T-T-02 end to end (`sync-envelope-authentication.md` §3.4): the **relay** writes
 * tombstones straight into its own store — valid wire ids for every entity it holds, an HLC far in
 * the future, a Device tombstone with a `revokedDeviceId` among them — and two paired devices pull
 * them through the real `/sync/log` path. Before envelope authentication each one deleted its row on
 * every device and moved the realm epoch. Now every device drops them: rows stay, the epoch and the
 * clock do not move. A genuine delete afterwards still propagates, so the green is not "ingest is off".
 */
class ForgedTombstoneSystemTest {

    private val farFuture = 4_000_000_000_000L

    @Test
    fun theRelayCannotForgeADelete() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost, pollingIntervalMs = 200)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice, pollingIntervalMs = 200)
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = assertNotNull(host.syncManager.currentOwnerId)
            pair(system, host, joiner)

            assertTrue(host.tenshuClient.fileService.defaultFileSystem.createFolder("/Kept") is TResult.Success)
            system.advanceUntil(timeout = 30.seconds) { "/Kept" in joiner.executionContext.rootFilePaths() }
            assertTrue("/Kept" in joiner.executionContext.rootFilePaths(), "precondition: the folder synced")

            val epochBefore = joiner.syncManager.currentRealmEpoch
            val wireIds = system.syncMailbox.getChanges(ownerId, 0L).blocks
                .mapNotNull { it.targetEntityIdHash?.get()?.takeIf(String::isNotEmpty) }
                .toSet()
            assertTrue(wireIds.isNotEmpty(), "precondition: the relay holds named blocks")

            // ── the relay forges: untagged, garbage-tagged, and a Device revocation ──
            val ctx = host.executionContext.context
            val forged = wireIds.flatMapIndexed { i, wireId ->
                listOf(
                    forge(ctx, ownerId, "forged-$i", wireId, TOMBSTONE_NON_DEVICE, null, tag = null),
                    forge(ctx, ownerId, "forged-tag-$i", wireId, TOMBSTONE_NON_DEVICE, null, tag = ByteArray(32) { 7 }),
                )
            } + forge(
                ctx, ownerId, "forged-revoke", wireIds.first(), Device.ID,
                revoked = assertNotNull(joiner.syncManager.currentDeviceId), tag = ByteArray(32) { 7 },
            )
            val rejectedBefore = EnvelopeRejections.tombstoneCount
            forged.forEach { system.syncMailbox.pushTombstone(ownerId, it) }

            // Both devices pull their log, so each forgery is dropped twice. Waiting on the rejection
            // count is the honest anchor: it moves only when a device actually evaluated one.
            system.advanceUntil(timeout = 30.seconds) {
                EnvelopeRejections.tombstoneCount - rejectedBefore >= 2L * forged.size
            }
            assertTrue(
                EnvelopeRejections.tombstoneCount - rejectedBefore >= 2L * forged.size,
                "every forged tombstone must be pulled and evaluated by both devices, or this proves nothing",
            )

            assertTrue("/Kept" in joiner.executionContext.rootFilePaths(), "the joiner keeps the folder")
            assertTrue("/Kept" in host.executionContext.rootFilePaths(), "the host keeps the folder")
            assertEquals(epochBefore, joiner.syncManager.currentRealmEpoch, "the realm epoch did not move")
            assertTrue(
                joiner.syncManager.hlcManager.current().physicalTime.get() < farFuture,
                "the forged HLC did not drag the joiner's clock",
            )

            // ── a genuine delete still propagates ──
            val deleted = host.tenshuClient.fileService.defaultFileSystem.createFolder("/Doomed")
            assertTrue(deleted is TResult.Success)
            system.advanceUntil(timeout = 30.seconds) { "/Doomed" in joiner.executionContext.rootFilePaths() }
            host.deleteFolder("/Doomed")
            system.advanceUntil(timeout = 30.seconds) { "/Doomed" !in joiner.executionContext.rootFilePaths() }
            assertTrue("/Doomed" !in joiner.executionContext.rootFilePaths(), "a genuine tombstone still deletes")
        } finally {
            system.shutdown()
        }
    }

    private fun forge(
        ctx: org.tenshu.core.core.TContext,
        ownerId: String,
        blockId: String,
        wireId: String,
        type: String,
        revoked: String?,
        tag: ByteArray?,
    ): DeleteBlock = DeleteBlock.create(
        context = ctx,
        blockId = blockId,
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(ctx, farFuture, 0L, "relay-forger"),
        targetEntityIdHash = wireId,
        targetEntityType = type,
        revokedDeviceId = revoked,
        authTag = tag,
    )

    private suspend fun TestClient.deleteFolder(path: String) {
        val result = tenshuClient.fileService.defaultFileSystem.deleteFile(path)
        assertTrue(result is TResult.Success, "delete $path: $result")
    }

    private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(assertNotNull(host.syncManager.getPairingString()))
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Joiner Phone",
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

    private fun ExecutionContext.rootFilePaths(): Set<String> {
        val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(type = TList.ID, parentEntity = rootId, fieldRef = Folder.Fields.FILES),
            isEntity = false,
        )
        return (getArrayData(ref)?.toList() ?: emptyList()).map { entry ->
            when (entry) {
                is Entity<*> -> entry.getFieldValue<TString>(File.Field.PATH).get()
                else -> (entry as org.tenshu.core.Composite).getField<TString>(File.Field.PATH).get()
            }
        }.toSet()
    }

    private companion object {
        /** `TombstoneWireType.NON_DEVICE` (internal to `:sync-service-proxy`). */
        const val TOMBSTONE_NON_DEVICE = "tenshu/entity"
    }
}
