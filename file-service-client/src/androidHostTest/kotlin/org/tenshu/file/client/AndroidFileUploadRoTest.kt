package org.tenshu.file.client

import org.tenshu.core.Data
import org.tenshu.core.FileUploader
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.createTestContext
import org.tenshu.core.request.RoProvider
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.core.common.request.TaskRequestChain
import org.tenshu.core.core.TContext
import org.tenshu.core.file.system.UploadSourceFile
import org.tenshu.core.id
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.Type
import org.tenshu.file.test.createFileTestRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidFileUploadRoTest {

    @Test
    fun uploadsFileAndWrapsChainInRequestObject() {
        val recorded = mutableListOf<String>()
        val uploader = object : FileUploader {
            override fun upload(uri: String) {
                recorded += uri
            }
        }

        val capturedChains = mutableListOf<RequestChain>()
        val roProvider = object : RoProvider {
            override fun <T : Data> createRequestObject(
                type: Type,
                requestChain: RequestChain,
                context: TContext
            ): T {
                capturedChains += requestChain
                @Suppress("UNCHECKED_CAST")
                return StubRequestObject(context, requestChain) as T
            }
        }

        val context = createTestContext(
            schemaRegistry = createFileTestRegistry(),
            additionalComponents = mapOf(
                FileUploader.COMPONENT_ID to uploader,
                RoProvider.COMPONENT_ID to roProvider,
            ),
        )

        val taskExecutor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> =
                error("execute should not be called from this test")
        }
        val ro = UploadSourceFileRo(TaskRequestChain(taskExecutor, context), context)

        ro.uploadFile("/", "file.txt", "content://mock/1")

        assertEquals(listOf("content://mock/1"), recorded)
        assertEquals(UploadSourceFile.ID, capturedChains.single().getCallstack().last().id)
    }

    private class StubRequestObject(
        override val context: TContext,
        override val requestChain: RequestChain,
    ) : RequestObject
}
