package org.tenshu.notifications.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.notifications.AppRegisteredNotification
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.notifications.SendNotification
import org.tenshu.notifications.test.createNotificationProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyNotificationServiceUnitTest {

    @Test
    fun sendNotificationIsWorking() = runTest {
        val testProxy = createNotificationProxy(this)

        val uuid = "test-uuid"
        val timestamp = 123456789L
        val appName = "TestApp"

        val notification = AppRegisteredNotification.create(
            context = testProxy.executionContext.context,
            uuid = uuid,
            timestamp = timestamp,
            appName = appName
        )

        val serializedNotification = testProxy.serializer.serialize(notification)

        val request = byteArrayOf(
            *idToUuid(NotificationService.ID),
            *idToUuid(SendNotification.ID),
            *serializedNotification
        )

        val resultBytes = testProxy.handleRequest(request)

        assertContentEquals(
            expected = byteArrayOf(
                *idToUuid(TResult.Success.ID),
            ),
            actual = resultBytes
        )

        // Verify notification is in the list
        val flowId = 1L
        val notificationsRequest = byteArrayOf(
            *idToUuid(NotificationService.ID),
            *idToUuid(NotificationService.Relations.NOTIFICATIONS),
            *idToUuid(Stream.ID),
            *flowId.toByteArray()
        )

        testProxy.handleRequest(notificationsRequest)

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }

        // We expect at least 1 update (initial state). 
        // If SendNotification was successful, the list should already have the notification.
        assertTrue(valueUpdates.isNotEmpty())
        val update = valueUpdates.first()

        @Suppress("UNCHECKED_CAST")
        val notifications = (update.payload as TList<Notification>).toList()

        assertEquals(1, notifications.size)
        val received = notifications.first() as AppRegisteredNotification
        assertEquals(uuid, received.uuid.get())
        assertEquals(timestamp, received.timestamp.get())
        assertEquals(appName, received.appName.get())
    }
}
