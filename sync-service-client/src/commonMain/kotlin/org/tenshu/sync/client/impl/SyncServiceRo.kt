package org.tenshu.sync.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.sync.ContentTransferStatus
import org.tenshu.sync.ContentUploadProgress
import org.tenshu.sync.RuntimeIdentity
import org.tenshu.sync.SchemaFenceStatus
import org.tenshu.sync.SyncCatchUpHeartbeat
import org.tenshu.sync.SyncCatchUpStatus
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.SyncService

class SyncServiceRo(
    requestChain: RequestChain,
    context: TContext
) : ServiceRo(requestChain, context), SyncService {
    // Without this override, `Service`'s default getter returns the base
    // `service("tenshu/core/service")` schema, so `operations` queries the wrong
    // type and never finds the SyncService-registered ROs (StartPairing, etc.).
    override val schema: ServiceSchema
        get() = SyncService.Schema

    override val syncHealthStatuses: TList<SyncHealthStatus>
        get() = serviceRelation(SyncService.Relations.SYNC_HEALTH, SyncHealthStatus.Schema)

    /** Schema pull-fence signal (`core-schema-evolution.md` §7.3) — mirror of [syncHealthStatuses]. */
    override val schemaFenceStatuses: TList<SchemaFenceStatus>
        get() = serviceRelation(SyncService.Relations.SCHEMA_FENCE, SchemaFenceStatus.Schema)

    /** Deferred-content signal (`sync-metered-plane-split.md` §8) — mirror of [syncHealthStatuses]. */
    override val contentTransferStatuses: TList<ContentTransferStatus>
        get() = serviceRelation(SyncService.Relations.CONTENT_TRANSFER, ContentTransferStatus.Schema)

    /** Upload-pass progress (`drive-upload-progress.md` P2) — mirror of [contentTransferStatuses]. */
    override val contentUploadProgresses: TList<ContentUploadProgress>
        get() = serviceRelation(
            SyncService.Relations.CONTENT_UPLOAD_PROGRESS,
            ContentUploadProgress.Schema,
        )

    /** Per-writer pull activity (`ios-sync-activity-indicator.md` §3.1). */
    override val syncCatchUpStatuses: TList<SyncCatchUpStatus>
        get() = serviceRelation(SyncService.Relations.CATCH_UP, SyncCatchUpStatus.Schema)

    override val syncCatchUpHeartbeats: TList<SyncCatchUpHeartbeat>
        get() = serviceRelation(SyncService.Relations.CATCH_UP_HEARTBEAT, SyncCatchUpHeartbeat.Schema)

    /** Runtime host identity (`core-schema-skew-surface.md` SK-2) — mirror of [syncHealthStatuses]. */
    override val runtimeIdentities: TList<RuntimeIdentity>
        get() = serviceRelation(SyncService.Relations.RUNTIME_IDENTITY, RuntimeIdentity.Schema)
}
