package app.tenshu.control

import app.tenshu.control.WindowsTraySupervisor.Outcome
import app.tenshu.control.WindowsTraySupervisor.Running
import app.tenshu.control.lifecycle.TrayLiveness
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Windows tray agent's supervision (desktop-windows-port.md §3.9 T3). [TrayLiveness.decide] itself is pinned by
 * `TrayLivenessTest`; this pins what the supervisor *does* with each verdict, and when it gives up.
 */
class WindowsTraySupervisorTest {

    private val now = 1_800_000_000_000L
    private val old = Running(pid = 7, ageSeconds = 3_600)
    private val fresh = TrayLiveness.Heartbeat(now - 5_000, visible = true)

    private var running: Running? = null
    private var heartbeat: TrayLiveness.Heartbeat? = null
    private val started = mutableListOf<Long>()
    private val stopped = mutableListOf<Long>()
    private val recorded = mutableListOf<TrayLiveness.Action>()

    private val supervisor = WindowsTraySupervisor(
        find = { running },
        start = { started += now },
        stop = { stopped += it },
        heartbeat = { heartbeat },
        record = { decision, _ -> recorded += decision.action },
    )

    @Test
    fun startsAnAgentWhenNoneIsRunning() {
        assertEquals(Outcome.ACTED, supervisor.tick(now))
        assertEquals(1, started.size)
        assertEquals(emptyList(), stopped)
    }

    @Test
    fun leavesAHealthyAgentAloneAndLogsOnlyTheFirstHealthyRound() {
        running = old
        heartbeat = fresh
        repeat(3) { assertEquals(Outcome.HEALTHY, supervisor.tick(now)) }
        assertEquals(emptyList(), started)
        assertEquals(listOf(TrayLiveness.Action.SKIP), recorded)
    }

    /** The case the heartbeat exists for: alive, holding the single-instance mutex, and no icon. */
    @Test
    fun replacesAnAgentWhoseIconIsNotPlaced() {
        running = old
        heartbeat = TrayLiveness.Heartbeat(now - 5_000, visible = false)
        assertEquals(Outcome.ACTED, supervisor.tick(now))
        assertEquals(listOf(7L), stopped)
        assertEquals(1, started.size)
    }

    @Test
    fun replacesAWedgedAgent() {
        running = old
        heartbeat = TrayLiveness.Heartbeat(now - 120_000, visible = true)
        assertEquals(Outcome.ACTED, supervisor.tick(now))
        assertEquals(listOf(7L), stopped)
    }

    /** An agent that crashes on start must not be restarted forever while the user has no icon at all. */
    @Test
    fun fallsBackAfterTooManyRoundsWithoutAHealthyAgent() {
        val outcomes = (1..WindowsTraySupervisor.MAX_UNHEALTHY_ROUNDS + 1).map { supervisor.tick(now) }
        assertEquals(List(WindowsTraySupervisor.MAX_UNHEALTHY_ROUNDS) { Outcome.ACTED } + Outcome.FELL_BACK, outcomes)
        assertEquals(WindowsTraySupervisor.MAX_UNHEALTHY_ROUNDS, started.size)
    }

    @Test
    fun aHealthyRoundResetsTheCount() {
        repeat(WindowsTraySupervisor.MAX_UNHEALTHY_ROUNDS) { supervisor.tick(now) }
        running = old
        heartbeat = fresh
        assertEquals(Outcome.HEALTHY, supervisor.tick(now))
        running = null
        assertEquals(Outcome.ACTED, supervisor.tick(now))
    }

    /** A young agent is inside its grace period: it has proven nothing, so it must not reset the count either. */
    @Test
    fun anAgentInItsGracePeriodDoesNotResetTheCount() {
        repeat(WindowsTraySupervisor.MAX_UNHEALTHY_ROUNDS) { supervisor.tick(now) }
        running = Running(pid = 8, ageSeconds = 1)
        assertEquals(Outcome.HEALTHY, supervisor.tick(now))
        running = null
        assertEquals(Outcome.FELL_BACK, supervisor.tick(now))
    }
}
