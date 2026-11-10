package app.tenshu.control.lifecycle

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Start at login on Windows (desktop-windows-port.md §3.5): the package's startup task, toggled through the native
 * tray agent — `tray-agent --startup-task status|enable|disable` — because the switch is WinRT (`StartupTask`) and
 * the JVM cannot call it. The agent runs as a child of this packaged process, so it has the package identity the
 * API needs.
 *
 * ⚠ Exists only where the agent does — the MSIX. A dev run has no package, so no startup task, and
 * [DesktopShellSupport] then hides the switch rather than offer one that cannot work.
 */
class WindowsStartupTask(
    private val agent: File,
    /** Runs the agent with [args] and returns its first output line, or null on failure. Injectable for tests. */
    private val run: (agent: File, args: List<String>) -> String? = ::runAgent,
) {
    enum class State {
        ENABLED,
        DISABLED,
        /** Turned off in Settings → Apps → Startup. ⚠ Only that page may turn it back on. */
        DISABLED_BY_USER,
        DISABLED_BY_POLICY,
        ENABLED_BY_POLICY,
        UNAVAILABLE,
    }

    fun state(): State = call("status")
    fun enable(): State = call("enable")
    fun disable(): State = call("disable")

    private fun call(verb: String): State = parse(run(agent, listOf("--startup-task", verb)))

    companion object {
        /** The agent's answer → a [State]. Anything unrecognised is [State.UNAVAILABLE], never a guess at "on". */
        fun parse(line: String?): State = when (line?.trim()) {
            "enabled" -> State.ENABLED
            "disabled" -> State.DISABLED
            "disabledByUser" -> State.DISABLED_BY_USER
            "disabledByPolicy" -> State.DISABLED_BY_POLICY
            "enabledByPolicy" -> State.ENABLED_BY_POLICY
            else -> State.UNAVAILABLE
        }

        /** The task for this process, or null off Windows or without the packaged agent. */
        fun forThisProcess(): WindowsStartupTask? = WindowsTrayAgentBinary.packaged()?.let(::WindowsStartupTask)

        /**
         * ⚠ Waits for the exit first, then reads: the answer is one word, far below any pipe buffer. Reading first
         * would block on an agent that hangs without printing, and the timeout would never apply.
         */
        private fun runAgent(agent: File, args: List<String>): String? = runCatching {
            val process = ProcessBuilder(listOf(agent.absolutePath) + args)
                .directory(agent.parentFile)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            process.inputStream.bufferedReader().use { it.readLine() }
        }.getOrNull()

        private const val AGENT_TIMEOUT_SECONDS = 15L
    }
}

/**
 * Where the native Windows tray agent lives in the package: `tray-agent\tray-agent.exe` beside Control's launcher
 * (`packageMsix` puts it there). Shared by the tray supervisor and the start-at-login switch.
 */
object WindowsTrayAgentBinary {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** The packaged agent, or null off Windows or outside the MSIX (no jpackage launcher, or no agent beside it). */
    fun packaged(): File? {
        if (!isWindows) return null
        val launcher = System.getProperty("jpackage.app-path")?.let { File(it) } ?: return null
        return File(launcher.parentFile, "tray-agent${File.separator}tray-agent.exe").takeIf { it.isFile }
    }
}
