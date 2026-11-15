@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi::class, kotlin.experimental.ExperimentalObjCRefinement::class)

package org.tenshu.sdk.ios

import org.tenshu.sync.proxy.SyncHealthHolder
import org.tenshu.sync.proxy.connectivity
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import app.tenshu.control.di.buildPlatformProxyModule
import app.tenshu.control.di.ProxyGraphJob
import app.tenshu.control.di.buildProxyModule
import app.tenshu.control.di.closeProxyGraph
import app.tenshu.control.di.proxyGraphJob
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.delegate.IosSecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.FileUploader
import org.tenshu.file.proxy.ios.IosUploadSourceReader
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.security.cryptoBackendsModule
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.sync.proxy.SyncRuntimeHolder

/**
 * The **app-agnostic** route-1 in-process runtime substrate (`ios-first-party-route-1-migration.md`
 * P1). Everything about running the production proxy graph *in the app's own process* over the
 * shared App-Group replica — assembly over `buildProxyModule()`/`buildPlatformProxyModule()`, the
 * per-app last-wins overrides (client id + Keychain bucket), adopting Control's handed-off realm
 * identity via [RealmKeyMaterialBridge] (the joiner path — never the master key A1), the shared-
 * replica rehydrate, and the deprovision/custody-purge lifecycle — that does **not** depend on
 * *which* app it is.
 *
 * Each first-party app's `*LocalRuntime` (`DriveLocalRuntime`, `FitLocalRuntime`, …) is a thin
 * consumer configured with its own [Config] and the repo set it installs on its surface: the
 * generic glue lives here, the per-app wiring (repo construction, surface install,
 * background-refresh specifics, debug probes) stays in each app's object.
 *
 * ⚠ [isProvisioned] reads the ONE shared [RealmKeyMaterial] blob — realm-level keys, not app-scoped:
 * this substrate hands each app the whole realm identity, because on iOS first-party apps are realm
 * co-owners (Decision C, threat model A4; `ios-first-party-route-1-migration.md` §4).
 */
class FirstPartyLocalRuntime(private val config: Config) {

    /** Per-app identity for the in-process runtime. */
    class Config(
        /** The app's client id — the `LocalClientConnection` key (`platformPackageName`). */
        val clientId: String,
        /** The app's own per-app Keychain bucket (never Control's; custody purge hits the same items). */
        val secureStorageService: String,
    )

    /**
     * The process's binding of the SQLite shared replica. ⚠⚠ Every production assembly makes it:
     * there is no plist switch and no relay guard (B.22). A test seam.
     */
    internal var sharedReplicaSqliteBinding: () -> SharedReplicaBinding = { SharedReplicaBinding.process(config.clientId) }

    /** Whether Control's bridge is still gone, asked before a deferred custody purge (B.13 (178)). A test seam. */
    internal var stillDeprovisioned: () -> Boolean = { !isProvisioned() }

    /**
     * §6 step 4's shipping-gate probe before a rebuild (B.16 (219)): Control's bridge still there.
     * Bridge gone: no rebuild; the app's own gate (`RootView.recheck`) deprovisions. A test seam.
     */
    internal var rebuildProbe: () -> Boolean = { isProvisioned() }

    /** The app's reinstall ([rebuildWith]), or null while the app has registered none. */
    @kotlin.concurrent.Volatile
    private var reinstall: (() -> Unit)? = null

    /**
     * ⚠⚠ **How the app rebuilds its graph** (Appendix B.16): its `installed` dropped and
     * its `install()` run again, on the main thread, which is the re-provision path every app
     * already has (`deprovision` then `install` in one process). The host of §6 calls it at the
     * foreground after a fence, or at a protected entry after a not-ready open. Registered by each
     * app's `install()`; only a production assembly (never a test's replica directory) calls it.
     */
    fun rebuildWith(reinstall: () -> Unit) {
        this.reinstall = reinstall
    }

    /**
     * What a graph's teardown runs, beyond its loops and `SyncManager.lock()` (B.16 (228)): the
     * app's own components bound to that graph (its scopes, a capture, a backup loop). One list per
     * assembled graph; [onCurrentGraphStop] adds to the latest one's.
     */
    internal class GraphStops {
        private val lock = platform.Foundation.NSRecursiveLock()
        private val blocks = ArrayList<() -> Unit>()
        private var stopped = false

        /**
         * ⚠ A block added to a graph that is **already stopped** runs
         * at once: a component registered late is never left running over a stopped graph.
         */
        fun add(block: () -> Unit) {
            val runNow = run {
                lock.lock()
                try {
                    if (!stopped) blocks += block
                    stopped
                } finally {
                    lock.unlock()
                }
            }
            if (runNow) runGuarded(block)
        }

        /** Each block once, in order; a throw is logged and the rest still run. Later adds run at once. */
        fun runAll() {
            val taken = run {
                lock.lock()
                try {
                    stopped = true
                    blocks.toList().also { blocks.clear() }
                } finally {
                    lock.unlock()
                }
            }
            taken.forEach(::runGuarded)
        }

        private fun runGuarded(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                println("[replica-host] a graph component's stop threw: ${t.message}")
            }
        }
    }

    @kotlin.concurrent.Volatile
    private var currentStops: GraphStops? = null

    @kotlin.concurrent.Volatile
    private var currentAbandons: GraphStops? = null

    /** B.17 (263): what the graph just assembled runs at each foreground while it serves. */
    private class GraphForegrounds {
        private val lock = platform.Foundation.NSRecursiveLock()
        private val blocks = ArrayList<() -> Unit>()

        fun add(block: () -> Unit) {
            lock.lock()
            try {
                blocks += block
            } finally {
                lock.unlock()
            }
        }

        fun runAll() {
            val taken = run {
                lock.lock()
                try {
                    blocks.toList()
                } finally {
                    lock.unlock()
                }
            }
            taken.forEach { block ->
                try {
                    block()
                } catch (t: Throwable) {
                    println("[replica-host] a graph component's foreground hook threw: ${t.message}")
                }
            }
        }
    }

    @kotlin.concurrent.Volatile
    private var currentForegrounds: GraphForegrounds? = null

    /**
     * ⚠ B.17 (263): [block] runs at each foreground (on the main thread, inside the notification,
     * so it must only dispatch) while the graph just assembled serves the shared store: Drive
     * replays a new-folder answer kept while no graph served.
     */
    fun onCurrentGraphForeground(block: () -> Unit) {
        currentForegrounds?.add(block)
    }

    /**
     * B.16 (238): what runs when the graph just assembled is **given up with no rebuild to follow**
     * (a corrupt store, a latched process, a refused probe, a deprovision): a component that kept
     * something alive for a successor (Timeline's parked location manager) lets it go. Once; a late
     * registration on a graph already given up runs at once.
     */
    fun onCurrentGraphAbandoned(block: () -> Unit) {
        currentAbandons?.add(block)
    }

    /** The last assembled graph's hand-off to the host, until [graphReady] runs it. */
    @kotlin.concurrent.Volatile
    private var pendingHandOff: (() -> Unit)? = null

    /**
     * Q45: the root job of the graph [pendingHandOff] would hand over, so a reinstall that throws
     * before [graphReady] cancels the half-built graph's scopes with its components (240).
     */
    @kotlin.concurrent.Volatile
    private var pendingGraphJob: ProxyGraphJob? = null

    /**
     * ⚠⚠ **The graph just assembled is complete**: every app calls it
     * at the end of its `install()`, after its components are installed over the graph and
     * registered with [onCurrentGraphStop]. Only now is the graph handed to the shared store's host,
     * which may stop it at once (a not-ready stand-in).
     */
    fun graphReady() {
        val handOff = pendingHandOff ?: return
        pendingHandOff = null
        pendingGraphJob = null
        handOff()
    }

    /**
     * ⚠⚠ **The app's components that are bound to the graph just assembled** (B.16 (228)): a scope
     * the assembly made, a process-level component built over its client (a
     * capture, a backup loop, a collector). [block] runs once, when the shared store's host stops
     * that graph (a fence, a hold, a deprovision), with the loops and the lock; the rebuild then
     * builds the component anew over the new graph. Called by an app right after `assemble`.
     */
    fun onCurrentGraphStop(block: () -> Unit) {
        currentStops?.add(block)
    }

    /**
     * The host's state for this process's shared store ([StorageStateSurface] reads it): null
     * before a graph over it was built (or over a test's replica directory).
     */
    @kotlin.native.HiddenFromObjC
    val sharedReplicaStatus: kotlinx.coroutines.flow.StateFlow<SharedReplicaHostStatus>?
        get() = SharedReplicaBinding.processOrNull()?.host?.status

    /**
     * The binding this runtime uses: on the production path (no test replica directory), always.
     * ⚠⚠ No plist switch and no dev-lab relay guard (B.22): any relay, any build configuration.
     */
    internal fun sqliteBindingFor(replicaDirectory: String?): SharedReplicaBinding? {
        if (replicaDirectory != null) return null
        return sharedReplicaSqliteBinding()
    }

    /** Why the last [sqliteBindingForAssembly] put this launch on the stand-in ([waitsForControl]). */
    internal var standInReason: String? = null
        private set

    /**
     * ⚠⚠ What Control has declared ([SharedReplicaChoice], B.14 (190)): the runtime binds the
     * SQLite store only on [ReplicaChoice.SQLITE]. A test seam.
     */
    internal var sharedReplicaChoice: () -> ReplicaChoice = { SharedReplicaChoice.production().read() }

    /**
     * The binding a runtime **assembles over**: [sqliteBindingFor], and then ⚠⚠ the SQLite store
     * only when Control has declared it (B.14 (190)); otherwise the retryable not-ready stand-in,
     * rebuilt at the next foreground or entry, by when Control may have declared. The custody purge
     * does not ask: a store an app once wrote is wiped before custody goes whatever the choice says
     * now.
     */
    internal fun sqliteBindingForAssembly(replicaDirectory: String?): SharedReplicaBinding? {
        waitsForControl = false
        standInReason = null
        val binding = sqliteBindingFor(replicaDirectory) ?: return null
        val choice = sharedReplicaChoice()
        if (choice != ReplicaChoice.SQLITE) {
            // `reset` is Control mid-way (its forget); nothing declared is Control not bound yet.
            println("[replica-binding] Control's replica choice reads $choice: this app waits on the not-ready stand-in until Control declares the SQLite store")
            waitsForControl = true
            standInReason = "Control has not declared the SQLite store yet"
        }
        return binding
    }

    /** ⚠⚠ B.20.9 (376): the last [sqliteBindingForAssembly] read anything but `sqlite`: the binding is made, the store is the stand-in. */
    internal var waitsForControl: Boolean = false
        private set

    /** The assembled base graph plus the raw [TenshuClient] built over it. Apps wrap this with their repos. */
    class Base internal constructor(
        val koin: Koin,
        val tenshuClient: TenshuClient,
        /**
         * The id this runtime publishes its per-writer rows under (`named("hostAppId")`, the
         * route-1 `clientId`). ⚠ Read your own `RuntimeIdentity` with it —
         * `syncService.runtimeIdentity(hostAppId)` — never the unkeyed read: the replica is
         * shared, so it holds every co-resident app's row.
         */
        val hostAppId: String,
    )

    /**
     * Assemble the production proxy graph in-process over the shared replica and, when Control has
     * handed off realm keys ([handedOffMaterial]), adopt them so the graph is a LIVE realm device.
     *
     * Reuses the **production** Control-on-iOS modules verbatim (the "mirror production wiring" rule)
     * with a last-wins base override for the per-app bits (client id + Keychain delegate + optional
     * test replica dir / dev relay). [appOverrides] is an additional last-wins module for anything
     * app-specific (e.g. Drive's background-upload content-blob-store) — applied *after* the
     * platform module so it wins.
     *
     * Ordering is load-bearing: the handed-off `deviceId`/A4 are seeded into the delegate BEFORE the
     * graph constructs the [SyncManager] (they are storage-read at construction), then [adopt] runs
     * once the client is built (it only touches the SyncManager, so it is order-independent w.r.t.
     * the app's repo construction, which the caller does after).
     */
    fun assembleBase(
        replicaDirectory: String? = null,
        secureStorage: SecureStorageDelegate? = null,
        handedOffMaterial: RealmKeyMaterial? = null,
        relayBaseUrl: String? = null,
        appOverrides: Module? = null,
    ): Base {
        // ⚠⚠ B.22: on the production path always made (no switch); null only for a test's replica directory.
        val sqlite = sqliteBindingForAssembly(replicaDirectory)
        // B.13: Q24's assertion, attach, then install, on the main thread and before the graph
        // can resolve the store (B.12 (163)); once per process.
        sqlite?.installAtLaunch()
        // B.14 (196): re-read at every foreground; a choice that does not allow SQLite fences it.
        val waiting = waitsForControl
        sqlite?.stillChosen = {
            val reading = sharedReplicaChoice()
            // A stand-in waiting for Control stays chosen through `reset`: its rebuild re-reads.
            reading == ReplicaChoice.SQLITE || (waiting && reading == ReplicaChoice.RESET)
        }
        val base = module {
            single(named("platformPackageName")) { config.clientId }
            // `drive-upload-source-read.md`: the reader that turns a picked source
            // reference into bytes, consumed by the `UploadSourceFile` / `ReplaceSourceFile`
            // implementations in the iOS file-service overlay. Bound in the BASE: any first-party
            // runtime that serves an upload op needs one, and the factory errors loudly on its
            // absence rather than falling through to a confusing "no implementation" at upload time.
            // ⚠⚠ `file://` only (`ios-per-app-frameworks.md` D6). This module is in every app's
            // framework, so PhotoKit here would put Photos.framework in every app. Drive binds its
            // `phasset://`-capable reader over this one in its `appOverrides`.
            single<IosUploadSourceReader> { IosFileUploadSource }
            // ⚠⚠ The reader above is only half of what an upload needs. The four source-file ROs
            // ALSO push the source out-of-band before the chain runs, and `fileUploader()` is a
            // non-null cast over a `getOrNull` lookup — so with nothing bound, every route-1 upload
            // and replace NPEs. In-process has no boundary to push across; see
            // [InProcessFileUploader] for why this is a binding and not a null-safe call site.
            single<FileUploader> { InProcessFileUploader }
            // `core-schema-skew-surface.md` SK-2: route 1 links its own proxy, so THIS app owns
            // the runtime — not Control. Bound here and applied last, so it overrides the
            // Control-shaped default in `buildPlatformProxyModule()`, which this assembly
            // deliberately reuses verbatim.
            //
            // ⚠ `clientId` is the route-1 convention (`app.tenshu.fit.ios`), not the announce
            // convention (`app.tenshu.fit`). That is fine and deliberate: consumers ask
            // `isControlHosted()`, which tests against Control's one canonical id — so every other
            // host is free to use whatever id it already has.
            single(named("hostAppId")) { config.clientId }
            single<SecureStorageDelegate> {
                secureStorage ?: IosSecureStorageDelegate(
                    aead = get(),
                    serviceName = config.secureStorageService,
                )
            }
            if (replicaDirectory != null) {
                // A test's replica: the rows every assembly over the same name in this process
                // shares, each graph with its own store handle and the name's one lock.
                single<ObjectStore> { TestReplicas.store(replicaDirectory) }
                single<DatabaseLock> { TestReplicas.lock(replicaDirectory) }
            }
            if (relayBaseUrl != null) {
                single(named("syncRelayUrl")) { relayBaseUrl }
            }
        }
        // B.13: the SQLite store, last-wins over the platform module's, right after the base. One
        // module for the apps and Control (B.14 (189)).
        val store = sqlite?.let {
            sharedReplicaStoreModule(
                it,
                standIn = if (waiting) SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, standInReason ?: "Control has not declared the SQLite store yet", willRetry = true) else null,
            )
        }
        // Fresh module instances, never the shared `val`s: Koin caches singletons ON the module's
        // factories, so reusing the vals across assemblies (tests build several per process)
        // silently shares the first runtime's singletons — SyncManager included.
        val modules = listOfNotNull(buildProxyModule(), buildPlatformProxyModule(), base, store, appOverrides)
        val koin = koinApplication { modules(modules) }.koin
        // Adopt Control's handed-off realm identity BEFORE anything uses the SyncManager — the A4
        // keypair is read from the storage delegate on first use, and `currentDeviceId` is DERIVED
        // from it (the cache is primed with the HANDED KEY's id, never the bridge's separate
        // `deviceId` copy, which a partial publish could leave stale), so seeding the key here makes
        // the app ride Control's ONE device identity (the route-1 key-custody decision) instead of
        // minting its own.
        if (handedOffMaterial != null) {
            val delegate = koin.get<SecureStorageDelegate>()
            // An unparseable blob primes nothing (the manager then mints its own key);
            // it must never throw here — assembly runs bare from Swift, so a throw aborts the app.
            handedOffMaterial.keyDerivedDeviceIdOrNull?.let { delegate.storeString(SyncManagerImpl.Keys.DEVICE_ID, it) }
            delegate.storeSecret(
                SyncManagerImpl.Keys.DEVICE_SIGNING_KEY,
                handedOffMaterial.deviceSigningBlob.decodeToString(),
            )
        }
        val client = koin.get<TenshuClient>()
        if (handedOffMaterial != null) {
            adopt(koin, handedOffMaterial)
        }
        // B.13 (169): rule P's loops, the runtime's, resolved when the protection asks.
        sqlite?.bindLoops { koin.getOrNull<SyncRuntimeHolder>()?.runtime?.components }
        // ⚠⚠ B.16: the graph handed to the host of §6, which stops it at a fence (the loops
        // unbound, `SyncManager.lock()` as `deprovision` does, custody kept) and rebuilds it through
        // the app's reinstall in the foreground or at a protected entry.
        val stops = GraphStops()
        currentStops = stops
        val abandons = GraphStops()
        currentAbandons = abandons
        val foregrounds = GraphForegrounds()
        currentForegrounds = foregrounds
        // ⚠⚠ The graph is handed to the host only by [graphReady], once the app has installed its
        // components over it: a stand-in graph is stopped as it is handed over (B.16 (214)), and a
        // stop that ran before the app's registrations would leave a component started over it
        // running.
        val graphStore = koin.get<ObjectStore>()
        pendingHandOff = sqlite?.let { binding -> { binding.host.graphInstalled(
            store = graphStore,
            stop = {
                runCatching { koin.get<SyncManager>().lock() }
                stops.runAll()
            },
            rebuild = {
                // ⚠⚠ B.16 (229): the old graph is closed before the new one is built, so its
                // singletons are dropped, not orphaned one per rebuild. ⚠⚠ Q45: the close also
                // cancels the graph's root job (`ProxyGraphJob`, the single's `onClose`), so every
                // scope its proxy module made stops here: after the host's stop (loops unbound,
                // `SyncManager.lock()`, the registered components) and step 2b's release, never at
                // the stop itself, where the stopped graph still serves its stand-in.
                // A stop that ran before a late registration already ran it (GraphStops.add);
                // once more here, so nothing of the old graph is left over a closed one.
                stops.runAll()
                closeGraph(koin)
                reinstallOrTearDown()
            },
            mayRebuild = { reinstall != null && rebuildProbe() },
            abandon = { abandons.runAll() },
            foreground = { foregrounds.runAll() },
        ) } }
        pendingGraphJob = if (sqlite != null) proxyGraphJob(koin) else null
        // ⚠ One line here rather than six in the per-app runtimes: the realm-storage banner needs a
        // client, and "each app remembers to wire it" is precisely the shape that leaves an app's
        // services unregistered. Install-only — no service init, no Koin lookups
        // beyond the client already resolved above, so it cannot fail on the startup path.
        // SS-1b: the strip's health is THIS runtime's, read in-process from its own holder — the
        // store's single health row is shared by every app on the replica (`RealmStorageSurface`).
        RealmStorageSurface.install(
            client,
            ownConnectivity = koin.getOrNull<SyncHealthHolder>()?.observe()
                ?.map { it.connectivity() }
                ?.distinctUntilChanged(),
        )
        // The sync-activity capsule (`ios-sync-activity-indicator.md` §3), at the same seam and for
        // the same reason. ⚠ The id is the one `hostAppId` above hands the scheduler: the surface
        // reads the row THIS runtime writes, and a second spelling of it would read nobody's.
        SyncActivitySurface.install(client, hostAppId = config.clientId)
        SyncActivitySurface.debugCatchUp = { koin.getOrNull<SyncRuntimeHolder>()?.runtime?.components?.catchUp }
        return Base(koin = koin, tenshuClient = client, hostAppId = config.clientId)
    }

    /**
     * The app's reinstall. ⚠ B.16 (240): one that throws after it assembled a graph
     * leaves that half-built graph's components (its scopes, a component started before the throw)
     * running with nothing to stop them: they are stopped here, and the graph never handed over.
     */
    private fun reinstallOrTearDown() {
        val reinstallNow = reinstall ?: return println("[replica-host] no reinstall registered: the graph is not rebuilt")
        try {
            reinstallNow()
        } catch (t: Throwable) {
            if (pendingHandOff != null) {
                pendingHandOff = null
                currentStops?.runAll()
                // Q45: never handed over and never closed, so its scopes are cancelled here.
                pendingGraphJob?.cancel()
                pendingGraphJob = null
                println("[replica-host] the reinstall threw after it assembled a graph: its components were stopped")
            }
            throw t
        }
    }

    /** Test seam: the graphs [closeGraph] closed. */
    internal var closedGraphs = 0
        private set

    /** Closes an old graph's Koin (B.16 (229)). Never throws. */
    private fun closeGraph(koin: Koin) {
        try {
            // Q45: the graph's root job cancelled first, then the close (B.16.15 (295)).
            closeProxyGraph(koin)
            closedGraphs++
        } catch (t: Throwable) {
            println("[replica-host] closing the old graph threw: ${t.message}")
        }
    }

    /**
     * Make the app a live realm device with the material Control handed off over the shared Keychain
     * — WITHOUT the master key A1, which the app never holds. `initializeWithKeys` is the **joiner**
     * path (derived keys + ownerId, no phrase) and a route-1 app is exactly a joiner-shaped device:
     * it also persists the joiner payload in the app's own (sealed) secret bucket, so the standard
     * construction-time rehydration makes the adoption durable across launches.
     *
     * The two latches mirror what is already true realm-wide: Control registered the realm
     * (`realmRelayRegistered`) and this device rides an owner-funded realm (`joinedFundedRealm`).
     * With `isEnabled()` true, the STANDARD machinery lights up: save observers emit realm-sealed
     * `SyncBlock`s, the scheduler's drain pushes them with the A4-signed JWT, and pulls ingest through
     * the `SyncProcessor` — no parallel sync path.
     */
    private fun adopt(koin: Koin, material: RealmKeyMaterial) {
        val syncManager = koin.get<SyncManager>()
        // A13: the realm encryption keypair rides the hand-off so the sharing cluster works on a
        // route-1 app — the standard joiner payload slot; absent (material written by an older
        // Control) → sharing write-paths fail loudly.
        val a13Public = material.realmEncryptionPublicKey
        val a13Private = material.realmEncryptionPrivateKey
        val realmEncryptionKeyPair =
            if (a13Public != null && a13Private != null) {
                SyncCryptoManager.KeyPair(publicKey = a13Public, privateKey = a13Private)
            } else {
                null
            }
        syncManager.initializeWithKeys(
            keys = SyncCryptoManager.DerivedKeys(
                sharedIdentityKey = material.sharedIdentityKey,
                sharedVolumeKey = material.sharedVolumeKey,
            ),
            ownerId = material.ownerId,
            realmEncryptionKeyPair = realmEncryptionKeyPair,
        )
        syncManager.realmRelayRegistered = true
        syncManager.joinedFundedRealm = true
    }

    /**
     * The shipping gate probe (`ios-drive-parity.md` P1d): whether Control has provisioned the
     * route-1 key material into the shared Keychain. Reads the ONE shared blob (see the class
     * KDoc's key-scope caveat). Cheap enough for a launch/foreground probe.
     */
    fun isProvisioned(): Boolean = RealmKeyMaterialBridge.read() != null

    /**
     * A standalone delegate on the app's Keychain bucket — for the deprovision-at-launch path, where
     * the vanish is detected before any proxy graph booted (no koin to read the delegate from). Just
     * the crypto backends the delegate's AEAD seam needs.
     */
    fun standaloneDelegate(): SecureStorageDelegate {
        val crypto = koinApplication { modules(cryptoBackendsModule()) }.koin
        return IosSecureStorageDelegate(aead = crypto.get(), serviceName = config.secureStorageService)
    }

    /** Whether the app still holds adopted custody at rest (the joiner payload) — the deprovision trigger. */
    fun hasResidualCustody(delegate: SecureStorageDelegate): Boolean =
        delegate.getSecret(SyncManagerImpl.Keys.JOINER_PAYLOAD) != null

    /**
     * Purge the app's own at-rest custody copies (threat-model A4 duty): the joiner payload, ownerId,
     * A4 blob, deviceId, and the realm latches. Without this a cleared provisioning would silently
     * resurrect the adopted identity on the next launch via the construction-time rehydration.
     * True when purged; false when the SQLite store's wipe did not finish and custody was kept.
     */
    fun purgeCustody(delegate: SecureStorageDelegate): Boolean {
        // ⚠⚠ B.13 (171), the D3 order: the store first, custody after.
        // A wipe that did not finish keeps custody (so this runs again, and the store's `meta.wipe`
        // makes the next open finish the wipe); never custody gone over a store still present.
        // ⚠ The wipe is handed to the worker (B.13 (178)): this is the main thread at launch. The
        // keys then go once it finishes, if Control's bridge is still gone by then.
        val sqlite = sqliteBindingFor(replicaDirectory = null)
        if (sqlite != null) {
            val now = sqlite.wipeBeforeCustodyPurge(replicaBuildOfThisBuild()) {
                if (!stillDeprovisioned()) {
                    println("[replica-binding] re-provisioned before the wipe finished: custody kept")
                } else {
                    purgeKeys(delegate)
                    // ⚠ A re-provision racing the purge can lose keys it had just written; the next
                    // launch re-adopts from the bridge (B.13 (178)). Said, never repaired here.
                    if (!stillDeprovisioned()) println("[replica-binding] re-provisioned during the custody purge: the next launch re-adopts from the bridge")
                }
            }
            if (!now) return false
        }
        purgeKeys(delegate)
        return true
    }

    private fun purgeKeys(delegate: SecureStorageDelegate) {
        CUSTODY_KEYS.forEach { key -> runCatching { delegate.remove(key) } }
    }

    /**
     * A wake (a background refresh): the SQLite store's worker trigger (B.12 (162)). Nothing
     * before this process opened the store. Any thread; only dispatches.
     */
    fun wakeSharedReplica() {
        // Only a process that bound the store has a binding.
        SharedReplicaBinding.processOrNull()?.wake()
    }

    companion object {
        private val CUSTODY_KEYS = listOf(
            SyncManagerImpl.Keys.JOINER_PAYLOAD,
            SyncManagerImpl.Keys.OWNER_ID,
            SyncManagerImpl.Keys.DEVICE_SIGNING_KEY,
            SyncManagerImpl.Keys.DEVICE_ID,
            SyncManagerImpl.Keys.LAST_SYNC_TIMESTAMP,
            SyncManagerImpl.Keys.REALM_RELAY_REGISTERED,
            SyncManagerImpl.Keys.JOINED_FUNDED_REALM,
        )
    }
}
