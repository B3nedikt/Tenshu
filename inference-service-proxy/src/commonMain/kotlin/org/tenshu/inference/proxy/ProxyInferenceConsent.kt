package org.tenshu.inference.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.inference.IndexingPaused
import org.tenshu.inference.InferenceCapability
import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.Setting

/**
 * Whether the user has agreed to [capability] **here** (IN-1, IN-D3), read straight off the proxy
 * DB — the same shape as `ProxyMiaGrants` and `ProxySyncRules.readFileSyncRuleSet`.
 *
 * ⚠⚠ **Both switches, and absence means NO.** The realm answers "may this realm be indexed for X
 * at all"; the device answers "may *this* device do the work". Unlike Mia's read grants — which
 * default *on* because reading was already the shipped behaviour — nothing here has ever run, so
 * silence means the user has not asked for it. Inferring over someone's library on the strength of
 * a missing row would be helping oneself.
 *
 * ⚠ Read per call, never cached: consent is a security decision, and a revocation the user flips
 * must take effect on the next unit of work rather than at the next process start. An indexing
 * pass that cached this at the top would keep grinding through a library the user just switched
 * off.
 */
fun ExecutionContext.hasInferenceConsent(
    capability: InferenceCapability,
    /**
     * This device's id.
     *
     * ⚠⚠ Required, not defaulted: a `DeviceSetting`'s key is `"$deviceId/$preferenceId"`, so a read
     * that guesses at the device half silently finds **no row for anything** — and since absence
     * means "no", the switch would appear to work while never taking effect. `ProxySyncRules`
     * takes it as a parameter for the same reason.
     */
    deviceId: String,
): Boolean {
    val realm = BooleanAdapter.fromData(readRealmSettingValue(capability.realmConsent.id))
        ?: capability.realmConsent.default
    if (!realm) return false
    return BooleanAdapter.fromData(readDeviceSettingValue(deviceId, capability.deviceConsent.id))
        ?: capability.deviceConsent.default
}

/**
 * Has the user **explicitly** switched [capability] off — for the realm, or on this device?
 *
 * ⚠⚠ **The deletion test, and deliberately NOT the negation of [hasInferenceConsent].** "May I do
 * this work" treats absence as NO, which is right for starting anything. "Must I destroy data"
 * cannot: a read that finds nothing — no row yet, a row mid-update, a read that failed and fell
 * through its `runCatching` — is not a user's act, and a deletion cannot be taken back. (Found while
 * diagnosing a mid-pass wipe on the maintainer's phone, 2026-09-27; that wipe turned out to be a
 * genuine switch-off of the device row, but the old rule would have deleted just the same on a
 * read that merely found nothing.)
 *
 * Switching off writes an explicit `false` row (the Settings switch, on any device — the realm row
 * syncs), so a withdrawal is always visible here. Only that deletes.
 */
fun ExecutionContext.inferenceConsentWithdrawn(capability: InferenceCapability, deviceId: String): Boolean =
    BooleanAdapter.fromData(readRealmSettingValue(capability.realmConsent.id)) == false ||
        BooleanAdapter.fromData(readDeviceSettingValue(deviceId, capability.deviceConsent.id)) == false

/**
 * Has the user paused indexing on **this** device (IN-D6 / [org.tenshu.inference.IndexingPaused])?
 *
 * ⚠ Device-scoped, so [deviceId] is required for the same reason `hasInferenceConsent` requires it:
 * a `DeviceSetting`'s key is `"$deviceId/$preferenceId"`, and a read that guesses the device half
 * finds no row — which here would read as "not paused" and quietly ignore the pause.
 *
 * ⚠⚠ Distinct from consent. Withdrawing consent is *"do not index my library"*; pausing is *"not
 * right now"* and keeps every derived row. A caller that conflated them would make "my phone is
 * hot" and "I changed my mind about this feature" the same act.
 */
fun ExecutionContext.isIndexingPaused(deviceId: String): Boolean =
    BooleanAdapter.fromData(readDeviceSettingValue(deviceId, IndexingPaused.id))
        ?: IndexingPaused.default

/**
 * Everything that may run here **and** can (IN-1).
 *
 * ⚠⚠ The two halves are deliberately separate everywhere else and joined only here: consent
 * without a backend is *"you said yes and we cannot"*, a backend without consent is *"we can and
 * you did not"*, and a caller that conflates them will eventually tell the user the wrong one of
 * those two things.
 */
fun ExecutionContext.permittedInference(
    registry: InferenceBackendRegistry,
    deviceId: String,
): Set<InferenceCapability> =
    InferenceCapability.entries.filterTo(mutableSetOf()) {
        registry.isAvailable(it) && hasInferenceConsent(it, deviceId)
    }

/** The stored polymorphic `value` of a realm-scoped preference row, or `null`. */
private fun ExecutionContext.readRealmSettingValue(preferenceId: String): Data? =
    readSettingValue(RealmSetting.ID, preferenceId)

/** The stored polymorphic `value` of the `(deviceId, preferenceId)` row, or `null`. */
private fun ExecutionContext.readDeviceSettingValue(deviceId: String, preferenceId: String): Data? =
    readSettingValue(DeviceSetting.ID, DeviceSetting.compositeKey(deviceId, preferenceId))

private fun ExecutionContext.readSettingValue(type: String, preferenceId: String): Data? {
    val dataId = DataId.StringDataId.create(context = context, type = type, keyValue = preferenceId)
    val stored = database.getData(dataId) ?: return null
    return when (stored) {
        is Entity<*> -> runCatching { stored.getFieldValue<Data>(Setting.Field.VALUE) }.getOrNull()
        is Composite -> runCatching { stored.getField<Data>(Setting.Field.VALUE) }.getOrNull()
        else -> null
    }
}
