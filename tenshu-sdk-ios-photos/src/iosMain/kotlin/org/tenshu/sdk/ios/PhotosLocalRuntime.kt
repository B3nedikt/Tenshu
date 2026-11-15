package org.tenshu.sdk.ios

import app.tenshu.photos.data.LibraryWatch
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.data.declarePhotosWantedThumbnailClasses
import app.tenshu.photos.data.photosControlReachability
import app.tenshu.control.di.OwnedInferenceStores
import app.tenshu.control.di.PhotosModelDownloads
import app.tenshu.control.di.PhotosModelTransfers
import app.tenshu.control.di.PhotosOnDeviceAi
import app.tenshu.control.di.PhotosIndexNow
import app.tenshu.control.di.PhotosIndexNowTask
import app.tenshu.control.di.PhotosProcessingWake
import app.tenshu.control.di.PhotosProcessingWakeTask
import org.tenshu.inference.proxy.ForegroundArrivals
import org.tenshu.inference.proxy.ProcessingWake
import org.tenshu.inference.proxy.WakeScheduler
import org.tenshu.inference.proxy.WakeTask
import org.tenshu.inference.proxy.ContinuedIndexing
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaTaskProtection
import org.tenshu.inference.proxy.ContinuedTask
import app.tenshu.control.ui.screens.models.ModelsRepository
import app.tenshu.control.di.ownedInferenceStoresModule
import org.tenshu.inference.proxy.catalog.ModelTransferReport
import app.tenshu.control.di.photosOnDeviceAiModule
import app.tenshu.reachability.ControlReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.tenshu.inference.proxy.MediaIndexRunner
import org.koin.core.qualifier.named
import org.tenshu.inference.proxy.AiReadiness
import org.koin.core.module.Module
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.sync.common.SyncManager
import platform.Foundation.NSBundle

/**
 * The route-1 **in-process** Photos runtime (`photos-ios.md` PIO-1) — the Photos twin of
 * [CalendarLocalRuntime] / [ContactsLocalRuntime], over the [FirstPartyLocalRuntime] substrate
 * (`ios-first-party-route-1-migration.md`). Photos is **born** on route 1: no EPC tier, nothing to
 * migrate off.
 *
 * ⚠ **No new service wiring.** Every service Photos reads is already hosted by the route-1 graph —
 * `:file-service`, `:photo-service` (`initPhotoService` runs in the iOS `PlatformProxyModule`),
 * `:place-service`, `:ingest-service`, `:preference-service`, `:inference-service`. That is what
 * keeps this file thin. (Android's `photosSchemaConfigs` list has no counterpart here: route 1 runs
 * the whole proxy graph in-process, so every schema is registered by construction.)
 *
 * ⚠⚠ **Photos does not drain the backup on iOS** (PIO-D5). Drive's in-process runtime runs the
 * PhotoKit drain; two route-1 processes draining one cursor into one replica would upload twice.
 * Photos writes the same selection rows (PIO-5) and shows status.
 *
 * ⚠⚠ **Photos owns the inference stores on iOS** (PIO-D10, `ios-on-device-inference.md` §3.1): the
 * vector and template logs, the model bytes and their index live in Photos' own container, bound
 * through `ownedInferenceStoresModule`, and carry a stamp of the realm they were built for (the owner
 * id and the shared store's `meta.epoch`). Every
 * other route-1 graph binds stores that cannot write.
 * ⚠⚠ **On-device AI is behind a build flag that is off** ([ON_DEVICE_AI_INFO_KEY],
 * `ios-on-device-inference.md` §14). With it off, or on a phone a static gate refuses, no backend
 * is bound and search by description is never asked for. With it on, the backend is bound
 * **once**, here, and never rebound: what it claims is `AiReadiness`'s to say. A model is
 * downloaded through the hooks at the end of this object; until one is prepared and has passed
 * its probe, `photosLike` answers `NO_BACKEND`.
 *
 * Whole-blob key custody (migration proposal §4 → **C**): first-party apps are realm co-owners on
 * iOS route 1.
 */
object PhotosLocalRuntime {

    /** Photos' client id for the in-process `LocalClientConnection` (route-1, not Control's). */
    const val PHOTOS_CLIENT_ID: String = "app.tenshu.photos.ios"

    /** Photos' per-app Keychain bucket — the assembly's delegate and [deprovision] hit the same items. */
    private const val SECURE_STORAGE_SERVICE = "app.tenshu.photos.ios.secure-storage"

    /**
     * Whether this build can play a `MOTION` clip in a grid tile — `true`: the `AVPlayerLayer` tile
     * player draws it (`photos-ios.md` §8.10). ⚠⚠ It moves WITH the player, never before: declaring
     * the rung while nothing can draw it makes every device mint clips for an app that shows none —
     * and left `false` after, an unminted video's clip is never backfilled and its tile stays a
     * still poster.
     */
    private const val MOTION_PLAYBACK_SUPPORTED = true

    /**
     * The Info.plist key of the on-device AI build flag: `photos-ios/project.yml` writes the build
     * setting `TENSHU_PHOTOS_ON_DEVICE_AI` into it, and that setting is `NO`.
     *
     * ⚠⚠ Off until the device pass of `ios-on-device-inference.md` §5: the code ships, the flag
     * does not move. ⚠ Per app, like every other build-time value an iOS app reads (the
     * `TenshuControlAppStoreId` precedent): the umbrella is one binary linked into ten apps, and a
     * constant baked into it could not be Photos' alone.
     */
    const val ON_DEVICE_AI_INFO_KEY: String = "TenshuPhotosOnDeviceAi"

    /**
     * Whether [raw], the Info.plist value, switches on-device AI on. ⚠ Only the exact `YES`: an
     * unset build setting substitutes to the empty string, an unsubstituted one stays `$(…)`, and
     * a missing key is `null` — each of them is off.
     */
    internal fun onDeviceAiBuildFlag(
        raw: Any? = NSBundle.mainBundle.objectForInfoDictionaryKey(ON_DEVICE_AI_INFO_KEY),
    ): Boolean = (raw as? String)?.trim() == "YES"

    /**
     * The Info.plist key of the **face** build flag (`ios-face-grouping.md` FD10, F5):
     * `photos-ios/project.yml` writes the build setting `TENSHU_PHOTOS_FACES` into it, and that
     * setting is `NO`.
     *
     * ⚠⚠ A second flag, never a widening of the first: it binds the face backend only where the AI
     * flag and both static gates already admitted the phone ([PhotosOnDeviceAi.facesAdmitted]).
     * Off until F8's device pass, and flipped after the AI flag (FD10).
     */
    const val FACES_INFO_KEY: String = "TenshuPhotosFaces"

    /** Whether [raw], the Info.plist value, switches faces on. ⚠ Only the exact `YES`, as [onDeviceAiBuildFlag]. */
    internal fun facesBuildFlag(
        raw: Any? = NSBundle.mainBundle.objectForInfoDictionaryKey(FACES_INFO_KEY),
    ): Boolean = (raw as? String)?.trim() == "YES"

    /**
     * A test's stand-in for the build flags and the phone the static gates read.
     *
     * @param facesBuildFlag the face flag; off unless a case says otherwise, as in production.
     */
    class OnDeviceAiInputs(
        val buildFlag: Boolean,
        val osMajorVersion: Int,
        val physicalMemoryBytes: Long,
        val facesBuildFlag: Boolean = false,
    )

    private val runtime = FirstPartyLocalRuntime(
        FirstPartyLocalRuntime.Config(
            clientId = PHOTOS_CLIENT_ID,
            secureStorageService = SECURE_STORAGE_SERVICE,
        ),
    )

    /** One assembled runtime: the Koin graph plus the repository built over its client. */
    class Assembly internal constructor(
        internal val koin: Koin,
        val tenshuClient: TenshuClient,
        val photosRepository: PhotosRepository,
        internal val reachability: ControlReachability,
        internal val scope: CoroutineScope,
        /** Where this assembly's inference stores live — what [deprovision] clears by path. */
        internal val inferenceRoot: String,
        /** Whether a backend was bound: the static gates' answer, for the life of this assembly. */
        internal val onDeviceAi: Boolean,
        /** Whether the face backend was bound too: the face flag over [onDeviceAi]'s answer. */
        internal val faces: Boolean,
        /**
         * What the Models screen is built over. ⚠ A provider, resolved when the screen first
         * opens: resolving it constructs the graph's model source, which is not launch-path work.
         */
        internal val modelsRepository: () -> ModelsRepository,
    ) {
        /** After a deprovision: the towers being unloaded behind it. A test waits on it. */
        internal var unloading: Job? = null

        /**
         * The last inference-store check [launchInferenceCheck] started: what a processing wake waits
         * for before it asks what is asked of it (until it ends no backend serves).
         */
        @kotlin.concurrent.Volatile
        internal var checking: Job? = null
    }

    private var installed: Assembly? = null

    /** Whether [install] has run. */
    fun isInstalled(): Boolean = installed != null

    /** The shipping gate probe: has Control provisioned the route-1 key material? */
    fun isProvisioned(): Boolean = runtime.isProvisioned()

    /**
     * Assemble a runtime without touching the global surface — the testable entry point.
     *
     * The **one** reachability engine is built here and shared, exactly as
     * `photosRepositoryModule()` does on Compose (`fit-app.md` §9.14). There is no whole-realm
     * index to share (`photos-app.md` §40).
     */
    fun assemble(
        replicaDirectory: String? = null,
        secureStorage: SecureStorageDelegate? = null,
        handedOffMaterial: RealmKeyMaterial? = null,
        relayBaseUrl: String? = null,
        /** A test's directory for the inference stores; production is Photos' Application Support. */
        inferenceRoot: String? = null,
        /** A test's answer for the realm the device is on; production reads Control's hand-off. */
        deviceRealmOwner: (() -> String?)? = null,
        /** A test's stand-in for marking the directory excluded from backup; `null` is the real call. */
        inferenceBackupExclusion: ((String) -> String?)? = null,
        /**
         * A test's answer for the shared store's `meta.epoch`, the realm stamp's second half; `null`
         * reads the graph's own store, as production does (`ownedInferenceStoresModule`).
         */
        replicaEpoch: (() -> Long?)? = null,
        /** A test's build flag and phone; production reads the Info.plist and `NSProcessInfo`. */
        onDeviceAi: OnDeviceAiInputs? = null,
        /** A test's bindings over the on-device AI module (a compiler that compiles nothing). */
        onDeviceAiOverrides: Module? = null,
    ): Assembly {
        val ownedRoot: String = inferenceRoot ?: OwnedInferenceStores.privateRoot()
        // ⚠⚠ Decided here, once, before anything is offered: nothing below can change it, and
        // nothing later reassembles this graph (`ios-on-device-inference.md` §3.2, §7 D12).
        val aiAdmitted: Boolean =
            if (onDeviceAi != null) {
                PhotosOnDeviceAi.admitted(onDeviceAi.buildFlag, onDeviceAi.osMajorVersion, onDeviceAi.physicalMemoryBytes)
            } else {
                PhotosOnDeviceAi.admitted(onDeviceAiBuildFlag())
            }
        // ⚠⚠ Faces: their own flag AND everything above. Decided once, with the same inputs.
        val facesAdmitted: Boolean = aiAdmitted &&
            if (onDeviceAi != null) {
                PhotosOnDeviceAi.facesAdmitted(onDeviceAi.buildFlag, onDeviceAi.facesBuildFlag, onDeviceAi.osMajorVersion, onDeviceAi.physicalMemoryBytes)
            } else {
                PhotosOnDeviceAi.facesAdmitted(onDeviceAiBuildFlag(), facesBuildFlag())
            }
        // The one thing that makes this app, and no other, the device's inference owner.
        val ownedStores: Module = ownedInferenceStoresModule(
            root = ownedRoot,
            deviceRealmOwner = deviceRealmOwner ?: { RealmKeyMaterialBridge.read()?.ownerId },
            excludeFromBackup = inferenceBackupExclusion ?: OwnedInferenceStores::excludeFromBackup,
            storeEpoch = replicaEpoch,
        )
        val base = runtime.assembleBase(
            replicaDirectory = replicaDirectory,
            secureStorage = secureStorage,
            handedOffMaterial = handedOffMaterial,
            relayBaseUrl = relayBaseUrl,
            // ⚠ Not admitted: the very module this graph had before the flag existed.
            appOverrides = if (aiAdmitted) {
                module { includes(listOfNotNull(ownedStores, photosOnDeviceAiModule(ownedRoot, faces = facesAdmitted), onDeviceAiOverrides)) }
            } else {
                ownedStores
            },
        )
        val client = base.tenshuClient
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // ⚠ No schema-skew source: route 1 runs this app's own proxy, so there is no app↔proxy
        // skew to announce (Android's announce path has no counterpart), and `null` degrades to the
        // pre-SK-1 behaviour rather than failing.
        val reachability = photosControlReachability(client, probeScope = scope, controlHasRealm = app.tenshu.reachability.RealmProbe.None("iOS route-1: provisioning is ControlProvisioning's, not this engine's"))
        return Assembly(
            koin = base.koin,
            tenshuClient = client,
            // ⚠ [scope]: where the library watch keeps its observers (`photos-ios.md` §8.22.3). Without
            // it the repository promises nothing and Collections walks the library on every return.
            // ⚠⚠ On ONE lane, off the main thread. Off it, because a subscribe evaluates the list it
            // observes and the labels are thousands of rows. One lane, because a subscribe registers
            // with `StreamRegistry` on the thread that makes it, and its maps have no common lock
            // (`tech-debt.md`): five registrations side by side would be five new writers at once.
            photosRepository = TenshuPhotosRepository(
                client,
                reachability = reachability,
                watchScope = CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1)),
                // An edited copy's push to the relay outlives the editor (`photos-editing.md` §9.6).
                copyUploadScope = scope,
            ),
            reachability = reachability,
            scope = scope,
            inferenceRoot = ownedRoot,
            onDeviceAi = aiAdmitted,
            faces = facesAdmitted,
            // ⚠ From the same inputs the decision above was made from, so the screen and the
            // graph cannot disagree about whether this phone is offered a model.
            modelsRepository = {
                if (onDeviceAi != null) {
                    PhotosOnDeviceAi.modelsRepository(base.koin, onDeviceAi.buildFlag, onDeviceAi.osMajorVersion, onDeviceAi.physicalMemoryBytes)
                } else {
                    PhotosOnDeviceAi.modelsRepository(base.koin, onDeviceAiBuildFlag())
                }
            },
        )
    }

    /**
     * The realm-stamp check and the fingerprint rule over [assembly]'s inference stores; `true`
     * when they are usable. ⚠ Never on the main thread — see [OwnedInferenceStores.check].
     */
    internal suspend fun checkInferenceStores(assembly: Assembly): Boolean =
        OwnedInferenceStores.check(assembly.koin)

    /**
     * Whether the installed runtime's inference stores may be read or written. `false` until the
     * check [install] starts has finished. What a backend's readiness consults (§14).
     */
    internal fun inferenceStoresUsable(): Boolean =
        installed?.let { OwnedInferenceStores.usable(it.koin) } ?: false

    /**
     * ⚠⚠ On the assembly's own scope, never inline: both callers are on, or one hop from, the
     * launch path, the first check reads the logs whole and a mismatch clears them. Until it
     * returns the stores are not usable, which is the safe side.
     */
    private fun launchInferenceCheck(assembly: Assembly, launch: Boolean) {
        assembly.checking = assembly.scope.launch {
            runCatching { checkInferenceStores(assembly) }
                .onFailure { println("PhotosLocalRuntime -> inference store check failed: $it") }
            // Readiness's writer at launch and on every foreground, after the stores' own check:
            // a new OS build or build constant voids the verdict it holds. Then the preflight,
            // for a prepared model with no verdict, and at launch the text tower's warm-up.
            if (assembly.onDeviceAi) {
                runCatching { PhotosOnDeviceAi.recheck(assembly.koin, launch) }
                    .onFailure { println("PhotosLocalRuntime -> readiness check failed: $it") }
            }
        }
    }

    /**
     * Production path: read the durable realm-key hand-off and assemble over the shared replica,
     * then install the real repository on [PhotosSurface]. Idempotent; process-lifetime.
     */
    fun install() {
        if (installed != null) return
        runtime.rebuildWith(::rebuildGraph)
        install(
            assemble(
                handedOffMaterial = RealmKeyMaterialBridge.read(),
                relayBaseUrl = devRelayOverride(),
            ),
        )
    }

    /**
     * ⚠ `ios-shared-store-sqlite.md` Appendix B.16: how the shared store's host rebuilds
     * this app's graph after a fence or a not-ready open, on the main thread: the re-provision path
     * ([install] after `installed` is dropped). Registered by [install]; never called
     * without a binding.
     */
    private fun rebuildGraph() {
        installed = null
        install()
    }

    /**
     * Whether [assembly]'s phone groups faces, for People — or `null` where the assembly bound no
     * face backend (`ios-face-grouping.md` F7). ⚠⚠ Read from the graph's face readiness, so a phone
     * the face preflight found unsuitable, or a graph that was torn down (its readiness retired,
     * B.16) answers `false` until the next install replaces it ([PhotosFaceGrouping.over]).
     */
    internal fun faceGrouping(assembly: Assembly): PhotosFaceGrouping? {
        if (!assembly.faces) return null
        val readiness: AiReadiness = assembly.koin.getOrNull(named(PhotosOnDeviceAi.FACE_READINESS)) ?: return null
        val koin: Koin = assembly.koin
        return PhotosFaceGrouping.over(readiness) { koin.getOrNull<MediaIndexRunner>() }
    }

    /** [install]'s body over a given assembly — the testable entry point. */
    internal fun install(assembly: Assembly) {
        installed = assembly
        // ⚠ B.16 (228): when the shared store's host stops this graph, its scope, its
        // on-device AI (indexer, towers) and its inference stores go as at a deprovision; custody
        // and the surfaces stay (the rebuild reinstalls the surfaces over the new graph).
        runtime.onCurrentGraphStop { teardownGraph(assembly) }
        // W-1: the rungs this app draws, from the same list Android declares (PIO-0).
        assembly.scope.declarePhotosWantedThumbnailClasses(
            client = assembly.tenshuClient,
            motionPlaybackSupported = MOTION_PLAYBACK_SUPPORTED,
            // Explicitly ungated (ADR 0025 §8.6): iOS route-1 wires no realm probe.
            realmGate = { null },
        )
        PhotosSurface.installRepository(assembly.photosRepository)
        // ⚠ Started now, not when Collections is first opened: its observers take a moment to go
        // live, and a first walk that began before they did cannot be called clean — the first
        // return to the tab would walk the library once more for nothing.
        val watch: LibraryWatch? = assembly.photosRepository.libraryWatch()
        // ⚠ PR-6b (IN-D6a): new arrivals are indexed while Photos is in front, found from that same
        // watch — never from a walk. Only where a backend was bound (the AI flag; faces their own).
        if (assembly.onDeviceAi) followArrivals(assembly, watch)
        // ⚠ The route search takes is the assembly's, decided with its graph: `null` keeps the
        // "isn't available on this iPhone yet" surface, where the proxy is never asked.
        val koin: Koin = assembly.koin
        PhotosSurface.installOnDeviceAi(if (assembly.onDeviceAi) ({ PhotosOnDeviceAi.unsuitable(koin) }) else null)
        // F7: People's `groupingRunsHere`, from the same assembly — never a literal in Swift.
        PhotosSurface.installFaceGrouping(faceGrouping(assembly))
        PhotosSurface.installModels(assembly.modelsRepository)
        // The stamp's first check point: before anything can be bound over the stores.
        launchInferenceCheck(assembly, launch = true)
        // The graph is complete; only from here does the shared store's host take it, and it may
        // stop it at once (a not-ready stand-in). Nothing when the process has no binding.
        runtime.graphReady()
    }

    /**
     * The library watch's changes into the arrivals batch (`ios-on-device-inference.md` §17). ⚠⚠ Every
     * move of the revision may be an arrival (it errs toward moving, `LibraryWatch`); with no watch,
     * or one not watching, nothing vouches, and every foreground owes a batch. ⚠ A batch waits for
     * the latest store check first ([Assembly.checking]): until it ends no backend serves.
     */
    private fun followArrivals(assembly: Assembly, watch: LibraryWatch?) {
        runCatching {
            assembly.koin.get<ForegroundArrivals>().follow(
                changes = watch?.revision?.drop(1)?.map { } ?: emptyFlow(),
                vouches = { watch?.watching?.value == true },
                prepare = { assembly.checking?.join() },
            )
        }.onFailure { println("PhotosLocalRuntime -> new arrivals are not followed: $it") }
    }

    /**
     * The foreground hook of an already-installed runtime: the inference stamp's second check point
     * (a reset can land while Photos is resident and suspended). Nothing when nothing is installed.
     */
    fun checkAtForeground() {
        val assembly = installed ?: return
        launchInferenceCheck(assembly, launch = false)
    }

    /**
     * Tear down Photos' side of the route-1 provisioning: seal the graph, stop the probe, purge
     * Photos' per-app custody copies, clear the vectors and face templates derived from the realm
     * being left (never the models), and restore the honest not-connected surface. The shared
     * replica is left alone (Control's store).
     */
    fun deprovision() {
        val assembly = installed
        installed = null
        deprovision(assembly)
    }

    /**
     * ⚠⚠ One graph's teardown when the shared store's host stops it (B.16 (228)): its scope
     * cancelled and its on-device AI retired (the running pass and the indexing scope cancelled, the
     * towers unloaded behind it). ⚠⚠ **Never [OwnedInferenceStores.relinquish]**: that is the
     * leaving-the-realm erase (the stamp, then every vector and face template), and a fence is not
     * leaving the realm: the rebuilt graph reads the same index a relaunch would. The stores are
     * plain files with no handle to close, so nothing else is owed.
     */
    internal fun teardownGraph(assembly: Assembly) {
        assembly.scope.cancel()
        if (assembly.onDeviceAi) assembly.unloading = PhotosOnDeviceAi.retire(assembly.koin)
        // ⚠ B.16 (241): the stopped graph's index pass, stopped and waited for on a
        // scope of the process's, so the next graph's store check (which opens the same files)
        // first joins it, with a bound: no append of the old graph is in flight when it opens them.
        // (245) Resolved here, synchronously, while the graph's Koin is open; only the cancel
        // and the join run later.
        val stopWriter: suspend () -> Unit = OwnedInferenceStores.writerStopper(assembly.koin)
        // ⚠⚠ (246) No graph opens the files until it has really ended: no deadline.
        OwnedInferenceStores.holdOpensUntil(teardownScope.launch { stopWriter() })
    }


    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** [deprovision]'s body over a given assembly — the testable entry point. */
    internal fun deprovision(assembly: Assembly?) {
        PhotosSurface.installNotConnected()
        // §36 step 2: an identity teardown leaves no Collections cards behind.
        PhotosSurface.clearCollectionsSnapshot()
        if (assembly != null) {
            assembly.scope.cancel()
            // ⚠⚠ Before the stores are given up, and terminal: from this line nothing is served
            // from this graph, which stays reachable from view models after a sign-out. It does
            // not wait: the towers are unloaded behind it, on a scope that outlives this one.
            if (assembly.onDeviceAi) assembly.unloading = PhotosOnDeviceAi.retire(assembly.koin)
            OwnedInferenceStores.relinquish(assembly.koin, assembly.inferenceRoot)
            runCatching { assembly.koin.get<SyncManager>().lock() }
            runtime.purgeCustody(assembly.koin.get<SecureStorageDelegate>())
        } else {
            OwnedInferenceStores.relinquish(koin = null)
            runtime.purgeCustody(runtime.standaloneDelegate())
        }
    }

    // ─── The model download (`ios-on-device-inference.md` §14.11) ──────────────────────────────

    /**
     * Whether this build, on this phone, offers a model at all: the build flag and the static
     * gates, as [assemble] decides them. ⚠ What the app asks before it creates the background
     * session: with the flag off there is no session, no bridge and no transfer.
     */
    fun modelDownloadsOffered(): Boolean = PhotosOnDeviceAi.admitted(onDeviceAiBuildFlag())

    /**
     * Whether this build has a Models screen at all: the build flag alone. ⚠ Not
     * [modelDownloadsOffered]: a phone the gates refused still gets the screen, which says why.
     */
    fun modelsScreenInThisBuild(): Boolean = onDeviceAiBuildFlag()

    /** The Models repository while no runtime is installed: why nothing is offered, or *not connected*. */
    internal fun modelsWithNoRuntime(): ModelsRepository = PhotosOnDeviceAi.modelsRepository(koin = null, buildFlag = onDeviceAiBuildFlag())

    /**
     * Whether an enabled build ever downloaded on this phone. What a build that does **not**
     * offer a model asks at launch: only then is there a session to attach and drain.
     */
    fun modelDownloadsLeftBehind(): Boolean = PhotosModelDownloads.leftBehind()

    /** For a build that offers no model: deletes the delivered files nothing will ever install. */
    fun discardDeliveredModelFiles() {
        PhotosModelDownloads.discardDelivered()
    }

    /**
     * Registers the Swift background-`URLSession` driver (`photos-ios/Sources/ModelDownload.swift`).
     * Once, at app launch, and only when [modelDownloadsOffered].
     *
     * ⚠ The process's, not an assembly's: the session outlives every runtime, and the runtime
     * installed later asks for it when it has something to transfer or to resume.
     */
    fun installModelTransferBridge(bridge: ModelTransferBridge) {
        PhotosModelDownloads.installTransfers(
            object : PhotosModelTransfers {
                override fun start(ref: String, url: String, allowsCellular: Boolean) = bridge.start(ref, url, allowsCellular)
                override fun liveRefs(completion: (List<String>) -> Unit) = bridge.liveTransferRefs(completion)
                override fun cancel(ref: String) = bridge.cancel(ref)
            },
        )
    }

    /**
     * Swift → Kotlin, **synchronously from `urlSession(_:downloadTask:didFinishDownloadingTo:)`**:
     * moves the session's temporary file into Photos' private downloads directory.
     *
     * ⚠⚠ Needs no installed runtime, and must not: this also runs in a process iOS relaunched in
     * the background for the session's events, where installing a runtime would open the shared
     * replica. The file waits there, unverified, for the next launch to settle it from the journal.
     *
     * @return whether the file was taken.
     */
    fun onModelTransferDelivered(ref: String, temporaryPath: String, statusCode: Int): Boolean =
        PhotosModelDownloads.acceptDelivered(ref, temporaryPath, statusCode)

    /** Swift → Kotlin: the file just delivered for [ref] is nobody's (the ledger's `endDelivery`). */
    fun discardDeliveredModelFile(ref: String) = PhotosModelDownloads.discardDelivered(ref)

    /**
     * Swift → Kotlin, from `didCompleteWithError`. Returns at once. With no runtime installed it
     * is dropped: the next launch reads the journal and the disk, which say the same.
     *
     * ⚠⚠ **And it is dropped whenever Photos is not in the foreground**, runtime or no runtime
     * ([modelTransfersSettleNow]). "A background relaunch installs no runtime" covers a process
     * iOS *started* for the session's events; a Photos that was only **suspended** is woken for
     * the same events with its runtime resident, and would hash half a gigabyte and commit with
     * seconds of background time and no assertion to finish in. So the rule is one rule: in the
     * background a delivered file is moved into place and nothing else happens; it is verified
     * and installed at the next return to the foreground, from the journal and the disk.
     *
     * @return whether the report was taken, and so whether [report] will be settled by Kotlin.
     */
    fun onModelTransferCompleted(ref: String, statusCode: Int, errorMessage: String?, report: ModelTransferReport): Boolean {
        if (!settlesModelTransfers) return false
        val assembly: Assembly = installed?.takeIf { it.onDeviceAi } ?: return false
        return PhotosModelDownloads.transferFinished(assembly.koin, ref, statusCode, errorMessage, report)
    }

    /** Swift → Kotlin: a transfer of [ref] could not be made at all. No task ever held the ref. */
    fun onModelTransferNotStarted(ref: String, errorMessage: String): Boolean =
        onModelTransferCompleted(ref, -1, errorMessage, ModelTransferReport.Unquestioned)

    /**
     * Swift → Kotlin, from the scene phase: whether Photos is in the foreground, and so whether
     * a transfer's end is acted on now ([onModelTransferCompleted]). ⚠ `false` until the first
     * call: a process is not assumed to be in the foreground.
     * ⚠ What it does not cover: a settle that began in the foreground and is still hashing when
     * Photos is sent to the background. It touches Photos' own private files only (the journal,
     * the staging and the model store, none of them in the App Group), so a suspension that
     * catches it freezes it and nothing more.
     */
    fun modelTransfersSettleNow(inForeground: Boolean) {
        settlesModelTransfers = inForeground
        // The same fact, for the prepare step: nothing is compiled in the background either.
        // ⚠⚠ And for a load the scene leaves under: recorded as frozen, not as a kill (§14.5).
        PhotosOnDeviceAi.sceneChanged(installed?.takeIf { it.onDeviceAi }?.koin, inForeground)
    }

    @kotlin.concurrent.Volatile
    private var settlesModelTransfers: Boolean = false

    /** Swift → Kotlin, from `didWriteData`: the bytes of [ref] received so far. */
    fun onModelTransferProgress(ref: String, bytesWritten: Long) {
        val assembly: Assembly = installed?.takeIf { it.onDeviceAi } ?: return
        PhotosModelDownloads.transferProgressed(assembly.koin, ref, bytesWritten)
    }

    // ─── *Index now* continued (`ios-on-device-inference.md` §15, iOS 26+) ──────────────────────

    /**
     * Registers Swift's `BGTaskScheduler` driver. Once, at app launch, and only on iOS 26+ with
     * [modelDownloadsOffered] (the build flag and the static gates): with the flag off nothing
     * calls this, and every press is the foreground pass (§14.19).
     *
     * ⚠⚠ **Not declined** (`ios-shared-store-sqlite.md` B.23, `ios-on-device-inference.md`
     * §15.4 Q1): the launched task takes the shared store's **task token**
     * ([SharedReplicaEntries.protectTask]), whose assertion is the task itself, and ends it before
     * the task is completed.
     */
    fun installIndexNowContinuation(bridge: IndexNowContinuationBridge) {
        PhotosIndexNow.install(scheduler = { identifier -> bridge.submit(identifier, PhotosIndexNowTask.TITLE, PhotosIndexNowTask.SUBTITLE) })
    }

    /** Swift → Kotlin: the submission of [identifier] was answered. ⚠ Any thread, any order. */
    fun onIndexNowTaskAnswered(identifier: String, accepted: Boolean) {
        continuedIndexing()?.answered(identifier, accepted)
    }

    /** The store's protection for a launched task: [SharedReplicaEntries.protectTask]. A test seam. */
    internal var taskProtection: (reason: String) -> SharedReplicaTaskProtection = SharedReplicaEntries::protectTask

    /** Each live task's hold on the shared store, by identifier: what its expiry finds. */
    private val taskHolds = HashMap<String, SharedReplicaTaskProtection>()
    private val taskHoldsLock = platform.Foundation.NSRecursiveLock()

    private fun <T> holds(block: HashMap<String, SharedReplicaTaskProtection>.() -> T): T {
        taskHoldsLock.lock()
        try {
            return taskHolds.block()
        } finally {
            taskHoldsLock.unlock()
        }
    }

    /**
     * Swift → Kotlin, from the task's launch handler. ⚠⚠ Every task handed here is completed exactly
     * once: by the pass's outcome, or at once (with `false`) when no installed graph asked for it.
     *
     * ⚠⚠ **The store's task token first** (B.23): taken before the claim goes live, so the pass the
     * task carries in the background is under protection whose assertion is the task, and
     * ended inside the completion, **before** `setTaskCompleted`. No token (none could be had):
     * the request is treated as refused and the pass stays the foreground pass.
     */
    fun onIndexNowTaskLaunched(identifier: String, task: IndexNowContinuedTask) {
        val bridge: ContinuedIndexing = continuedIndexing() ?: run {
            println("PhotosLocalRuntime -> a continued task with no runtime to own it ($identifier): completed")
            task.complete(false)
            return
        }
        if (bridge.identifier() != identifier) {
            // Not the claim's (refused meanwhile, out of time, another graph's): the bridge completes
            // it at once, and it carries no pass, so it takes no token.
            bridge.launched(identifier, plainTask(task))
            return
        }
        val hold = taskProtection("photos-index-now")
        // Registered before the token is taken: an expiry from here on is kept (Swift sets the
        // expirationHandler before it calls this).
        holds { put(identifier, hold) }
        if (!hold.begin()) {
            holds { remove(identifier) }
            bridge.answered(identifier, accepted = false)
            task.complete(false)
            return
        }
        val adapted = object : ContinuedTask {
            override fun progress(done: Long, total: Long) = task.progress(done, total)

            // ⚠⚠ The token ends, then the task is completed (ContinuedIndexing calls this once).
            override fun complete(success: Boolean) {
                // ⚠⚠ The hold stays findable until its token has ended: an
                // expiry during this release must find it, turn the release into an expiry and
                // wait for it, rather than return with the drain still running.
                hold.finish {
                    holds { remove(identifier) }
                    task.complete(success)
                }
            }
        }
        bridge.launched(identifier, adapted)
    }

    private fun plainTask(task: IndexNowContinuedTask): ContinuedTask = object : ContinuedTask {
        override fun progress(done: Long, total: Long) = task.progress(done, total)
        override fun complete(success: Boolean) = task.complete(success)
    }

    /**
     * Swift → Kotlin, from the task's `expirationHandler`. ⚠⚠ The order (B.23): the token's end is
     * made an expiry; the owner cancels the pass (at its next photo) and completes the task, whose
     * completion first expires the token — B.15.3's interrupt and its 2 s drain — and only then
     * calls `setTaskCompleted`; the token is then ended in any case, before this returns.
     */
    fun onIndexNowTaskExpired(identifier: String) {
        val hold: SharedReplicaTaskProtection? = holds { get(identifier) }
        hold?.expire()
        continuedIndexing()?.expired(identifier)
        hold?.endToken()
        holds { remove(identifier) }
    }

    // ─── The processing wake (`ios-on-device-inference.md` PR-6, §16) ─────────────────────────────

    /** What Swift registers at launch, submits and finds in `BGTaskSchedulerPermittedIdentifiers`. */
    fun processingWakeIdentifier(): String = PhotosProcessingWakeTask.IDENTIFIER

    /**
     * Registers Swift's `BGTaskScheduler` driver for the wake. Once, at app launch, and only with
     * [modelDownloadsOffered] (the build flag and the static gates): with the flag off nothing is
     * ever submitted, and a wake that comes anyway is completed at once ([runProcessingWake]).
     */
    fun installProcessingWake(bridge: ProcessingWakeBridge) {
        PhotosProcessingWake.install(
            object : WakeScheduler {
                // ⚠ Not journalled here: the submission is Swift's and asynchronous, and a refusal
                // (Background App Refresh off, the simulator) must not read as *armed*. Swift says
                // what it got through [onProcessingWakeSubmitAnswered].
                override fun submit() = bridge.submit(
                    PhotosProcessingWakeTask.IDENTIFIER,
                    requiresExternalPower = PhotosProcessingWakeTask.REQUIRES_EXTERNAL_POWER,
                    requiresNetworkConnectivity = PhotosProcessingWakeTask.REQUIRES_NETWORK_CONNECTIVITY,
                )

                override fun withdraw() {
                    bridge.withdraw(PhotosProcessingWakeTask.IDENTIFIER)
                    journal("withdrawn")
                }
            },
        )
    }

    /** Swift → Kotlin: what `BGTaskScheduler.submit` answered, for the journal. */
    fun onProcessingWakeSubmitAnswered(accepted: Boolean, detail: String?) {
        journal(if (accepted) "armed" else "submit refused: ${detail ?: "unknown"}")
    }

    /**
     * The wake's journal, one line per arm, launch and end, for the device runbook's three nights
     * (`how-to/photos-processing-wake.md`): nothing is attached to a phone overnight, so stdout is
     * lost. ⚠ In Photos' private inference root, never the App Group; kept small
     * by dropping its older half ([ProcessingWakeJournal]). A test's root is the assembly's.
     */
    internal fun journal(event: String) {
        println("[photos-wake] $event")
        val root: String = installed?.inferenceRoot ?: return
        ProcessingWakeJournal.append(root, event)
    }

    /**
     * One wake's place, by the id Swift gave it: whether it expired, its hold on the store, and the
     * [ProcessingWake] that carries it once it runs. ⚠ Created only by [onProcessingWakeBegan] (or a
     * run with none), removed at the completion: an expiry never creates one.
     */
    private class WakeEntry {
        var expired = false
        var hold: SharedReplicaTaskProtection? = null
        var wake: ProcessingWake? = null
    }

    private val wakes = HashMap<String, WakeEntry>()

    private fun <T> wakeEntries(block: HashMap<String, WakeEntry>.() -> T): T {
        taskHoldsLock.lock()
        try {
            return wakes.block()
        } finally {
            taskHoldsLock.unlock()
        }
    }

    /** How many wakes the runtime holds a place for. A test's: none is left behind. */
    internal fun wakeEntryCount(): Int = wakeEntries { size }

    /** A test seam: run after the wake is marked running and before it is launched. Null in production. */
    internal var beforeWakeLaunchForTests: (() -> Unit)? = null

    /** [ProcessingWakeTask] completed once, whichever path reaches it first. */
    private class OnceProcessingTask(private val task: ProcessingWakeTask) {
        private val done = kotlin.concurrent.AtomicInt(0)
        fun complete(success: Boolean) {
            if (done.compareAndSet(0, 1)) task.complete(success)
        }
    }

    /**
     * Swift → Kotlin, **first** in the wake's launch handler, before its `expirationHandler` is set:
     * the wake's place, so an expiry that comes before [runProcessingWake] is kept, and an expiry
     * that comes after the completion (none should) finds nothing and leaves nothing behind.
     */
    fun onProcessingWakeBegan(wakeId: String) {
        wakeEntries { getOrPut(wakeId) { WakeEntry() } }
    }

    /**
     * Swift → Kotlin, from the wake's launch handler, **off the main thread and after Swift installed
     * the runtime on it** (a background launch builds no view, so nothing else would). ⚠⚠ Every task
     * handed here is completed exactly once: by the wake's terminal outcome, or at once (`false`) when
     * no installed graph can carry it, the store's token cannot be had, or [wakeId] already expired.
     *
     * ⚠⚠ **The store's task token first** (`ios-shared-store-sqlite.md` B.23.3): taken before the wake
     * does anything, its assertion the task itself, and ended inside the completion, **before**
     * `setTaskCompleted`. Never an entry's `withProtection`, whose `beginBackgroundTask` iOS ends
     * within seconds whatever task keeps the app running. ⚠ Blocks while the token is taken.
     *
     * @param wakeId a fresh id per launch, the same one [onProcessingWakeExpired] is called with.
     */
    fun runProcessingWake(wakeId: String, task: ProcessingWakeTask) {
        val once = OnceProcessingTask(task)
        val expiredFirst: Boolean = wakeEntries { getOrPut(wakeId) { WakeEntry() }.expired }
        val assembly: Assembly? = installed?.takeIf { it.onDeviceAi }
        val wake: ProcessingWake? = assembly?.koin?.let { koin -> runCatching { koin.get<ProcessingWake>() }.getOrNull() }
        if (expiredFirst || wake == null) {
            println("[photos-wake] nothing to carry it (expired=$expiredFirst installed=${assembly != null}): completed")
            wakeEntries { remove(wakeId) }
            once.complete(false)
            return
        }
        val hold: SharedReplicaTaskProtection = taskProtection("photos-processing-wake")
        // Registered before the token is taken: an expiry from here on finds it.
        val expiredMeanwhile: Boolean = wakeEntries {
            val entry = getOrPut(wakeId) { WakeEntry() }
            entry.hold = hold
            entry.expired
        }
        if (expiredMeanwhile || !hold.begin()) {
            println("[photos-wake] no token for it (expired=$expiredMeanwhile): completed")
            hold.finish {
                wakeEntries { remove(wakeId) }
                once.complete(false)
            }
            return
        }
        // ⚠ The wake this entry's expiry tells, held here: never re-resolved from whatever graph
        // is installed when the expiry comes.
        wakeEntries { getOrPut(wakeId) { WakeEntry() }.wake = wake }
        val checking: Job? = assembly.checking
        val began: kotlin.time.Instant = kotlin.time.Clock.System.now()
        PhotosProcessingWake.lastPass = null
        journal("launched")
        beforeWakeLaunchForTests?.invoke()
        wake.launched(
            object : WakeTask {
                // ⚠⚠ The token ends, then the task is completed (the wake calls this once). The
                // entry stays findable until the token has ended: an expiry during this release
                // turns it into an expiry and waits for it.
                override fun complete(success: Boolean) {
                    val minutes: Double = (kotlin.time.Clock.System.now() - began).inWholeSeconds / 60.0
                    val expired: Boolean = wakeEntries { this[wakeId]?.expired == true }
                    journal("ended minutes=${(minutes * 10).toLong() / 10.0} success=$success expired=$expired ${PhotosProcessingWake.lastPass ?: "nopass"}")
                    hold.finish {
                        wakeEntries { remove(wakeId) }
                        once.complete(success)
                    }
                }
            },
            // ⚠ Until the store check has ended, no backend serves and nothing would be asked.
            prepare = {
                val waited: kotlin.time.Instant = kotlin.time.Clock.System.now()
                checking?.join()
                // 📱 How long the launch's store check held the wake (§16.6).
                journal("checked ms=${(kotlin.time.Clock.System.now() - waited).inWholeMilliseconds}")
            },
        )
        // ⚠⚠ Read AFTER the launch: an expiry anywhere before it (the journal's write, the seam)
        // found no wake current, and its token has ended — a wake launched past it would park at
        // the closed gate and never complete. `expired()` is idempotent.
        if (wakeEntries { this[wakeId]?.expired == true }) wake.expired()
    }

    /**
     * Swift → Kotlin, from the wake's `expirationHandler`. ⚠⚠ The order (B.23): the token's end is
     * made an expiry; the wake is told (the scheduled bar says no at once, the pass is cancelled at its
     * next photo, and the task is completed with `false` once it has stopped); then the token is ended
     * as a backstop — B.15.3's interrupt and its 2 s drain — before this returns.
     * ⚠ An expiry for a wake the runtime holds no place for (completed already) does nothing.
     */
    fun onProcessingWakeExpired(wakeId: String) {
        val entry: WakeEntry = wakeEntries { this[wakeId]?.also { it.expired = true } } ?: run {
            println("[photos-wake] an expiry for a wake that is not held ($wakeId)")
            return
        }
        val (hold: SharedReplicaTaskProtection?, wake: ProcessingWake?) = wakeEntries { entry.hold to entry.wake }
        println("[photos-wake] expired (running=${wake != null})")
        hold?.expire()
        wake?.expired()
        hold?.endToken()
        // ⚠ After the token has ended: the journal's write is no part of the handler's budget before it.
        journal("expired (running=${wake != null})")
    }

    private fun continuedIndexing(): ContinuedIndexing? =
        installed?.takeIf { it.onDeviceAi }?.koin?.let { koin -> runCatching { koin.get<ContinuedIndexing>() }.getOrNull() }

    /** Whether Photos still holds adopted custody at rest — the deprovision trigger. */
    fun hasResidualCustody(): Boolean {
        installed?.let { return runtime.hasResidualCustody(it.koin.get<SecureStorageDelegate>()) }
        return runtime.hasResidualCustody(runtime.standaloneDelegate())
    }
}
