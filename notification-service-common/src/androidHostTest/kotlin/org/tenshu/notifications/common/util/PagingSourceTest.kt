package org.tenshu.notifications.common.util

import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.paging.pagingSource
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.notifications.NotificationService
import org.tenshu.file.test.createFileProxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PagingSourceTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setUp() {
        tContext = createTestContext()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pagingSource_retainsDataOnNavigation() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testProxy = createFileProxy()
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

        try {
            // 1. Initial state
            val pager1 = notificationService.notifications.pagingSource(mapper = { it })
            assertEquals(0, pager1.asSnapshot().size)

            // 2. Trigger notification
            fileService.defaultFileSystem.createFolder("/folder1")
            advanceUntilIdle() // Ensure monitor sees it

            assertEquals(1, pager1.asSnapshot().size)

            // 3. Simulate navigation away and back by starting a NEW paging flow for the same service list
            // This simulates opening the notification screen again.
            val pager2 = notificationService.notifications.pagingSource(mapper = { it })
            val snapshot2 = pager2.asSnapshot()

            assertEquals(
                1,
                snapshot2.size,
                "Navigating back should still show the existing notification"
            )
        } finally {
            advanceUntilIdle()
        }
    }

    @Test
    fun pagingSource_handlesDuplicateEmissionsGracefully() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testProxy = createFileProxy()
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

        try {
            val pager = notificationService.notifications.pagingSource(mapper = { it })

            // 1. Trigger first notification
            fileService.defaultFileSystem.createFolder("/folder1")
            advanceUntilIdle()

            var snapshot = pager.asSnapshot()
            assertEquals(1, snapshot.size, "Should have 1 notification")

            // 2. Trigger second notification
            fileService.defaultFileSystem.createFolder("/folder2")
            advanceUntilIdle()

            snapshot = pager.asSnapshot()
            assertEquals(2, snapshot.size, "Should have 2 notifications")

            // Check uniqueness of UUIDs (this would fail if merge was broken)
            val uuids = snapshot.map { it.uuid.get() }
            assertEquals(
                uuids.distinct().size,
                uuids.size,
                "All notifications should have unique UUIDs"
            )
        } finally {
            advanceUntilIdle()
        }
    }
}
