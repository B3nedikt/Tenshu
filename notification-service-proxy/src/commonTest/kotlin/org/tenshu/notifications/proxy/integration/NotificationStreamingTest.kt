package org.tenshu.notifications.proxy.integration


import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FileService
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.get
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.file.test.createFileProxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class NotificationStreamingTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setUp() {
        tContext = createTestContext()
    }

    @Test
    fun notificationsAreReturnedAsDtoSubclass() = runTest {
        val testProxy = createFileProxy(backgroundScope)
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
        val fileService = env.getService<FileService>(FileService.ID)

        // Create a folder to trigger a notification
        fileService.defaultFileSystem.createFolder("/test")

        // Collect notifications
        // We might need to wait a bit or skip the first empty emission if it's there
        val result = notificationService.notifications.flow().first { res ->
            val list = (res as? TResult.Success<TList<Notification>>)?.value
            list != null && list.toList().isNotEmpty()
        }
        assertTrue(result is TResult.Success<*>)
        val notifications = result.get().toList()

        assertTrue(notifications.isNotEmpty(), "Should have at least one notification")

        // This cast will fail if the items are entities instead of DTOs
        val firstNotification = notifications.first()
        assertTrue(
            firstNotification is FolderAddedNotification,
            "Item should implement FolderAddedNotification interface but was ${firstNotification::class.simpleName}"
        )
    }
}
