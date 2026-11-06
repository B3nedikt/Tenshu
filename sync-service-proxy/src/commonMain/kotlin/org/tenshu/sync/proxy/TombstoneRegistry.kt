package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.util.compare
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.id
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.common.LocalWriteMetadata
import org.tenshu.sync.common.PersistedTombstone
import org.tenshu.sync.common.ReapWatermark
import org.tenshu.sync.common.SubtreeReapOrdering
import org.tenshu.sync.common.ResolvedDataId
import org.tenshu.sync.common.TombstoneTypeCounts
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncMetadata

/**
 * Tracks tombstones (DeleteBlock observations) per-entity-id-hash.
 *
 * Three roles:
 *  1. Persisted record of `idHash → highestTombstoneHlc`, used by [SyncProcessor.mergeData] to
 *     short-circuit any incoming SyncBlock whose HLC is older than the tombstone (the entity
 *     is dead at that HLC and the update should be ignored).
 *  2. Reverse index of `idHash → DataId`, populated via [DataObserver] hooks on every local
 *     save. This lets [SyncProcessor.processDeleteBlock] resolve the local DataId for an incoming
 *     tombstone (and [FileContentManifestApplier] resolve the target of an on-demand content
 *     manifest) so the update can be applied to the local DB.
 *  3. Durable record of `idHash → HLC of the last **local** write` ([LocalWriteMetadata]), also
 *     written from [onDataSaved]. `SyncProcessor.processDeleteBlock`'s order-independence guard
 *     compares an incoming tombstone against the entity's last write, and [SyncMetadata] — the
 *     only record before this one — covers ingested writes alone. See [recordLocalWrite].
 *
 * The reverse index is **durable**: each mapping is persisted as a local-only [ResolvedDataId]
 * record (keyed by idHash), written from [onDataSaved] and removed in [onDataDeleted]. The
 * in-memory [idHashToDataId] map is a write-through cache rebuilt lazily by [resolveDataId] on
 * a miss. Persistence is required for binary content sync: a file whose metadata synced in an
 * earlier session must still resolve its DataId on the next launch so its bytes can be fetched
 * on open — an in-memory-only cache (the prior shape) lost that mapping across restart and the
 * manifest apply silently no-op'd.
 */
class TombstoneRegistry(
    private val executionContext: ExecutionContext,
    /**
     * EI-1: an entity's relay-visible name (`sync-entity-identity.md`).
     *
     * ⚠⚠ **Required, with no default, and that is the point.** This index LOOKS local — a string to
     * a `DataId`, never leaving the device — but its consumers (`SyncProcessor.mergeData`,
     * `FileContentManifestApplier`) resolve hashes that **arrived from the relay**. Key it one way
     * while peers name entities another and every inbound tombstone misses, silently, with both
     * halves looking healthy. A default let a caller forget; a required parameter cannot be.
     */
    private val wireIdFor: (DataId) -> String?,
    /**
     * Stamps a local event — `syncManager.hlcManager.tick()` in production. Called on every
     * **local** write ([recordLocalWrite]) and every **local** delete ([onDataDeleted]'s
     * short-circuit tombstone, see [localTombstones]). `null` (the default) disables both
     * recordings — kept for direct-construction tests that exercise neither; production wiring
     * (`initSyncService`) always supplies it.
     *
     * ⚠⚠ **`tick()`, not `current()`.** A local write is a local event and needs a position in
     * the total order, exactly like the `SyncBlock` the same save emits. `current()` returns
     * whatever the clock last absorbed, which is `(0, 0)` until something ticks or a peer block
     * arrives — so with `current()` a device's first local writes would be stamped at HLC 0 and
     * every tombstone would beat them. It also made the stamp depend on observer registration
     * order and on `syncManager.isEnabled()` (a disabled emitter never ticks), neither of which
     * a data-loss guard should rest on.
     */
    private val localEventHlc: (() -> HybridLogicalClock)? = null
) : DataObserver, SubtreeReapOrdering {

    private val tombstones = mutableMapOf<String, HybridLogicalClock>()
    private val idHashToDataId = mutableMapOf<String, DataId>()

    /**
     * Short-circuit HLCs for entities this device deleted **locally** without emitting a
     * relay tombstone (a `deleteData` with no [TombstoneEmitter.emit] — e.g.
     * `SharedRealmGrantManager.deletePendingGrant`, or `DeleteFileImplementation`'s recursive
     * child deletes). Without these, a create block this device previously pushed and then
     * pulls back (relay echo) finds no tombstone in [tombstoneHlcFor], so
     * [SyncProcessor.resolveConflictAndSave] re-applies it and the deleted row is resurrected.
     *
     * Kept **separate** from [tombstones] on purpose: these are not real (pushable, persisted,
     * epoch-counted) tombstones. They must NOT feed [typeCounts] — `SyncManager.currentRealmEpoch`
     * derives the realm epoch from `tombstoneCountForType(Device.ID)`, and a local delete is not
     * a revocation. They are also in-memory only, like [idHashToDataId]: lost across restart,
     * which is fine — the row is gone from the DB too and a restarted device pulls from its
     * advanced cursor, so the same create is not re-pulled within the resurrection window.
     */
    private val localTombstones = mutableMapOf<String, HybridLogicalClock>()

    /**
     * Wire names reaped in this process ([recordReap]) and not saved since. Their delete must not stamp
     * the "now" marker in [localTombstones]: that is a per-device point, while the durable
     * [ReapWatermark] holds the one point every device shares (§10.12 Q4, revised).
     *
     * ⚠⚠ **Not consumed by the first delete notification.** A delete can reach this observer more than
     * once (measured in `ReapOrderingTest`: every delete arrived twice), and a set consumed on the
     * first left the second to stamp the very marker this exists to suppress. An entry leaves only
     * when the entity is saved again ([onDataSaved]): a recreate, whose later local delete is an
     * ordinary one. Bounded by the entities reaped in this process.
     */
    private val reapsInFlight = mutableSetOf<String>()

    // Per-target-type tombstone count. `SyncManager.currentRealmEpoch` reads this with
    // `Device.ID` to derive the realm epoch (docs/adr/0007-sync-device-management.md §"Realm-state
    // data" — derived-cache shape). Maintained in-memory + rebuilt from the persisted
    // store on init alongside the `tombstones` map.
    private val typeCounts = mutableMapOf<String, Int>()

    // All mutating + reading methods enter [Database.transaction] so the
    // in-memory maps stay consistent with each other and with the durable
    // per-key rows behind them. Observer fanout is unlocked
    // ([ExecutionContext.saveData] releases the DB lock before invoking
    // observers), so without this two `Dispatchers.Default` workers could
    // race on a plain `mutableMapOf` and corrupt internals. Using the
    // existing reentrant DB lock (rather than a private one) keeps the
    // registry's invariants co-managed with the database, and the
    // `database.saveData` call inside [record] enters the same lock
    // re-entrantly — no contention amplification beyond what a row write
    // already pays.
    /** Whether [typeCounts] has been loaded from its durable [TombstoneTypeCounts] row. */
    private var countsLoaded: Boolean = false

    /**
     * ⚠⚠ **No store reads here.** This constructor runs inside `initSyncService`, which mints the
     * IPC session — so anything it does blocks every client's first read. Tombstones are point
     * lookups ([PersistedTombstone]) and the type counts load on first use ([ensureCountsLoaded]).
     */
    init {
        executionContext.database.transaction {
            executionContext.addDataObserver(this)
        }
    }

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val dataId = executionContext.getDataId(data) ?: return
        // Don't index our own durable sidecars (they have DataIds too) — avoids churn and any
        // chance of re-entrancy. ⚠ They are written at the `Database` level, which does not fire
        // observers, so this is belt-and-braces rather than the only guard.
        if (dataId.type.get() in SIDECAR_TYPES) return
        executionContext.database.transaction {
            val idHash = wireIdFor(dataId) ?: return@transaction
            idHashToDataId[idHash] = dataId
            // A reaped entity saved again is a recreate; its next local delete is an ordinary one.
            reapsInFlight.remove(idHash)
            // Persist the mapping so it survives a restart. Written at the [Database] level
            // (not [ExecutionContext.saveData]) so it does not re-fire observers — no
            // recursion back into this hook.
            executionContext.database.saveData(
                ResolvedDataId.create(executionContext.context, idHash, dataId)
            )

            // ⚠⚠ The tombstone guard's other half. `SyncMetadata` records the last *ingested*
            // write, so without this row a local write is invisible to
            // [SyncProcessor.processDeleteBlock] and a tombstone older than it deletes a live
            // entity — the "ghost file" data loss. Ingest-origin saves are skipped: those are
            // exactly what `SyncMetadata` already covers.
            //
            // ⚠ Gated on [Syncable] so this record's population tracks *synced* entities
            // rather than every row this device ever writes. A `DeleteBlock` addresses an
            // entity, and all seven production emit sites target a syncable entity type
            // (`RevokeDevice`, `DeleteRealmData`, `DeleteFile`, `DataIdMigrator`,
            // `DeleteWorkout`, and the retention sweeps in `FileServiceProxyInitialization`
            // / `HealthProxyInitialization`), so a non-syncable row's last-write HLC has no
            // reader — it would be one store row per local write, bought for nobody.
            //
            // ⚠⚠ Standing assumption, not construction: an emit site added later against a
            // NON-syncable type would be silently unprotected — no stamp on the write side,
            // nothing for the guard to read. Adding an emit site owes a check that its target
            // type carries [Syncable].
            val hlcSource = localEventHlc
            if (origin != WriteOrigin.SYNC_INGEST && hlcSource != null && isSyncable(data)) {
                recordLocalWrite(idHash, hlcSource())
            }
        }
    }

    /**
     * Whether [data]'s own schema carries [Syncable] — the same gate
     * `SyncObserver.processSavedData` applies before emitting a block for it, read the same way
     * (registered schema first, the value's own as the fallback).
     *
     * ⚠ Deliberately the entity-level arm only. `SyncObserver` also emits for an entity's
     * syncable *properties*, but a tombstone addresses the entity, so mirroring that second
     * loop here would cost a per-property schema lookup on every local save to protect a row
     * nothing can tombstone.
     */
    private fun isSyncable(data: Data): Boolean {
        val type = if (data is Entity<*>) data.type.get() else data.id
        val schema = executionContext.schemaProvider.getSchema(type) ?: data.schema
        return schema.hasTrait(Syncable.ID)
    }

    /**
     * Stamp [idHash]'s last local write at [clockHlc]. Highest-HLC-wins, so an out-of-order
     * observer fanout can never walk the record backwards.
     *
     * ⚠ Durable, unlike [localTombstones]'s in-memory markers, and the asymmetry is deliberate:
     * losing a local-delete marker across a restart *resurrects* a row, which the next tombstone
     * pull corrects, while losing a local-write record *deletes* one. A restart is precisely when
     * the risk is live — `SyncScheduler.pullSyncLogBefore` backfills below the cursor floor and
     * re-serves tombstones the device has already seen.
     */
    private fun recordLocalWrite(idHash: String, clockHlc: HybridLogicalClock) {
        val existing = localWriteHlc(idHash)
        if (existing != null && existing.compare(clockHlc) >= 0) return
        executionContext.database.saveData(
            LocalWriteMetadata.create(
                context = executionContext.context,
                targetEntityIdHash = idHash,
                hlc = clockHlc,
            )
        )
    }

    /**
     * The HLC of the last write this device made to [idHash] *itself*, or null if it has only
     * ever ingested the entity (or predates this record). Read by
     * [SyncProcessor.processDeleteBlock] alongside the entity's `SyncMetadata`; the guard
     * compares the tombstone against whichever is higher.
     *
     * ⚠ Not cached in memory: absence is the common answer for the ingest-heavy population, and
     * a miss cache would grow one entry per entity ever looked up — the residency problem
     * [PersistedTombstone] exists to avoid. Reads land on the tombstone path only, which is rare.
     */
    fun localWriteHlc(idHash: String): HybridLogicalClock? =
        executionContext.database.transaction {
            val recordId = DataId.StringDataId.create(
                context = executionContext.context,
                type = LocalWriteMetadata.ID,
                keyValue = idHash,
            )
            (executionContext.database.getData(recordId) as? LocalWriteMetadata)?.hlc
        }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        if (dataId.type.get() in SIDECAR_TYPES) return
        executionContext.database.transaction {
            val idHash = wireIdFor(dataId) ?: return@transaction
            idHashToDataId.remove(idHash)
            // Drop the durable mapping too (Database-level delete: no observer re-fire).
            executionContext.database.deleteData(
                DataId.StringDataId.create(
                    context = executionContext.context,
                    type = ResolvedDataId.ID,
                    keyValue = idHash
                )
            )
            // The entity is gone, so its last-local-write stamp is meaningless — and a
            // recreate writes a fresh one through [onDataSaved]. Dropping it keeps the sidecar
            // population proportional to live entities rather than to every id ever written.
            executionContext.database.deleteData(
                DataId.StringDataId.create(
                    context = executionContext.context,
                    type = LocalWriteMetadata.ID,
                    keyValue = idHash
                )
            )

            // Local deletes that don't emit a relay tombstone leave no entry in [tombstones],
            // so a re-pulled create (relay echo of our own earlier push, or a peer's create we
            // ingested then deleted locally) would resurrect the row. Stamp a local-only
            // short-circuit so [resolveConflictAndSave] drops it. Skip ingest-origin deletes:
            // those came from `processDeleteBlock`, which already called [record] with the
            // authoritative DeleteBlock HLC.
            val hlcSource = localEventHlc
            // ⚠ A reaped descendant already holds its durable watermark at T ([recordReap]); a "now"
            // marker on top would refuse blocks in (T, now] here while every other device accepts
            // them — the split the revised Q4 exists to remove.
            val reaped = idHash in reapsInFlight
            if (origin != WriteOrigin.SYNC_INGEST && hlcSource != null && !reaped) {
                recordLocalDelete(idHash, hlcSource())
            }
        }
    }

    /**
     * Stamp a local-only short-circuit tombstone for [idHash]. Highest-HLC-wins, and never
     * lower than the entity's last applied block ([SyncMetadata.hlc]) — that block is exactly
     * the create a relay echo re-delivers, so the recorded HLC must be ≥ it for the
     * `tombstoneHlc.compare(block.hlc) >= 0` short-circuit to fire. [clockHlc] (the device's
     * current HLC) already dominates locally-stamped/ingested-personal blocks; the metadata
     * max additionally covers a shared-realm-ingested entity (whose create advanced the realm's
     * own clock, not the personal one). Touches neither [tombstones] nor [typeCounts].
     */
    private fun recordLocalDelete(idHash: String, clockHlc: HybridLogicalClock) {
        val metadataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = SyncMetadata.ID,
            keyValue = idHash
        )
        val metadataHlc = (executionContext.database.getData(metadataId) as? SyncMetadata)?.hlc
        val recordHlc =
            if (metadataHlc != null && metadataHlc.compare(clockHlc) > 0) metadataHlc else clockHlc

        val existing = localTombstones[idHash]
        if (existing == null || recordHlc.compare(existing) > 0) {
            localTombstones[idHash] = recordHlc
        }
    }

    /**
     * Records a [DeleteBlock] in the registry. The tombstone is persisted via the
     * underlying [Database] so the in-memory map can be rebuilt across restarts.
     * If the registry already holds a tombstone for this entity at an HLC ≥ the
     * incoming one, the call is a no-op.
     */
    fun record(block: DeleteBlock): Unit = executionContext.database.transaction {
        ensureCountsLoaded()
        val idHash = block.targetEntityIdHash.get()
        val current = persistedTombstoneHlc(idHash)
        // First-observation-wins for the type-count: only bump when the entity is newly
        // tombstoned (new idHash). A higher-HLC re-record for the same entity is a
        // refresh, not a separate revocation.
        val isNew = current == null
        if (current != null && block.hlc.compare(current) <= 0) return@transaction

        tombstones[idHash] = block.hlc
        refreshDurablePoint(idHash, block.hlc)
        if (isNew) {
            val type = block.targetEntityType.get()
            typeCounts[type] = (typeCounts[type] ?: 0) + 1
            writeTypeCounts()
        }
        // ⚠ One small per-key row, never a list: a resident array of every tombstone grew to
        // 10,523 members and cost 6.3 s to resolve at launch. Database-level write (not
        // `ExecutionContext.saveData`) so it does not re-fire observers — same as the
        // `ResolvedDataId` sidecar above.
        executionContext.database.saveData(
            PersistedTombstone.create(
                context = executionContext.context,
                targetEntityIdHash = idHash,
                hlc = block.hlc,
                targetEntityType = block.targetEntityType.get(),
            )
        )
    }

    /**
     * Highest tombstone HLC observed for [idHash], or null if none. Considers both real
     * (ingested/emitted) tombstones and local-delete short-circuit markers ([localTombstones]),
     * returning whichever is higher — so a locally-deleted-but-not-relay-tombstoned entity is
     * still protected from resurrection by a re-pulled create.
     */
    fun tombstoneHlcFor(idHash: String): HybridLogicalClock? =
        executionContext.database.transaction {
            // ⭐ CR-2 B3b: the reap watermark counts too, so a reaped descendant refuses blocks at or
            // below T after a restart as well as before it (audit gap 11).
            latest(durablePoint(idHash), localTombstones[idHash])
        }

    /**
     * The later of [idHash]'s persisted tombstone and its reap watermark, cached once either exists.
     *
     * ⚠ Cached only on a hit, like [persistedTombstoneHlc], so the cache stays bounded by tombstoned and
     * reaped entities; a resolved point is then answered with no read at all (the cost
     * `aLookupStillCostsOneReadAndCachesIt` pins). Every writer of either half refreshes a cached
     * entry, so it cannot go stale. A miss costs two point reads.
     */
    private fun durablePoint(idHash: String): HybridLogicalClock? {
        durablePoints[idHash]?.let { return it }
        val point = latest(persistedTombstoneHlc(idHash), reapWatermarkHlc(idHash)) ?: return null
        durablePoints[idHash] = point
        return point
    }

    private val durablePoints = mutableMapOf<String, HybridLogicalClock>()

    private fun refreshDurablePoint(idHash: String, point: HybridLogicalClock) {
        val cached = durablePoints[idHash] ?: return
        if (point.compare(cached) > 0) durablePoints[idHash] = point
    }

    private fun latest(a: HybridLogicalClock?, b: HybridLogicalClock?): HybridLogicalClock? = when {
        a == null -> b
        b == null -> a
        else -> if (b.compare(a) > 0) b else a
    }

    // ---- SubtreeReapOrdering (CR-2 B3b, §10.12 Q4 revised and accepted 2026-10-02) ----

    override fun deletionPointOf(dataId: DataId): HybridLogicalClock? =
        executionContext.database.transaction {
            val idHash = wireIdFor(dataId) ?: return@transaction null
            // ⚠ The real tombstone or the reap point, never the local "now" marker: only those two are
            // the same on every device. A subfolder reaped in a cascade passes its T to its children.
            persistedTombstoneHlc(idHash) ?: reapWatermarkHlc(idHash)
        }

    override fun versionOf(dataId: DataId): HybridLogicalClock? =
        executionContext.database.transaction {
            val idHash = wireIdFor(dataId) ?: return@transaction null
            val ingested = (
                executionContext.database.getData(
                    DataId.StringDataId.create(
                        context = executionContext.context,
                        type = SyncMetadata.ID,
                        keyValue = idHash,
                    )
                ) as? SyncMetadata
                )?.hlc
            latest(ingested, localWriteHlc(idHash))
        }

    override fun recordReap(dataId: DataId, point: HybridLogicalClock): Unit =
        executionContext.database.transaction {
            val idHash = wireIdFor(dataId) ?: return@transaction
            reapsInFlight += idHash
            recordReapPoint(idHash, point)
        }

    /**
     * Persists [point] as [idHash]'s reap watermark, keeping the later of two, and owes the relay the
     * matching `/sync/content/reap` removal ([ReapRemovalOutbox]). Called for every reaped descendant
     * ([recordReap]) and for a recursive delete's own cascade, whose wire names also ride its
     * tombstone — redundant there, and idempotent at the relay.
     */
    fun recordReapPoint(idHash: String, point: HybridLogicalClock): Unit =
        executionContext.database.transaction {
            val existing = reapWatermarkHlc(idHash)
            if (existing != null && existing.compare(point) >= 0) return@transaction
            refreshDurablePoint(idHash, point)
            // Database-level write, like [PersistedTombstone]: no observer re-fire, never synced.
            executionContext.database.saveData(
                ReapWatermark.create(context = executionContext.context, targetEntityIdHash = idHash, hlc = point)
            )
            // ⭐ CR-2 B3d (wire spec §3.3): the relay owes the same ordering point. Enqueued in THIS
            // transaction, so a watermark never exists without its removal owed; only when the point
            // moved, because an earlier enqueue already covers an equal or older one.
            ReapRemovalOutbox(executionContext).enqueue(idHash, point)
        }

    /** The durable reap point for [idHash], or null. ⚠ A miss is not cached, as [persistedTombstoneHlc]. */
    private fun reapWatermarkHlc(idHash: String): HybridLogicalClock? =
        (
            executionContext.database.getData(
                DataId.StringDataId.create(
                    context = executionContext.context,
                    type = ReapWatermark.ID,
                    keyValue = idHash,
                )
            ) as? ReapWatermark
            )?.hlc

    /**
     * Highest observed tombstone HLC for [idHash] — in-memory cache first, then the durable
     * [PersistedTombstone] row, warming the cache on a hit.
     *
     * The read-through is what lets startup skip loading anything: exactly the shape
     * [resolveDataId] already uses for the reverse index. ⚠ A miss is not cached — absence is
     * the overwhelmingly common answer (most entities are never tombstoned), so caching it would
     * grow a map with one entry per entity ever looked up, which is the residency problem this
     * change exists to remove.
     */
    private fun persistedTombstoneHlc(idHash: String): HybridLogicalClock? =
        tombstones[idHash] ?: loadPersistedTombstone(idHash)?.also { tombstones[idHash] = it }

    private fun loadPersistedTombstone(idHash: String): HybridLogicalClock? {
        val recordId = DataId.StringDataId.create(
            context = executionContext.context,
            type = PersistedTombstone.ID,
            keyValue = idHash,
        )
        return (executionContext.database.getData(recordId) as? PersistedTombstone)?.hlc
    }

    /** Loads [typeCounts] from its durable row on first use — off the launch path. */
    private fun ensureCountsLoaded() {
        if (countsLoaded) return
        loadTypeCounts()?.let { typeCounts.putAll(it) }
        countsLoaded = true
    }

    private fun loadTypeCounts(): Map<String, Int>? =
        (
            executionContext.database.getData(
                DataId.StringDataId.create(
                    context = executionContext.context,
                    type = TombstoneTypeCounts.ID,
                    keyValue = TombstoneTypeCounts.SINGLETON_KEY,
                )
            ) as? TombstoneTypeCounts
            )?.let { TombstoneTypeCounts.decode(it.counts.get()) }

    private fun writeTypeCounts() {
        executionContext.database.saveData(
            TombstoneTypeCounts.create(executionContext.context, typeCounts)
        )
    }

    /**
     * Number of distinct entities of type [typeId] for which a tombstone has been
     * observed. Used by [SyncManager.currentRealmEpoch] (queried with `Device.ID`)
     * to derive the realm epoch.
     */
    fun tombstoneCountForType(typeId: String): Int =
        executionContext.database.transaction {
            ensureCountsLoaded()
            typeCounts[typeId] ?: 0
        }

    /**
     * Local DataId for [idHash], populated as a side effect of saves; null if unknown.
     *
     * On an in-memory cache miss (e.g. the first lookup after a restart, when the map starts
     * empty), falls back to the durable [ResolvedDataId] record and re-populates the cache —
     * so a mapping learned in a prior session still resolves.
     */
    fun resolveDataId(idHash: String): DataId? =
        executionContext.database.transaction {
            idHashToDataId[idHash]
                ?: loadResolvedDataId(idHash)?.also { idHashToDataId[idHash] = it }
        }

    /**
     * Ensure the reverse index resolves [dataId] — writing both the in-memory cache and
     * the durable [ResolvedDataId] record if the mapping is absent. Idempotent: a no-op
     * when [dataId] is already indexed (in memory or on disk).
     *
     * The reverse index is otherwise populated *only* as a side effect of [onDataSaved],
     * i.e. a live write. That leaves a structural gap: startup hydration loads entities
     * from the (non-enumerable) store straight into the cache **without** firing the save
     * observer, and nothing else ever rebuilds the index — so an entity that exists in the
     * DB but wasn't written this session can have no `idHash → DataId` mapping, and any
     * lookup for it ([FileContentManifestApplier] resolving an on-demand content manifest)
     * silently fails with no path to self-repair.
     *
     * This is the seam that closes that gap from the read side: a caller that already holds
     * an entity's [DataId] — the OnDemand byte-fetch path does, it is handed the entity id
     * by `Maybe.get()` — can repair the mapping on the exact access that needs it, turning a
     * write-only index that can silently drift from the entities it indexes into one that
     * heals on demand.
     */
    fun rememberDataId(dataId: DataId): Unit = executionContext.database.transaction {
        if (dataId.type.get() == ResolvedDataId.ID) return@transaction
        val idHash = wireIdFor(dataId) ?: return@transaction
        // resolveDataId checks the cache and the durable record (and warms the cache on a
        // durable hit), so this writes only when the mapping is genuinely missing.
        if (resolveDataId(idHash) != null) return@transaction
        idHashToDataId[idHash] = dataId
        // Database-level write (not ExecutionContext.saveData): no observer re-fire, exactly
        // as onDataSaved persists the mapping.
        executionContext.database.saveData(
            ResolvedDataId.create(executionContext.context, idHash, dataId)
        )
    }

    private fun loadResolvedDataId(idHash: String): DataId? {
        val recordId = DataId.StringDataId.create(
            context = executionContext.context,
            type = ResolvedDataId.ID,
            keyValue = idHash
        )
        return (executionContext.database.getData(recordId) as? ResolvedDataId)?.targetDataId
    }


    private companion object {
        /** Types this registry owns; indexing them would index its own bookkeeping. */
        val SIDECAR_TYPES = setOf(
            ResolvedDataId.ID,
            LocalWriteMetadata.ID,
            PersistedTombstone.ID,
            TombstoneTypeCounts.ID,
        )
    }
}
