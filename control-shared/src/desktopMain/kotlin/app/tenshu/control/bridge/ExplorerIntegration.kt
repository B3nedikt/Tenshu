package app.tenshu.control.bridge

import app.tenshu.control.lifecycle.DesktopControlSettings
import app.tenshu.windows.WindowsCloudFiles
import app.tenshu.windows.WindowsSyncRootManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.Koin
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The one [ExplorerSyncRoot] of this Control, behind the Settings switch (desktop-windows-port.md §7.8).
 * `Main.kt` [install]s it once the proxy is up; the Settings screen flips it with [setEnabled].
 *
 * ⚠ Every call does file and COM work: call from a background thread.
 */
object ExplorerIntegration {
    private var koin: Koin? = null
    private var log: (String) -> Unit = ::println
    @Volatile private var running: ExplorerSyncRoot? = null
    private val settings = DesktopControlSettings()
    private val retries = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "explorer-cleanup").apply { isDaemon = true }
    }
    private var cleanup: ScheduledFuture<*>? = null
    private var restart: ScheduledFuture<*>? = null

    /** The root whose start() is running: its first pass may report before [running] is set. */
    @Volatile private var starting: ExplorerSyncRoot? = null

    private val _status = MutableStateFlow(ExplorerStatus(ExplorerStatus.State.OFF))

    /** What the folder is doing, for the line under the Settings switch (X4). */
    val status: StateFlow<ExplorerStatus> = _status.asStateFlow()

    /** The state when nothing is serving: off, emptying the folder, or on and waiting to start. */
    private fun idleStatus() {
        _status.value = ExplorerStatus(
            when {
                isEnabled() -> ExplorerStatus.State.STARTING
                cleanup != null || settings.explorerCleanupPending() -> ExplorerStatus.State.CLEANING_UP
                else -> ExplorerStatus.State.OFF
            },
        )
    }

    /** Windows with a usable Cloud Files API; false elsewhere, where the switch is not shown. */
    val supported: Boolean by lazy {
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) &&
            WindowsCloudFiles.isAvailable() && WindowsSyncRootManager.isAvailable()
    }

    fun isEnabled(): Boolean = settings.explorerIntegration()

    @Synchronized
    fun install(koin: Koin, log: (String) -> Unit) {
        this.koin = koin
        this.log = log
        if (!supported) return
        if (isEnabled()) {
            start()
            // ⚠ Switched on, but Windows refused at this start (often just not ready yet at login): the user's choice
            // stands and the start is retried, rather than a switch that reads on while nothing is served.
            if (running == null) retryStart()
        }
        // Switched off while a downloaded copy was open: finish emptying the folder, now or as soon as it can be.
        else if (settings.explorerCleanupPending()) cleanUp()
        if (running == null) idleStatus()
    }

    /**
     * Turns it on (register, connect, fill the folder) or off (empty the folder of placeholders, unregister).
     * Answers whether it is now on. ⚠ A start Windows refused leaves the switch off, so Settings never shows a
     * folder that is not there. Before [install] (Control still starting) it can be turned off, not on.
     *
     * ⚠⚠ Off is not done until the folder is empty of our placeholders: one open in an app that shares no delete
     * access is a decrypted copy that must still go. Until then the cleanup is retried every minute, across
     * restarts ([DesktopControlSettings.explorerCleanupPending]), with the root left registered so the leftovers
     * are still recognisable as ours.
     */
    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        if (!supported) return false
        if (enabled) {
            stopCleanup()
            if (running == null) start()
            settings.setExplorerIntegration(running != null)
        } else {
            stopRestart()
            settings.setExplorerIntegration(false)
            val root = running
            running = null
            root?.stop()
            cleanUp()
        }
        if (running == null) idleStatus()
        return running != null
    }

    /** Control quitting: stop serving, keep the registration and the folder. */
    @Synchronized
    fun shutdown() {
        running?.stop()
        running = null
        stopCleanup()
        stopRestart()
    }

    private fun retryStart() {
        if (restart != null) return
        restart = retries.scheduleWithFixedDelay({
            synchronized(this) {
                if (!isEnabled() || running != null) {
                    stopRestart()
                } else {
                    runCatching { start() }.onFailure { log("explorer: start retry failed: $it") }
                    if (running != null) stopRestart()
                }
            }
        }, CLEANUP_RETRY_SECONDS, CLEANUP_RETRY_SECONDS, TimeUnit.SECONDS)
    }

    private fun stopRestart() {
        restart?.cancel(false)
        restart = null
    }

    @Synchronized
    private fun cleanUp() {
        if (ExplorerSyncRoot.removeAll(ExplorerSyncRoot.defaultRoot(), log)) {
            settings.setExplorerCleanupPending(false)
            stopCleanup()
            if (running == null) idleStatus()
            return
        }
        settings.setExplorerCleanupPending(true)
        if (cleanup == null) {
            cleanup = retries.scheduleWithFixedDelay(
                { runCatching { cleanUp() }.onFailure { log("explorer: cleanup retry failed: $it") } },
                CLEANUP_RETRY_SECONDS, CLEANUP_RETRY_SECONDS, TimeUnit.SECONDS,
            )
        }
    }

    private fun stopCleanup() {
        cleanup?.cancel(false)
        cleanup = null
    }

    private fun start() {
        val koin = koin ?: return
        var self: ExplorerSyncRoot? = null
        val root = ExplorerSyncRoot(
            koin, ExplorerSyncRoot.defaultRoot(), log,
            journalFile = org.tenshu.core.common.storage.DesktopAppPaths.sharedFile("explorer-versions.tsv"),
            // Only the one serving speaks: a pass of a root being stopped must not overwrite "off".
            onStatus = { status -> if (self != null && (running === self || starting === self)) _status.value = status },
        )
        self = root
        starting = root
        _status.value = ExplorerStatus(ExplorerStatus.State.STARTING)
        running = if (runCatching { root.start() }.onFailure { log("explorer: start failed: $it") }.getOrDefault(false)) {
            settings.setExplorerCleanupPending(false)
            root
        } else {
            root.stop()
            null
        }
        starting = null
    }

    private const val CLEANUP_RETRY_SECONDS = 60L
}
