package app.tenshu.home.data

import app.tenshu.home.model.Device
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.Room
import kotlinx.coroutines.flow.Flow

/**
 * The Home app's device surface (docs/proposals/home-and-mia-apps.md §6). M1 binds
 * [MockHomeDevicesRepository]; the real `:home-device-service`-backed implementation
 * replaces it behind this interface with no VM/UI change — the same seam Timeline and Fit
 * proved.
 */
interface HomeDevicesRepository {

    /** The household's rooms. */
    fun rooms(): Flow<List<Room>>

    /** All devices, sorted by [Device.sortIndex]. */
    fun devices(): Flow<List<Device>>

    /** Adds a device with a plausible default state for its kind; it lands at the end of the list. */
    // ⚠⚠ **No `addDevice` / `removeDevice` / `moveDevice`, as of 2026-08-26.** They existed only to
    // mutate `MockHomeDevicesRepository`: an in-memory household that survived nothing and was
    // indistinguishable, on screen, from real devices. Adding a *real* device is the HD-2
    // permit-join flow through `DevicePairingSource`; there is no proxy-backed write path for the
    // rest, so offering one was an affordance that could not do what it appeared to do.
}
