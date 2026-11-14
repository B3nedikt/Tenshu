package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.replaceFile
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * **P2 of `docs/proposals/core-deterministic-invalidation.md` §8.4** — the listing-level contract of
 * P1, at the array-listener seam every reactive read of `Folder.files` hangs off
 * (`StreamRegistry.registerRefForStream` → `setArrayListener`).
 *
 * P1 silences the steady-state notification for a **redundant** save — every entry already stored
 * byte-identically. These pin the cases that decide whether that is safe:
 *
 * 1. **This device's own echo does not notify.** The benefit P0a measured.
 * 2. ⚠⚠ **A peer's edit of a row this device already lists still notifies.** The failure §0.6 exists
 *    to prevent. Membership does not change, so the gated steady-state branch is the *only* path to
 *    the listener: silencing it, or keying it on `SYNC_INGEST` (the withdrawn authorship test),
 *    turns this red.
 * 3. A peer's new file, and a wiped device refilling its own history (§0.7 F1), reach the listing.
 *    ⚠ Neither can be broken by P1's gate — a membership change arrives through the ungated add
 *    and diff-fallback paths — so these guard the listing end to end, not the predicate.
 */
class ListingNotificationSystemTest {

    @Test fun ownEchoDoesNotNotify_peerEditAndPeerAddDo() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairedPair(system)
            val echoes = IngestProbe().also { host.executionContext.addDataObserver(it) }
            val notified = RootListingProbe().also { it.attach(host) }
            // ⚠ Attached before the upload, like every probe here: the joiner ingests the host's file
            // exactly once, and nothing orders that ingest after the host's own echo — which lands
            // inside `upload` itself. A probe attached later can miss the one event it waits for, and
            // the wait below then runs out its timeout (seen under load).
            val joinerSaw = IngestProbe().also { joiner.executionContext.addDataObserver(it) }

            assertTrue(upload(host, "own.txt") is TResult.Success)
            system.advanceUntil(timeout = 30.seconds) { echoes.sawIngestOf("/own.txt") }

            assertEquals(
                listOf(WriteOrigin.USER),
                notified.originsFor("/own.txt"),
                "the host's own upload notifies once; its echo from the relay must not notify again",
            )

            // A peer's edit of the same row: it lands as a steady-state re-save on the host, the one
            // branch P1 gates. Wait for the joiner to hold the row first, so its edit is an edit.
            system.advanceUntil(timeout = 30.seconds) { joinerSaw.sawIngestOf("/own.txt") }
            assertTrue(
                joiner.tenshuClient.fileService.defaultFileSystem
                    .replaceFile(parentPath = "/", fileName = "own.txt", data = "edited by the peer".encodeToByteArray()) is TResult.Success,
            )
            system.advanceUntil(timeout = 30.seconds) {
                WriteOrigin.SYNC_INGEST in notified.originsFor("/own.txt")
            }

            assertTrue(upload(joiner, "peer.txt") is TResult.Success)
            // ⚠ Asserted as "notified at all", not by origin: a peer's file arrives with its parent
            // folder's block, whose row rewrite reaches the listener through `setArrayListener`'s
            // diff fallback — and that path reports `USER`, because the save's origin is not
            // visible there (`DatabaseImpl.setArrayListener`). What matters here is that it arrives.
            system.advanceUntil(timeout = 30.seconds) { notified.originsFor("/peer.txt").isNotEmpty() }
        } finally {
            system.shutdown()
        }
    }

    @Test fun aWipedDeviceRefillingItsOwnHistory_notifiesTheListing() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (_, joiner) = pairedPair(system)
            val echoes = IngestProbe().also { joiner.executionContext.addDataObserver(it) }

            // The joiner authors a file, and its own echo lands — so the relay holds a block whose
            // HLC names the joiner. That is exactly the block a wiped joiner will replay.
            assertTrue(upload(joiner, "mine.txt") is TResult.Success)
            system.advanceUntil(timeout = 30.seconds) { echoes.sawIngestOf("/mine.txt") }

            // The store (and the cursor that lives in it) goes, and the device back-fills from the
            // relay. The probe is attached inside the wipe, before the sync loop restarts, so the
            // refill cannot land ahead of it.
            val notified = RootListingProbe()
            joiner.wipeStoreWhileNotRunning { notified.attach(joiner) }

            // The assertion is the wait itself: it times out if the refill never reaches the listing.
            system.advanceUntil(timeout = 60.seconds) { notified.originsFor("/mine.txt").isNotEmpty() }
        } finally {
            system.shutdown()
        }
    }

    /** Every `onAdded` on the root folder's `files` array — what a root listing re-evaluates on. */
    private class RootListingProbe {
        private val events = mutableListOf<Pair<String, WriteOrigin>>()

        fun attach(client: TestClient) {
            val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
            client.database.setArrayListener(
                reference = ArrayDatabaseReference(
                    arrayId = DataId.EntityFieldDataId.create(
                        type = TList.ID,
                        parentEntity = rootId,
                        fieldRef = Folder.Fields.FILES,
                    ),
                    isEntity = false,
                ),
                onAdded = { value, origin -> pathOf(value)?.let { synchronized(events) { events += it to origin } } },
                onRemoved = { _, _ -> },
            )
        }

        fun originsFor(path: String): List<WriteOrigin> =
            synchronized(events) { events.filter { it.first == path }.map { it.second } }
    }

    /** A plain observer: one that wants the redundancy bit would make every save on the device pay for it. */
    private class IngestProbe : DataObserver {
        private val ingested = mutableSetOf<String>()

        override fun onDataSaved(data: Data, origin: WriteOrigin) {
            if (origin != WriteOrigin.SYNC_INGEST) return
            pathOf(data)?.let { synchronized(ingested) { ingested += it } }
        }

        override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit

        fun sawIngestOf(path: String): Boolean = synchronized(ingested) { path in ingested }
    }

    private suspend fun upload(client: TestClient, name: String) =
        client.tenshuClient.fileService.defaultFileSystem.uploadFile("/", name, name.encodeToByteArray())

    private suspend fun pairedPair(system: TestSystem): Pair<TestClient, TestClient> {
        val host = system.client("host", TestClientRole.RealmHost, pollingIntervalMs = 200)
        val joiner = system.client("joiner", TestClientRole.JoiningDevice, pollingIntervalMs = 200)
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
        return host to joiner
    }

    private companion object {
        fun pathOf(data: Data): String? = when {
            data is Entity<*> && data.type.get() == File.ID ->
                data.getFieldValue<TString>(File.Field.PATH).get()
            data !is Entity<*> && data.id == File.ID ->
                (data as Composite).getField<TString>(File.Field.PATH).get()
            else -> null
        }
    }
}
