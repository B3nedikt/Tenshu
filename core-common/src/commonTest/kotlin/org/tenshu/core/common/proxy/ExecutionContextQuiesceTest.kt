package org.tenshu.core.common.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Proxy Background Lifecycle" tech-debt — Phase 2: [ExecutionContext.quiesce]
 * (and [ClientRegistry.shutdownAndJoin] layered over it) must cancel **and
 * join** the session's background work before [ExecutionContext.destroy] runs,
 * so a loop mid-tick can no longer touch a disposed context / dropped DI
 * singleton. A plain `cancel()` returns before the coroutine's cleanup runs —
 * the join is what makes teardown deterministic. Swapping the `cancelAndJoin`
 * in [ExecutionContext.quiesce] for a bare `cancel` makes the `cleanedUp`
 * assertion below fail.
 */
class ExecutionContextQuiesceTest {

    @Test
    fun quiesce_joinsBackgroundWork_beforeReturning() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = backgroundScope
        )
        val executionContext = proxy.executionContext

        val started = CompletableDeferred<Unit>()
        var cleanedUp = false
        executionContext.sessionScope.launch {
            try {
                started.complete(Unit)
                while (true) delay(1_000)
            } finally {
                cleanedUp = true
            }
        }
        started.await() // the background loop is now running on sessionScope

        executionContext.quiesce()

        assertTrue(
            cleanedUp,
            "quiesce() must join the session's background coroutines (let their cleanup run) before returning"
        )
        assertTrue(
            executionContext.sessionScope.coroutineContext[Job]!!.isCancelled,
            "quiesce() cancels the session scope"
        )
    }

    @Test
    fun shutdownAndJoin_quiescesEverySession() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = backgroundScope
        )
        val executionContext = proxy.executionContext

        val started = CompletableDeferred<Unit>()
        var cleanedUp = false
        executionContext.sessionScope.launch {
            try {
                started.complete(Unit)
                while (true) delay(1_000)
            } finally {
                cleanedUp = true
            }
        }
        started.await()

        proxy.clientRegistry.shutdownAndJoin()

        assertTrue(
            cleanedUp,
            "shutdownAndJoin() must quiesce (cancel + join) each session's background work"
        )
        assertTrue(
            executionContext.sessionScope.coroutineContext[Job]!!.isCancelled,
            "shutdownAndJoin() tears the session scope down"
        )
    }
}
