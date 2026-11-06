package app.tenshu.home.data

import app.tenshu.home.model.Device
import app.tenshu.home.model.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The seeded household, in memory (docs/proposals/home-and-mia-apps.md §6).
 *
 * ⚠⚠ **A test and preview fixture only, as of 2026-08-26 — never wire it into a production
 * graph.** It was the base delegate of the real devices chain, which meant an app that could not
 * reach Control still rendered "Living room · Connected" and "Sofa lamp · On · 70%": device states
 * indistinguishable, on screen, from real ones. A client-side fallback cannot be told apart from
 * the truth by the screen above it. Demo content, if it returns, belongs **behind the proxy** as
 * seeded rows a real read returns.
 *
 * ⚠ Read-only now: `addDevice`/`removeDevice`/`moveDevice` left `HomeDevicesRepository` with the
 * affordances they backed, since nothing persisted them.
 */
class MockHomeDevicesRepository(
    seedDevices: List<Device> = HomeMockData.devices(),
    seedRooms: List<Room> = HomeMockData.rooms(),
) : HomeDevicesRepository {

    private val roomsFlow = MutableStateFlow(seedRooms)
    private val devicesFlow = MutableStateFlow(seedDevices.sortedBy { it.sortIndex }.reindexed())

    override fun rooms(): Flow<List<Room>> = roomsFlow

    override fun devices(): Flow<List<Device>> = devicesFlow

    private fun List<Device>.reindexed(): List<Device> =
        mapIndexed { index, device -> device.copy(sortIndex = index) }
}
