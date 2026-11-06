package org.tenshu.sdk.ios

import app.tenshu.home.data.HomeReadOnlyException
import app.tenshu.home.data.MockHomeDevicesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.routine.client.routineService
import org.tenshu.routine.client.save
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.Trigger
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The route-1 in-process Home runtime assembles the FULL production proxy graph in this process
 * and serves Home's reads over a replica directory — the Home twin of [ContactsLocalRuntimeTest].
 *
 * It pins that the iOS proxy graph hosts `:home-device-service`, `:hub-management-service` and
 * `:routine-service`, so a route-1 Home needs no service wiring of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLocalRuntimeTest {

    private fun assemble() = HomeLocalRuntime.assemble(
        replicaDirectory = NSTemporaryDirectory() + "home-local-runtime-test-" + Random.nextLong().toString(16),
        secureStorage = InMemorySecureStorageDelegate(),
    )

    @Test
    fun theRuntimeReachesTheInProcessHomeDeviceService() = runBlocking {
        // ⚠ Emptiness alone proves nothing — an unwired service folds to the same empty list
        // (`emptyOnUnreachable`). The reachability probe is a `homeDevices` read that must come
        // back `Success`. ⚠⚠ Wait for it: `null` is "not yet known".
        val assembly = assemble()
        val reachable = withTimeout(30_000) { assembly.reachability.state.first { it == true } }
        assertEquals(true, reachable)
    }

    @Test
    fun aFreshReplicaHasNoDevicesNoAutomationsAndNoActivity() = runBlocking {
        val home = assemble().repositories
        assertTrue(home.devices.devices().first().isEmpty(), "no hub and no bridged devices: no tiles")
        assertTrue(home.automations.automations().first().isEmpty())
        assertTrue(home.activity.events().first().isEmpty())
    }

    /**
     * The read path end to end, and read-only enforced on the same repository.
     *
     * ⚠ The row is written through the **raw client**, which is not what the surface gets: the
     * test needs a real `Routine` on the replica to prove the read is wired, and the read-only
     * repository is — correctly — unable to make one.
     */
    @Test
    fun aRoutineOnTheReplicaIsListed_andTheReadOnlyRepositoryRefusesToChangeIt() = runBlocking {
        val assembly = assemble()
        val encoded = JsonRoutineProgramCodec().encode(
            RoutineProgram(
                trigger = Trigger.TimeOfDay(22 * 60),
                actions = listOf(Action.SetPower("lamp-1", true)),
            ),
        )
        assembly.tenshuClient.routineService.save(
            routineId = "evening",
            name = "Evening",
            timeZoneId = "Europe/Berlin",
            programJson = encoded.programJson,
            programVersion = encoded.programVersion,
        )

        val automations = assembly.repositories.automations
        // ⚠ Wait for the row rather than sampling the first frame (the subscribe-window race
        // `ContactsLocalRuntimeTest` records).
        val listed = withTimeout(30_000) { automations.automations().first { it.isNotEmpty() } }
        assertEquals(listOf("Evening"), listed.map { it.name })
        assertTrue(listed.single().enabled)

        assertFailsWith<HomeReadOnlyException> { automations.setEnabled("evening", false) }
        assertFailsWith<HomeReadOnlyException> { automations.removeAutomation("evening") }
        // And the refusals wrote nothing.
        val after = withTimeout(30_000) { automations.automations().first { it.isNotEmpty() } }
        assertTrue(after.single().enabled, "a refused toggle must not have landed")
    }

    @Test
    fun theSurfaceDefaultIsNotConnected_neverTheMockHousehold() = runBlocking {
        HomeSurface.installNotConnected()
        assertEquals(
            false,
            HomeSurface.controlReachable().first(),
            "not-connected is a KNOWN state — null would mean 'still deciding'",
        )

        // ⚠ `viewModelScope` is `Main.immediate` and `runBlocking` owns the main thread here, so
        // without a live Main the collection would never run and `loaded` would stay false.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val devices = HomeSurface.devicesViewModel()
            val state = withTimeout(30_000) { devices.uiState.first { it.loaded } }
            assertTrue(state.devices.isEmpty(), "an unprovisioned Home shows no devices: ${state.devices}")
            assertTrue(state.controls.isEmpty())

            val automations = HomeSurface.automationsViewModel()
            assertTrue(withTimeout(30_000) { automations.uiState.first { it.loaded } }.automations.isEmpty())

            val activity = HomeSurface.activityViewModel()
            assertTrue(withTimeout(30_000) { activity.uiState.first { it.loaded } }.events.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }

        // The pairing case: the mock household is NOT empty, so the assertions above would fail
        // if the surface defaulted to it.
        assertTrue(MockHomeDevicesRepository().devices().first().isNotEmpty())
    }

    @Test
    fun installingARuntimeSwapsTheSurface_andTeardownRestoresIt() = runBlocking {
        HomeSurface.installNotConnected()
        val assembly = assemble()
        HomeSurface.install(assembly.repositories, assembly.reachability.state)
        assertEquals(
            true,
            withTimeout(30_000) { HomeSurface.controlReachable().first { it == true } },
            "installing a runtime makes the surface reachable",
        )

        HomeSurface.installNotConnected()
        assertEquals(false, HomeSurface.controlReachable().first())
    }
}
