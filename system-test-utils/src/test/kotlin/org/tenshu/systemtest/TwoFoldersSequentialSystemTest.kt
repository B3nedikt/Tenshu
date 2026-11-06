package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.equalTo
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
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
 * Production complaint: phone creates `/A` → desktop shows `/A`. Phone then
 * creates `/B` → desktop now shows only `/B`; `/A` has disappeared. Both
 * folders should remain visible after every subsequent creation.
 *
 * `PostPairingFolderSyncSystemTest` only creates a single folder + a single
 * file, so it doesn't exercise the second-write codepath. This test pins
 * down sequential folder creation specifically, with both the store-level
 * `/.FILES` assertion and the SDK-chain assertion that production reads from.
 */
class TwoFoldersSequentialSystemTest {

    @Test fun joinerSeesAllFolders_createdSequentially() = runBlocking {
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

            // ---- Phase 1: host creates /A. Joiner sees /A. ----
            val folderA = "/A"
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .createFolder(folderA) is TResult.Success
            )
            system.advanceUntil(timeout = 20.seconds) {
                folderA in joiner.executionContext.rootFilePaths()
            }
            assertTrue(
                folderA in joiner.executionContext.rootFilePaths(),
                "After first folder, joiner should see /A; got ${joiner.executionContext.rootFilePaths()}"
            )

            // ---- Phase 2: host creates /B. Joiner sees BOTH /A and /B. ----
            val folderB = "/B"
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .createFolder(folderB) is TResult.Success
            )
            system.advanceUntil(timeout = 20.seconds) {
                joiner.executionContext.rootFilePaths().containsAll(setOf(folderA, folderB))
            }

            val joinerPathsAfterB = joiner.executionContext.rootFilePaths()
            assertTrue(
                folderA in joinerPathsAfterB,
                "After second folder, joiner should STILL see /A; got $joinerPathsAfterB"
            )
            assertTrue(
                folderB in joinerPathsAfterB,
                "After second folder, joiner should also see /B; got $joinerPathsAfterB"
            )

            // ---- Phase 3 (regression depth): same shape with a third folder. ----
            val folderC = "/C"
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .createFolder(folderC) is TResult.Success
            )
            system.advanceUntil(timeout = 20.seconds) {
                joiner.executionContext.rootFilePaths()
                    .containsAll(setOf(folderA, folderB, folderC))
            }

            val joinerPathsAfterC = joiner.executionContext.rootFilePaths()
            assertTrue(
                joinerPathsAfterC.containsAll(setOf(folderA, folderB, folderC)),
                "After third folder, joiner should see /A, /B, /C; got $joinerPathsAfterC"
            )

            // ---- Phase 4: same assertion via the production SDK chain. ----
            // `TenshuFilesRepository.getFiles("/")` uses `root.files.search<...>{}.get()`;
            // this guards against a regression where the underlying DB state is
            // right but the request-chain materialiser doesn't surface it.
            for (path in setOf(folderA, folderB, folderC)) {
                val resolved = joiner.tenshuClient.fileService.defaultFileSystem.root.files
                    .search<File> { this.path equalTo path }
                    .resolve()
                assertTrue(
                    resolved != null,
                    "SDK chain on joiner should surface $path; root.files returned null"
                )
            }
        } finally {
            system.shutdown()
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
