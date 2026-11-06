package org.tenshu.sync.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.messageOrNull
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.LaneAwareDataObserver
import org.tenshu.core.common.proxy.externalPropertyWriter
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.util.DefaultChunkTransferConcurrency
import org.tenshu.core.common.util.generateId
import org.tenshu.core.common.util.mapChunksBounded
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.common.util.sha256
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.id
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.DeltaBatchPayload
import org.tenshu.sync.FileChunkRef
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncDelta
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.ProvisionalRows
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.core.common.content.ContentDeclarations
import org.tenshu.core.common.content.ContentDeclarationsCache
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.sync.common.ContentDescription
import org.tenshu.sync.common.DescribeOutcome
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.ContentResolvability
import org.tenshu.sync.common.FileContentSyncEmitter
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.sync.common.PendingContentEmit
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncBlockEmitter
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SubtreeReapOrdering
import org.tenshu.sync.common.TombstoneEmitter

/**
 * Observes data changes in the [ExecutionContext] and generates [SyncBlock]s
 * for data marked with the [Syncable] trait.
 *
 * Also implements [TombstoneEmitter] so operation implementations in other modules
 * (e.g. `:file-service-proxy`'s `DeleteFileImplementation`) can publish a [DeleteBlock]
 * without pulling in this module directly.
 */
class SyncObserver(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val tombstoneRegistry: TombstoneRegistry? = null,
    /**
     * Content-plane port for the two-plane file-content design
     * (`docs/adr/0017-file-content-two-plane-channel.md` P1). Null in test harnesses / wirings
     * without a relay content store — [emitFileContent] then no-ops
     * (returns false) instead of trying to chunk-push.
     */
    private val contentBlobStore: ContentBlobStore? = null,
    /**
     * `FileDatabase` the persisted file bytes were written to by
     * `UploadFileImplementation.writeByteArray`. The emit path reads
     * them back one `CHUNK` at a time from here — never a second
     * whole-file copy. Null ⇒ [emitFileContent] no-ops.
     */
    private val fileDatabase: FileDatabase? = null,
    /**
     * Fired after an outbound block/tombstone is enqueued so [SyncScheduler]
     * drains it within one observer-dispatch + channel send instead of waiting
     * for the next poll tick. Null = no wake wiring (direct-construction tests
     * that don't exercise scheduler latency). Every enqueue site below is
     * inherently user-originated — ingest saves early-return on
     * [WriteOrigin.SYNC_INGEST] before reaching them — so this cannot start a
     * paired-device echo. See `core-background-processing.md` §8 Phase 4a-bis.
     */
    private val wakeSignal: SyncWakeSignal? = null,
    /**
     * Scope the Facet A emit consumer runs on. Defaults to the session's
     * [ExecutionContext.sessionScope] (ADR 0014 cancels+joins it on teardown) —
     * the same shape [SyncScheduler]/[ScheduleDrainLoop] use (an injected scope,
     * not a direct `sessionScope.launch`, which also keeps this off the
     * detach-discipline scan in `PhaseL2CancellationTest`). `initSyncService`
     * passes its background scope. §A.6 covers the service tier.
     */
    private val coroutineScope: CoroutineScope = executionContext.sessionScope,
    /**
     * Multi-realm routing (`sync-multi-realm-runtime.md`). When set,
     * each data block is emitted to *its entity's* realm — keys + a per-realm
     * outbound queue — resolved from the entity's DataId namespace prefix.
     * `null` (the default, and every wiring without shared realms) is the
     * single-realm path: everything routes to the personal realm and the
     * base `PENDING_BLOCKS` queue.
     */
    private val realmRegistry: RealmRegistry? = null,
    /**
     * The metered **content** gate (`sync-metered-plane-split.md` §3): consulted by
     * [emitFileContent], and the only place the user's `WifiOnly` policy is enforced. `false`
     * (Wi-Fi-only on a metered network) journals the emit as a [PendingContentEmit] instead of
     * `PUT`ting chunks; the scheduler's [drainDeferredContent] replays it when the gate opens.
     *
     * ⚠ This gate deliberately does **not** reach the metadata plane. Wrapping the whole
     * `SyncScheduler` tick would withhold the kilobyte-sized metadata blocks *and the inbound
     * pull* while the megabytes went out from right here — the inverse of the policy. Default
     * [SyncGate.AlwaysAllow] for test/legacy wirings.
     */
    private val contentGate: SyncGate = SyncGate.AlwaysAllow,
    /**
     * Where each tombstone's cascade is journalled before the block is queued, for `PushTombstone` to
     * read back at push time (`content-plane-cr2-b3-wire-spec.md`). Store-backed, so the default is the production shape.
     */
    private val tombstoneCascades: TombstoneCascades = TombstoneCascades(DatabaseTombstoneCascadeStore(executionContext)),
    /** Shared by every session of one runtime — see [EmitKeyLocks]. A default only for tests. */
    internal val emitKeyLocks: EmitKeyLocks = EmitKeyLocks(),
    /**
     * How many content-plane chunk transfers [emitFileContent] keeps in flight. See
     * [DefaultChunkTransferConcurrency] for why the default is 4 and what it costs in heap;
     * `1` is the strictly-sequential path, byte-for-byte, which makes this the kill-switch if a platform ever turns out to dislike the fan-out.
     */
    private val chunkTransferConcurrency: Int = DefaultChunkTransferConcurrency,
    /**
     * The ONE resolver the whole sync layer names entities with.
     *
     * ⚠⚠ Injected rather than built here, because two independently-built resolvers give one entity
     * two names: one that sees `realmRegistry` and one that does not name a shared-realm entity
     * under the shared key at the emit and the personal key at the ingest.
     */
    private val wireIdResolver: WireIdResolver? = null,
    /**
     * Per-entry retry backoff for [drainDeferredContent] (`control-background-work-off-fgs.md` §5.7).
     * ⚠ On by default, on a monotonic clock: a wiring that forgets it must not get the
     * 3,000-`PUT`s-a-minute retry storm against a failing content plane.
     */
    private val contentEmitBackoff: ContentEmitBackoff = ContentEmitBackoff(nowMs = monotonicMs()),

) : LaneAwareDataObserver, TombstoneEmitter, SyncBlockEmitter, FileContentSyncEmitter {

    // Facet A (ADR 0015): outbound block generation is
    // decoupled from the saveData thread. onDataSaved only enqueues; a single
    // consumer coroutine does the serialize + AEAD + HLC tick + pendingBlocks
    // enqueue, so saveData does not pay that cost inline.
    //
    // The task-scoped sync lane (§10.3) rides the SAME consumer: lane state is
    // mutated only on this coroutine (stage via [Saved]'s laneId; open /
    // checkpoint / close / replay marshalled as events below), so the lane
    // needs no locking and `awaitEmitIdle` covers it for tests — the emit
    // channel is exactly "where a task-scoped checkpoint boundary naturally
    // lands" (bounded-store-cache.md).
    private sealed interface EmitEvent {
        data class Saved(
            val data: Data,
            val laneId: String? = null,
            /** `content-plane-cr2-b4-spec.md` §2.3: the content ids this save cleared, read before the write ([prepareSave]). */
            val cleared: Set<String> = emptySet(),
        ) : EmitEvent

        /** Join point: completes once every prior [Saved] has been processed. */
        data class Barrier(val signal: CompletableDeferred<Unit>) : EmitEvent

        /** Lane lifecycle (task-scoped sync lane §10.3); ack completes when applied. */
        data class OpenLane(val laneId: String, val ack: CompletableDeferred<Unit>) : EmitEvent
        data class CheckpointLane(val laneId: String, val ack: CompletableDeferred<Unit>) : EmitEvent
        data class CloseLane(val laneId: String, val ack: CompletableDeferred<Unit>) : EmitEvent
        data class ReplayLaneJournal(val ack: CompletableDeferred<Unit>) : EmitEvent
    }

    // UNLIMITED (§A.2): the producer (onDataSaved) is non-suspend so it can't
    // apply backpressure; saves are bounded per operation. Revisit with a depth
    // gauge if a bulk-import path materialises.
    private val emitChannel = Channel<EmitEvent>(Channel.UNLIMITED)

    /**
     * This runtime's serialized frozen `VersionVector` — the full block stamp
     * (`core-schema-evolution.md` §4.1.2): payloads can embed foreign-service
     * types, so one version can't describe a block. Assembled once from the
     * registered module graph (the schema set is fixed for the process) and
     * written into every sealed envelope.
     */
    private val blockStamp: ByteArray by lazy {
        BlockPayloadEnvelope.stampOf(executionContext.schemaProvider, executionContext.context)
    }

    /**
     * Realm epoch bump (`core-schema-evolution.md` §7.2). Consulted after
     * each locally-originated save's own blocks are generated; raises the realm's
     * `RealmSchemaEpoch` entry for any service whose types this write contained
     * and whose recorded version trails this device's. Cheap when nothing is
     * behind — see [RealmSchemaEpochWriter].
     */
    private val epochWriter: RealmSchemaEpochWriter by lazy {
        RealmSchemaEpochWriter(executionContext, realmRegistry)
    }

    // Locked-window hold (re-emit on enable). A Syncable save or file-content emit that lands
    // while the realm is locked/initialising (`!syncManager.isEnabled()`, no keys yet) must not be
    // dropped — the entity would stay local and NEVER re-emit, stranded forever (e.g. a file
    // uploaded during the seconds-long autoload window at process start). So we hold the work here and replay it through the normal path once the realm becomes enabled
    // ([reemitPendingLockedWork], driven by the scheduler's enable edge). UNLIMITED + trySend keeps
    // the producers non-suspend; the queues stay small (saves-while-locked are rare).
    private val pendingSaves = Channel<HeldSave>(Channel.UNLIMITED)

    /** A save held while the realm is locked. */
    private class HeldSave(val data: Data, val cleared: Set<String>)
    /**
     * A content emit held while the realm was locked. [bound] is the whole target when its content id
     * did not come from the row (a bound producer's committed id, or a journal entry's): replaying
     * those through the unbound path would re-read the id off the row — two photo saves committing
     * C1 then C2 while locked would have the first replay publish C1's bytes under C2.
     */
    private data class PendingContent(
        val entityId: DataId,
        val propertyId: String,
        val filePath: String,
        val bound: ContentTarget? = null,
    )
    private val pendingContent = Channel<PendingContent>(Channel.UNLIMITED)

    // ⚠⚠ Per-(entity, property) ownership of a content emit, so the same content is never
    // emitted twice concurrently.
    //
    // `emitFileContent` journals the intent *before* it emits and clears it only after, so between
    // those two points the entry is on disk and looks exactly like an interrupted upload. A drain
    // landing in that window emits the same content a second time — and the second emit is not a
    // cheap no-op: the AEAD draws a fresh nonce, so the chunks encrypt to different ciphertexts
    // under different `sha256(ciphertext)` refs, while the manifest (whose ref is derived from the
    // contentId, so it is the SAME address) is overwritten to name only the second set. The first
    // set is then unreachable: no manifest names it, so reclamation — which resolves a content's
    // chunks THROUGH its manifest — can never free it. One duplicate, never-reclaimable
    // copy of the file's bytes per upload, charged to the owner's quota for ever.
    //
    // ⚠⚠ Ownership, not a check. A "is this key busy?" test that releases before emitting is no
    // guard at all: both paths pass the test and both emit. The claim is held across the journal
    // write, the emit AND the clear, because every one of those windows is one the other path can
    // act in — the inline path taking it only after journalling is what leaves the drain a
    // ready-to-drain entry nobody owns.
    //
    // ⚠ The two sides want opposite things when the key is taken, which is why there are two
    // helpers: the inline path is a user's upload of possibly NEWER bytes, so it waits; the drain
    // runs on the sync tick and must never block it, so it skips and re-plans next pass.
    //
    // ⚠ Measured, not theorised: without ownership `ContentLifecycleSystemTest` sees 4 blob PUTs for
    // one 4 KB upload (chunk, manifest, chunk', manifest) with the drain's emit interleaved between
    // the inline emit and its clear. The window is the whole inline upload, so it widens with file size.
    // ⚠⚠ The table is the RUNTIME's, not this observer's — see [EmitKeyLocks].
    private suspend fun acquireEmitKeyLock(emitKey: String): EmitKeyLocks.Lock = emitKeyLocks.acquire(emitKey)

    private suspend fun releaseEmitKeyLock(emitKey: String, lock: EmitKeyLocks.Lock) =
        emitKeyLocks.release(emitKey, lock)

    /**
     * Marks the coroutine that currently owns an emit key, so a nested acquire of the **same** key
     * passes straight through.
     *
     * ⚠⚠ Without this the producer deadlocks on itself: ownership starts before the bytes and the
     * row are written, and the producer's own `emitFileContent` runs inside it. A `Mutex` is not
     * reentrant, so that inner acquire would wait for a lock this very coroutine holds — hanging
     * the upload instead of duplicating it, which is a worse failure than the one being fixed.
     */
    private class OwnedEmitKey(val emitKey: String) : AbstractCoroutineContextElement(OwnedEmitKey) {
        companion object Key : CoroutineContext.Key<OwnedEmitKey>
    }

    /** Run [block] as the sole emitter of [emitKey], waiting for whoever holds it. */
    private suspend fun <T> withEmitKey(emitKey: String, block: suspend () -> T): T {
        if (currentCoroutineContext()[OwnedEmitKey]?.emitKey == emitKey) return block()
        val lock = acquireEmitKeyLock(emitKey)
        try {
            return lock.mutex.withLock {
                emitKeyLocks.beginHold(emitKey)
                try {
                    withContext(OwnedEmitKey(emitKey)) { block() }
                } finally {
                    // ⚠ NonCancellable: a cancelled emit may already have written a manifest, and an end
                    // lost to the cancellation would leave the content reading busy — or hide the emit.
                    withContext(NonCancellable) { emitKeyLocks.endHold(emitKey) }
                }
            }
        } finally {
            releaseEmitKeyLock(emitKey, lock)
        }
    }

    /**
     * ⚠⚠ Claims the key of the content the row names **now** — the content whose bytes the producer
     * is about to overwrite. That is the one a drain could publish wrongly in the window between the
     * new bytes landing and the new row being saved: the row still vouches for the old content, so
     * the drain's fast path would trust bytes that are already the new file's. The producer's own
     * emit, inside, claims the new content's key separately.
     */
    override suspend fun <T> withContentEmitOwnership(
        targetEntityId: DataId,
        propertyId: String,
        block: suspend () -> T,
    ): T {
        // ⚠⚠ Revalidated after the wait. Two overwrites of one path both read the
        // key of the content the row names before either starts; the second then waits on that key
        // and gets it only after the first has moved the row on to NEW content — which it does not
        // own. A drain of the first overwrite's intent could then take the fast path while the
        // second rewrites the bytes under it. ⇒ re-read the key once held, and retry if it moved.
        while (true) {
            val key = currentEmitKey(targetEntityId, propertyId)
            val claimed = withEmitKey(key) {
                if (currentEmitKey(targetEntityId, propertyId) != key) null else Owned(block())
            }
            if (claimed != null) return claimed.value
        }
    }

    /** A block's result, distinguishable from "the key moved, try again" even when the result is null. */
    private class Owned<T>(val value: T)

    /** As [withEmitKey], but returns null rather than waiting when someone else already owns it. */
    private suspend fun <T> tryWithEmitKey(emitKey: String, block: suspend () -> T): T? {
        if (currentCoroutineContext()[OwnedEmitKey]?.emitKey == emitKey) return block()
        val lock = acquireEmitKeyLock(emitKey)
        try {
            if (!lock.mutex.tryLock()) return null
            try {
                emitKeyLocks.beginHold(emitKey)
                try {
                    // ⚠ Marked as the owner, like [withEmitKey]: a stamp check inside must not count this
                    // very hold as "another emit".
                    return withContext(OwnedEmitKey(emitKey)) { block() }
                } finally {
                    withContext(NonCancellable) { emitKeyLocks.endHold(emitKey) }
                }
            } finally {
                lock.mutex.unlock()
            }
        } finally {
            releaseEmitKeyLock(emitKey, lock)
        }
    }

    // Task-scoped sync lane state (§10.3). Declared BEFORE the init block so the
    // consumer coroutine it launches never observes them uninitialized. Consumer-
    // thread confined; see the lane section further down for the semantics.
    private class Lane(val id: String) {
        /** Coalesced last-wins by "targetHash|propertyId" (§10.3), insertion-ordered. */
        val staged = LinkedHashMap<String, SyncDelta>()
        val stagedSizes = HashMap<String, Int>()
        var stagedBytes = 0

        /** Every journal append since the last checkpoint, superseded entries included. */
        val journaled = mutableListOf<SyncDelta>()

        /**
         * ⚠⚠ `ios-shared-store-sqlite.md` B.20.16: a checkpoint found no realm to seal in. From then
         * on the lane keeps **nothing in memory**: each save's delta goes to the durable journal
         * alone (its only copy anyway, and what the D6 replay seals), so a locked realm under a long
         * task does not grow the lane with every save, and the miss is logged once for the lane,
         * not at every checkpoint.
         */
        var spilled = false
    }

    private val lanes = mutableMapOf<String, Lane>()

    private val laneJournalRef = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = SyncDelta.ID,
            relationId = SyncService.Collections.PENDING_LANE_DELTAS
        ),
        isEntity = false,
        elementSchemaId = SyncDelta.ID
    )

    init {
        // Consumer on the session's owned scope (ADR 0014 cancels+joins it on
        // teardown). Correct for every producer — including detached L2 tasks,
        // which ClientRegistry.deferredTeardown keeps the session alive for. In
        // the §A.6 service-tier/headless case SyncObserver would be one-per-proxy
        // and the headless drain would call awaitEmitIdle to drain-and-join
        // before releasing its wakelock.
        coroutineScope.launch {
            for (event in emitChannel) {
                when (event) {
                    is EmitEvent.Saved ->
                        // §A.3 error sink: a serialize/encrypt failure is logged
                        // and the single block dropped — the entity is already
                        // persisted locally, so its change re-syncs on the next
                        // save. CancellationException propagates (teardown).
                        try {
                            val laneId = event.laneId
                            // The lane path answers what it wrote, as the ordinary path does —
                            // never a constant. Its unopened-lane fallback is the ordinary path,
                            // which finds no realm when a sibling locked it.
                            val handoff = if (laneId != null) {
                                stageIntoLane(laneId, event.data)
                            } else {
                                processSavedData(event.data, event.cleared)
                            }
                            if (handoff is EmitHandoff.NotQueued) {
                                println("SyncObserver -> a save's block was not queued (${handoff.why})")
                            }
                            // Realm epoch bump (§7.2) — AFTER the data's own
                            // blocks, and in its own catch, so a bump failure can
                            // never cost the write its block. Re-enters this
                            // channel when it does write (the epoch row is
                            // Syncable); the writer excludes its own type, so
                            // there is no loop.
                            try {
                                epochWriter.onLocalWrite(event.data)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                println("SyncObserver -> realm schema-epoch bump failed: $e")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }

                    is EmitEvent.Barrier -> event.signal.complete(Unit)

                    is EmitEvent.OpenLane -> {
                        lanes.getOrPut(event.laneId) { Lane(event.laneId) }
                        event.ack.complete(Unit)
                    }

                    is EmitEvent.CheckpointLane -> {
                        try {
                            lanes[event.laneId]?.let { checkpointLane(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        } finally {
                            event.ack.complete(Unit)
                        }
                    }

                    is EmitEvent.CloseLane -> {
                        try {
                            lanes.remove(event.laneId)?.let { checkpointLane(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        } finally {
                            event.ack.complete(Unit)
                        }
                    }

                    is EmitEvent.ReplayLaneJournal -> {
                        try {
                            replayLaneJournalOnConsumer()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        } finally {
                            event.ack.complete(Unit)
                        }
                    }
                }
            }
        }
    }

    /**
     * ⭐ The content declarations (`content-plane-cr2-b3-wire-spec.md`), rebuilt only when a module registers (null without a
     * [SchemaRegistry], i.e. a wiring that cannot name content-bearing types at all).
     */
    private val contentDeclarations =
        (executionContext.schemaProvider as? SchemaRegistry)?.let(::ContentDeclarationsCache)

    /**
     * ⭐ `content-plane-cr2-quorumless.md` §10.12 Q7: a save that **names content**, or that **replaces a stored row that
     * named content**, is refused its lane and emitted standalone, so its block carries its edges and
     * meets the relay's per-push gate. Only a save naming none over a row naming none (or no row) may
     * stay inside a `DeltaBatchPayload`, where the relay can see no edge at all.
     *
     * ⚠⚠ Runs on the saving thread BEFORE the write ([LaneAwareDataObserver.admitsToLane]): the row
     * this save replaces is readable only now. A batched clear would leave the old edge standing for
     * ever, contradicting G1's complete-edge-set invariant.
     *
     * ⚠ "Cannot say" ([ContentDeclarations.edgesOf] answering null) refuses too: standalone is always
     * correct, merely unbatched.
     */
    override fun admitsToLane(data: Data, origin: WriteOrigin, syncLaneId: String): Boolean {
        if (origin == WriteOrigin.SYNC_INGEST || origin == WriteOrigin.DERIVED) return true
        val declarations = contentDeclarations?.get() ?: return true
        val edges = declarations.edgesOf(data) ?: return false
        // The type and every ancestor reference no content: nothing a batch could hide.
        if (edges.isEmpty()) return true
        if (edges.any { it.contentId != null }) return false
        val dataId = executionContext.getDataId(data) ?: return true
        val stored = runCatching { executionContext.database.getData(dataId, asEntity = true) }.getOrNull()
            ?: return true
        val storedEdges = declarations.edgesOf(stored) ?: return false
        return storedEdges.none { it.contentId != null }
    }

    /**
     * The edges the block carrying [data] as a whole row owes the relay — `contentEdgesOf` of the
     * **payload in hand**, never a store re-read (`content-plane-cr2-quorumless.md` §10.11).
     *
     * ⚠ Empty for a type that references no content (and for a wiring with no [SchemaRegistry]).
     * ⚠ "Cannot say" also yields empty and is reported: the relay then keeps the holder's previous
     * registers, a leak, never a loss; the cause is a content-bearing property declared without a
     * `ContentDerivation`, which `ContentEdgesEquivalenceTest` exists to catch.
     */
    private fun edgesOfRow(data: Data): List<ContentEdge> {
        val declarations = contentDeclarations?.get() ?: return emptyList()
        return declarations.edgesOf(data) ?: run {
            println(
                "SyncObserver -> cannot name the content edges of a ${(data as? Entity<*>)?.type?.get() ?: data.id} " +
                    "row (a content-bearing property lacks its ContentDerivation); pushing it with none"
            )
            emptyList()
        }
    }

    /**
     * ⭐ `content-plane-cr2-b4-spec.md` §2.3: the content ids this save **clears** — named by the row it replaces, not by the
     * new edges — read on the saving thread before the write, the one moment the replaced row exists.
     * They ride to the block's edge sidecar, where the drain uses them to push this block after a later
     * one (another holder) that adds the same content: a move's new path before its old path's clear.
     *
     * ⚠ Null (nothing to carry) for an ingest or derived save, a type naming no content, a new row, or
     * a save that keeps every id. "Cannot say" for either side is also null: ordering is lost, not edges.
     */
    override fun prepareSave(data: Data, origin: WriteOrigin): Any? {
        if (origin == WriteOrigin.SYNC_INGEST || origin == WriteOrigin.DERIVED) return null
        return prepareCleared(data)
    }

    private fun prepareCleared(data: Data): Any? {
        val declarations = contentDeclarations?.get() ?: return null
        val edges = declarations.edgesOf(data) ?: return null
        if (edges.isEmpty()) return null
        val dataId = executionContext.getDataId(data) ?: return null
        val stored = runCatching { executionContext.database.getData(dataId, asEntity = true) }.getOrNull()
            ?: return null
        // ⚠ Non-regenerable only: a regenerable rung is never `content_freed` (the relay un-frees it on
        // the push that names it, B4 §1.3), so nothing has to wait for it.
        val storedIds = declarations.edgesOf(stored)?.filter { !it.regenerable }?.mapNotNull { it.contentId }?.toSet()
            ?: return null
        val cleared = storedIds - edges.mapNotNull { it.contentId }.toSet()
        return cleared.takeIf { it.isNotEmpty() }?.let(::ClearedContents)
    }

    /** [prepareSave]'s token. */
    private class ClearedContents(val ids: Set<String>)

    override fun onDataSaved(data: Data, origin: WriteOrigin, syncLaneId: String?, prepared: Any?) {
        val cleared = (prepared as? ClearedContents)?.ids.orEmpty()
        onSaved(data, origin, syncLaneId, cleared)
    }

    override fun onDataSaved(data: Data, origin: WriteOrigin, syncLaneId: String?) =
        onSaved(data, origin, syncLaneId, emptySet())

    private fun onSaved(data: Data, origin: WriteOrigin, syncLaneId: String?, cleared: Set<String>) {
        // Skip block generation when the save came from ingesting a peer's
        // SyncBlock — without this guard the observer would echo every received
        // block back out, producing an infinite round-trip at network speed.
        // (Checked first: an ingest save can only happen while enabled anyway.)
        // ⚠⚠ DERIVED joins SYNC_INGEST here. Both mean "this save carries no new information
        // for peers": an ingest echo would duplicate a block, a derived echo would broadcast one
        // device's local recomputation as if it were news — see [WriteOrigin.DERIVED] for the
        // measured incident.
        if (origin == WriteOrigin.SYNC_INGEST || origin == WriteOrigin.DERIVED) return
        // Locked/initialising (no keys): hold for re-emit on enable rather than dropping — a save
        // dropped here never re-emits and the entity is stranded local forever. The lane tag is
        // deliberately NOT preserved across the hold: a replayed save emits per-save (correct,
        // just unbatched) — the lane's own lease/close semantics belong to the live task.
        if (!syncManager.isEnabled()) {
            pendingSaves.trySend(HeldSave(data, cleared))
            return
        }
        // Non-blocking handoff — no serialize/encrypt/HLC on the saveData thread.
        emitChannel.trySend(EmitEvent.Saved(data, syncLaneId, cleared))
    }

    /**
     * Suspends until every [EmitEvent.Saved] enqueued before this call has been
     * processed (block generated + queued to `pendingBlocks`). Sends a [Barrier]
     * and awaits it: the channel is FIFO and single-consumer, so the barrier
     * completes only after all prior saves. Used by tests that inspect
     * `pendingBlocks` right after `saveData`. ⚠ The headless background drain does **not**
     * call this — if it adopts drain-and-join before releasing its wakelock (§A.6), this is
     * the seam.
     */
    override suspend fun awaitEmitIdle() {
        val signal = CompletableDeferred<Unit>()
        emitChannel.send(EmitEvent.Barrier(signal))
        signal.await()
    }

    /**
     * Replay every Syncable save / file-content emit that was held because it landed while the
     * realm was locked (`!syncManager.isEnabled()`). Driven by [SyncScheduler] on the
     * locked→enabled edge (the realm just unlocked / finished autoloading its keys), so the work
     * flows through the normal emit path with keys available. Idempotent and cheap when the
     * hold queues are empty (the common case). Suspends because the content replay
     * ([emitFileContent]) is suspend.
     */
    suspend fun reemitPendingLockedWork() {
        while (true) {
            val held = pendingSaves.tryReceive().getOrNull() ?: break
            emitChannel.trySend(EmitEvent.Saved(held.data, cleared = held.cleared))
        }
        while (true) {
            val c = pendingContent.tryReceive().getOrNull() ?: break
            // A replay failure (transient store error) is no worse than the original emit failing;
            // don't let one held item's failure abort the rest of the drain.
            bestEffort("SyncObserver -> replaying a held content emit") {
                val bound = c.bound
                if (bound != null) {
                    // ⚠⚠ As parked, never re-resolved: the row may name other content by now.
                    withEmitKey(bound.emitKey) { emitFileContentOwned(bound, force = false) }
                } else {
                    emitFileContent(c.entityId, c.propertyId, c.filePath)
                }
            }
        }
        // Lane journal deltas held over from a locked window (keys vanished between
        // checkpoints) seal with the keys back. Idempotent no-op when empty.
        replaySyncLaneJournal()
    }

    // ---- Metered content gate: durable deferral journal (sync-metered-plane-split.md §4) ----

    private val pendingContentEmitsRef = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = PendingContentEmit.ID,
            relationId = SyncService.Collections.PENDING_CONTENT_EMITS,
        ),
        isEntity = false,
        elementSchemaId = PendingContentEmit.ID,
    )

    /**
     * Park a content emit the closed [contentGate] refused, **last-wins per content** (D5):
     * re-uploading the same content before the drain replaces its intent instead of queuing a
     * second emit, which would otherwise make the journal grow once per save across a long
     * cellular stretch and upload the same bytes repeatedly on reconnect.
     *
     * ⚠ `addToArray` **appends** — the `DataIdProvider` key alone does not upsert. The
     * remove-then-add inside one transaction is what makes it last-wins, the same idiom
     * `initSyncHealth` uses for its single-row relation.
     *
     * Raw `database.*` (not `ExecutionContext`) — a pure local sidecar that must not re-fire
     * observers, mirroring `DataIdMigrator.beginMarker`.
     */
    private fun journalDeferredContent(target: ContentTarget) {
        val emitKey = target.emitKey
        // ⚠⚠ Carry any parked resume state forward. This write runs at the TOP of every
        // `emitFileContent`, so building a fresh entry here silently erases what the previous
        // attempt got onto the relay — the retry then re-uploads chunks that were already there,
        // which is the exact waste resumable emits exist to remove. Pinned by
        // `anAbortedEmitParksWhatLandedAndTheRetrySendsOnlyTheRest`.
        val existing = readDeferredContent(emitKey)
        // ⚠⚠ ...but only while it still describes these bytes. The parked chunks were encrypted
        // against the bytes the entry's hash names; stamping a NEW hash over OLD landed chunks would
        // make the emit's own guard pass and splice two files under one manifest. One content id
        // has one hash in production, so this only fires for a wiring that re-states a hash —
        // which is exactly when it matters.
        val parkedHash = existing?.contentHash?.get().orEmpty()
        val resumeStillApplies = parkedHash.isEmpty() || target.contentHash == null || parkedHash == target.contentHash
        val entry = PendingContentEmit.create(
            context = executionContext.context,
            emitKey = emitKey,
            targetEntityId = target.holder,
            propertyId = target.propertyId,
            filePath = target.filePath,
            // ⚠ The expected hash when the row stores one, else whatever guard a parked attempt
            // left: for a content-keyed entry the two cannot differ (one content, one hash), and an
            // entity-keyed entry keeps exactly the value it had.
            contentHash = target.contentHash ?: parkedHash,
            wrappedCek = if (resumeStillApplies) existing?.wrappedCek?.get().orEmpty() else "",
            landedChunkHashes = if (resumeStillApplies) existing?.landedChunkHashes?.get().orEmpty() else "",
            contentId = target.contentId.orEmpty(),
        )
        val entryId = DataId.StringDataId.create(
            context = executionContext.context,
            type = PendingContentEmit.ID,
            keyValue = emitKey,
        )
        executionContext.database.transaction {
            executionContext.database.removeFromArray(pendingContentEmitsRef, entryId)
            executionContext.database.addToArray(pendingContentEmitsRef, entry)
        }
    }

    /** What [drainOne] decided for an entry before anything was uploaded. */
    private enum class DrainVerdict { PUBLISH, PUBLISH_VERIFIED, KEEP, RETIRE }

    /** What one drained entry did, decided while its emit key is still held. */
    private data class DeferredDrainOutcome(
        val emitted: Boolean,
        /** Cleared from the journal — emitted, or gone and unreachable for ever. */
        val retired: Boolean,
        /** This entry, not a previous one, met a `507`. */
        val refused: Boolean,
    )

    /**
     * How many deferred content emits [drainDeferredContent] has actually uploaded, since process
     * start — the push job's content progress signal (`control-background-work-off-fgs.md` §5.3).
     * ⚠ Not `onProgress`'s `completed`: that counts *resolved* entries, a failed one included, so a
     * pass where every PUT is answered 500 would read as eight minutes of progress.
     */
    @kotlin.concurrent.Volatile
    var deferredContentEmitted: Long = 0
        private set

    /**
     * Content chunks the relay accepted on ANY path since process start — the inline upload on a
     * client's call included (`control-background-work-off-fgs.md` §5.7).
     *
     * ⚠⚠ The push job's other progress signal. An automatic backup uploads each file's content inside
     * the client's call, and the drain answers that entry "someone already emitting", so a run that
     * counted only [deferredContentEmitted] would see two minutes of "sent nothing" during one long
     * video, end `STALLED` and park — losing Control's network the moment the client's own job
     * ends mid-upload. Every chunk that lands is the plane moving.
     */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private val contentChunksLanded = kotlin.concurrent.atomics.AtomicLong(0)

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    val contentChunksLandedTotal: Long get() = contentChunksLanded.load()

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private fun countChunkLanded() {
        contentChunksLanded.addAndFetch(1L)
    }

    /**
     * File-content emits that **completed** — every chunk and the manifest landed — on any path,
     * since process start.
     *
     * ⚠⚠ What ends the push job's park, and not [contentChunksLandedTotal] nor accepted metadata:
     * with only the content plane failing, metadata blocks keep landing and a file that keeps
     * changing re-sends its chunks every attempt, so either signal would un-park the push every 2–3
     * minutes into another failing run. A completed emit is the plane actually working.
     */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private val contentEmitsLanded = kotlin.concurrent.atomics.AtomicLong(0)

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    val contentEmitsLandedTotal: Long get() = contentEmitsLanded.load()

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private fun countEmitLanded() {
        contentEmitsLanded.addAndFetch(1L)
    }

    /** Whether the content plane is waiting out refused writes (§5.7) — the scheduler's seam. */
    fun contentPlaneWaiting(): Boolean = contentEmitBackoff.planeWaiting()

    /** How long it still waits, in ms; 0 when it does not. */
    fun contentPlaneWaitRemainingMs(): Long = contentEmitBackoff.planeWaitRemainingMs()

    /** Let the next content write through a waiting plane — [ContentEmitBackoff.halfOpen]. */
    fun retryContentPlane() = contentEmitBackoff.halfOpen()

    /** Whether the last pass reported entries waiting; the log line says only its changes. */
    private var waitingReported = false

    /**
     * Push every deferred file's content now, clearing each intent as its bytes land. Driven by
     * [SyncScheduler] once per tick; a no-op (one array read) when the journal is empty, which is
     * the common case.
     *
     * [force] carries the user's one-shot *"upload over cellular"* override: it replays past the
     * gate that parked these in the first place. Without it, a still-closed gate means there is
     * nothing to do — replaying would only re-journal what is already journalled.
     *
     * **Re-resolved, never replayed blindly** (D6): [emitFileContentNow] reads today's bytes for
     * the recorded path, so a file modified while deferred uploads its *current* content. An entry
     * whose file has since been deleted can never succeed — `fileSize` returns null and the emit
     * returns false — so it is dropped rather than retried forever (its tombstone already reached
     * peers on the ungated metadata plane). A *transient* failure (relay down) keeps its entry, so
     * the next tick retries; that is the same posture the block drain takes.
     *
     * Returns the number of entries still waiting when it finishes.
     */
    suspend fun drainDeferredContent(
        force: Boolean = false,
        /**
         * Called as each file finishes, with `(completed, total)` for THIS pass — the figures
         * behind *"Uploading 3 of 12"* (`drive-upload-progress.md` P2). Invoked once before the
         * first upload (`0, total`) so the banner shows the denominator immediately rather than
         * after the first file lands, and once per completion after that. `total` is fixed at the
         * start of the pass: entries added mid-pass belong to the next one, so the denominator
         * cannot move under the user.
         *
         * ⚠ Unthrottled by design — the caller decides the publish cadence, because only it knows
         * how expensive a publish is. `SyncScheduler` throttles before touching the store.
         */
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val pending = executionContext.database.getArrayData(pendingContentEmitsRef)
            ?.filterIsInstance<PendingContentEmit>()
            ?.toList()
            ?: return 0
        if (pending.isEmpty()) return 0
        if (!force && !contentGate.shouldSync()) return pending.size

        var remaining = 0
        var waiting = 0
        // ⚠ Progress is in FILES, the unit of [deferredContentFigures]: a photo is its data plus
        // every thumbnail rung, one entry each, and counting entries here would make the banner flap
        // between "Waiting for Wi-Fi. 1 file" and "Uploading 2 of 5". A file completes once all
        // of its entries in this pass have.
        val entriesLeftPerFile = pending.groupingBy { it.targetEntityId.getDataIdHash() }.eachCount().toMutableMap()
        val totalFiles = entriesLeftPerFile.size
        var completed = 0
        onProgress(0, totalFiles)
        // ⚠⚠ Per-pass, and deliberately NOT [contentStorageRejected] itself. That field is sticky —
        // it has to outlive this pass so the inline emit path can consult it between drains — so
        // reading it directly here would make an entry rejected on the *previous* pass skip this
        // one before the probe has even run. This one is about "the relay just said no, now, so the
        // rest of this queue is pointless".
        var rejectedThisPass = false
        pending.forEach { entry ->
            // Reset per entry: the flag below answers "did THIS emit meet a 507", which is the only
            // thing that should stop the rest of the queue.
            storageRefusalSeenThisEntry = false
            // ⚠⚠ The rest of the queue meets the same quota check, so once the relay has answered
            // `507` this pass is over. Continuing costs a measured ~30 rejected `PUT`s/second — each
            // one a full chunk uploaded before the relay reads far enough to refuse it — sustained
            // for as long as the device stays over quota. Counted as still-waiting, not dropped:
            // these entries are perfectly valid and go out as soon as there is room.
            if (rejectedThisPass) {
                remaining++
                return@forEach
            }
            // ⚠⚠ Still waiting out its last failed upload (§5.7): not tried, counted as waiting. A
            // `force` is the user's own "upload over cellular now", which a wait must not override.
            // A landing earlier in this pass already cleared every wait ([ContentEmitBackoff.landed]).
            // ⚠⚠ And the whole plane, once several writes in a row were refused: without it a
            // 1,095-entry backlog's FIRST attempts were ~9,000 failing PUTs in 3.5 min on the
            // emulator before any per-entry wait could apply. ⚠⚠ Re-read for EVERY entry, never
            // skipped because something landed earlier in the pass: against a relay that answers
            // ~80% of PUTs 503, one landing would ignore every re-trip for the rest of the journal
            // — the storm back.
            if (!force &&
                (contentEmitBackoff.planeWaiting() || contentEmitBackoff.isWaiting(entry.emitKey.get()))
            ) {
                remaining++
                waiting++
                return@forEach
            }
            // ⚠⚠ Claimed for the whole emit, not merely tested. Someone already emitting this
            // exact content is counted as still waiting rather than dropped — the inline emit
            // clears the entry itself on success, and if it fails the entry is still here for the
            // next pass. ⚠ The entry's OWN key, which is the content's, not the row's.
            val outcome = tryWithEmitKey(entry.emitKey.get()) { drainOne(entry) }
            // ⚠ Nobody owns an entry another path is already emitting: it clears the entry itself
            // on success, and if it fails the entry is still here for the next pass.
            if (outcome == null) {
                remaining++
                return@forEach
            }
            // ⚠⚠ Keyed on a FRESH refusal, not the sticky level. Reading the level would mean that
            // once latched, the first entry to fail for ANY reason — a relay 500, offline, a file
            // since deleted — ends the pass and looks like a quota refusal: a 605-entry backlog
            // headed by one dead file would drain one entry per pass while reporting storage trouble. The
            // level is about "may the inline path try"; only a refusal *this entry* says the rest
            // of this queue is pointless.
            if (!outcome.emitted && outcome.refused) rejectedThisPass = true
            if (outcome.emitted) deferredContentEmitted++
            when {
                outcome.emitted -> contentEmitBackoff.landed()
                outcome.retired -> contentEmitBackoff.cleared(entry.emitKey.get())
                // A refused write is recorded where it happens (`emitFileContentNow`), on this
                // path and the inline one alike.
                else -> Unit
            }
            if (!outcome.retired) remaining++
            // Counts resolved entries, not successful ones: a dropped dead intent is progress too
            // (it leaves the queue), and a bar that stalls on an entry that can never succeed is
            // worse than one that moves past it.
            val file = entry.targetEntityId.getDataIdHash()
            val left = entriesLeftPerFile.getValue(file) - 1
            entriesLeftPerFile[file] = left
            if (left == 0) {
                completed++
                onProgress(completed, totalFiles)
            }
        }
        // ⚠ Said when waiting starts and when it ends, not per pass nor per change: the drain runs
        // every few seconds under a push job, and the jittered waits end one by one, so a line per
        // change would still be a line per pass.
        if ((waiting > 0) != waitingReported) {
            waitingReported = waiting > 0
            println(
                if (waitingReported) {
                    "SyncObserver -> content drain: $waiting of ${pending.size} entries waiting out a failed upload (backoff)"
                } else {
                    "SyncObserver -> content drain: no entry waiting out a failed upload any more"
                },
            )
        }
        return remaining
    }

    /**
     * One journal entry's pass, decided while its key is held.
     *
     * ⚠⚠ **The bytes decide, not the row.** The row the entry names is a *hint*: after a peer's
     * move it is retired, after a peer's overwrite it names other content, and after a path is
     * reused it names a stranger. None of those says anything about whether this device still holds
     * this content; read as if it did, a retired row resolves no content id, the emit falls through
     * to the legacy mailbox branch, and the intent is cleared as done with nothing any reader could
     * resolve.
     *
     * | the bytes at [PendingContentEmit.filePath] | verdict |
     * |---|---|
     * | the holder still names this content there | publish — the fast path, trusting the row |
     * | absent, and a live holder names this content | **keep** — a local move is relocating them |
     * | absent otherwise | retire as gone — deleted here, or never recoverable from here |
     * | present, hash known | publish if they hash to this content, else retire as superseded |
     * | present, hash unknown | retire if the holder now names *other* content, else publish |
     *
     * ⚠ "Keep" never becomes "drop" on absence alone while a row still wants the content: that is
     * the in-process window of a local move, where the bytes have moved and the entry has not yet
     * been retargeted, and dropping there deletes the only record of an upload whose bytes are one
     * directory over.
     */
    private suspend fun drainOne(snapshot: PendingContentEmit): DeferredDrainOutcome {
        // ⚠⚠ Re-read under the claim, never the pass's snapshot. A local move
        // retargets an entry IN PLACE — same key, new holder, new path — so between the pass's read
        // and this claim the snapshot can describe a row and a path that no longer exist. Judged on
        // it, the entry looks gone and the retire deletes the freshly retargeted row: a moved upload,
        // stranded by the drain that was meant to send it.
        val entry = readDeferredContent(snapshot.emitKey.get())
            ?: return DeferredDrainOutcome(emitted = false, retired = true, refused = false)
        // ⚠ The realm locked mid-pass: leave the entry exactly as it is for the pass after unlock. No
        // verdict on a locked realm — the entry is the intent, and nothing here may park a copy of it.
        if (!syncManager.isEnabled()) return DeferredDrainOutcome(emitted = false, retired = false, refused = false)
        val contentId = entry.contentId.get()
        if (contentId.isEmpty()) return drainEntityKeyed(entry)

        val holder = entry.targetEntityId
        val propertyId = entry.propertyId.get()
        val filePath = entry.filePath.get()
        val port = executionContext.context.externalPropertyWriter()
        // ⚠ Null is "retired" OR "unreadable" — `resolveContentId` swallows a failed read. Every
        // branch below that acts on a null does so only in the direction that keeps content.
        val holderContentId = port?.resolveContentId(executionContext, holder, propertyId)
        val holderNamesIt = holderContentId == contentId
        val holderPath = port?.resolveExternalPath(executionContext, holder, propertyId)
        val expectedHash = entry.contentHash.get().ifEmpty { null }
        val bytesHere = fileDatabase?.fileSize(filePath) != null

        val target = ContentTarget(
            holder = holder,
            propertyId = propertyId,
            filePath = filePath,
            contentId = contentId,
            contentHash = expectedHash,
            // ⚠⚠ Never parked: the journal entry IS this emit's durable intent. A park would hold a copy of the entry's holder and path, and a local move that
            // retargets the entry meanwhile would be undone by the replay's journal write — the moved
            // file's upload then retired as "no bytes here".
            parkIfLocked = false,
        )

        // ⚠⚠ The drain READS the published marker, not only writes it. An entry can outlive a publish
        // of its content — `ensureContentOnRelay` finds content another device published (derived
        // thumbnail rungs share one id everywhere), declares this holder and returns without touching
        // the journal — and draining it would re-upload the content under a fresh CEK, leaving the
        // other chunk set undescribed. The marker is set only for a DECLARED publish
        // (`recordPublish`), so edge and handle have landed and the entry's work is done. ⚠ Also the
        // KEEP rule's only exit: bytes gone, holder still naming the content, but already published —
        // nothing is left to protect.
        // ⚠⚠ The marker is a HINT, confirmed with the relay before anything is retired. It is in
        // memory and never cleared by a move, so it can be stale: publish at /a, move to /b, park a
        // repair there (the relay lost the content), move back — the retarget names /a again and
        // /a's old marker matches, which would delete the only repair intent. One request, only for
        // an entry that hits a marker.
        // ⚠ Only while the holder still NAMES this content; otherwise the rules below decide, as for any
        // entry whose row moved on.
        // ⚠ Also when the holder read FAILED (null is "retired" or "unreadable") while the bytes are here:
        // skipping the marker there would fall through to PUBLISH — a duplicate. The declare
        // below re-reads the holder and refuses on anything but this content, so the entry is kept for the
        // next pass. With the bytes gone nothing could be published anyway, and the rules below retire it.
        val markerKey = publishedKey(target)
        val markerApplies = holderNamesIt || (holderContentId == null && bytesHere)
        if (markerApplies && markerKey != null && emitKeyLocks.wasPublished(markerKey)) {
            val settled = resolveAndDeclareSettled(holder, propertyId, contentId)
            when (settled.resolvability) {
                ContentResolvability.RESOLVABLE -> {
                    // ⚠⚠ RESOLVABLE proves the bytes exist, not that the relay's description matches the
                    // manifest: a forced repair through ANOTHER holder of this content
                    // can publish fresh refs and fail its declare. So this holder is declared against the
                    // refs the manifest names BEFORE its entry goes, and the entry is kept when that fails
                    // or cannot settle. ⚠ The marker stays: unmarked, the next pass would re-upload.
                    if (!settled.declared) {
                        return DeferredDrainOutcome(emitted = false, retired = false, refused = false)
                    }
                    if (!clearDeferredContentIfUnchanged(entry)) {
                        return DeferredDrainOutcome(emitted = false, retired = false, refused = false)
                    }
                    println("SyncObserver -> dropping deferred content emit ($filePath, contentId=$contentId): already published")
                    return DeferredDrainOutcome(emitted = false, retired = true, refused = false)
                }
                // The relay lost it: the marker is stale, and this entry is exactly the upload still owed.
                ContentResolvability.ABSENT -> emitKeyLocks.unmarkPublished(markerKey)
                // ⚠ Could not ask, or another emit of it kept running: neither retire on it nor re-upload
                // a probably-published content.
                ContentResolvability.UNKNOWN, null ->
                    return DeferredDrainOutcome(emitted = false, retired = false, refused = false)
            }
        }

        val verdict = when {
            // ⚠ A writer that exposes no path (`resolveExternalPath` defaults to null) cannot say
            // the bytes moved, so a holder that still names the content is trusted as it always was.
            holderNamesIt && bytesHere && (holderPath == null || holderPath == filePath) -> DrainVerdict.PUBLISH
            !bytesHere -> if (holderNamesIt) DrainVerdict.KEEP else DrainVerdict.RETIRE
            expectedHash != null -> when (wholeFileHashAt(filePath)) {
                expectedHash -> DrainVerdict.PUBLISH_VERIFIED
                // ⚠ Unreadable is not "someone else's": a read that failed says nothing about whose
                // bytes these are, and retiring on it deletes the only record of an upload.
                null -> DrainVerdict.KEEP
                else -> DrainVerdict.RETIRE
            }
            // ⚠ With no stored hash the holder is all there is to go on. The only contents without one
            // are derived thumbnail rungs — re-mintable from the file by any byte holder — and workout
            // streams, whose rows never move; so a holder that names OTHER content has superseded this
            // one. ⚠⚠ Contact photos DO move and come back (an offline peer's last-writer edit), so their
            // producer journals a hash (bound overload) and they take the PUBLISH_VERIFIED branch above.
            // Publishing it anyway is what an overwrite made while the rungs were parked would do.
            holderContentId != null -> DrainVerdict.RETIRE
            else -> DrainVerdict.PUBLISH
        }
        if (verdict == DrainVerdict.KEEP || verdict == DrainVerdict.RETIRE) {
            if (verdict == DrainVerdict.RETIRE) {
                // ⚠ Compare-and-delete: a retarget can still land after the re-read above, and it
                // runs in a store transaction too — so the two serialise, and a row that moved on is
                // left for the next pass rather than deleted on evidence about where it used to be.
                if (!clearDeferredContentIfUnchanged(entry)) {
                    return DeferredDrainOutcome(emitted = false, retired = false, refused = false)
                }
                println(
                    "SyncObserver -> dropping deferred content emit ($filePath, contentId=$contentId): " +
                        if (!bytesHere) "no bytes here any more" else "the bytes here are no longer this content"
                )
            }
            return DeferredDrainOutcome(emitted = false, retired = verdict == DrainVerdict.RETIRE, refused = false)
        }
        var declared = false
        val emitted = try {
            emitFileContentNow(
                if (verdict == DrainVerdict.PUBLISH_VERIFIED) target.copy(verifyWhileStreaming = true) else target
            ) { declared = it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            println("SyncObserver -> deferred content emit failed ($filePath): $t")
            false
        }
        // ⚠⚠ The clear belongs INSIDE the claim, not after it. Releasing the key first leaves a gap
        // in which a new inline upload of the same content claims the key and journals its own,
        // newer intent — and this clear then deletes *that*. The entry this pass is entitled to
        // retire is the one it emitted.
        if (emitted) {
            // ⚠⚠ A deferred upload published HERE must be marked too, or the next ordinary emit of the
            // same content re-uploads it under a fresh CEK — the metered/quota-deferred path. Same key
            // the inline path uses.
            // ⚠ Marked BEFORE the clear: the clear is a store transaction and can throw, and an entry left
            // behind must then find the marker and retire rather than publish a second chunk set.
            publishedKey(target)?.let { emitKeyLocks.recordPublish(it, declared) }
            clearDeferredContent(entry.emitKey.get())
        }
        return DeferredDrainOutcome(
            emitted = emitted,
            retired = emitted,
            // The emit latches [contentStorageRejected] on a `507`; observing it is what turns one
            // refusal into "stop the pass" rather than 605 more doomed uploads.
            refused = storageRefusalSeenThisEntry,
        )
    }

    /**
     * An entry keyed on the row rather than the content: the row resolved no content id when it was
     * journalled (a non-content-addressed holder — the entity-keyed mailbox path).
     *
     * ⚠⚠ An entry whose row is gone cannot be told apart from content that was deleted when the
     * property is content-addressed: what it was for died with the row. It is dropped, loudly.
     */
    private suspend fun drainEntityKeyed(entry: PendingContentEmit): DeferredDrainOutcome {
        val holder = entry.targetEntityId
        val propertyId = entry.propertyId.get()
        val filePath = entry.filePath.get()
        val target = resolveContentTarget(holder, propertyId, filePath)
        val rowExists = executionContext.database.getData(holder) != null
        // ⚠ "Its row is gone" condemns the entry only for a property a content-addressed writer
        // owns — there the content id died with the row. A wiring whose writer never addresses by
        // content asks only whether the bytes are still there.
        val addressedByContent = executionContext.context.externalPropertyWriter()?.supports(propertyId) == true
        val identityLost = !rowExists && addressedByContent
        val gone = identityLost || fileDatabase?.fileSize(filePath) == null
        if (gone) {
            clearDeferredContent(entry.emitKey.get())
            println(
                "SyncObserver -> dropping deferred content emit for $filePath: " +
                    if (identityLost) "its row is gone and it was never content-keyed, so what it was for is unknown"
                    else "the file no longer exists"
            )
            return DeferredDrainOutcome(emitted = false, retired = true, refused = false)
        }
        // A row that exists and resolves no content id: the entity-keyed mailbox path.
        val emitted = try {
            emitFileContentNow(target)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            println("SyncObserver -> deferred content emit failed ($filePath): $t")
            false
        }
        if (emitted) clearDeferredContent(entry.emitKey.get())
        return DeferredDrainOutcome(emitted = emitted, retired = emitted, refused = storageRefusalSeenThisEntry)
    }

    /**
     * The whole-file hash of the bytes at [filePath], folded at the emit's own chunk boundary.
     *
     * ⚠⚠ The boundary is not a detail: [IncrementalSha256] is a hash-CHAIN, order- and
     * boundary-sensitive, so a hash folded at any other stride never equals the stored one and every
     * verified entry would read as superseded. ⚠ A full read pass, taken only off the fast path —
     * when the row has stopped vouching for the bytes — and BEFORE any `PUT`, so bytes that turn out
     * to be someone else's are never uploaded under this content's address.
     */
    private fun wholeFileHashAt(filePath: String): String? {
        val db = fileDatabase ?: return null
        val size = db.fileSize(filePath) ?: return null
        val hasher = IncrementalSha256()
        if (size == 0L) {
            hasher.update(ByteArray(0))
            return hasher.digest().toHexLower()
        }
        var offset = 0L
        while (offset < size) {
            val chunk = db.readBytesRange(filePath, offset, FileContentChunkSizeBytes) ?: return null
            if (chunk.isEmpty()) break
            hasher.update(chunk)
            offset += chunk.size
        }
        return hasher.digest().toHexLower()
    }

    /**
     * What the metered content gate is currently holding — the banner's "N files waiting" figure
     * and the bytes behind it. Bytes are read per entry from [fileDatabase] (the same lookup the
     * drain's gone-check makes), so an entry whose file has vanished contributes 0 rather than a
     * stale size.
     *
     * ⚠ The count is distinct HOLDERS, not entries: the journal holds one entry per
     * `(content, property)`, so one photo parks its data plus every thumbnail rung and would read
     * as "5 files". The bytes still sum every entry — the thumbnails do cross the metered link.
     */
    suspend fun deferredContentFigures(): DeferredContentFigures {
        val pending = executionContext.database.getArrayData(pendingContentEmitsRef)
            ?.filterIsInstance<PendingContentEmit>()
            ?.toList()
            ?: return DeferredContentFigures()
        return DeferredContentFigures(
            count = pending.distinctBy { it.targetEntityId.getDataIdHash() }.size,
            bytes = pending.sumOf { fileDatabase?.fileSize(it.filePath.get()) ?: 0L },
        )
    }

    /** The parked entry for one `(entity, property)`, or `null` when nothing is journalled. */
    private fun readDeferredContent(emitKey: String): PendingContentEmit? =
        executionContext.database.getData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = PendingContentEmit.ID,
                keyValue = emitKey,
            )
        ) as? PendingContentEmit

    /**
     * Park what an aborted emit already got onto the relay, so the next attempt sends only the rest
     * (`tracks/track-identity-and-reference.md` P1).
     *
     * ⚠⚠ **Measured:** without this a 20 MiB upload over a flaky link cost **3.00×** on
     * the relay and left 41,945,160 B unreachable — each attempt re-keys and re-`PUT`s every chunk,
     * so the multiplier is the attempt count.
     *
     * ⚠⚠ The CEK is stored **wrapped under the volume key**, byte-identical to what the manifest
     * carries. Raw would add no practical exposure — this device already holds the plaintext and the
     * volume key that unwraps every manifest — but wrapped keeps the entry useless in isolation,
     * which is the property [PendingContentEmit]'s own KDoc is built on.
     *
     * ⚠ [contentHash] is the guard, not decoration: the drain re-reads today's bytes, so a file
     * modified while parked must re-chunk from scratch. A mismatch discards the partial upload.
     */
    private fun recordEmitProgress(
        target: ContentTarget,
        targetEntityId: DataId,
        propertyId: String,
        filePath: String,
        contentHash: String,
        wrappedCekHex: String,
        landedChunkHashes: String,
    ) {
        val emitKey = target.emitKey
        val entry = PendingContentEmit.create(
            context = executionContext.context,
            emitKey = emitKey,
            targetEntityId = targetEntityId,
            propertyId = propertyId,
            filePath = filePath,
            contentHash = contentHash,
            wrappedCek = wrappedCekHex,
            landedChunkHashes = landedChunkHashes,
            // ⚠ The identity rides along, or the progress write erases what makes the entry
            // content-keyed and the next pass reads it as an entity-keyed row.
            contentId = target.contentId.orEmpty(),
        )
        val entryId = DataId.StringDataId.create(
            context = executionContext.context,
            type = PendingContentEmit.ID,
            keyValue = emitKey,
        )
        executionContext.database.transaction {
            executionContext.database.removeFromArray(pendingContentEmitsRef, entryId)
            executionContext.database.addToArray(pendingContentEmitsRef, entry)
        }
    }

    /**
     * What one content publish is FOR: resolved from the row when an emit starts, and read
     * back off the journal entry by the drain — never re-resolved from a row the drain cannot trust.
     *
     * @property holder the row that wants the content — a hint once journalled.
     * @property contentId null for a row with no content id: the entity-keyed mailbox path.
     * @property contentHash the whole-content hash when the row stores one.
     */
    private data class ContentTarget(
        val holder: DataId,
        val propertyId: String,
        val filePath: String,
        val contentId: String?,
        val contentHash: String?,
        /**
         * Hash the bytes AS THEY ARE STREAMED and refuse the manifest if they are not [contentHash].
         *
         * ⚠⚠ Set exactly when nothing else holds the location still. Off the fast path no claim
         * covers the path — a local recreate after a peer's move, or an on-demand fetch promoting a
         * newcomer's bytes, can write it between the drain's pre-check and its ranged reads — and a
         * manifest over those reads puts mixed bytes behind this content's address, stamped with its
         * hash, so every reader's integrity gate rejects the file the moved row names.
         */
        val verifyWhileStreaming: Boolean = false,
        /**
         * The content id (and hash) are the CALLER's, not re-read from the holder's row: a bound
         * producer's committed id, or a journal entry's. ⚠ Anything that holds the target for later
         * (the locked-window park) keeps it whole rather than re-resolving it.
         */
        val bound: Boolean = false,
        /**
         * Whether an emit landing on a locked realm is held for the unlock replay. ⚠ False for the
         * drain: its journal entry already is the durable intent, and a held copy would undo a
         * retarget made while locked.
         */
        val parkIfLocked: Boolean = true,
    ) {
        val emitKey: String
            get() = contentId?.let { PendingContentEmit.contentIdKeyOf(it, propertyId) }
                ?: PendingContentEmit.keyOf(holder.getDataIdHash().toString(), propertyId)
    }

    /**
     * The key of the intent for the content [holder] names now — [ContentTarget.emitKey] without
     * resolving the whole target (no content-hash read), which a key does not need.
     */
    private fun currentEmitKey(holder: DataId, propertyId: String): String =
        executionContext.context.externalPropertyWriter()
            ?.resolveContentId(executionContext, holder, propertyId)
            ?.let { PendingContentEmit.contentIdKeyOf(it, propertyId) }
            ?: emitKeyFor(holder, propertyId)

    /** [ContentTarget] as the row at [holder] states it now. */
    private suspend fun resolveContentTarget(holder: DataId, propertyId: String, filePath: String): ContentTarget {
        val port = executionContext.context.externalPropertyWriter()
        val contentId = port?.resolveContentId(executionContext, holder, propertyId)
        return ContentTarget(
            holder = holder,
            propertyId = propertyId,
            filePath = filePath,
            contentId = contentId,
            contentHash = contentId?.let { port.resolveContentHash(executionContext, holder, propertyId) },
        )
    }

    /**
     * The entity journal key for one `(entity, property)` pair. One derivation, used by the journal write,
     * the clear-on-success and the drain's clear — three call sites that must agree exactly, since
     * a mismatch would silently orphan an entry and re-upload the file on every pass.
     */
    private fun emitKeyFor(targetEntityId: DataId, propertyId: String): String =
        PendingContentEmit.keyOf(
            targetEntityIdHash = targetEntityId.getDataIdHash().toString(),
            propertyId = propertyId,
        )

    /**
     * [clearDeferredContent], but only while the stored row still says what [entry] says about where
     * its bytes are and which row holds them — checked and deleted in one store transaction.
     */
    private fun clearDeferredContentIfUnchanged(entry: PendingContentEmit): Boolean {
        val key = entry.emitKey.get()
        var cleared = false
        executionContext.database.transaction {
            val current = readDeferredContent(key)
            val unchanged = current == null || (
                current.filePath.get() == entry.filePath.get() &&
                    current.targetEntityId.getDataIdHash() == entry.targetEntityId.getDataIdHash()
                )
            if (unchanged) {
                clearDeferredContent(key)
                cleared = true
            }
        }
        return cleared
    }

    private fun clearDeferredContent(emitKey: String) {
        val entryId = DataId.StringDataId.create(
            context = executionContext.context,
            type = PendingContentEmit.ID,
            keyValue = emitKey,
        )
        executionContext.database.transaction {
            executionContext.database.removeFromArray(pendingContentEmitsRef, entryId)
            // ⚠⚠ Delete the ROW too, not just the membership. Removing only from the array leaves
            // the `PendingContentEmit` entity behind for every intent ever cleared — a leak that is
            // invisible by construction, because every counter and the drain itself read the
            // ARRAY: `deferredContentFigures()` reports 0, the banner shows nothing, and the rows
            // accumulate silently in the store.
            //
            // ⚠ Orphaned rows read exactly like stuck uploads — files × content properties,
            // last-wins keying, sitting next to photos still staged in `files/` — while the bytes
            // are on the relay. Nothing is lost, but it manufactures a state that looks like data
            // loss.
            //
            // ⚠ Safe to delete: `PendingContentEmit` carries only `DataIdProvider` — it is NOT
            // Syncable, so this emits no tombstone and cannot reach a peer.
            executionContext.database.deleteData(entryId)
        }
    }

    // ---- Task-scoped sync lane (core-background-processing.md §10.3) ----
    //
    // A lane batches the saves of one bulk task into DeltaBatchPayload container
    // blocks instead of one SyncBlock per save. All lane state below is
    // confined to the emit-consumer coroutine (stage rides EmitEvent.Saved;
    // open/checkpoint/close/replay are marshalled as events), so no locking.
    //
    // Scope (documented limits):
    //  - Personal-realm only: a lane-tagged save whose entity routes to a
    //    shared realm falls back to the ordinary per-save path (correct, just
    //    unbatched). Shared-realm bulk ingest has no consumer yet.
    //  - Saves that land while locked lose their lane tag (see onDataSaved).

    /** Checkpoint when a lane holds this many coalesced deltas (§10.5 D5). */
    private companion object LaneCaps {
        /** [resolveAndDeclareSettled]: re-dos before giving up on a content another emit keeps replacing. */
        const val MAX_SETTLE_ATTEMPTS = 5

        /** [resolveAndDeclareSettled]: how long to wait out another emit of the same content. */
        const val SETTLE_BUSY_WAIT_MS = 200L

        const val LANE_DELTA_CAP = 500
        /** ... or this many encoded payload bytes (§4.4 block-size budget). */
        const val LANE_BYTES_CAP = 256 * 1024
        /** Backstop: raw journal appends since the last checkpoint (same-entity hammering). */
        const val LANE_JOURNAL_CAP = 2_000
    }

    /** Opens (or re-opens, no-op) a sync lane — the gate: staging requires an opened lane. */
    override suspend fun openSyncLane(laneId: String) = laneEvent { EmitEvent.OpenLane(laneId, it) }

    /** Seals the lane's staged deltas into one `DeltaBatchPayload` block now (no-op when empty). */
    override suspend fun checkpointSyncLane(laneId: String) = laneEvent { EmitEvent.CheckpointLane(laneId, it) }

    /** Final checkpoint + lane teardown. */
    override suspend fun closeSyncLane(laneId: String) = laneEvent { EmitEvent.CloseLane(laneId, it) }

    /**
     * Seals every delta lingering in the durable lane journal (§10.3 D6) — a
     * prior session crashed (or lost keys) between checkpoints. Called from
     * `initSyncService` and from the locked→enabled edge
     * ([reemitPendingLockedWork]); idempotent and cheap when the journal is
     * empty. Cross-lane: all lingering deltas seal together (batch composition
     * is policy, not correctness — receivers merge per delta).
     */
    suspend fun replaySyncLaneJournal() = laneEvent { EmitEvent.ReplayLaneJournal(it) }

    private suspend fun laneEvent(event: (CompletableDeferred<Unit>) -> EmitEvent) {
        val ack = CompletableDeferred<Unit>()
        emitChannel.send(event(ack))
        ack.await()
    }

    /** Consumer-thread only. Mirrors [processSavedData]'s fan-out, staging instead of emitting. */
    private fun stageIntoLane(laneId: String, data: Data): EmitHandoff {
        // The explicit-open gate: a save tagged with an UNOPENED lane id
        // keeps the ordinary per-save path. Load-bearing: ProxyGateway threads
        // EVERY cancellable call's taskId as requestSyncLaneId, so an ordinary
        // L2 background op (AwaitPairing, folder-upload Bg variants) reaches
        // here with an id no one registered — without the gate its saves would
        // stage into a never-closing lane and strand until journal replay.
        // ⚠⚠ The fallback's answer is this save's answer, false included.
        val lane = lanes[laneId] ?: return processSavedData(data)
        var handoff: EmitHandoff = EmitHandoff.Queued
        val type = if (data is Entity<*>) data.type.get() else data.id
        val schema = executionContext.schemaProvider.getSchema(type) ?: data.schema
        val entityId = executionContext.getDataId(data)
        if (schema.hasTrait(Syncable.ID)) {
            handoff = handoff and stageDelta(lane, data, entityId, propertyId = null)
        }
        if (data is Entity<*>) {
            data.properties.forEach { (ref, value) ->
                val propertyId = ref.get()
                val propSchema = executionContext.schemaProvider.getSchema(propertyId)
                if (propSchema?.hasTrait(Syncable.ID) == true) {
                    handoff = handoff and stageDelta(lane, value, entityId, propertyId)
                }
            }
        }
        return handoff
    }

    private fun stageDelta(
        lane: Lane,
        value: Data,
        targetEntityId: DataId?,
        propertyId: String?,
    ): EmitHandoff {
        val targetHash = targetEntityId?.let { wireIds.of(it) }
        // Fallbacks (see section comment): shared-realm entities and
        // DataId-less values keep the ordinary per-save path.
        val isPersonal = realmRegistry == null ||
            realmRegistry.realmForDataId(targetEntityId)?.let { realmRegistry.isPersonal(it) } != false
        if (!isPersonal || targetHash == null) {
            return generateSyncBlock(value, targetEntityId, propertyId)
        }

        val hlc = syncManager.hlcManager.tick()
        val delta = SyncDelta.create(
            context = executionContext.context,
            hlc = hlc,
            value = value,
            targetEntityIdHash = targetHash,
            propertyId = propertyId,
            deltaId = generateId(),
        )
        val encodedSize = BinarySerializer().serialize(delta).size

        // Durable journal first (D6): the delta is recoverable before it is
        // only in lane memory. Superseded copies stay journaled until the
        // checkpoint removes them (coalescing happens in `staged`).
        // ⚠⚠ This row IS the save's durable handoff. `addToArray` throws when it
        // cannot write, and the checkpoint and the replay remove it only once its batch is queued
        // (see [sealBatch]).
        executionContext.database.addToArray(laneJournalRef, delta)
        // ⚠⚠ A spilled lane holds nothing in memory; the journal row is the handoff ([Lane.spilled]).
        if (lane.spilled) return EmitHandoff.Queued
        lane.journaled += delta

        val key = "$targetHash|${propertyId ?: ""}"
        lane.stagedBytes += encodedSize - (lane.stagedSizes.put(key, encodedSize) ?: 0)
        lane.staged[key] = delta

        if (lane.staged.size >= LANE_DELTA_CAP ||
            lane.stagedBytes >= LANE_BYTES_CAP ||
            lane.journaled.size >= LANE_JOURNAL_CAP
        ) {
            checkpointLane(lane)
        }
        return EmitHandoff.Queued
    }

    /** Consumer-thread only: seal `lane`'s staged deltas into one batch block. */
    private fun checkpointLane(lane: Lane) {
        if (lane.spilled) {
            // Spilled: nothing in memory to seal. Once a realm is back the whole journal, this
            // lane's spilled rows included, is sealed by the D6 replay; until then, silence.
            if (syncManager.currentRealmContext() != null) replayLaneJournalOnConsumer()
            return
        }
        if (lane.staged.isEmpty() && lane.journaled.isEmpty()) return
        if (lane.staged.isNotEmpty()) {
            // ⚠⚠ No realm to seal in, and the journal is the only durable copy of these deltas
            // (their saves' records were released on it): the journal is kept for the D6 replay.
            // ⚠⚠ And the lane spills, so its memory stops growing.
            val sealed = sealBatch(lane.staged.values.toList())
            if (sealed is EmitHandoff.NotQueued) {
                spill(lane, sealed.why)
                return
            }
        }
        lane.journaled.forEach { delta ->
            executionContext.getDataId(delta)?.let {
                executionContext.database.removeFromArray(laneJournalRef, it)
            }
        }
        lane.staged.clear()
        lane.stagedSizes.clear()
        lane.stagedBytes = 0
        lane.journaled.clear()
    }

    /**
     * Consumer-thread only ([Lane.spilled]): [lane]'s memory dropped, its journal rows kept on disk, and
     * every later save of it journalled alone. Logged here, once per lane.
     */
    private fun spill(lane: Lane, why: String) {
        laneLog("SyncObserver -> lane ${lane.id}: a checkpoint was not sealed ($why); from now on its deltas go to the durable journal alone, for the replay")
        lane.staged.clear()
        lane.stagedSizes.clear()
        lane.stagedBytes = 0
        lane.journaled.clear()
        lane.spilled = true
    }

    /** Where [spill] logs. A test seam. */
    internal var laneLog: (String) -> Unit = ::println

    /** How many deltas [laneId]'s memory holds (staged + journaled), or null when it is not open. A test seam; consumer state, read once idle. */
    internal fun laneMemoryOf(laneId: String): Int? = lanes[laneId]?.let { it.staged.size + it.journaled.size }

    /** Consumer-thread only: the D6 replay — seal whatever the durable journal still holds. */
    private fun replayLaneJournalOnConsumer() {
        if (!syncManager.isEnabled()) return
        val lingering = executionContext.database.getArrayData(laneJournalRef)
            ?.toList()
            ?.filterIsInstance<SyncDelta>()
            ?: return
        if (lingering.isEmpty()) return

        // Any in-memory lane state is a subset of the journal (stage journals
        // first) — the replayed seal supersedes it, so drop it to avoid a
        // double emit. (A double emit would be harmless anyway: receivers skip
        // equal-HLC deltas via SyncMetadata.)
        // ⚠ Only the STATE goes. Every open lane stays open, fresh and
        // un-spilled, so a task mid-way keeps batching instead of falling back to one block per save
        // for the rest of its life (the explicit-open gate treats a missing lane as unopened).
        val open = lanes.keys.toList()
        lanes.clear()
        open.forEach { lanes[it] = Lane(it) }

        // Coalesce last-wins by the same key the live path uses, then seal in
        // cap-sized batches (a crashed bulk task may exceed one block's caps).
        val coalesced = LinkedHashMap<String, SyncDelta>()
        lingering.forEach { delta ->
            val key = "${delta.targetEntityIdHash?.get() ?: ""}|${delta.propertyId?.get() ?: ""}"
            coalesced[key] = delta
        }
        // ⚠⚠ All-or-nothing on the journal — a chunk sealed before the realm went is
        // sealed again at the next replay (receivers skip equal-HLC deltas), never dropped.
        val sealed = coalesced.values.chunked(LANE_DELTA_CAP)
            .fold<List<SyncDelta>, EmitHandoff>(EmitHandoff.Queued) { acc, chunk -> acc and sealBatch(chunk) }
        if (sealed is EmitHandoff.NotQueued) {
            println("SyncObserver -> the lane journal replay was not sealed (${sealed.why}); it stays for the next replay")
            return
        }
        lingering.forEach { delta ->
            executionContext.getDataId(delta)?.let {
                executionContext.database.removeFromArray(laneJournalRef, it)
            }
        }
    }

    /** Consumer-thread only: encrypt + enqueue one `DeltaBatchPayload` block (personal realm, D2 null hash). */
    private fun sealBatch(deltas: List<SyncDelta>): EmitHandoff {
        // Keys gone: the caller keeps the journal for replay.
        val realm = syncManager.currentRealmContext() ?: return EmitHandoff.NotQueued("no realm to seal the lane batch in")
        val payload = DeltaBatchPayload.create(executionContext.context, deltas)
        // ⭐ sync-envelope-authentication.md §3.2: the envelope is bound into the payload's AEAD.
        val block = EnvelopeAuth.sealSyncBlock(
            crypto = cryptoManager,
            context = executionContext.context,
            blockId = generateId(),
            ownerId = realm.ownerId,
            hlc = syncManager.hlcManager.tick(),
            targetEntityIdHash = null, // §10.2 D2: containers carry no wire routing hash
            plaintext = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), blockStamp),
            identityKey = realm.derivedKeys.sharedIdentityKey,
        )
        val pendingBlocksRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )
        executionContext.database.addToArray(pendingBlocksRef, block)
        wakeSignal?.wake()
        return EmitHandoff.Queued
    }

    /** ⚠ [EmitHandoff.Queued] only when every block this save owes is queued (or it owes none). */
    private fun processSavedData(data: Data, cleared: Set<String> = emptySet()): EmitHandoff {
        var queued: EmitHandoff = EmitHandoff.Queued
        val type = if (data is Entity<*>) data.type.get() else data.id
        val schema = executionContext.schemaProvider.getSchema(type) ?: data.schema
        val entityId = executionContext.getDataId(data)
        if (schema.hasTrait(Syncable.ID)) {
            queued = queued and generateSyncBlock(
                data = data,
                targetEntityId = entityId,
                propertyId = null,
                cleared = cleared,
            )
        }

        // Also check for Syncable properties in entities
        if (data is Entity<*>) {
            data.properties.forEach { (ref, value) ->
                val propertyId = ref.get()
                val propSchema = executionContext.schemaProvider.getSchema(propertyId)
                if (propSchema?.hasTrait(Syncable.ID) == true) {
                    queued = queued and generateSyncBlock(
                        data = value,
                        targetEntityId = entityId,
                        propertyId = propertyId,
                    )
                }
            }
        }
        return queued
    }

    /**
     * ⭐ Adds before removes (`content-plane-cr2-b4-spec.md` §2.3): records which content the holder being removed names, by
     * its wire name, while the row still exists — inside `deleteData`'s transaction, before the row
     * goes. The tombstone and reap drains read it to hold a removal while a queued, unacknowledged
     * block adds the same content (a move's new path), and drop it when the removal is accepted.
     *
     * ⚠ Not for an ingest delete: a peer's tombstone applied here owes this device no removal push, so
     * an entry would never be consumed. A reaped descendant is deleted with the default origin and
     * does owe a reap, so it is recorded.
     * ⚠ Best-effort: an unreadable row, an unnameable holder (no realm) or a type naming no content
     * records nothing, which leaves that removal unheld.
     */
    override fun beforeDataDeleted(dataId: DataId, origin: WriteOrigin) {
        if (origin == WriteOrigin.SYNC_INGEST || origin == WriteOrigin.DERIVED) return
        val declarations = contentDeclarations?.get() ?: return
        if (!declarations.referencesContent(dataId.type.get())) return
        val row = runCatching { executionContext.database.getData(dataId, asEntity = true) }.getOrNull() ?: return
        // Non-regenerable only, as in [prepareSave].
        val named = declarations.edgesOf(row)?.filter { !it.regenerable }?.mapNotNull { it.contentId }?.toSet().orEmpty()
        if (named.isEmpty()) return
        val holder = wireIds.ofOrNull(dataId) ?: return
        RemovedContentsStore(executionContext).record(holder, named)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        // Tombstones are emitted explicitly by operation implementations via [emit] —
        // not on every database delete (cascade-delete inside an operation must not
        // produce one tombstone per child).
    }

    override fun emitPropertyBlock(
        targetEntityId: DataId,
        propertyId: String,
        value: Data
    ) {
        if (!syncManager.isEnabled()) return
        // ⚠ This interface is best-effort by contract (`SyncBlockEmitter`: no realm
        // is a silent no-op, and no save record rides on it), so the answer is logged, not returned.
        val handoff = generateSyncBlock(
            data = value,
            targetEntityId = targetEntityId,
            propertyId = propertyId
        )
        if (handoff is EmitHandoff.NotQueued) println("SyncObserver -> a property block was not queued (${handoff.why})")
    }

    /** The durable membership slot — see [TombstoneEmitter.residentOwnerId]. */
    override val residentOwnerId: String? get() = syncManager.residentOwnerId

    /**
     * The emitter names the entity, because only it has the realm key (`sync-entity-identity.md`
     * EI-1). ⚠⚠ A caller that hashed the id itself would emit a local name, and the tombstone
     * would address an entity no peer can match — silently.
     */
    override fun emit(
        targetEntityId: DataId,
        targetEntityType: String,
        revokedDeviceId: String?
    ): String? {
        // ⚠⚠ `ofOrNull`, not `of`, and the reason is a race rather than a preference. This overload
        // must keep the hash form's contract — emitting without a realm is a silent no-op, not an
        // error — and `of` throws. Guarding with an `isEnabled()`/realm check first is not enough:
        // `SyncManagerImpl.lock()` clears `derivedKeys` and `currentOwnerId` independently, so a
        // concurrent `LockRealm` can empty the realm between the check and the resolve.
        // ⚠ What a throw costs here decides it: `DataIdMigrator.retire` emits *after* writing its
        // journal marker and deleting the old row, so it would wedge the migration in exactly the
        // state the marker exists to recover, and the delete paths would fail ordinary local
        // deletes. A no-op is recoverable — the marker is still there, and resume re-runs it.
        // ⚠ The loudness `of` buys is real but unattainable race-free here: "no realm" and "realm
        // vanished mid-call" are the same observation from inside this method.
        // ⚠⚠ One read, carrying the realm with the name. Resolving here and letting
        // `enqueueTombstone` ask for the `ownerId` separately lets an owner adoption land between
        // them: both reads succeed, and the block is stamped for realm B carrying a name derived
        // under realm A's key — unmatchable in the realm it is addressed to, with the local row
        // already deleted and the retry already cleared.
        val resolved = wireIds.resolve(targetEntityId) ?: return null
        return enqueueTombstone(
            realm = resolved.realm,
            targetEntityIdHash = resolved.wireName,
            targetEntityType = targetEntityType,
            revokedDeviceId = revokedDeviceId,
            cascadeNames = emptyList(),
        )
    }

    override fun emit(
        targetEntityIdHash: String,
        targetEntityType: String,
        revokedDeviceId: String?
    ): String? {
        // ⚠ The hash form holds no `DataId`, so it can only use the session's realm — its caller
        // resolved the name elsewhere and owns that mismatch risk. Every id-form path above passes
        // the realm it named under.
        val realm = syncManager.currentRealmContext() ?: return null
        return enqueueTombstone(realm, targetEntityIdHash, targetEntityType, revokedDeviceId, emptyList())
    }

    private fun enqueueTombstone(
        realm: RealmContext,
        targetEntityIdHash: String,
        targetEntityType: String,
        revokedDeviceId: String?,
        cascadeNames: List<String>,
    ): String? {
        if (!syncManager.isEnabled()) return null
        // ⚠⚠ `isEnabled()` is a **global** answer and this block belongs to a *captured* realm. An
        // owner adoption completing between the resolve and here leaves it true for the realm that
        // just arrived, so an A-stamped block would join the queue that is pushed with a JWT minted
        // for B — the relay answers 403, while `emit` has already returned a block id and the caller
        // has deleted the row and cleared its retry on the strength of it. A's peers keep the row
        // for ever. ⇒ refuse instead: not emitted is a state the callers already handle.
        if (!realmStillCurrent(realm)) return null
        // ⚠⚠ Stamped with the **personal** realm, not the entity's, because the tombstone queue is
        // drained by `PushTombstone` against the personal relay and the relay refuses any other
        // `ownerId` with a `403`. For a personal-realm entity these are the same value — the
        // overwhelming case, and the one the supersession check above protects. For a shared-realm
        // entity they differ, and the block then reaches the wrong stream: a known gap (the
        // tombstone plane has no per-realm push path, unlike blocks).
        // ⚠⚠ **No fallback to the entity's realm.** `realmStillCurrent` takes a different branch for
        // a shared realm — registry membership, which `lock()` does not clear — so it can pass while
        // `currentRealmContext()` is null, and `initializeWithKeys` seats `derivedKeys` several
        // statements before `currentOwnerId`. Falling back would stamp the shared owner there and
        // queue a block the drain can never admit, while telling the caller it was emitted. Refusing
        // is the answer callers already handle.
        val ownerId = syncManager.currentRealmContext()?.ownerId ?: return null

        val block = DeleteBlock.create(
            context = executionContext.context,
            blockId = generateId(),
            ownerId = ownerId,
            hlc = syncManager.hlcManager.tick(),
            targetEntityIdHash = targetEntityIdHash,
            targetEntityType = targetEntityType,
            revokedDeviceId = revokedDeviceId
        )

        // Self-record locally so subsequent stale SyncBlocks for the same entity get
        // short-circuited in [SyncProcessor.resolveConflictAndSave].
        tombstoneRegistry?.record(block)
        // ⭐ `content-plane-cr2-quorumless.md` §10.12 Q4: the descendants this delete removed locally
        // refuse blocks at or below THIS tombstone's HLC — the same T every peer reaps them at —
        // durably, so a restart does not reopen them. Their in-memory "now" markers sit just below T.
        cascadeNames.forEach { tombstoneRegistry?.recordReapPoint(it, block.hlc) }

        // ⚠⚠ Before the block is queued: from the `addToArray` below the drain can push it, and
        // `PushTombstone` reads the cascade back from the store when it does.
        tombstoneCascades.record(block.blockId.get(), cascadeNames)

        // Queue for outbound relay push (mirror of pendingBlocks for SyncBlock).
        val pendingTombstonesRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = DeleteBlock.ID,
                relationId = SyncService.Collections.PENDING_TOMBSTONES
            ),
            isEntity = false,
            elementSchemaId = DeleteBlock.ID
        )
        executionContext.database.addToArray(pendingTombstonesRef, block)
        // Explicit tombstone emit is always user-originated — shortcut the
        // scheduler's poll interval so the delete reaches the relay promptly.
        wakeSignal?.wake()
        // The block id is the caller's receipt that the tombstone is queued.
        return block.blockId.get()
    }

    /** The registry holds the tombstones and reap points a subtree reaper orders by. */
    override val reapOrdering: SubtreeReapOrdering? get() = tombstoneRegistry

    /** `content-plane-cr2-b5-spec.md` §4: the same registry answers which rows a move must refuse until the log reaches them. */
    override val provisionalRows: ProvisionalRows? by lazy {
        tombstoneRegistry?.let { ProvisionalRowProbe(syncManager, it) }
    }

    /**
     * §9.14: the removed descendants are named HERE, while the realm key is in hand, and recorded in
     * the process-shared [tombstoneCascades] before the block is queued or the drain is woken.
     */
    override fun emitWithCascade(
        targetEntityId: DataId,
        targetEntityType: String,
        cascadedEntityIds: List<DataId>,
    ): String? {
        val resolved = wireIds.resolve(targetEntityId)
        if (resolved == null) {
            // ⚠⚠ "No realm" is two states, told apart by `residentOwnerId` exactly as [emitAll]
            // does. A device in **no realm at all** owes nobody a tombstone, and `DeleteFile` calls
            // this *after* the rows and their bytes are gone — throwing there would report every
            // delete on such a device as an internal error. A **member** that is merely locked, or still
            // loading its keys, does owe one, so that stays loud rather than a silent success.
            check(syncManager.residentOwnerId == null) {
                "No realm context for ${targetEntityId.type.get()} — a wire id cannot be named without " +
                    "one, and naming it locally would diverge silently (sync-entity-identity.md EI-1)"
            }
            return null
        }
        return enqueueTombstone(
            realm = resolved.realm,
            targetEntityIdHash = resolved.wireName,
            targetEntityType = targetEntityType,
            revokedDeviceId = null,
            cascadeNames = cascadedEntityIds.mapNotNull { wireIds.ofOrNull(it) },
        )
    }

    /**
     * The batch path (`control-data-reset.md` §11): N blocks, **one** array
     * write, **one** wake. Emitting a realm-data reset's tombstones through
     * [emit] would make the pending queue's own read-modify-write quadratic and
     * wake the scheduler once per row.
     */
    override fun emitAll(targets: List<TombstoneEmitter.Target>): List<String>? {
        if (targets.isEmpty()) return emptyList()
        // ⚠⚠ "Not enabled" is two different states, and only one of them is safe to delete on.
        // `residentOwnerId` is the durable membership marker: non-null means this device belongs to
        // a realm whose peers hold these rows, and it is merely **locked** (or still inside the cold
        // start's async key-load window). The caller reads an empty answer as "local-only, deleting
        // is correct" — so answering empty there destroys the local ids for rows that owe a
        // tombstone, and no retry can ever name them again. ⚠ `LockRealm` landing between two reset
        // chunks is exactly this, `PACE_MS` apart.
        if (!syncManager.isEnabled()) {
            return if (syncManager.residentOwnerId != null) null else emptyList()
        }
        // ⚠⚠ `resolve`, not `ofOrNull` + a separate owner read: each target's name and the realm it
        // is stamped for come from **one** read, so an owner adoption landing mid-batch cannot
        // produce a block addressed to realm B carrying a name derived under realm A's key. It is
        // also what `generateSyncBlock` already does — the entity's own realm, not the session's.
        // ⚠⚠ `resolve` rather than a throwing form, because a concurrent `LockRealm` can clear the
        // realm *during* this and a throw would break `emitAll`'s documented skip-when-sync-is-off
        // contract. Checking `isEnabled()` above does not help; the window is after it.
        //
        // ⚠⚠ ALL-OR-NOTHING, not a per-target skip. `DeleteRealmDataImplementation` deletes the whole
        // chunk whether or not these emit, so a *partial* batch destroys the local ids for rows whose
        // tombstone never went out — no retry can name them again, and peers keep or later resurrect
        // exactly those rows. Emitting none is the documented sync-off behaviour the caller already
        // reasons about; emitting some is a state nothing recovers from.
        // ⚠ `null`, not `emptyList()` — the caller deletes rows on an empty answer (the sync-off
        // case) and must not delete on this one. See the interface KDoc.
        val resolved = targets.map { wireIds.resolve(it.entityId) }
        if (resolved.any { it == null }) return null
        // ⚠ The same supersession check the single path makes, and all-or-nothing for the same
        // reason: a batch half-queued into a realm that has gone is a state nothing recovers from.
        if (resolved.any { !realmStillCurrent(it!!.realm) }) return null

        // ⚠ Hoisted: read once, so a batch cannot carry a mix of drainable and undrainable stamps —
        // `emitAll` would then answer a full non-null list and the caller would delete every row.
        val batchOwnerId = syncManager.currentRealmContext()?.ownerId ?: return null
        val blocks = targets.mapIndexed { index, target ->
            val named = resolved[index]!!
            DeleteBlock.create(
                context = executionContext.context,
                blockId = generateId(),
                // ⚠ The personal realm, for the same reason as the single path above: this queue is
                // drained against the personal relay, which 403s any other owner.
                ownerId = batchOwnerId,
                hlc = syncManager.hlcManager.tick(),
                targetEntityIdHash = named.wireName,
                targetEntityType = target.entityType,
                revokedDeviceId = target.revokedDeviceId
            )
        }

        blocks.forEach { tombstoneRegistry?.record(it) }

        val pendingTombstonesRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = DeleteBlock.ID,
                relationId = SyncService.Collections.PENDING_TOMBSTONES
            ),
            isEntity = false,
            elementSchemaId = DeleteBlock.ID
        )
        executionContext.database.addAllToArray(pendingTombstonesRef, blocks)
        wakeSignal?.wake()
        return blocks.map { it.blockId.get() }
    }

    /** [EmitHandoff.Queued] once the block is queued; ⚠ not when there was no realm to make it in (nothing queued). */
    private fun generateSyncBlock(
        data: Data,
        targetEntityId: DataId?,
        propertyId: String?,
        cleared: Set<String> = emptySet(),
    ): EmitHandoff {
        // Route this block to *its entity's* realm — resolved from the
        // entity's DataId namespace prefix (a registered shared realm, else the
        // personal realm). With no registry / no shared realms this is exactly
        // the personal `currentRealmContext()`, so the single-realm path is
        // byte-identical (same keys, same queue).
        val realm = (realmRegistry?.realmForDataId(targetEntityId)
            ?: syncManager.currentRealmContext()) ?: return EmitHandoff.NotQueued("no realm to make the block in")
        val ownerId = realm.ownerId
        val keys = realm.derivedKeys

        // Wrap the update into a single payload shape so the relay never
        // learns the property ID (zero-trust: docs/sync/threat-model.md
        // §"Property-ID exposure to relay"). `propertyId == null` is an
        // entity update; non-null names the property slot the joiner
        // should write to after decryption.
        val wrapped = DataBlockPayload.create(
            context = executionContext.context,
            propertyId = propertyId,
            value = data
        )

        val serializer = BinarySerializer()
        val serializedData = BlockPayloadEnvelope.seal(serializer.serialize(wrapped), blockStamp)

        // Metadata blocks use the shared identity key, with the envelope bound as associated data
        // (⭐ sync-envelope-authentication.md §3.2) so the relay cannot re-stamp hlc/target/blockId.
        val block = EnvelopeAuth.sealSyncBlock(
            crypto = cryptoManager,
            context = executionContext.context,
            blockId = generateId(),
            ownerId = ownerId,
            hlc = syncManager.hlcManager.tick(),
            targetEntityIdHash = targetEntityId?.let { wireIds.of(it) },
            plaintext = serializedData,
            identityKey = keys.sharedIdentityKey,
        )

        // ⭐ `content-plane-cr2-b3-wire-spec.md` §5: a whole-row block names its row's COMPLETE edge set, computed here
        // from the payload in hand. ⚠ Only a standalone entity block: a property block's payload is one
        // slot, not the row, and a block with no target cannot carry edges (the relay 400s it).
        val edges = if (propertyId == null && targetEntityId != null) edgesOfRow(data) else emptyList()

        // Add to the realm's pending-blocks queue. The personal realm uses the
        // base relation; a shared realm uses a per-realm-suffixed relation so
        // the two realms' outbound queues never collide and each is drained
        // against its own relay.
        val isPersonal = realmRegistry == null || realmRegistry.isPersonal(realm)
        val pendingRelationId = when {
            isPersonal -> SyncService.Collections.PENDING_BLOCKS
            else -> SyncService.Collections.PENDING_BLOCKS + RealmRegistry.NAMESPACE_SEP + ownerId
        }
        val pendingBlocksRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = SyncBlock.ID,
                relationId = pendingRelationId
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )

        // ⚠⚠ Durable BEFORE the block is queued: from the `addToArray` below the drain may push it,
        // and a push that finds no edges recorded sends none.
        // ⭐ B4 spec §2.3: with the content ids the save cleared, which only a whole-row block can say.
        PendingBlockEdgeStore(executionContext).save(
            block.blockId.get(),
            edges,
            cleared = if (propertyId == null && targetEntityId != null) cleared else emptySet(),
        )
        executionContext.database.addToArray(pendingBlocksRef, block)
        // Reached only on user-originated saves (onDataSaved early-returns on
        // SYNC_INGEST) and explicit property emits — both user-perceptible.
        // Wake the scheduler so the block drains within one short-poll window.
        wakeSignal?.wake()
        return EmitHandoff.Queued
    }

    // ---- Two-plane file content (docs/adr/0017-file-content-two-plane-channel.md P1) --------

    /**
     * Per-chunk plaintext size for the content plane. 2 MiB is the
     * sweet spot against whole-file OOMs (~10–13 MB DSLR JPEGs ⇒ 5–7
     * chunks): big enough that per-chunk AEAD + manifest-entry overhead
     * is negligible, small enough that emit/queue/push/relay peak heap
     * stays a few MB even with a deep upload backlog. Independent of
     * file size and backlog by construction. Changing this does NOT
     * break already-emitted manifests — chunkCount/totalSize are
     * recorded per-manifest, so the reader is told the boundaries.
     */
    private val chunkSizeBytes = FileContentChunkSizeBytes

    /**
     * Is [realm] — captured earlier in this call — still the realm this device would push to?
     *
     * ⚠⚠ Not the same question as `isEnabled()`. That one is global and answers "some realm is
     * live"; this one asks whether the realm a block was *named and stamped for* is still the one
     * its queue drains against. An owner adoption makes the two disagree, and the outbound push
     * mints its JWT from the current owner — so a block from the superseded realm is rejected at the
     * relay with a `403` after its caller has already been told it was emitted.
     *
     * ⚠ A shared realm is checked by presence in the registry rather than by being *the* current
     * one: shared-realm entities legitimately resolve to a realm that is not
     * `currentRealmContext()`, and it is still current as long as its runtime is registered.
     */
    private fun realmStillCurrent(realm: RealmContext): Boolean {
        val registry = realmRegistry
        if (registry != null && !registry.isPersonal(realm)) {
            return registry.runtimeFor(realm.ownerId) != null
        }
        return syncManager.currentRealmContext()?.ownerId == realm.ownerId
    }

    /**
     * An entity's relay-visible name (`sync-entity-identity.md` EI-1). ⚠⚠ Every hash that goes ON
     * THE WIRE, or that keys a row later compared against an inbound one, goes through here — this
     * seam exists to make mixing the two keyings impossible. [emitKeyFor] is the one deliberate
     * exception: its journal is local-only and never meets an inbound value.
     */
    private val wireIds = wireIdResolver ?: WireIdResolver(syncManager) { realmRegistry }


    /**
     * Latched by [emitFileContentNow] when the relay refused a chunk with `507` (over quota), read
     * and cleared by [drainDeferredContent].
     *
     * Not a `SyncHealthFigures` field, and not thrown: the observer must not depend on the
     * scheduler (the dependency runs the other way, see [SyncScheduler.drainDeferredContent]), and
     * the emit path's `Boolean` contract is load-bearing for local-first upload. This is the
     * narrowest channel that lets one pass tell itself "stop, the rest is doomed too".
     *
     * Read by the scheduler through a lambda (`SyncScheduler.contentStorageRejected`) to arm its
     * backoff and raise the "storage full" health signal — the same indirection [drainDeferredContent]
     * itself is wired through, and for the same reason.
     */
    var contentStorageRejected: Boolean = false
        private set

    /**
     * Set when the relay answers `507` to a content write — **both** a chunk `PUT` and the
     * manifest `PUT` — and **cleared by whoever reads it**, via [consumeStorageRefusal]. The edge,
     * where [contentStorageRejected] is the level.
     *
     * ⚠ The manifest half matters more than it sounds: chunks a previous attempt landed are
     * skipped whole on retry, so an entry parked with every chunk already up retries
     * for ever with zero chunk `PUT`s and one manifest `PUT` — which, for an owner at the ceiling,
     * is refused every time, and without it nothing would raise either flag.
     */
    private var storageRefusalSeen: Boolean = false

    /**
     * Raised beside [storageRefusalSeen] but reset by the drain before EVERY entry, so it answers
     * "did this one emit meet a `507`" rather than "has one ever". That is what may truncate a pass;
     * the sticky level must not, or one dead file at the head of a backlog ends every pass.
     */
    private var storageRefusalSeenThisEntry: Boolean = false

    /**
     * Did the relay refuse a content write for space **since this was last asked**? Read-and-clear.
     *
     * ⚠⚠ The scheduler must classify a tick on THIS, never on [contentStorageRejected]. That flag is
     * sticky by design — only an accepted write clears it — so reading it asks "was the relay full
     * when we last heard?", and answering yes re-arms the backoff on a tick where nothing was even
     * attempted. Three ways that wedges a device, all measured or traced:
     *
     *  - **nothing queued.** The drain runs, attempts nothing, refuses nothing, and the sticky flag
     *    still reads true. Seen on a phone with 2.45 GB free: "Storage full", every upload refused,
     *    cleared only by restarting the app — because `UploadFile` refuses up front on the same
     *    signal, so the journal it would need to fill stays empty. A closed loop.
     *  - **the content gate is shut.** `drainDeferredContent` returns before touching an entry when
     *    the user's Wi-Fi-only policy is on, so a cellular device with a backlog reports a storage
     *    rejection it never received — "Storage full" for the whole time it is off Wi-Fi, with the
     *    quota possibly untouched.
     *  - **any unrelated failure.** Once latched, the first entry that fails for any reason — a
     *    relay 500, offline, a file that has since been deleted — ends the pass, and the sticky flag
     *    makes that look like a refusal. A 605-entry backlog headed by one dead file would report
     *    "Storage full" for hours while dropping one entry per backoff window.
     *
     * ⚠ Read-and-clear races the inline emit path, which does not run under the scheduler's tick
     * lock: a `507` landing between the read and the reset is dropped. The cost is one missed
     * backoff window — the next refusal re-arms it — and the safe direction, since the alternative
     * is backing off on a refusal that never happened.
     */
    internal fun consumeStorageRefusal(): Boolean {
        val seen = storageRefusalSeen
        storageRefusalSeen = false
        return seen
    }

    override suspend fun emitFileContent(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String
    ): Boolean {
        // ⚠ Resolved once, here, while the row that asks is the row that holds the content: every
        // later reader of the intent — the drain above all — takes the content from the ENTRY, and
        // the row is only ever a hint after this.
        val target = resolveContentTarget(targetEntityId, propertyId, filePath)
        return withEmitKey(target.emitKey) { emitFileContentOwned(target, force = false) }
    }

    override suspend fun journalFileContent(targetEntityId: DataId, propertyId: String, filePath: String) {
        val target = resolveContentTarget(targetEntityId, propertyId, filePath)
        withEmitKey(target.emitKey) { journalDeferredContent(target) }
    }

    // ⚠⚠ The bound pair: the target is built from the content id — and
    // hash — the producer committed, never re-read from the row: a peer's row ingested since may name
    // other content. The hash matters to the drain: an entry WITH one is published when its bytes
    // still hash to it, even once the row names other content; without one it is retired then, which
    // for a contact photo (rows that move and come back) would drop a never-uploaded photo. The file
    // producer keeps the unbound calls.
    override suspend fun journalFileContent(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String,
        contentId: String,
        contentHash: String?,
    ) {
        val target = boundContentTarget(targetEntityId, propertyId, filePath, contentId, contentHash)
        withEmitKey(target.emitKey) { journalDeferredContent(target) }
    }

    override suspend fun emitFileContent(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String,
        contentId: String,
        contentHash: String?,
    ): Boolean {
        val target = boundContentTarget(targetEntityId, propertyId, filePath, contentId, contentHash)
        return withEmitKey(target.emitKey) { emitFileContentOwned(target, force = false) }
    }

    private fun boundContentTarget(holder: DataId, propertyId: String, filePath: String, contentId: String, contentHash: String?) =
        ContentTarget(
            holder = holder,
            propertyId = propertyId,
            filePath = filePath,
            contentId = contentId,
            contentHash = contentHash,
            bound = true,
        )

    /**
     * [emitFileContent] for a REPAIR — the relay lost content this runtime may already have published,
     * so the "already published" skip must not apply. `ContentRepair` and
     * [ensureContentOnRelay] come through here; both have just established the content is not there.
     */
    suspend fun repairFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean {
        val target = resolveContentTarget(targetEntityId, propertyId, filePath)
        return withEmitKey(target.emitKey) { emitFileContentOwned(target, force = true) }
    }

    /**
     * ⭐ `content-plane-cr2-b4-spec.md` §2.2: re-upload [contentId] — FREED at the relay — from this device's plaintext, for a
     * revive. A fresh CEK, new chunks, the manifest at its derived ref, and **every** ref returned
     * (chunks + manifest) **without the describe**: a describe of a freed id is refused, and the revive
     * on the holder's retried push replaces it (§10.12 Q3).
     *
     * ⚠⚠ Repairability is "a Primary declaration with a local file", generalised from
     * [REPAIRABLE_PROPERTY_IDS] through the external-property writers, which already know where each
     * property's bytes live: a `File`'s path, a `Workout`'s `workoutStreamsPath`. The caller has
     * checked the property is the row's Primary content and that the row still names [contentId].
     *
     * ⚠ [ReviveUpload.NotNow] when the content gate is closed, the relay is refusing for space, the
     * realm is locked or the upload failed: the bytes exist, so the block waits. Never journalled —
     * the queued block is the intent, and its revive sidecar the progress.
     */
    suspend fun reuploadForRevive(holder: DataId, propertyId: String, contentId: String): ReviveUpload {
        val path = runCatching {
            executionContext.context.externalPropertyWriter()?.resolveExternalPath(executionContext, holder, propertyId)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val db = fileDatabase ?: return ReviveUpload.NoLocalBytes
        if (path == null || db.fileSize(path) == null) return ReviveUpload.NoLocalBytes
        if (!syncManager.isEnabled() || !contentGate.shouldSync() || contentStorageRejected) return ReviveUpload.NotNow
        val target = resolveContentTarget(holder, propertyId, path)
        // ⚠ The row names other content than the caller read a moment ago: not this content's bytes.
        if (target.contentId != contentId) return ReviveUpload.NotNow
        var refs: List<String>? = null
        val emitted = withEmitKey(target.emitKey) {
            // The relay lost this content, so "already published here" does not hold.
            publishedKey(target)?.let { emitKeyLocks.unmarkPublished(it) }
            emitFileContentNow(target, describe = false, freshKey = true, onRefs = { refs = it })
        }
        val landed = refs
        return if (emitted && landed != null) ReviveUpload.Uploaded(landed) else ReviveUpload.NotNow
    }

    /**
     * The content-declaration backfill's view of this runtime's content emits: the pass resolves and
     * declares OUTSIDE the emit keys, so it checks each content's stamp afterwards — see [EmitKeyLocks].
     */
    val backfillEmitGuard: ContentDeclarationBackfill.EmitGuard = object : ContentDeclarationBackfill.EmitGuard {
        override suspend fun stampOf(contentId: String): Long? = emitKeyLocks.stampOf(contentId)

        override suspend fun currentSequence(): Long = emitKeyLocks.currentSequence()

        override suspend fun currentContentOf(holder: DataId, propertyId: String): String? =
            executionContext.context.externalPropertyWriter()?.resolveContentId(executionContext, holder, propertyId)
    }

    /** The published-marker key, or null for a legacy holder-keyed emit (no contentId) — never skipped. */
    private fun publishedKey(target: ContentTarget): String? =
        target.contentId?.let { "${target.holder.getDataIdHash()}|${target.emitKey}" }

    private suspend fun emitFileContentOwned(target: ContentTarget, force: Boolean): Boolean {
        // ⚠⚠ The same content for the same holder, already published by this runtime ⇒ nothing
        // to do. Checked UNDER the emit key, so a caller that waited while another emitted it lands here
        // and stops — the serialized duplicate. ⚠ Before journalling, and touching no journal entry: the
        // journal is keyed by content across holders, so clearing it could drop ANOTHER holder's intent.
        // ⚠ Never for a legacy holder-keyed emit (no contentId): there a second emit is new bytes.
        val publishedKey = publishedKey(target)
        if (!force && publishedKey != null && emitKeyLocks.wasPublished(publishedKey)) return true
        // ⚠⚠ A forced emit says the relay LOST this content, so "published here" is no longer true. Cleared
        // BEFORE journalling: if the gate parks this intent, a surviving marker would make the drain retire
        // it as done — a repair silently dropped. The publish that lands re-marks it.
        if (force && publishedKey != null) emitKeyLocks.unmarkPublished(publishedKey)

        // ⚠ JOURNAL FIRST, ALWAYS (`drive-upload-progress.md` P4) — not only when the metered gate
        // is closed. The intent is durable before a single byte moves, which is what makes an
        // upload survivable:
        //
        //  - **Crash safety.** A process death mid-upload would otherwise leave the file local-only
        //    with *no record that it was ever meant to sync* — `UploadFile` has already returned,
        //    so nothing retries. The intent outlives the crash and the next drain finishes it.
        //  - **Failure recovery.** A failed chunk `PUT` aborts the emit (see below); the journal,
        //    not the op's caller, owns the retry.
        //  - **Visibility.** The journal becomes the single source of truth for "what is
        //    uploading", so the banner's figures cover Wi-Fi uploads and not just deferred ones.
        //
        // The write is one small local row against an already-open store, so this costs a
        // transaction per upload — cheap next to chunking and encrypting the file that follows.
        journalDeferredContent(target)

        // Metered content gate (`sync-metered-plane-split.md` §3/§4): the ONE place the user's
        // Wi-Fi-only policy is enforced. Closed ⇒ leave the intent journalled and stop; the
        // scheduler's content drain replays it once the gate opens (or the user's one-shot "over
        // cellular" override fires).
        if (!contentGate.shouldSync()) return false

        // ⚠⚠ The relay is currently refusing this owner's content for space, so an inline attempt
        // is a full chunk uploaded to be discarded. Leave it journalled and let the drain's backoff
        // decide when to probe again.
        //
        // Measured: with only the scheduler's drain backed off, the *inline* path still produces
        // ~10 doomed `PUT`s/minute — one per scheduled upload op (`tenshu-scheduled-op`),
        // i.e. exactly the Synced Folders backlog. Gating the drain alone stops the storm but not
        // the drip, and each drip is still a chunk on the wire.
        //
        // ⚠ This does not make the upload fail: the intent is already journalled above, so the file
        // syncs as soon as there is room. `UploadFile` itself refuses *earlier* and more loudly via
        // `StorageCapacityHolder.relayRejected`, which is what puts a dialog in front of the user.
        if (contentStorageRejected) return false

        // ⚠⚠ The content plane refused several writes in a row and is waiting it out (§5.7). Leave
        // the intent journalled: every inline attempt meanwhile would be a full chunk sent to be refused,
        // one per photo of a running backup. A repair ([force]) still goes: it was asked for.
        if (!force && contentEmitBackoff.planeWaiting()) return false

        // Gate open ⇒ upload inline. ⚠ Latency and ordering are deliberate: `UploadFile` returns with the bytes already on the content plane, so
        // the entity-before-bytes invariant and every caller that reads content straight after an
        // upload behave identically. The journal is a safety net under the existing path, not a
        // queue in front of it.
        var declared = false
        val emitted = emitFileContentNow(target) { declared = it }
        // A failed emit deliberately leaves the intent in the journal for the drain to retry —
        // that is the whole point of writing it first.
        if (emitted) {
            clearDeferredContent(target.emitKey)
            // ⚠ Only a DECLARED publish is marked: an undeclared one must stay
            // re-emittable, or the missing edge is never retried in this runtime.
            publishedKey?.let { emitKeyLocks.recordPublish(it, declared) }
            // The plane is answering: the drain's waiting entries need not sit out their backoff.
            contentEmitBackoff.landed()
        }
        return emitted
    }

    /**
     * The manifest `GET` plus one bodiless presence check per chunk, instead of a full re-upload for
     * an unchanged overwrite. ⚠⚠ Only a complete `true` skips: a locked realm, a missing
     * store, an undecryptable manifest, an unreachable relay and a single missing or unknown chunk all
     * hand off, because an unchanged save is the one way a holder re-seeds content the relay lost,
     * and a skip on anything less would quietly retire it. A handed-off intent already journalled (a
     * closed metered gate) is upserted, not duplicated.
     */
    override suspend fun ensureContentOnRelay(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String,
        contentId: String,
    ): Boolean {
        // ⚠⚠ The whole resolve → declare-or-repair runs UNDER the content's emit key. Unheld, it could
        // read manifest A while a concurrent repair republished under refs B, then declare A's stale refs
        // over B — content live on the relay but undescribed. The key is reentrant for its owner, so the
        // repair branch runs under the same hold.
        return ensureTargetOnRelay(resolveContentTarget(targetEntityId, propertyId, filePath), contentId)
    }

    /**
     * [ensureContentOnRelay] for a caller whose [contentId] was read EARLIER than this call: the
     * declaration backfill, which hands over the id its walk saw.
     *
     * ⚠⚠ The check and the upload target are ONE read, taken UNDER the content's emit key.
     * [ensureContentOnRelay] probes the id it is given and, when the relay lacks it, force-emits
     * whatever the row names now. For a holder an overwrite moved on that is the new content under a
     * fresh key, landed or not: a duplicate chunk set. Here the target read under the key
     * is compared, and that same target is what is ensured. The first read only finds the key.
     *
     * ⚠ An overwrite to OTHER content holds that content's key, not this one, so it can still replace
     * the bytes while this emit streams them. Hence `verifyWhileStreaming`: bytes that are not this
     * content's hash refuse the manifest, and the intent stays journalled for the drain's own check.
     */
    suspend fun ensureNamedContentOnRelay(
        targetEntityId: DataId,
        propertyId: String,
        filePath: String,
        contentId: String,
    ): NamedContentEnsure {
        val first = resolveContentTarget(targetEntityId, propertyId, filePath)
        if (first.contentId == null) return NamedContentEnsure.UNREADABLE
        if (first.contentId != contentId) return NamedContentEnsure.MOVED_ON
        return withEmitKey(first.emitKey) {
            val target = resolveContentTarget(targetEntityId, propertyId, filePath)
            when (target.contentId) {
                null -> NamedContentEnsure.UNREADABLE
                contentId -> {
                    ensureTargetHeld(target.copy(verifyWhileStreaming = true), contentId)
                    NamedContentEnsure.ACTED
                }
                else -> NamedContentEnsure.MOVED_ON
            }
        }
    }

    private suspend fun ensureTargetOnRelay(target: ContentTarget, contentId: String): Boolean =
        withEmitKey(target.emitKey) { ensureTargetHeld(target, contentId) }

    /** The body of an ensure. ⚠ The caller holds [target]'s emit key. */
    private suspend fun ensureTargetHeld(target: ContentTarget, contentId: String): Boolean {
        val targetEntityId = target.holder
        val propertyId = target.propertyId
        val settled = resolveAndDeclareSettled(targetEntityId, propertyId, contentId)
        // ⚠ Another emit of this content kept running: it is publishing it, so neither declare from a
        // manifest it may be replacing nor force a duplicate. Not ensured; the caller asks again.
        if (settled.resolvability == null) return false
        return if (settled.resolvability == ContentResolvability.RESOLVABLE) {
            // ⚠⚠ Published already — but maybe by ANOTHER holder of the same content (a copy, a
            // divergent move, a shared thumbnail rung). Skipping the upload must not skip THIS
            // holder's edge, or deleting the original leaves this one's content looking unreferenced.
            // Declared against the set the relay's manifest really names, which also realigns a
            // description a duplicate emit left pointing at the losing set.
            val declared = settled.declared
            // ⚠ Recorded like any publish: content left by another runtime or
            // device, now declared for this holder, is "published" here too — or a later ordinary
            // emit of the same pair uploads a duplicate chunk set.
            publishedKey(target)?.let { emitKeyLocks.recordPublish(it, declared) }
            true
        } else {
            // Forced: the relay just said it does not have this content, so "this runtime published
            // it already" is exactly the case that must re-emit.
            emitFileContentOwned(target, force = true)
            false
        }
    }

    private class Settled(val resolvability: ContentResolvability?, val declared: Boolean)

    /**
     * [resolveContent], then [declareResolvedHolder] from the refs it returned — re-done while ANOTHER emit
     * of the same content overlapped.
     *
     * ⚠⚠ The caller's emit key covers one `(content, property)`, but the manifest is content-addressed: an
     * emit through another property can publish refs B between this resolve (refs A) and this declare, and
     * the declare then writes A over B — content live on the relay but undescribed. So the per-content stamp
     * (`EmitKeyLocks`) is read before and after, not counting the caller's own hold, and the pair is re-done
     * until it holds still. `resolvability == null` means another emit kept running throughout: nothing was
     * declared, and nothing was learned.
     */
    private suspend fun resolveAndDeclareSettled(holder: DataId, propertyId: String, contentId: String): Settled {
        val ownHolds = if (
            currentCoroutineContext()[OwnedEmitKey]?.emitKey?.let(PendingContentEmit::contentIdOfKey) == contentId
        ) 1 else 0
        repeat(MAX_SETTLE_ATTEMPTS) {
            val before = emitKeyLocks.stampOf(contentId, ownHolds)
            if (before == null) {
                delay(SETTLE_BUSY_WAIT_MS)
                return@repeat
            }
            val (resolvability, chunkRefs) = resolveContent(holder, contentId)
            if (resolvability != ContentResolvability.RESOLVABLE) return Settled(resolvability, declared = false)
            if (!declareResolvedHolder(holder, propertyId, contentId, chunkRefs)) {
                return Settled(resolvability, declared = false)
            }
            if (emitKeyLocks.stampOf(contentId, ownHolds) == before) return Settled(resolvability, declared = true)
        }
        return Settled(null, declared = false)
    }

    /**
     * Describe content already on the relay for [holder] — its refs as the relay's own manifest names
     * them, with [holder]'s handle — realigning a description a duplicate emit left pointing at a losing
     * chunk set. Best-effort, like the emit's own describe: a failure is healed by the backfill,
     * never a loss of bytes.
     *
     * ⚠ No edge: [holder]'s reference to the content reached the relay with its own push.
     * ⚠⚠ Only while the holder still NAMES [contentId]: describing content for a row that has moved on
     * would attach its handle to content it no longer holds.
     */
    private suspend fun declareResolvedHolder(
        holder: DataId,
        propertyId: String,
        contentId: String,
        chunkRefs: List<String>,
    ): Boolean {
        if (chunkRefs.isEmpty()) return false
        val port = executionContext.context.externalPropertyWriter() ?: return false
        val named = runCatching { port.resolveContentId(executionContext, holder, propertyId) }.getOrNull()
        if (named != contentId) return false
        val realm = (realmRegistry?.realmForDataId(holder) ?: syncManager.currentRealmContext()) ?: return false
        val store = if (realmRegistry == null || realmRegistry.isPersonal(realm)) {
            contentBlobStore
        } else {
            realmRegistry.runtimeFor(realm.ownerId)?.contentBlobStore
        } ?: return false
        val refs = chunkRefs + manifestContentRef(contentId)
        // ⚠ Over the relay's per-description cap there is nothing honest to send: a partial set would
        // describe the content as made of fewer chunks than it is.
        if (refs.size > ContentDeclarationBackfill.MAX_REFS_PER_DESCRIPTION) return false
        // ⚠⚠ `describeContent` suspends, so a bare `runCatching` would fold the caller's cancellation
        // into "not described" — a RESOLVABLE outcome, which answers `true` to a caller that has
        // already gone. A failure still means "not described"; a cancellation never did.
        return runCatchingCancellable {
            store.describeContent(listOf(ContentDescription(contentId, refs)))
        }.getOrDefault(false)
    }

    /**
     * ⚠⚠ Whether a producer should make content at all right now — **not** whether an emit would be
     * journalled. Both halves of "no" are sticky enough to be worth asking before a render: the
     * metered gate is the user's `WifiOnly` policy, and the rejection latch is the relay refusing on
     * quota (cleared by the next accepted write).
     */
    override suspend fun canPublishContentNow(): Boolean =
        contentGate.shouldSync() && !contentStorageRejected

    /**
     * Whether a peer resolving [contentId] would find everything it needs: the manifest, and every
     * chunk that manifest names. ⚠⚠ The manifest alone is not enough. Reclamation deletes a
     * content's chunks before its manifest, so an interrupted or partial delete leaves a manifest that
     * resolves and a file that `FileContentManifestApplier.apply` cannot assemble.
     *
     * ⚠⚠ **Tri-state, and everything that cannot answer says [ContentResolvability.UNKNOWN]** — a
     * locked realm, no store, an unreachable relay, an undecryptable manifest. Only the relay
     * answering "not here" is [ContentResolvability.ABSENT]. `ensureContentOnRelay` treats both the
     * same (anything but RESOLVABLE hands the bytes off, which is always safe), but a caller that
     * *deletes* on absence must not act on a network blip.
     */
    override suspend fun contentResolvability(targetEntityId: DataId, contentId: String): ContentResolvability =
        resolveContent(targetEntityId, contentId).first

    /**
     * [contentResolvability], also returning the chunk refs the relay's manifest names when the content
     * is RESOLVABLE (empty otherwise) — so a caller that finds it already published can declare its own
     * holder against the set the manifest really names.
     */
    private suspend fun resolveContent(
        targetEntityId: DataId,
        contentId: String,
    ): Pair<ContentResolvability, List<String>> {
        if (!syncManager.isEnabled()) return ContentResolvability.UNKNOWN to emptyList()
        // ⚠ The same routing as [emitFileContentNow]'s (a shared realm's own store, else the personal
        // one); a check against a different store than the upload used answers about the wrong realm.
        val realm = (realmRegistry?.realmForDataId(targetEntityId) ?: syncManager.currentRealmContext())
            ?: return ContentResolvability.UNKNOWN to emptyList()
        val store = if (realmRegistry == null || realmRegistry.isPersonal(realm)) {
            contentBlobStore
        } else {
            realmRegistry.runtimeFor(realm.ownerId)?.contentBlobStore
        } ?: return ContentResolvability.UNKNOWN to emptyList()

        val manifestRef = manifestContentRef(contentId)
        val cipher = (store.getChunk(manifestRef) as? TResult.Success)?.value?.get()
            // ⚠ `getChunk` reports a missing blob and a failed request as the same `Failure`, so the
            // one question it cannot answer is asked separately — and only on this path, so a healthy
            // read still costs one round trip.
            ?: return when (store.hasChunk(manifestRef)) {
                false -> ContentResolvability.ABSENT
                else -> ContentResolvability.UNKNOWN
            } to emptyList()
        val parser = BinaryParser(executionContext.context.schemaProvider(), executionContext.context.dataFactory())
        val identityKeys = listOf(realm.derivedKeys.sharedIdentityKey) +
            realm.historicalDerivedKeys.map { it.sharedIdentityKey }
        val manifest = identityKeys.firstNotNullOfOrNull { key ->
            runCatching {
                val opened = BlockPayloadEnvelope.open(cryptoManager.decrypt(cipher, key)) ?: return@runCatching null
                parser.parse<Data>(opened.payloadBytes) as? FileContentManifestPayload
            }.getOrNull()
        } ?: return ContentResolvability.UNKNOWN to emptyList()
        // A manifest that decrypts but names other content is not this content's: nothing here can
        // serve it, and nothing was learned about whether the relay holds it.
        if (manifest.fileId.get() != contentId) return ContentResolvability.UNKNOWN to emptyList()

        val chunks = manifest.chunks.toList()
        if (chunks.isEmpty()) return ContentResolvability.UNKNOWN to emptyList()
        val present = mapChunksBounded(
            concurrency = chunkTransferConcurrency,
            produceNext = { index -> chunks.getOrNull(index) },
        ) { _, chunkRef -> store.hasChunk(chunkRef.ref.get()) }
        return when {
            present.all { it == true } -> ContentResolvability.RESOLVABLE to chunks.map { it.ref.get() }
            // ⚠ One definite "not here" is enough: the content cannot be assembled either way, and
            // the relay answered. An unknown among them keeps the whole answer unknown.
            present.any { it == false } -> ContentResolvability.ABSENT to emptyList()
            else -> ContentResolvability.UNKNOWN to emptyList()
        }
    }

    /**
     * The bytes [targetEntityId]'s own journalled intent adds to [deferredContentFigures] — what an
     * overwrite of that file supersedes, since the journal keeps one entry per `(entity, property)`.
     */
    override suspend fun queuedContentBytesFor(targetEntityId: DataId, propertyId: String): Long {
        val entry = queuedEntryFor(targetEntityId, propertyId) ?: return 0L
        return fileDatabase?.fileSize(entry.filePath.get()) ?: 0L
    }

    /**
     * The intent for the content [targetEntityId] names **now** (its entity key when it names none).
     * ⚠ Asked through the row's current content id, so a row a peer moved here finds the intent its
     * uploader journalled under the old path — the pin then holds for the moved row, which the
     * entity key could never do.
     */
    private fun queuedEntryFor(targetEntityId: DataId, propertyId: String): PendingContentEmit? =
        readDeferredContent(currentEmitKey(targetEntityId, propertyId))

    /**
     * The entry itself, with no reference to its size — see [FileContentSyncEmitter.hasQueuedContentFor]
     * for why the byte figure above cannot answer this. Note the two ways that one returns `0` for a
     * live intent: an empty file, and a `filePath` `fileDatabase` can no longer size.
     */
    override suspend fun hasQueuedContentFor(targetEntityId: DataId, propertyId: String): Boolean =
        queuedEntryFor(targetEntityId, propertyId) != null

    /**
     * Retire the intents an overwrite of [targetEntityId] superseded — named by the overwrite, which
     * is the only thing that knows them.
     *
     * ⚠⚠ Why the drain cannot be left to do it: it retires a superseded intent only when it RUNS,
     * and behind a closed gate it does not. Every overwrite made on cellular would then leave its
     * predecessor journalled beside the new intent — the waiting count and bytes both counting it, and
     * the upload quota pre-flight, which counts the queue, refusing uploads over bytes that no longer
     * exist.
     *
     * ⚠ Retired only while the entry still names THIS row as its holder, and only once the row names
     * other content for that property. A divergent rename can leave a twin on the same content id
     * whose entry holds the bytes at the twin's path; overwriting this row replaced nothing of that.
     */
    override suspend fun discardSupersededContent(targetEntityId: DataId, superseded: List<Pair<String, String>>) {
        val holderHash = targetEntityId.getDataIdHash()
        val port = executionContext.context.externalPropertyWriter()
        for ((contentId, propertyId) in superseded) {
            val key = PendingContentEmit.contentIdKeyOf(contentId, propertyId)
            withEmitKey(key) {
                val entry = readDeferredContent(key) ?: return@withEmitKey
                if (entry.targetEntityId.getDataIdHash() != holderHash) return@withEmitKey
                if (port?.resolveContentId(executionContext, targetEntityId, propertyId) == contentId) return@withEmitKey
                clearDeferredContent(key)
            }
        }
    }

    /**
     * ⚠⚠ A path is not an orphan while an intent reads its bytes from it. After a peer's move the
     * uploader's bytes stay at the OLD path, which no row names any more — exactly what
     * `OrphanedBytesSweep` collects — and they are the only copy of content the relay has never seen.
     * A scan, because no key addresses an intent by where its bytes are; the sweep asks about a
     * handful of candidates, so the journal is read a handful of times.
     */
    override suspend fun hasQueuedContentAt(filePath: String): Boolean =
        executionContext.database.getArrayData(pendingContentEmitsRef)
            ?.filterIsInstance<PendingContentEmit>()
            ?.toList()
            ?.any { it.filePath.get() == filePath }
            ?: false

    /**
     * [emitFileContent] minus the metered gate — the body that actually chunks, encrypts and
     * `PUT`s. Separate so the content drain can replay a journalled intent without re-consulting
     * the gate it was already cleared past (a forced "sync over cellular" override would otherwise
     * be re-refused by the very gate it exists to bypass).
     */
    private suspend fun emitFileContentNow(
        target: ContentTarget,
        /** False for a revive's re-upload, whose refs ride the push instead ([onRefs]). */
        describe: Boolean = true,
        /** Never resume a parked attempt; a revive mints a fresh CEK and sends every chunk. */
        freshKey: Boolean = false,
        /** Told every ref of a content-addressed publish, chunks + manifest, once both have landed. */
        onRefs: (List<String>) -> Unit = {},
        /** Told whether the describe landed (only a DESCRIBED publish may be marked published). */
        onDeclared: (Boolean) -> Unit = {},
    ): Boolean = emitFileContentNowUncounted(target, describe, freshKey, onRefs, onDeclared).also { landed ->
        if (landed) countEmitLanded()
    }

    private suspend fun emitFileContentNowUncounted(
        target: ContentTarget,
        describe: Boolean,
        freshKey: Boolean,
        onRefs: (List<String>) -> Unit,
        onDeclared: (Boolean) -> Unit,
    ): Boolean {
        val targetEntityId = target.holder
        val propertyId = target.propertyId
        val filePath = target.filePath
        // Locked/initialising: hold for re-emit on enable (mirror of onDataSaved). The file bytes
        // are already persisted locally, so re-running this once keys are available reads them back
        // and emits the content manifest + chunks — otherwise a file uploaded pre-enable syncs its
        // metadata but never its bytes.
        if (!syncManager.isEnabled()) {
            // ⚠ Only a producer's emit parks; a drain's entry stays journalled (`parkIfLocked`). A bound
            // target is parked whole — nothing about it is stripped or re-derived on replay.
            if (target.parkIfLocked) {
                pendingContent.trySend(PendingContent(targetEntityId, propertyId, filePath, bound = target.takeIf { it.bound }))
            }
            return false
        }
        // Route to *this entity's* realm — a namespaced `"$realmId::"` target goes to
        // the shared realm's keys + content store + queue, else the personal realm.
        // Mirrors `generateSyncBlock`.
        val realm = (realmRegistry?.realmForDataId(targetEntityId)
            ?: syncManager.currentRealmContext()) ?: return false
        val ownerId = realm.ownerId
        val keys = realm.derivedKeys
        // Per-realm content plane: the personal store for the personal realm, else the
        // shared realm's own `ownerId`-scoped store (built by the registry's factory).
        val store = if (realmRegistry == null || realmRegistry.isPersonal(realm)) {
            contentBlobStore
        } else {
            realmRegistry.runtimeFor(realm.ownerId)?.contentBlobStore
        } ?: return false
        val db = fileDatabase ?: return false

        val totalSize = db.fileSize(filePath) ?: return false
        // chunkCount ≥ 1 even for a 0-byte file (a single empty chunk),
        // so an empty file still round-trips through manifest+content.
        val chunkCount = if (totalSize == 0L) {
            1
        } else {
            ((totalSize + chunkSizeBytes - 1) / chunkSizeBytes).toInt()
        }

        // Content addressing (file-content-addressing.md §3.2): bind the chunk
        // AAD + address the manifest by the file's stable random `contentId` instead of
        // the mutable path-hash, so a moved file's chunks stay valid without re-upload.
        // `null` = a non-content-addressed entity (a shared-realm SharedFile, whose id is
        // already a stable UUID, or a legacy/test wiring): fall back to the legacy
        // path-hash binding + mailbox manifest block.
        //
        // ⚠⚠ Taken from the TARGET, never re-read from the row here. The target was resolved
        // while the row still held the content — or read off the journal, whose row may since
        // have been retired by a peer's move. Re-reading answers null for exactly that row, and a
        // null here is the legacy branch: a manifest under a dead entity's name, no edge, no
        // handle, and `true` returned to a caller that then clears the intent as done.
        val contentId = target.contentId
        val fileId = contentId ?: wireIds.of(targetEntityId)
        // One-hash-pass (§8.4): for a content-addressed file the whole-file hash was
        // already computed at upload and stamped as File.contentHash — carried on the target
        // rather than re-hashed from the file we are already streaming to chunk. The legacy
        // path has no stored hash, so it folds one incrementally.
        val precomputedHash = if (contentId != null) target.contentHash else null
        // Content-plane chunks use the shared *volume* key (bulk user
        // data), distinct from the shared *identity* key the metadata
        // plane / manifest block uses.
        val volumeKey = keys.sharedVolumeKey
        // Content-addressing §3.4: mint a fresh random per-content key (CEK) and encrypt the
        // chunks under IT, not the realm volume key directly — the ciphertext is then
        // realm-independent, so a Mode-B share re-wraps this key to the target realm instead of
        // re-encrypting the bytes. The volume key's role is *wrapping* the CEK (below).
        // `generateSharedRealmMasterKey()` is just a fresh 32-byte CSPRNG key — reused here as the
        // CEK generator.
        //
        // ⭐ **Fresh per emit, except when resuming one.** An aborted attempt parks its wrapped CEK
        // and the chunks that landed, so the retry sends only the rest under the same key — the
        // manifest carries one CEK for every chunk, so a fresh key would strand what already landed.
        // Measured: restarting cost 3.00× on the relay for one 20 MiB upload
        // (`content-plane-refcount.md` §9.9b).
        //
        // ⚠⚠ The guard is [PendingContentEmit.contentHash], not the presence of the entry: the
        // drain re-reads *today's* bytes, so a file edited while parked must re-chunk from scratch.
        // Resuming across an edit would splice two files' chunks under one manifest.
        val emitKey = target.emitKey
        val parked = readDeferredContent(emitKey)
        val resumable = parked
            ?.takeIf { !freshKey }
            ?.takeIf { precomputedHash != null && it.contentHash.get() == precomputedHash }
            ?.takeIf { it.wrappedCek.get().isNotBlank() }
        val landedHashes: List<String> = resumable
            ?.landedChunkHashes?.get()?.split(",").orEmpty()
        val cek = resumable
            ?.let {
                runCatching {
                    cryptoManager.decryptWithAad(
                        it.wrappedCek.get().hexToByteArraySafe(),
                        volumeKey,
                        cekWrapAad(fileId),
                    )
                }.getOrNull()
            }
            ?: cryptoManager.generateSharedRealmMasterKey()
        // What this attempt has on the relay, by chunk index — seeded with what a previous one left.
        val landedByIndex = mutableMapOf<Int, String>()
        landedHashes.forEachIndexed { i, h -> if (h.isNotBlank()) landedByIndex[i] = h }
        val landedLock = kotlinx.coroutines.sync.Mutex()
        val wholeFileHasher = if (precomputedHash == null) IncrementalSha256() else null
        // ⚠ See [ContentTarget.verifyWhileStreaming]. Folded at the producer's read boundary, which
        // is the emit's chunk stride — the same boundary `File.ContentHash` was folded at.
        val streamVerifier = if (target.verifyWhileStreaming && precomputedHash != null) IncrementalSha256() else null

        // Chunk transfers OVERLAP (bounded by [chunkTransferConcurrency]) while chunk
        // *production* stays strictly sequential. Each PUT is a full round trip to a
        // single-region relay, so a serial loop makes a large upload round-trip-bound rather
        // than bandwidth-bound — invisible at ~20 ms from Europe, most of the wall clock from
        // Asia. Overlapping N of them amortises that RTT term without changing the wire format,
        // the relay, or anything a reader sees.
        //
        // Reading stays in the producer, and not merely as an implementation detail: the
        // whole-file hash-chain is order- AND boundary-sensitive, and `offset` advances by the
        // bytes actually read, so both would corrupt under a parallel producer. `mapChunksBounded`
        // takes its permit before calling us, so at most N chunk buffers exist at once — the
        // resident-set guarantee relaxes from O(CHUNK) to O(N × CHUNK), still independent of
        // file size and of how deep the upload backlog is (the property that keeps Control off OOM).
        var readOffset = 0L
        var lastChunkWasShort = false
        val chunkRefs = try {
            mapChunksBounded(
                concurrency = chunkTransferConcurrency,
                produceNext = { index ->
                    if (index >= chunkCount || lastChunkWasShort) {
                        null
                    } else {
                        // One CHUNK-sized window read at a time. `readBytesRange` on a real FS
                        // impl is a positional read (a fresh handle per call, so concurrent
                        // readers would be safe too); on InMemory it's a fresh copyOfRange.
                        // Either way the emit path never materialises the whole file.
                        val plainChunk = db.readBytesRange(filePath, readOffset, chunkSizeBytes)
                            ?: ByteArray(0)
                        wholeFileHasher?.update(plainChunk)
                        streamVerifier?.update(plainChunk)
                        readOffset += plainChunk.size
                        // An empty read ends the file: the chunk itself still goes out (a 0-byte
                        // file IS exactly one empty chunk) but nothing is produced after it —
                        // a `break` after the PUT.
                        if (plainChunk.isEmpty()) lastChunkWasShort = true
                        plainChunk
                    }
                },
            ) { index, plainChunk ->
                // ⭐ A chunk a previous attempt already put on the relay is skipped whole — not
                // re-encrypted, not re-sent.
                //
                // ⚠⚠ Skipped by **index**, and it has to be: the AEAD draws a fresh random nonce
                // per encryption, so re-encrypting these same bytes under this same CEK yields a
                // different ciphertext and a different `sha256(ciphertext)`. There is no hash to
                // compare against, which is precisely why the soundness rests on
                // [PendingContentEmit.contentHash] having matched before the loop began: same file
                // bytes and the same chunk size mean index *is* the identity of the chunk.
                val landed = landedLock.withLock { landedByIndex[index] }
                if (landed != null) {
                    return@mapChunksBounded FileChunkRef.create(
                        context = executionContext.context,
                        ref = "blob-$landed",
                        cipherHash = landed,
                    )
                }

                // AAD binds (fileId, chunkIndex, chunkCount) so a chunk
                // ciphertext can't be silently reordered, truncated,
                // replayed across files, or spliced (threat-model P1
                // amendment). `index` is the production index, so the binding is
                // unaffected by which transfer completes first.
                val aad = fileChunkAad(fileId, index, chunkCount)
                val cipher = cryptoManager.encryptWithAad(plainChunk, cek, aad)

                // Ref is a hash of the CIPHERTEXT, never plaintext — a
                // plaintext-derived ref is a confirmation-of-file oracle.
                val cipherHashHex = sha256(cipher).toHexLower()
                val ref = "blob-$cipherHashHex"

                val put = store.putChunk(ref, cipher)
                if (put is org.tenshu.core.TResult.Failure) {
                    // A failed chunk PUT aborts the whole emit — emitting a
                    // manifest that references an absent blob would surface
                    // a half file on the reader. Throwing (rather than returning) is
                    // what cancels the sibling transfers instead of paying for PUTs
                    // whose manifest is already doomed. The upload op's caller retries
                    // the whole operation; content blobs are content-addressed +
                    // immutable so a retry is idempotent.
                    //
                    // ⚠⚠ Carry the transport's reason, don't just name the chunk: a `507`/`402`
                    // rejection is classified from this exception further up (the drain arms a
                    // backoff on it), and dropping the reason here would let an over-quota
                    // device retry forever. See [ChunkPutFailed.reason].
                    throw ChunkPutFailed(
                        message = "SyncObserver -> file-content chunk PUT failed " +
                                "(file=$fileId, chunk=$index/$chunkCount) — aborting manifest emit",
                        reason = put.error.messageOrNull(),
                    )
                }

                landedLock.withLock { landedByIndex[index] = cipherHashHex }
                countChunkLanded()

                FileChunkRef.create(
                    context = executionContext.context,
                    ref = ref,
                    cipherHash = cipherHashHex
                )
            }
        } catch (e: ChunkPutFailed) {
            // §5.7: a refused write, on whichever path. ⚠ Not a `507`: that has its own pass-wide
            // stop and scheduler backoff.
            // ⚠ Classified by reason: a 507, a 402, a 4xx are not the plane.
            contentEmitBackoff.failed(target.emitKey, e.reason)
            // ⚠⚠ Latch a `507` before collapsing to `false`. The Boolean this function returns
            // cannot say *why* an emit failed, and "the realm is full" is the one reason where
            // retrying the rest of the queue is guaranteed waste — every sibling entry meets the
            // same quota check. [drainDeferredContent] reads this to end its pass and let the
            // scheduler arm a backoff; without it an over-quota device re-attempts its whole
            // journal every tick forever (see [STORAGE_REJECTION_MARKER]).
            //
            // ⚠ Latched rather than thrown, so `emitFileContent`'s contract is unchanged: an
            // upload is local-first (ADR 0019) — the bytes are already on disk and the intent is
            // journalled — so a full relay must not turn `UploadFile` into a failed operation.
            if (e.isStorageRejection()) {
                contentStorageRejected = true
                // ⭐ The FRESH signal, distinct from the sticky one beside it. The sticky flag says
                // "the relay was full when we last heard"; this says "the relay refused us just
                // now". Only the second is a sound basis for arming a backoff — see
                // [consumeStorageRefusal].
                storageRefusalSeen = true
                // ⚠ And the per-entry one, which is what truncates the rest of this pass.
                storageRefusalSeenThisEntry = true
            }
            // ⭐ Park what DID land, so the next attempt sends only the rest. Written here rather
            // than per chunk on purpose: `mapChunksBounded` runs N transfers at once, so a write
            // per landing is N concurrent read-modify-writes of one row for no benefit — the only
            // moment the partial set matters is the moment the emit gives up.
            //
            // ⚠ A crash between the last landing and here still restarts from scratch. The measured
            // loss is flaky links, not crashes.
            //
            // ⚠⚠ Only when the hash is known: without it the next attempt cannot tell whether the
            // bytes still match what landed, and resuming on a changed file splices two files'
            // chunks under one manifest.
            if (precomputedHash != null && landedByIndex.isNotEmpty()) {
                val wrapped = cryptoManager.encryptWithAad(cek, volumeKey, cekWrapAad(fileId))
                recordEmitProgress(
                    target = target,
                    targetEntityId = targetEntityId,
                    propertyId = propertyId,
                    filePath = filePath,
                    contentHash = precomputedHash,
                    wrappedCekHex = wrapped.toHexLower(),
                    landedChunkHashes = (0 until chunkCount)
                        .joinToString(",") { landedByIndex[it].orEmpty() },
                )
            }
            println(e.message)
            return false
        }

        // Every chunk landed, so whatever the relay last refused, it is accepting writes now.
        // ⚠ Cleared here rather than at drain-pass start: the flag has to survive *between* passes
        // for the inline path below to consult it, so only evidence of an accepted write may clear
        // it — otherwise a device that is still full re-opens the storm every tick.
        contentStorageRejected = false
        // ⚠⚠ The EDGE is deliberately NOT cleared here, for two reasons.
        //
        // First, an accepted write does not disprove a refusal. The relay accepts an
        // already-present ref even while its owner is over quota, so this line is reachable with
        // the owner still full — clearing on it would drop a refusal that is still true.
        //
        // Second, inline emits run OUTSIDE the scheduler's tick lock, so clearing here can erase a
        // fresh edge another emit raised moments ago. The scheduler would then consume `false`,
        // clear the backoff instead of arming it, and the refused entry resumes retrying at tick
        // cadence — the storm the backoff exists to bound.
        //
        // ⇒ the edge stays monotonic until `consumeStorageRefusal` reads it. That is the whole
        // point of an edge: it is the reader's to clear, once, and nobody else's. The level below
        // is what an accepted write legitimately clears, because it answers a different question —
        // "may the inline path try" — and a write that landed does answer that.

        // ⚠⚠ Before the manifest, which is the one write that makes the chunks mean anything. The
        // chunks just sent become unreachable ciphertext — a leak — and the entry stays for the next
        // pass, whose pre-check then sees the new bytes and retires it. Recording resume progress
        // here would park chunks of the WRONG bytes against this content's hash.
        if (streamVerifier != null && streamVerifier.digest().toHexLower() != precomputedHash) {
            println(
                "SyncObserver -> bytes at $filePath changed while being published as $contentId; " +
                    "no manifest written"
            )
            return false
        }

        val wholeFileHash = precomputedHash ?: wholeFileHasher!!.digest().toHexLower()
        // Wrap the CEK under the realm volume key (AEAD, domain-separated AAD bound to fileId) and
        // carry it on the manifest — the reader unwraps it with its volume key(s) to decrypt.
        val wrappedCek = cryptoManager.encryptWithAad(cek, volumeKey, cekWrapAad(fileId))
        val manifest = FileContentManifestPayload.create(
            context = executionContext.context,
            fileId = fileId,
            propertyId = propertyId,
            chunkCount = chunkCount,
            totalSize = totalSize,
            wholeFileHash = wholeFileHash,
            chunks = chunkRefs,
            wrappedCek = wrappedCek,
        )

        // Encrypt the manifest with the shared identity key (same as DataBlockPayload).
        val serializer = BinarySerializer()
        val manifestPlaintext = BlockPayloadEnvelope.seal(serializer.serialize(manifest), blockStamp)

        if (contentId != null) {
            // ⚠ A content-plane blob, not a SyncBlock: no envelope to bind, so it stays a plain AEAD
            // (`sync-envelope-authentication.md` §3.2 — the reader opens it by contentId).
            val encrypted = cryptoManager.encrypt(manifestPlaintext, keys.sharedIdentityKey)
            // Content addressing (§8.3): the manifest is CONTENT, not metadata —
            // store it as an opaque blob on the content plane at `manifestContentRef(contentId)`
            // (a `blob-<sha256(...)>` ref, relay-shape-valid — see that helper),
            // a sibling of the chunks in the same store. It leaves the ordered mailbox
            // entirely: the reader fetches it on demand by contentId
            // (OnDemandResolutionImpl → SyncProcessor.resolveContentManifest), so a moved
            // file (new entity id, same contentId) still resolves with no re-emit, and
            // the relay never sees a dictionary-attackable path-hash routing key (§7).
            val put = store.putChunk(manifestContentRef(contentId), encrypted)
            if (put is org.tenshu.core.TResult.Failure) {
                contentEmitBackoff.failed(target.emitKey, put.error.messageOrNull())
                // ⭐ Park the chunks here too, and this is the case where it is worth the most:
                // EVERY chunk landed and only the manifest did not, so without this the retry
                // re-encrypts and re-sends the entire file to redo one small `PUT`.
                if (precomputedHash != null && landedByIndex.isNotEmpty()) {
                    recordEmitProgress(
                        target = target,
                        targetEntityId = targetEntityId,
                        propertyId = propertyId,
                        filePath = filePath,
                        contentHash = precomputedHash,
                        wrappedCekHex = cryptoManager
                            .encryptWithAad(cek, volumeKey, cekWrapAad(fileId)).toHexLower(),
                        landedChunkHashes = (0 until chunkCount)
                            .joinToString(",") { landedByIndex[it].orEmpty() },
                    )
                }
                // ⚠⚠ Classify it. The manifest is a `PUT` like any chunk, so the relay refuses it
                // for quota exactly the same way — and `RestContentBlobStore` tags that with
                // [STORAGE_REJECTION_MARKER]. Discarding `put.error` here would make a
                // self-reinforcing loop with no backoff at all: chunks a previous attempt landed are
                // skipped WHOLE on retry, so an entry parked with every chunk already up retries for
                // ever with zero chunk PUTs and one manifest PUT, which for an owner at the ceiling
                // 507s every time. With no marker the level stays false, the pass never truncates,
                // and the scheduler sees no edge and clears the backoff.
                if (put.error.messageOrNull()?.contains(STORAGE_REJECTION_MARKER) == true) {
                    contentStorageRejected = true
                    storageRefusalSeen = true
                    storageRefusalSeenThisEntry = true
                }
                println(
                    "SyncObserver -> file-content manifest PUT failed " +
                            "(contentId=$contentId) — aborting emit"
                )
                return false
            }

            // ⭐ The describe (`content-plane-refcount.md` §6e): this content object is made of these
            // refs, so the relay can free them once nothing references it. It reads no manifest and
            // derives nothing (HR #1). ⚠ No edge: which holder references this content reaches the
            // relay only with the holder's own push, which carries the row's complete edge set
            // (`content-plane-cr2-b3-wire-spec.md` §3.1).
            //
            // ⚠⚠ AFTER the manifest PUT, and necessarily: a ref is `sha256(ciphertext)`, so the set
            // does not exist until the chunks have been produced and PUT (§10.12 Q3). The describe
            // carries no edge, so it starts no clock, and it may land before or after the row's push.
            //
            // ⚠⚠ Best-effort: a `false` is a transport failure, and failing the emit for it would make
            // every upload retry for ever. The backfill re-describes.
            //
            // The manifest is a blob of this content too, and freeing the chunks while leaving it behind
            // would just move the orphan.
            val refs = chunkRefs.map { it.ref.get() } + manifestContentRef(contentId)
            onRefs(refs)
            if (!describe) {
                wakeSignal?.wake()
                return true
            }
            val described = store.describe(
                listOf(ContentDescription(contentId = contentId, refs = refs))
            )
            // ⭐ `content-plane-cr2-b4-spec.md` §2.2 step 4: a describe refused as FREED means the
            // content was freed while it was PENDING (its last edge went before this upload landed, §10.12 Q2). The describe is dropped,
            // and so is the upload's journal entry (the `true` below clears it): re-describing is refused
            // for ever, and the row's own push meets the refusal and repairs through a revive.
            val declared = described == DescribeOutcome.Described
            onDeclared(declared)
            if (described is DescribeOutcome.Refused) {
                println(
                    "SyncObserver -> content $contentId was freed before its describe landed; the describe " +
                        "is dropped and the holder's push will revive it"
                )
            } else if (!declared) {
                // ⚠⚠ This does NOT heal on its own: a file is emitted once, so a file never
                // re-uploaded stays undeclared for ever. The content-declaration backfill closes it — re-declaring is free, because the
                // relay holds a set.
                println(
                    "SyncObserver -> content NOT described (contentId=$contentId) — the blob " +
                            "is live but undescribed on the relay, and stays so until a backfill pass"
                )
            }
        } else {
            // ⚠ No contentId, so no edge — and the chunks above ARE on the content plane. This
            // path's bytes stay undeclared, one of the reasons the relay may not free on an empty
            // edge set alone.
            // Legacy / shared-realm path: the manifest rides the ordered pendingBlocks
            // drain keyed by the (already-stable) entity hash. Scope
            // to the file entity hash so the reader finds it via fetchEntityBlocks.
            // ⭐ sync-envelope-authentication.md §3.2: a manifest BLOCK binds its envelope like any other.
            val block = EnvelopeAuth.sealSyncBlock(
                crypto = cryptoManager,
                context = executionContext.context,
                blockId = generateId(),
                ownerId = ownerId,
                hlc = syncManager.hlcManager.tick(),
                targetEntityIdHash = wireIds.of(targetEntityId),
                plaintext = manifestPlaintext,
                identityKey = keys.sharedIdentityKey,
            )
            // Personal realm uses the base relation; a shared realm uses the per-realm-
            // suffixed relation so its manifest drains to its own relay.
            val pendingRelationId =
                if (realmRegistry == null || realmRegistry.isPersonal(realm)) {
                    SyncService.Collections.PENDING_BLOCKS
                } else {
                    SyncService.Collections.PENDING_BLOCKS + RealmRegistry.NAMESPACE_SEP + ownerId
                }
            val pendingBlocksRef = ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = executionContext.context,
                    type = SyncBlock.ID,
                    relationId = pendingRelationId
                ),
                isEntity = false,
                elementSchemaId = SyncBlock.ID
            )
            executionContext.database.addToArray(pendingBlocksRef, block)
        }
        // User-originated upload path — wake the scheduler so any pending outbound
        // (the entity metadata block, and the legacy manifest block) drains promptly.
        // The content-plane PUTs above already completed synchronously here.
        wakeSignal?.wake()
        return true
    }

    override suspend fun shareContentToRealm(
        contentId: String,
        targetRealmOwnerId: String,
        targetPropertyId: String,
    ): Boolean {
        if (!syncManager.isEnabled()) return false
        // Source = the personal realm (only YOUR file is shared): its content lives in the
        // personal store, its manifest is wrapped under the personal identity key + CEK
        // under the personal volume key(s).
        val personalRealm = syncManager.currentRealmContext() ?: return false
        val personalStore = contentBlobStore ?: return false
        // Target = the shared realm's runtime (its own store + keys, ownerId-scoped).
        val targetRuntime = realmRegistry?.runtimeFor(targetRealmOwnerId) ?: return false
        val targetKeys = targetRuntime.context.derivedKeys
        val targetStore = targetRuntime.contentBlobStore ?: return false

        // 1. Fetch + decrypt the personal manifest (content plane, personal identity key).
        val manifestRef = manifestContentRef(contentId)
        val manifestCipher: ByteArray =
            (personalStore.getChunk(manifestRef) as? TResult.Success)?.value?.get() ?: return false
        val parser = BinaryParser(
            executionContext.context.schemaProvider(),
            executionContext.context.dataFactory(),
        )
        val manifest = runCatching {
            val plain = cryptoManager.decrypt(manifestCipher, personalRealm.derivedKeys.sharedIdentityKey)
            val opened = BlockPayloadEnvelope.open(plain) ?: return false
            parser.parse<Data>(opened.payloadBytes)
        }.getOrNull() as? FileContentManifestPayload ?: return false

        // 2. Unwrap the CEK under the personal volume key(s) (current + retired, GR-7).
        val personalVolumeKeys = buildList {
            add(personalRealm.derivedKeys.sharedVolumeKey)
            personalRealm.historicalDerivedKeys.forEach { add(it.sharedVolumeKey) }
        }
        val cek = unwrapCek(manifest.wrappedCek.get(), contentId, personalVolumeKeys) ?: return false

        // 3. Copy the CEK-encrypted chunk blobs personal → target store, by ref. The
        //    ciphertext is realm-independent, so the same `blob-<sha256(ciphertext)>` ref
        //    is valid in both stores — no decrypt, no re-encrypt of the (large) bodies.
        //    Copies overlap for the same reason emit's do — and this loop pays *two* round trips
        //    per chunk (GET then PUT), so a serial version of it is the worst latency shape in
        //    the content plane. Production is trivial here (indexing an in-memory ref list, no
        //    hash chain and no offset to advance), so the permit bounds exactly what matters:
        //    at most N chunk ciphertexts resident at once.
        val chunks = manifest.chunks.toList()
        try {
            mapChunksBounded(
                concurrency = chunkTransferConcurrency,
                produceNext = { index -> chunks.getOrNull(index) },
            ) { _, chunkRef ->
                val ref = chunkRef.ref.get()
                val cipher: ByteArray =
                    (personalStore.getChunk(ref) as? TResult.Success)?.value?.get()
                        ?: throw ChunkPutFailed("shareContentToRealm -> source chunk GET failed ($ref)")
                if (targetStore.putChunk(ref, cipher) is TResult.Failure) {
                    throw ChunkPutFailed("shareContentToRealm -> target chunk PUT failed ($ref)")
                }
            }
        } catch (e: ChunkPutFailed) {
            println(e.message)
            return false
        }

        // 4. Re-wrap the CEK under the TARGET volume key + rewrite the manifest's
        //    propertyId to the shared data slot (so the joiner's applier resolves the
        //    realm byte-path), then re-encrypt the tiny manifest under the target
        //    identity key and PUT it at the same content-addressed ref in the target store.
        val reWrappedCek =
            cryptoManager.encryptWithAad(cek, targetKeys.sharedVolumeKey, cekWrapAad(contentId))
        val reKeyed = FileContentManifestPayload.create(
            context = executionContext.context,
            fileId = contentId,
            propertyId = targetPropertyId,
            chunkCount = manifest.chunkCount.get().toInt(),
            totalSize = manifest.totalSize.get(),
            wholeFileHash = manifest.wholeFileHash.get(),
            chunks = chunks,
            wrappedCek = reWrappedCek,
        )
        val reEncrypted = cryptoManager.encrypt(
            BlockPayloadEnvelope.seal(BinarySerializer().serialize(reKeyed), blockStamp),
            targetKeys.sharedIdentityKey
        )
        val put = targetStore.putChunk(manifestRef, reEncrypted)
        if (put is TResult.Success) {
            wakeSignal?.wake()
            return true
        }
        return false
    }

    /** Unwrap the CEK from [wrappedCek], trying each candidate volume key (GR-7 retained keyring). */
    private fun unwrapCek(
        wrappedCek: ByteArray,
        contentId: String,
        candidateVolumeKeys: List<ByteArray>,
    ): ByteArray? {
        val aad = cekWrapAad(contentId)
        for (volumeKey in candidateVolumeKeys) {
            try {
                return cryptoManager.decryptWithAad(wrappedCek, volumeKey, aad)
            } catch (_: Exception) {
                // wrong key ⇒ AEAD tag fails; try the next retired key.
            }
        }
        return null
    }
}

/**
 * Hex → bytes for the parked wrapped CEK, the inverse of the `toHexLower()` that wrote it.
 *
 * ⚠ Returns empty on anything malformed rather than throwing: a corrupt parked value must degrade
 * to "cannot resume, start fresh", never to a failed upload. The unwrap that follows fails closed
 * for the same reason.
 */
private fun String.hexToByteArraySafe(): ByteArray {
    if (length % 2 != 0) return ByteArray(0)
    return runCatching {
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrElse { ByteArray(0) }
}
