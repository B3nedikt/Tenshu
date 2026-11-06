package org.tenshu.notifications.proxy


import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.file.FileService
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateFolderNotificationUnitTest {

    @Test
    fun createFolder_EmitsFileAddedNotification() = runTest {
        val testProxy = createFileProxy(this)
        val flowId = 1L
        val testPath = "/new_folder"

        // 1. Start listening to notifications
        val notificationsRequest = byteArrayOf(
            *idToUuid(NotificationService.ID),
            *idToUuid(NotificationService.Relations.NOTIFICATIONS),
            *idToUuid(Stream.ID),
            *flowId.toByteArray()
        )
        testProxy.handleRequest(notificationsRequest)

        // 2. Create a folder
        val createFolderRequest = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(CreateFolder.ID),
            *testPath.encodeToByteArray().size.toLong().toByteArray(),
            *testPath.encodeToByteArray()
        )
        testProxy.handleRequest(createFolderRequest)

        // Wait for the async listener-launched onUpdate to drain.
        for (i in 1..50) {
            val n = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
                .count { it.streamId.get() == flowId }
            if (n >= 2) break
            delay(20)
        }

        // 3. Verify notification was emitted
        val valueUpdates = testProxy.updates
            .filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }

        // We expect 2 updates:
        // 1. Initial emission (empty list)
        // 2. Notification emission (individual item)
        assertEquals(
            2,
            valueUpdates.size,
            "Should have 2 updates: initial empty and then the notification"
        )

        val initialUpdate = valueUpdates[0]

        @Suppress("UNCHECKED_CAST")
        val initialNotifications = (initialUpdate.payload as TList<Notification>).toList()
        assertEquals(0, initialNotifications.size)

        val update = valueUpdates[1]
        assertEquals(flowId, update.streamId.get())

        val notification = update.payload as FolderAddedNotification
        assertEquals(testPath, notification.path.get())
    }
}
