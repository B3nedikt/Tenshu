package org.tenshu.notifications.proxy.integration


import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.drop
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.core.TContext
import org.tenshu.core.get
import org.tenshu.core.notifications.NotificationService
import org.tenshu.file.test.createFileProxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationLocalStreamingTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setUp() {
        tContext = createTestContext()
    }

    @Test
    fun localStreaming_PagingSourceWorks() = runTest {
        val testProxy = createFileProxy(this)
        val executor = LocalTaskExecutor(testProxy.executionContext)

        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = testProxy.schemaRegistry,
            requestChainFactory = { context ->
                LocalRequestChain(executor, context)
            },
            tContext = testProxy.executionContext.context
        )

        val notificationService = env.getService<NotificationService>(NotificationService.ID)

        // Test drop/take which is used by paging
        val flow = notificationService.notifications.drop(0).take(10).flow()

        val initial = flow.first()
        if (initial is TResult.Failure) {
            println("Stream failed: ${initial.error}")
            val errorMsg =
                initial.error.getField<org.tenshu.core.TString>(org.tenshu.core.GenericError.Fields.MESSAGE)
                    .get()
            assertTrue(false, "Stream failed with error: $errorMsg")
        }
        assertTrue(initial is TResult.Success<*>, "Expected Success but got $initial")
        assertEquals(0, initial.get().toList().size)
    }
}
