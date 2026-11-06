package org.tenshu.core.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.ReconnectingTaskExecutor
import org.tenshu.core.common.request.RemoteTaskExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [DefaultInteractorProvider] as the `:core`-visible [org.tenshu.core.ProxyConnectivity]
 * (`docs/proposals/core-client-resubscription.md` R5).
 *
 * It carries this because it is already the one thing that self-wires to the executor's reconnect
 * edge; the R4 `setOnConnectionChanged` slot is single-occupancy and belongs to
 * `TenshuClient.proxyConnected`, so a second listener there would silently displace the first.
 */
class ProviderProxyConnectivityTest {

    private class FakeRemote(
        override var isConnected: Boolean,
    ) : RemoteTaskExecutor, ReconnectingTaskExecutor {
        override var onReconnected: (suspend () -> Unit)? = null
        override fun bind() {}
        override fun unbind() {}
        override fun execute(task: TList<Data>): TResult<Data> = error("not used")
        override suspend fun executeAsync(task: TList<Data>): TResult<Data> = error("not used")

        suspend fun reconnect() {
            isConnected = true
            onReconnected?.invoke()
        }
    }

    /** In-process: no channel, so nothing to lose. */
    private class LocalExecutor : AsyncTaskExecutor {
        override fun execute(task: TList<Data>): TResult<Data> = error("not used")
        override suspend fun executeAsync(task: TList<Data>): TResult<Data> = error("not used")
    }

    private fun provider(executor: AsyncTaskExecutor): DefaultInteractorProvider =
        // Touching isConnected resolves the lazy executor, which is also what installs the
        // self-wired onReconnected hook — the same path production takes on its first read.
        DefaultInteractorProvider(taskExecutorProvider = { executor })

    @Test
    fun anInProcessExecutorReadsAsPermanentlyConnected() = runTest {
        val subject = provider(LocalExecutor())
        assertTrue(subject.isConnected, "no channel to lose is 'connected', not a fallback")
        // Must not suspend.
        withTimeout(1.seconds) { subject.awaitConnected() }
    }

    @Test
    fun awaitConnectedReturnsAtOnceWhenTheChannelIsUp() = runTest {
        val subject = provider(FakeRemote(isConnected = true))
        withTimeout(1.seconds) { subject.awaitConnected() }
    }

    @Test
    fun awaitConnectedWakesOnTheReconnectEdge() = runTest(timeout = TIMEOUT) {
        val executor = FakeRemote(isConnected = false)
        val subject = provider(executor)
        assertFalse(subject.isConnected)

        val woke = CompletableDeferred<Unit>()
        backgroundScope.launch(Dispatchers.Default) {
            subject.awaitConnected()
            woke.complete(Unit)
        }
        withContext(Dispatchers.Default) { yield() }
        assertFalse(woke.isCompleted, "still down — nothing to wake for")

        // ⚠ The wait must run on a real dispatcher: runTest's `withTimeout` is measured in
        // *virtual* time, which it skips straight past while the real background coroutine has
        // not been scheduled yet — a guaranteed timeout that says nothing about the code.
        withContext(Dispatchers.Default) {
            executor.reconnect()
            withTimeout(WAIT) { woke.await() }
        }
    }

    @Test
    fun awaitConnectedRechecksTheLevelRatherThanBlockingForAnEdge() = runTest(timeout = TIMEOUT) {
        // The caller's decision to wait is made on a *stale* read: `Stream.kt` checks isConnected,
        // then calls this. If the reconnect lands in that window, an implementation that only ever
        // looks forward sleeps until the *next* reconnect — which, for a proxy that comes back and
        // stays up, is never. Re-reading the level here is the first defence, and it is what this
        // pins: reconnect before the wait starts, having already observed the channel as down.
        //
        // ⚠ The second defence — sampling the edge counter *before* the level, so a reconnect
        // landing between the level read and the subscribe is still caught — guards a window
        // narrower than this test can reach deterministically. It is asserted by construction and
        // documented at `ProxyConnectivity.awaitConnected`, not covered here.
        val executor = FakeRemote(isConnected = false)
        val subject = provider(executor)
        assertFalse(subject.isConnected, "observed down — this is the caller's stale read")

        withContext(Dispatchers.Default) {
            executor.reconnect()
            withTimeout(WAIT) { subject.awaitConnected() }
        }
    }

    @Test
    fun theReconnectEdgeStillDrivesTheResubscribePass() = runTest(timeout = TIMEOUT) {
        // Connectivity rides the same hook as R1's replay; neither may displace the other.
        val executor = FakeRemote(isConnected = false)
        val subject = provider(executor)
        assertFalse(subject.isConnected)
        assertTrue(executor.onReconnected != null, "the provider self-wired the reconnect hook")

        var replays = 0
        val installed = executor.onReconnected!!
        executor.onReconnected = { replays += 1; installed() }
        withContext(Dispatchers.Default) {
            executor.reconnect()
            assertEquals(1, replays)
            withTimeout(WAIT) { subject.awaitConnected() }
        }
    }

    private companion object {
        val TIMEOUT = 30.seconds
        val WAIT = 5.seconds
    }
}
