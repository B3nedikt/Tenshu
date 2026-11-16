package org.tenshu.sdk.ios

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ B.16 (238), a privacy matter: the host's detach (off main) and the user's off/on (main) must
 * not interleave inside one transition of `TimelineCaptureRuntime`'s `active`/`activeClient`.
 *
 * The interleaving is **forced**: the old capture's `detach()`, called on a background thread
 * inside `detachIfOver`, hands control to the test thread and waits for it to turn capture off and
 * on again. Unserialised, the off/on runs there and then, the detach's `active = null` lands after
 * it and drops the new capture, and the final opt-out never stops it. Serialised, the off/on
 * blocks until the detach is done (its wait times out), and the opt-out stops the new capture.
 * Over a real assembly and fake captures. Every name is made up.
 */
class TimelineCaptureRuntimeRaceTest {

    private class FakeCapture(private val onDetach: () -> Unit) : TimelineCaptureRuntime.Capture {
        val calls = ArrayList<String>()
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun detach() {
            calls += "detach"
            onDetach()
        }
    }

    private val savedEnabled = TimelineCaptureRuntime.enabled
    private val savedAssembly = TimelineCaptureRuntime.currentAssembly
    private val savedFactory = TimelineCaptureRuntime.factory
    private val savedReleaseParked = TimelineCaptureRuntime.releaseParked

    @AfterTest
    fun restore() {
        TimelineCaptureRuntime.stopCapture()
        TimelineCaptureRuntime.enabled = savedEnabled
        TimelineCaptureRuntime.currentAssembly = savedAssembly
        TimelineCaptureRuntime.factory = savedFactory
        TimelineCaptureRuntime.releaseParked = savedReleaseParked
        TestGraphs.stopAll() // ⚠ last: the hooks above may still need the graph
    }

    @Test
    fun anOffAndOnDuringTheHostsDetachLeavesTheNewCaptureReachableByTheOptOut() = runBlocking {
        val graph = TimelineLocalRuntime.assemble(
            replicaDirectory = NSTemporaryDirectory() + "timeline-capture-race-" + Random.nextLong().toString(16),
            secureStorage = InMemorySecureStorageDelegate(),
        )
        val inDetach = CompletableDeferred<Unit>()
        val offAndOnDone = CompletableDeferred<Unit>()
        val made = ArrayList<FakeCapture>()
        var on = true
        TimelineCaptureRuntime.enabled = { on }
        TimelineCaptureRuntime.currentAssembly = { graph }
        TimelineCaptureRuntime.releaseParked = {}
        TimelineCaptureRuntime.factory = {
            FakeCapture(onDetach = {
                // Only the first capture's detach, the host's, opens the window.
                if (inDetach.complete(Unit)) {
                    // Serialised, the test thread is blocked on the lock and never says done:
                    // the bounded wait is what lets the detach finish.
                    runBlocking { withTimeoutOrNull(DETACH_WAIT_MS) { offAndOnDone.await() } }
                }
            }).also { made += it }
        }
        TimelineCaptureRuntime.startIfEnabled()
        assertEquals(1, made.size)

        // The host's stop, on its collector thread.
        val hostStop = launch(Dispatchers.Default) {
            TimelineCaptureRuntime.detachIfOver(graph.client.tenshuClient)
        }
        withTimeout(5_000) { inDetach.await() }

        // Inside the detach's window, on this thread: the user turns capture off, then on.
        on = false
        TimelineCaptureRuntime.stopCapture()
        on = true
        TimelineCaptureRuntime.startIfEnabled()
        offAndOnDone.complete(Unit)
        withTimeout(5_000) { hostStop.join() }

        assertEquals(2, made.size, "the 'on' started a new capture")
        // ⚠⚠ The opt-out must reach it.
        on = false
        TimelineCaptureRuntime.stopCapture()
        assertTrue("stop" in made[1].calls, "the new capture was stopped by the opt-out, not lost: ${made[1].calls}")
    }

    private companion object {
        /** How long the forced detach waits for the off/on; serialised, it always waits this long. */
        const val DETACH_WAIT_MS = 500L
    }
}
