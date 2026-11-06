package app.tenshu.control.lifecycle

import java.io.File

/**
 * Installs / removes the macOS **LaunchAgents** that autostart Control's two
 * login-started processes (docs/proposals/desktop-macos-integration.md §4 task 2 +
 * §6a Phase-4 packaging):
 *
 * - the **daemon** ([forDaemon]) — the JVM Control process, booted hidden
 *   (`TENSHU_CONTROL_START_HIDDEN=true`) so it comes straight to the tray, no
 *   window; `KeepAlive=false` so an explicit Quit stays quit until next login.
 * - the **tray** ([forTray]) — the embedded Swift menu-bar agent
 *   (`Contents/Helpers/tray-agent`), `KeepAlive=true` so the lightweight
 *   menu-bar presence is *always* up. It runs as its own agent, independent of
 *   the daemon's lifecycle, precisely so it survives a daemon Quit: that's what
 *   makes the tray's "Open Control" cold-launch (which re-spawns the daemon)
 *   meaningful. (SMAppService is the future modernisation; it needs a JVM↔native
 *   bridge the repo doesn't have yet — see the proposal §6a / §8 Q1.)
 *
 * Plist generation ([plistContent], [writePlist]) and state ([isInstalled],
 * [removePlist]) are pure file ops and unit-tested; [install]/[uninstall]
 * additionally run `launchctl` (manual/on-device).
 */
class LaunchAgentManager(
    private val label: String = DEFAULT_LABEL,
    private val launchAgentsDir: File = defaultLaunchAgentsDir(),
    /** `KeepAlive` value: the tray is always-on (true); the daemon is quit-able (false). */
    private val keepAlive: Boolean = false,
    /** `EnvironmentVariables` for the agent; an empty map omits the key entirely. */
    private val environment: Map<String, String> = mapOf(START_HIDDEN_ENV to "true"),
) {
    fun plistFile(): File = File(launchAgentsDir, "$label.plist")

    /**
     * Whether the plist file exists. ⚠ This is **not** "autostart works" — launchd
     * keeps a *separate*, per-user disabled database that overrides the plist
     * entirely. Use [isAutostartEnabled] for the user-facing question; see
     * [isDisabled] for why the distinction cost a real debugging session.
     */
    fun isInstalled(): Boolean = plistFile().exists()

    /**
     * Whether launchd has this label marked **disabled** in its per-user database
     * (`launchctl print-disabled gui/<uid>`).
     *
     * ⚠⚠ WHY THIS EXISTS. That database is independent of the plist file and
     * outlives it: [uninstall] runs `launchctl unload **-w**`, and `-w` is exactly
     * the flag that writes the disabled key. Deleting or re-writing the plist does
     * NOT clear it, and a disabled service is never started at login however
     * correct the plist looks. macOS's own System Settings → Login Items writes the
     * same flag. Before this method existed the Settings toggle reported autostart
     * as ON (it only checked [isInstalled]) while launchd had it disabled — the
     * off→on round-trip was a one-way door that silently killed autostart forever.
     *
     * Returns false when the state cannot be read: an absent record *is* launchd's
     * "enabled" default, so a failure to read must not present as "off".
     */
    fun isDisabled(): Boolean =
        currentUid()
            ?.let { uid -> launchctlOutput("print-disabled", "gui/$uid") }
            ?.let { parseDisabledState(it, label) }
            ?: false

    /** The user-facing "start at login is on" question: a plist AND not disabled. */
    fun isAutostartEnabled(): Boolean = isInstalled() && !isDisabled()

    /**
     * Clears launchd's disabled flag for this label (`launchctl enable`).
     *
     * ⚠ `enable` is deliberately NOT `load`/`bootstrap`: it only clears the
     * database key and starts nothing, so it preserves the property the callers
     * depend on — this very process IS the daemon, and loading a `RunAtLoad` agent
     * live would boot a second one. `ControlInstanceGuard` would now refuse that
     * copy and it would exit, but starting it at all is wasted work. The plist
     * still takes effect at the next login; this just makes sure launchd is
     * willing to honour it.
     */
    fun enable(): Boolean =
        currentUid()?.let { uid -> runLaunchctl("enable", "gui/$uid/$label") } ?: false

    /** The `launchd` property list that runs [executablePath] at login. */
    fun plistContent(executablePath: String): String {
        val lines = mutableListOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
            "<plist version=\"1.0\">",
            "<dict>",
            "    <key>Label</key>",
            "    <string>$label</string>",
            "    <key>ProgramArguments</key>",
            "    <array>",
            "        <string>${executablePath.escapeXml()}</string>",
            "    </array>",
            "    <key>RunAtLoad</key>",
            "    <true/>",
        )
        if (environment.isNotEmpty()) {
            lines += "    <key>EnvironmentVariables</key>"
            lines += "    <dict>"
            environment.forEach { (k, v) ->
                lines += "        <key>${k.escapeXml()}</key>"
                lines += "        <string>${v.escapeXml()}</string>"
            }
            lines += "    </dict>"
        }
        lines += "    <key>KeepAlive</key>"
        lines += if (keepAlive) "    <true/>" else "    <false/>"
        lines += "</dict>"
        lines += "</plist>"
        return lines.joinToString("\n") + "\n"
    }

    /** Writes the plist (creating the LaunchAgents dir); returns the file. */
    fun writePlist(executablePath: String): File {
        launchAgentsDir.mkdirs()
        return plistFile().apply { writeText(plistContent(executablePath)) }
    }

    /** Deletes the plist. Returns true if a file was removed. */
    fun removePlist(): Boolean = plistFile().let { it.exists() && it.delete() }

    /** Writes the plist and `launchctl load`s it. Returns true on success. */
    fun install(executablePath: String): Boolean {
        val file = writePlist(executablePath)
        return runLaunchctl("load", "-w", file.absolutePath)
    }

    /** `launchctl unload`s and removes the plist. */
    fun uninstall(): Boolean {
        val file = plistFile()
        if (file.exists()) runLaunchctl("unload", "-w", file.absolutePath)
        return removePlist()
    }

    private fun runLaunchctl(vararg args: String): Boolean = runCatching {
        ProcessBuilder(listOf("launchctl", *args)).inheritIO().start().waitFor() == 0
    }.getOrDefault(false)

    /** `launchctl` with its stdout captured (the state queries need to read it). */
    private fun launchctlOutput(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("launchctl", *args))
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }.getOrNull()

    companion object {
        /** The daemon agent's label (also the packaged-app `CFBundleIdentifier`). */
        const val DEFAULT_LABEL = "app.tenshu.control"

        /** The tray agent's label (a sibling of the daemon's). */
        const val TRAY_LABEL = "app.tenshu.control.tray"

        const val START_HIDDEN_ENV = "TENSHU_CONTROL_START_HIDDEN"

        /** Daemon agent: hidden boot, quit-able (`KeepAlive=false`). */
        fun forDaemon(launchAgentsDir: File = defaultLaunchAgentsDir()): LaunchAgentManager =
            LaunchAgentManager(
                label = DEFAULT_LABEL,
                launchAgentsDir = launchAgentsDir,
                keepAlive = false,
                environment = mapOf(START_HIDDEN_ENV to "true"),
            )

        /** Tray agent: always-on (`KeepAlive=true`), no daemon-only env. */
        fun forTray(launchAgentsDir: File = defaultLaunchAgentsDir()): LaunchAgentManager =
            LaunchAgentManager(
                label = TRAY_LABEL,
                launchAgentsDir = launchAgentsDir,
                keepAlive = true,
                environment = emptyMap(),
            )

        /**
         * The embedded tray binary that ships next to the daemon launcher, given
         * the daemon's executable path (`<app>/Contents/MacOS/TenshuControl`):
         * the `<app>/Contents/Helpers/tray-agent` file, or null if it isn't there.
         * Dev runs and unpackaged launches have no such binary, so the caller
         * simply skips installing the tray agent.
         */
        fun trayBinaryFor(daemonExecPath: String): File? {
            val contents = File(daemonExecPath).parentFile?.parentFile ?: return null
            return File(contents, "Helpers/tray-agent").takeIf { it.isFile }
        }

        /**
         * Reads one label's verdict out of `launchctl print-disabled gui/<uid>`:
         * true = disabled, false = explicitly enabled, **null = no record at all**
         * (launchd's default, which means enabled).
         *
         * The output is one indented line per known label:
         * ```
         * disabled services = {
         * 	"app.tenshu.control" => enabled
         * 	"com.example.other" => disabled
         * }
         * ```
         * ⚠ Match the **quoted** label, never a bare `contains`: `app.tenshu.control`
         * is a prefix of `app.tenshu.control.tray`, so a substring test reports the
         * daemon as disabled whenever the *tray* label is — which is exactly the
         * pairing this codebase produces ([uninstall] disables both).
         * ⚠ Older macOS prints `=> true` / `=> false` for the same two states.
         */
        internal fun parseDisabledState(printDisabledOutput: String, label: String): Boolean? {
            val prefix = "\"$label\" =>"
            val line = printDisabledOutput.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith(prefix) }
                ?: return null
            return when (line.removePrefix(prefix).trim().lowercase()) {
                "disabled", "true" -> true
                "enabled", "false" -> false
                else -> null
            }
        }

        /**
         * The current user's numeric uid, for launchd's `gui/<uid>` domain. Shelled
         * out rather than taken from a JDK API because the supported one
         * (`com.sun.security.auth.module.UnixSystem`) lives behind a module export
         * this app does not open.
         */
        private fun currentUid(): String? = runCatching {
            val process = ProcessBuilder("/usr/bin/id", "-u").redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            out.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
        }.getOrNull()

        private fun defaultLaunchAgentsDir(): File =
            File(System.getProperty("user.home") ?: ".", "Library/LaunchAgents")
    }
}

private fun String.escapeXml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
