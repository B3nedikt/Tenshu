@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.tenshu.sdk.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.file.client.declareWantedThumbnailClassesAtLaunch
import org.tenshu.preference.client.preferenceService
import org.tenshu.core.InteractorProvider
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.PureOperationRunnerImplementation
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.app.SchemaAnnouncer
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.CallstackMaterializer
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.Database
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.LocalClientConnection
import org.tenshu.core.common.proxy.forwardStreamElementsInOrder
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.proxy.PendingRedemptionStore
import org.tenshu.contact.proxy.RestInviteRelayClient
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.contact.proxy.ContactPhotosPropertyWriter
import org.tenshu.sync.proxy.RealmRegistryHolder
import org.tenshu.sync.proxy.SyncProcessorHolder
import org.tenshu.sync.proxy.impl.OnDemandResolutionImpl
import org.tenshu.sync.proxy.perRealmDeviceRelay
import app.tenshu.diagnostics.LocalStorageDiagnosticSink
import org.tenshu.core.common.proxy.DelegatingExternalPropertyWriter
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.proxy.db.DatabaseImpl
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.NoOpDatabaseLock
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.security.DefaultSyncCryptoManager
import org.tenshu.core.common.security.MasterKeyWrapper
import org.tenshu.core.common.security.NobleAeadBackend
import org.tenshu.core.common.security.NobleEd25519Backend
import org.tenshu.core.common.security.NobleKdfBackend
import org.tenshu.core.common.security.NobleX25519Backend
import org.tenshu.core.common.security.jwtTokenProvider
import org.tenshu.core.common.time.SystemClock
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.LazyDataMaterializer
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RoProvider
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.Clock
import org.tenshu.core.time.clock
import org.tenshu.core.trait.TraitResolver
import org.tenshu.identity.EntitlementSignature
import org.tenshu.storage.proxy.DevSeedEntitlementMintClient
import org.tenshu.storage.proxy.EntitlementMintClient
import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import org.tenshu.storage.proxy.EntitlementStorageProvider
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.StorageProvider
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.TombstoneEmitterHolder
import org.tenshu.sync.proxy.RelayUsageClient
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.health.proxy.WorkoutStreamsPropertyWriter
import org.tenshu.sync.proxy.RestContentBlobStore
import org.tenshu.sync.proxy.RestSyncRelay
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.sync.proxy.SyncSchedulerHolder

// In-process runtime for the JS SDK. Mirrors the production Koin wiring in
// :core-common/.../di/CoreCommonModule.kt and :control-shared/.../di/ProxyModule.kt
// — same components, same providers — assembled by hand because we don't
// ship Koin in JS. It assembles:
//   - the foundation: SchemaRegistry / Clock / SyncCryptoManager /
//     TraitResolver / PureOperationRunner / DataFactory / SchemaProvider /
//     RoProvider / OperationResolver;
//   - per-service schemas, the Database, CallstackMaterializer,
//     ClientRegistry, and a default ExecutionContext;
//   - the executor side: LocalTaskExecutor over the default ExecutionContext,
//     DefaultInteractorProvider in tContext.components under
//     InteractorProvider.COMPONENT_ID, and the LocalClientConnection →
//     dispatcher bridge;
//   - the client surface that DSL extensions
//     (`client.fileService.defaultFileSystem.root.files.flow()`,
//     `client.notificationService.notifications.flow()`) navigate through: a
//     TenshuEnvironment over the task executor + a LocalRequestChain factory,
//     and a DefaultTenshuClient over the environment — the same shape as
//     `:control-shared/.../di/ProxyModule.kt`'s registrations.
//
// ClientRegistry's `initialization` runs each plugin's init, as production's
// `platformProxyInitialization` (PlatformProxyModule.kt) does; see
// `clientRegistryLazy`. ⚠ The file plugin's init creates the root `/` Folder
// entity: without it, `addDataToArray` under `/` silently no-ops and a
// created folder never lists.
//
// Persistence: the protocol store is an `IndexedDbObjectStore`, and file
// bytes an `IndexedDbFileDatabase` in a separate IDB database
// (`tenshu-sdk-files`). `open(...)` is suspend (it hydrates an in-memory
// cache from disk), so `JsRuntime` keeps each store as a `Deferred<...>` and
// exposes `awaitReady()`; SDK entrypoints `await` it before touching
// `tenshuClient`. The lazy chain stays synchronous — once hydration
// completes, `database` reads the store via `hydration.getCompleted()`.
//
// Sync: a `HttpClient(Js)` with kotlinx.serialization JSON, a `RestSyncRelay`
// pointing at `relayBaseUrl` (overridable via `JsRuntime.configure(...)`
// before first sync access, default `https://relay.tenshu.app`), and
// `SyncManagerImpl` over IndexedDB-backed secure storage. The sync chain is
// constructed lazily on first op dispatch — apex must call
// `JsRuntime.configure(...)` (via `TenshuSdk.configure(...)`) before the
// first sync-touching SDK call to override the relay URL.
//
// The wrapped master key lives in its own IDB store
// (`tenshu-sdk-master-keys`) with a `MasterKeyWrapper` constructed off the
// shared `SyncCryptoManager`. It is hydrated alongside the others by
// `awaitReady()` and wiped by `purgeRealmStorage()`. See
// `docs/web/architecture.md` §4.3 / §4.4.
//
// All non-IDB construction is `lazy`; the runtime is built on first access.
//
// Per-service wiring (extraConfigs / mergerRegistry / operation-factory
// chain / init lambda) is driven by iterating [JsRuntimeBuilder.orderedPlugins]
// (`docs/proposals/web-sdk-bundle-splitting.md`). Each service is a
// [JsRuntimePlugin] (see FirstPartyPlugins.kt); this object is the sole
// [RuntimeContext] the plugins compose against. Kept as an `object` (not the
// proposal's `class JsRuntime(plugins)`) so the `JsRuntime.foo` call sites
// across the SDK surface don't churn; [JsRuntimeBuilder] is the registration
// seam.
internal object JsRuntime : RuntimeContext {

    private const val DEFAULT_CLIENT_ID = "tenshu-js-sdk"
    internal const val OBJECT_STORE_DB_NAME = "tenshu-sdk"
    private const val FILE_DB_NAME = "tenshu-sdk-files"
    private const val SESSION_DB_NAME = "tenshu-sdk-session"
    private const val SECURE_STORAGE_DB_NAME = "tenshu-sdk-secure"
    private const val MASTER_KEY_DB_NAME = "tenshu-sdk-master-keys"
    private const val DEFAULT_RELAY_BASE_URL = "https://relay.tenshu.app"

    // Exposed `internal` so the unlock-with-phrase path
    // (`Session.unlockWithPhrase`) can derive the master keypair + shared
    // derived keys locally without going through the operation chain.
    // Mirrors the same `internal` access already granted to `syncManager`
    // and `masterKeyStore` for the PRF wrap/unwrap path —
    // local-only unlock plumbing is a session-API concern, not a
    // sync-protocol op.
    override val syncCryptoManager: SyncCryptoManager =
        DefaultSyncCryptoManager(
            ed25519 = NobleEd25519Backend(),
            x25519 = NobleX25519Backend(),
            aead = NobleAeadBackend(),
            kdf = NobleKdfBackend(),
        )
    private val systemClock: Clock = SystemClock()
    override val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    // Install the durable sink before anything can log. An `object` initialiser runs once,
    // on first touch of JsRuntime, which is earlier than any SDK entry point — so nothing the
    // runtime says is lost to the default no-op sink.
    init {
        app.tenshu.diagnostics.Diagnostics.install(LocalStorageDiagnosticSink())
        // ⚠ A boot line is not noise, it is the spine of the log: every other entry is only
        // interpretable relative to "which page load was this", and reconstructing that from
        // timestamps is guesswork.
        app.tenshu.diagnostics.Diagnostics.i("Runtime", "boot")
    }

    // Apex calls this once before any SDK op that touches the relay (in
    // particular `Realm.create`, `Pairing.submit`, `Recovery.claim`). Calling
    // it after the sync chain has been constructed is an error if the URL
    // would change — the relay URL is captured at lazy-init time and cannot
    // be hot-swapped without re-creating the runtime. Re-calling with the
    // *same* URL is a no-op so HMR / repeated test setup don't trip the
    // check.
    private var configuredRelayBaseUrl: String = DEFAULT_RELAY_BASE_URL
    fun configure(relayBaseUrl: String) {
        if (configuredRelayBaseUrl == relayBaseUrl) return
        check(!syncRelayLazy.isInitialized()) {
            "JsRuntime.configure(...) called with a different URL after the " +
                    "sync chain was already constructed (was=$configuredRelayBaseUrl, " +
                    "new=$relayBaseUrl). Configure once at module load before any " +
                    "sync-touching SDK call."
        }
        configuredRelayBaseUrl = relayBaseUrl
        // A bundle built against the wrong relay looks like a sync bug from every other angle.
        // The URL is not a secret and it is the first thing worth knowing.
        app.tenshu.diagnostics.Diagnostics.i("Runtime", "relay = $relayBaseUrl")
    }

    // IDB hydration starts on first access to JsRuntime. SDK entrypoints
    // (`FileSystem.*`, `Notifications.observe`, `TenshuSdk.awaitReady`) await
    // these Deferreds before they touch `tenshuClient`; the lazy `database`
    // and `fileDatabase` properties read the stores via `getCompleted()`, so
    // accessing the runtime chain before `awaitReady()` resolves throws.
    // That's by design — keeps the rest of the runtime synchronous-lazy
    // without bleeding `Deferred` through the type graph.
    // ⚠⚠ ONE RUNTIME PER ORIGIN, decided before any store opens (`RuntimeLease`). Every hydration
    // below awaits [leaseGranted] first, so a tab that lost the race to another tab never opens an
    // IndexedDB database — it cannot read a stale copy, and it cannot write one back.
    private val lease: Deferred<LeaseState> = serviceScope.async {
        RuntimeLease.default.acquire(onLost = { abandonAfterSteal() }) { yieldToAnotherTab() }
    }

    /** Suspends forever in a tab that does not hold the runtime: its stores must never open. */
    private suspend fun leaseGranted() {
        if (lease.await() == LeaseState.ELSEWHERE) awaitCancellation()
    }

    /** The lease outcome, acquiring it if nothing has yet (`TenshuSdk.awaitRuntimeLease`). */
    internal suspend fun awaitLease(): LeaseState {
        lease.await()
        return RuntimeLease.default.state.value
    }

    /**
     * The holder's half of "Use here": stop all runtime work, then close every store with
     * fire-and-forget writes — `close()` flushes the queue before it refuses more. Called by
     * [RuntimeLease.yieldNow] after it marked this page DISPLACED (so [awaitReady] already refuses)
     * and before it releases the lock.
     *
     * The session and master-key stores only write on an explicit `flush()` from an SDK call; they
     * are closed too, so an SDK call already past [awaitReady] cannot persist them afterwards.
     */
    private suspend fun yieldToAnotherTab() {
        serviceScope.coroutineContext[Job]?.cancelChildren()
        openedStores().forEach { store ->
            when (store) {
                is IndexedDbObjectStore -> store.close()
                is IndexedDbFileDatabase -> store.close()
                is IndexedDbSecureStorageDelegate -> store.close()
                is IndexedDbSessionStore -> store.close()
                is IndexedDbMasterKeyStore -> store.close()
            }
        }
        app.tenshu.diagnostics.Diagnostics.i("Runtime", "yielded the runtime to another tab")
    }

    /**
     * The lock was STOLEN (this tab was frozen or hung when another asked). ⚠⚠ Drop, never flush:
     * the new holder may already have hydrated and written, so whatever is still queued here is
     * stale, and flushing it would land old rows behind the new holder's cursor. Transactions
     * IndexedDB already started are refused by the write fence (`StoreFence.kt`), not by this.
     */
    private fun abandonAfterSteal() {
        serviceScope.coroutineContext[Job]?.cancelChildren()
        openedStores().forEach { store ->
            when (store) {
                is IndexedDbObjectStore -> store.abandon()
                is IndexedDbFileDatabase -> store.abandon()
                is IndexedDbSecureStorageDelegate -> store.abandon()
                is IndexedDbSessionStore -> store.close()
                is IndexedDbMasterKeyStore -> store.close()
            }
        }
        app.tenshu.diagnostics.Diagnostics.w("Runtime", "runtime lock was stolen — queued writes dropped")
    }

    // A store's write was refused: another runtime claimed the databases — stop and say so.
    private fun fencedOut() = RuntimeLease.default.fencedOut()

    private fun openedStores(): List<Any?> =
        listOf<Deferred<*>>(hydration, fileHydration, secureStorageHydration, sessionHydration, masterKeyHydration)
            .filter { it.isCompleted && !it.isCancelled }
            .map { it.getCompleted() }

    private val hydration: Deferred<IndexedDbObjectStore> = serviceScope.async {
        leaseGranted()
        // The per-service version gate (`core-schema-evolution.md` §8.9).
        // ⚠⚠ Without this config the store would gate on TENSHU_STORAGE_FORMAT_VERSION
        // alone — which a service-owned reshape deliberately does not move (House
        // Rule 9) — so v-old rows would reach new code and garble positionally.
        //
        // ⚠ Forcing `tContext` here is safe *because this runs in a coroutine*: the
        // body is dispatched, so it executes after the constructor has finished and
        // every `Lazy` delegate below exists. `tContext` forces `schemaRegistry`;
        // the reverse does not hold (the registry only captures a provider lambda),
        // so there is no reentrancy.
        IndexedDbObjectStore.open(
            dbName = OBJECT_STORE_DB_NAME,
            scope = serviceScope,
            migrations = StoreSchemaMigrations(
                context = tContext,
                schemas = schemaRegistry,
                // Unused on this path: the JS store has no migration seam, so the
                // config's role here is to say *wipe*, never *migrate*.
                deviceId = "",
            ),
            onFencedOut = ::fencedOut,
        )
    }
    private val fileHydration: Deferred<IndexedDbFileDatabase> = serviceScope.async {
        leaseGranted()
        IndexedDbFileDatabase.open(FILE_DB_NAME, serviceScope, onFencedOut = ::fencedOut)
    }
    private val sessionHydration: Deferred<IndexedDbSessionStore> = serviceScope.async {
        leaseGranted()
        IndexedDbSessionStore.open(SESSION_DB_NAME, onFencedOut = ::fencedOut)
    }
    private val secureStorageHydration: Deferred<IndexedDbSecureStorageDelegate> =
        serviceScope.async {
            leaseGranted()
            IndexedDbSecureStorageDelegate.open(SECURE_STORAGE_DB_NAME, serviceScope, onFencedOut = ::fencedOut)
        }
    private val masterKeyHydration: Deferred<IndexedDbMasterKeyStore> = serviceScope.async {
        leaseGranted()
        IndexedDbMasterKeyStore.open(MASTER_KEY_DB_NAME, onFencedOut = ::fencedOut)
    }

    // TC-2 (`sync-hlc-tip-device-identity.md`): the device's durable HLC watermark. Shared by
    // every tab of this origin — which is the point, since each tab runs its own JsRuntime and
    // therefore its own HLCManager under one shared device ID, and the relay keeps a single tip
    // slot per device. Without this, two tabs take turns overwriting that slot with their own
    // unrelated positions and peers read the backward step as a forked view.
    private val hlcWatermarkHydration: Deferred<IndexedDbHlcWatermarkStore> = serviceScope.async {
        leaseGranted()
        IndexedDbHlcWatermarkStore.open()
    }

    suspend fun awaitReady() {
        // ⚠ Refuse, never hang: a tab without the runtime answers every SDK call with this, which
        // is what lets the UI say "open in another tab" instead of spinning.
        when (awaitLease()) {
            LeaseState.ELSEWHERE, LeaseState.DISPLACED -> throw RuntimeElsewhereException()
            else -> Unit
        }
        hydration.await()
        fileHydration.await()
        sessionHydration.await()
        secureStorageHydration.await()
        masterKeyHydration.await()
        hlcWatermarkHydration.await()
    }

    fun isReady(): Boolean =
        hydration.isCompleted &&
                fileHydration.isCompleted &&
                sessionHydration.isCompleted &&
                secureStorageHydration.isCompleted &&
                masterKeyHydration.isCompleted &&
                hlcWatermarkHydration.isCompleted

    // Session-state store; readable after `awaitReady()`. Holds device-name
    // (written by the onboarding wrappers) and the apex's session token
    // (written by `Session.persist`).
    internal val sessionStore: IndexedDbSessionStore get() = sessionHydration.getCompleted()

    /** The TC-2 HLC watermark; readable after [awaitReady]. */
    override val hlcWatermarkStore: IndexedDbHlcWatermarkStore
        get() = hlcWatermarkHydration.getCompleted()

    // IDB-backed wrapped-master-key store (asset A1 at rest, per
    // `docs/web/architecture.md` §4.3). Available after `awaitReady()`.
    // The WebAuthn-PRF wrap path in `Session` writes it at passkey
    // registration and unwraps it on `Session.unlock`. The wrap envelope is owned by
    // [masterKeyWrapper]; this store is purely IDB CRUD over opaque blobs.
    internal val masterKeyStore: IndexedDbMasterKeyStore
        get() = masterKeyHydration.getCompleted()

    // Wrap/unwrap helper for [masterKeyStore]. Constructed off the same
    // [SyncCryptoManager] the rest of the runtime uses, so the wrap envelope
    // is its XChaCha20-Poly1305, with an HKDF stretch on non-32-byte wrap keys.
    internal val masterKeyWrapper: MasterKeyWrapper by lazy {
        MasterKeyWrapper(syncCryptoManager)
    }

    // Web-side holder for the realm's BIP-39 recovery-phrase entropy (the
    // 32-byte seed A1 + the shared keys derive from). In-memory only. It is
    // meant to track `SyncManagerImpl.currentMasterKeyPair` — set when the
    // entropy is in-hand (`Realm.create` / `Session.unlockWithPhrase` /
    // `Recovery.claim`) or recovered from the v2 wrap blob on PRF unlock, and
    // never on a joiner device (joiners receive derived keys, not the phrase).
    // ⚠⚠ It tracks it on the SETTING side only: `lock()` nulls the keypair and
    // nothing nulls this — see below. Kept here —
    // not on the cross-platform `SyncManager` — so the entropy-at-rest
    // change stays entirely web-side. `Recovery.revealPhrase` reads it to
    // reconstruct the 24-word mnemonic, and `Session.registerPasskey` wraps
    // it into the persisted blob (v2) under the WebAuthn-PRF key.
    //
    // ⚠⚠ Four setters, NO clear, and the set of setters is the contract: this
    // must track `currentMasterKeyPair` exactly. A path that seats a master
    // keypair without setting this leaves that browser's `revealPhrase`
    // resolving `unavailable`, and its next passkey registration persists a
    // keypair-only v1 blob. Adding a path that seats a master keypair means
    // adding it here too.
    //
    // ⚠⚠ **Nothing nulls this field, on any path.** It survives `Session.clear`
    // — both "Lock now" and "Disconnect this browser" — for the life of the
    // page, so a tab the UI has routed to /lock can still reconstruct the
    // 24-word phrase. A one-line null here is NOT the fix: web dispatches `LockRealm` from nowhere,
    // so A1 stays resident
    // regardless, and each tab owns a separate `JsRuntime`, so a clear in one
    // tab leaves the others holding it. `docs/tech-debt.md`, "Web's Lock now
    // does not lock the realm — A1 stays resident", has all three parts and
    // they want doing together.
    internal var currentEntropy: ByteArray? = null

    // Wipes the protocol-metadata, file-bytes, and secure-storage IDBs so
    // the next page load reads as a fresh `firstTime` AND a brand-new
    // device-ID is generated. Used by `Session.clear({purgeRealm: true})`
    // for revocation cleanup. Doesn't touch `tenshu-sdk-session` — caller
    // (`Session.clear`) wipes that separately so the device-name lookup
    // also reads empty.
    //
    // **Caller must reload the page after this returns.** `SyncManagerImpl`
    // captures `currentDeviceId` at construction and `RestSyncRelay`
    // captures the auth-token closures over the same SyncManager — none
    // of those rebind on the wipe, so live operations would still use the
    // pre-purge identity. Documented in the contract and surfaced to apex
    // through the `Session.clear` Promise: resolving the Promise means
    // "purge committed, time to reload."
    internal suspend fun purgeRealmStorage() {
        objectStore.clearAll()
        fileHydration.getCompleted().clearAll()
        secureStorageHydration.getCompleted().clearAll()
        masterKeyHydration.getCompleted().clearAll()
    }

    // Chicken-and-egg: SchemaRegistry needs a TContext provider, the
    // TContext exposes the registry. Both are lazy; the provider lambda
    // only fires when something inside the registry actually needs the
    // context, by which time tContext is fully initialised.
    // Schema configs are contributed per-plugin; the flatMap over ordered
    // plugins keeps the [common, client] × {file, notification, identity,
    // sync} order. `registerConfig` dedups + walks
    // prerequisites, so any overlap between plugins is harmless. Held as an
    // explicit `Lazy` so [augment] can tell whether the registry is already
    // live and a late plugin's configs must be folded in.
    private val schemaRegistryLazy: Lazy<SchemaRegistry> = lazy {
        createDefaultSchemaRegistry(
            tContextProvider = { tContext },
            extraConfigs = JsRuntimeBuilder.orderedPlugins.flatMap { it.schemaConfigs },
        )
    }
    val schemaRegistry: SchemaRegistry get() = schemaRegistryLazy.value

    private val traitResolver: TraitResolver by lazy { TraitResolverImpl { tContext } }

    // FileDatabase is a path-keyed byte store, separate from the protocol-
    // level Database below. Several file-service ops need it (UploadFile,
    // GetData, DeleteFile, MoveFile) — CreateFolder doesn't but the factory
    // takes it uniformly. IDB-backed so file bytes survive reloads, as the
    // protocol store's metadata does. Read via `fileHydration.getCompleted()` — `awaitReady()`
    // is the gate. Exposed as `internal` so the SDK surface (`FileSystem.getData`
    // in jsMain) can read bytes directly without dispatching the protocol-
    // level `GetData` operation: the in-process runtime IS the source of
    // truth on this device, the multi-device/multi-process round-trip is
    // a higher-layer concern.
    override val fileDatabase: FileDatabase get() = fileHydration.getCompleted()

    // SecureStorage holds device-scoped material that must outlive a single
    // SyncManager instance — most importantly the Ed25519 device-ID, which
    // the relay keys all subsequent JWTs against. Backed by IndexedDB
    // (`tenshu-sdk-secure`) so the device ID survives reload; without
    // persistence the relay would treat every reload as a brand-new
    // unauthenticated device and sync would break. Lives in its own IDB database
    // separate from the protocol store, file bytes, and session record so
    // each can be wiped independently. Available after `awaitReady()`.
    private val secureStorage: SecureStorageDelegate
        get() = secureStorageHydration.getCompleted()

    // The PRF-wrap producer/unwrap (`Session.registerPasskey` /
    // `Session.unlock`) needs to read `currentOwnerId` + `currentMasterKeyPair`
    // and call `reseatMasterKeyPair` on this SyncManager. Exposed `internal`
    // so the cross-package SDK surface in `app.tenshu.sdk.Session` can
    // reach it without going through the operation chain (this is local
    // device state, not a sync-protocol op).
    override val syncManager: SyncManager by lazy {
        SyncManagerImpl(
            cryptoManager = syncCryptoManager,
            storageDelegate = secureStorage,
            context = tContext,
            // ⚠⚠ The `Database`'s own store and lock, as every `PlatformProxyModule` passes them.
            // The pull/tombstone/backfill cursors and the first-pull marker are rows IN this
            // store (A1, `sync-cursor-store-lifecycle.md`). Left to its default, the manager keeps
            // them in a private in-memory store: every reload re-pulls from 0 and re-walks the
            // backfill, and `wipeLocalStore` drops that throwaway store, not this one.
            // Safe on JS: the IDB store is fully hydrated before this lazy can run, so its
            // synchronous reads are cache hits, never read-through.
            objectStore = objectStore,
            storeLock = storeLock,
        ).also { manager ->
            // ⚠ No Web Locks ⇒ no exclusivity ⇒ a durable cursor could sit ahead of rows another
            // tab overwrote. The conservative fallback is to refuse to persist the cursor across
            // loads: reset it at boot, and the re-pull from 0 heals it.
            if (refusesDurableCursor(lease.getCompleted())) manager.resetPullProgress()
        }
    }

    // Mergers are contributed per-plugin (files owns `FolderMerger`); sync
    // consumes the registry in its init. Explicit `Lazy` so [augment] can fold
    // a late plugin's mergers into an already-built registry.
    private val mergerRegistryLazy: Lazy<SemanticMergerRegistry> = lazy {
        SemanticMergerRegistry().apply {
            JsRuntimeBuilder.orderedPlugins.forEach { plugin ->
                plugin.mergers.forEach { register(it) }
            }
        }
    }
    override val mergerRegistry: SemanticMergerRegistry get() = mergerRegistryLazy.value

    override val tombstoneEmitterHolder: TombstoneEmitterHolder by lazy {
        TombstoneEmitterHolder()
    }

    // Populated by the sync plugin's `initialize` (FirstPartyPlugins) with
    // `SyncProxyComponents.scheduler`, mirroring `tombstoneEmitterHolder`.
    // `Devices.revokeSelf` reads it to force a drain (`drainNow()`) so the
    // device-revocation tombstone is pushed to the relay *before* the apex
    // wipes local state — the load-bearing ordering for web self-revoke.
    override val syncSchedulerHolder: SyncSchedulerHolder by lazy {
        SyncSchedulerHolder()
    }

    // Storage-service wiring (web-storage-status.md Bucket 2). The provider
    // feeds `StorageStatus`'s figures; the holder is the Tier-2 enforcement
    // latch read by `storageServiceProxyImplementationFactory` (`CheckCapacity`).
    // The storage plugin's `initialize` collects [storageProvider] into both via
    // `initStorageService`, and runs `initEntitlementStorageReader` so the figures
    // track the synced `Entitlement` — a web device that joins a funded realm
    // reflects the subscription with no further wiring.
    override val storageProvider: StorageProvider by lazy { EntitlementStorageProvider() }
    override val storageCapacityHolder: StorageCapacityHolder by lazy {
        StorageCapacityHolder()
    }

    // Contact-service dependency bundle (drive-web-mode-b-sharing.md MB-1).
    // Mirrors the native wiring in `:control-shared`'s PlatformProxyModule: a
    // `RestInviteRelayClient` over the same `authTokenProvider` device-key JWT
    // seam the sync relay uses, the A13 identity-key seam resolved lazily
    // through `syncManager` (so `:contact-service-proxy` stays sync-agnostic),
    // and the IndexedDB-backed redemption store. `lazy` so a file-only session
    // never constructs it; building it dials nothing (`HttpClient(Js)` defers to
    // the browser's `fetch`).
    override val contactProxyComponents: ContactProxyComponents by lazy {
        ContactProxyComponents(
            relayClient = RestInviteRelayClient(
                baseUrl = configuredRelayBaseUrl,
                httpClient = httpClient,
                authTokenProvider = authTokenProvider,
            ),
            cryptoManager = syncCryptoManager,
            identityKeyPair = { syncManager.getRealmEncryptionKeyPair() },
            redemptionStore = PendingRedemptionStore(secureStorage),
            // `contacts-app.md` §9.10: a card's big PHOTO lines on the content plane.
            // ⚠ Read at first dispatch, after hydration — `fileDatabase` throws before it.
            fileDatabase = fileDatabase,
            syncBlockEmitterHolder = syncBlockEmitterHolder,
            tombstoneEmitterHolder = tombstoneEmitterHolder,
        )
    }

    // Shared-realm membership grant runtime (drive-web-mode-b-sharing.md MB-2).
    // Populated by the sync plugin's `initialize`; read by its `operationFactory`
    // at dispatch time (same lazy-holder shape as `syncSchedulerHolder`).
    override val realmRegistryHolder: RealmRegistryHolder by lazy { RealmRegistryHolder() }

    override val nowMs: () -> Long = { systemClock.nowEpochMillis() }

    // `perRealmDeviceRelay`: a granted (cross-user) realm's relay JWT is signed
    // with that member's fresh per-realm device key (D2 unlinkability), not the
    // personal A4. Built per dispatch from the realm context; construction dials
    // nothing (Ktor `HttpClient(Js)` defers to `fetch`).
    override val grantedRelayFactory: (RealmContext) -> SyncRelay? = { realm ->
        perRealmDeviceRelay(
            baseUrl = configuredRelayBaseUrl,
            httpClient = httpClient,
            context = tContext,
            cryptoManager = syncCryptoManager,
            realm = realm,
            nowEpochSec = { systemClock.nowEpochMillis() / 1000 },
        )
    }

    // An A4-signed per-shared-realm relay (JWT scoped to the realm's `ownerId`),
    // the registry's `relayFactory` — so a member's accept writes a `Member` row
    // that actually pushes back to the owner's realm.
    override val sharedRealmRelayFactory: (String) -> SyncRelay? by lazy {
        org.tenshu.sync.proxy.sharedRealmRelayFactory(
            baseUrl = configuredRelayBaseUrl,
            httpClient = httpClient,
            context = tContext,
            syncManager = syncManager,
            cryptoManager = syncCryptoManager,
            nowEpochSec = { systemClock.nowEpochMillis() / 1000 },
        )
    }

    // MB-3 content plane: the personal processor (for personal OnDemand fetches)
    // + the per-shared-realm content store. Populated/used by the sync plugin's
    // initialize + the OnDemand resolver below.
    override val syncProcessorHolder: SyncProcessorHolder by lazy { SyncProcessorHolder() }

    // MB-4: the file-content emit holder the file factory reads + the sync plugin
    // populates (mirrors `tombstoneEmitterHolder` / `syncSchedulerHolder`).
    override val syncBlockEmitterHolder: SyncBlockEmitterHolder by lazy { SyncBlockEmitterHolder() }

    override val sharedRealmContentBlobStoreFactory: (String) -> ContentBlobStore? by lazy {
        org.tenshu.sync.proxy.sharedRealmContentBlobStoreFactory(
            baseUrl = configuredRelayBaseUrl,
            httpClient = httpClient,
            context = tContext,
            syncManager = syncManager,
            cryptoManager = syncCryptoManager,
            nowEpochSec = { systemClock.nowEpochMillis() / 1000 },
        )
    }

    // OnDemand byte fetch for `readSharedFile` (Mode-B delivers content lazily on
    // open). Routes a shared-realm read to that realm's runtime (relay + key-aware
    // processor) via the registry holder. Registered into [tContext] through an
    // eager forwarding wrapper whose real delegate builds lazily — the impl reads
    // `syncRelay`, which itself reads `tContext`, so constructing it eagerly inside
    // the `tContext` build would recurse. The wrapper breaks that cycle: the
    // delegate materialises on the first `resolve()`, after `tContext` is built.
    // The proxy-side half of the wanted-classes declaration (`drive-synced-thumbnails.md`
    // §15.2): the pull IS the declaration. Mirrors the Android and desktop
    // `single { … }` wirings in `control-shared-proxy`'s PlatformProxyModule.
    private val wantedThumbnailClasses: org.tenshu.file.proxy.WantedThumbnailClasses by lazy {
        org.tenshu.file.proxy.WantedThumbnailClasses { syncManager.currentDeviceId }
    }

    private val onDemandResolutionDelegate: OnDemandResolutionImpl by lazy {
        OnDemandResolutionImpl(
            relay = syncRelay,
            syncProcessorHolder = syncProcessorHolder,
            realmRegistryHolder = realmRegistryHolder,
            // ⚠⚠ Real only because `PreferencePlugin` registers a preference plane: `declare`
            // swallows every `Throwable`, so without one this fails silently for ever while
            // looking wired (`drive-xxlarge-thumbnail-rung.md` §9.7). Omitting it means a browser
            // never declares a class it pulled, the gap the Android and desktop wirings call out.
            onPull = { executionContext, _, propertyId ->
                wantedThumbnailClasses.notePull(executionContext, propertyId)
            },
        )
    }
    // ⚠⚠ Forward EVERY method, not just `resolve`. `OnDemandResolution` gives its three streaming
    // methods permissive defaults (`null`, `null`, `false`) so that non-streaming resolvers and
    // test fakes keep compiling — so a production wrapper that overrides only `resolve` silently
    // becomes one of those fakes. The delegate implements all three.
    //
    // Unforwarded, `openContentStream` answers null for every file: the op throws "Content stream
    // unavailable — file not synced yet or realm locked", the streaming download falls back to a
    // whole-file read on every download, and the Service Worker's `<video>` Range path has nothing
    // behind it.
    //
    // ⚠ Wrapping a shared component and dropping what the wrapper forgets to forward is the
    // recurring web wiring failure (`contentBlobStore`, `GetPreview` and `File.Preview` too). A
    // default that answers "nothing" is indistinguishable from an unwired seam.
    private val onDemandResolution: OnDemandResolution = object : OnDemandResolution {
        override suspend fun resolve(
            executionContext: ExecutionContext,
            entityId: org.tenshu.core.entity.DataId,
            propertyId: String,
            params: org.tenshu.core.collection.list.TList<org.tenshu.core.Data>?,
        ) = onDemandResolutionDelegate.resolve(executionContext, entityId, propertyId, params)

        override suspend fun openContentStream(
            executionContext: ExecutionContext,
            entityId: org.tenshu.core.entity.DataId,
            propertyId: String,
        ) = onDemandResolutionDelegate.openContentStream(executionContext, entityId, propertyId)

        override suspend fun readContentRange(handle: String, offset: Long, length: Int) =
            onDemandResolutionDelegate.readContentRange(handle, offset, length)

        override fun closeContentStream(handle: String) =
            onDemandResolutionDelegate.closeContentStream(handle)
    }

    // ⚠⚠ Without this the content plane is unreachable, however well it is wired. A
    // content-addressed file's manifest is keyed by `contentId` on the content plane, NOT in the
    // entity's mailbox blocks, and `OnDemandResolutionImpl` reaches it only via
    // `externalPropertyWriter()?.resolveContentId(...)`. Unregistered, that reads null and every
    // resolution takes the block-fetch fallback, which for such a file finds no manifest: block
    // fetches, zero chunk requests, and the bytes sit unread on the relay.
    //
    // Fans out exactly like native's `DelegatingExternalPropertyWriter` (`PlatformProxyModule`, all
    // three platforms): file bytes + workout stream blobs. Forwarding wrapper — see
    // [onDemandResolution] for why the delegate is lazy.
    //
    // ⚠⚠ The workout writer is as load-bearing as the file writer (`HealthPlugin` registers the
    // web's health service).
    // Without it `Workout.Streams` breaks in both directions with no error: `resolveContentId`
    // answers null, so a phone's content-plane streams are looked up on the entity-keyed branch
    // (no manifest there), and both landing paths (`FileContentManifestApplier`,
    // `SyncProcessor.attachPropertyAndSave`) refuse a property no writer `supports` — the web
    // detail never shows a phone's route or HR. Outbound, a web import's upload is journalled and
    // published entity-keyed although its row names a `contentId`. `WorkoutStreamsOnWebTest` pins
    // both. It is a pure function of the row: it
    // installs no observer and starts no work.
    private val propertyWriterDelegate: ExternalPropertyWriter by lazy {
        DelegatingExternalPropertyWriter(
            FileBytesPropertyWriter(fileDatabase = fileDatabase),
            WorkoutStreamsPropertyWriter(fileDatabase = fileDatabase),
            // Contacts (`contacts-app.md` §9.10): a card's relocated PHOTO lines.
            ContactPhotosPropertyWriter(fileDatabase = fileDatabase),
        )
    }
    private val externalPropertyWriter: ExternalPropertyWriter = object : ExternalPropertyWriter {
        override fun supports(propertyId: String): Boolean =
            propertyWriterDelegate.supports(propertyId)

        override fun write(
            executionContext: ExecutionContext,
            targetEntityId: org.tenshu.core.entity.DataId,
            propertyId: String,
            value: org.tenshu.core.Data,
        ) = propertyWriterDelegate.write(executionContext, targetEntityId, propertyId, value)

        override fun resolveExternalPath(
            executionContext: ExecutionContext,
            targetEntityId: org.tenshu.core.entity.DataId,
            propertyId: String,
        ): String? =
            propertyWriterDelegate.resolveExternalPath(executionContext, targetEntityId, propertyId)

        override fun resolveContentId(
            executionContext: ExecutionContext,
            targetEntityId: org.tenshu.core.entity.DataId,
            propertyId: String,
        ): String? =
            propertyWriterDelegate.resolveContentId(executionContext, targetEntityId, propertyId)

        override fun resolveContentHash(
            executionContext: ExecutionContext,
            targetEntityId: org.tenshu.core.entity.DataId,
            propertyId: String,
        ): String? =
            propertyWriterDelegate.resolveContentHash(executionContext, targetEntityId, propertyId)
    }

    // web-storage-status.md Bucket 3: the in-process dev-key minter, used by
    // `Realm.create` to mint a default-free entitlement on web genesis (no
    // commerce service stands behind `drive.tenshu.app` — same posture as
    // Control under `TENSHU_BILLING_MOCK`). Signs the canonical entitlement
    // message with the committed dev seed; peers verify against the dev publisher
    // key on merge.
    internal val entitlementMintClient: EntitlementMintClient by lazy {
        DevSeedEntitlementMintClient(
            sign = { msg -> syncCryptoManager.signEd25519(EntitlementSignature.DEV_SEED, msg) },
            now = { systemClock.nowEpochMillis() },
        )
    }

    // Single shared HTTP client. The JS engine reuses the browser's `fetch`,
    // so connection pooling is the browser's job; we just need ContentNegotiation
    // for the JSON request/response bodies the relay expects.
    // ⚠⚠ TransportFailureMapping: a refused `fetch` must reach the relay clients as an
    // `Exception`, or it escapes their catches and fails ops that already saved.
    private val httpClient: HttpClient by lazy {
        HttpClient(Js) {
            install(ContentNegotiation) { json() }
            install(TransportFailureMapping)
        }
    }

    // Per `sync-backend-relay-plan.md` §4.1, every relay request carries a
    // short-lived JWT signed by the device's Ed25519 key. Returns null when
    // the device isn't yet associated with a realm — the relay rejects
    // unauthenticated state-changing requests in production mode. Mirrors
    // the production wiring in `:control-shared`'s PlatformProxyModule.kt;
    // only the clock source differs (Clock.nowEpochMillis() instead of
    // System.currentTimeMillis(), since the latter doesn't exist on JS).
    private val authTokenProvider: suspend () -> String? by lazy {
        jwtTokenProvider(
            ownerIdProvider = { syncManager.currentOwnerId },
            deviceIdProvider = { syncManager.currentDeviceId },
            signer = { msg ->
                syncCryptoManager.signEd25519(
                    privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                    message = msg
                )
            },
            nowEpochSec = { systemClock.nowEpochMillis() / 1000 }
        )
    }

    // retention-quota-model.md §5 / D5: the storage-usage reader — a best-effort
    // GET /sync/usage over the same device-key JWT + shared HttpClient. Returns the
    // owner's real stored bytes (or null on any failure), feeding the StorageStatus gauge.
    override val relayUsageReader: (suspend () -> Pair<Long, Long?>?)? by lazy {
        val client = RelayUsageClient(
            baseUrl = configuredRelayBaseUrl,
            httpClient = httpClient,
            authTokenProvider = authTokenProvider,
        )
        // ⚠ No entitlement credential is presented here: the web runtime has no commerce session,
        // so the relay reports usage only and the limit stays null — rendered as unknown, exactly
        // as on a client that has never been online.
        //
        // ⚠⚠ That is NOT "no local record store": the storage plugin runs
        // `initEntitlementStorageReader` against the synced `Entitlement`, and the *sync relay*
        // must present it (`entitlementProofProvider`, [syncRelayLazy]) or every web write is
        // `402 entitlement_required`. `GET /sync/usage` is a read and needs no entitlement, so
        // the omission is correct HERE only.
        ({ client.fetchUsage()?.let { it.usedBytes to it.limitBytes } })
    }

    // The web's only possible write credential. See [RuntimeContext] for why a
    // browser can never present the bearer-token form.
    override val entitlementCredentialProvider: EntitlementRecordCredentialProvider by lazy {
        EntitlementRecordCredentialProvider()
    }

    private val syncRelayLazy: Lazy<SyncRelay> = lazy {
        RestSyncRelay(
            baseUrl = configuredRelayBaseUrl,
            httpClient = httpClient,
            context = tContext,
            authTokenProvider = authTokenProvider,
            // No dev-owner fallback: a null owner means no realm binding yet, so the
            // JWT provider mints no token (unauthenticated) rather than impersonating
            // a placeholder owner the relay rejects.
            ownerIdProvider = { syncManager.currentOwnerId },
            // The signed-record proof (`X-Entitlement-Proof`). No `entitlementTokenProvider`
            // beside it, deliberately: the web has no commerce session to mint one from, so
            // the record path is not a fallback here — it is the whole credential story.
            // Returns null until the owner's `Entitlement` has synced and derived, which the
            // relay answers as `402 entitlement_required`; the reader's `recordSink` then fires
            // `onRecordUsable` below and the stranded writes re-drive.
            entitlementProofProvider = {
                entitlementCredentialProvider.proofHeader(systemClock.nowEpochMillis())
            },
        )
    }
    override val syncRelay: SyncRelay get() = syncRelayLazy.value

    // The personal realm's content plane (`drive-web-parity.md` W1). Same
    // `RestContentBlobStore` the shared-realm factory above mints, but bound to the
    // *personal* owner — so it needs no per-realm keying and reuses this runtime's own
    // [authTokenProvider] rather than a realm-scoped one.
    //
    // ⚠⚠ The entitlement proof is not optional here. `PUT /sync/content/{ref}` is
    // write-gated exactly like `POST /sync/push` (`ContentRouting`'s `resolveEntitlement`),
    // so omitting it gives the content plane a silent 402 — bytes that upload, fail, and say
    // nothing. This presents
    // the same signed-record credential the sync relay does, from the same provider, so
    // both planes strand and re-drive together when the record lands.
    //
    // ⚠ No `entitlementTokenProvider`, for the reason given on [syncRelayLazy]: a browser
    // has no commerce session, so the record proof is the whole credential story.
    private val contentBlobStoreLazy: Lazy<ContentBlobStore> = lazy {
        RestContentBlobStore(
            baseUrl = configuredRelayBaseUrl,
            httpClient = httpClient,
            context = tContext,
            authTokenProvider = authTokenProvider,
            entitlementProofProvider = {
                entitlementCredentialProvider.proofHeader(systemClock.nowEpochMillis())
            },
        )
    }
    override val contentBlobStore: ContentBlobStore get() = contentBlobStoreLazy.value

    // Per-dispatch lookup over the composed plugin factory chain
    // (`web-sdk-bundle-splitting.md` §6.2). The core `implementationFactory` is tried first, then each
    // plugin's factory in registration order; the first non-null wins. Reified
    // as a `MutableList` (not a fixed elvis chain) so [augment] can append a
    // late plugin's factory and the next dispatch sees it.
    //
    // Cost property: each plugin's `operationFactory`
    // returns a lambda that reads relay/manager state from the [RuntimeContext]
    // only when actually invoked, and sync is ordered last — so file /
    // notification ops short-circuit before the sync arm runs, and the relay's
    // lazy stays unfired until a real sync op (CreateRealm, InitiatePairing, …)
    // dispatches. Ordering otherwise doesn't affect behaviour (op-id namespaces
    // are disjoint across services).
    private val factoryChainLazy: Lazy<MutableList<OperationImplementationFactory>> = lazy {
        JsRuntimeBuilder.orderedPlugins.mapNotNullTo(mutableListOf()) {
            it.operationFactory(this)
        }
    }

    /**
     * ⚠ TEST-ONLY, empty in every production runtime: implementations consulted before core's, so a
     * jsTest can wrap a core op in a counting delegate — the `TestProxy.additionalImplementations`
     * recipe `FilteredWindowFusionTest` uses, which is the only way to tell a fused read from an
     * unfused one (the rows are identical either way). Nothing in `jsMain` writes it.
     */
    internal val operationOverridesForTest: MutableMap<String, OperationImplementation> = mutableMapOf()

    private val operationImplementationFactory: OperationImplementationFactory =
        OperationImplementationFactory { id ->
            operationOverridesForTest[id]
                ?: implementationFactory(id)
                ?: factoryChainLazy.value.firstNotNullOfOrNull { it(id) }
        }

    private val pureOperationRunner: PureOperationRunner by lazy {
        PureOperationRunnerImplementation(
            tContextProvider = { tContext },
            implementationFactoryProvider = { operationImplementationFactory },
        )
    }

    // The interactor provider is constructed eagerly (its only ctor work is
    // capturing the taskExecutor lambda + a mutex), so it can be referenced
    // from `tContext.components` without forcing the executor chain.
    private val defaultInteractorProvider: DefaultInteractorProvider =
        DefaultInteractorProvider { taskExecutor }

    val tContext: TContext by lazy {
        object : TContext {
            override val components: Map<String, TComponent> = buildMap {
                put(DataFactory.COMPONENT_ID, schemaRegistry)
                put(SchemaProvider.COMPONENT_ID, schemaRegistry)
                put(RoProvider.COMPONENT_ID, schemaRegistry)
                put(OperationResolver.COMPONENT_ID, schemaRegistry)
                put(LazyDataMaterializer.ID, schemaRegistry)
                put(TraitResolver.ID, traitResolver)
                put(PureOperationRunner.ID, pureOperationRunner)
                put(SyncCryptoManager.ID, syncCryptoManager)
                put(Clock.COMPONENT_ID, systemClock)
                put(InteractorProvider.COMPONENT_ID, defaultInteractorProvider)
                // MB-3: `readSharedFile` looks this up to fetch a shared file's
                // bytes on open (forwarding wrapper — see [onDemandResolution]).
                put(OnDemandResolution.ID, onDemandResolution)
                // The `contentId` lookup an OnDemand byte fetch needs before it can
                // reach the content plane at all (`drive-web-parity.md` W1).
                put(ExternalPropertyWriter.ID, externalPropertyWriter)
            }
        }
    }

    /**
     * The protocol store, after [awaitReady]. One instance shared by [database] and [syncManager],
     * so the sync cursors share the applied data's lifecycle (A1).
     */
    internal val objectStore: IndexedDbObjectStore get() = hydration.getCompleted()

    // JS is single-threaded; `withLock` bodies cannot be interleaved by the dispatcher, so a
    // no-op lock is sufficient. ONE instance, shared by [database] and [syncManager], so the
    // two cannot drift apart if this ever stops being a no-op.
    private val storeLock: DatabaseLock = NoOpDatabaseLock()

    val database: Database by lazy {
        DatabaseImpl(
            context = tContext,
            objectStore = objectStore,
            dbEntryMapper = DbEntryMapper(
                context = tContext,
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver,
            ),
            traitResolver = traitResolver,
            lock = storeLock,
        )
    }

    private val materializer: CallstackMaterializer by lazy {
        CallstackMaterializer(schemaRegistry, tContext)
    }

    // Mirrors `platformProxyInitialization` in `:control-shared`'s
    // PlatformProxyModule.kt, but driven per-plugin. Each plugin's `initialize`
    // runs inside ClientRegistry's `initialization` hook in plugin order
    // (files → notifications no-op → identity → sync): files creates the `/`
    // root entity, identity does its setup, sync spins up the SyncProcessor /
    // SyncObserver / SyncScheduler chain and publishes the tombstone emitter
    // back into the shared holder. `this` is the registry-provided
    // ExecutionContext (the receiver `initFileService()` runs against); `JsRuntime` is the
    // RuntimeContext sync init pulls its deps from.
    // Explicit `Lazy` so [augment] can run a late plugin's init against the
    // already-built context.
    private val clientRegistryLazy: Lazy<ClientRegistry> = lazy {
        ClientRegistry(
            database = database,
            traitResolver = traitResolver,
            implementationFactory = operationImplementationFactory,
            clientConnectionFactory = { _ -> LocalClientConnection() },
            materializer = materializer,
            pureOperationRunner = pureOperationRunner,
            baseContext = tContext,
            serviceScope = serviceScope,
            initialization = {
                JsRuntimeBuilder.orderedPlugins.forEach { plugin ->
                    plugin.initialize(this, this@JsRuntime)
                }
            },
        )
    }
    val clientRegistry: ClientRegistry get() = clientRegistryLazy.value

    val executionContext: ExecutionContext by lazy {
        clientRegistry.getExecutionContext(DEFAULT_CLIENT_ID)
    }

    // Bridges the per-session LocalClientConnection's stream-element output
    // into the same DefaultInteractorProvider that DSL-side callers ask for
    // interactors. Mirrors the AsyncTaskExecutor wiring in
    // :control-shared/.../di/ProxyModule.kt.
    val taskExecutor: AsyncTaskExecutor by lazy {
        val context = executionContext
        val connection = context.clientConnection
        if (connection is LocalClientConnection) {
            // In wire order, on one consumer — see `forwardStreamElementsInOrder`.
            connection.forwardStreamElementsInOrder(serviceScope, defaultInteractorProvider)
        }
        LocalTaskExecutor(context)
    }

    val tenshuEnvironment: TenshuEnvironment by lazy {
        TenshuEnvironment(
            taskExecutor = taskExecutor,
            schemaRegistry = schemaRegistry,
            tContext = tContext,
            requestChainFactory = { ctx -> LocalRequestChain(taskExecutor, ctx) },
        )
    }

    val tenshuClient: TenshuClient by lazy {
        DefaultTenshuClient(tenshuEnvironment).also { client ->
            // Schema announce (core-schema-evolution.md §6.6): the in-process
            // JS runtime announces too — one code path, frozen envelope
            // exercised everywhere. Not gated (live objects), fire-and-forget.
            val announcer = SchemaAnnouncer(
                appId = "app.tenshu.web",
                tContext = tContext,
                schemaProvider = schemaRegistry,
                clientProvider = { client },
            )
            // The app-side half of the wanted-classes declaration (`drive-synced-thumbnails.md`
            // §15.2, `drive-xxlarge-thumbnail-rung.md` §4.8): tell the realm which large classes
            // this browser draws, so a native device's
            // backfill mints them for files uploaded before those classes existed.
            //
            // ⚠⚠ The row is a `DeviceSetting`, so this needs `PreferencePlugin`'s preference
            // service (`drive-xxlarge-thumbnail-rung.md` §9.7). ⚠ It only helps where a *native*
            // device runs the backfill; nothing on web
            // registers that schedule, which is why the lightbox keeps its original-fallback.
            // ⚠ `xxlarge` and `xlarge` only: `motion` is declarable but web has no playback
            // surface, and LIST/LARGE are implicit everywhere (`ThumbnailPreferences.declarable`).
            // ⚠⚠ `runCatching` around the CALL, not just inside it. `client.preferenceService` is
            // `getService(PreferenceService.ID)`, which **throws for an unregistered service**, and
            // it is evaluated as an argument — outside the helper's own try/catch. Unguarded, a
            // piece of launch-time bookkeeping could take down client construction if
            // `PreferencePlugin` ever stopped registering. A declaration must never be able to do
            // that; the proxy's pull-path half still runs.
            runCatching {
            serviceScope.declareWantedThumbnailClassesAtLaunch(
                preferences = client.preferenceService,
                // ⚠ The runtime's own `SyncManager`, not a client read: it is the same id the
                // proxy-side `WantedThumbnailClasses` uses, and it needs no session to answer.
                deviceId = { syncManager.currentDeviceId?.takeIf { it.isNotBlank() } },
                // Explicitly open (ADR 0025 §8.6): the web runtime IS the realm's own runtime (apex
                // is Control in the browser, §Decision 5) — there is no separate Control to wait on.
                awaitWritable = {},
                beforeWrite = {},
                retryAfter = { false },
            ) {
                setOf(GetStoredThumbnail.CLASS_XXLARGE, GetStoredThumbnail.CLASS_XLARGE)
            }
            }.onFailure {
                // ⚠⚠ Caught, then SAID. Discarding the `Result` would make a missing preference
                // plane look exactly like a working one (§9.7: `declare` swallows every
                // Throwable, so `notePull` without a preference plane is a permanent silent no-op
                // that looks wired). The `Job` is dropped on purpose; the exception is not.
                app.tenshu.diagnostics.Diagnostics.e(
                    "WantedThumbnailClasses",
                    "launch declaration skipped: ${it.message ?: it::class.simpleName}",
                )
            }
            serviceScope.launch { try {
                    announcer.announce()
                } catch (e: Throwable) {
                    // Best-effort §6.6 hygiene: in-process calls are not gated, and
                    // a partially-wired graph (test fixtures) must never leak an
                    // uncaught exception onto the global handler.
                    app.tenshu.diagnostics.Diagnostics.e(
                        "SchemaAnnounce", "in-process announce failed: ${e.message}",
                    )
                } }
        }
    }

    val clock: Clock get() = tContext.clock()

    // Folds a plugin registered *after* the runtime is already live into the
    // running graph (`web-sdk-bundle-splitting.md` §4.3). For each facet, if it has already
    // materialized the plugin's contribution is added now; otherwise it's a
    // no-op because the plugin is already in `JsRuntimeBuilder.orderedPlugins`
    // and will be picked up when that facet first builds.
    //
    // Every first-party plugin is seeded before first runtime use, so the
    // apex flows don't exercise this path — it's the seam per-service
    // dynamic-import registration (`web-sdk-bundle-splitting.md` §4.3) relies on. `registerConfig` is
    // idempotent, so re-folding a plugin whose configs overlap is safe.
    private fun augment(plugin: JsRuntimePlugin) {
        if (schemaRegistryLazy.isInitialized()) {
            plugin.schemaConfigs.forEach { schemaRegistry.registerConfig(it) }
            // A plugin folded in AFTER the store opened contributes services the
            // open-time gate never saw, so its versions are missing from the
            // recorded vector. Record them now, or a later reshape of one
            // of them would be invisible to the next open's drift check.
            //
            // ⚠⚠ New entries ONLY — `recordNewServiceVersions` never overwrites a
            // recorded version. A late plugin whose service is already recorded at
            // an OLDER version is real drift that this open missed; stamping current
            // over it would hide it forever, while leaving it stale lets the next
            // open catch it. Fail towards detecting later, never towards silence.
            serviceScope.launch {
                runCatching {
                    hydration.await().recordNewServiceVersions(
                        schemaRegistry.serviceSchemas().associate { it.id.get() to it.version },
                    )
                }
            }
        }
        if (mergerRegistryLazy.isInitialized()) {
            plugin.mergers.forEach { mergerRegistry.register(it) }
        }
        if (factoryChainLazy.isInitialized()) {
            plugin.operationFactory(this)?.let { factoryChainLazy.value += it }
        }
        if (clientRegistryLazy.isInitialized()) {
            plugin.initialize(executionContext, this)
        }
    }

    // Installed last so all `Lazy` delegates above are constructed (but not
    // evaluated) before the hook can fire. Triggers `JsRuntimeBuilder`'s eager
    // first-party seed — those register() calls run with the hook still null
    // (set only once this returns), so they're picked up at first build rather
    // than augmented.
    init {
        JsRuntimeBuilder.installAugmentHook(::augment)
    }
}

/**
 * Whether the sync cursors must be reset at boot under this lease: only without Web Locks,
 * where nothing stops a second tab's runtime and a durable cursor could sit ahead of rows it
 * overwrote. `JsRuntime.syncManager` calls this; `RuntimeLeaseTest` pins it.
 */
internal fun refusesDurableCursor(lease: LeaseState): Boolean = lease == LeaseState.UNGUARDED
