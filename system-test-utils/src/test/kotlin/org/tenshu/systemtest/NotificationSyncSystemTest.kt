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
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
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
 * Production complaint: notifications never show up on the joiner side.
 *
 * Expected behaviour: when the host calls `FileSystem.createFolder("/X")`,
 * the impl writes a `FolderAddedNotification` into
 * `NotificationService.Relations.NOTIFICATIONS`. That notification should
 * propagate to every paired device so each device's notification stream
 * surfaces the change.
 *
 * Current behaviour (what this test pins down):
 *  1. `Notification.Schema` carries no `Syncable` trait, so even if the
 *     observer fired the notification would not be emitted as a `SyncBlock`.
 *  2. `CreateFolderImplementation` writes the notification via
 *     `executionContext.addDataToArray(...)` which goes through
 *     `Database.addToArray` → `Database.saveData` — bypassing
 *     `ExecutionContext.saveData` and therefore never triggering `SyncObserver`.
 *
 * Both layers conspire so the host never even tries to push a notification
 * block; the joiner has nothing to receive. Failing on master (before any fix).
 */
class NotificationSyncSystemTest {

    @Test fun folderAddedNotification_propagatesFromHostToJoiner() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

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

            val folderPath = "/Shared"
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .createFolder(folderPath) is TResult.Success,
                "Host createFolder should succeed"
            )

            // Host's own notification relation must contain the FolderAddedNotification.
            // Sanity check — guards against the test claiming the joiner is broken
            // when actually the host's writer never wrote.
            assertTrue(
                host.executionContext.notificationPaths().contains(folderPath),
                "Host should see its own FolderAddedNotification for $folderPath; " +
                    "got ${host.executionContext.notificationPaths()}"
            )

            // Joiner must observe the same notification after sync drains.
            // Will time out on master — Notification isn't Syncable, and the
            // impl writes via addDataToArray which bypasses SyncObserver.
            system.advanceUntil(timeout = 15.seconds) {
                joiner.executionContext.notificationPaths().contains(folderPath)
            }

            val joinerPaths = joiner.executionContext.notificationPaths()
            assertTrue(
                folderPath in joinerPaths,
                "Joiner should observe FolderAddedNotification for $folderPath after sync; " +
                    "got $joinerPaths"
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * Read the joiner's notification relation as paths. The relation array
     * lives at `RelationDataId(NotificationService.Relations.NOTIFICATIONS)` —
     * the same target `addDataToArray` writes to on the host side. If the
     * notification propagated, the deserialized `FolderAddedNotification`
     * (or its parent type) will appear here on the joiner.
     */
    private fun ExecutionContext.notificationPaths(): Set<String> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = context,
                type = TList.ID,
                relationId = NotificationService.Relations.NOTIFICATIONS
            ),
            isEntity = false,
            elementSchemaId = Notification.ID
        )
        val entries = getArrayData(ref)?.toList() ?: emptyList()
        return entries.mapNotNull { entry ->
            // FolderAddedNotification.PATH lives under the FileAddedNotification
            // parent's field (id = "tenshu/notifications/file/added/path"). Read
            // it directly off the entity/composite using the field ref string.
            val pathField = "tenshu/notifications/file/added/path"
            when (entry) {
                is Entity<*> -> runCatching {
                    entry.getFieldValue<TString>(pathField).get()
                }.getOrNull()
                else -> runCatching {
                    (entry as org.tenshu.core.Composite).getField<TString>(pathField).get()
                }.getOrNull()
            }
        }.toSet()
    }
}
