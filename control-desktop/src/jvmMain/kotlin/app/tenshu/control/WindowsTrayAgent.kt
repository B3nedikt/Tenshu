package app.tenshu.control

import app.tenshu.control.lifecycle.TrayLiveness
import app.tenshu.control.lifecycle.WindowsTrayAgentBinary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Control's native Windows tray agent (`control-desktop/windows/TrayAgent`, desktop-windows-port.md §3.9, D5):
 * finding it, starting it, and keeping it working — the Windows counterpart of `ensureTrayRunning`'s macOS arm.
 *
 * Where it lives:
 *  - **Packaged:** `tray-agent\tray-agent.exe` beside Control's launcher inside the MSIX (`packageMsix` puts it
 *    there). ⚠ Started by this packaged process, it runs in the **same package context**, so the bridge gate
 *    admits it as `Tenshu.Control` and its notifications carry the package's identity.
 *  - **Dev run:** only when `TENSHU_TRAY_AGENT` names a built `tray-agent.exe` — developer mode then admits the
 *    identity-less agent on the bridge, and it needs `TENSHU_TRAY_DEV=1`, which is passed along.
 *
 * Supervision is [WindowsTraySupervisor], every [SUPERVISE_SECONDS]: the agent's heartbeat judged by the same
 * [TrayLiveness] table as the macOS tray. If the agent cannot be kept healthy, [fellBack] turns true and Control
 * draws its own Compose tray instead — never zero icons, and never two.
 */
internal object WindowsTrayAgent {

    private const val SUPERVISE_SECONDS = 30L

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** The agent to run, or `null` when there is none (not Windows, not packaged and no dev override). */
    val binary: File? by lazy {
        if (!isWindows) return@lazy null
        System.getenv("TENSHU_TRAY_AGENT")?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
            ?.let { return@lazy it }
        WindowsTrayAgentBinary.packaged()
    }

    private val devOverride: Boolean get() = !System.getenv("TENSHU_TRAY_AGENT").isNullOrBlank()

    private val _fellBack = MutableStateFlow(false)

    /**
     * True while the agent is given up on; Control then shows its own tray (`WindowsControlTray`). Turns false again
     * if a healthy agent appears anyway — Windows can start one itself (a toast click, the startup task) — so the
     * Compose tray steps aside and there is never a second icon.
     */
    val fellBack: StateFlow<Boolean> = _fellBack.asStateFlow()

    /** Starts the agent now and keeps it working. Best-effort: a missing tray must never stop Control. */
    fun startAndSupervise(socketDir: String, log: (String) -> Unit) {
        val agent = binary ?: return
        val dir = File(socketDir)
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "WindowsTrayAgent").apply { isDaemon = true }
        }
        val supervisor = WindowsTraySupervisor(
            find = { findAgent(agent) },
            start = { start(agent, socketDir, log) },
            stop = ::stopAndAwait,
            heartbeat = { TrayLiveness.readHeartbeat(dir) },
            record = { decision, now -> TrayLiveness.appendLog(dir, decision, now) },
        )
        executor.scheduleWithFixedDelay({
            runCatching {
                when (supervisor.tick(System.currentTimeMillis())) {
                    WindowsTraySupervisor.Outcome.FELL_BACK -> {
                        log("[tray] the native tray agent could not be kept running; using Control's own tray")
                        _fellBack.value = true
                    }
                    WindowsTraySupervisor.Outcome.RECOVERED -> {
                        log("[tray] a healthy native tray agent is running again; Control's own tray steps aside")
                        _fellBack.value = false
                    }
                    else -> Unit
                }
            }.onFailure { log("[tray] supervision failed: ${it.message}") }
        }, 0, SUPERVISE_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * The running agent, by image name. ⚠ Any `tray-agent.exe` counts, not only ours: the single-instance mutex is
     * per session, so whichever copy holds it decides whether ours can start.
     *
     * ⚠ The **oldest** match. Settings' start-at-login switch runs the same exe for a moment
     * (`tray-agent --startup-task …`), and Windows does not expose a process's arguments to tell it apart; those
     * runs last well under a second, so the long-lived agent is the oldest.
     */
    private fun findAgent(agent: File): WindowsTraySupervisor.Running? =
        ProcessHandle.allProcesses()
            .filter { handle ->
                handle.info().command().orElse(null)?.let { File(it).name.equals(agent.name, ignoreCase = true) } == true
            }
            .min(compareBy { it.info().startInstant().orElse(Instant.MAX) })
            .map { handle ->
                WindowsTraySupervisor.Running(
                    pid = handle.pid(),
                    ageSeconds = handle.info().startInstant()
                        .map { Duration.between(it, Instant.now()).seconds }.orElse(null),
                )
            }
            .orElse(null)

    private fun stopAndAwait(pid: Long) {
        ProcessHandle.of(pid).ifPresent { handle ->
            handle.destroyForcibly()
            runCatching { handle.onExit().get(5, TimeUnit.SECONDS) }
        }
    }

    private fun start(agent: File, socketDir: String, log: (String) -> Unit) {
        runCatching {
            val builder = ProcessBuilder(agent.absolutePath)
                .directory(agent.parentFile)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            // ⚠ The socket directory is handed over explicitly, as on macOS: the two sides resolve it
            // independently otherwise, and agree only while neither is configured.
            builder.environment()["TENSHU_IPC_DIR"] = socketDir
            if (devOverride) builder.environment()["TENSHU_TRAY_DEV"] = "1"
            builder.start()
        }.onFailure { log("[tray] could not start ${agent.absolutePath}: ${it.message}") }
    }
}

/**
 * One supervision round for the Windows tray agent, free of process calls so every path is unit-testable.
 *
 * Each [tick] asks [TrayLiveness.decide] about the running agent and acts: start one (SPAWN), leave it (SKIP), or
 * kill and restart it (REPLACE). ⚠ Repeats are not logged — this runs every 30 s, and the log is a breadcrumb
 * trail of *decisions*: every round that acts is written, and otherwise only a change between "in its grace
 * period" and "healthy", so the log always shows whether an agent it started ever became healthy.
 *
 * ⚠⚠ Gives up after [MAX_UNHEALTHY_ROUNDS] consecutive rounds that had to act. An agent that crashes on start (a
 * missing Windows App Runtime, say) would otherwise be restarted forever while the user has no icon at all;
 * falling back to Control's own tray trades the native look for a working one. While fallen back it starts
 * nothing, but keeps watching: a healthy agent that Windows starts by itself (a toast click, the startup task)
 * ends the fallback ([Outcome.RECOVERED]), so the Compose tray can step aside instead of making a second icon.
 *
 * ⚠ A round that follows a gap much longer than the interval (the machine slept) only resets the clock: the
 * agent's heartbeat is as old as the sleep until its own timer catches up, and judging it then would kill a
 * healthy agent on every resume.
 */
internal class WindowsTraySupervisor(
    private val find: () -> Running?,
    private val start: () -> Unit,
    private val stop: (pid: Long) -> Unit,
    private val heartbeat: () -> TrayLiveness.Heartbeat?,
    private val record: (TrayLiveness.Decision, Long) -> Unit,
) {
    data class Running(val pid: Long, val ageSeconds: Long?)

    enum class Outcome {
        /** The agent is healthy, or in its grace period. */
        HEALTHY,
        /** An agent was started or replaced. */
        ACTED,
        /** Given up on this round: Control should draw its own tray. */
        FELL_BACK,
        /** Fallen back, and still no healthy agent: nothing to do. */
        WAITING,
        /** Fallen back, and a healthy agent appeared: Control's own tray should step aside. */
        RECOVERED,
        /** The first round after a long gap (sleep): nothing judged. */
        RESUMED,
    }

    private enum class Kind { ACTED, GRACE, HEALTHY }

    private var unhealthyRounds = 0
    private var fellBack = false
    private var lastKind: Kind? = null
    private var lastTickMillis: Long? = null

    fun tick(nowEpochMillis: Long): Outcome {
        val previous = lastTickMillis
        lastTickMillis = nowEpochMillis
        if (previous != null && nowEpochMillis - previous > RESUME_GAP_MILLIS) {
            record(
                TrayLiveness.Decision(
                    TrayLiveness.Action.SKIP,
                    "resumed after a ${(nowEpochMillis - previous) / 1000}s gap; judging from the next round",
                ),
                nowEpochMillis,
            )
            lastKind = null
            return Outcome.RESUMED
        }

        val running = find()
        val decision = TrayLiveness.decide(
            trayPid = running?.pid,
            processAgeSeconds = running?.ageSeconds,
            heartbeat = heartbeat(),
            nowEpochMillis = nowEpochMillis,
        )
        // A young agent inside its grace period is SKIPped too, but has proven nothing yet: it neither resets the
        // count nor adds to it, and it does not end a fallback.
        val inGrace = running?.ageSeconds?.let { it < TrayLiveness.GRACE_SECONDS } == true
        val kind = when {
            decision.action != TrayLiveness.Action.SKIP -> Kind.ACTED
            inGrace -> Kind.GRACE
            else -> Kind.HEALTHY
        }

        if (fellBack) {
            if (kind != Kind.HEALTHY) return Outcome.WAITING
            fellBack = false
            unhealthyRounds = 0
            record(decision, nowEpochMillis)
            lastKind = kind
            return Outcome.RECOVERED
        }

        if (kind == Kind.ACTED || kind != lastKind) {
            record(decision, nowEpochMillis)
            lastKind = kind
        }
        if (kind != Kind.ACTED) {
            if (kind == Kind.HEALTHY) unhealthyRounds = 0
            return Outcome.HEALTHY
        }
        if (++unhealthyRounds > MAX_UNHEALTHY_ROUNDS) {
            running?.let { stop(it.pid) }
            fellBack = true
            return Outcome.FELL_BACK
        }
        if (decision.action == TrayLiveness.Action.REPLACE) running?.let { stop(it.pid) }
        start()
        return Outcome.ACTED
    }

    companion object {
        /**
         * Four rounds that had to act. With the grace round that follows each start of an agent that lives but never
         * gets healthy, that is two to four minutes without a working icon.
         */
        const val MAX_UNHEALTHY_ROUNDS = 4

        /** Three supervision intervals: a gap this long means the machine slept, not that the agent is late. */
        const val RESUME_GAP_MILLIS = 90_000L
    }
}
