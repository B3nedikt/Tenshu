package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationUuidUniquenessTest {

    @Test
    fun `two notifications fired in the same ms have different uuids`() = runTest {
        val testProxy = createFileProxy(backgroundScope)

        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/a".toTString())
        )
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/b".toTString())
        )

        val notificationsRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = NotificationService.Relations.NOTIFICATIONS
            ),
            isEntity = false,
            elementSchemaId = Notification.ID
        )
        val notifications = testProxy.executionContext.getArrayData(notificationsRef)
            ?.toList()
            ?.filterIsInstance<FolderAddedNotification>()
            ?: emptyList()

        val uuids = notifications.map { it.uuid.get() }.toSet()
        assertEquals(
            notifications.size, uuids.size,
            "UUIDs must be unique even when timestamps collide."
        )
    }
}
