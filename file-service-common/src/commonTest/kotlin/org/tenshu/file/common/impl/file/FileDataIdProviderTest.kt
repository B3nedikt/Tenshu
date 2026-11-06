package org.tenshu.file.common.impl.file

import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.file.common.fileServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileDataIdProviderTest {

    @Test
    fun getDataId_returnsCorrectDataId() {
        val testProxy = TestProxy().apply {
            schemaRegistry.registerConfig(fileServiceCommonModule)
        }

        val file = File.create(
            context = testProxy.tContext,
            path = "/test/path.txt",
        )

        val entity = Entity.create<File>(
            context = testProxy.tContext,
            composite = file
        )

        val dataId = testProxy.executionContext.getDataId(entity)

        assertTrue(dataId is DataId.StringDataId)
        assertEquals(File.ID, dataId.type.get())
        assertEquals("/test/path.txt", dataId.keyValue.get())
    }
}
