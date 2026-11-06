package org.tenshu.core.common

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.request.CallOutcome
import org.tenshu.core.common.request.RemoteTaskExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where an executor's [CallOutcome] goes once `DefaultTenshuClient` has it.
 *
 * ## ⚠⚠ The case that shapes all of this
 *
 * A proxy refusing the session on a schema mismatch **still answers** — the announce's own round
 * trip comes back with a perfectly readable typed refusal, and since every first-party app
 * announces at launch it arrives before any screen has read anything. So "the response parsed"
 * cannot mean "reachable": that would clear the update banner on a device that can read nothing,
 * which is the SK-1 defect the skew surface exists to prevent.
 *
 * [CallOutcome.Answered] therefore requires the *result* to be a success too, and a refusal lands
 * on [CallOutcome.AnsweredWithError] — which clears the skew latch (the answer WAS readable) and
 * asserts nothing about reachability.
 */
class CallOutcomeRoutingTest {

    private class FakeRemote : RemoteTaskExecutor {
        var emit: ((CallOutcome) -> Unit)? = null
        override var isConnected: Boolean = true
        override fun bind() {}
        override fun unbind() {}
        override fun setOnCallOutcome(callback: ((CallOutcome) -> Unit)?) {
            emit = callback
        }

        override fun execute(task: TList<Data>): TResult<Data> = error("not used")
        override suspend fun executeAsync(task: TList<Data>): TResult<Data> = error("not used")
    }

    /**
     * ⚠ The proxy exists only to supply a `TContext`; no chain is ever issued, which is why the
     * factory errors rather than returning a stub that could quietly be used. Its scope is the
     * test's [kotlinx.coroutines.test.TestScope.backgroundScope] so nothing outlives the test — a
     * leaked coroutine here is reported against whichever `runTest` runs next.
     */
    private fun TestScope.clientOn(executor: FakeRemote): DefaultTenshuClient {
        val proxy = TestProxy(coroutineScope = backgroundScope)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { error("no chain is issued in this test") },
        )
        return DefaultTenshuClient(env).also { it.init() }
    }

    /** ⚠ The refusal case, stated as a test: an answered *failure* must not read as reachable. */
    @Test
    fun onlyASucceedingCallCountsAsAnAnswer() = runTest {
        val executor = FakeRemote()
        val client = clientOn(executor)

        val firstEvent = async { client.proxyAnswered.first() }
        // Let the collector attach before anything is emitted — a SharedFlow with no replay
        // drops what it was not yet listening for, exactly as production does.
        runCurrent()

        executor.emit?.invoke(CallOutcome.AnsweredWithError)
        executor.emit?.invoke(CallOutcome.NotConnected)
        executor.emit?.invoke(CallOutcome.Unintelligible)
        assertFalse(firstEvent.isCompleted, "none of those is a served call")

        executor.emit?.invoke(CallOutcome.Answered)
        runCurrent()
        assertTrue(firstEvent.isCompleted, "a served call is one event")
        firstEvent.await()
    }

    /**
     * The skew latch, which is a *condition* rather than an event: it ends when something is
     * updated, and any readable answer is the proof.
     */
    @Test
    fun anyReadableAnswerClearsTheSkewLatch() = runTest {
        val executor = FakeRemote()
        val client = clientOn(executor)

        executor.emit?.invoke(CallOutcome.Unintelligible)
        assertTrue(client.proxyAnswerUnreadable.value)

        executor.emit?.invoke(CallOutcome.AnsweredWithError)
        assertFalse(client.proxyAnswerUnreadable.value, "a typed failure was still readable")

        executor.emit?.invoke(CallOutcome.Unintelligible)
        assertTrue(client.proxyAnswerUnreadable.value)
        executor.emit?.invoke(CallOutcome.Answered)
        assertFalse(client.proxyAnswerUnreadable.value)
    }

    /**
     * ⚠⚠ **A dead channel does not clear it**, and it used to. `NotConnected` is not a readable
     * answer, so treating it as one discards a skew verdict the reconnect then has to re-earn —
     * and until it does, the banner tells the user to check a connection instead of to update.
     */
    @Test
    fun aDeadChannelLeavesTheSkewLatchAlone() = runTest {
        val executor = FakeRemote()
        val client = clientOn(executor)

        executor.emit?.invoke(CallOutcome.Unintelligible)
        assertTrue(client.proxyAnswerUnreadable.value)

        executor.emit?.invoke(CallOutcome.NotConnected)
        assertEquals(true, client.proxyAnswerUnreadable.value)
    }
}
