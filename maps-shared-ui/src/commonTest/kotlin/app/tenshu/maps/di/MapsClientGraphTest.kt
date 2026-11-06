package app.tenshu.maps.di

import app.tenshu.maps.di.mapsSchemaConfigs
import org.tenshu.core.common.assertClientGraphRegisters
import org.tenshu.core.place.PlaceService
import org.tenshu.core.mapregion.MapRegionService
import org.tenshu.storage.StorageService
import org.tenshu.sync.SyncService
import kotlin.test.Test

/**
 * Every service Maps reaches is registered in its client graph.
 *
 * ⚠⚠ The failure this guards is **silent**: `getService` errors on an unregistered schema and the
 * app's repositories catch, so a missing registration renders as absent data rather than as a
 * crash. The announce gate cannot see it either — it compares only services the client declares.
 * `assertClientGraphRegisters` carries the full reasoning and Photos' §18.4 is the worked example.
 *
 * ⚠ [reached] is grepped, not derived — the compiler cannot connect a Gradle dependency to a
 * runtime list two modules away. **When a repository reaches a new service, add it here and to
 * `mapsSchemaConfigs`.**
 */
class MapsClientGraphTest {

    private val reached = mapOf(
        "offline region download and installation" to MapRegionService.ID,
        "the Places list and its roles" to PlaceService.ID,
        "the realm storage banner" to StorageService.ID,
        "the schema fence behind the update banner" to SyncService.ID,
    )

    @Test
    fun everyServiceTheAppReachesIsRegistered() =
        assertClientGraphRegisters("Maps", mapsSchemaConfigs, reached)
}
