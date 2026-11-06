package app.tenshu.home.data

import app.tenshu.home.model.Action
import app.tenshu.home.model.Automation
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.DeviceState
import app.tenshu.home.model.Trigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.home.device.HomeDevice
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.common.program.Action as ProgramAction
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.Trigger as ProgramTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The two compositions a shell without Compose DI binds (the iOS route-1 slice, 2026-10-02): the
 * not-connected default and the read-only synced Home.
 *
 * ⚠ Every "is empty" / "refuses" assertion below is paired with a case over the SAME fixture that
 * shows data, so an unwired fixture cannot pass for an honest empty one.
 */
class HomeRepositoriesTest {

    private class FakeHub(rows: List<HubStatusInfo> = emptyList()) : HubStatusSource {
        val rows = MutableStateFlow(rows)
        override fun statuses(): Flow<List<HubStatusInfo>> = rows
    }

    private class FakeDevices(rows: List<SyncedDeviceInfo> = emptyList()) : DeviceSource {
        val rows = MutableStateFlow(rows)
        override fun devices(): Flow<List<SyncedDeviceInfo>> = rows
    }

    private class FakeRoutines(
        routines: List<SyncedRoutineInfo> = emptyList(),
        runs: List<SyncedRoutineRunInfo> = emptyList(),
    ) : RoutineSource {
        val routines = MutableStateFlow(routines)
        val runs = MutableStateFlow(runs)
        override fun routines(): Flow<List<SyncedRoutineInfo>> = routines
        override fun runs(): Flow<List<SyncedRoutineRunInfo>> = runs
    }

    private val now = 1_790_000_000_000L

    private fun hub() = HubStatusInfo(
        deviceId = "hub-1",
        name = "Cellar hub",
        diskFreeBytes = 300,
        diskTotalBytes = 1_000,
        replicaFileCount = 12,
        replicaByteCount = 640,
        lastBackupPassAtEpochMs = now,
        freshAsOfEpochMs = now,
    )

    private fun lamp() = SyncedDeviceInfo(
        deviceId = "lamp-1",
        kind = HomeDevice.KIND_LIGHT,
        capabilities = listOf(HomeDevice.CAP_POWER, HomeDevice.CAP_LEVEL),
        name = "Desk lamp",
        room = "Study",
        stateFresh = true,
        online = true,
        powerOn = true,
        levelPercent = 40,
    )

    private fun routine(): SyncedRoutineInfo {
        val encoded = JsonRoutineProgramCodec().encode(
            RoutineProgram(
                trigger = ProgramTrigger.TimeOfDay(22 * 60),
                actions = listOf(ProgramAction.SetPower("lamp-1", true)),
            ),
        )
        return SyncedRoutineInfo(
            routineId = "r1",
            name = "Evening",
            enabled = true,
            state = Routine.STATE_ACTIVE,
            suspendedReason = "",
            programJson = encoded.programJson,
            programVersion = encoded.programVersion,
        )
    }

    private fun run() = SyncedRoutineRunInfo(
        firingId = "f1",
        routineId = "r1",
        routineName = "Evening",
        state = RoutineRun.STATE_RAN,
        claimedAtEpochMs = now,
    )

    private fun readOnly(
        hub: HubStatusSource = FakeHub(),
        devices: DeviceSource = FakeDevices(),
        routines: RoutineSource = FakeRoutines(),
    ) = readOnlySyncedHomeRepositories(
        hubStatus = hub,
        devices = devices,
        routines = routines,
    )

    private fun automation() = Automation(
        id = "",
        name = "New",
        enabled = true,
        trigger = Trigger.Schedule(600),
        conditions = emptyList(),
        actions = listOf(Action.DeviceCommand("lamp-1", true)),
        lastRunAt = null,
    )

    // ---------------------------------------------------------------- not connected

    /** The defect this slice removes: a household nobody owns, on a phone nothing set up. */
    @Test
    fun notConnected_isEmptyEverywhere_andNeverTheSeededHousehold() = runTest {
        val home = notConnectedHomeRepositories()

        assertTrue(home.devices.devices().first().isEmpty())
        assertTrue(home.devices.rooms().first().isEmpty())
        assertTrue(home.automations.automations().first().isEmpty())
        assertTrue(home.activity.events().first().isEmpty())

        // ⚠ The pairing case: the mocks DO carry rows, so "empty" above is a property of the
        // not-connected surface rather than of repositories that happen to have nothing.
        assertTrue(MockHomeDevicesRepository().devices().first().isNotEmpty())
        assertTrue(MockAutomationsRepository().automations().first().isNotEmpty())
        assertTrue(MockHomeActivityRepository().events().first().isNotEmpty())
    }

    @Test
    fun notConnected_refusesEveryWrite_andSaysWhy() = runTest {
        val automations = notConnectedHomeRepositories().automations

        val refusal = assertFailsWith<HomeReadOnlyException> { automations.addAutomation(automation()) }
        assertEquals(HomeReadOnlyException.NOT_SET_UP, refusal.message)
        assertFailsWith<HomeReadOnlyException> { automations.updateAutomation(automation()) }
        assertFailsWith<HomeReadOnlyException> { automations.removeAutomation("r1") }
        assertFailsWith<HomeReadOnlyException> { automations.setEnabled("r1", false) }
    }

    // ---------------------------------------------------------------- read-only synced

    /** An empty realm is an honest empty list — no tiles, no demo rows. */
    @Test
    fun readOnly_overAnEmptyRealm_isEmpty() = runTest {
        val home = readOnly()

        assertTrue(home.devices.devices().first().isEmpty())
        assertTrue(home.automations.automations().first().isEmpty())
        assertTrue(home.activity.events().first().isEmpty())
    }

    @Test
    fun readOnly_showsTheRealmsDevicesWithTheirLastSyncedState() = runTest {
        val devices = readOnly(devices = FakeDevices(listOf(lamp()))).devices.devices()
            .first { it.isNotEmpty() }

        val lamp = devices.single()
        assertEquals("Desk lamp", lamp.name)
        assertEquals(DeviceKind.Light, lamp.kind)
        val state = assertIs<DeviceState.Light>(lamp.state)
        assertTrue(state.on)
        assertEquals(40, state.brightnessPercent)
    }

    /** The hub layer is the inner one, so its tiles survive beside real devices. */
    @Test
    fun readOnly_keepsTheHubAndStorageTilesBesideBridgedDevices() = runTest {
        val devices = readOnly(hub = FakeHub(listOf(hub())), devices = FakeDevices(listOf(lamp())))
            .devices.devices()
            .first { rows -> rows.any { it.kind == DeviceKind.Storage } && rows.any { it.kind == DeviceKind.Light } }

        assertEquals(listOf(DeviceKind.Hub, DeviceKind.Storage, DeviceKind.Light), devices.map { it.kind })
        val storage = assertIs<DeviceState.Storage>(devices[1].state)
        assertEquals(700, storage.usedBytes)
        assertEquals(1_000, storage.totalBytes)
    }

    @Test
    fun readOnly_showsAutomationsAndTheirRunHistory() = runTest {
        val home = readOnly(routines = FakeRoutines(listOf(routine()), listOf(run())))

        assertEquals("Evening", home.automations.automations().first().single().name)
        val event = home.activity.events().first().single()
        assertEquals("Evening", event.title)
        assertEquals("Ran", event.detail)
    }

    /**
     * ⚠⚠ Read-only is enforced below the screen: a toggle wired by mistake throws rather than
     * writing. Paired with the read above — the same repository that shows "Evening" refuses to
     * change it.
     */
    @Test
    fun readOnly_refusesEveryAutomationWrite() = runTest {
        val automations = readOnly(routines = FakeRoutines(listOf(routine()))).automations
        assertEquals(1, automations.automations().first().size)

        val refusal = assertFailsWith<HomeReadOnlyException> { automations.setEnabled("r1", false) }
        assertEquals(HomeReadOnlyException.READ_ONLY, refusal.message)
        assertFailsWith<HomeReadOnlyException> { automations.removeAutomation("r1") }
        assertFailsWith<HomeReadOnlyException> { automations.addAutomation(automation()) }
        assertFailsWith<HomeReadOnlyException> { automations.updateAutomation(automation().copy(id = "r1")) }
        assertFailsWith<HomeReadOnlyException> { ReadOnlyRoutineControlSource.runNow("r1") }
    }
}
