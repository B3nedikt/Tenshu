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

    /**
     * Fallen back, Control draws its own tray, but Windows can still start the agent itself (a toast click, the
     * startup task). Nothing is started while fallen back; a healthy agent ends the fallback, so there is one icon.
     */
    @Test
    fun aHealthyAgentAfterTheFallbackEndsIt() {
        repeat(WindowsTraySupervisor.MAX_UNHEALTHY_ROUNDS + 1) { supervisor.tick(now) }
        val startsBefore = started.size
        assertEquals(Outcome.WAITING, supervisor.tick(now))
        running = Running(pid = 9, ageSeconds = 5)
        assertEquals(Outcome.WAITING, supervisor.tick(now), "an agent in its grace period proves nothing yet")
        running = old
        heartbeat = fresh
        assertEquals(Outcome.RECOVERED, supervisor.tick(now))
        assertEquals(Outcome.HEALTHY, supervisor.tick(now))
        assertEquals(startsBefore, started.size)
    }

    /** After a sleep the heartbeat is as old as the sleep; judging it then would kill a healthy agent. */
    @Test
    fun theFirstRoundAfterASleepJudgesNothing() {
        running = old
        heartbeat = fresh
        supervisor.tick(now)
        val afterSleep = now + 3_600_000
        assertEquals(Outcome.RESUMED, supervisor.tick(afterSleep))
        assertEquals(emptyList(), stopped)
        heartbeat = TrayLiveness.Heartbeat(afterSleep + 25_000, visible = true)
        assertEquals(Outcome.HEALTHY, supervisor.tick(afterSleep + 30_000))
        assertEquals(emptyList(), stopped)
    }

    /** The log must show whether an agent it started ever became healthy, not stop at its grace period. */
    @Test
    fun theLogShowsTheAgentBecomingHealthyAfterItsGracePeriod() {
        supervisor.tick(now) // spawn
        running = Running(pid = 8, ageSeconds = 5)
        supervisor.tick(now) // grace
        supervisor.tick(now) // grace again: not logged
        running = old
        heartbeat = fresh
        supervisor.tick(now) // healthy: logged
        supervisor.tick(now) // healthy again: not logged
        assertEquals(
            listOf(TrayLiveness.Action.SPAWN, TrayLiveness.Action.SKIP, TrayLiveness.Action.SKIP),
            recorded,
        )
    }
}
