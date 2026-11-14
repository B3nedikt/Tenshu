package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.SaveOutcomeAwareDataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.id
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **P0a of `docs/proposals/core-deterministic-invalidation.md` §8.4** — what is the self-echo, as the
 * store sees it?
 *
 * ⚠ A measurement pinned as a test, not a behaviour test. Nothing here changes what any save does:
 * the probe is a [SaveOutcomeAwareDataObserver] that records every `File` save on the host with its
 * [WriteOrigin] and whether the store reported it redundant (every written entry byte-identical to
 * what was already stored). Authorship is fixed by the scenario, not read off an HLC: only the host
 * uploads `own.txt`, so a `SYNC_INGEST` save of it on the host *is* its own echo; only the joiner
 * uploads `peer.txt`, so a `SYNC_INGEST` save of it on the host *is* a peer's write.
 *
 * ⚠ Corrects the proposal's §2.3: the echo is reproducible off-device, because [TestSystem] runs a
 * real in-process relay.
 *
 * The assertions pin the two facts §8 stands on — if either goes red, §8's predicate no longer
 * separates what it claims to, and P1 must not ship on it.
 */
class SelfEchoRedundancySystemTest {

    private data class FileSave(val path: String, val origin: WriteOrigin, val redundant: Boolean)

    private class FileSaveProbe : SaveOutcomeAwareDataObserver {
        val saves = mutableListOf<FileSave>()

        override fun onDataSaved(data: Data, origin: WriteOrigin, redundant: Boolean) {
            val path = pathOf(data) ?: return
            synchronized(saves) { saves += FileSave(path, origin, redundant) }
        }

        override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit

        fun of(path: String): List<FileSave> = synchronized(saves) { saves.filter { it.path == path } }

        private fun pathOf(data: Data): String? = when {
            data is Entity<*> && data.type.get() == File.ID ->
                data.getFieldValue<TString>(File.Field.PATH).get()
            data !is Entity<*> && data.id == File.ID ->
                (data as Composite).getField<TString>(File.Field.PATH).get()
            else -> null
        }
    }

    @Test fun ownEchoIsRedundant_peerWriteIsNot() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost, pollingIntervalMs = 200)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice, pollingIntervalMs = 200)
            pair(system, host, joiner)

            val probe = FileSaveProbe()
            host.executionContext.addDataObserver(probe)

            // The host's own write, and its echo back from the relay.
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile("/", "own.txt", byteArrayOf(1, 2, 3)) is TResult.Success
            )
            system.advanceUntil(timeout = 30.seconds) {
                probe.of("/own.txt").any { it.origin == WriteOrigin.SYNC_INGEST }
            }

            // A peer's write, arriving at the host.
            assertTrue(
                joiner.tenshuClient.fileService.defaultFileSystem
                    .uploadFile("/", "peer.txt", byteArrayOf(4, 5, 6)) is TResult.Success
            )
            system.advanceUntil(timeout = 30.seconds) {
                probe.of("/peer.txt").any { it.origin == WriteOrigin.SYNC_INGEST }
            }

            val own = probe.of("/own.txt")
            val peer = probe.of("/peer.txt")
            println("P0a own.txt  saves on host: $own")
            println("P0a peer.txt saves on host: $peer")

            // ⚠⚠ The safety half: a peer's write is never redundant. If this goes red the predicate
            // would suppress a real update — §0.6's failure, the one that must never ship.
            assertTrue(
                peer.filter { it.origin == WriteOrigin.SYNC_INGEST }.none { it.redundant },
                "a peer's write must never read as redundant: $peer",
            )
            // The benefit half: the host's own echo writes back bytes it already holds.
            assertTrue(
                own.filter { it.origin == WriteOrigin.SYNC_INGEST }.all { it.redundant },
                "the host's own echo should be byte-identical to what it stored: $own",
            )
        } finally {
            system.shutdown()
        }
    }

    private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
        host.bootstrapAsHost(deviceName = "Host")
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Joiner",
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
