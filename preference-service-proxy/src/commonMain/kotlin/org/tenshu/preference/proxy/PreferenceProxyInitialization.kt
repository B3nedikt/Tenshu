package org.tenshu.preference.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.RealmSetting
import org.tenshu.core.entity.DataId
import org.tenshu.core.id

/**
 * Initialises the preference service's proxy-side observers.
 *
 * [PreferenceService.Relations.REALM_SETTINGS] and
 * [PreferenceService.Relations.DEVICE_SETTINGS] are derived views of every
 * locally-known [RealmSetting] / [DeviceSetting]: relation membership is not a
 * synced primitive — `SyncBlock`s carry the entity, and each receiver derives
 * the relation locally. Without it a setting arriving via sync would land in the
 * local DB but stay invisible to `PreferenceService.realmSettings` /
 * `.deviceSettings`.
 *
 * Both are **declared on [PreferenceService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2) —
 * they were the local `PreferenceRelationMaintainer`.
 *
 * Idempotent via remove-then-add keyed on each composite's own `DataId`
 * (preferenceId for realm rows, `"$deviceId/$preferenceId"` for device rows —
 * see `DeviceSetting.compositeKey`).
 */
fun ExecutionContext.initPreferenceService() {
    installRelationMaintainer()
}
