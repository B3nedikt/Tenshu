package org.tenshu.file.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.File
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.sync.FileSyncSetting
import org.tenshu.core.file.sync.PathSyncOverrideSetAdapter
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.id
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.time.Instant
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Setting
import org.tenshu.core.common.util.compare
import org.tenshu.sync.common.SubtreeReapOrdering
import org.tenshu.sync.common.TombstoneEmitterHolder
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.FileHistory
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.common.relation.relationReference

/**
 * Initialises the file service's proxy-side observers.
 *
 * Six of the service's seven derived relations are **declared**, not written
 * (`core-relation-system.md` §9.2.1 / R2), and served by the one generic
 * `RelationMaintainer` [installRelationMaintainer] installs:
 *
 *  - `MODIFICATIONS` / `SHARES` / `PLACEMENTS` on [FileService.Schema]
 *    (`serviceDataRelation(id, type, maintained = true)`);
 *  - `Folder.FILES`, `File.FileHistory` and the shared-realm children tree as
 *    `relationMembership(...)` specs in `fileServiceCommonModule` — the
 *    entity-scoped shapes no service schema can carry.
 *
 * What is left here is the pair of **policies** that are not membership at all: the history **retention cap** (§4.6) and the **tombstone cascade**
 * (§4.4). They keep their own observer, [FileHistoryPolicyObserver].
 *
 * `FileSyncSettingsRelationMaintainer` is the seventh: a 2→1 *fold* that mints
 * a projection row from two `DeviceSetting` siblings, not a mirror of a saved
 * entity — see its KDoc.
 *
 * @param tombstoneEmitterHolder source of the [org.tenshu.sync.common.TombstoneEmitter]
 *   used by the retention cap (§4.6) to propagate trims of over-cap history. Read
 *   lazily at trim time (the emitter is set later in the same DI init lambda).
 *   `null` (test harnesses / pre-sync wirings) still trims the **local** store —
 *   only the cross-device tombstone propagation is skipped.
 */
fun ExecutionContext.initFileService(
    tombstoneEmitterHolder: TombstoneEmitterHolder? = null,
    /**
     * Source of the plaintext dropped by [DeletedFolderSubtreeReaper] when a folder's
     * descendants are reaped. `null` (test harnesses / pre-sync wirings) still deletes the
     * descendant **entities** — only their bytes are left behind.
     */
    fileDatabase: FileDatabase? = null,
    /**
     * Store handle for the orphaned-file sweep ([OrphanedFileSweeper]). `null` (test harnesses / wirings with no store
     * to sweep) simply returns no sweeper, and the caller has nothing to schedule.
     */
    objectStore: ObjectStore? = null,
    /**
     * Notes the paths an **ingested** delete strands plaintext at, for `OrphanedBytesSweep`
     * (`docs/tech-debt.md`). `null` — the default — records nothing: a delete received from another
     * device then frees that device's disk and nobody else's.
     *
     * ⚠ Best-effort by contract: ingest must not acquire a failure mode for a housekeeping note.
     */
    orphanedBytesRecorder: OrphanedBytesRecorder? = null,
    /**
     * Rows the orphaned-file sweep may *examine* per pass. `null` takes
     * [OrphanedFileSweeper.EXAMINE_PAGE], which suits a store with a by-type index (see that
     * constant).
     */
    sweepExamineLimit: Int? = null,
): OrphanedFileSweeper? {
    val rootId = DataId.StringDataId.create(context = this.context, type = File.ID, keyValue = "/")
    if (database.getData(rootId) == null) {
        saveData(
            Entity.create<Folder>(
                context = this.context,
                composite = Folder.create(this.context, path = "/", files = emptyList()),
            ) {
                set(File.Size, 0L)
                set(File.LastModifiedAt, 0L)
                set(File.CreatedAt, 0L)
            }
        )
    }
    installRelationMaintainer()
    addDataObserver(FileHistoryPolicyObserver(this, tombstoneEmitterHolder))
    addDataObserver(DeletedFolderSubtreeReaper(this, fileDatabase, objectStore, tombstoneEmitterHolder))
    addDataObserver(FileSyncSettingsRelationMaintainer(this))
    // Records the paths an INGESTED delete leaves plaintext behind at — `docs/tech-debt.md` "A
    // delete frees the deleting device's disk and no one else's". ⚠ Null recorder (the default)
    // records nothing: the bytes stay for ever.
    orphanedBytesRecorder?.let { addDataObserver(IngestedDeleteRecorder(it)) }

    // ⚠⚠ One-shot re-arm for rows a reaper stranded without re-arming the sweep. Such a store already
    // reads `SWEEP_COMPLETE`, which is terminal, so without this it keeps its orphans forever — and orphans occupy destination paths, so the affected Synced Folders bucket can never be
    // rebuilt. Spent once per [ObjectStore.FILE_SWEEP_REPAIR_VERSION], never on every launch.
    // ⚠ Under the DatabaseLock: no ObjectStore is thread-safe on its own.
    objectStore?.let { store ->
        val spent = database.transaction {
            if (store.loadOrphanedFileSweepRepair() == ObjectStore.FILE_SWEEP_REPAIR_VERSION) return@transaction false
            store.clearOrphanedFileSweepCursor()
            store.storeOrphanedFileSweepRepair(ObjectStore.FILE_SWEEP_REPAIR_VERSION)
            true
        }
        if (spent) {
            println(
                "OrphanedFileSweeper: one-shot repair v${ObjectStore.FILE_SWEEP_REPAIR_VERSION} " +
                    "spent — sweep re-armed to collect rows stranded after it completed"
            )
        }
    }

    // The orphaned-file sweep. Returned rather than self-scheduled: the sweep needs a tick, and the scheduler does not
    // exist yet at this point in the graph. The caller registers it via
    // `SyncScheduler.addSyncCycleHook`, the same seam `InviteResponsePickup` rides.
    // ⚠ Says which of the three states this wiring is in — created, or not created because no
    // store was supplied. Without it, "no sweeper" and "sweeper that never ran" look identical
    // from the logs.
    return objectStore
        ?.let {
            println(
                "OrphanedFileSweeper: created (caller must register it on the sync cycle), " +
                    "examineLimit=${sweepExamineLimit ?: OrphanedFileSweeper.EXAMINE_PAGE}"
            )
            OrphanedFileSweeper(
                executionContext = this,
                objectStore = it,
                examineLimit = sweepExamineLimit ?: OrphanedFileSweeper.EXAMINE_PAGE,
                tombstoneEmitterHolder = tombstoneEmitterHolder,
            )
        }
        ?: run {
            println("OrphanedFileSweeper: NOT created — initFileService got no ObjectStore")
            null
        }
}

/**
 * Deletes a folder's **descendants** when the folder entity itself is deleted — the half
 * of a recursive delete that does not travel between devices.
 *
 * `DeleteFileImplementation` emits exactly **one** tombstone, for the delete's root
 * ("one tombstone per delete *operation*, not per cascaded entity"), and cascades to the
 * children only in its own local store. A peer applying that tombstone therefore deletes
 * the folder row and nothing beneath it: every descendant entity — and, far more
 * expensively, every descendant's **plaintext bytes** — stays on disk forever, reachable
 * from nothing. For a deleted Synced-Folders backup destination that is the whole photo
 * bucket. This observer closes the gap by reproducing the originator's cascade wherever
 * the folder row dies.
 *
 * ⚠ It recurses **through `deleteData`**, not through itself: deleting a child fires this
 * same observer for the child, which reaps that child's array in turn. Depth is bounded
 * by the tree.
 *
 * ⚠ It reads the `FILES` **array object** rather than the folder entity's field, because
 * by the time an observer runs the entity row is already gone — the array is a separate
 * object under the folder's `EntityFieldDataId` and outlives it. That is also what makes
 * the observer inert on the local path: `DeleteFileImplementation` deletes the array
 * *before* the entity, so the read finds nothing and the cascade it already performed is
 * not repeated.
 *
 * ⚠ Children are deleted at the **caller's default origin, never `SYNC_INGEST`**, even
 * when the folder's own delete came from ingest. `TombstoneRegistry.onDataDeleted` skips
 * its local short-circuit marker for ingest-origin deletes (those already have a real
 * `DeleteBlock` recorded), and these cascaded children have no `DeleteBlock` of their
 * own — so marking them as ingest would leave every descendant unprotected against a
 * re-pulled create resurrecting it.
 *
 * ## ⭐ One ordering point per deleted folder (`content-plane-cr2-quorumless.md` §10.12 Q4)
 *
 * When sync supplies a [SubtreeReapOrdering] (through the emitter's holder), the cascade reaps by
 * the deleted folder's tombstone HLC **T**, the same point on every device and at the relay:
 *  - a descendant whose version here is **≤ T** (or that has none — a placeholder) is reaped, and its
 *    reap point is recorded first, so this device refuses any block for it at or below T for good;
 *  - a descendant **newer than T** — written after the delete, by a device that had not seen it — is
 *    **kept**: a live row on every device, with its edge at the relay. The sweep is re-armed so it is
 *    eventually removed by [OrphanedFileSweeper] with a real tombstone, consistently everywhere.
 *
 * ⚠⚠ Reaping every descendant whatever its version, each device at its own "now", makes devices
 * disagree about late blocks (one keeps a child another refuses), and lets the relay free content a
 * device still shows. With no ordering (no sync on this runtime) the cascade reaps every descendant.
 */
private class DeletedFolderSubtreeReaper(
    private val executionContext: ExecutionContext,
    private val fileDatabase: FileDatabase?,
    /**
     * Store handle used **only** to re-arm the orphaned-file sweep when this cascade degrades — see
     * [ObjectStore.clearOrphanedFileSweepCursor]. `null` strands the subtree and says so in the log.
     */
    private val objectStore: ObjectStore?,
    /** Source of the [SubtreeReapOrdering]; null, or an emitter without one, reaps every descendant. */
    private val tombstoneEmitterHolder: TombstoneEmitterHolder? = null,
) : DataObserver {

    override fun onDataSaved(data: Data, origin: WriteOrigin) = Unit

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        val fileId = dataId as? DataId.StringDataId ?: return
        if (fileId.type.get() != File.ID) return

        val arrayId = DataId.EntityFieldDataId.create(
            context = executionContext.context,
            type = TList.ID,
            parentEntity = fileId as DataId.EntityDataId,
            fieldRef = Folder.Fields.FILES,
        )
        val children = readChildren(arrayId)
        if (children.isEmpty()) return

        val ordering = tombstoneEmitterHolder?.emitter?.reapOrdering
        // T: the deleted folder's tombstone HLC, or — for a subfolder reaped in this same cascade — the
        // T it was reaped at. Null (no ordering, or nothing recorded) ⇒ every descendant is reaped.
        val point = ordering?.deletionPointOf(fileId)
        var kept = 0
        children.forEach { child ->
            val childId = executionContext.getDataId(child) as? DataId.StringDataId ?: return@forEach
            if (ordering != null && point != null) {
                val version = ordering.versionOf(childId)
                if (version != null && version.compare(point) > 0) {
                    // Newer than the delete: a live row everywhere. Never removed silently here.
                    kept++
                    return@forEach
                }
                // ⚠ Before the delete: the registry must know this is a reap at T, not a local delete
                // at "now", by the time the delete's observers run.
                ordering.recordReap(childId, point)
            }
            // Bytes first: a leaf's plaintext is what actually fills the device, and after
            // the entity is gone nothing else knows the path. A no-op for folders.
            fileDatabase?.deleteFile(childId.keyValue.get())
            executionContext.deleteData(childId)
        }
        // The array object is not reachable from anything now that the folder row is gone.
        executionContext.deleteData(arrayId)
        if (kept > 0) {
            // ⚠ The kept children are orphans now (their parent is gone). Hand them to the sweep, whose
            // delete is a real tombstone — the consistent final clear (§10.12 Q4).
            println("DeletedFolderSubtreeReaper: kept $kept descendant(s) newer than the delete; sweep re-armed")
            objectStore?.clearOrphanedFileSweepCursor()
        }
    }

    /**
     * Snapshot the members (`.toList()`) so deleting below does not mutate what we iterate.
     *
     * ⚠ Degrades to "no cascade" rather than throwing, because this runs inside sync ingest's
     * observer fanout — where an escaping throw takes down the whole tick, not just this delete.
     *
     * ⚠⚠ The folder row still dies, so **degrading strands the subtree**, and on a store whose
     * orphaned-file sweep already read `SWEEP_COMPLETE` nothing would ever collect it. That is why
     * this path re-arms the sweep. Top-level array reads skip a dangling member rather than raise
     * `Entry not found`; the branch stays armed because "no dangling members" is not the same claim
     * as "this read cannot fail".
     */
    private fun readChildren(arrayId: DataId.ReferencedDataId): List<Data> = runCatching {
        executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = arrayId,
                isEntity = false,
                elementSchemaId = File.ID,
            )
        )?.toList()
    }.getOrElse {
        println("WARN DeletedFolderSubtreeReaper: unreadable children array (${it.message}); descendants not reaped")
        // ⚠⚠ This branch is where orphans are BORN, and it is the only place that knows it. The
        // folder row is already gone; its descendants are now unreachable and nothing else will
        // notice. Hand them to the orphaned-file sweep by re-arming it — without this the store may
        // already read SWEEP_COMPLETE, which is terminal, and the subtree is stranded forever (a
        // whole photo bucket under a deleted `…/Camera`). Re-arming is cheap and idempotent;
        // the sweep is bounded, resumable and self-terminating, so the cost is one more traversal.
        objectStore?.clearOrphanedFileSweepCursor()
        null
    }.orEmpty()
}

/**
 * The two history **policies** that sit beside the declared membership
 * (`core-relation-system.md` §9.2, R2): neither half is expressible as a
 * `RelationSpec`.
 *
 * **Tombstone cascade** (§4.4): when a `File`/`Folder` entity is deleted —
 * locally (`DeleteFileImplementation`'s recursive `executionContext.deleteData`)
 * or on a joiner (`SyncProcessor.processDeleteBlock` applying the file's
 * tombstone) — its append-only history is deleted too. The file's single
 * tombstone covers both planes; no per-modification tombstone is emitted
 * (`SyncObserver.onDataDeleted` is a no-op), so the history dies with the file on
 * each device independently.
 *
 * **Retention cap** (§4.6): when a *locally-originated* write pushes a file's
 * history over [FileModification.RETENTION_CAP], the oldest
 * non-[CreatedFileModification] rows are trimmed (Created is the pinned floor
 * record). The trim is local-delete + a per-row tombstone via
 * [tombstoneEmitterHolder], so every device converges to the same bounded log and
 * the relay GCs the dropped blocks. Only `USER`-origin saves trim — peers drop
 * their copies via the propagated tombstone, never by re-trimming on ingest
 * (which would double the tombstone traffic).
 *
 * Both work by `executionContext.deleteData(<FileModification>)`, which fans out
 * to every observer — including the generic `RelationMaintainer`, which drops the
 * row from `MODIFICATIONS` *and* from that file's `File.FileHistory` array. So
 * this observer never touches an array itself; it only decides *which rows die*.
 */
private class FileHistoryPolicyObserver(
    private val executionContext: ExecutionContext,
    private val tombstoneEmitterHolder: TombstoneEmitterHolder? = null,
) : DataObserver {

    // Read-only: the generic maintainer owns writing this array. Derived from the
    // relation's declared spec so the two cannot disagree about `isEntity` /
    // `elementSchemaId` (`core-relation-system.md` D4).
    private val modificationsRef: ArrayDatabaseReference by lazy {
        executionContext.relationReference(FileService.Relations.MODIFICATIONS)
    }

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        if (!data.isFileModification()) return
        // Retention cap (§4.6): only the originating device trims; peers converge
        // via the propagated tombstone, so a sync-ingested row is never re-trimmed
        // here (that would double tombstone traffic).
        if (origin != WriteOrigin.USER) return
        enforceRetention(fileIdOf(data) ?: return)
    }

    /**
     * Trim a file's history down to [FileModification.RETENTION_CAP], dropping the
     * oldest non-`Created` rows. Each drop is a local `deleteData` (which clears
     * both the realm-wide and the per-file membership through the generic
     * maintainer) plus a per-row tombstone so other devices drop the same rows and
     * the relay GCs the blocks. Runs inside [onDataSaved]'s observer dispatch —
     * re-entrant-safe via the snapshot-iterate `ObserverRegistry`.
     */
    private fun enforceRetention(filePath: String) {
        val forFile = (executionContext.database.getArrayData(modificationsRef)?.toList()
            ?: return)
            .filter { fileIdOf(it) == filePath }
        var overBy = forFile.size - FileModification.RETENTION_CAP
        if (overBy <= 0) return

        // Oldest first; never drop the pinned Created floor record.
        for (row in forFile.sortedBy { timestampOf(it) }) {
            if (overBy <= 0) break
            if (typeIdOf(row) == CreatedFileModification.ID) continue
            val key = compositeKeyOf(row) ?: continue
            val rowId = DataId.StringDataId.create(
                context = executionContext.context,
                type = FileModification.ID,
                keyValue = key
            )
            executionContext.deleteData(rowId, origin = WriteOrigin.USER)
            // ⚠⚠ The id form: only the emitter can derive the relay-visible name
            // (`HMAC(K_entity, identity)`), and a locally-hashed one addresses an entity no peer
            // matches — silently, since both sides still sync.
            tombstoneEmitterHolder?.emitter?.emit(
                targetEntityId = rowId,
                targetEntityType = FileModification.ID,
            )
            overBy--
        }
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        val stringDataId = dataId as? DataId.StringDataId ?: return
        // A file/folder was deleted -> cascade-delete its history (§4.4). The
        // FileModification.ID case needs nothing here: dropping the deleted row's
        // membership is the generic maintainer's job on both arrays.
        if (stringDataId.type.get() == File.ID) {
            cascadeDeleteHistory(stringDataId.keyValue.get(), origin)
        }
    }

    private fun cascadeDeleteHistory(filePath: String, origin: WriteOrigin) {
        // Snapshot the log (`.toList()`) so deleting rows below doesn't mutate the
        // collection we're iterating.
        val rows = executionContext.database.getArrayData(modificationsRef)?.toList() ?: return
        rows.forEach { row ->
            if (fileIdOf(row) != filePath) return@forEach
            val key = compositeKeyOf(row) ?: return@forEach
            // `deleteData` fans back to every observer — re-entrant-safe via the
            // snapshot-iterate ObserverRegistry. No tombstone is emitted
            // (SyncObserver.onDataDeleted is a no-op); the parent file's tombstone
            // already drives this cascade on every device.
            executionContext.deleteData(
                DataId.StringDataId.create(
                    context = executionContext.context,
                    type = FileModification.ID,
                    keyValue = key
                ),
                origin = origin
            )
        }
    }

    private fun Data.isFileModification(): Boolean {
        val typeId = (this as? Entity<*>)?.type?.get() ?: this.id
        val schema = executionContext.schemaProvider.getSchema(typeId) as? CompositeSchema
            ?: return false
        return schema.isSubtypeOf(FileModification.ID)
    }

    private fun compositeKeyOf(data: Data): String? = stringFieldOf(
        data,
        FileModification.Field.COMPOSITE_KEY,
    )

    private fun fileIdOf(data: Data): String? = stringFieldOf(data, FileModification.Field.FILE_ID)

    private fun stringFieldOf(data: Data, fieldId: String): String? = when (data) {
        is Entity<*> -> runCatching { data.optionalFieldValue<TString>(fieldId)?.get() }.getOrNull()
        is Composite -> runCatching { data.getField<TString>(fieldId).get() }.getOrNull()
        else -> null
    }

    private fun typeIdOf(data: Data): String =
        (data as? Entity<*>)?.type?.get() ?: data.id

    private fun timestampOf(data: Data): Long {
        return when (data) {
            is Entity<*> -> runCatching {
                data.getFieldValue<Instant>(FileModification.Field.TIMESTAMP).toEpochMilliseconds()
            }.getOrNull()
            is Composite -> runCatching {
                data.getField<Instant>(FileModification.Field.TIMESTAMP).toEpochMilliseconds()
            }.getOrNull()
            else -> null
        } ?: 0L
    }
}

/**
 * Maintains the first-class `fileSyncSettings` relation
 * (`FileSyncSetting.RELATION`, hung off `PreferenceService` — see
 * `drive-offline-online.md` §"Follow-up — a first-class serviceDataRelation") as a
 * derived, typed **projection** of each device's file-synchronisation settings.
 *
 * ⚠ **Deliberately not folded into the generic `RelationMaintainer`** (R2). Every
 * relation that mechanism serves mirrors *the saved entity* into an array; this
 * is a **2→1 fold** onto a *different* type — a device's settings live in two
 * synced `DeviceSetting` rows (`file.sync.defaultPolicy` and
 * `file.sync.overrides`) and this observer folds them into a single
 * [FileSyncSetting] per device, re-reading *both* siblings from the DB on every
 * save/delete of *either*. `RelationSpec` has no way to say that, and shouldn't:
 * it is a projection, the same class of thing as `LatestSampleMaintainer`'s
 * rollup.
 *
 * The relation is also **cross-service** — declared from `:file-service-common`
 * but hung off `PreferenceService.ID` — so it keeps its hand-written
 * `serviceDataRelation(fromId, targetId, …)` registration too: a schema walk of
 * `PreferenceService.Schema` cannot see a relation another module contributes.
 * That is the second permanent carve-out from §9.2.1, beside `FileSystem.ROOT`'s
 * single-entity form.
 *
 * The `DeviceSetting` rows are the `Syncable(Eager)` source of truth; the
 * projection never goes on the wire (it carries no `Syncable` trait). Each device
 * re-derives it locally. Without this observer,
 * `preferenceService.fileSyncSettings` would always read empty.
 *
 * Keyed by `deviceId` (one projection row per device); idempotent via
 * remove-then-add. When a device has *no* file-sync rows left (both deleted), the
 * projection row is removed rather than left as a synthetic all-defaults entry.
 *
 * This observer is registered by [initFileService]; the `DeviceSetting` writes it
 * reacts to flow through the same [ExecutionContext], so no extra wiring against
 * the preference service is needed.
 */
private class FileSyncSettingsRelationMaintainer(
    private val executionContext: ExecutionContext,
) : DataObserver {

    private val settingsRef: ArrayDatabaseReference = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = org.tenshu.core.collection.list.TList.ID,
            relationId = FileSyncSetting.RELATION,
        ),
        isEntity = false,
        elementSchemaId = FileSyncSetting.ID,
    )

    private val fileSyncPreferenceIds: Set<String> = setOf(
        FileSyncPreferences.DefaultPolicy.id,
        FileSyncPreferences.Overrides.id,
    )

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        if (!data.isDeviceSetting()) return
        val preferenceId = preferenceIdOf(data) ?: return
        if (preferenceId !in fileSyncPreferenceIds) return
        val deviceId = deviceIdOf(data) ?: return
        rebuild(deviceId)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        val stringDataId = dataId as? DataId.StringDataId ?: return
        if (stringDataId.type.get() != DeviceSetting.ID) return
        // The DeviceSetting key is `"$deviceId/$preferenceId"` and the file-sync
        // preferenceIds are dotted constants (no `/`), so a suffix match recovers
        // the deviceId iff this was one of our rows.
        val deviceId = deviceIdFromDeletedKey(stringDataId.keyValue.get()) ?: return
        rebuild(deviceId)
    }

    /**
     * Re-derive the [FileSyncSetting] projection for [deviceId] from the current
     * DB state of both source rows. Runs after the triggering save/delete has been
     * applied to the DB, so a just-deleted row reads back as absent.
     */
    private fun rebuild(deviceId: String) {
        // defaultPolicy is stored as the SyncPolicy constant itself (no codec); the
        // overrides value is a PathSyncOverrideSet composite the adapter unwraps.
        val defaultPolicy = readSettingValue(deviceId, FileSyncPreferences.DefaultPolicy.id)
            ?.let { it as? SyncPolicy }
        val overrides = readSettingValue(deviceId, FileSyncPreferences.Overrides.id)
            ?.let { PathSyncOverrideSetAdapter.fromData(it) }

        val projectionDataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = FileSyncSetting.ID,
            keyValue = deviceId,
        )

        executionContext.database.transaction {
            executionContext.database.removeFromArray(settingsRef, projectionDataId)
            // Only keep a projection row while the device actually has a file-sync
            // setting; if both source rows are gone, leave the relation empty for it.
            // The element is a bare composite (no `Entity.create`/`saveData` needed —
            // the relation array stores it inline and the chain `search` evaluates the
            // predicate against the composite directly; readers extract `.value`
            // tolerantly rather than as `Entity<T>` — see `fileSyncSettingFor`).
            if (defaultPolicy != null || overrides != null) {
                executionContext.database.addToArray(
                    settingsRef,
                    FileSyncSetting.create(
                        context = executionContext.context,
                        deviceId = deviceId,
                        defaultPolicy = defaultPolicy ?: FileSyncPreferences.DefaultPolicy.default,
                        overrides = overrides ?: FileSyncPreferences.Overrides.default,
                    ),
                )
            }
        }
    }

    /** The stored polymorphic `value` of the `(deviceId, preferenceId)` row, or null. */
    private fun readSettingValue(deviceId: String, preferenceId: String): Data? {
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = DeviceSetting.ID,
            keyValue = DeviceSetting.compositeKey(deviceId, preferenceId),
        )
        val stored = executionContext.database.getData(dataId) ?: return null
        return when (stored) {
            is Entity<*> -> runCatching { stored.getFieldValue<Data>(Setting.Field.VALUE) }.getOrNull()
            is Composite -> runCatching { stored.getField<Data>(Setting.Field.VALUE) }.getOrNull()
            else -> null
        }
    }

    private fun deviceIdFromDeletedKey(compositeKey: String): String? {
        for (preferenceId in fileSyncPreferenceIds) {
            val suffix = "/$preferenceId"
            if (compositeKey.endsWith(suffix)) return compositeKey.removeSuffix(suffix)
        }
        return null
    }

    private fun Data.isDeviceSetting(): Boolean {
        val typeId = (this as? Entity<*>)?.type?.get() ?: this.id
        val schema = executionContext.schemaProvider.getSchema(typeId) as? CompositeSchema
            ?: return false
        return schema.isSubtypeOf(DeviceSetting.ID)
    }

    private fun preferenceIdOf(data: Data): String? = stringFieldOf(data, Setting.Field.PREFERENCE_ID)
    private fun deviceIdOf(data: Data): String? = stringFieldOf(data, DeviceSetting.Field.DEVICE_ID)

    private fun stringFieldOf(data: Data, fieldId: String): String? = when (data) {
        is Entity<*> -> runCatching { data.optionalFieldValue<TString>(fieldId)?.get() }.getOrNull()
        is Composite -> runCatching { data.getField<TString>(fieldId).get() }.getOrNull()
        else -> null
    }
}
