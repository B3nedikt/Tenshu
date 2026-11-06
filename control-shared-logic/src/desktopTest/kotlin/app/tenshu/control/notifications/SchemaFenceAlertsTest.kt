package app.tenshu.control.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fence alerts once per **engagement** — not per tick, not per changed service set —
 * and an unknown verdict is never a transition.
 */
class SchemaFenceAlertsTest {

    private class Recorder : SchemaFenceAlertHandle {
        val events = mutableListOf<String>()
        override fun onFenceEngaged(fencedServiceIds: List<String>) {
            events += "engaged:" + fencedServiceIds.joinToString(",")
        }
        override fun onFenceCleared() {
            events += "cleared"
        }
    }

    @Test
    fun engagesOnce_thenClearsOnce() {
        val handle = Recorder()
        val alerts = SchemaFenceAlerts(handle)

        alerts.onVerdict(listOf("a"))
        alerts.onVerdict(listOf("a"))
        // The set growing is the same engagement: still "update Control", so no second alert.
        alerts.onVerdict(listOf("a", "b"))
        alerts.onVerdict(emptyList())
        alerts.onVerdict(emptyList())

        assertEquals(listOf("engaged:a", "cleared"), handle.events)
    }

    @Test
    fun unknownIsNeverATransition() {
        val handle = Recorder()
        val alerts = SchemaFenceAlerts(handle)

        alerts.onVerdict(null)
        alerts.onVerdict(listOf("a"))
        // A restart's "not yet evaluated" between two fenced verdicts must not clear and re-alert.
        alerts.onVerdict(null)
        alerts.onVerdict(listOf("a"))

        assertEquals(listOf("engaged:a"), handle.events)
    }

    @Test
    fun clearOnAColdStartWithNothingAlerted_isSilent() {
        val handle = Recorder()
        SchemaFenceAlerts(handle).onVerdict(emptyList())
        assertEquals(emptyList(), handle.events, "nothing was alerted, so there is nothing to withdraw")
    }

    @Test
    fun aPersistedMemory_suppressesTheReAlertAcrossProcesses() {
        // Android: every background wake is a new process, and a fenced device stays fenced.
        val memory = SchemaFenceAlertMemory.InProcess()
        val first = Recorder()
        SchemaFenceAlerts(first, memory).onVerdict(listOf("a"))

        val second = Recorder()
        val nextProcess = SchemaFenceAlerts(second, memory)
        nextProcess.onVerdict(listOf("a"))
        assertEquals(emptyList(), second.events, "the same engagement is not re-alerted")

        nextProcess.onVerdict(emptyList())
        assertEquals(listOf("cleared"), second.events, "but the alert from before is withdrawn")
    }
}
