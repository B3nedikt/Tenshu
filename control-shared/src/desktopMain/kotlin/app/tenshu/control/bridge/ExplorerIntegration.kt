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

    /** Turns it on (register, connect, fill the folder) or off (empty the folder of placeholders, unregister). */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        settings.setExplorerIntegration(enabled)
        if (!supported) return
        if (enabled) {
            if (running == null) start()
        } else {
            val root = running ?: koin?.let { ExplorerSyncRoot(it, ExplorerSyncRoot.defaultRoot(), log) }
            running = null
            root?.remove()
        }
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
