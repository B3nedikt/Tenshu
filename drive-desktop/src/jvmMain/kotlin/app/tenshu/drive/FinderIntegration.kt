package app.tenshu.drive

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Makes Tenshu Drive appear in Finder's sidebar (docs/proposals/desktop-macos-integration.md §5).
 *
 * The sidebar entry exists once an `NSFileProviderDomain` is registered for the File Provider
 * extension embedded in this bundle (`Contents/PlugIns/TenshuFileProvider.appex`). The JVM
 * cannot call `NSFileProviderManager` itself, so registration is the bundled
 * `Contents/MacOS/tenshu-domain` helper's job; this only runs it.
 *
 * Registration is idempotent, so it runs on every launch — that is also what re-creates the
 * entry after the user removes it or reinstalls the app.
 */
internal object FinderIntegration {

    /**
     * Best-effort and off the main thread: a dev run, a non-mac host or an unsigned bundle has
     * no helper, and none of those may delay or fail the app coming up.
     */
    fun registerDomain() {
        val helper = bundledHelper() ?: return
        thread(name = "finder-domain-registration", isDaemon = true) {
            // ⚠ Retried, because the first attempt after an install or update can fail with
            // providerNotFound: the system has not re-discovered the extension inside the
            // freshly copied bundle yet (measured 2026-10-04 — refused seconds after the copy,
            // accepted on the next launch). Without the retry a new install shows no Finder
            // entry until Drive is started a second time.
            for (attempt in 1..ATTEMPTS) {
                val registered = runCatching { runHelper(helper, "add") }
                    .onFailure { println("[FinderIntegration] registration failed: $it") }
                    .getOrDefault(false)
                if (registered || attempt == ATTEMPTS) break
                Thread.sleep(RETRY_DELAY_MILLIS * attempt)
            }
        }
    }

    /**
     * Tears every Tenshu domain down, and with it the system's own record of the items it showed.
     *
     * ⚠⚠ That record lives in the File Provider daemon, not under the app's data directory, so
     * deleting the data directory alone leaves Finder listing the files of a realm this device
     * no longer belongs to. A local reset runs this first (`--remove-finder-domain`,
     * scripts/reset-local-device-state.sh); the next ordinary launch registers a fresh domain.
     */
    fun removeDomains(): Boolean {
        val helper = bundledHelper() ?: return false
        return runCatching { runHelper(helper, "remove") }
            .onFailure { println("[FinderIntegration] removal failed: $it") }
            .getOrDefault(false)
    }

    /** Runs `tenshu-domain <command>`; true when it exited successfully. */
    private fun runHelper(helper: File, command: String): Boolean {
        val process = ProcessBuilder(helper.absolutePath, command)
            .redirectErrorStream(true)
            .start()
        // Wait first, read after: reading to EOF blocks for as long as the helper holds
        // stdout open, which would make the timeout unreachable for a hung helper.
        // The helper prints a line or two, far below the pipe buffer, so it never blocks on us.
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText().trim()
        println("[FinderIntegration] ${if (finished) output else "$command timed out. $output"}")
        return finished && process.exitValue() == 0
    }

    private const val ATTEMPTS = 4
    private const val RETRY_DELAY_MILLIS = 5_000L

    /**
     * `<app>/Contents/MacOS/tenshu-domain`, beside the running launcher.
     *
     * ⚠ It registers only because THIS process spawns it: the File Provider daemon attributes
     * the call to the responsible process, and refuses the same binary run from a shell.
     */
    private fun bundledHelper(): File? {
        val launcher = ProcessHandle.current().info().command().orElse(null) ?: return null
        return File(File(launcher).parentFile, "tenshu-domain").takeIf { it.canExecute() }
    }
}
