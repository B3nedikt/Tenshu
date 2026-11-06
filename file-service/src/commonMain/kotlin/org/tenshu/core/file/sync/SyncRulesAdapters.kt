package org.tenshu.core.file.sync

import org.tenshu.core.Data
import org.tenshu.core.core.TContext
import org.tenshu.preference.PreferenceAdapter

/**
 * `SyncPolicy <-> Data` codec for the per-device default
 * ([FileSyncPreferences.DefaultPolicy]).
 *
 * Now a trivial **identity** codec: [SyncPolicy] is a first-class [org.tenshu.core.TConstant],
 * so the value already *is* a self-describing `Data` — it rides the polymorphic
 * `genericData` preference-value slot directly (stored by schema id, reconstructed
 * via `dataFactory.createConstant`) and Control reads it through the schema with no
 * adapter (`docs/proposals/drive-offline-online.md` §4.6). An unknown/mismatched
 * value decodes to `null`, so the read path falls back to
 * [org.tenshu.preference.Preference.default].
 */
object SyncPolicyAdapter : PreferenceAdapter<SyncPolicy> {
    override fun toData(value: SyncPolicy, context: TContext): Data = value

    override fun fromData(data: Data?): SyncPolicy? = data as? SyncPolicy
}

/**
 * `List<PathSyncOverride> <-> Data` codec for the per-device override set
 * ([FileSyncPreferences.Overrides]).
 *
 * Now **self-describing**: wraps the context-free value list into a single
 * [PathSyncOverrideSet] composite (each element a registered
 * [PathSyncOverrideEntry]) and back. The polymorphic `genericData` slot can't hold
 * a bare array, so the single-composite wrapper is the storable shape; Control then
 * reads the overrides field-by-field with no shared codec. The old length-prefixed
 * big-endian byte blob is gone (`docs/proposals/drive-offline-online.md` §4.10).
 *
 * A mismatched value (not a [PathSyncOverrideSet]) decodes to `null` → the
 * empty-list default.
 */
object PathSyncOverrideSetAdapter : PreferenceAdapter<List<PathSyncOverride>> {

    override fun toData(value: List<PathSyncOverride>, context: TContext): Data =
        PathSyncOverrideSet.create(context, value)

    override fun fromData(data: Data?): List<PathSyncOverride>? =
        (data as? PathSyncOverrideSet)?.toValues()
}
