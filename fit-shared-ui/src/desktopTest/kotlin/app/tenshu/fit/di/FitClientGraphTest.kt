package app.tenshu.fit.di

import app.tenshu.fit.di.fitSchemaConfigs
import org.tenshu.core.common.assertClientGraphRegisters
import org.tenshu.core.health.HealthService
import org.tenshu.identity.IdentityService
import org.tenshu.preference.PreferenceService
import org.tenshu.storage.StorageService
import org.tenshu.sync.SyncService
import org.tenshu.core.timeline.TimelineService
import kotlin.test.Test

/**
 * Every service Fit reaches is registered in its client graph.
 *
 * ⚠⚠ The failure this guards is **silent**: `getService` errors on an unregistered schema and the
 * app's repositories catch, so a missing registration renders as absent data rather than as a
 * crash. The announce gate cannot see it either — it compares only services the client declares.
 * `assertClientGraphRegisters` carries the full reasoning and Photos' §18.4 is the worked example.
 *
 * ⚠ [reached] is grepped, not derived — the compiler cannot connect a Gradle dependency to a
 * runtime list two modules away. **When a repository reaches a new service, add it here and to
 * `fitSchemaConfigs`.**
 */
class FitClientGraphTest {

    private val reached = mapOf(
        "workouts, samples, sleep and imports" to HealthService.ID,
        "movement traces promoted into workouts (FA-1)" to TimelineService.ID,
        "goals and the owner's person (FP-1)" to PreferenceService.ID,
        "the recording device's identity" to IdentityService.ID,
        "the import's background task and sync lane (TS-4)" to SyncService.ID,
        "the realm storage banner" to StorageService.ID,
    )

    @Test
    fun everyServiceTheAppReachesIsRegistered() =
        assertClientGraphRegisters("Fit", fitSchemaConfigs, reached)
}
