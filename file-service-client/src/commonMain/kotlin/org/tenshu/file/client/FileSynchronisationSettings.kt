package org.tenshu.file.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.search
import org.tenshu.core.equalTo
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.sync.FileSyncSetting
import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.core.file.sync.ruleSet
import org.tenshu.core.resolve
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.setForDevice

/**
 * The typed **file-synchronisation settings** surface
 * (`docs/proposals/drive-offline-online.md` §4) — a thin facade over the
 * preference service that both Drive (this device) and Control (any device, §4.6)
 * consume, so no app holds the model and no shared codec is needed.
 *
 * Every accessor is **deviceId-parameterized**: Drive passes its own device id;
 * Control passes any paired device's id to inspect "what that device keeps".
 *
 * Two read shapes coexist:
 *  - the **per-preference** facade below (`fileSyncRuleSet` / `fileSyncDefaultPolicy`
 *    / `fileSyncDefaultPolicyOrNull`), which reads the two underlying `DeviceSetting`
 *    rows directly and is precise about *row presence* (the `…OrNull` variant); and
 *  - the **first-class** [fileSyncSettings] relation — a chain-DSL-native
 *    `TList<FileSyncSetting>` typed projection (one row per device), populated by the
 *    proxy-side `FileSyncSettingsRelationMaintainer`. Read it for typed,
 *    field-by-field access without a generic-`Data` decode (`fileSyncSettingFor` /
 *    `fileSyncRuleSetFor`).
 *
 * **Writes go through the facade only** ([setFileSyncDefaultPolicy] /
 * [setFileSyncOverrides] → the two `DeviceSetting` rows); the relation is a derived
 * read view, so there is nothing to write to it.
 */

/**
 * The first-class `serviceDataRelation` projection of every device's
 * file-synchronisation settings — `preferenceService.fileSyncSettings` reads as a
 * chain-DSL `TList<FileSyncSetting>` (`docs/proposals/drive-offline-online.md`
 * §"Follow-up — a first-class serviceDataRelation").
 *
 * The relation is registered from `:file-service-common` *against*
 * `PreferenceService.ID` (cross-config — `:preference-service` is untouched), so it
 * has no native property on the `PreferenceService` interface; this extension builds
 * the same chain a native relation property would (mirrors `FileServiceRo.shares`),
 * resolving the relation id through the registry. The membership is derived on the
 * proxy from the synced `file.sync.*` `DeviceSetting` rows — see
 * `FileSyncSettingsRelationMaintainer`.
 */
val PreferenceService.fileSyncSettings: TList<FileSyncSetting>
    get() {
        val requestObject = this as RequestObject
        val nextChain = requestObject.requestChain
            .addReference(PreferenceService.ID)
            .addReference(FileSyncSetting.RELATION)
        return requestObject.context.roProvider().createRequestObject<TList<FileSyncSetting>>(
            type = listType(FileSyncSetting.Schema),
            requestChain = nextChain,
            context = requestObject.context,
        )
    }

/**
 * One-shot typed read of [deviceId]'s projected sync settings, or `null` when the
 * device has no file-sync row yet. The typed payoff over the facade: `defaultPolicy`
 * + `overrides` arrive together on one entity, read field-by-field with no codec at
 * the call site (decode via [FileSyncSetting.ruleSet]).
 */
suspend fun PreferenceService.fileSyncSettingFor(deviceId: String): FileSyncSetting? {
    val result = fileSyncSettings
        .search<FileSyncSetting> { this.deviceId equalTo deviceId }
        .resolve()
    // `search<T>` is typed `Maybe<Entity<T>>`, but a relation array element comes
    // back as the bare `FileSyncSetting` composite. Read `.value` through the star
    // projection so the compiler doesn't insert an `Entity<T>` checkcast on a
    // composite (the same extraction shape `ShareSyncSystemTest` uses).
    return (result as? TResult.Success<*>)?.value as? FileSyncSetting
}

/**
 * One-shot [SyncRuleSet] for [deviceId] via the first-class relation — the
 * chain-native counterpart of [fileSyncRuleSet]'s reactive `combine`. Falls back to
 * [SyncRuleSet.Default] when the device has no projection row.
 */
suspend fun PreferenceService.fileSyncRuleSetFor(deviceId: String): SyncRuleSet =
    fileSyncSettingFor(deviceId)?.ruleSet() ?: SyncRuleSet.Default

/** This device's effective rule set (default + sparse overrides), re-emitting on change. */
fun PreferenceService.fileSyncRuleSet(deviceId: String): Flow<SyncRuleSet> =
    combine(
        // onStart seeds each leg with its default so `combine` fires immediately even
        // before any row exists (the per-device relation is silent on a cold start —
        // see PreferenceServiceExtensions.readForDevice).
        readForDevice(FileSyncPreferences.DefaultPolicy, deviceId)
            .onStart { emit(FileSyncPreferences.DefaultPolicy.default) },
        readForDevice(FileSyncPreferences.Overrides, deviceId)
            .onStart { emit(FileSyncPreferences.Overrides.default) },
    ) { default, overrides -> SyncRuleSet(deviceDefault = default, overrides = overrides) }

/** This device's default [SyncPolicy], re-emitting on change (falls back to the default). */
fun PreferenceService.fileSyncDefaultPolicy(deviceId: String): Flow<SyncPolicy> =
    readForDevice(FileSyncPreferences.DefaultPolicy, deviceId)
        .onStart { emit(FileSyncPreferences.DefaultPolicy.default) }

/** Writes this device's default [SyncPolicy]. */
suspend fun PreferenceService.setFileSyncDefaultPolicy(
    deviceId: String,
    policy: SyncPolicy,
): TResult<Data?> = setForDevice(FileSyncPreferences.DefaultPolicy, policy, deviceId)

/**
 * This device's **cache** ceiling in bytes, re-emitting on change; `0` means no
 * ceiling (`retention-quota-model.md` Face B / D6).
 *
 * ⚠ It bounds only *evictable* cached content — [SyncPolicy.KEEP_OFFLINE] files are
 * outside it, neither counted against it nor evicted to satisfy it. Any UI over this
 * must therefore say **cache**, not "total data": counting content that cannot be
 * evicted would make the setting unsatisfiable the moment pins exceed it, and a label
 * promising a total footprint we do not enforce is the worse failure.
 */
fun PreferenceService.fileSyncMaxLocalBytes(deviceId: String): Flow<Long> =
    readForDevice(FileSyncPreferences.MaxLocalBytes, deviceId)
        .onStart { emit(FileSyncPreferences.MaxLocalBytes.default) }

/** Writes this device's cache ceiling in bytes; `0` clears it. */
suspend fun PreferenceService.setFileSyncMaxLocalBytes(
    deviceId: String,
    maxLocalBytes: Long,
): TResult<Data?> = setForDevice(FileSyncPreferences.MaxLocalBytes, maxLocalBytes, deviceId)

/** Replaces this device's sparse override set. */
suspend fun PreferenceService.setFileSyncOverrides(
    deviceId: String,
    overrides: List<PathSyncOverride>,
): TResult<Data?> = setForDevice(FileSyncPreferences.Overrides, overrides, deviceId)

/**
 * One-shot read of [deviceId]'s **explicitly-set** default policy, or `null` when
 * the device has no policy row yet (pre-v3, or never written). Distinct from
 * [fileSyncDefaultPolicy], which folds the absent case into the default — Control's
 * read-only view uses this to show nothing rather than claim a default a device
 * never chose (§4.6). A direct row query, so it returns promptly.
 */
suspend fun PreferenceService.fileSyncDefaultPolicyOrNull(deviceId: String): SyncPolicy? {
    val key = DeviceSetting.compositeKey(deviceId, FileSyncPreferences.DefaultPolicy.id)
    val result = deviceSettings
        .search<DeviceSetting> { this.compositeKey equalTo key }
        .resolve()
    // Star-project before reading `.value` — `search<T>` is statically typed
    // `Maybe<Entity<T>>` but the chain hands back the bare `DeviceSetting`
    // composite, and an inferred `TResult.Success<Entity<…>>` makes the compiler
    // insert an `Entity` checkcast that throws (same shape as [fileSyncSettingFor]).
    val setting = (result as? TResult.Success<*>)?.value as? DeviceSetting
    return setting?.value?.let { FileSyncPreferences.DefaultPolicy.adapter.fromData(it) }
}
