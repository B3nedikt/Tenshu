package org.tenshu.sdk.ios

import app.tenshu.home.data.HomeRepositories
import app.tenshu.home.data.notConnectedHomeRepositories
import app.tenshu.home.ui.screens.activity.ActivityViewModel
import app.tenshu.home.ui.screens.automations.AutomationsViewModel
import app.tenshu.home.ui.screens.devices.DevicesViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Wires Home's view-model layer (`:home-shared-logic`) to iOS so the SwiftUI `home-ios` app
 * resolves each screen's view model — the Home twin of [CalendarSurface] / [PhotosSurface].
 *
 * The default is the honest **not-connected** set ([notConnectedHomeRepositories]): no devices, no
 * automations, no activity. ⚠⚠ It used to be three mock repositories, so `home-ios` showed an
 * invented household — "Sofa lamp · On · 70%", "588 GB free of 1 TB" — whether or not Control had
 * ever set the phone up. Nothing on this surface can install a mock: a fake a shipping build can
 * reach is the defect, not a convenience.
 *
 * ⚠⚠ **Read-only (2026-10-02, the first route-1 slice).** [devicesViewModel] is built over the
 * repository alone — no `DeviceSource`, no pairing source, no control source — so its `controls`
 * map stays empty and `setDevicePower` / `startPairing` are inert. The automations view model's
 * `setEnabled` / `removeAutomation` reach a repository that throws. The later slice that adds
 * device control and automation editing changes [HomeLocalRuntime]'s composition, not this object.
 *
 * ⚠ The three repositories swap together, as one [HomeRepositories] — real devices beside a stale
 * automation list would be a half-state no screen could explain.
 */
object HomeSurface {

    private var repositories: HomeRepositories = notConnectedHomeRepositories()

    /** `false` on the not-connected surface; the runtime's one reachability signal otherwise. */
    private var reachable: Flow<Boolean?> = flowOf(false)

    /**
     * Install the repositories subsequently-built view models bind, plus the runtime's
     * reachability signal. Called by the tier that owns a live client — [HomeLocalRuntime].
     */
    fun install(homeRepositories: HomeRepositories, reachability: Flow<Boolean?>) {
        repositories = homeRepositories
        reachable = reachability
    }

    /** Restore the honest not-connected surface — the teardown target. */
    fun installNotConnected() {
        repositories = notConnectedHomeRepositories()
        reachable = flowOf(false)
    }

    /**
     * Whether the installed repositories can reach the in-process proxy. ⚠ `null` means *not yet
     * known*: the shell shows no banner while it waits.
     */
    fun controlReachable(): Flow<Boolean?> = reachable

    fun devicesViewModel(): DevicesViewModel = DevicesViewModel(repositories.devices)

    fun automationsViewModel(): AutomationsViewModel =
        AutomationsViewModel(repositories.automations, repositories.devices)

    fun activityViewModel(): ActivityViewModel = ActivityViewModel(repositories.activity)
}
