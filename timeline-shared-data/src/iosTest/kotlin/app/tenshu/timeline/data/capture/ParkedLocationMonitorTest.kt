package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * `ios-shared-store-sqlite.md` B.16 (233), (238): a detached capture's manager stays monitoring only
 * while a successor needs it; at most one is parked, and stopping releases it. Over made-up
 * managers whose stop is recorded.
 */
class ParkedLocationMonitorTest {
    private class Manager(val name: String)

    @Test
    fun aParkedManagerIsStoppedAndReleasedAndAtMostOneIsParked() {
        val stopped = ArrayList<String>()
        val monitor = ParkedLocationMonitor<Manager> { stopped += it.name }
        val a = Manager("a")
        monitor.park(a)
        assertSame(a, monitor.current)
        assertEquals(emptyList(), stopped, "parked, still monitoring")
        // A second park stops the first: no two managers alive.
        monitor.park(Manager("b"))
        assertEquals(listOf("a"), stopped)
        // An opt-out (or a successor's start, or a graph given up): stopped and released.
        monitor.stopAndRelease()
        assertEquals(listOf("a", "b"), stopped)
        assertNull(monitor.current)
        monitor.stopAndRelease()
        assertEquals(listOf("a", "b"), stopped, "idempotent")
    }
}
