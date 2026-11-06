package app.tenshu.home.di

import app.tenshu.home.di.homeSchemaConfigs
import org.tenshu.core.common.assertClientGraphRegisters
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.hub.management.HubManagementService
import org.tenshu.routine.RoutineService
import org.tenshu.storage.StorageService
import org.tenshu.sync.SyncService
import kotlin.test.Test

/**
 * Every service Home reaches is registered in its client graph.
 *
 * ⚠⚠ The failure this guards is **silent**: `getService` errors on an unregistered schema and the
 * app's repositories catch, so a missing registration renders as absent data rather than as a
 * crash. The announce gate cannot see it either — it compares only services the client declares.
 * `assertClientGraphRegisters` carries the full reasoning and Photos' §18.4 is the worked example.
 *
 * ⚠ [reached] is grepped, not derived — the compiler cannot connect a Gradle dependency to a
 * runtime list two modules away. **When a repository reaches a new service, add it here and to
 * `homeSchemaConfigs`.**
 */
class HomeClientGraphTest {

    private val reached = mapOf(
        "the household's devices and their state" to HomeDeviceService.ID,
        "the hub's status tile" to HubManagementService.ID,
        "automations and the routines engine" to RoutineService.ID,
        "the realm storage banner" to StorageService.ID,
        "the Control-not-set-up wall (ADR 0025 §Decision 8)" to SyncService.ID,
    )

    @Test
    fun everyServiceTheAppReachesIsRegistered() =
        assertClientGraphRegisters("Home", homeSchemaConfigs, reached)
}
