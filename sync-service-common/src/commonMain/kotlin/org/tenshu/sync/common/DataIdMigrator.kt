package org.tenshu.sync.common

import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.sync.SyncService

/**
 * Generic DataId-migration primitive — `file-content-addressing.md` §3.3.4 (decision A,
 * §3.3.1). When an entity moves from one key to another (e.g. a `File`
 * whose path-derived DataId changes on a move), the *old* key must be retired everywhere:
 * peers told it is gone, the orphaned old-key row dropped locally, and the stale sync
 * bookkeeping at both hashes swept. This class owns that **old-side** work generically; the
 * caller creates the new-key entity (whose ordinary `saveData` maintains the `ResolvedDataId`
 * reverse-index + parent membership via the standard observers — nothing bespoke needed).
 *
 * It lives in `:sync-service-common` because it needs [SyncMetadata] + [TombstoneEmitter] +
 * [RekeyMigrationMarker] (all here) over [ExecutionContext] (from `:core-common`);
 * `:file-service-proxy` depends on this module, so `MoveFile` constructs it from the
 * `(executionContext, tombstoneEmitter)` it already holds — **no** cross-module bridge or DI.
 *
 * **Crash safety.** The retirement touches several rows (two `SyncMetadata` sweeps,
 * an entity delete + its cascade, a tombstone). A crash mid-way would otherwise leave the entity
 * at the new key with the old key only partially retired. [migrate] guards this with a durable
 * **intent-journal marker** ([RekeyMigrationMarker]) written in its own `transaction {}` **before**
 * the first retirement write and cleared after the last. Because the object store's write mailbox
 * is ordered, any retirement write on disk implies the marker is on disk too — so a marker
 * surviving to the next startup is exactly a crashed migration, and [resumeIncompleteMigrations]
 * (called once at proxy init) re-runs the retirement, which is idempotent (content-addressed
 * puts + a delete of an already-gone row + a highest-HLC-wins tombstone all re-apply cleanly).
 * The marker guarantees the new-key entity was durably saved first (the caller saves it before
 * calling [migrate], hence before the marker), so resume never deletes an old key whose
 * replacement is missing.
 *
 * **Two journals, not one.** A retirement can also fail to *publish*: `emit` answers null when
 * there is no realm to name the target in (sync not yet enabled, or a concurrent `LockRealm`), and
 * by then the old row is already gone locally, so the marker must survive as the retry. ⚠⚠ But a
 * marker that survives a locked realm is one the user can outlive — they can create a new file at
 * the retired path before unlocking — and a phase-1 replay deletes that path unconditionally *and*
 * tombstones it under a name derived from the path itself, killing the new row locally and on every
 * peer. So the retry moves to a second journal
 * ([org.tenshu.sync.SyncService.Collections.PENDING_REKEY_TOMBSTONES]) that authorises an emit and
 * nothing else, and is abandoned outright if the old key is live again.
 */
class DataIdMigrator(
    private val executionContext: ExecutionContext,
    private val tombstoneEmitter: TombstoneEmitter?,
    /**
     * The realm this device belongs to, read afresh on each construction — see
     * [TombstoneEmitter.residentOwnerId]. `null` ⇒ no realm at all, so nothing owes a tombstone and
     * no retry is kept.
     */
    private val ownerId: () -> String? = { tombstoneEmitter?.residentOwnerId },
) {

    /** Phase 1: the retirement has not (provably) run yet — a replay may delete the old key. */
    private val journalRef: ArrayDatabaseReference = journal(
        SyncService.Collections.PENDING_REKEY_MIGRATIONS
    )

    /**
     * The realm this migration belongs to, captured **once** when the migrator is built — which is
     * per operation, before any retirement write.
     *
     * ⚠⚠ Load-bearing beyond naming the array. A different-owner pairing can complete *before*
     * `retire` reaches its emit, and then every read inside that emit sees the new realm and agrees
     * with itself: the wire name is derived under B, the block is stamped for B, every snapshot
     * guard passes — and the tombstone is perfectly matchable against an unrelated entity at the
     * same path in B, whose row it deletes on every one of B's peers. A self-consistent snapshot of
     * the wrong realm is the failure mode those guards cannot see, because the operation began
     * before the snapshot did.
     */
    private val originOwnerId: String? = ownerId()

    /**
     * Phase 2: the local retirement is done and only the tombstone is owed — emit, never delete.
     *
     * ⚠⚠ **Scoped to the realm this device belongs to**, the same way a shared realm's outbound
     * block queue is (`PENDING_BLOCKS + NAMESPACE_SEP + ownerId`). A retry names its target by
     * `DataId` and the wire name is derived from that *plus the realm key*, so one written under
     * realm A and replayed under realm B tombstones whatever lives at that path in **B**. Naming
     * the array after the owner makes that structurally impossible: the replay reads the array of
     * the realm it is running in and cannot see the other's. ⚠ A guard at the owner transition
     * cannot do this on its own — a move can add a marker after the cleanup's snapshot and before
     * the new keys are seated, and no lock spans the two.
     * ⚠ `residentOwnerId`, not `currentOwnerId`: the realm is *locked* whenever these are written,
     * which is exactly when the session value is null.
     */
    private val owedTombstonesRef: ArrayDatabaseReference? = originOwnerId?.let {
        journal(SyncService.Collections.PENDING_REKEY_TOMBSTONES + "/" + it)
    }

    private fun journal(relationId: String) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = RekeyMigrationMarker.ID,
            relationId = relationId,
        ),
        isEntity = false,
        elementSchemaId = RekeyMigrationMarker.ID,
    )

    /**
     * Retire [oldDataId] in favour of a new key whose DataId hash is [newHash]. [entityType]
     * is the schema id of the migrated entity (the tombstone's `targetEntityType`).
     * [extraLocalCleanup] runs after the metadata sweep and before the old entity is deleted —
     * used e.g. for a moved folder's own now-empty `FILES` array object.
     *
     * The new-key entity must already have been saved by the caller (so its create block is
     * enqueued no later than the old-key tombstone emitted here — §3.3.3.D — and so a lingering
     * journal marker always implies the replacement is durable).
     */
    fun migrate(
        oldDataId: DataId,
        newHash: String,
        entityType: String,
        extraLocalCleanup: () -> Unit = {},
    ) {
        // Marker FIRST, in its own transaction (ordered flushBarrier) — see the class KDoc.
        beginMarker(oldDataId, newHash, entityType)
        val tombstoned = retire(oldDataId, newHash, entityType, extraLocalCleanup)
        // ⚠⚠ Hand the retry to the *second* journal, not this one. `emit` answers null when there is
        // no realm to name the target in (a concurrent `LockRealm`, or sync not yet enabled) and by
        // then the old row is already deleted locally: dropping the marker would leave no retry and
        // every peer holding the old path for ever. But re-running *this* phase later would re-run
        // the delete — see [PENDING_REKEY_TOMBSTONES]. The owed-tombstone row is written before this
        // marker is cleared, so a crash between them leaves both (and the phase-1 replay is
        // idempotent), never neither.
        // ⚠ The phase move is one `transaction` so no *reader* sees the half-way state — mutual
        // exclusion, which is all `Database.transaction` provides. ⚠⚠ It is NOT crash atomicity:
        // there is no rollback, so a kill between the two writes still leaves both markers, exactly
        // as it would without the wrapper. What makes that survivable is the ordering above plus
        // phase 1 running only at init, never on the unlock edge — see [resumeOwedTombstones].
        executionContext.database.transaction {
            if (!tombstoned) oweTombstone(oldDataId, newHash, entityType)
            // Marker LAST, once the retirement is done.
            clearMarker(journalRef, oldDataId.getDataIdHash().toString())
        }
    }

    /**
     * Complete any migration that a prior session began but did not finish (a marker still in
     * either journal). Idempotent: re-running a completed step is a no-op; each marker is cleared
     * once its phase is done. Returns how many were resumed. Call once at proxy init, after the
     * observers + tombstone emitter are wired — and again when the realm unlocks, which is what the
     * phase-2 journal is waiting for.
     */
    fun resumeIncompleteMigrations(): Int {
        // ⚠ Snapshot phase 2 BEFORE phase 1 runs, or a phase-1 marker that still cannot emit is
        // counted (and retried) twice in the same pass.
        val owed = owedTombstonesRef?.let { markersIn(it) }.orEmpty()
        val pending = markersIn(journalRef)

        pending.forEach { marker ->
            // Phase 1 — the retirement may not have run. Replaying it deletes `marker.oldDataId`,
            // which is safe only **at proxy init**, before any operation of *this* process can
            // recreate the old path. ⚠⚠ On route-1 that is not a global quiescent point: Control and
            // Drive are separate processes over one replica, each reaching `initSyncService` at a
            // moment unrelated to the other's user activity, and this journal lives in the shared
            // replica — so one app's init can replay a marker the other app stranded, after the user
            // recreated that path there. Phase 1 has no recreated-path guard at all. ⚠ This case
            // is open; the in-process case, which is every other platform, is safe.
            // ⚠⚠ Which is why this function must not be called on the unlock edge — see
            // [resumeOwedTombstones]. A phase-1 marker *can* outlive the call that wrote it: any
            // throw inside `retire` (the cascade observers, the folder's own array cleanup, the
            // emit) leaves it in this journal for the rest of the session, and by unlock time the
            // user may have created a new file at that path. ⚠ That same "can outlive its call" is
            // what makes the route-1 case above reachable.
            val tombstoned = retire(
                oldDataId = marker.oldDataId,
                newHash = marker.newHash.get(),
                entityType = marker.entityType.get(),
                extraLocalCleanup = {},
            )
            // ⚠ Same transaction as the inline path, and the same caveat: exclusion, not atomicity.
            executionContext.database.transaction {
                if (!tombstoned) {
                    oweTombstone(marker.oldDataId, marker.newHash.get(), marker.entityType.get())
                }
                clearMarker(journalRef, marker.markerKey.get())
            }
        }

        replayOwed(owed)
        return pending.size + owed.size
    }

    /**
     * Replay **only** the owed-tombstone journal — the emit-only phase.
     *
     * ⚠⚠ This is what the locked→enabled edge must call, never [resumeIncompleteMigrations]. Phase 1
     * deletes `oldDataId` unconditionally, and its safety rests on running before any operation
     * could recreate that path — true at proxy init, false at an unlock that follows arbitrary user
     * activity. Replaying phase 1 there destroys a file the user created at the retired path, and
     * (the realm now being unlocked) emits a correctly-named tombstone that deletes it on every
     * peer: exactly the loss the phase split exists to prevent, reached through the unlock hook.
     */
    fun resumeOwedTombstones(): Int {
        val owed = owedTombstonesRef?.let { markersIn(it) }.orEmpty()
        replayOwed(owed)
        return owed.size
    }

    private fun replayOwed(owed: List<RekeyMigrationMarker>) {
        owed.forEach { marker ->
            // Phase 2 — the local retirement is done; only the tombstone is owed. Emit only.
            //
            // ⚠⚠ And not even that if the path has been recreated. A marker here survives for as
            // long as the realm stays locked, and the user can create a new file at the old path in
            // that window. The tombstone's wire name is derived from the *DataId*, so it names that
            // new row just as well as the old one — emitting would delete the user's new file on
            // every peer. ⚠ Skipping strands nothing: peers resolve the same wire name, so the
            // recreated row's own block supersedes the old generation there anyway.
            // ⚠⚠ The guard and the emit are ONE transaction, because separately they are a race
            // with the very thing the guard is for. The store lock is reentrant and every mutation
            // runs under it, so a `saveData` recreating this key cannot interleave between the two.
            // Point-in-time, the check can answer "free" microseconds before the recreation lands —
            // and the tombstone, emitted after, carries the later HLC, so peers delete the new
            // generation and this device follows when its own tombstone echoes back.
            // ⚠ **Route 1.** Several iOS processes share one replica, and this guard asks an
            // *existence* question. An in-process index a sibling's write does not update would let
            // a path recreated by *another* process still be tombstoned here. The SQLite shared
            // replica holds no rows in memory: the read goes to the store inside the access's own
            // transaction (`ios-shared-store-sqlite.md` §2.5). ⚠ Not verified against this race.
            val emitted = executionContext.database.transaction {
                if (executionContext.database.getData(marker.oldDataId) != null) {
                    println(
                        "DataIdMigrator -> abandoning owed tombstone ${marker.markerKey.get()}: the " +
                            "old key has been recreated, and its tombstone would name the new row too"
                    )
                    return@transaction null
                }
                tombstoneEmitter?.emit(
                    targetEntityId = marker.oldDataId,
                    targetEntityType = marker.entityType.get(),
                )
            }
            // ⚠ Two different reasons to clear, and both are terminal for this marker: the retry
            // went out, or the key it names is alive again and must never be tombstoned.
            val recreated = executionContext.database.getData(marker.oldDataId) != null
            if (emitted != null || recreated) clearMarker(owedTombstonesRef!!, marker.markerKey.get())
        }
    }

    /**
     * Drop every owed tombstone without emitting it — for an **owner transition**, where the
     * originating realm is gone.
     *
     * ⚠⚠ A marker names its target by `DataId`, and the wire name is derived from that *plus the
     * realm key*. Replayed under a new owner it therefore names whatever lives at the same path in
     * the **new** realm, and tombstones an unrelated file. ⚠ The owner cannot simply be recorded on
     * the marker instead: a retry exists precisely because there was no realm to name the target in,
     * so at the moment it is written there is no `ownerId` to write down.
     */
    fun discardOwedTombstones(): Int {
        val ref = owedTombstonesRef ?: return 0
        val owed = markersIn(ref)
        owed.forEach { clearMarker(ref, it.markerKey.get()) }
        return owed.size
    }

    private fun markersIn(ref: ArrayDatabaseReference): List<RekeyMigrationMarker> =
        executionContext.database.getArrayData(ref)
            ?.filterIsInstance<RekeyMigrationMarker>()
            ?.toList()
            ?: emptyList()

    /**
     * The idempotent old-key retirement. Identical whether run inline or on resume.
     *
     * @return whether the old-key tombstone was actually emitted. `false` means there was no realm
     *   to name it in, so the caller must move the retry to the phase-2 journal: the row is gone
     *   locally and only a later re-emit can tell peers about it. ⚠ Never by keeping the phase-1
     *   marker — that would re-run this delete against whatever lives at the old key by then.
     */
    private fun retire(
        oldDataId: DataId,
        newHash: String,
        entityType: String,
        extraLocalCleanup: () -> Unit,
    ): Boolean {
        val oldHash = oldDataId.getDataIdHash().toString()

        // Sweep the local per-device LWW tip (`SyncMetadata`, written only on the ingest path)
        // at both hashes (§3.3.3.E): at the old hash it is garbage + a landmine if the path is
        // ever reused; at the new hash a leftover high-HLC row from a previously-deleted file
        // there could silently drop the re-emitted entity block when this device re-ingests its
        // own echo. Raw `database.deleteData` (not `ExecutionContext.deleteData`) so we don't
        // fire observers over pure local bookkeeping (which would stamp a spurious local
        // tombstone marker); deleting a non-existent row is a harmless no-op.
        sweepSyncMetadata(oldHash)
        sweepSyncMetadata(newHash)

        extraLocalCleanup()

        // Drop the orphaned old-key entity locally, through `ExecutionContext` so the ordinary
        // observers run: `TombstoneRegistry.onDataDeleted` removes `ResolvedDataId` @ oldHash,
        // the parent-listing maintainer repairs membership, and the file-history cascade reaps
        // old-key rows (history is re-keyed to the new key *before* this, so it survives).
        executionContext.deleteData(oldDataId)

        // Tell peers the old key is gone — else a peer still holding it shows the entity at
        // both keys (§3.3.3.A). The same `DeleteBlock` `DeleteFile` emits.
        //
        // ⚠⚠ The **id** form, never the hash form. `oldHash` above is `getDataIdHash()` — the
        // *local* name — and the relay-visible name is `HMAC(K_entity, identity)`, which only the
        // emitter can derive. Naming the tombstone locally addresses an entity no peer can match,
        // and nothing errors: both sides sync and the row simply never dies, so every move would
        // leave peers showing the file at both paths.
        // ⚠ Still resolvable after the `deleteData` above: `WireIdResolver.ofOrNull` derives from
        // the `DataId` and the realm key, not from the row.
        // ⚠⚠ Only into the realm this migration started in — see [originOwnerId]. An adoption that
        // landed since means the emit would name an entity in the *new* realm; refusing leaves the
        // retry in the old realm's journal, where a later session in that realm can still deliver
        // it, and where a session in the new realm cannot see it at all.
        if (ownerId() != originOwnerId) {
            println(
                "DataIdMigrator -> not emitting: the realm changed under this migration " +
                    "(started in ${originOwnerId ?: "no realm"})"
            )
            return false
        }
        val emitter = tombstoneEmitter ?: return false
        // ⭐ No successor is named. The new key's own push carries its content edges
        // (`content-plane-cr2-b3-wire-spec.md` §3.1), so nothing here declares or undeclares.
        return emitter.emit(targetEntityId = oldDataId, targetEntityType = entityType) != null
    }

    private fun beginMarker(oldDataId: DataId, newHash: String, entityType: String) =
        writeMarker(journalRef, oldDataId, newHash, entityType)

    /**
     * Move the retry to phase 2: the row is already gone locally, only the tombstone is owed.
     *
     * ⚠ No realm ⇒ no retry. A device that belongs to no realm has no peers to tell, so there is
     * nothing to keep — and nowhere realm-scoped to keep it.
     */
    private fun oweTombstone(oldDataId: DataId, newHash: String, entityType: String) {
        val ref = owedTombstonesRef ?: return
        writeMarker(ref, oldDataId, newHash, entityType)
    }

    private fun writeMarker(
        ref: ArrayDatabaseReference,
        oldDataId: DataId,
        newHash: String,
        entityType: String,
    ) {
        val marker = RekeyMigrationMarker.create(
            context = executionContext.context,
            markerKey = oldDataId.getDataIdHash().toString(),
            oldDataId = oldDataId,
            newHash = newHash,
            entityType = entityType,
        )
        // Own transaction ⇒ its writes + the flushBarrier stay contiguous and ordered before the
        // retirement writes. Raw `database.addToArray` (not `ExecutionContext`) — pure local
        // sidecar, must not re-fire observers.
        executionContext.database.transaction {
            executionContext.database.addToArray(ref, marker)
        }
    }

    private fun clearMarker(ref: ArrayDatabaseReference, markerKey: String) {
        val markerDataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = RekeyMigrationMarker.ID,
            keyValue = markerKey,
        )
        executionContext.database.transaction {
            executionContext.database.removeFromArray(ref, markerDataId)
        }
    }

    private fun sweepSyncMetadata(hash: String) {
        executionContext.database.deleteData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = SyncMetadata.ID,
                keyValue = hash,
            )
        )
    }
}
