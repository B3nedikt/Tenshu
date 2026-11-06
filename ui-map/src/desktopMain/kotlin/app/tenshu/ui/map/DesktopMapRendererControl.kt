package app.tenshu.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.prefs.Preferences

/**
 * The desktop twin of iOS's `MapRendererControl` — the user's election of Apple Maps as the
 * renderer, persisted, **off by default**, revocable, and re-read at every hero composition so
 * revoking takes effect on the next screen rather than the next launch.
 *
 * ⚠⚠ **Per app, not per device** (`ios-apple-maps-option.md` **D9**). `Preferences.userRoot()` is
 * scoped to the node below, which is per *application*, exactly as `UserDefaults.standard` is per
 * iOS app: electing in Timeline leaves Maps and Fit on MapLibre. The consent copy says "in this
 * app" for that reason — a grant whose stated scope exceeds its real one is defective consent, not
 * a cosmetic slip.
 *
 * ⚠ **Device-local, never synced** (**D3**): a synced setting would be remotely flippable, and an
 * egress consent must not be actuatable from another device. The cost — it is invisible in
 * Control's device list and does not survive a reinstall — is accepted there.
 */
object DesktopMapRendererControl {

    /**
     * ⚠ The node is keyed by the running app's own id, so two Tenshu desktop apps on one machine
     * hold two elections. That is D9's per-app scope, made explicit rather than inherited from
     * whatever `Preferences` happens to do.
     */
    private val node: Preferences by lazy {
        Preferences.userRoot().node("app/tenshu/${appId()}/basemap")
    }

    private const val KEY = "renderer"
    private const val MAPKIT = "mapKit"

    /**
     * Compose-observable so a toggle updates every visible hero at once. Seeded from the store at
     * first touch; writes go through [select], which persists before it publishes.
     */
    var usesAppleMaps: Boolean by mutableStateOf(node.get(KEY, null) == MAPKIT)
        private set

    /** Record the user's choice. Persists first, so a crash cannot leave the UI ahead of the store. */
    fun select(appleMaps: Boolean) {
        if (appleMaps) node.put(KEY, MAPKIT) else node.remove(KEY)
        node.flush()
        usesAppleMaps = appleMaps
    }

    /**
     * The app this JVM is: the launcher's own name for a packaged bundle, else the main class's
     * package tail for a dev run. ⚠ Not a constant — `:ui-map` is compiled into every desktop shell,
     * so a shared key would silently make one election serve them all, which is the "per device"
     * claim D9 rejects.
     */
    private fun appId(): String {
        val launcher = ProcessHandle.current().info().command().orElse(null)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && !it.startsWith("java") }
        return (launcher ?: System.getProperty("tenshu.appId") ?: "desktop")
            .replace(Regex("[^A-Za-z0-9_-]"), "")
            .ifBlank { "desktop" }
    }
}
