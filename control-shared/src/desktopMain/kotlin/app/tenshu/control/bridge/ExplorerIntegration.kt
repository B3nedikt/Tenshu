package app.tenshu.control.bridge

import app.tenshu.control.lifecycle.DesktopControlSettings
import app.tenshu.windows.WindowsCloudFiles
import app.tenshu.windows.WindowsSyncRootManager
import org.koin.core.Koin

/**
 * The one [ExplorerSyncRoot] of this Control, behind the Settings switch (desktop-windows-port.md §7.8).
 * `Main.kt` [install]s it once the proxy is up; the Settings screen flips it with [setEnabled].
 *
 * ⚠ Every call does file and COM work: call from a background thread.
 */
object ExplorerIntegration {
    private var koin: Koin? = null
    private var log: (String) -> Unit = ::println
    private var running: ExplorerSyncRoot? = null
    private val settings = DesktopControlSettings()

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
        if (supported && isEnabled()) start()
    }

    /**
     * Turns it on (register, connect, fill the folder) or off (empty the folder of placeholders, unregister).
     * Answers whether it is now on. ⚠ A start Windows refused leaves the switch off, so Settings never shows a
     * folder that is not there. Before [install] (Control still starting) it can be turned off, not on.
     */
    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        if (!supported) return false
        if (enabled) {
            if (running == null) start()
            settings.setExplorerIntegration(running != null)
        } else {
            settings.setExplorerIntegration(false)
            val root = running
            running = null
            if (root != null) root.remove() else ExplorerSyncRoot.removeAll(ExplorerSyncRoot.defaultRoot(), log)
        }
        return running != null
    }

    /** Control quitting: stop serving, keep the registration and the folder. */
    @Synchronized
    fun shutdown() {
        running?.stop()
        running = null
    }

    private fun start() {
        val koin = koin ?: return
        val root = ExplorerSyncRoot(koin, ExplorerSyncRoot.defaultRoot(), log)
        running = if (runCatching { root.start() }.onFailure { log("explorer: start failed: $it") }.getOrDefault(false)) {
            root
        } else {
            root.stop()
            null
        }
    }
}
