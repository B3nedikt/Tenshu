package app.tenshu.timeline.di

import app.tenshu.timeline.di.timelineSchemaConfigs
import org.tenshu.core.common.assertClientGraphRegisters
import org.tenshu.core.file.FileService
import org.tenshu.core.place.PlaceService
import org.tenshu.core.health.HealthService
import org.tenshu.core.mapregion.MapRegionService
import org.tenshu.storage.StorageService
import org.tenshu.sync.SyncService
import org.tenshu.core.timeline.TimelineService
import kotlin.test.Test

/**
 * Every service Timeline reaches is registered in its client graph.
 *
 * ⚠⚠ The failure this guards is **silent**: `getService` errors on an unregistered schema and the
 * app's repositories catch, so a missing registration renders as absent data rather than as a
 * crash. The announce gate cannot see it either — it compares only services the client declares.
 * `assertClientGraphRegisters` carries the full reasoning and Photos' §18.4 is the worked example.
 *
 * ⚠ [reached] is grepped, not derived — the compiler cannot connect a Gradle dependency to a
 * runtime list two modules away. **When a repository reaches a new service, add it here and to
 * `timelineSchemaConfigs`.**
 */
class TimelineClientGraphTest {

    private val reached = mapOf(
        "moments, movements and captured traces" to TimelineService.ID,
        "the photos shown on a moment, and Takeout import" to FileService.ID,
        "place naming and roles" to PlaceService.ID,
        "the basemap a route is drawn on" to MapRegionService.ID,
        "health samples joined onto a movement" to HealthService.ID,
        "this device's identity and the schema fence" to SyncService.ID,
        "the realm storage banner" to StorageService.ID,
    )

    @Test
    fun everyServiceTheAppReachesIsRegistered() =
        assertClientGraphRegisters("Timeline", timelineSchemaConfigs, reached)
}
