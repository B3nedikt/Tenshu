package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for the iOS-joiner one-way-sync bug root-caused in
 * `sync-cursor-store-lifecycle.md`: the pull cursor (`SyncManager.lastSyncTimestamp`)
 * persists on a **different lifecycle** than the object store that holds the applied data.
 * On iOS route-1 the cursor is Keychain-backed and survives an app-container wipe / reinstall
 * / `-wipeDeviceState`, while the object store lives in the wiped container. A device then
 * resumes from a **non-zero cursor over an empty store**: `GET /sync/changes?since=<cursor>`
 * returns nothing, so the owner's `Entitlement` and every pre-join block are skipped forever
 * (no proof → `entitlement_required` 402 → one-way sync).
 *
 * **Option A1 (this test):** the cursor is persisted as a row IN the object store, so it shares
 * the applied data's exact lifecycle. Modelling the iOS container wipe with
 * [InMemoryObjectStore.clear] now drops the cursor *together with* the data, so
 * `lastSyncTimestamp` reads 0 and the joiner back-fills from scratch — the desynced
 * "cursor > 0 over an empty store" state is unrepresentable, which is why no startup self-heal
 * exists for it. The harness runs on the JVM but the guarantee is platform-agnostic.
 */
class SyncCursorStoreDesyncSystemTest {

    @Test fun cursorSharesStoreLifecycle_soDesyncCannotOccur() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 1_000
            )
            host.bootstrapAsHost(deviceName = "Host Tablet")

            val folderPath = "/Documents"
            val fileName = "old.txt"
            val filePath = "/$fileName"
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem.createFolder(folderPath)
                    is TResult.Success
            )
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = fileName, data = byteArrayOf(1, 2))
                    is TResult.Success
            )

            val joiner = system.client(
                deviceId = "joiner",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 100
            )
            pair(system, host, joiner)

            // 1. Joiner converges normally: it sees the host's folder + file, cursor advances.
            system.advanceUntil(timeout = 60.seconds) {
                joiner.executionContext.rootFilePaths().containsAll(setOf(folderPath, filePath))
            }
            //    ⚠ Read on a tick boundary, not at the first sight of the rows. A tick applies a
            //    page and only then persists the cursor that page earned, so the rows are visible
            //    first — captured 2026-10-10 with the cursor at 0 and the first-pull marker at
            //    STARTED, 11 ms before the same tick wrote 13 and COMPLETED. `drainNow` cannot
            //    return while that tick is in flight.
            joiner.syncScheduler.drainNow()
            assertTrue(
                joiner.syncManager.lastSyncTimestamp > 0L,
                "joiner cursor should have advanced past the host's blocks"
            )

            // 2. Model the iOS container wipe: clear the object store. A1 persists the cursor as a
            //    row IN that store (not in the Keychain-backed storageDelegate), so the wipe drops
            //    the cursor too — the desynced "cursor > 0 over an empty store" state is now
            //    structurally UNREPRESENTABLE.
            //
            //    ⚠ Through the harness, never a bare `clear()`: the app a container wipe happens
            //    to is not running, and this joiner's scheduler is. See
            //    [TestClient.wipeStoreWhileNotRunning] for what a bare clear raced.
            joiner.wipeStoreWhileNotRunning {
                assertTrue(joiner.database.isEmpty(), "store should be empty after the modelled wipe")
                assertEquals(
                    0L, joiner.syncManager.lastSyncTimestamp,
                    "A1: the cursor lives in the object store, so a store wipe resets it to 0 — it " +
                        "can never survive as a stale cursor over an empty store (the pre-A1 " +
                        "one-way-sync bug)"
                )
                assertFalse(
                    folderPath in joiner.executionContext.rootFilePaths(),
                    "wiped joiner should no longer see the folder"
                )
            }

            // 3. With the cursor reset by the wipe (since=0), the joiner back-fills the whole realm
            //    on its own — no self-heal needed; the desync simply cannot occur.
            system.advanceUntil(timeout = 60.seconds) {
                joiner.executionContext.rootFilePaths().containsAll(setOf(folderPath, filePath))
            }
            val backFilled = joiner.executionContext.rootFilePaths()
            assertTrue(folderPath in backFilled, "joiner should re-see the folder; got $backFilled")
            assertTrue(filePath in backFilled, "joiner should re-see the file; got $backFilled")
        } finally {
            system.shutdown()
        }
    }

    private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
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
        system.advanceUntil(timeout = 60.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            status is TResult.Success && status.value?.get() == true
        }
    }

    private fun ExecutionContext.rootFilePaths(): Set<String> {
        val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = TList.ID,
                parentEntity = rootId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        return (getArrayData(ref)?.toList() ?: emptyList()).map { entry ->
            when (entry) {
                is Entity<*> -> entry.getFieldValue<TString>(File.Field.PATH).get()
                else -> (entry as org.tenshu.core.Composite)
                    .getField<TString>(File.Field.PATH).get()
            }
        }.toSet()
    }
}
