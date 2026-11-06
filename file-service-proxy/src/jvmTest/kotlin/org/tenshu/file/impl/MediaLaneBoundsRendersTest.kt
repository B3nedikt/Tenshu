package org.tenshu.file.impl

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.MediaWorkBudget
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.file.GetPreview
import org.tenshu.core.toTByteArray
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D4 / P3 (`media-processing-pipeline-sweep.md`): **eight renders submitted at once run at most two at
 * a time**, because the minter renders on the process's media lane, not on its caller's thread.
 *
 * ⚠⚠ Real threads and real latches, deliberately. A virtual-time `runTest` drained with
 * `advanceUntilIdle` serialises everything onto one thread. It would report a peak of 1 whether or
 * not a lane exists, and the test would pass on the code it is meant to catch.
 *
 * The callers sit on `Dispatchers.IO`, which has threads to spare. Without the lane all eight renders
 * enter `GetPreview` together.
 */
class MediaLaneBoundsRendersTest {

    @Test
    fun eightRendersRunAtMostTwoAtATime() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val proxy = createFileProxy(scope)
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val twoEntered = CountDownLatch(MediaWorkBudget.PARALLELISM)
            val thirdEntered = CountDownLatch(MediaWorkBudget.PARALLELISM + 1)
            val release = CountDownLatch(1)

            proxy.additionalImplementations[GetPreview.ID] = object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?,
                ): T {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    twoEntered.countDown()
                    thirdEntered.countDown()
                    try {
                        check(release.await(30, TimeUnit.SECONDS)) { "the test never released the renders" }
                    } finally {
                        inFlight.decrementAndGet()
                    }
                    @Suppress("UNCHECKED_CAST")
                    return byteArrayOf(1, 2, 3).toTByteArray(executionContext.context) as T
                }
            }

            val minter = ThumbnailClassMinter(InMemoryFileDatabase())
            val renders = (1..8).map { i ->
                async(Dispatchers.IO) {
                    minter.renderStill(proxy.executionContext, "/p$i.jpg", px = 256, format = null, maxBytes = 1024)
                }
            }

            assertTrue(twoEntered.await(30, TimeUnit.SECONDS), "the lane should admit two renders")
            // ⚠ The one bounded wait: proving that nothing ELSE happens needs a window. Without the lane
            // the other six enter within microseconds of the first two.
            assertFalse(
                thirdEntered.await(500, TimeUnit.MILLISECONDS),
                "a third render started while two were in flight (peak so far ${peak.get()})",
            )
            release.countDown()

            val results = renders.awaitAll()
            assertEquals(8, results.count { it is ThumbnailClassMinter.Render.Bytes }, "every render completes")
            assertEquals(MediaWorkBudget.PARALLELISM, peak.get(), "peak concurrent renders")
        } finally {
            scope.cancel()
        }
    }
}
