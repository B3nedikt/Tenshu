package org.tenshu.preference.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.TContext
import org.tenshu.core.get
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.clearSetting
import org.tenshu.preference.writeSetting

/*
 * Typed convenience wrappers around [org.tenshu.preference.WriteSetting] /
 * [org.tenshu.preference.ClearSetting] that route through a [Preference]
 * declaration's [org.tenshu.preference.PreferenceAdapter] for the
 * `T <-> Data` round-trip. Per
 * `docs/proposals/core-preference-service.md` §6.4.
 *
 * Covers writes ([set] / [setForDevice] / [clear] / [clearOverride]) against
 * REALM, DEVICE, and OVERRIDABLE scopes, plus the reactive reads ([read] /
 * [readForDevice]) added in Phase 3 for the Control settings UI. SystemTests
 * still use the chain DSL directly
 * (`service.realmSettings.search<RealmSetting> { ... }.get()`) for one-shot
 * assertions.
 *
 * `TContext` is derived from the receiver via `this as TContext` — every
 * RO in the request DSL implements both [Data] and [TContext], so the cast
 * always succeeds at call sites that actually obtained the service through
 * the DSL. See `docs/how-to/add-composites-and-operations.md` §2 for the
 * convention.
 */

/**
 * Writes [value] to the row appropriate for [pref]'s scope:
 *
 * - REALM, OVERRIDABLE: writes the realm row.
 * - DEVICE: returns a [TResult.Failure] — call [setForDevice] with an
 *   explicit device id instead (no `currentDeviceId` resolver exists in
 *   `:preference-service-client` yet; the SDK does not know which device
 *   it's running on without a proxy round trip).
 */
suspend fun <T> PreferenceService.set(
    pref: Preference<T>,
    value: T,
): TResult<Data?> {
    val context = this as TContext
    if (pref.scope == PreferenceScope.DEVICE) {
        return TResult.Failure.create(
            context = context,
            error = GenericError.create(
                context = context,
                message = "DEVICE-scoped preference ${pref.id} must use setForDevice(...)"
            )
        )
    }
    return writeSetting(
        preferenceId = pref.id,
        value = pref.adapter.toData(value, context),
        deviceId = null,
    )
}

/**
 * Writes [value] to the per-device row keyed by [deviceId]. Valid for DEVICE
 * and OVERRIDABLE scopes; passing a REALM-scoped preference here is a
 * mis-use — the resulting row is unreachable through the REALM read path.
 */
suspend fun <T> PreferenceService.setForDevice(
    pref: Preference<T>,
    value: T,
    deviceId: String,
): TResult<Data?> {
    val context = this as TContext
    return writeSetting(
        preferenceId = pref.id,
        value = pref.adapter.toData(value, context),
        deviceId = deviceId,
    )
}

/**
 * Clears the realm row for [pref]. Subsequent reads fall back to
 * [Preference.default].
 */
suspend fun <T> PreferenceService.clear(
    pref: Preference<T>,
): TResult<Data?> = clearSetting(pref.id, deviceId = null)

/**
 * Clears the per-device row for [pref] on [deviceId]. For OVERRIDABLE
 * preferences this is the canonical "remove this device's override" gesture.
 */
suspend fun <T> PreferenceService.clearOverride(
    pref: Preference<T>,
    deviceId: String,
): TResult<Data?> = clearSetting(pref.id, deviceId = deviceId)

/**
 * Reactive realm-row read: emits [pref]'s effective value whenever the realm
 * setting changes — a local write, a `SyncBlock` arriving from another device,
 * or a clear (which re-emits [Preference.default]). A present-but-undecodable
 * row (type mismatch) also resolves to [Preference.default] — see
 * `core-preference-service.md` §6.5/§6.6.
 *
 * The underlying relation stream replays existing rows on subscribe, but emits
 * nothing for a realm that has never held this row, so on a cold start with no
 * row the flow stays silent until one appears. Consumers therefore seed their
 * initial state with [Preference.default] (see `BackgroundSyncSettingsViewModel`)
 * rather than relying on a first emission.
 *
 * Valid for REALM and OVERRIDABLE preferences (OVERRIDABLE resolves to the
 * realm row only until Phase 6 wires per-device override resolution). For a
 * DEVICE-scoped preference use [readForDevice]; passing one here never matches
 * because device rows live in a separate relation.
 */
fun <T> PreferenceService.read(pref: Preference<T>): Flow<T> =
    realmSettings.flow().map { result ->
        val row = (result as? TResult.Success)?.value?.toList()
            ?.firstOrNull { it.preferenceId.get() == pref.id }
        pref.adapter.fromData(row?.value) ?: pref.default
    }

/**
 * **One-shot snapshot** of [pref]'s realm value — what [read] cannot give you.
 *
 * [read] is a *stream*: on a realm that has never held any setting it stays silent, so a `.first()`
 * on it hangs; and a caller that seeds a default to avoid that then races the store and usually
 * reads its own seed. Neither is acceptable for a decision that must be made **once, correctly** —
 * "has the platform already been asked?", "did the user already answer?". This terminal reads the
 * relation once and answers immediately: absent, undecodable, or an empty realm all resolve to
 * [Preference.default].
 *
 * Prefer [read] for anything a screen renders; use this only where a snapshot is the point.
 */
suspend fun <T> PreferenceService.readOnce(pref: Preference<T>): T {
    val row = realmSettings.getAll()
        .let { (it as? TResult.Success)?.value?.toList() }
        ?.firstOrNull { it.preferenceId.get() == pref.id }
    return pref.adapter.fromData(row?.value) ?: pref.default
}

/**
 * Reactive per-device read: emits [pref]'s value for [deviceId], re-emitting on
 * change. Falls back to [Preference.default] when the `(deviceId, preferenceId)`
 * row is absent or undecodable. The canonical reader for DEVICE-scoped
 * preferences and for inspecting another device's row (`readAll`-style usage).
 */
fun <T> PreferenceService.readForDevice(
    pref: Preference<T>,
    deviceId: String,
): Flow<T> {
    val key = DeviceSetting.compositeKey(deviceId, pref.id)
    return deviceSettings.flow().map { result ->
        val row = (result as? TResult.Success)?.value?.toList()
            ?.firstOrNull { it.compositeKey.get() == key }
        pref.adapter.fromData(row?.value) ?: pref.default
    }
}

/**
 * **One-shot snapshot** of [pref]'s value for [deviceId] — the device twin of
 * [readOnce], and what any decision that is made *once* must use.
 *
 * A `.first()` on [readForDevice] is not the same thing twice over. On a device whose
 * relation holds no rows at all the stream never emits, so the call hangs; and callers
 * layer their own "seed a default, then switch to the real rows" flow on top for the
 * benefit of screens (`TenshuBackupPreferences.observe`), which makes `.first()` return
 * the **placeholder**, deterministically, forever. That is what silently disabled Synced
 * Folders: the drain read its selected buckets with `.first()` and always saw an empty
 * set. This terminal reads the relation once and answers immediately — absent,
 * undecodable, or an empty relation all resolve to [Preference.default].
 *
 * Prefer [readForDevice] for anything a screen renders; use this wherever a snapshot is
 * the point (a background worker's gate, a read-modify-write, a drain's inputs).
 */
/**
 * [readOnceForDevice] that can say **"could not read"**: `null` when the settings relation could not
 * be read at all (the proxy is unreachable, the call failed), [Preference.default] only for an
 * absent or undecodable row.
 *
 * ⚠⚠ For any decision that must not act on a guess. `readOnceForDevice` answers a failed read with
 * the default, which for a switch that defaults off is indistinguishable from "the user turned it
 * off" — and Synced Folders' scheduler cancelled every backup job on exactly that answer whenever
 * Drive started while Control was slow or dead.
 */
suspend fun <T> PreferenceService.readOnceForDeviceOrNull(
    pref: Preference<T>,
    deviceId: String,
): T? {
    val key = DeviceSetting.compositeKey(deviceId, pref.id)
    val result = try {
        deviceSettings.getAll()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        return null
    }
    val rows = (result as? TResult.Success)?.value?.toList() ?: return null
    val row = rows.firstOrNull { it.compositeKey.get() == key }
    return pref.adapter.fromData(row?.value) ?: pref.default
}

suspend fun <T> PreferenceService.readOnceForDevice(
    pref: Preference<T>,
    deviceId: String,
): T {
    val key = DeviceSetting.compositeKey(deviceId, pref.id)
    val row = deviceSettings.getAll()
        .let { (it as? TResult.Success)?.value?.toList() }
        ?.firstOrNull { it.compositeKey.get() == key }
    return pref.adapter.fromData(row?.value) ?: pref.default
}
