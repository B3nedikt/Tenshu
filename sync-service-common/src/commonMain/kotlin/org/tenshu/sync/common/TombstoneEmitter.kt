package org.tenshu.sync.common

/**
 * Bridge for operation implementations to emit tombstones (`DeleteBlock`s) without
 * pulling in `:sync-service-proxy` directly. Implemented in proxy by `SyncObserver`,
 * which constructs the block, queues it for relay push, and records it in the local
 * `TombstoneRegistry` so future stale `SyncBlock`s for the same entity are short-
 * circuited.
 *
 * See `docs/adr/0006-sync-tombstones.md` Phase 3.
 */
interface TombstoneEmitter {

    /**
     * The ordering a subtree reaper reaps by (CR-2 B3b, `content-plane-cr2-quorumless.md` §10.12 Q4,
     * revised): one point per deleted folder, its tombstone's HLC, shared by every device. Null on an
     * emitter that has none, in which case a reaper keeps its pre-B3b behaviour.
     *
     * ⚠ Carried on the emitter rather than wired separately because the emitter's holder already
     * reaches `initFileService` on every runtime that reaps, and the ordering lives beside it in sync.
     */
    val reapOrdering: SubtreeReapOrdering? get() = null

    /**
     * Which rows are still provisional slice-snapshot rows, so a move can refuse them until the log
     * reaches them (CR-2 B5 §4). Null on an emitter that has none, in which case nothing is refused.
     *
     * ⚠ Carried here for [reapOrdering]'s reason: the emitter already reaches the move on every runtime.
     */
    val provisionalRows: ProvisionalRows? get() = null

    /**
     * The realm this device **belongs to**, durable across a lock and across the cold-start key-load
     * window — `SyncManager.residentOwnerId`.
     *
     * ⚠⚠ Here because a retry that outlives its realm is a delete aimed at the wrong one. A rekey
     * marker names its target by `DataId` and the wire name is derived from that *plus the realm
     * key*, so a retry written under realm A and replayed under realm B tombstones whatever lives at
     * that path in B. The emitter is the component that knows which realm is which, and `null` means
     * this device belongs to none — in which case there are no peers to tell and no retry to keep.
     * ⚠ Not `currentOwnerId`: that is null while the realm is merely locked, which is exactly when
     * these retries are created.
     */
    val residentOwnerId: String? get() = null

    /**
     * Emit a tombstone for the entity identified by [targetEntityIdHash]. [targetEntityType]
     * is the schema id of the deleted entity (used by the receiver's merge dispatcher).
     *
     * For Device-entity tombstones (i.e. revocations), pass [revokedDeviceId] so the relay
     * can extract the deviceId and revoke its `KnownDevicesStore` registration — closes
     * T-K-02 push-rejection per `docs/sync/threat-model.md` §8.5. Omit (or pass null) for
     * non-revocation tombstones.
     *
     * @return the emitted block's **`blockId`**, or `null` if nothing was emitted (sync disabled,
     *   or no current realm). ⚠ RH-1a α (`sync-retention-horizons.md` §3.5): this is the join key
     *   between a delete and the relay sequence its tombstone is eventually assigned. A reclamation
     *   candidate is recorded here, synchronously, but the seq only exists once the block is pushed
     *   — later, on the sync lane — so without an identifier there is nothing to stamp when the
     *   push returns. Callers that do not reclaim may ignore it.
     */
    fun emit(
        targetEntityIdHash: String,
        targetEntityType: String,
        revokedDeviceId: String? = null
    ): String?

    /**
     * Emit a tombstone for [targetEntityId], letting the emitter name it.
     *
     * ⚠⚠ **Prefer this over the hash form.** Since EI-1 the relay-visible name of an entity is
     * `HMAC(K_entity, identity)` — it needs a realm key, and only the emitter has one. A caller that
     * hashes the id itself produces the *local* name, so its tombstone addresses an entity no peer
     * can match, and nothing errors: both sides sync and the row simply never dies
     * (`sync-entity-identity.md` EI-1).
     *
     * ⚠ The hash form stays for callers that genuinely hold only a hash — a re-emit from a stored
     * record, where the name was resolved when the record was written.
     */
    fun emit(
        targetEntityId: org.tenshu.core.entity.DataId,
        targetEntityType: String,
        revokedDeviceId: String? = null
    ): String?

    /**
     * Emit a whole batch (a realm-data reset deleting every row of a type).
     *
     * ⚠ Not sugar: [emit] appends its block to the durable pending-tombstones
     * array, which is a full read-modify-write of that array, and it wakes the
     * sync scheduler. Looping it over N rows is O(N²) in array writes plus N
     * wakes — the reset ANR (`control-data-reset.md` §11). Implementations
     * queue the batch in **one** array write and wake **once**.
     *
     * The default implementation loops, so a non-batching implementation (test
     * doubles) needs no change.
     *
     * @return each emitted block's `blockId`, in the order of [targets]. ⚠⚠ An override **must** be
     *   all-or-nothing: queue every target, or answer `null` / `emptyList()` — a *partial* batch is
     *   the one state nothing recovers from, because the caller deletes the whole chunk either way.
     *   ⚠ The default body below is **not** all-or-nothing (it `mapNotNull`s, dropping what it could
     *   not name), which is tolerable only because the sole production implementation overrides it.
     *   An emitter that takes the default and is handed to a chunked deleter produces exactly that
     *   unrecoverable state. See the `null` ≠ empty note below.
     *
     * ⚠⚠ `null` ≠ empty, and callers that delete rows must tell them apart:
     *  - **`emptyList()`** — nothing needed to be emitted, because this device belongs to **no
     *    realm** (never set up, or discarded). Deleting locally is correct; that is the
     *    long-standing local-only behaviour.
     *  - **`null`** — the targets *should* have been tombstoned and could not be **named for the
     *    wire**. Deleting anyway destroys the only ids that could tombstone those rows later, so the
     *    caller must **not** proceed.
     *
     * ⚠⚠ "Sync is off" is **not** the dividing line, and reading it that way is a data-loss bug.
     * A realm that is merely **locked** — or still inside the cold start's async key-load window —
     * reports `isEnabled() == false` while its peers hold every row this batch is about. The
     * membership question is `residentOwnerId` (durable), not `isEnabled()` (loaded in this
     * session): a paced reset whose `LockRealm` lands between two chunks must stop, not delete.
     */
    fun emitAll(targets: List<Target>): List<String>? =
        targets.mapNotNull { emit(it.entityId, it.entityType, it.revokedDeviceId) }

    /**
     * [emit], naming the entities a recursive delete removed along with [targetEntityId].
     *
     * ADR 0006 #7 keeps one tombstone per delete *operation*, and each receiver cascades locally.
     * But the relay removes edges by tombstone, so without this a folder delete removed the folder's
     * alone: every file inside kept its edge and its bytes stood for ever (§9.14). ⭐ Since CR-2 B3 the
     * cascade rides the tombstone's own push (`content-plane-cr2-b3-wire-spec.md` §3.2), and the
     * relay removes the whole subtree in that push's transaction.
     *
     * ⚠⚠ One call, not emit-then-record: [emit] wakes the drain, which can push the block before a
     * later call lands, and a push that finds no cascade recorded names none. Persisted with the
     * pending tombstone (CR-2 B3b), so a restart before the push loses nothing.
     *
     * The default ignores the cascade, for emitters with no content plane.
     */
    fun emitWithCascade(
        targetEntityId: org.tenshu.core.entity.DataId,
        targetEntityType: String,
        cascadedEntityIds: List<org.tenshu.core.entity.DataId>,
    ): String? = emit(targetEntityId, targetEntityType)

    /** One entity to tombstone — the batch element of [emitAll]. */
    data class Target(
        /**
         * ⚠⚠ The **DataId**, not a hash. Since EI-1 the relay-visible name is
         * `HMAC(K_entity, identity)` and only the emitter holds the realm key, so a caller that
         * hashes the id itself names the tombstone *locally* — addressing an entity no peer can
         * match, with nothing to notice it: both sides sync and the row simply never dies. The
         * batch path carried `entityIdHash` and every realm-data reset emitted unmatched
         * tombstones because of it.
         */
        val entityId: org.tenshu.core.entity.DataId,
        val entityType: String,
        val revokedDeviceId: String? = null,
    )
}
