package org.tenshu.file.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.changes
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.contains
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.files
import org.tenshu.core.file.name
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A whole-tree `traverse().changes()` — the Photos feed's observer — over the real file proxy, after
 * signal-only streams stopped re-evaluating on every write (`StreamRegistry.setSignalListener`).
 *
 * ⚠⚠ The failure this guards is **silence**, which no tick-counting test of an existing folder can
 * see: a signal-only stream now re-discovers the arrays it reads only when a write could add one.
 * A folder created after the subscribe brings arrays nobody listened to at subscribe time, and a
 * write two levels into them must still tick.
 */
class TraverseChangesSignalTest {

    private companion object {
        val TIMEOUT = 60.seconds
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 300L
        const val POLL_MS = 10L
        val BYTES = byteArrayOf(1, 2, 3)
    }

    @Test
    fun aWriteInsideFoldersCreatedAfterTheSubscribeIsSignalled() = observing { fs, ticks ->
        awaitTickAfter(ticks) { assertTrue(fs.createFolder("/later") is TResult.Success<*>) }
        awaitTickAfter(ticks) { assertTrue(fs.createFolder("/later/deeper") is TResult.Success<*>) }

        awaitTickAfter(ticks) {
            assertTrue(fs.uploadFile("/later/deeper", "x.txt", BYTES) is TResult.Success<*>)
        }
    }

    /**
     * ⚠⚠ A removal can expose arrays the stream never read: under `take(1)` only the first folder is
     * walked, and deleting it brings the second one into view. A write inside that one must tick.
     */
    @Test
    fun aWriteInsideAFolderARemovalBroughtIntoAWindowIsSignalled() = runTest(timeout = TIMEOUT) {
        withContext(Dispatchers.Default) {
            val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val fs = fileSystem(createFileProxy(proxyScope))
                assertTrue(fs.createFolder("/first") is TResult.Success<*>)
                assertTrue(fs.createFolder("/second") is TResult.Success<*>)
                val ticks = MutableStateFlow(0)
                val observer = launch {
                    fs.root.files.take(1).traverse().changes(emitOnSubscribe = true).collect { ticks.value++ }
                }
                withTimeout(WAIT_MS) { while (ticks.value < 1) delay(POLL_MS) }

                awaitTickAfter(ticks) { assertTrue(fs.deleteFile("/first") is TResult.Success<*>) }
                awaitTickAfter(ticks) {
                    assertTrue(fs.uploadFile("/second", "x.txt", BYTES) is TResult.Success<*>)
                }
                observer.cancel()
            } finally {
                proxyScope.cancel()
            }
        }
    }

    @Test
    fun aWriteTheReaderDoesNotShowMaySignal_butARealOneMust() = runTest(timeout = TIMEOUT) {
        withContext(Dispatchers.Default) {
            val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val fs = fileSystem(createFileProxy(proxyScope))
                assertTrue(fs.uploadFile("/", "seed.jpg", BYTES) is TResult.Success<*>)
                val ticks = MutableStateFlow(0)
                val observer = launch {
                    fs.root.files.traverse().filter<File> { name.contains(".jpg") }
                        .changes(emitOnSubscribe = true).collect { ticks.value++ }
                }
                withTimeout(WAIT_MS) { while (ticks.value < 1) delay(POLL_MS) }

                // A row the filter drops: over-signalling is allowed, so this asserts nothing either way.
                fs.uploadFile("/", "ignored.txt", BYTES)
                delay(SETTLE_MS)

                awaitTickAfter(ticks) {
                    assertTrue(fs.uploadFile("/", "shown.jpg", BYTES) is TResult.Success<*>)
                }
                observer.cancel()
            } finally {
                proxyScope.cancel()
            }
        }
    }

    // --- Fixture ----------------------------------------------------------------------------

    /** A realm with one file, observed by `root.files.traverse().changes()` once its subscribe ticked. */
    private fun observing(body: suspend CoroutineScope.(FileSystem, MutableStateFlow<Int>) -> Unit) =
        runTest(timeout = TIMEOUT) {
            // ⚠ Real dispatching: the registry's drains run on the proxy's session scope, which an
            // unconfined or virtual-time scope would run inline inside the database's lock.
            withContext(Dispatchers.Default) {
                val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val fs = fileSystem(createFileProxy(proxyScope))
                    assertTrue(fs.uploadFile("/", "seed.txt", BYTES) is TResult.Success<*>)
                    val ticks = MutableStateFlow(0)
                    val observer = launch {
                        fs.root.files.traverse().changes(emitOnSubscribe = true).collect { ticks.value++ }
                    }
                    withTimeout(WAIT_MS) { while (ticks.value < 1) delay(POLL_MS) }
                    body(fs, ticks)
                    observer.cancel()
                } finally {
                    proxyScope.cancel()
                }
            }
        }

    /** Runs [write] once earlier ticks have settled, then waits for a tick that came after it. */
    private suspend fun awaitTickAfter(ticks: MutableStateFlow<Int>, write: suspend () -> Unit) {
        delay(SETTLE_MS)
        val before = ticks.value
        write()
        withTimeout(WAIT_MS) { while (ticks.value <= before) delay(POLL_MS) }
    }

    private fun fileSystem(proxy: TestProxy): FileSystem {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService<FileService>(FileService.ID).defaultFileSystem
    }
}
