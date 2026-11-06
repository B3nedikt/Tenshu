package org.tenshu.file.proxy.integration


import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.equalTo
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.files
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.ifEmptyError
import org.tenshu.file.test.createFileProxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalStreamingIntegrationTest {

    @BeforeTest
    fun setUp() {
    }

    @Test
    fun localStreaming_FlowEmitsInitialState() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val executor = LocalTaskExecutor(testProxy.executionContext)

        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = testProxy.schemaRegistry,
            requestChainFactory = { context ->
                LocalRequestChain(executor, context)
            },
            tContext = testProxy.tContext
        )

        val fileService = env.getService<FileService>(FileService.ID)

        // Initial state should be empty
        val initialFilesResult = fileService
            .defaultFileSystem
            .root
            .files
            .flow()
            .first()

        if (initialFilesResult is TResult.Failure) {
            error("Initial stream failed: ${initialFilesResult.error}")
        }
        assertTrue(
            initialFilesResult is TResult.Success<*>,
            "Initial result should be Success but was $initialFilesResult"
        )
        val initialList = initialFilesResult.get().toList()
        assertEquals(
            0,
            initialList.size,
            "Initial list should be empty but had ${initialList.size} items: $initialList"
        )

        // Add a folder
        fileService
            .defaultFileSystem
            .createFolder("/test")

        // Next emission should have 1 item
        val updatedFilesResult = fileService
            .defaultFileSystem
            .root
            .files
            .flow()
            .first()

        if (updatedFilesResult is TResult.Failure) {
            error("Updated stream failed: ${updatedFilesResult.error}")
        }
        assertTrue(
            updatedFilesResult is TResult.Success<*>,
            "Updated result should be Success but was $updatedFilesResult"
        )
        val files = updatedFilesResult.get().toList()
        assertEquals(1, files.size, "Updated list should have 1 item but had ${files.size}: $files")
        assertEquals("/test", files.first().path.get())
    }

    @Test
    fun localStreaming_NestedSubfolderFlowEmitsUpdates() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val executor = LocalTaskExecutor(testProxy.executionContext)

        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = testProxy.schemaRegistry,
            requestChainFactory = { context ->
                LocalRequestChain(executor, context)
            },
            tContext = testProxy.tContext
        )

        val fileService = env.getService<FileService>(FileService.ID)

        // 1. Create Folder A
        fileService.defaultFileSystem.createFolder("/A")

        // 2. Create Subfolder B inside A
        fileService.defaultFileSystem.createFolder("/A/B")

        // 3. Start listening to /A/B/files
        val nestedFilesFlow = fileService.defaultFileSystem.root.files
            .search<Folder> { path equalTo "/A/B" }
            .ifEmptyError()
            .files
            .flow()

        val channel = Channel<TResult<TList<File>>>(Channel.UNLIMITED)
        val job = launch { nestedFilesFlow.collect { channel.send(it) } }

        val initial = channel.receive()
        if (initial is TResult.Failure) error("Initial nested stream failed: ${initial.error}")
        assertEquals(0, initial.get().toList().size)

        // 4. Upload image to /A/B
        fileService.defaultFileSystem.uploadFile(
            parentPath = "/A/B",
            fileName = "image.png",
            data = byteArrayOf(0)
        )

        // 5. Next emission should have the image
        val updated = channel.receive()
        if (updated is TResult.Failure) error("Updated nested stream failed: ${updated.error}")
        val files = updated.get().toList()
        assertEquals(
            1,
            files.size,
            "Nested list should have 1 item but had ${files.size}: $files"
        )
        assertEquals("/A/B/image.png", files.first().path.get(), "File path mismatch")

        job.cancel()
    }

    /**
     * Regression for the production `IndexOutOfBoundsException` seen when many
     * file/folder ops fire in quick succession against an open `.flow()`
     * collector: the `RemoteBinaryTaskExecutor.transport.onCallback` IPC
     * dispatcher launches a fresh coroutine per received `StreamElement`,
     * which means the row merge used to be called concurrently against the
     * same internal list inside [org.tenshu.core.collection.list.flow]. A `Removed` shrinking
     * the list between a `Value`'s `indexOfFirst` and its subsequent
     * `set(index, …)` crashed with `Index 0 out of bounds for length 0`.
     *
     * The fix routes incoming `StreamElement`s through an unbounded `Channel`
     * and lets the flow's collector loop apply the merges serially. This
     * test fires many sequential creates without waiting on the flow — under
     * the bug the flow consumer would crash; with the fix it converges to
     * the correct final state.
     */
    @Test
    fun localStreaming_concurrentMutations_doNotCrashFlow() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val executor = LocalTaskExecutor(testProxy.executionContext)

        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = testProxy.schemaRegistry,
            requestChainFactory = { context ->
                LocalRequestChain(executor, context)
            },
            tContext = testProxy.tContext
        )

        val fileService = env.getService<FileService>(FileService.ID)

        val rootFlow = fileService.defaultFileSystem.root.files.flow()
        val channel = Channel<TResult<TList<File>>>(Channel.UNLIMITED)
        val job = launch { rootFlow.collect { channel.send(it) } }

        // Wait for the initial emission so the listener is registered.
        val initial = channel.receive()
        if (initial is TResult.Failure) {
            error("Initial flow emission was Failure: ${initial.error}")
        }

        // Fire a stream of creates without yielding to the flow consumer.
        val expected = (0 until 25).map { "/f$it" }
        for (path in expected) {
            fileService.defaultFileSystem.createFolder(path)
        }

        // Drain emissions until the flow converges to the full set.
        while (true) {
            val next = channel.receive()
            if (next is TResult.Failure) {
                error("Concurrent flow consumption produced Failure: ${next.error}")
            }
            val paths = next.get().toList().map { it.path.get() }.toSet()
            if (paths.containsAll(expected)) break
        }

        job.cancel()
    }
}
