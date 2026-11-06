package org.tenshu.hub.management.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.hub.management.HubCommand
import org.tenshu.hub.management.HubManagementService
import org.tenshu.hub.management.HubStatus
import org.tenshu.core.id

/**
 * Initialises the hub-management service's proxy-side observers.
 *
 * [HubManagementService.Relations.HUB_STATUSES] and
 * [HubManagementService.Relations.HUB_COMMANDS] are derived views of every
 * locally-known [HubStatus] / [HubCommand] — whether saved by a local operation
 * (`IssueHubCommandImplementation`, the hub's own status publisher) **or**
 * arriving via the sync-merge path. Relation membership is not a synced
 * primitive; the receiver derives it.
 *
 * Both are **declared on [HubManagementService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2) —
 * they were the local `HubManagementRelationMaintainer`.
 */
fun ExecutionContext.initHubManagementService() {
    installRelationMaintainer()
}
