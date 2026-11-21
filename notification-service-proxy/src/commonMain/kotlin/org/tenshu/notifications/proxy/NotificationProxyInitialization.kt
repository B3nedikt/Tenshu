package org.tenshu.notifications.proxy

import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.notifications.DismissedRequest
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.sync.common.TombstoneEmitterHolder
import org.tenshu.core.entity.DataId
import org.tenshu.core.id

/**
 * Initialises the notification service's proxy-side observers.
 *
 * [NotificationService.Relations.NOTIFICATIONS] and
 * [NotificationService.Relations.DISMISSED_REQUESTS] are derived views of every
 * locally-known [Notification] / [DismissedRequest]: relation membership is not
 * a synced primitive — `SyncBlock`s carry the entity, and each receiver derives
 * the relation locally. Without it a notification arriving via sync would land in
 * the local DB but stay invisible to the notification stream, which is exactly
 * the production "notifications never show up on the other side" symptom
 * (`NotificationSyncSystemTest`), and a peer's dismissal would never clear the
 * badge here (`control-central-notifications.md` Phase 4).
 *
 * Both are **declared on [NotificationService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2) —
 * they were `NotificationRelationMaintainer` / `DismissedRequestRelationMaintainer`.
 *
 * Idempotent via remove-then-add keyed on the entity's own `DataId`, so the
 * explicit `saveData` and the sync-merge path converge to one entry.
 *
 * Also installs [NotificationRetention], which keeps the notification log bounded
 * (`control-central-notifications.md` §10 D2).
 */
fun ExecutionContext.initNotificationService(
    /**
     * What tombstones a prune. ⚠⚠ Every runtime with sync must pass its holder: without it a prune
     * deletes on this device only, and every peer and the relay keep the rows.
     */
    tombstoneEmitterHolder: TombstoneEmitterHolder?,
    /** Where [NotificationRetention]'s passes run. A test passes its scheduler's scope. */
    retentionScope: CoroutineScope = sessionScope,
) {
    installRelationMaintainer()
    // §10 D2: the log is capped by construction — see [NotificationRetention].
    addDataObserver(NotificationRetention(this, scope = retentionScope, tombstones = tombstoneEmitterHolder))
}
