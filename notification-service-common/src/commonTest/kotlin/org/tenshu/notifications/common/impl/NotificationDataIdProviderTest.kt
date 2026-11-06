package org.tenshu.notifications.common.impl

import org.tenshu.core.common.TestProxy
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.notifications.Notification
import org.tenshu.notifications.common.notificationServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationDataIdProviderTest {

    @Test
    fun getDataId_returnsCorrectDataId() {
        val testProxy = TestProxy().apply {
            schemaRegistry.registerConfig(notificationServiceCommonModule)
        }

        val notification = testProxy.tContext.create<Notification>(
            type = Notification.ID,
            fields = listOf("uuid-123", 123456789L)
        )

        val entity = Entity.create<Notification>(
            context = testProxy.tContext,
            composite = notification
        )

        val dataId = testProxy.executionContext.getDataId(entity)

        assertTrue(dataId is DataId.StringDataId)
        assertEquals(Notification.ID, dataId.type.get())
        assertEquals("uuid-123", dataId.keyValue.get())
    }
}
