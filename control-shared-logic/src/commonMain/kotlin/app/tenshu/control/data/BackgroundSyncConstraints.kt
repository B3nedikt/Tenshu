package app.tenshu.control.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import org.tenshu.core.core.TContext
import org.tenshu.preference.ControlPreferences
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.client.read
import org.tenshu.sync.ConstraintSpec

/**
 * Bridges the realm-wide background-sync preferences
 * ([ControlPreferences.WifiOnly] / [ControlPreferences.MaxMonthlyDataMb]) to the
 * [ConstraintSpec] a sync `ScheduleItem` should carry — the preference-service
 * Phase 5 consumer (`core-preference-service.md` §9 step 6 / §10.1).
 *
 * Lives in `:control-shared` because it's the one module that depends on both
 * `:preference-service` (where Control's prefs are declared, §3.8) and
 * `:sync-service` (where [ConstraintSpec] lives) — keeping that edge out of the
 * service modules themselves.
 *
 * The mapping is best-effort: only the Android `PlatformScheduler`
 * (bgproc Phase 6) actually *enforces* the resulting constraint; other
 * platforms treat it as advisory (`core-background-processing.md` §3.3 / §5.6).
 */

/** Pure mapping: preference values → the constraint a sync job carries. */
fun backgroundSyncConstraintSpec(
    context: TContext,
    wifiOnly: Boolean,
    maxMonthlyDataMb: Long,
): ConstraintSpec = ConstraintSpec.create(
    context = context,
    network = if (wifiOnly) ConstraintSpec.NETWORK_UNMETERED else ConstraintSpec.NETWORK_ANY,
    // 0 (or less) means "no cap" — leave the field absent rather than store a 0.
    maxDataMb = maxMonthlyDataMb.takeIf { it > 0 },
)

/**
 * Reactive source for the sync scheduler: re-emits a fresh [ConstraintSpec]
 * whenever either preference changes, so a toggle reschedules the job under the
 * new constraint.
 *
 * Each leg seeds [ControlPreferences]' declared default via [onStart] because
 * the underlying [read] stays silent until a row exists (Phase 3 cold-start
 * contract) — without the seed [combine] would emit nothing until *both* prefs
 * had been written at least once.
 */
fun PreferenceService.backgroundSyncConstraints(): Flow<ConstraintSpec> {
    val context = this as TContext
    return combine(
        read(ControlPreferences.WifiOnly).onStart { emit(ControlPreferences.WifiOnly.default) },
        read(ControlPreferences.MaxMonthlyDataMb)
            .onStart { emit(ControlPreferences.MaxMonthlyDataMb.default) },
    ) { wifiOnly, maxMb -> backgroundSyncConstraintSpec(context, wifiOnly, maxMb) }
}
