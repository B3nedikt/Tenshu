package app.tenshu.home.data

import app.tenshu.home.model.Device
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The storage tile's real-source mapping (hub proposal §13 H4 → Home M1 tile):
 * `HubStatusInfo` → `DeviceState.Storage` incl. the staleness derivation, and
 * the fallback contract — no row / no usable disk figures → the mock devices
 * pass through untouched (never render fake numbers as real, and never render
 * real-looking zeros either).
 */
class HubAwareHomeDevicesRepositoryTest {

    private class FakeHubStatusSource : HubStatusSource {
        val flow = MutableStateFlow<List<HubStatusInfo>>(emptyList())
        override fun statuses(): Flow<List<HubStatusInfo>> = flow
    }

    private fun status(
        freshAsOfEpochMs: Long,
        diskFreeBytes: Long = 600_000_000_000,
        diskTotalBytes: Long = 1_000_000_000_000,
        name: String = "Backup Hub",
        deviceId: String = "hub-device-1",
    ) = HubStatusInfo(
        deviceId = deviceId,
        name = name,
        diskFreeBytes = diskFreeBytes,
        diskTotalBytes = diskTotalBytes,
        replicaFileCount = 42,
        replicaByteCount = 123_456_789,
        lastBackupPassAtEpochMs = 77,
        freshAsOfEpochMs = freshAsOfEpochMs,
    )

    private fun TestScope.repository(source: HubStatusSource) = HubAwareHomeDevicesRepository(
        delegate = MockHomeDevicesRepository(),
        source = source,
        nowMs = { testScheduler.currentTime },
        staleAfterMs = 1_000,
        recheckIntervalMs = 100,
    )

    private fun List<Device>.storageState(): DeviceState.Storage? =
        firstOrNull { it.kind == DeviceKind.Storage }?.state as? DeviceState.Storage

    /**
     * ⚠⚠ **No status ⇒ no Hub or Storage tile at all**, which reverses what this asserted before
     * ("devices are the mock exactly"). The tiles used to exist because a seeded row existed and
     * live figures were painted onto it; they are synthesised from the status now, so with no hub
     * reporting there is nothing to describe. A Hub tile rendered from nothing is the defect the
     * whole change is about.
     */
    @Test
    fun noStatusRow_meansNoHubTiles() = runTest {
        val repository = repository(FakeHubStatusSource())
        val devices = repository.devices().first()
        assertTrue(
            devices.none { it.kind == DeviceKind.Hub || it.kind == DeviceKind.Storage },
            "a hub tile with no reporting hub is exactly the fabrication this removed",
        )
        assertNull(devices.storageState()?.live, "no status can never be marked live")
    }

    @Test
    fun statusWithDiskFigures_mapsOntoTheStorageTileAndHubRow() = runTest {
        val source = FakeHubStatusSource()
        source.flow.value = listOf(status(freshAsOfEpochMs = 0))
        val repository = repository(source)

        val devices = repository.devices().first { it.storageState()?.live != null }
        val storage = assertNotNull(devices.storageState())
        assertEquals(1_000_000_000_000, storage.totalBytes)
        assertEquals(400_000_000_000, storage.usedBytes)
        assertEquals(600_000_000_000, storage.freeBytes)

        val live = assertNotNull(storage.live)
        assertEquals("Backup Hub", live.hubName)
        assertEquals(42, live.replicaFileCount)
        assertEquals(123_456_789, live.replicaByteCount)
        assertEquals(77, live.lastBackupPassAtEpochMs)
        assertEquals(0, live.freshAsOfEpochMs)
        assertFalse(live.stale, "a just-published snapshot must not be stale")

        val hub = devices.first { it.kind == DeviceKind.Hub }
        assertEquals(DeviceState.Connectivity(online = true), hub.state)
    }

    @Test
    fun freshestStatusWins_whenSeveralHubsPublished() = runTest {
        val source = FakeHubStatusSource()
        source.flow.value = listOf(
            status(freshAsOfEpochMs = 0, name = "Old Hub", deviceId = "hub-a", diskTotalBytes = 500),
            status(freshAsOfEpochMs = 50, name = "Fresh Hub", deviceId = "hub-b"),
        )
        val repository = repository(source)

        val devices = repository.devices().first { it.storageState()?.live != null }
        assertEquals("Fresh Hub", assertNotNull(devices.storageState()?.live).hubName)
    }

    @Test
    fun statusWithoutDiskFigures_neverOverlays_theNeverFakeNumbersContract() = runTest {
        val source = FakeHubStatusSource()
        source.flow.value = listOf(status(freshAsOfEpochMs = 0, diskFreeBytes = 0, diskTotalBytes = 0))
        val repository = repository(source)

        val emissions = mutableListOf<List<Device>>()
        val job = launch { repository.devices().collect { emissions += it } }
        advanceTimeBy(10_000)
        runCurrent()
        job.cancel()

        // ⚠⚠ The contract is unchanged and only its shape moved: a hub reporting **without disk
        // figures** must never produce a storage tile, because the only numbers available would be
        // invented. It used to be "leaves the mock untouched" — now that the tiles are synthesised
        // from the status rather than painted onto seeded rows, the same rule reads as "no tile".
        assertTrue(emissions.isNotEmpty())
        emissions.forEach { devices ->
            assertTrue(
                devices.none { it.kind == DeviceKind.Hub || it.kind == DeviceKind.Storage },
                "a figure-less status must produce no tile at all, never one with invented numbers",
            )
        }
    }

    @Test
    fun hubGoingSilent_flipsTheTileToStale_andTheHubRowOffline() = runTest {
        val source = FakeHubStatusSource()
        source.flow.value = listOf(status(freshAsOfEpochMs = 0))
        val repository = repository(source)

        // Fresh first — then no further rows arrive and the ticker re-derives.
        repository.devices().first { it.storageState()?.live?.stale == false }
        val staleDevices = repository.devices().first { it.storageState()?.live?.stale == true }

        assertTrue(
            testScheduler.currentTime > 1_000,
            "staleness must not flip before the threshold (flipped at ${testScheduler.currentTime}ms)",
        )
        val storage = assertNotNull(staleDevices.storageState())
        assertEquals(1_000_000_000_000, storage.totalBytes, "stale figures stay visible, marked stale")
        val hub = staleDevices.first { it.kind == DeviceKind.Hub }
        assertEquals(DeviceState.Connectivity(online = false), hub.state)
    }

}
