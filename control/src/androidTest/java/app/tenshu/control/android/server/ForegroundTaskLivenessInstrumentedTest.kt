package app.tenshu.control.android.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase L2-LIVE (docs/proposals/core-background-processing.md §8): the
 * instrumented half — an in-flight stub task holds liveness up and completion
 * tears it down, exercised over the real `launchIn`/`collect` path on an
 * Android runtime (the JVM unit test drives `onCount` directly and skips the
 * coroutine collector). Standing up the full proxy + a second app to mint a
 * real `BackgroundOperation` is out of scope for this seam; the in-flight
 * registry round-trip is covered by `PhaseL2CancellationTest`, so here the
 * stand-in is the `StateFlow<Int>` the registry exposes.
 *
 * Not locally compilable (no Android SDK in the build env); ships
 * correctness-by-construction + CI/Studio, as with the Phase L2 binder PR.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundTaskLivenessInstrumentedTest {

    private sealed interface Event {
        data class Active(val count: Int) : Event
        data object Idle : Event
    }

    private class RecordingHandle : TaskLivenessHandle {
        @Volatile
        var events: List<Event> = emptyList()
            private set

        override fun onActive(taskCount: Int) {
            events = events + Event.Active(taskCount)
        }

        override fun onCountChanged(taskCount: Int) = Unit

        override fun onIdle() {
            events = events + Event.Idle
        }
    }

    @Test
    fun inFlightTask_holdsLivenessUp_thenCompletionTearsItDown() = runBlocking {
        val inFlight = MutableStateFlow(0)
        val handle = RecordingHandle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        ForegroundTaskLiveness(inFlight, handle).launchIn(scope)

        inFlight.value = 1 // a Layer-2 task starts
        withTimeout(2_000) {
            while (handle.events != listOf<Event>(Event.Active(1))) yield()
        }

        inFlight.value = 0 // it completes
        withTimeout(2_000) {
            while (handle.events != listOf(Event.Active(1), Event.Idle)) yield()
        }

        scope.cancel()
        assertEquals(listOf(Event.Active(1), Event.Idle), handle.events)
    }
}
