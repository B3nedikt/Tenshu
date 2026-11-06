package app.tenshu.hub

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.home.device.DeviceCommand

/**
 * HD-D7 (actuation half): the at-most-once decision table reused from H4
 * ([HubCommandGate]) under the tight **2-minute** device-command staleness
 * window, plus the durable journal's re-delivery no-op — both load-bearing
 * (a backlog-replayed toggle must never flip lights at 3 a.m.; see
 * `docs/sync/threat-model.md`).
 */
class DeviceCommandGateTest {

    private val target = SimulatedDeviceBridge.LIGHT_ID
    private val window = DeviceCommand.STALE_WINDOW_MS

    private fun decide(
        ageMs: Long,
        alreadyConsumed: Boolean = false,
    ): HubCommandGate.Decision = HubCommandGate.decide(
        targetDeviceId = target,
        issuedAtEpochMs = 1_000_000_000L,
        ownDeviceId = target, // ownership pre-satisfied — the host checks the bridge set
        nowEpochMs = 1_000_000_000L + ageMs,
        alreadyConsumed = alreadyConsumed,
        staleWindowMs = window,
    )

    @Test
    fun freshCommandExecutes() {
        assertEquals(HubCommandGate.Decision.Execute, decide(ageMs = 0))
        assertEquals(HubCommandGate.Decision.Execute, decide(ageMs = 30_000))
    }

    @Test
    fun exactlyAtTheTwoMinuteBoundary_stillExecutes() {
        assertEquals(HubCommandGate.Decision.Execute, decide(ageMs = window))
    }

    @Test
    fun oneMillisecondPastTheWindow_isStale() {
        assertEquals(HubCommandGate.Decision.Stale, decide(ageMs = window + 1))
    }

    @Test
    fun hoursOldBacklogReplay_isStale() {
        assertEquals(HubCommandGate.Decision.Stale, decide(ageMs = 6L * 60 * 60 * 1000))
    }

    @Test
    fun consumedWinsOverFreshness() {
        assertEquals(
            HubCommandGate.Decision.AlreadyConsumed,
            decide(ageMs = 0, alreadyConsumed = true),
        )
    }

    @Test
    fun journalMakesRedeliveryANoOp_acrossReloads() {
        val dir = createTempDirectory("device-cmd-journal").toFile()
        try {
            val file = File(dir, "consumed-device-commands.log")
            val journal = ConsumedCommandJournal(file)
            assertFalse(journal.isConsumed("cmd-1"))
            journal.markConsumed("cmd-1")
            assertTrue(journal.isConsumed("cmd-1"))

            // A fresh journal over the same file (hub restart) still knows it.
            val reloaded = ConsumedCommandJournal(file)
            assertTrue(reloaded.isConsumed("cmd-1"))
            assertFalse(reloaded.isConsumed("cmd-2"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
