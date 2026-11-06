package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.sync.client.withBulkWriteTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C4 (`sync-device-catch-up.md` §11.7): `withBulkWriteTask` **degrades**, and this is the only
 * place that can prove it.
 *
 * ⚠⚠ The obvious home — the system harness — cannot: its client is fully wired, so
 * `taskService.register` succeeds there no matter how little else has been set up. A test that
 * merely *hopes* registration fails would assert nothing and read as if it had. What a degraded
 * runtime actually looks like is this: a proxy with **no sync service registered at all** (an older
 * Control, a runtime assembled without the sync client module), where `getService(TaskService.ID)`
 * throws before a task can exist.
 *
 * The property under test is the one every consumer leans on: batching is an optimisation, so
 * losing it must never cost a write, throw, or skip the block.
 */
class BulkWriteTaskDegradationTest {

    @Test fun withNoTaskService_theBlockStillRunsAndReturnsItsValue() = runTest {
        val proxy = TestProxy()
        val client = clientOf(proxy)
        var ran = 0

        val result = client.withBulkWriteTask("test/degraded") { task ->
            ran++
            assertNull(task, "no task can exist on a proxy with no sync service")
            "the block's own value"
        }

        assertEquals(1, ran, "the block must run exactly once")
        assertEquals("the block's own value", result, "and its value must come back untouched")
    }

    @Test fun withNoTaskService_aThrowFromTheBlockStillPropagates() = runTest {
        val proxy = TestProxy()
        val client = clientOf(proxy)
        val thrown = try {
            client.withBulkWriteTask<Unit>("test/degraded") { error("boom") }
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue(thrown != null && thrown.message == "boom", "got $thrown")
    }

    private fun clientOf(proxy: TestProxy): TenshuClient = DefaultTenshuClient(
        TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        ),
    )
}
