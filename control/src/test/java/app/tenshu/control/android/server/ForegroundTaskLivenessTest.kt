package app.tenshu.control.android.server

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.flow.first
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

    /** Drives the real collector over [counts], with [lingerMs] as the window; returns the events. */
    private fun collect(lingerMs: Long, script: suspend (MutableStateFlow<Int>) -> Unit): List<Event> =
        kotlinx.coroutines.runBlocking {
            val handle = RecordingHandle()
            val counts = MutableStateFlow(0)
            val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
            ForegroundTaskLiveness(counts, handle, idleLingerMs = lingerMs).launchIn(scope)
            kotlinx.coroutines.yield()
            script(counts)
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            handle.events.toList()
        }

    @Test
    fun `a gap shorter than the linger keeps the service promoted`() {
        // ⚠⚠ The case the linger exists for: a user-started folder upload is a run of tasks with gaps.
        // Demoting in a gap after the user left would make the next promotion refused for good.
        val events = collect(lingerMs = 2_000) { counts ->
            counts.value = 1
            kotlinx.coroutines.delay(50)
            counts.value = 0 // between two files
            kotlinx.coroutines.delay(100)
            counts.value = 1 // the next file
            kotlinx.coroutines.delay(50)
        }
        assertEquals(listOf(Event.Active(1), Event.CountChanged(1)), events)
    }

    @Test
    fun `idle fires once the count has stayed at zero for the linger`() {
        val events = collect(lingerMs = 100) { counts ->
            counts.value = 1
            kotlinx.coroutines.delay(50)
            counts.value = 0
            kotlinx.coroutines.delay(400)
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
