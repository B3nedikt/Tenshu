package app.tenshu.home.data

import app.tenshu.home.model.ActivityEvent
import app.tenshu.home.model.Automation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The three repositories Home's screens read, as one value — so a shell that swaps them swaps all
 * three together and can never show real devices beside a fabricated automation list.
 */
class HomeRepositories(
    val devices: HomeDevicesRepository,
    val automations: AutomationsRepository,
    val activity: HomeActivityRepository,
)

/**
 * The devices chain over the synced rows: bridged devices on top, the Hub and Storage tiles
 * synthesised from `HubStatus` underneath, and **nothing** at the bottom.
 *
 * ⚠ One function rather than a nested constructor call per shell: `homeClientModule` (Android,
 * desktop) and the iOS route-1 runtime must compose the same chain, and the order is load-bearing —
 * [SyncedDeviceAwareHomeDevicesRepository] keeps only the delegate's infrastructure tiles, so the
 * hub layer has to be the inner one.
 */
fun syncedHomeDevicesRepository(
    hubStatus: HubStatusSource,
    devices: DeviceSource,
): HomeDevicesRepository = SyncedDeviceAwareHomeDevicesRepository(
    delegate = HubAwareHomeDevicesRepository(
        delegate = EmptyHomeDevicesRepository(),
        source = hubStatus,
    ),
    source = devices,
)

/**
 * Home with nothing behind it: no devices, no automations, no activity, and every write refused.
 *
 * ⚠⚠ This is the default of a shell that has no runtime yet, and it is **empty on purpose**. What
 * used to stand here on iOS was a seeded household — "Sofa lamp · On · 70%", "588 GB free of 1 TB"
 * — on a phone Control had never set up. A lamp shown as on by an app that can reach nothing is
 * worse than a blank screen: it is a statement about the user's home, and it is false.
 */
fun notConnectedHomeRepositories(): HomeRepositories = HomeRepositories(
    devices = EmptyHomeDevicesRepository(),
    automations = NotConnectedAutomationsRepository(),
    activity = NotConnectedHomeActivityRepository(),
)

/**
 * The **read-only** Home over the synced rows — the first iOS slice (2026-10-02): devices with
 * their last-synced state, the hub/storage tile, the automation list, and run history.
 *
 * ⚠⚠ Read-only is **enforced here, not left to the screen**. The automations repository is the
 * real [SyncedAutomationsRepository] over a [ReadOnlyRoutineControlSource], so a toggle a later
 * screen wires by mistake throws instead of writing; and no [DeviceControlSource] or
 * [DevicePairingSource] is built at all, so `DevicesViewModel` constructed over [devices] alone
 * has no controls map and no pairing bridge.
 *
 * ⚠ Reads stay honest: an empty realm is an empty list. Nothing here substitutes demo content.
 */
fun readOnlySyncedHomeRepositories(
    hubStatus: HubStatusSource,
    devices: DeviceSource,
    routines: RoutineSource,
): HomeRepositories = HomeRepositories(
    devices = syncedHomeDevicesRepository(hubStatus, devices),
    automations = SyncedAutomationsRepository(
        source = routines,
        control = ReadOnlyRoutineControlSource,
        // ⚠ Authoring inputs, and nothing is authored here: both refuse rather than supply a
        // plausible value a write could carry.
        timeZoneId = { throw HomeReadOnlyException() },
        newId = { throw HomeReadOnlyException() },
    ),
    activity = SyncedHomeActivityRepository(routines),
)

/** Thrown by every write on a read-only or not-connected Home surface. */
class HomeReadOnlyException(
    message: String = READ_ONLY,
) : IllegalStateException(message) {
    companion object {
        const val READ_ONLY: String = "Home is read-only on this device."
        const val NOT_SET_UP: String = "Tenshu Control has not set this device up yet."
    }
}

/**
 * A [RoutineControlSource] that refuses everything, out loud.
 *
 * ⚠ A throw rather than a no-op: a save that silently does nothing tells the user their
 * automation exists when nothing will ever run it — the asymmetry [SyncedAutomationsRepository]
 * already draws for an unreachable proxy.
 */
object ReadOnlyRoutineControlSource : RoutineControlSource {
    override suspend fun save(
        routineId: String,
        name: String,
        timeZoneId: String,
        programJson: String,
        programVersion: Long,
        homeLatE5: Long,
        homeLonE5: Long,
    ): Unit = throw HomeReadOnlyException()

    override suspend fun delete(routineId: String): Unit = throw HomeReadOnlyException()

    override suspend fun setEnabled(routineId: String, enabled: Boolean): Unit =
        throw HomeReadOnlyException()

    override suspend fun runNow(routineId: String): Unit = throw HomeReadOnlyException()
}

/** No automations, and every write refused — see [notConnectedHomeRepositories]. */
class NotConnectedAutomationsRepository : AutomationsRepository {
    override fun automations(): Flow<List<Automation>> = flowOf(emptyList())

    override suspend fun addAutomation(automation: Automation): String = refuse()

    override suspend fun updateAutomation(automation: Automation): Unit = refuse()

    override suspend fun removeAutomation(automationId: String): Unit = refuse()

    override suspend fun setEnabled(automationId: String, enabled: Boolean): Unit = refuse()

    private fun refuse(): Nothing = throw HomeReadOnlyException(HomeReadOnlyException.NOT_SET_UP)
}

/** No activity — see [notConnectedHomeRepositories]. */
class NotConnectedHomeActivityRepository : HomeActivityRepository {
    override fun events(): Flow<List<ActivityEvent>> = flowOf(emptyList())
}
