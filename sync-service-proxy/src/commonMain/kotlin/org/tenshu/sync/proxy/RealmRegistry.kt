package org.tenshu.sync.proxy

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.atomicfu.locks.withLock

import org.tenshu.core.common.util.HLCManager
import org.tenshu.core.common.util.encodeAdmissionStamp
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.RealmSchemaEpoch
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay

/**
 * A shared realm's runtime state on this device: its [context] (keys + ownerId),
 * the [relay] its outbound queue is drained to / its inbound stream is pulled from,
 * its own [processor] (decrypts inbound blocks under *this* realm's keys + advances
 * *this* realm's HLC — SR-M.1b-pull), and its own pull [cursor].
 *
 * [relay] is null for a realm registered for emit-routing only — its blocks queue
 * but aren't pushed/pulled until a relay is wired (a shared-folder migration, SR-3b,
 * registers one). [processor] is null when the registry was built without a
 * processor factory (emit/push-only or direct-construction tests); the pull skips
 * such realms.
 *
 * [cursor] is the per-realm equivalent of `SyncManager.lastSyncTimestamp` — the
 * relay's arrival-time cursor for *this* realm's stream, advanced after each pull.
 * It is deliberately mutable runtime state held here (not on the immutable
 * [RealmContext]) so the personal realm's single-valued `SyncManager` is untouched.
 */
class RealmRuntime(
    val context: RealmContext,
    val relay: SyncRelay?,
    val processor: SyncProcessor? = null,
    var cursor: Long = 0L,
    /**
     * The realm's content-plane client (SR-3c-prereq) — `emitFileContent` `PUT`s this
     * realm's file chunks to its own `ownerId`-scoped content store. `null` when the
     * registry was built without a content-store factory (no shared-realm file content).
     */
    val contentBlobStore: org.tenshu.sync.common.ContentBlobStore? = null,
    /**
     * The realm's **one** HLC, ticking as [RealmContext.deviceId] — shared by the emit side
     * (`SyncObserver` stamps this realm's blocks with it), the ingest side ([processor] advances it)
     * and the signed tip. ⚠⚠ Never the personal `SyncManager.hlcManager` for a shared realm: its
     * node id is the personal device id, clear on every envelope, which links a member's personal
     * realm to the shares it writes into (threat model T-GR-05). `null` only in wirings built
     * without a clock factory; [RealmRegistry.clockFor] then supplies a realm-local one.
     */
    val clock: HLCManager? = null,
) {
    /** This realm's pulls, for the caught-up gate ([RealmRegistry.caughtUpSinceStuck]). Resets with the runtime. */
    val pulls: PullProgress = PullProgress()

    /** The [pulls] count when a rotation recovery first found this realm stuck; `null` when not stuck. */
    var stuckSeenAtPull: Long? = null

    /** This device's own clock at that first observation — what the recovery's time-only valve measures from. */
    var stuckSeenAtMillis: Long? = null
}

/**
 * Counts one stream's pulls so a caller can ask "has a **complete** pull started since I looked?" — no clocks
 * involved. A pull takes a number at its start ([begin]); a complete one (the relay answered, nothing more
 * waiting) records it ([finish]). [completeSince] is then true only for a complete pull that *started* after
 * the observation, so a pull already in flight at that moment never counts.
 */
class PullProgress {
    // ⚠ Written by the scheduler's tick, read by grant operations on other coroutines (and threads): every
    // access holds the lock, so an observation never reads a stale count and then credits an in-flight pull.
    private val lock = reentrantLock()
    private var started = 0L
    private var completeFrom = -1L

    fun begin(): Long = lock.withLock { ++started }

    /** ⚠ A pull that left more behind clears the mark: there is a backlog again. */
    fun finish(token: Long, complete: Boolean) = lock.withLock {
        completeFrom = if (complete) maxOf(completeFrom, token) else -1L
    }

    /** The current count, to observe now and compare later. */
    fun observe(): Long = lock.withLock { started }

    fun completeSince(observed: Long): Boolean = lock.withLock { completeFrom > observed }
}

/**
 * The set of realms this device participates in (SR-M.1b,
 * `docs/proposals/sync-multi-realm-runtime.md`).
 *
 * The **personal** realm is always `syncManager.currentRealmContext()` —
 * resolved dynamically, never held as an entry, so its behaviour is byte-
 * identical to the single-realm world. **Shared** realms (shared folders, each
 * its own realm with its own master key) are registered explicitly, each
 * carrying its own [RealmRuntime] (own `ownerId` + keys + relay).
 *
 * An entity is routed to its realm by a **namespace prefix on its DataId key**:
 * a shared realm's entities are keyed `"$realmId::$baseKey"`, personal entities
 * are unprefixed. Routing is therefore a deterministic prefix lookup, not a
 * heuristic — which is what keeps the emit path from ever encrypting an entity
 * under the wrong realm's key (the highest-risk failure mode, §2.2 of the doc).
 *
 * Emit routing (SR-M.1b-core) decides which realm's keys + queue a saved
 * entity's block goes to. The push drain (SR-M.1b-drain) pushes each shared
 * realm's queue to its own [RealmRuntime.relay]. The pull (SR-M.1b-pull) pulls
 * each shared realm's stream through that realm's own [RealmRuntime.processor]
 * (keyed to its keys + clock) and advances its own [RealmRuntime.cursor].
 */
class RealmRegistry(
    private val syncManager: SyncManager,
    /**
     * Builds the per-realm inbound [SyncProcessor] for a registered shared realm
     * (SR-M.1b-pull) — one that decrypts under that realm's keys + advances that
     * realm's own HLC. `null` in wirings that only emit/push (no inbound pull) and
     * in direct-construction tests; the pull then skips realms with no processor.
     * `initSyncService` supplies the production factory.
     */
    private val processorFactory: ((RealmContext, HLCManager?) -> SyncProcessor)? = null,
    /**
     * Builds a registered shared realm's [SyncRelay] — ⚠⚠ signed as the device **registered in that
     * realm** ([RealmContext.deviceId] / [RealmContext.deviceSigningKeyPair], `perRealmDeviceRelay`),
     * never the personal A4 (D10, threat model T-GR-05). Consulted by [register] when no explicit relay
     * is passed. `null` (direct-construction tests) ⇒ realms register with no relay (they emit-route +
     * queue locally but don't push/pull) — never with a personal-key one.
     */
    private val relayFactory: ((RealmContext) -> SyncRelay?)? = null,
    /**
     * Builds the per-realm [org.tenshu.sync.common.ContentBlobStore] (SR-3c-prereq) — a
     * content-plane client scoped to the shared realm's `ownerId`. Consulted by
     * [register]. `null` (the default) ⇒ shared realms have no content store
     * (`emitFileContent` to them no-ops). Production DI supplies the factory.
     */
    private val contentBlobStoreFactory: ((RealmContext) -> org.tenshu.sync.common.ContentBlobStore?)? = null,
    /**
     * Builds a registered shared realm's [RealmRuntime.clock] (T-GR-05) — one per realm, handed to
     * its [processorFactory] too, so emit and ingest tick one clock. `null` (direct-construction
     * tests) ⇒ [clockFor] makes a realm-local clock on first use instead.
     */
    private val clockFactory: ((RealmContext) -> HLCManager)? = null,
) {

    // Insertion-ordered for deterministic iteration; keyed by realm ownerId.
    private val runtimes = LinkedHashMap<String, RealmRuntime>()

    /** The personal realm's pulls (forward pass plus backfill), for [caughtUpSinceStuck]. */
    val personalPulls: PullProgress = PullProgress()

    // Per shared realm: the [personalPulls] count when a rotation recovery first found it stuck.
    private val personalStuckSeen = HashMap<String, Long>()

    /**
     * GR-7: has this device caught up on **both** streams since it first found [realmId] stuck on a retired key?
     * That is: a complete pull of the shared realm (its signed membership records, so a revocation is known) and
     * of the personal realm (where another owner device's newer membership would arrive), each started after
     * the first call for this stuck spell. The first call records the observation, so it always answers `false`.
     *
     * ⚠ An unregistered realm, or one with no relay, never pulls, so it never counts as caught up.
     * ⚠ It proves only that the relay *says* there is nothing more. A relay that withholds a revocation still
     * passes (threat model T-GR-02).
     */
    fun caughtUpSinceStuck(realmId: String, nowMillis: Long): Boolean = stuckLock.withLock {
        val runtime = runtimes[realmId] ?: return false
        val sharedSeen = runtime.stuckSeenAtPull ?: runtime.pulls.observe().also { runtime.stuckSeenAtPull = it }
        if (runtime.stuckSeenAtMillis == null) runtime.stuckSeenAtMillis = nowMillis
        val personalSeen = personalStuckSeen.getOrPut(realmId) { personalPulls.observe() }
        return runtime.pulls.completeSince(sharedSeen) && personalPulls.completeSince(personalSeen)
    }

    /**
     * How long, by this device's own clock, [realmId] has been seen stuck — `0` before the first
     * [caughtUpSinceStuck] of the spell, and for an unregistered realm. ⚠ In memory: a restart starts a new spell.
     */
    fun stuckForMillis(realmId: String, nowMillis: Long): Long = stuckLock.withLock {
        val since = runtimes[realmId]?.stuckSeenAtMillis ?: return 0L
        (nowMillis - since).coerceAtLeast(0L)
    }

    /** End [realmId]'s stuck spell: the next one observes afresh. */
    fun clearStuck(realmId: String) = stuckLock.withLock {
        runtimes[realmId]?.let {
            it.stuckSeenAtPull = null
            it.stuckSeenAtMillis = null
        }
        personalStuckSeen.remove(realmId)
    }

    /**
     * Serializes the grant operations (grant, accept, remove, poll). A rotation suspends — it drains the emitter
     * first — and two of them interleaving could let a stale re-key overwrite a fresh one in a contact's one-slot
     * grant mailbox, or let two recoveries both pass their check and fork the key.
     */
    val grantOps: Mutex = Mutex()

    /** GR-7 key requests and their rate limits ([KeyRequestTracker]). */
    val keyRequests: KeyRequestTracker = KeyRequestTracker()

    // Grant operations run on several coroutines at once; the stuck observations are read-modify-write.
    private val stuckLock = reentrantLock()
    /** How many forgets are running — [register] refuses while any is (a count: forgets can overlap). */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private val forgetsInProgress = kotlin.concurrent.atomics.AtomicInt(0)

    /**
     * Run [block] — "Forget this device" — with registration refused throughout, and every shared
     * realm unregistered both before it and after it: a runtime registered in between (a
     * membership pulled before custody went) holds the forgotten identity.
     */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    inline fun <R> whileForgetting(block: () -> R): R {
        beginForget()
        try {
            return block()
        } finally {
            unregisterAll()
            endForget()
        }
    }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @PublishedApi internal fun beginForget() { forgetsInProgress.fetchAndAdd(1) }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @PublishedApi internal fun endForget() { forgetsInProgress.fetchAndAdd(-1) }

    /**
     * Register a shared realm this device belongs to, optionally with the [relay]
     * its outbound queue drains to / inbound stream is pulled from. When a
     * [processorFactory] is configured, a per-realm inbound [SyncProcessor] is
     * built here so the pull can decrypt this realm's stream under its own keys.
     * Idempotent on re-register: rebuilds the runtime, resetting the pull cursor but keeping the clock when
     * the realm-local device id is unchanged. A key rotation, and a re-key synced in from another of this
     * user's devices, both re-register mid-session.
     */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    fun register(realm: RealmContext, relay: SyncRelay? = null) {
        // ⚠⚠ Refused while "Forget this device" runs: the personal pull keeps going until custody is
        // cleared, and a membership it pulls would register a runtime under the identity being
        // forgotten — surviving the forget, then failing on its zeroed key (§7i.5).
        if (forgetsInProgress.load() > 0) {
            println("RealmRegistry -> not registering ${realm.ownerId}: this device is being forgotten")
            return
        }
        val resolvedRelay = relay ?: relayFactory?.invoke(realm)
        // ⚠⚠ A re-register under the SAME realm-local device id (a key rotation, or a re-key that synced in)
        // keeps that id's clock. A fresh one restarts at (0,0): the next edit is stamped from wall time alone,
        // ignoring every HLC already seen, and loses LWW to an older write from a fast-clocked peer — and the
        // signed tip goes backwards for an id the relay already recorded. ⚠ The cursor is NOT carried: a pull
        // from 0 under the new keys is what recovers blocks sealed under them before this device held them.
        val previous = runtimes[realm.ownerId]
        val clock = previous?.clock?.takeIf { previous.context.deviceId == realm.deviceId }
            ?: clockFactory?.invoke(realm)
        val processor = processorFactory?.invoke(realm, clock)
        val contentStore = contentBlobStoreFactory?.invoke(realm)
        fallbackClocks.remove(realm.ownerId)
        runtimes[realm.ownerId] =
            RealmRuntime(realm, resolvedRelay, processor, contentBlobStore = contentStore, clock = clock)
    }

    // Realm-local clocks for runtimes registered without a clock factory; keyed by realm ownerId.
    private val fallbackClocks = HashMap<String, HLCManager>()

    /**
     * The clock a block for [realm] must be stamped with: its runtime's [RealmRuntime.clock] for a
     * registered shared realm, or `null` for the personal realm (the caller uses
     * `SyncManager.hlcManager`, unchanged). ⚠⚠ A shared realm **never** gets `null` — without a
     * configured clock it gets a realm-local one ticking as [RealmContext.deviceId], never the
     * personal clock (T-GR-05).
     */
    fun clockFor(realm: RealmContext, context: TContext): HLCManager? {
        val runtime = runtimes[realm.ownerId] ?: return null
        return runtime.clock
            ?: fallbackClocks.getOrPut(realm.ownerId) { HLCManager(runtime.context.deviceId, context) }
    }

    /**
     * An admission stamp for this device's registration in shared realm [realmId] (§5b R3):
     * `HlcStamp.encode()` of a tick of **that realm's** clock, so it names the realm-local device id
     * (T-GR-05). `null` when the realm is not registered here — the admission then carries none,
     * the pre-R3 behaviour.
     */
    fun admissionStampFor(realmId: String, context: TContext): String? {
        val runtime = runtimes[realmId] ?: return null
        return clockFor(runtime.context, context)?.tick()?.encodeAdmissionStamp()
    }

    /** True iff a shared realm with [ownerId] is already registered. */
    fun isRegistered(ownerId: String): Boolean = runtimes.containsKey(ownerId)

    /** The registered shared realm's runtime for [ownerId], or null. */
    fun runtimeFor(ownerId: String): RealmRuntime? = runtimes[ownerId]

    /** Forget a shared realm (e.g. on removal from it). */
    fun unregister(realmId: String) {
        runtimes.remove(realmId)
        fallbackClocks.remove(realmId)
        stuckLock.withLock { personalStuckSeen.remove(realmId) }
    }

    /**
     * Forget every shared realm — "Forget this device" (`ForgetThisDeviceImplementation`). Each
     * runtime holds the forgotten identity and its own cursor, both dropped with it. Returns how
     * many were registered.
     */
    fun unregisterAll(): Int {
        val count = runtimes.size
        runtimes.clear()
        fallbackClocks.clear()
        stuckLock.withLock { personalStuckSeen.clear() }
        keyRequests.clear()
        return count
    }

    /** The registered shared realms (excludes the personal realm). */
    fun sharedRealms(): List<RealmContext> = runtimes.values.map { it.context }

    /** Shared realms that have a relay wired — the ones the push drain processes. */
    fun sharedRuntimesWithRelay(): List<RealmRuntime> =
        runtimes.values.filter { it.relay != null }

    /**
     * The realm a saved entity belongs to: a registered shared realm if the
     * entity's [dataId] key carries that realm's `"$realmId::"` prefix, else the
     * personal realm (`syncManager.currentRealmContext()`). Returns `null` only
     * when the device has no enabled personal realm and the key matches no
     * shared realm — the same not-enabled gate the single-realm path used.
     */
    fun realmForDataId(dataId: DataId?): RealmContext? {
        val key = (dataId as? DataId.StringDataId)?.keyValue?.get()
        if (key != null) {
            val candidate = key.substringBefore(NAMESPACE_SEP, missingDelimiterValue = "")
            if (candidate.isNotEmpty()) {
                runtimes[candidate]?.let { return it.context }
            }
        }
        return syncManager.currentRealmContext()
    }

    /**
     * True iff [realm] is this device's personal realm (vs a registered shared
     * realm). Used to pick the outbound queue: the personal realm uses the base
     * `PENDING_BLOCKS` relation (unchanged); a shared realm uses a per-realm
     * suffixed relation so the two never collide.
     */
    fun isPersonal(realm: RealmContext): Boolean =
        !runtimes.containsKey(realm.ownerId)

    companion object {
        /** Separator between a shared realm's id and an entity's base key. */
        const val NAMESPACE_SEP = "::"

        /** The per-realm outbound block queue relation id (personal = base). */
        fun pendingBlocksRelationId(baseRelationId: String, realmId: String): String =
            baseRelationId + NAMESPACE_SEP + realmId

        /**
         * The key of a realm's [org.tenshu.sync.common.RealmSchemaEpoch] row
         * (`core-schema-evolution.md` §7.1): the bare singleton key for the personal
         * realm ([realmId] `null`), else the shared realm's namespaced key.
         *
         * Deliberately the **same `"$realmId::"` prefix every other shared-realm entity
         * carries**, rather than a bespoke scheme: the epoch row is then routed, keyed,
         * encrypted, queued and pushed to its own realm by [realmForDataId] and the
         * ordinary emit path, with no special case anywhere. The personal key is
         * unchanged, so existing rows need no migration.
         */
        fun epochKeyFor(realmId: String?): String =
            if (realmId == null) RealmSchemaEpoch.SINGLETON_KEY
            else realmId + NAMESPACE_SEP + RealmSchemaEpoch.SINGLETON_KEY
    }
}


/**
 * GR-7 key requests, both sides, with their rate limits (`shared-realm-membership-grant.md` §14.3). Process-wide on
 * [RealmRegistry], because the grant manager is rebuilt for every operation.
 *
 * - **Member:** a realm whose stream holds a block no key opens is *behind*; at most one request per realm per
 *   [MIN_INTERVAL_MS] goes out ([due], [requested]).
 * - **Owner:** at most one answer per member per realm per [MIN_INTERVAL_MS] ([mayAnswer]).
 *
 * ⚠⚠ The trigger is forgeable — a relay can put any bytes in a stream — so it buys an attacker one request and one
 * signed, ordered re-key per interval, which they cannot read or steer. The limits are what keep that bounded.
 */
class KeyRequestTracker {
    private val lock = reentrantLock()
    /** Realm → the latest stamp of a block there that no key opened (unauthenticated: it did not open). */
    private val behind = HashMap<String, Long>()
    private val lastRequested = HashMap<String, Long>()
    private val lastAnswered = HashMap<String, Long>()

    fun markBehind(realmId: String, blockPhysicalTime: Long) = lock.withLock {
        behind[realmId] = maxOf(behind[realmId] ?: Long.MIN_VALUE, blockPhysicalTime)
    }

    /** The latest unopenable block's stamp in [realmId], or null when nothing marked it. */
    fun latestUnopenable(realmId: String): Long? = lock.withLock { behind[realmId] }

    /** The behind realms this device may ask about now. */
    fun due(nowMs: Long): Set<String> = lock.withLock {
        behind.keys.filterTo(mutableSetOf()) { realm -> lastRequested[realm]?.let { nowMs - it >= MIN_INTERVAL_MS } ?: true }
    }

    /** Every realm still behind — what a request names, so a replacement request never drops an outstanding one. */
    fun behindRealms(): Set<String> = lock.withLock { behind.keys.toSet() }

    /**
     * A request went out for [realmId]. ⚠ It stays behind: a lost request or a lost answer must be retried once the
     * interval passes, and nothing else would mark it again (the pull has moved past the block). Only [adopted] clears.
     */
    fun requested(realmId: String, nowMs: Long) = lock.withLock { lastRequested[realmId] = nowMs }

    /** A genuine re-key for [realmId] was adopted (or confirmed the key we hold): no longer behind. */
    fun adopted(realmId: String) = lock.withLock { behind.remove(realmId); Unit }

    /** Whether the owner may answer [memberId]'s request for [realmId] now. */
    fun mayAnswer(realmId: String, memberId: String, nowMs: Long): Boolean = lock.withLock {
        val last = lastAnswered["$realmId::$memberId"]
        last == null || nowMs - last >= MIN_INTERVAL_MS
    }

    /** The owner's answer went out. ⚠ Only once it did: a failed drop must not hold the next answer back. */
    fun answered(realmId: String, memberId: String, nowMs: Long) = lock.withLock { lastAnswered["$realmId::$memberId"] = nowMs }

    fun clear() = lock.withLock {
        behind.clear()
        lastRequested.clear()
        lastAnswered.clear()
    }

    companion object {
        /** One request, and one answer, per realm (per member) per six hours. */
        const val MIN_INTERVAL_MS: Long = 6L * 60L * 60L * 1_000L
    }
}
