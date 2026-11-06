package org.tenshu.sdk.ios

import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `ios-shared-store-sqlite.md` B.16 (228): the live capture is a process-level
 * component built over one graph's client. When the shared store's host stops that graph the
 * capture is **detached** (monitoring off, no session end written through the stopped graph), and
 * the rebuild's `startIfEnabled` builds it over the new graph. Over two real assemblies and a fake
 * capture. Every name is made up.
 */
class TimelineCaptureRebuildTest {

    private class FakeCapture(val over: TimelineLocalRuntime.Assembly) : TimelineCaptureRuntime.Capture {
        val calls = ArrayList<String>()
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun detach() { calls += "detach" }
    }

    private val made = ArrayList<FakeCapture>()

    private fun assembly() = TimelineLocalRuntime.assemble(
        replicaDirectory = NSTemporaryDirectory() + "timeline-capture-rebuild-" + Random.nextLong().toString(16),
        secureStorage = InMemorySecureStorageDelegate(),
    )

    private val savedEnabled = TimelineCaptureRuntime.enabled
    private val savedAssembly = TimelineCaptureRuntime.currentAssembly
    private val savedFactory = TimelineCaptureRuntime.factory
    private val savedReleaseParked = TimelineCaptureRuntime.releaseParked
    private var parkedReleases = 0

    @AfterTest
    fun restore() {
        TimelineCaptureRuntime.stopCapture()
        TimelineCaptureRuntime.enabled = savedEnabled
        TimelineCaptureRuntime.currentAssembly = savedAssembly
        TimelineCaptureRuntime.factory = savedFactory
        TimelineCaptureRuntime.releaseParked = savedReleaseParked
    }

    @Test
    fun aStoppedGraphsCaptureIsDetachedAndTheRebuildCapturesOverTheNewGraph() {
        val old = assembly()
        val new = assembly()
        var current = old
        TimelineCaptureRuntime.enabled = { true }
        TimelineCaptureRuntime.currentAssembly = { current }
        TimelineCaptureRuntime.factory = { a -> FakeCapture(a).also { made += it } }

        TimelineCaptureRuntime.startIfEnabled()
        assertEquals(1, made.size)
        assertSame(old, made[0].over)

        // Another graph's stop leaves this capture alone.
        TimelineCaptureRuntime.detachIfOver(new.client.tenshuClient)
        assertEquals(listOf("start"), made[0].calls)

        // The old graph stopped: detached, never stopped (stop writes the session's end).
        TimelineCaptureRuntime.detachIfOver(old.client.tenshuClient)
        assertEquals(listOf("start", "detach"), made[0].calls)

        // The rebuild: capture over the new graph.
        current = new
        TimelineCaptureRuntime.startIfEnabled()
        assertEquals(2, made.size, "a capture over the rebuilt graph")
        assertSame(new, made[1].over)
        assertEquals(listOf("start"), made[1].calls)
    }

    @Test
    fun aCaptureOverAnotherGraphIsReplacedEvenWhenNothingDetachedIt() {
        // A capture started over a stand-in graph after its stop ran
        // must not be kept by the rebuilt graph's startIfEnabled.
        val old = assembly()
        val new = assembly()
        var current = old
        TimelineCaptureRuntime.enabled = { true }
        TimelineCaptureRuntime.currentAssembly = { current }
        TimelineCaptureRuntime.factory = { a -> FakeCapture(a).also { made += it } }
        TimelineCaptureRuntime.startIfEnabled()
        current = new
        TimelineCaptureRuntime.startIfEnabled()
        assertEquals(2, made.size, "replaced, never kept")
        assertEquals(listOf("start", "detach"), made[0].calls)
        assertSame(new, made[1].over)
        TimelineCaptureRuntime.startIfEnabled()
        assertEquals(2, made.size, "idempotent over the same graph")
    }

    @Test
    fun turningCaptureOffStopsAParkedManagerEvenWithNoLiveCapture() {
        // B.16 (238), a privacy matter: after a detach no capture is
        // live, and the opt-out must still stop the manager the detach left monitoring.
        val old = assembly()
        var on = true
        TimelineCaptureRuntime.enabled = { on }
        TimelineCaptureRuntime.currentAssembly = { old }
        TimelineCaptureRuntime.factory = { a -> FakeCapture(a).also { made += it } }
        TimelineCaptureRuntime.releaseParked = { parkedReleases++ }
        TimelineCaptureRuntime.startIfEnabled()
        TimelineCaptureRuntime.detachIfOver(old.client.tenshuClient)
        assertEquals(0, parkedReleases, "parked for a successor")
        // The user turns capture off: no live capture, the parked manager is stopped.
        on = false
        TimelineCaptureRuntime.stopCapture()
        assertEquals(1, parkedReleases)
        // Off at a later launch's startIfEnabled too, and when the graph is given up.
        TimelineCaptureRuntime.startIfEnabled()
        assertEquals(2, parkedReleases)
        TimelineCaptureRuntime.abandonParked()
        assertEquals(3, parkedReleases)
    }
}
