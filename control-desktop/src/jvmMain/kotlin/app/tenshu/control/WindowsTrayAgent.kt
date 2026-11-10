package app.tenshu.control

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Control's native Windows tray agent (`control-desktop/windows/TrayAgent`, desktop-windows-port.md §3.9, D5):
 * finding it, starting it, and keeping it running — the Windows counterpart of `ensureTrayRunning`'s macOS
 * arm.
 *
 * Where it lives:
 *  - **Packaged:** `tray-agent\tray-agent.exe` beside Control's launcher inside the MSIX (`packageMsix` puts it
 *    there). ⚠ Started by this packaged process, it runs in the **same package context**, so the bridge gate
 *    admits it as `Tenshu.Control` and its notifications carry the package's identity.
 *  - **Dev run:** only when `TENSHU_TRAY_AGENT` names a built `tray-agent.exe` — developer mode then admits the
 *    identity-less agent on the bridge, and it needs `TENSHU_TRAY_DEV=1`, which is passed along.
 *
 * Supervision is a re-start every [SUPERVISE_SECONDS]: the agent holds a single-instance mutex and exits at once
 * when one is already running, so starting it again is both harmless and the whole restart policy.
 *
 * ⚠ While an agent binary is present Control draws no Compose tray of its own — two icons for one app.
 */
internal object WindowsTrayAgent {

    private const val SUPERVISE_SECONDS = 30L

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** The agent to run, or `null` when there is none (not Windows, not packaged and no dev override). */
    val binary: File? by lazy {
        if (!isWindows) return@lazy null
        System.getenv("TENSHU_TRAY_AGENT")?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
            ?.let { return@lazy it }
        val launcher = System.getProperty("jpackage.app-path")?.let { File(it) } ?: return@lazy null
        File(launcher.parentFile, "tray-agent${File.separator}tray-agent.exe").takeIf { it.isFile }
    }

    private val devOverride: Boolean get() = !System.getenv("TENSHU_TRAY_AGENT").isNullOrBlank()

    /** Starts the agent now and keeps it running. Best-effort: a missing tray must never stop Control. */
    fun startAndSupervise(socketDir: String, log: (String) -> Unit) {
        val agent = binary ?: return
        val supervisor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "WindowsTrayAgent").apply { isDaemon = true }
        }
        supervisor.scheduleWithFixedDelay({ start(agent, socketDir, log) }, 0, SUPERVISE_SECONDS, TimeUnit.SECONDS)
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
