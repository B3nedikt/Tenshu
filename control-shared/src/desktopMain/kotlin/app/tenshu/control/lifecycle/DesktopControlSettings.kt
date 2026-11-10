package app.tenshu.control.lifecycle

import java.io.File
import java.util.Properties
import org.tenshu.core.common.storage.DesktopAppPaths

/**
 * Desktop-local Control preferences, persisted to `control.settings.properties`
 * in the [DesktopAppPaths] root (the App Group container on a signed mac build,
 * `~/.tenshu` otherwise). These are *device-local UI* choices — not synced
 * realm data — so they live here rather than in the preference-service
 * (docs/proposals/desktop-macos-integration.md §4 task 1, slice 1D).
 *
 * Storage errors are swallowed: a missing/corrupt file falls back to defaults.
 */
class DesktopControlSettings(
    private val file: File = defaultFile(),
) {
    /**
     * Whether closing the Control window hides it to the menu-bar tray (true,
     * default) or quits the daemon (false). Read by `Main.kt` on each window
     * close. Defaults to true so the tray-first behaviour is the out-of-box one.
     */
    fun closeToTray(): Boolean = read().getProperty(KEY_CLOSE_TO_TRAY)?.toBooleanStrictOrNull() ?: true

    fun setCloseToTray(value: Boolean) = write(KEY_CLOSE_TO_TRAY, value.toString())

    /**
     * Whether the one-time "Start at login defaults ON" first-run step has run.
     * Latches true after [Main.kt] installs the daemon LaunchAgent on first
     * launch, so a user who later turns Start-at-login *off* in Settings doesn't
     * have it silently re-enabled on the next launch. Defaults to false.
     */
    fun startAtLoginDefaultApplied(): Boolean =
        read().getProperty(KEY_START_AT_LOGIN_DEFAULT_APPLIED)?.toBooleanStrictOrNull() ?: false

    fun setStartAtLoginDefaultApplied(value: Boolean) =
        write(KEY_START_AT_LOGIN_DEFAULT_APPLIED, value.toString())

    /**
     * Whether the user paused background sync from the tray (bgproc §5.3
     * *Paused*). Device-local so a paused daemon stays paused across restarts
     * without pausing the user's other devices. Defaults to false (syncing).
     * Feeds [app.tenshu.control.data.SyncPauseState] at proxy init.
     */
    fun syncPaused(): Boolean = read().getProperty(KEY_SYNC_PAUSED)?.toBooleanStrictOrNull() ?: false

    fun setSyncPaused(value: Boolean) = write(KEY_SYNC_PAUSED, value.toString())

    /**
     * Whether Drive shows in File Explorer (Windows, desktop-windows-port.md §7.8). Off by default while the
     * folder is read-only (X1); device-local, like every switch here.
     */
    fun explorerIntegration(): Boolean = read().getProperty(KEY_EXPLORER_INTEGRATION)?.toBooleanStrictOrNull() ?: false

    fun setExplorerIntegration(value: Boolean) = write(KEY_EXPLORER_INTEGRATION, value.toString())

    private fun read(): Properties {
        val props = Properties()
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
        return props
    }

    private fun write(key: String, value: String) {
        runCatching {
            val props = read().apply { setProperty(key, value) }
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "Tenshu Control desktop settings") }
        }
    }

    companion object {
        const val KEY_CLOSE_TO_TRAY = "closeToTray"
        const val KEY_START_AT_LOGIN_DEFAULT_APPLIED = "startAtLoginDefaultApplied"
        const val KEY_SYNC_PAUSED = "syncPaused"
        const val KEY_EXPLORER_INTEGRATION = "explorerIntegration"

        private fun defaultFile(): File =
            DesktopAppPaths.sharedFile("control.settings.properties")
    }
}
