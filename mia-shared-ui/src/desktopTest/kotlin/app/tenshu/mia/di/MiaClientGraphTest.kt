package app.tenshu.mia.di

import app.tenshu.mia.di.miaSchemaConfigs
import org.tenshu.core.common.assertClientGraphRegisters
import org.tenshu.mia.MiaService
import org.tenshu.preference.PreferenceService
import org.tenshu.storage.StorageService
import org.tenshu.sync.SyncService
import kotlin.test.Test

/**
 * Every service Mia reaches is registered in its client graph.
 *
 * ⚠⚠ The failure this guards is **silent**: `getService` errors on an unregistered schema and the
 * app's repositories catch, so a missing registration renders as absent data rather than as a
 * crash. The announce gate cannot see it either — it compares only services the client declares.
 * `assertClientGraphRegisters` carries the full reasoning and Photos' §18.4 is the worked example.
 *
 * ⚠ [reached] is grepped, not derived — the compiler cannot connect a Gradle dependency to a
 * runtime list two modules away. **When a repository reaches a new service, add it here and to
 * `miaSchemaConfigs`.**
 */
class MiaClientGraphTest {

    private val reached = mapOf(
        "conversations and the query planner" to MiaService.ID,
        "the per-source grant switches (MQ-2b)" to PreferenceService.ID,
        "the realm storage banner" to StorageService.ID,
        "the Control-not-set-up wall (ADR 0025 §Decision 8)" to SyncService.ID,
    )

    @Test
    fun everyServiceTheAppReachesIsRegistered() =
        assertClientGraphRegisters("Mia", miaSchemaConfigs, reached)
}
