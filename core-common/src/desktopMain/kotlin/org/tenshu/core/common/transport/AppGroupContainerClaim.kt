package org.tenshu.core.common.transport

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Asks macOS for the App Group container once per process, before anything opens a path in it.
 *
 * ⚠⚠ **Without this a packaged app is refused its own container, silently.** macOS lets a
 * development-signed app into `~/Library/Group Containers/<group>` only after the app has asked for
 * the container through `containerURL(forSecurityApplicationGroupIdentifier:)`. Until then every
 * `open`/`mkdir` there fails with `EPERM`. `tccd` logs a `kTCCServiceSystemPolicyAppDataDetailed`
 * refusal and shows **no dialog**. The JVM opens the path directly and never makes that call, so on
 * 2026-10-29 a freshly installed Control died at start (SQLite error 14) and Photos lost every
 * write, while the apps reported only "Control not running".
 *
 * The grant goes to the process that **launched** the caller (measured: a helper run from a shell
 * granted the shell), so the bundled `Contents/Helpers/appgroup-claim` run from here credits this
 * app. It is a helper rather than an FFM call because eight of the ten apps bundle Java 21.
 * docs/tech-debt.md "A packaged desktop app is refused its own App Group container".
 *
 * Best effort: a dev run has no bundle and therefore no helper, and the claim is skipped. A packaged
 * app whose helper is missing or fails logs one line naming the cause, because the refusal that
 * follows otherwise shows up far from here, as a database or file error.
 */
internal object AppGroupContainerClaim {

    const val HELPER_NAME = "appgroup-claim"

    /** jpackage sets this to the launcher, `<App>.app/Contents/MacOS/<App>`. */
    private const val APP_PATH_PROPERTY = "jpackage.app-path"

    @Volatile private var claimed = false
    @Volatile private var claimedPath: String? = null

    /**
     * Claims [groupId] once per process. A concurrent caller waits until the claim has finished.
     *
     * ⚠⚠ The latch is set only **after** the helper returns. Set before it, a second thread passed
     * the fast path while the first still waited on the helper, and opened the container before the
     * grant existed: the original EPERM, now intermittent. A failed or timed-out claim is not
     * retried. The failure is logged once, and the refusal that follows names the real cause.
     */
    fun ensureClaimed(
        groupId: String,
        launcherPath: String? = System.getProperty(APP_PATH_PROPERTY),
    ): String? {
        if (claimed) return claimedPath
        synchronized(this) {
            if (claimed) return claimedPath
            try {
                val helper = helperFor(launcherPath) ?: return null
                val outcome = run(helper, groupId)
                claimedPath = outcome.path
                outcome.failure?.let { System.err.println("AppGroupContainerClaim: $it") }
                return claimedPath
            } finally {
                claimed = true
            }
        }
    }

    /** Test seam: forget that this process has claimed. */
    internal fun resetForTest() {
        synchronized(this) {
            claimed = false
            claimedPath = null
        }
    }

    /**
     * The bundled helper beside the launcher at [launcherPath], or `null` when this is not a
     * packaged run (no launcher, or a launcher outside an `.app`) or the helper is absent.
     * A packaged app without the helper is logged here, because it is a build defect.
     */
    fun helperFor(launcherPath: String?): File? {
        val launcher = launcherPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        val contents = launcher.parentFile?.parentFile ?: return null
        if (contents.name != "Contents" || contents.parentFile?.name?.endsWith(".app") != true) return null
        val helper = File(contents, "Helpers/$HELPER_NAME")
        if (!helper.canExecute()) {
            System.err.println(
                "AppGroupContainerClaim: ${helper.path} is missing — the App Group container will " +
                    "be refused (rebuild with packageDesktopApp, which embeds it)",
            )
            return null
        }
        return helper
    }

    /** What one helper run produced: the container path, or a description of the failure. */
    internal data class Outcome(val path: String?, val failure: String?)

    /**
     * Runs [helper] for [groupId]. The path is read from **stdout only**: stderr is kept apart, so
     * a warning the helper prints can never end up inside the path the sockets are built from.
     */
    internal fun run(helper: File, groupId: String, timeoutSeconds: Long = 10): Outcome = try {
        val stderr = File.createTempFile("appgroup-claim", ".err")
        try {
            val process = ProcessBuilder(helper.absolutePath, groupId)
                .redirectError(stderr)
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Outcome(null, "${helper.name} timed out after ${timeoutSeconds}s")
            } else {
                val stdout = process.inputStream.bufferedReader().readText().trim()
                val exit = process.exitValue()
                when {
                    exit != 0 -> Outcome(
                        null,
                        "${helper.name} exited $exit: ${listOf(stdout, stderr.readText().trim())
                            .filter { it.isNotEmpty() }.joinToString(" / ")}",
                    )
                    // Claimed, but no path reported (an older helper): the caller falls back.
                    stdout.isEmpty() -> Outcome(null, null)
                    // One absolute path, nothing else: anything more is not a path we can trust.
                    !stdout.startsWith("/") || stdout.contains('\n') ->
                        Outcome(null, "${helper.name} printed no container path: '$stdout'")
                    else -> Outcome(stdout, null)
                }
            }
        } finally {
            stderr.delete()
        }
    } catch (e: Exception) {
        Outcome(null, "${helper.name} could not run: ${e.message}")
    }

    /** Runs [helper] and returns the Foundation-resolved container path, or `null` on failure. */
    fun claimPath(helper: File, groupId: String, timeoutSeconds: Long = 10): String? =
        run(helper, groupId, timeoutSeconds).path

    /** Runs [helper] for [groupId]; returns `null` on success, else a description of the failure. */
    fun claim(helper: File, groupId: String, timeoutSeconds: Long = 10): String? =
        run(helper, groupId, timeoutSeconds).failure
}
