package app.tenshu.control.android.server

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.BackgroundLiveness

/**
 * Phase L2-LIVE (docs/proposals/core-background-processing.md §8): unit test
 * on the registry → liveness mapping (empty/non-empty edges, idempotent).
 * Drives the edge logic directly — no Android runtime, no coroutines — so the
 * contract is locked independently of the foreground-service plumbing, which
 * is correctness-by-construction (Android not locally compilable, as with the
 * Phase L2 binder PR).
 */
class ForegroundTaskLivenessTest {

    private sealed interface Event {
        data class Active(val count: Int) : Event
        data class CountChanged(val count: Int) : Event
        data object Idle : Event
    }

    private class RecordingHandle : TaskLivenessHandle {
        val events = mutableListOf<Event>()
        override fun onActive(taskCount: Int) {
            events += Event.Active(taskCount)
        }

        override fun onCountChanged(taskCount: Int) {
            events += Event.CountChanged(taskCount)
        }

        override fun onIdle() {
            events += Event.Idle
        }
    }

    private fun liveness(handle: TaskLivenessHandle) =
        ForegroundTaskLiveness(MutableStateFlow(0), handle)

    @Test
    fun `first in-flight task promotes, last one demotes`() {
        val handle = RecordingHandle()
        val live = liveness(handle)

        live.onCount(1)
        live.onCount(0)

        assertEquals(listOf(Event.Active(1), Event.Idle), handle.events)
    }

    @Test
    fun `count change while active only refreshes the notification`() {
        val handle = RecordingHandle()
        val live = liveness(handle)

        live.onCount(1)
        live.onCount(3)
        live.onCount(2)
        live.onCount(0)

        assertEquals(
            listOf(
                Event.Active(1),
                Event.CountChanged(3),
                Event.CountChanged(2),
                Event.Idle
            ),
            handle.events
        )
    }

    @Test
    fun `idle is idempotent - leading zeros and repeated zeros never fire onIdle`() {
        val handle = RecordingHandle()
        val live = liveness(handle)

        live.onCount(0) // never went active
        live.onCount(0)
        live.onCount(1)
        live.onCount(0)
        live.onCount(0) // already idle
        live.onCount(0)

        assertEquals(listOf(Event.Active(1), Event.Idle), handle.events)
    }

    @Test
    fun `re-activation after idle fires onActive again`() {
        val handle = RecordingHandle()
        val live = liveness(handle)

        live.onCount(1)
        live.onCount(0)
        live.onCount(1)
        live.onCount(0)

        assertEquals(
            listOf(Event.Active(1), Event.Idle, Event.Active(1), Event.Idle),
            handle.events
        )
    }

    @Test
    fun `negative count is treated as idle`() {
        val handle = RecordingHandle()
        val live = liveness(handle)

        live.onCount(2)
        live.onCount(-1)

        assertEquals(listOf(Event.Active(2), Event.Idle), handle.events)
    }

    // --- control-background-work-off-fgs.md §5.2: the demotion linger ---

    /**
     * Drives the real collector over a count flow, with [lingerMs] as the window; returns the events.
     *
     * ⚠⚠ Virtual time, not `runBlocking` + real `delay`: the collector's `collectLatest` takes several
     * dispatches to act on a value (a `cancelAndJoin` of the pending linger, then the new block), and
     * on the real clock a stall longer than the script's next `delay` let the script cancel the scope
     * between those hops — the gate's `but was:<[Active(count=1)]>`. Here the collector and its
     * linger `delay` both run on the test scheduler (it takes its dispatcher and its `Delay` from the
     * scope, nothing else), and [Steps.set] runs every queued task before the script moves on, so
     * each value is observed by construction and the linger is measured in virtual milliseconds.
     */
    private fun collect(lingerMs: Long, script: suspend Steps.() -> Unit): List<Event> {
        val handle = RecordingHandle()
        runTest {
            val steps = Steps(this, handle)
            ForegroundTaskLiveness(steps.counts, handle, idleLingerMs = lingerMs).launchIn(backgroundScope)
            runCurrent()
            steps.script()
        }
        return handle.events.toList()
    }

    /** The script's vocabulary: set a count and let the collector act on it, or let virtual time pass. */
    private class Steps(private val test: TestScope, private val handle: RecordingHandle) {
        val counts = MutableStateFlow(0)

        /** What the handle has seen so far. */
        fun events(): List<Event> = handle.events.toList()

        fun set(count: Int) {
            counts.value = count
            test.runCurrent()
        }

        fun advance(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }
    }

    @Test
    fun `a gap shorter than the linger keeps the service promoted`() {
        // ⚠⚠ The case the linger exists for: a user-started folder upload is a run of tasks with gaps.
        // Demoting in a gap after the user left would make the next promotion refused for good.
        val events = collect(lingerMs = 2_000) {
            set(1)
            advance(50)
            set(0) // between two files
            advance(1_999) // one virtual millisecond short of the linger
            set(1) // the next file
            advance(50)
        }
        assertEquals(listOf(Event.Active(1), Event.CountChanged(1)), events)
    }

    @Test
    fun `idle fires once the count has stayed at zero for the linger`() {
        val events = collect(lingerMs = 100) {
            set(1)
            advance(50)
            set(0)
            advance(99)
            assertEquals("not before the linger has run out", listOf<Event>(Event.Active(1)), events())
            advance(1)
        }
        assertEquals(listOf(Event.Active(1), Event.Idle), events)
    }

    // --- the promotion count: user-started work only ---

    private fun promotionCount(userTasks: Int, liveness: BackgroundLiveness): Int =
        kotlinx.coroutines.runBlocking {
            foregroundLivenessCount(MutableStateFlow(userTasks), liveness).first()
        }

    @Test
    fun `automatic sources never count towards the foreground service`() {
        val liveness = BackgroundLiveness()
        liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
        liveness.setActive(true, BackgroundLiveness.SCHEDULED_MEDIA_INDEX_PASS)

        assertEquals(
            "a sync push or a scheduled index pass is automatic: Play allows it no dataSync FGS",
            0,
            promotionCount(userTasks = 0, liveness),
        )
    }

    @Test
    fun `a user-started bulk task counts between its calls, and a reset from Control's own screen counts`() {
        val liveness = BackgroundLiveness()
        val count = kotlinx.coroutines.runBlocking {
            foregroundLivenessCount(MutableStateFlow(0), liveness, MutableStateFlow(1)).first()
        }
        assertEquals("a folder upload hashing a large file between calls keeps the service", 1, count)

        liveness.setActive(true, BackgroundLiveness.REALM_DATA_RESET)
        assertEquals(1, promotionCount(userTasks = 0, liveness))
        liveness.setActive(false, BackgroundLiveness.REALM_DATA_RESET)
        liveness.setActive(true, BackgroundLiveness.SCHEDULED_MEDIA_INDEX_PASS)
        assertEquals("a scheduled index pass is automatic", 0, promotionCount(userTasks = 0, liveness))
    }

    @Test
    fun `holding lowers its source however the block ends`() {
        val liveness = BackgroundLiveness()
        kotlinx.coroutines.runBlocking {
            liveness.holding(BackgroundLiveness.REALM_DATA_RESET) {
                assertEquals(1, liveness.userStartedActive.value)
            }
            runCatching { liveness.holding(BackgroundLiveness.REALM_DATA_RESET) { error("reset failed") } }
        }
        assertEquals("a source left raised pins the foreground service for good", 0, liveness.userStartedActive.value)
    }

    @Test
    fun `user-started sources and user-started tasks do`() {
        val liveness = BackgroundLiveness()
        liveness.setActive(true, BackgroundLiveness.SYNC_DRAIN)
        assertEquals(2, promotionCount(userTasks = 2, liveness))

        liveness.setActive(true, BackgroundLiveness.MAP_REGION_DOWNLOAD)
        assertEquals(1, promotionCount(userTasks = 0, liveness))

        liveness.setActive(false, BackgroundLiveness.MAP_REGION_DOWNLOAD)
        liveness.setActive(true, BackgroundLiveness.MODEL_DOWNLOAD)
        assertEquals(1, promotionCount(userTasks = 0, liveness))
    }
}
