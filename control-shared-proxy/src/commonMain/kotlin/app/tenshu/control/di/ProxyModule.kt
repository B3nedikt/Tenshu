package app.tenshu.control.di

import app.tenshu.control.data.AppRegistry
import app.tenshu.control.data.BundledAppIdentityResolver
import app.tenshu.control.data.BundledAppManifestSource
import app.tenshu.control.data.LandingStateRepository
import app.tenshu.control.data.TenshuAppRegistry
import org.tenshu.core.app.AppIdentityResolver
import org.tenshu.core.app.AppManifestSource
import app.tenshu.control.data.RealmDataClient
import app.tenshu.control.data.QuotaSoftLimitPreferences
import app.tenshu.control.data.RecoveryAffordancePreferences
import app.tenshu.control.data.ErrorEventsManager
import app.tenshu.control.data.BackgroundIngestManager
import app.tenshu.control.data.ShareManager
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.data.TenshuBackgroundIngestManager
import app.tenshu.control.data.TenshuErrorEventsManager
import app.tenshu.control.data.TenshuShareManager
import app.tenshu.control.data.TenshuOfflineMapsSuggestionPreferences
import app.tenshu.control.data.TenshuQuotaSoftLimitPreferences
import app.tenshu.control.data.TenshuRecoveryAffordancePreferences
import app.tenshu.control.data.TenshuLandingStateRepository
import app.tenshu.control.data.TenshuSyncRepository
import app.tenshu.control.notifications.PendingRequestsRepository
import app.tenshu.control.notifications.TenshuPendingRequestsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.qualifier.named
import org.tenshu.core.common.app.SchemaAnnouncer
import org.tenshu.core.common.proxy.SchemaRegistry
import org.koin.dsl.bind
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.module
import org.tenshu.core.Data
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.proxy.CallstackMaterializer
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.Database
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.LocalClientConnection
import org.tenshu.core.common.proxy.SessionAwareLocalTaskExecutor
import org.tenshu.core.common.proxy.forwardStreamElementsInOrder
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.app.proxy.PeerAttestationProbe
import org.tenshu.core.common.app.proxy.appRegistrationTrigger
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.db.DatabaseImpl
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.core.trait.TraitResolver
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.identity.client.identityServiceClientModule
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.ingest.client.ingestServiceClientModule
import org.tenshu.ingest.common.ingestServiceCommonModule
import org.tenshu.notifications.client.notificationServiceClientModule
import org.tenshu.notifications.common.notificationServiceCommonModule
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.proxy.DefaultEmbeddingStore
import org.tenshu.inference.proxy.DefaultFaceTemplateStore
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.proxy.FaceClustering
import org.tenshu.inference.proxy.FaceGroupingComponents
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.IndexProgressHolder
import org.tenshu.inference.proxy.InferenceStoreGate
import app.tenshu.control.inference.IndexCoverageSource
import app.tenshu.control.inference.IndexProgressSource
import app.tenshu.control.inference.HolderIndexProgressSource
import app.tenshu.control.inference.LastIndexCoverage
import app.tenshu.control.inference.recordPass
import app.tenshu.control.inference.ModelStoreAccess
import app.tenshu.control.inference.ModelStoreAccessSource
import app.tenshu.control.inference.modelStoreAccessOf
import org.tenshu.inference.proxy.MediaIndexer
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.LivePaths
import org.tenshu.inference.proxy.MediaSource
import org.tenshu.inference.proxy.PassExclusion
import org.tenshu.file.proxy.MeteredNetworkSignal
import org.tenshu.inference.proxy.catalog.MODEL_CATALOG_PUBLIC_KEY_HEX
import org.tenshu.inference.proxy.catalog.ModelDeliveryComponents
import org.tenshu.inference.proxy.catalog.ModelDownloaderFactory
import org.tenshu.inference.proxy.catalog.ModelStoreChanges
import org.tenshu.inference.proxy.catalog.ModelIndexStorage
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.catalog.ModelStoreOwnership
import org.tenshu.inference.proxy.catalog.modelDeliveryComponents
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import org.tenshu.mapregion.common.mapRegionServiceCommonModule
import org.tenshu.mapregion.proxy.MapRegionProxyComponents
import org.tenshu.mapregion.proxy.mapRegionComponents
import org.tenshu.sync.common.SyncManager
import org.tenshu.logging.client.loggingServiceClientModule
import org.tenshu.logging.common.loggingServiceCommonModule
import org.tenshu.mia.client.miaServiceClientModule
import org.tenshu.mia.common.miaServiceCommonModule
import org.tenshu.home.device.client.homeDeviceServiceClientModule
import org.tenshu.home.device.common.homeDeviceServiceCommonModule
import org.tenshu.routine.client.routineServiceClientModule
import org.tenshu.routine.common.routineServiceCommonModule
import org.tenshu.hub.management.client.hubManagementServiceClientModule
import org.tenshu.hub.management.common.hubManagementServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.storage.client.storageServiceClientModule
import org.tenshu.storage.common.storageServiceCommonModule
import org.tenshu.storage.proxy.StorageProvider
import org.tenshu.storage.proxy.EntitlementStorageProvider

val proxyModule = buildProxyModule()

/**
 * Every schema module the proxy registers — extracted from the Koin `single` so it can be
 * **asserted over without a Koin application** (`ProxySchemaCoverageTest`).
 *
 * ⚠⚠ This list is where a service actually ships. Calendar was absent until CA-5c and never worked
 * on a device; the whole inference track was absent through four green slices for the same reason.
 * Both had passing tests throughout, because every one of them built its own `TestProxy` registry.
 */
fun coreSchemaConfigurations(): List<ModuleConfiguration> = listOf(
            fileServiceCommonModule,
            fileServiceClientModule,
            notificationServiceCommonModule,
            notificationServiceClientModule,
            timelineServiceCommonModule,
            timelineServiceClientModule,
            healthServiceCommonModule,
            healthServiceClientModule,
            placeServiceCommonModule,
            placeServiceClientModule,
            mapRegionServiceCommonModule,
            mapRegionServiceClientModule,
            ingestServiceCommonModule,
            ingestServiceClientModule,
            identityServiceCommonModule,
            identityServiceClientModule,
            syncServiceCommonModule,
            syncServiceClientModule,
            preferenceServiceCommonModule,
            preferenceServiceClientModule,
            hubManagementServiceCommonModule,
            hubManagementServiceClientModule,
            homeDeviceServiceCommonModule,
            homeDeviceServiceClientModule,
            // Mia's query layer (mia-query-layer.md MQ-1). Both halves, for the
            // reason `miaServiceCommonModule` spells out: the client module builds
            // the callstack, the common module is what lets the PROXY build the
            // answer composites it returns.
            miaServiceCommonModule,
            miaServiceClientModule,
            routineServiceCommonModule,
            routineServiceClientModule,
            storageServiceCommonModule,
            storageServiceClientModule,
            loggingServiceCommonModule,
            loggingServiceClientModule,
            contactServiceCommonModule,
            contactServiceClientModule,
            // ⚠⚠ Absent until CA-5c, which is why Calendar never worked on a
            // device: without the schema here the proxy cannot materialise an
            // `Event`, and without the client module it cannot build the ops'
            // request objects. Every calendar test used its own `TestProxy`
            // registry, so nothing failed until something ran for real.
            calendarServiceCommonModule,
            calendarServiceClientModule,
            // PH-2 (`photos-app.md`): albums, ordering and flags. ⚠⚠ Added in the slice that
            // created the service, because the two omissions recorded here — Calendar's, which
            // cost four phases, and inference's, which cost four slices — were both invisible
            // until something ran for real. `inference-service.md` §17 states the rule: a service
            // is shipped when it is in THIS list, not when its tests are green.
            photoServiceCommonModule,
            photoServiceClientModule,
            // ⚠⚠ The SAME omission as Calendar's above, and it lasted four slices. IN-3a wrote
            // `MediaLabel` rows, IN-4a searched them and IN-4b added an op — all green, all
            // unreachable, because without the schema here the proxy cannot materialise a label
            // and without the client module it cannot build the ops' request objects. Every
            // inference test used its own `TestProxy` registry, so nothing failed until something
            // ran for real. ⚠ The general rule is in `inference-service.md` §17: a service is not
            // shipped when its tests are green, it is shipped when it is in this list.
            inferenceServiceCommonModule,
            inferenceServiceClientModule,
)

/**
 * A FRESH proxy-module instance. Koin stores singleton instances on the factory objects held
 * inside a [org.koin.core.module.Module], so reusing a module `val` across two
 * `koinApplication`s silently hands the second app the first app's singletons. The app boots
 * (one runtime per process) keep using [proxyModule]; anything that assembles more than one
 * runtime per process — the route-1 `DriveLocalRuntime` and its tests
 * (ios-drive-parity.md P1c) — must build fresh instances through this.
 */
/** ⚠ A logical prefix under the `FileDatabase` root, like `"/regions"` and `"/models"`. */
internal const val INFERENCE_EMBEDDINGS_PATH = "/inference/embeddings.log"

/** IN-D6a's arrivals queue — device-local, a few hundred lines at most. */
internal const val INFERENCE_ARRIVALS_PATH = "/inference/arrivals.queue"

/**
 * IN-6's face templates — a SEPARATE log from the embeddings one.
 *
 * ⚠⚠ Separate because they are separate assets with separate consent (IN-D3) and separate
 * deletion: switching People off must be able to drop these without touching semantic search's
 * vectors, and one file holding both makes that impossible to do without a rewrite.
 */
internal const val INFERENCE_FACES_PATH = "/inference/faces.log"

/** The qualifier of the `FileDatabase` this process's inference state lives in. */
const val INFERENCE_FILE_DATABASE = "inferenceFileDatabase"

/** A scope of this graph's own for [owner], a child of its [ProxyGraphJob] (Q45). */
private fun org.koin.core.scope.Scope.graphScope(
    owner: String,
    context: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
): CoroutineScope = get<ProxyGraphJob>(named(PROXY_GRAPH_JOB)).scope(owner, context)

fun buildProxyModule(): org.koin.core.module.Module = module {
    includes(coreCommonModule())

    // ⚠⚠ Q45 (`ios-shared-store-sqlite.md` B.16.8): the graph's root job. Every scope a definition
    // below makes is a child of it (`graphScope`), and the graph's Koin close cancels it, so a
    // closed graph's downloads, passes and session work stop with it. ⚠ A scope made here as
    // `CoroutineScope(SupervisorJob() + …)` is the bug this closes: it outlives a rebuilt graph.
    single(named(PROXY_GRAPH_JOB)) { ProxyGraphJob() } withOptions { onClose { it?.cancel() } }

    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
        coreSchemaConfigurations() +
            (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
    }

    // Where this process's inference state lives: the vector and template logs, the model bytes.
    // The shared `FileDatabase` everywhere but one graph. ⚠ On iOS route 1 that database is the
    // App Group's and every first-party app runs this module, so the one app that owns inference
    // there (Photos) overrides this with a database in its own container, and every other iOS
    // graph binds stores that hold no database at all (`ios-on-device-inference.md` §3.1).
    single<FileDatabase>(named(INFERENCE_FILE_DATABASE)) { get<FileDatabase>() }

    // Whether the stores below may be read or written right now. Always, by default: this
    // process's own realm-wipe hook clears them. ⚠ `get`, never `getOrNull`, at both call sites —
    // the iOS owner overrides this with a realm-stamp check, and a gate that failed to build must
    // fail the pass, not open it.
    single<InferenceStoreGate> { InferenceStoreGate.Unstamped }

    // IN-4b's vector sink — device-local, never synced (IN-D5), and a SINGLE instance because the
    // `photosLike` op reads what `MediaIndexer` writes. Two would give a search that finds nothing
    // while indexing reports success, with no error on either side.
    single<EmbeddingStore> { DefaultEmbeddingStore(get(named(INFERENCE_FILE_DATABASE)), INFERENCE_EMBEDDINGS_PATH) }

    // IN-6 F3's template sink — device-local and never synced, for a stronger reason than the
    // vectors above: a face template is biometric data, and IN-D5 keeps it off the wire by making
    // the synced artifact a name the user typed instead. A SINGLE instance because `faceGroups`
    // reads what `MediaIndexer` writes; two would give an empty People screen beside an index pass
    // that reports faces found, with no error on either side.
    single<FaceTemplateStore> { DefaultFaceTemplateStore(get(named(INFERENCE_FILE_DATABASE)), INFERENCE_FACES_PATH) }
    // IN-D6a: the photographs that arrived and no batch has finished with — device-local, beside the
    // other derived stores. ⚠ A `single`: the observer that fills it and the batch that drains it
    // must hold one queue.
    single { org.tenshu.inference.proxy.ArrivalQueue(get(named(INFERENCE_FILE_DATABASE)), INFERENCE_ARRIVALS_PATH) }

    // ⭐⭐ The MEASURED operating point (F2m, `photos-people-and-pets.md` §12.6) — 0.44 with
    // `minFaces = 2`, the threshold the one human-verified run was produced at. Its provenance and
    // the reason it sits at the high end of the safe band live on `FaceClustering.Measured`, which
    // is the one place to read before changing it.
    //
    // ⚠⚠ This replaced `Uncalibrated` on 2026-09-24 and is the line that decides whether People
    // shows anything at all: `clusterFaces` answers `Uncalibrated` with an empty list, so while it
    // stood here every device answered `UNCALIBRATED` however good the model was. ⚠ The threshold
    // describes THIS checkpoint's geometry — a different embedder needs its own measurement, not a
    // carried-over number.
    //
    // ⚠⚠ `livePaths` is P0f-b PR-1's read filter: People clusters only templates whose photograph
    // is still a live, untrashed `File`, so a delete or a trash holds on the very next read with no
    // pass. ⚠ The SAME predicate the media source below hands the reconcile, and the factory gives
    // `photosLike` this one too (one realm, one answer). `MediaLibraryWiringTest` fails if it goes.
    single { FaceGroupingComponents(store = get(), clustering = FaceClustering.Measured, livePaths = get()) }

    // The realm half of the read filter and of the reconcile's second proof (`MediaLibrarySeams`).
    single<LivePaths> { fileLivePaths }

    // IN-3b: the real runtime, supplied per platform because only two of the four can execute one.
    // Android and desktop contribute `OnnxInferenceBackend`; iOS and web contribute nothing and
    // therefore keep exactly today's behaviour — `photosLike` answers `NO_BACKEND` and an index
    // pass is a counted no-op, which is the honest state of the feature where no model can run.
    //
    // ⚠⚠ Still never `FakeInferenceBackend`. It exists to make the consumer stack provable with no
    // model; putting it here would make every device claim it can search photos and answer with
    // canned nonsense. Fakes are opt-in, never a default — the Control-on-iOS rule, one track over.
    //
    // ⚠ The `named` indirection rather than a per-platform override: it is the same shape as
    // `platformSchemaConfigs` above, and a Koin override would make the *order* of module
    // installation decide whether this device can search photos.
    single {
        InferenceBackendRegistry(
            getOrNull<List<InferenceBackend>>(named("inferenceBackends")) ?: emptyList(),
        )
    }

    // IN-2b's store and catalog fetch, wired (§19.2 step 1b). Mirrors the map-region components
    // above: the proxy owns egress, the download is streamed and resumable, and the bytes land only
    // after their sha256 matches the SIGNED catalog's figure.
    //
    // ⚠⚠ The base URL is OUR name — the R2 custom domain in deploy/cloudflare-models — never a
    // provider's. It is compiled in, so a provider URL here would make every later move an app
    // release; on our own name it is a DNS change (the tiles precedent, `tilesHostUrl` below).
    // ⚠ Until the first publish it answers 404, and the Models screen says it cannot reach the list —
    // the same sentence a blank URL produced.
    //
    // ⚠ Nothing fetches on its own: IN-D3 keeps inference off by default and IN-D10 requires the user
    // to see what a model costs first, so the trigger is the Models screen and never a first-launch
    // fetch.
    single(named("modelCatalogUrl")) { "https://models.service.tenshu.app" }
    single(named("modelCatalogPublicKeyHex")) { MODEL_CATALOG_PUBLIC_KEY_HEX }

    single {
        modelDeliveryComponents(
            httpClient = get(),
            storageDelegate = get(),
            fileDatabase = get(named(INFERENCE_FILE_DATABASE)),
            // ⚠ Absent means the delegate above. The iOS owner keeps the index beside the bytes,
            // so a reinstall cannot report a model whose bytes went with the container.
            indexStorage = getOrNull<ModelIndexStorage>(),
            ed25519 = get(),
            scope = graphScope(ProxyGraphJob.Owners.MODEL_DELIVERY),
            backgroundLiveness = get(),
            catalogBaseUrl = get(named("modelCatalogUrl")),
            catalogPublicKeyHex = get(named("modelCatalogPublicKeyHex")),
            // ⚠⚠ Contributed per platform, like `inferenceBackends` above, and ABSENT means
            // EMPTY — a build with no runtime offers no models rather than offering ~383 MB it
            // could never execute (IN-D13). Web contributes nothing on purpose, and so does iOS
            // but for ONE graph: Photos', behind its build flag, contributes `coreml` together
            // with the downloader that may carry it (`PhotosOnDeviceAi`, below).
            supportedRuntimes = getOrNull<Set<String>>(named("supportedModelRuntimes")) ?: emptySet(),
            // ⚠⚠ ABSENT means "one process by platform construction", which is a claim about
            // the platform and holds for exactly two: Android (only Control loads this module, and
            // its manifest declares no `android:process`) and iOS (only Photos' graph is offered a
            // model, and its store is in Photos' own container).
            // Desktop can run two Controls and MUST contribute its file lock —
            // `ModelStoreOwnershipWiringTest` fails if it stops.
            storeOwnership = getOrNull<ModelStoreOwnership>() ?: ModelStoreOwnership.SoleProcess,
            // ⚠⚠ ABSENT on every platform but the iOS owner, which contributes both TOGETHER
            // with its runtime: there the in-process fetch may not carry a model (it cannot be
            // held to Wi-Fi), and a commit or a removal has to stand the old generation down first
            // (`ios-on-device-inference.md` §14.11).
            downloaderFactory = getOrNull<ModelDownloaderFactory>(),
            storeChanges = getOrNull<ModelStoreChanges>() ?: ModelStoreChanges.Direct,
        )
    }

    // The store on its own, so a backend can resolve installed weights without reaching through the
    // whole component bundle.
    single<ModelStore> { get<ModelDeliveryComponents>().store }

    // IN-D6 (1e) — the index pass, and `MediaIndexer`'s FIRST production call site. It was built in
    // IN-3a and constructed only in tests for five slices, which is why a device with a model,
    // consent and a library still indexed nothing.
    //
    // ⚠⚠ `embeddings = get()` is the SAME `EmbeddingStore` singleton `photosLike` reads. Two
    // instances would give a search that finds nothing while indexing reports success, with no
    // error on either side — the failure `MediaIndexer`'s own KDoc calls out.
    // IN-D6's visible half (1e-2). ⚠ A `single`, so the pass that writes it and whatever reads it
    // are looking at one holder — two would give a bar that never moves beside a pass that is
    // working perfectly.
    single { IndexProgressHolder() }
    // ⚠ The Models screen reads that same holder in-process rather than through the bounded
    // `IndexProgress` op, so a pass that starts while it is open still shows (`IndexProgressSource`).
    // The repository asks for it with `get`, not `getOrNull`: unbound fails loudly, never inert.
    single<IndexProgressSource> { HolderIndexProgressSource(get()) }

    single {
        // ⚠ A provider, read per pass: the id changes in this process on "Forget this device"
        // (`SyncManagerImpl.forgetThisDevice` re-mints A4), and consent / pause rows are keyed on it.
        val syncManager = get<SyncManager>()
        MediaIndexer(
            registry = get(),
            deviceId = { syncManager.currentDeviceId },
            embeddings = get(),
            progress = get(),
            // IN-6 F3. ⚠⚠ The SAME `FaceTemplateStore` singleton `faceGroups` reads, for the
            // reason `embeddings` gives. ⚠ Passing it is what makes the face pass run at all —
            // `MediaIndexer` defaults it to null and then counts faces as skipped, which reads
            // exactly like a library with no faces in it.
            faces = get(),
            // ⚠⚠ Contributed per platform and ABSENT means NEVER. The drain loop does not enforce the
            // pass's charging/idle constraints, so this is the only thing between opening Control and
            // a multi-hour index run on battery (inference-service.md §32).
            conditions = getOrNull<IndexingConditions>(named("indexingConditions")) ?: IndexingConditions.Never,
        )
    }

    // §34 — the ONE thing that runs a pass on this device, so the scheduled start and the manual
    // one cannot overlap. ⚠⚠ A `single`: two runners are two single-flights, which is none. ⚠ Its
    // scope is the graph's (process-lifetime but for an iOS rebuild, Q45), never a request's, for
    // the same reason the model downloader's is — a manual pass outlives the IPC call that started
    // it — and it raises `BackgroundLiveness` while it works, or
    // Android reaps the proxy the moment the last client unbinds.
    single {
        MediaIndexRunner(
            indexer = get(),
            source = get(),
            scope = graphScope(ProxyGraphJob.Owners.MEDIA_INDEX_RUNNER),
            liveness = get(),
            // ⚠⚠ The SAME holder the indexer ticks, and the runner publishes the pass into it BEFORE
            // the op returns — `IndexProgress` is a bounded stream, so a screen that subscribes while
            // the library walk is still running would be told "nothing is indexing" and end.
            progress = get(),
            // "Indexed N of M — K aren't on this device" (2026-09-27), for Control's Models screen.
            onPassFinished = { result ->
                get<LastIndexCoverage>().recordPass(result)
            },
            // ⚠ Resolved per pass, never here: where the gate is a custody, it is built over this
            // runner's own cancel.
            beforePass = { get<InferenceStoreGate>().open(fromPass = true) },
            // ⚠ Contributed by the iOS owner alone, the PROCESS's (`PhotosOnDeviceAi`): a graph
            // that replaces another in one process brings a runner of its own. Absent is none.
            exclusion = getOrNull<PassExclusion>() ?: PassExclusion.None,
        )
    }

    // ⚠ Bound under the read interface too: the Models repository lives in `:control-shared-logic`,
    // which cannot name either holder, and resolves both with `getOrNull` — unbound reads as absent.
    single { LastIndexCoverage() } bind IndexCoverageSource::class
    // The same answer the store was constructed with, for the Models screen to say up front.
    // ⚠ `refusal()` is decided once per ownership, so this cannot disagree with the store.
    // ⚠⚠ Categorical, not a Boolean: "another Control owns it" and "the lock could not be taken"
    // owe the user different sentences (`modelStoreAccessOf`).
    single<ModelStoreAccess> {
        modelStoreAccessOf((getOrNull<ModelStoreOwnership>() ?: ModelStoreOwnership.SoleProcess).refusal())
    } bind ModelStoreAccessSource::class

    // ⚠⚠ Composition across services is CONTROL's job, not a service's — see `MediaLibrarySeams`,
    // where the source is a named function so a test builds this one rather than a copy.
    //
    // ⚠⚠ P0f-b PR-1: the source reports a COMPLETE walk only when `walkImageLibrary` read the whole
    // tree, with the same `LivePaths` as the second proof, so the pass's reconcile (B1) erases a
    // deleted photograph's template and vector. Before, it overrode only `media()` and no pass in
    // production reconciled anything. `MediaLibraryWiringTest` fails if this stops being the source.
    single<MediaSource> {
        controlMediaSource(
            files = get<FileDatabase>(),
            // ⚠ Contributed per platform; absent means "cannot tell", which allows the pull.
            metered = getOrNull<MeteredNetworkSignal>(named("indexMeteredNetwork")),
            livePaths = get(),
        )
    }

    // Offline map-region delivery (maps-region-delivery.md D5/D6). Tile-host CDN base + the pinned
    // manifest signing key: the client-pinned trust anchor (§9 / D3) whose PRIVATE half signs every
    // published catalog (kept offline; `tiles-signer keygen`). RegionCatalogVerifier checks
    // catalog.json.sig against this via Ed25519Backend — rotating it means re-signing every build.
    // Overridable per platform / via env (mirrors syncRelayUrl).
    single(named("tilesHostUrl")) { org.tenshu.mapregion.proxy.TILES_HOST_URL }
    single(named("tilesManifestPublicKey")) { org.tenshu.mapregion.proxy.tilesManifestPublicKey() }

    // Auxiliary process-liveness (BackgroundLiveness, §4.6): the sync scheduler and a proxy-side map
    // region download each hold it under their own key while they have work; ProxyService (Android)
    // merges it with the in-flight RPC count to keep the foreground service up across long background
    // work. Bound here (once, all platforms) so the map components and the sync init share one instance;
    // harmless on desktop/iOS, which don't drive an FGS off it.
    single { org.tenshu.core.common.proxy.BackgroundLiveness() }

    // The proxy-side region components, built once from injected primitives (D6: the proxy owns
    // egress; map apps reach this over IPC). The platform implementation factory hands these to
    // mapRegionServiceProxyImplementationFactory. The download runs on the graph's scope (Q45) and
    // raises BackgroundLiveness for its duration, so it survives the app backgrounding (§7.1).
    single<MapRegionProxyComponents> {
        mapRegionComponents(
            httpClient = get(),
            storageDelegate = get(),
            fileDatabase = get(),
            ed25519 = get(),
            tilesHostUrl = get(named("tilesHostUrl")),
            manifestPublicKey = get(named("tilesManifestPublicKey")),
            scope = graphScope(ProxyGraphJob.Owners.MAP_REGION),
            backgroundLiveness = get(),
        )
    }

    single<TraitResolver> { TraitResolverImpl(get()) }

    single<Database> {
        DatabaseImpl(
            context = get(),
            // Platform-provided: SqliteObjectStore on android/desktop
            // (PlatformProxyModule), InMemoryObjectStore override in the
            // integration harnesses. Quick-swap = that one DI line.
            objectStore = get<ObjectStore>(),
            dbEntryMapper = DbEntryMapper(
                context = get(),
                schemaProvider = get(),
                dataFactory = get(),
                traitResolver = get()
            ),
            traitResolver = get(),
            lock = get<DatabaseLock>()
        )
    }

    single<OperationImplementationFactory> {
        val factory =
            get<(String) -> OperationImplementation>(named("platformImplementationFactory"))
        OperationImplementationFactory { id -> factory(id) }
    }

    single {
        ProxyGateway(
            clientRegistry = get(),
            parser = get(),
            serializer = get()
        )
    }

    // EPC server-push routing (ios-proxy-architecture.md §3c ops hookup): shared between the
    // pairing host (which registers a session's sealed-push fn) and the per-session
    // EpcClientConnection the connection factory mints for that client.
    single { app.tenshu.control.epc.EpcCallbackRouter() }

    single {
        ClientRegistry(
            database = get(),
            traitResolver = get(),
            implementationFactory = get(),
            materializer = CallstackMaterializer(get(), get()),
            pureOperationRunner = get(),
            baseContext = get(),
            // ⚠⚠ The handler is the backstop for **every** `sessionScope.launch` in the proxy,
            // because `mintSession` derives each session scope from this one's context and so
            // inherits it. Without it an uncaught throw in any fire-and-forget — a service init
            // hook, a long-lived row publisher, a detached background op — reaches the thread's
            // uncaught handler, which on Android kills Control. A best-effort background task
            // must not be able to take the proxy down with it.
            //
            // ⚠ This contains the *crash*, not the *loss*: a collector that throws is still dead
            // for the rest of the session, and its rows silently stop updating. Sites where that
            // matters have to handle it themselves — see `docs/tech-debt.md`, "Fire-and-forget
            // launches in proxy init". The named line is what makes the loss findable at all.
            // ⚠ A child of the graph's root job (Q45): every session scope and detached request
            // job is parented to this one's job, so they stop with the graph too.
            serviceScope = graphScope(
                ProxyGraphJob.Owners.SERVICE_SCOPE,
                Dispatchers.Default + CoroutineExceptionHandler { context, e ->
                    println("TenshuProxy -> uncaught in proxy coroutine [$context]: $e")
                },
            ),
            // ⚠⚠ P7 (`drive-upload-progress.md` §9) is built but **NOT enabled**, and the reason
            // is measured, not suspected: with `mintSystemSession = true` this module's own
            // integration suite fails ~2 tests per run — a DIFFERENT two each run — while the same
            // suite passes cleanly twice in a row with it off. The system session runs
            // `initialization` like any other, so Control ends up with a second copy of every
            // per-session subsystem (entitlement collectors, storage readers, notification
            // publishers; the scheduler itself is deduped by `SyncRuntimeHolder`), and that is
            // enough to tip the timing-sensitive integration tests over.
            //
            // Turning this on needs the platform initialisation split into a data-plane half (which
            // the system session genuinely needs — the sync runtime's ingest writes fire relation
            // maintainers registered PER CONTEXT) and a client-facing half (which it must not run).
            // That is a real refactor across three platform modules, and getting the split wrong
            // fails silently, so it is deliberately not bundled here.
            mintSystemSession = false,
            clientConnectionFactory = get<(String) -> ClientConnection>(named("platformClientConnectionFactory")),
            initialization = get<ExecutionContext.() -> Unit>(named("platformProxyInitialization")),
            // A4: the handshake-triggered RegisterApp writer (track-a-control-apps.md).
            // SyncManager is resolved lazily inside deviceIdProvider — and only for
            // an allowlisted clientId — so this never forces SyncManager at registry
            // construction. A realm that does not exist yet (currentOwnerId == null)
            // skips registration and a later re-bind retries; once a realm is active
            // the proxy's own device id is written under the canonical appId.
            onSessionEstablished = appRegistrationTrigger(
                appIdentityResolver = get(),
                deviceIdProvider = {
                    val syncManager = get<SyncManager>()
                    syncManager.currentOwnerId?.let { syncManager.currentDeviceId }
                },
                // Whether this client's identity was *attested* rather than merely accepted
                // (desktop-ipc-client-identity.md P3). Optional and resolved per session, so
                // a platform that makes no such claim simply reports false — never an
                // over-statement. Desktop binds it to the peer-credential gate; Android has
                // OS-attested identity but has not yet made the claim explicitly.
                verifiedProvider = { clientId ->
                    getOrNull<PeerAttestationProbe>()?.isAttested(clientId) == true
                },
            )
        )
    }

    single<AsyncTaskExecutor> {
        val clientRegistry: ClientRegistry = get()
        val clientId = get<String>(named("platformPackageName"))
        // The scope the per-session stream listener delivers on. Bindable by name so a test can
        // put a forcing dispatcher under the production listener
        // (`FileDetailsDeferredPreviewForcedInterleavingTest`); nothing in production binds it.
        val scope = getOrNull<CoroutineScope>(named("clientStreamScope"))
            ?: graphScope(ProxyGraphJob.Owners.CLIENT_STREAM)

        // Must be a CancellableLocalTaskExecutor, NOT a bare AsyncTaskExecutor:
        // `DefaultInteractorProvider.buildCancellableTaskInteractor` routes a
        // BackgroundOperation (e.g. AwaitPairing) through `executeAsyncCancellable`
        // only when the executor implements this interface. A bare
        // AsyncTaskExecutor falls through to `executeAsync`, which runs the op
        // via the legacy detached path (`sessionScope.launch` + immediate
        // `Success(null)`) — so `awaitPairing().await()` returns instantly and
        // the pairing wizard shows "Pairing timed out" the moment it starts,
        // while the real handshake loop completes fire-and-forget in the
        // background. See `CancellableLocalTaskExecutor` KDoc.
        //
        // Session-aware, NOT a `by lazy` LocalTaskExecutor: a lazy delegate pins
        // the first session's ExecutionContext for the process lifetime, so a
        // proxy teardown that outlives the process (Android's
        // `ProxyService.onDestroy` → `quiesceSessionsAndJoin()` when the OS destroys
        // the bound-only service) left the UI executing every op against a cancelled
        // sessionScope — silent CancellationException, spinners hung forever
        // (the "Registering your realm" incident). The adoption hook re-wires
        // the per-session stream listener each time a fresh session is minted
        // (quiesce cleared the old connection's listeners).
        // Resolved lazily (first adoption = first op): the dispatcher's graph
        // reaches back to this AsyncTaskExecutor, so an eager get() here is a
        // circular resolution (StackOverflowError at startKoin). Same deferral
        // the old `by lazy` block relied on.
        val koin = getKoin()
        SessionAwareLocalTaskExecutor(
            sessionProvider = { clientRegistry.getExecutionContext(clientId) },
            onSessionAdopted = { executionContext ->
                val dispatcher = koin.get<StreamElementDispatcher>()
                val connection = executionContext.clientConnection
                if (connection is LocalClientConnection) {
                    // ⚠⚠ In wire order, on one consumer — NOT a `scope.launch` per element. Two
                    // launches race, and the losing snapshot arriving after its own deferred
                    // patch was the "initial emission already carries the preview" gate flake.
                    // See `forwardStreamElementsInOrder`.
                    connection.forwardStreamElementsInOrder(scope, dispatcher)
                }
            },
        )
    }

    single<TaskExecutor> { get<AsyncTaskExecutor>() }

    single {
        val taskExecutor: AsyncTaskExecutor = get()

        TenshuEnvironment(
            taskExecutor = taskExecutor,
            schemaRegistry = get(),
            tContext = get(),
            requestChainFactory = { ctx -> LocalRequestChain(taskExecutor, ctx) }
        )
    }

    single<TenshuClient> {
        DefaultTenshuClient(get()).also { client ->
            // Schema announce (core-schema-evolution.md §6.6): the in-process /
            // route-1 client announces too — one code path, and the frozen
            // envelope is exercised on every launch. In-process calls are not
            // gated (live objects cannot positionally garble), so this is a
            // fire-and-forget; on route-1 the FirstPartyLocalRuntime's
            // platformPackageName override makes it announce as that app.
            val announcer = SchemaAnnouncer(
                appId = get<String>(named("platformPackageName")),
                tContext = get(),
                schemaProvider = get<SchemaRegistry>(),
                clientProvider = { client },
            )
            graphScope(
                ProxyGraphJob.Owners.SCHEMA_ANNOUNCER,
                Dispatchers.Default +
                    CoroutineExceptionHandler { _, e ->
                        println("TenshuSchemaAnnounce: in-process announce failed: ${e.message}")
                    },
            ).launch {
                try {
                    announcer.announce()
                } catch (e: Throwable) {
                    // Best-effort §6.6 hygiene: in-process calls are not gated, and
                    // a partially-wired graph (test fixtures) must never leak an
                    // uncaught exception onto the global handler.
                    println("TenshuSchemaAnnounce: in-process announce failed: ${e.message}")
                }
            }
        }
    }

    single<SyncRepository> { TenshuSyncRepository(get()) }

    // Host-side pairing monitor (control-pairing-ux.md Phase 1): a process-lifetime
    // single owning the pairing poll + approval state, hoisted off the transient
    // SyncDashboardViewModel so pairing keeps working when the Sync screen is gone.
    single {
        app.tenshu.control.sync.HostPairingMonitor(get(), scope = graphScope(ProxyGraphJob.Owners.HOST_PAIRING_MONITOR))
    }

    // Central notifications — inbound actionable requests (grants + redeemed invites)
    // Control reads in-process over its own TenshuClient (control-central-notifications.md
    // Phase 1). Process-lifetime single so the feed watches for the whole session.
    single<PendingRequestsRepository> {
        TenshuPendingRequestsRepository(get(), scope = graphScope(ProxyGraphJob.Owners.PENDING_REQUESTS))
    }

    single<RecoveryAffordancePreferences> {
        TenshuRecoveryAffordancePreferences(get(), get())
    }

    single<QuotaSoftLimitPreferences> {
        TenshuQuotaSoftLimitPreferences(get(), get())
    }
    single<app.tenshu.control.data.OfflineMapsSuggestionPreferences> {
        // Home's offline-maps discovery suggestion (maps-region-delivery.md §7 phase 7).
        TenshuOfflineMapsSuggestionPreferences(get(), get())
    }

    // PublisherClient (the commerce account/session client) is bound per-platform in
    // `PlatformProxyModule` — the real `CommercePublisherClient` needs the platform HttpClient +
    // owner-id source + commerce origin, selected against `TENSHU_BILLING_MOCK` like the minter
    // (`commerce-accounts-and-sessions.md` §3–§4, Phase 3b). iOS binds the mock (dev-only).

    // storage-entitlement-sync.md slice 3: the StorageProvider now derives figures
    // from the *synced* Entitlement (so a 2nd device reflects the owner's
    // subscription), not the device-local mock account. The reader/writer are wired
    // by initEntitlementStorage in the platform init hooks; initStorageService
    // collects this same instance's figures. Bound concretely + as StorageProvider.
    single { EntitlementStorageProvider() }
    single<StorageProvider> { get<EntitlementStorageProvider>() }

    single<LandingStateRepository> { TenshuLandingStateRepository(get()) }

    // Apps section (control-app-registration.md). Real SDK-backed registry over
    // AppService + the bundled first-party manifest source (the ReiSource-shaped
    // seam the ledger impl will replace).
    single<AppManifestSource> { BundledAppManifestSource() }
    single<AppRegistry> { TenshuAppRegistry(client = get(), manifestSource = get()) }
    // A4: clientId -> canonical appId allowlist consumed by the registration
    // trigger above; the ReiSource-shaped seam the verifier replaces.
    single<AppIdentityResolver> { BundledAppIdentityResolver() }

    // Data section (core-realm-data.md Phase 4): enumerates realm-data type ids off the
    // in-process proxy SchemaRegistry. Pure schema walk — no proxy round-trip.
    single<RealmDataClient> { app.tenshu.control.data.TenshuRealmDataClient(client = get(), liveness = get()) }

    // Data → Shares (control-ia.md §5.3): realm-wide outbound-share inventory +
    // revoke, over the real in-realm client (P1b backend is live). MockShareManager
    // is for previews/tests only — not DI-registered.
    single<ShareManager> { TenshuShareManager(get()) }
    single<ErrorEventsManager> { TenshuErrorEventsManager(get()) }
    single<BackgroundIngestManager> { TenshuBackgroundIngestManager(get()) }

    // Soft app-lock (control-keep-device-unlocked.md §4.2, toggle 1): the device-local
    // "Require unlock" preference, over the non-synced SecureStorage string bucket. UI
    // gate only — it never touches keys, so background sync is unaffected. The
    // AppLockAvailability probe + the Android AppLockController glue are per-platform.
    single { app.tenshu.control.data.AppLockSettings(secureStorage = get()) }
}
