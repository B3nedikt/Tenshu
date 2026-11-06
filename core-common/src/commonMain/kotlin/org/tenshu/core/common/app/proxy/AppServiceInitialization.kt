package org.tenshu.core.common.app.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.app.AppGrant
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.AppService
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.DataId
import org.tenshu.core.id

/**
 * The [AppService.Relations.APP_REGISTRATIONS] array, for `RemoveAppImplementation`
 * (which enumerates registrations to tombstone an all-devices removal). Derived
 * from the relation's declared spec, so it cannot drift from what the generic
 * maintainer writes (`core-relation-system.md` D4).
 */
internal fun ExecutionContext.appRegistrationsRef(): ArrayDatabaseReference =
    relationReference(AppService.Relations.APP_REGISTRATIONS)

/** The [AppService.Relations.APP_GRANTS] array (grant purge on app removal). */
internal fun ExecutionContext.appGrantsRef(): ArrayDatabaseReference =
    relationReference(AppService.Relations.APP_GRANTS)

/**
 * Reads a [TString] field off a [Data] that may be a bare [Composite] or an
 * [Entity], tolerating an absent/mistyped field by returning `null`. Mirrors
 * the fold in `FileSyncSettingsRelationMaintainer`.
 */
internal fun stringFieldOf(data: Data, fieldId: String): String? = when (data) {
    is Entity<*> -> runCatching { data.optionalFieldValue<TString>(fieldId)?.get() }.getOrNull()
    is Composite -> runCatching { data.getField<TString>(fieldId).get() }.getOrNull()
    else -> null
}

/**
 * Initialises the app service's proxy-side observers.
 *
 * [AppService.Relations.APP_REGISTRATIONS] and [AppService.Relations.APP_GRANTS]
 * are derived views of every locally-known [AppRegistration] / [AppGrant] —
 * whether written by an operation implementation (`RegisterAppImplementation` /
 * `SetAppGrantImplementation`) or arriving via the sync-merge path. Relation
 * membership is derived locally, not synced.
 *
 * Both are **declared on [AppService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * [org.tenshu.core.common.relation.RelationMaintainer] this installs
 * (`core-relation-system.md` §9.2.1, R2) — they were the local
 * `AppRelationMaintainer`.
 *
 * Wired into each app's `PlatformProxyModule` initialization hook and the test
 * harness; idempotent via remove-then-add keyed on each composite's own
 * `DataId`.
 */
fun ExecutionContext.initAppService() {
    installRelationMaintainer()
}
