package org.tenshu.sync

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf
import org.tenshu.sync.SyncService.Collections.PENDING_BLOCKS

/**
 * Service responsible for managing the synchronization of data blocks between devices.
 */
interface SyncService : Service {

    /**
     * Single-row relation carrying this device's locally-derived [SyncHealthStatus]
     * (`docs/proposals/drive-connectivity-service.md`). The proxy maintains exactly
     * one element (keyed on [SyncHealthStatus.SINGLETON_KEY]); the client projects
     * it to a single value (see `:sync-service-client` `syncHealth()`). Non-`Syncable`,
     * IPC-local — it never reaches the relay.
     */
    val syncHealthStatuses: TList<SyncHealthStatus>

    /**
     * Single-row relation carrying this device's locally-derived [SchemaFenceStatus]
     * (`core-schema-evolution.md` §7.3): whether the realm schema **pull
     * fence** is engaged, and for which services. Same posture as
     * [syncHealthStatuses] — the proxy maintains exactly one element (keyed on
     * [SchemaFenceStatus.SINGLETON_KEY]), non-`Syncable`, IPC-local, never on the
     * relay.
     */
    val schemaFenceStatuses: TList<SchemaFenceStatus>

    /**
     * The [RuntimeIdentity] of each binary hosting a proxy over this store
     * (`core-schema-skew-surface.md` SK-2, §8.7) — **one row per writer**: exactly one (Control's)
     * on Android and desktop, one per route-1 app on a shared iOS replica. Same posture as
     * [schemaFenceStatuses] otherwise: non-`Syncable`, IPC-local, never on the wire.
     *
     * A client asks [isControlHosted] of its runtime's row to learn whether to name *this* app or
     * Control — without having to assert a deployment topology it does not own.
     */
    val runtimeIdentities: TList<RuntimeIdentity>

    /**
     * Single-row relation carrying this device's locally-derived [ContentTransferStatus]
     * (`sync-metered-plane-split.md` §8): how many files are waiting to upload and whether the
     * metered content gate is what is holding them. Same posture as [syncHealthStatuses] — one
     * element (keyed on [ContentTransferStatus.SINGLETON_KEY]), non-`Syncable`, IPC-local, never
     * on the relay.
     */
    val contentTransferStatuses: TList<ContentTransferStatus>

    /**
     * Single-row relation carrying this device's [ContentUploadProgress] — the "3 of 12" of an
     * upload pass in flight (`drive-upload-progress.md` P2). A separate row from
     * [contentTransferStatuses] because that one is a positional composite that cannot grow
     * fields; see [ContentUploadProgress]. Non-`Syncable`, IPC-local, never on the relay.
     */
    val contentUploadProgresses: TList<ContentUploadProgress>

    /**
     * Whether each runtime on this store is pulling data in (`ios-sync-activity-indicator.md`
     * §3.1). ⚠ **Not** single-row: one [SyncCatchUpStatus] per writer, keyed by its host app id.
     * A reader asks for its own runtime's row. Non-`Syncable`, local, never on the relay.
     */
    val syncCatchUpStatuses: TList<SyncCatchUpStatus>

    /**
     * Every runtime's pull **with a pulse** (`ios-sync-activity-indicator.md` §12): one
     * [SyncCatchUpHeartbeat] per writer, refreshed while it catches up. Unlike
     * [syncCatchUpStatuses] a reader may count **any** writer's row here, because a row stops
     * counting by itself once its writer stops beating. Non-`Syncable`, local, never on the relay.
     */
    val syncCatchUpHeartbeats: TList<SyncCatchUpHeartbeat>

    companion object {
        const val ID = "tenshu/sync/service"

        val Schema = service(
            id = ID,
            /**
             * Schema version (`core-schema-evolution.md` §4) — never serialized;
             * it reaches the wire only through the frozen `VersionVector`
             * (announce reply + block stamp + realm epoch).
             *
             * ⚠ The per-service version is the *data* version — the thing that fences peers.
             * A non-`Syncable`, locally-derived relation (e.g. [SchemaFenceStatus]) puts nothing
             * in a block and owes no bump: bumping for one would pull-fence every
             * not-yet-updated device over a purely local status row (§7.2). A persisted
             * positional reshape (even of a local-only row) still owes a bump + migration.
             */
            // v1 = release baseline (core-schema-evolution.md Appendix A).
            version = 1,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.SYNC_HEALTH,
                    type = TList.typeOf(SyncHealthStatus.Schema),
                ),
                serviceDataRelation(
                    id = Relations.SCHEMA_FENCE,
                    type = TList.typeOf(SchemaFenceStatus.Schema),
                ),
                serviceDataRelation(
                    id = Relations.CONTENT_TRANSFER,
                    type = TList.typeOf(ContentTransferStatus.Schema),
                ),
                serviceDataRelation(
                    id = Relations.CONTENT_UPLOAD_PROGRESS,
                    type = TList.typeOf(ContentUploadProgress.Schema),
                ),
                serviceDataRelation(
                    id = Relations.RUNTIME_IDENTITY,
                    type = TList.typeOf(RuntimeIdentity.Schema),
                ),
                serviceDataRelation(
                    id = Relations.CATCH_UP,
                    type = TList.typeOf(SyncCatchUpStatus.Schema),
                ),
                serviceDataRelation(
                    id = Relations.CATCH_UP_HEARTBEAT,
                    type = TList.typeOf(SyncCatchUpHeartbeat.Schema),
                ),
            ),
        )
    }

    object Relations {
        const val SYNC_HEALTH = "$ID/syncHealth"

        /** Single-row [SchemaFenceStatus] relation (§7.3) — see [schemaFenceStatuses]. */
        const val SCHEMA_FENCE = "$ID/schemaFence"

        /**
         * Per-writer [RuntimeIdentity] relation (`core-schema-skew-surface.md` SK-2) — see
         * [runtimeIdentities]. ⚠ Owes no version bump: a new
         * non-`Syncable` type reshapes nothing, and bumping this service would pull-fence every
         * not-yet-updated device (see the `version` KDoc above and [RuntimeIdentity]'s own).
         */
        const val RUNTIME_IDENTITY = "$ID/runtimeIdentity"

        /**
         * Single-row [ContentTransferStatus] relation (`sync-metered-plane-split.md` §8) —
         * see [contentTransferStatuses].
         */
        const val CONTENT_TRANSFER = "$ID/contentTransfer"

        /**
         * Single-row [ContentUploadProgress] relation (`drive-upload-progress.md` P2) —
         * see [contentUploadProgresses].
         */
        const val CONTENT_UPLOAD_PROGRESS = "$ID/contentUploadProgress"

        /**
         * Per-writer [SyncCatchUpStatus] relation (`ios-sync-activity-indicator.md` §3.1) — see
         * [syncCatchUpStatuses]. ⚠ Owes no version bump, for [RUNTIME_IDENTITY]'s reason:
         * a new non-`Syncable` type reshapes nothing, and a bump would pull-fence every device
         * not yet updated over a local status row.
         */
        const val CATCH_UP = "$ID/catchUp"

        /**
         * Per-writer [SyncCatchUpHeartbeat] relation (`ios-sync-activity-indicator.md` §12) — see
         * [syncCatchUpHeartbeats]. ⚠ Owes no version bump, for [CATCH_UP]'s reason; and a new
         * type rather than a field of [SyncCatchUpStatus], which would owe one.
         */
        const val CATCH_UP_HEARTBEAT = "$ID/catchUpHeartbeat"
    }

    object Collections {
        const val PENDING_BLOCKS = "tenshu/sync/collections/pendingBlocks"

        /** Outbound queue for tombstones awaiting relay push (mirror of [PENDING_BLOCKS]). */
        const val PENDING_TOMBSTONES = "tenshu/sync/collections/pendingTombstones"

        /**
         * Durable intent-journal of in-progress DataId migrations (`file-content-addressing.md`
         * §3.3.4). Each element is a [org.tenshu.sync.common.RekeyMigrationMarker];
         * `DataIdMigrator` adds one before retiring an old key and removes it when done, so a
         * marker surviving to the next startup is an interrupted migration to resume + complete.
         */
        const val PENDING_REKEY_MIGRATIONS = "tenshu/sync/collections/pendingRekeyMigrations"

        /**
         * Second phase of that journal: the old key's **local** retirement is done (metadata swept,
         * row deleted) and only its tombstone is still owed, because there was no realm to name it
         * in. Elements are the same [org.tenshu.sync.common.RekeyMigrationMarker].
         *
         * ⚠⚠ The split is not bookkeeping. A marker in [PENDING_REKEY_MIGRATIONS] authorises a
         * *delete* of the old key on replay; one here authorises only an *emit*. Kept in one
         * journal, a marker retained across a locked realm is indistinguishable from a crash before
         * the delete — and replaying the delete against a row the user has since created at that
         * same path destroys it, locally and (via the path-derived wire name) on every peer.
         */
        const val PENDING_REKEY_TOMBSTONES = "tenshu/sync/collections/pendingRekeyTombstones"

        /**
         * Durable staging journal of the task-scoped sync lane
         * (`core-background-processing.md` §10.3 D6). Each element is a
         * [org.tenshu.sync.SyncDelta] a lane has staged but not yet sealed
         * into a [org.tenshu.sync.DeltaBatchPayload] block. Local-only, never
         * on the wire. A checkpoint removes the deltas it sealed; entries
         * surviving to the next startup (crash / keys-vanish between
         * checkpoints) are replayed as a final batch by
         * `SyncObserver.replaySyncLaneJournal`, so staged rows are never
         * stranded locally (the locked-save gap class). Shared across lanes —
         * on replay, batch composition is policy, not correctness, so all
         * lingering deltas seal together.
         */
        const val PENDING_LANE_DELTAS = "tenshu/sync/collections/pendingLaneDeltas"

        /**
         * Durable intent-journal of file-content emits deferred by the metered **content** gate
         * (`sync-metered-plane-split.md` §4). Each element is a
         * [org.tenshu.sync.common.PendingContentEmit]: `SyncObserver.emitFileContent` adds one
         * instead of `PUT`ting chunks while the gate is closed, and the scheduler's content drain
         * removes it once the bytes reach the content plane.
         *
         * Holds **intents, never bytes or ciphertext** — the content is already durable in
         * `FileDatabase` and is re-read at drain time, so this adds no second at-rest copy of user
         * data. Unlike the metadata queues ([PENDING_BLOCKS] and friends) it is not an outbound
         * queue of ready-to-send payloads but a to-do list re-resolved against current state.
         */
        const val PENDING_CONTENT_EMITS = "tenshu/sync/collections/pendingContentEmits"

        /**
         * Device-local: each pending tombstone's cascade (`PendingTombstoneCascade` in
         * `:sync-service-common`), keyed by its `blockId`, until the drain has handled that push
         * (`content-plane-cr2-b3-wire-spec.md` §3.2). Holds wire names (HMACs) only, never paths
         * or content.
         */
        const val PENDING_TOMBSTONE_CASCADES = "tenshu/sync/collections/pendingTombstoneCascades"

        /**
         * Device-local: the reaps this device owes `POST /sync/content/reap` (`PendingReapRemoval` in
         * `:sync-service-common`), one per reaped wire name, until the relay accepts them
         * (`content-plane-cr2-b3-wire-spec.md` §3.3).
         * Holds wire names (HMACs) and HLCs only.
         */
        const val PENDING_REAP_REMOVALS = "tenshu/sync/collections/pendingReapRemovals"
    }
}
