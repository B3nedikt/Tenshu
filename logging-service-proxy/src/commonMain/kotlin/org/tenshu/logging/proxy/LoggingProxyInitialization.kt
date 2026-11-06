package org.tenshu.logging.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.logging.ErrorEvent
import org.tenshu.logging.LoggingService
import org.tenshu.core.id

/**
 * Initialises the logging service's proxy-side observers.
 *
 * [LoggingService.Relations.EVENTS] mirrors every locally-known [ErrorEvent] —
 * relation membership isn't a synced primitive, so the receiver derives the
 * relation locally from an incoming `SyncBlock`'s entity payload. Without it an
 * `ErrorEvent` arriving via sync would land in the local DB but stay invisible
 * to the Settings → Errors UI on the receiving device.
 *
 * It is **declared on [LoggingService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2) —
 * it was the local `ErrorEventRelationMaintainer`.
 */
fun ExecutionContext.initLoggingService() {
    installRelationMaintainer()
}
