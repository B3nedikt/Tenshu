package org.tenshu.ingest.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.ingest.IngestService
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.id

/**
 * Initialises the ingest service's proxy-side observers.
 *
 * [IngestService.Relations.SOURCES] is a derived view of every locally-known
 * [IngestSource] row — written by `RegisterSource` / `UpdateSourceStatus` /
 * `SetSourceEnabled`, **or** arriving from another of the user's devices in a
 * `SyncBlock`. Relation membership is not a synced primitive; without it a
 * source arriving via sync would land in the local DB but stay invisible to the
 * cross-device management view (phase-2 doc §3.1/§8).
 *
 * It is **declared on [IngestService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2) —
 * it was the local `IngestSourceRelationMaintainer`.
 */
fun ExecutionContext.initIngestService() {
    installRelationMaintainer()
}
