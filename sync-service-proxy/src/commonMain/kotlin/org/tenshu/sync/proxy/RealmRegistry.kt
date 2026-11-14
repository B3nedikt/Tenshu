package org.tenshu.sync.proxy

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
)

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
    private val processorFactory: ((RealmContext) -> SyncProcessor)? = null,
    /**
     * Builds the per-realm [SyncRelay] for a registered shared realm (SR-3b.2) — a
     * relay client whose JWT is scoped to the shared realm's `ownerId` (signed with
     * the device's same A4 key). Consulted by [register] when no explicit relay is
     * passed. `null` (the default, and direct-construction tests) ⇒ realms register
     * with no relay (they emit-route + queue locally but don't push/pull).
     * Production DI supplies the factory.
     */
    private val relayFactory: ((String) -> SyncRelay?)? = null,
    /**
     * Builds the per-realm [org.tenshu.sync.common.ContentBlobStore] (SR-3c-prereq) — a
     * content-plane client scoped to the shared realm's `ownerId`. Consulted by
     * [register]. `null` (the default) ⇒ shared realms have no content store
     * (`emitFileContent` to them no-ops). Production DI supplies the factory.
     */
    private val contentBlobStoreFactory: ((String) -> org.tenshu.sync.common.ContentBlobStore?)? = null,
) {

    // Insertion-ordered for deterministic iteration; keyed by realm ownerId.
    private val runtimes = LinkedHashMap<String, RealmRuntime>()

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
     * Idempotent on re-register (rebuilds the runtime — note a re-register resets
     * the pull cursor; shared realms aren't re-registered mid-session today).
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
        val resolvedRelay = relay ?: relayFactory?.invoke(realm.ownerId)
        val processor = processorFactory?.invoke(realm)
        val contentStore = contentBlobStoreFactory?.invoke(realm.ownerId)
        runtimes[realm.ownerId] = RealmRuntime(realm, resolvedRelay, processor, contentBlobStore = contentStore)
    }

    /** True iff a shared realm with [ownerId] is already registered. */
    fun isRegistered(ownerId: String): Boolean = runtimes.containsKey(ownerId)

    /** The registered shared realm's runtime for [ownerId], or null. */
    fun runtimeFor(ownerId: String): RealmRuntime? = runtimes[ownerId]

    /** Forget a shared realm (e.g. on removal from it). */
    fun unregister(realmId: String) {
        runtimes.remove(realmId)
    }

    /**
     * Forget every shared realm — "Forget this device" (`ForgetThisDeviceImplementation`). Each
     * runtime holds the forgotten identity and its own cursor, both dropped with it. Returns how
     * many were registered.
     */
    fun unregisterAll(): Int {
        val count = runtimes.size
        runtimes.clear()
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
