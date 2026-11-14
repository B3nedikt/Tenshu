package app.tenshu.control.di

import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.coreml.iosPreflightKeyOf
import org.tenshu.inference.proxy.catalog.InstalledIndex
import org.tenshu.inference.proxy.catalog.ModelDeliveryComponents
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.InstalledModelData
import app.tenshu.control.inference.CatalogRead
import app.tenshu.control.inference.ForegroundIndexSource
import app.tenshu.control.inference.IndexCoverageSource
import app.tenshu.control.inference.IndexProgressSource
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.proxy.PassStanding
import kotlinx.coroutines.flow.MutableStateFlow
import app.tenshu.control.inference.InstalledRead
import app.tenshu.control.inference.JoinedModelSource
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OnDeviceModelSource
import app.tenshu.control.inference.RetiredWithTheGraph
import app.tenshu.control.ui.screens.models.ModelsRepository
import app.tenshu.control.ui.screens.models.OnDeviceModelsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.CoalescedRuns
import org.tenshu.inference.proxy.CompiledModels
import org.tenshu.inference.proxy.ModelPreparer
import org.tenshu.inference.proxy.PendingUnloads
import org.tenshu.inference.proxy.PinnedModelChanges
import org.tenshu.inference.proxy.SceneForeground
import org.tenshu.inference.proxy.coreml.CoreMlCompiledModels
import org.tenshu.inference.proxy.coreml.coreMlNotPrepared
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.AiStaticGates
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.InferenceStoreCustody
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.MediaIndexer
import org.tenshu.inference.proxy.Preflight
import org.tenshu.inference.proxy.PreflightDevice
import org.tenshu.inference.proxy.PreflightOutcome
import org.tenshu.inference.proxy.PreflightReport
import org.tenshu.inference.proxy.SlowProbes
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.inference.proxy.PreflightProbeLookup
import org.tenshu.inference.proxy.catalog.BackgroundModelDownloader
import org.tenshu.inference.proxy.catalog.CoreMlDiskBudget
import org.tenshu.inference.proxy.catalog.ModelDownloaderFactory
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.catalog.ModelStoreChanges
import org.tenshu.inference.proxy.catalog.ModelTransferPort
import org.tenshu.inference.proxy.coreml.COREML_RUNTIMES
import org.tenshu.inference.proxy.coreml.CoreMlCompiler
import org.tenshu.inference.proxy.coreml.CoreMlInferenceBackend
import org.tenshu.inference.proxy.coreml.CoreMlModelCache
import org.tenshu.inference.proxy.coreml.CoreMlPreflightTowers
import org.tenshu.inference.proxy.coreml.CoreMlSessionOpener
import org.tenshu.inference.proxy.coreml.IosPreflightDevice
import org.tenshu.inference.proxy.coreml.SystemCoreMlCompiler
import org.tenshu.inference.proxy.coreml.coreMlBackendOver
import org.tenshu.inference.proxy.coreml.coreMlModelFacts
import org.tenshu.inference.proxy.coreml.iosAiDevice
import org.tenshu.inference.proxy.coreml.iosPreflightKey
import org.tenshu.inference.proxy.FaceTemplateCustody
import org.tenshu.inference.proxy.coreml.FaceImageDecoder
import org.tenshu.inference.proxy.coreml.IosFaceImageDecoder
import org.tenshu.inference.proxy.coreml.SystemVisionFaceDetector
import org.tenshu.inference.proxy.coreml.VisionCoreMlFaceBackend
import org.tenshu.inference.proxy.coreml.VisionFaceDetector
import org.tenshu.inference.proxy.coreml.coreMlFaceReadiness
import org.tenshu.inference.proxy.coreml.coreMlFaceNotPrepared
import org.tenshu.inference.proxy.coreml.CoreMlFacePreflightModels
import org.tenshu.inference.proxy.coreml.faceBackendOver
import org.tenshu.inference.proxy.coreml.iosFacePreflightKey
import org.tenshu.inference.proxy.coreml.iosFacePreflightKeyOf
import org.tenshu.inference.proxy.FacePreflightModels
import org.tenshu.inference.proxy.FacePreflightProbeLookup
import org.tenshu.inference.proxy.FacePreflightProber
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.faceGroups
import app.tenshu.control.inference.CombinedModelSource
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.hasInferenceConsent
import org.tenshu.sync.common.SyncManager
import kotlinx.coroutines.flow.Flow
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.inference.IndexPassRefused
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.startMediaIndexPass
import org.tenshu.inference.proxy.BackgroundWindow
import org.tenshu.inference.proxy.DeviceIndexingState
import org.tenshu.inference.proxy.ARRIVALS_DEBOUNCE_MS
import app.tenshu.control.inference.ArrivalsLogCursor
import app.tenshu.control.inference.ArrivalsMissed
import app.tenshu.control.inference.ArrivedPhotoPaths
import org.tenshu.inference.proxy.ArrivalQueue
import org.tenshu.inference.proxy.PathsBatch
import org.tenshu.inference.proxy.runArrivalsBatch
import org.tenshu.inference.proxy.ForegroundArrivals
import org.tenshu.inference.proxy.ForegroundIndexing
import org.tenshu.inference.proxy.ForegroundIndexingConditions
import org.tenshu.inference.proxy.IndexPassResult
import org.tenshu.inference.proxy.NEW_ARRIVALS_BATCH_CAP
import org.tenshu.inference.proxy.PassEnding
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.PassExclusion
import org.tenshu.inference.proxy.StartAnswer
import org.tenshu.inference.proxy.coreml.IosIndexingSignals
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import kotlin.concurrent.AtomicInt
import org.tenshu.inference.proxy.ContinuedIndexing
import org.tenshu.inference.proxy.ContinuedTaskScheduler
import org.tenshu.inference.proxy.IndexProgressHolder
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.MEDIA_INDEX_CAPABILITIES
import org.tenshu.inference.proxy.PassOutcome
import org.tenshu.inference.proxy.ProcessingWake
import org.tenshu.inference.proxy.WakeScheduler
import org.tenshu.inference.proxy.WakeWork
import org.tenshu.inference.proxy.isIndexingPaused
import org.tenshu.inference.proxy.passOutcomeOf
import org.tenshu.inference.proxy.permittedInference
import org.tenshu.inference.proxy.runWakePassOver
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * On-device AI in the graph of the **one** app that owns inference on this phone — Photos
 * (`ios-on-device-inference.md` §3.2 *Readiness*). Applied over
 * [ownedInferenceStoresModule], and only on a phone [PhotosOnDeviceAi.admitted] let through.
 *
 * ⚠⚠ **Bound once, at assembly, never rebound.** The backend and the readiness state are singles
 * of this graph for the life of the process. What changes while it runs is [AiReadiness], which
 * the backend consults on every call. Nothing here reassembles a graph.
 *
 * ⚠⚠ **The model download is bound here, whole or not at all** (§14.11). Three
 * bindings that belong together:
 * - `supportedModelRuntimes = COREML_RUNTIMES`, which is what makes the catalog offer the
 *   ~709 MB download. ⚠ Exactly `{"coreml"}`: a build may contribute one runtime per model id
 *   (`models-pipeline.md` §6.1), and no iOS source set contains an ONNX runtime to contribute;
 * - the downloader that may carry it: [BackgroundModelDownloader], over a transport that is held
 *   to Wi-Fi and outlives the process, behind a journal and a free-space check (D6, §3.4). The
 *   in-process fetch every other platform uses is never this graph's;
 * - [ModelStoreChanges]: every commit and every removal runs inside `AiReadiness.storeChanging`.
 *
 * ⚠⚠ **The foreground pass is bound here** (§14.19): `indexingConditions` (the
 * manual bar: in front, not hot, not Low Power Mode, not a low battery; the scheduled bar only inside
 * a processing wake, PR-6),
 * the process's [PassExclusion] and [ForegroundIndexing], which *Index now* goes through and which
 * stops the pass when Photos leaves the foreground. ⚠ A downloaded model **is** prepared
 * ([ModelPreparer], §14.17): compiled in the foreground, after which the preflight decides
 * whether it serves.
 *
 * **The preflight is bound** (§14.6): [Preflight], readiness's writer 2, started by
 * [PhotosOnDeviceAi.recheck]. With no model installable it finds nothing to probe.
 *
 * ⚠⚠ **Faces are bound here too, behind a second flag** ([faces]; `ios-face-grouping.md` FD10, F5):
 * the face backend beside the Core ML one, `coreml+vision` beside `coreml`, and the face pair's own
 * readiness ([PhotosOnDeviceAi.FACE_READINESS]) over **its own custody** ([FaceTemplateCustody], over
 * `ReadinessSlot.FACES.fingerprintPath`). ⚠⚠ Never SigLIP's: that custody clears the search vectors.
 * ⚠ Only inside this module, so only where the AI flag and both static gates admitted the phone:
 * faces ride SigLIP's pass, journal and readiness machinery. ⭐ F6: the face model has a prepare
 * step of its own ([PhotosOnDeviceAi.FACE_PREPARER], towers `[faceEmbedder]`) and a probe of its
 * own ([PhotosOnDeviceAi.FACE_PREFLIGHT], over `FacePreflightProber`), both run by the same check
 * as SigLIP's and after it; and the Models screen lists it ([CombinedModelSource]).
 *
 * @param root the absolute directory the owner's inference database is rooted at.
 * @param faces whether this build binds the face backend ([PhotosOnDeviceAi.facesAdmitted]).
 */
fun photosOnDeviceAiModule(root: String, faces: Boolean = false): Module = module {
    single {
        // ⚠ Under `inference/`, with everything else this app derives: the purge of a directory
        // that lost its backup exclusion removes that tree, and compiled models are ~740 MB of it.
        // ⚠⚠ The database itself, not a re-rooted view of it: `prepare` reads the archive through
        // it by the model store's key, which already begins with `inference/models/`.
        CoreMlModelCache(
            files = get(named(INFERENCE_FILE_DATABASE)),
            root = root,
            // A test's compiler; nothing binds one in production.
            compiler = getOrNull<CoreMlCompiler>() ?: SystemCoreMlCompiler,
            base = COMPILED_BASE,
        )
    }
    single {
        // ⚠ Lazy, each of them: this object is constructed wherever the backend registry first
        // is, and the custody's construction reads the logs whole.
        val custody: Lazy<InferenceStoreCustody> = lazy { get<InferenceStoreCustody>() }
        AiReadiness(
            files = { get(named(INFERENCE_FILE_DATABASE)) },
            facts = { coreMlModelFacts(get<ModelStore>(), get<CoreMlModelCache>(), get(named(INFERENCE_FILE_DATABASE))) },
            // ⚠ The preflight's key, wider than the custody's fingerprint: the vocabulary, the app
            // build and the probe void a verdict and clear no vector.
            currentKey = { iosPreflightKey(get<ModelStore>().installed()) },
            storesUsable = { custody.value.usable() },
            applyFingerprint = { fingerprint -> custody.value.applyFingerprint(fingerprint) },
            cancelPass = { get<MediaIndexRunner>().cancelRunningPass() },
            closeSessions = { get<CoreMlInferenceBackend>().closeSessions() },
        )
    }
    single {
        // A test's stand-in for `CoreMlSession.open`; nothing binds one in production.
        val open: CoreMlSessionOpener? = getOrNull()
        val readVocabulary: (String) -> ByteArray? =
            { path -> get<FileDatabase>(named(INFERENCE_FILE_DATABASE)).readBytes(path)?.get() }
        // ⚠⚠ No tower of this graph is opened while an earlier graph of this process is still
        // unloading its own: the process's handle, not anything of this graph's. Nor while this
        // graph's preflight still holds the pair it probed with.
        // ⚠ For an EARLIER graph's unload. When it is this graph that was torn down the wait
        // returns at once, and the load below finds the model is not served.
        val mine: AiReadiness = get()
        val koin: Koin = getKoin()
        val beforeLoad: suspend () -> Unit = {
            PhotosOnDeviceAi.awaitPendingUnload(mine)
            get<Preflight>().awaitSessionsClosed()
            // ⚠ Nor beside a face probe that holds the embedder (`ios-face-grouping.md` §16.2): the
            // reverse of the face loads' wait on search's probe. ⚠ Only one the graph has BUILT: a
            // wait must not construct the face pair. With faces off there is none, and this is nothing.
            PhotosOnDeviceAi.builtFacePreflight(koin)?.awaitSessionsClosed()
        }
        // ⚠⚠ Every open while `Ready` is recorded in the scene's foreground (§14.19).
        val foregroundNow: () -> Long? = { PhotosOnDeviceAi.foregroundEpoch() }
        if (open != null) {
            coreMlBackendOver(get<AiReadiness>(), readVocabulary, open = open::open, beforeLoad = beforeLoad, foregroundNow = foregroundNow)
        } else {
            coreMlBackendOver(get<AiReadiness>(), readVocabulary, beforeLoad = beforeLoad, foregroundNow = foregroundNow)
        }
    }
    single<CompiledModels> { CoreMlCompiledModels(get<CoreMlModelCache>()) }
    single {
        // The prepare step (§14.17): what takes an installed model out of `Preparing`.
        val koin: Koin = getKoin()
        ModelPreparer(
            store = get(),
            compiled = get(),
            readiness = get(),
            preflight = { afterRecompile ->
                // ⚠ After a second compile the probe is not held back by the backoff of the
                // probe that failed: it is about other bytes.
                // ⚠⚠ Counted in the scene's foreground, not as a foreground of its own.
                val foreground: Long? = PhotosOnDeviceAi.foregroundEpoch()
                if (afterRecompile) koin.get<Preflight>().recompiled(foreground) else koin.get<Preflight>().prepared(foreground)
            },
            // ⚠⚠ Readiness's own reading, by the function it reads with: what the preparer says
            // of an install it cannot finish is what readiness finds missing.
            currentKey = { iosPreflightKey(koin.get<ModelStore>().installed()) },
            notPrepared = { coreMlNotPrepared(koin.get(), koin.get(), koin.get(named(INFERENCE_FILE_DATABASE))) },
            // ⚠⚠ Nothing is compiled while Photos is not in the foreground: a compile is seconds
            // of CPU and a gigabyte of writes, and a background launch is suspended again.
            inForeground = { PhotosOnDeviceAi.inForeground() },
            // ⚠⚠ The first open is a model load: not while an earlier graph of this process is
            // still unloading its own, nor while this graph's probe holds a pair.
            beforeLoad = {
                PhotosOnDeviceAi.awaitPendingUnload(koin.get())
                koin.get<Preflight>().awaitSessionsClosed()
            },
            // ⚠⚠ The downloader's lock, which is the process's: no commit, removal or other
            // graph's prepare runs under a compile.
            steps = PhotosModelDownloads.steps,
            // ⚠ The scene's foreground, as the probe's: what a load's record carries (§14.5).
            foregroundNow = { PhotosOnDeviceAi.foregroundEpoch() },
        )
    }
    // ⚠⚠ Under the INTERFACE (§11's rule for the two holders: one bound under its concrete
    // type is resolved by nothing, silently). What Photos' Models screen reads (§14.18).
    single<OnDeviceModelSource> {
        val koin: Koin = getKoin()
        val readiness: AiReadiness = get()
        val preparer: ModelPreparer = get()
        val preflight: Preflight = get()
        val delivery: ModelDeliveryComponents = get()
        // ⚠ Only over the downloader that has a journal. A graph given another kind has no
        // source: this throws, and [PhotosOnDeviceAi.modelSource] answers `null` for it.
        val downloader: BackgroundModelDownloader = delivery.downloader as? BackgroundModelDownloader
            ?: error("the Models source needs the journalling downloader; this graph has ${delivery.downloader::class.simpleName}")
        val search = JoinedModelSource(
            modelId = preparer.modelId,
            // ⚠ Its own: a download's call outlives whoever asked for it, and the screen.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            retired = readiness.retiredNow,
            // ⚠ The state WITH the install it is about, never the bare state.
            readiness = readiness.standing,
            journal = downloader.journalView,
            outcome = downloader.lastOutcome,
            progress = delivery.progress.progress,
            compiling = preparer.compiling,
            probing = preflight.probing,
            prepare = preparer.lastReport,
            prepareRefusal = preparer.lastRefusal,
            preflight = preflight.lastReport,
            readInstalled = {
                // ⚠⚠ The store's ONE answer: records, not an index, or unreadable. `installed()`
                // alone answers bytes that are no index as no records, and would offer a Download
                // over them.
                when (val index: InstalledIndex = delivery.store.read()) {
                    InstalledIndex.NotAnIndex, is InstalledIndex.Unreadable -> InstalledRead.Unreadable
                    is InstalledIndex.Records -> {
                        // ⚠ One read for the record and both keys: the keys are THIS record's.
                        val record: InstalledModelData? = index.models.firstOrNull { it.modelId == preparer.modelId }
                        InstalledRead.Read(
                            record = record,
                            prepareKey = record?.let(preparer::keyFor),
                            preflightKey = record?.let { iosPreflightKey(index.models) },
                            // ⚠ From the record's own sizes: what a removal frees is known with no catalog.
                            onDeviceBytes = record?.let { it.byteSize + CoreMlDiskBudget.prepareBytes(it.byteSize) } ?: 0L,
                        )
                    }
                }
            },
            loadCatalog = {
                delivery.catalogSource.load()
                    ?.let { catalog ->
                        val entry: ModelEntryData? = catalog.models.firstOrNull { it.modelId == preparer.modelId }
                        // ⚠ §3.4's figure, first-open allowance counted as kept: the download and
                        // what the prepare step adds to it.
                        CatalogRead.Read(entry, entry?.let { it.byteSize + CoreMlDiskBudget.prepareBytes(it.byteSize) })
                    }
                    ?: CatalogRead.Unavailable
            },
            startDownload = { modelId, allowsCellular, admitted -> downloader.download(modelId, allowsCellular) { admitted() } },
            cancelDownload = { modelId -> downloader.cancel(modelId) },
            recheck = { PhotosOnDeviceAi.recheck(koin) },
            isRetired = readiness::isRetired,
            unloadSearch = { koin.get<CoreMlInferenceBackend>().closeText() },
            // ⚠ The key the entry would have once installed: no download is offered that it
            // would answer at once (§14.5 item 0).
            readOffered = { entry -> readiness.answerFor(iosPreflightKeyOf(entry)) },
            loadAgainOf = { entry ->
                val key: PreflightKey = entry?.let { iosPreflightKeyOf(it) } ?: iosPreflightKey(koin.get<ModelStore>().installed())
                readiness.loadAgain(key)
            },
        )
        // ⚠⚠ With the face backend, the face model's rows beside search's: each from its own
        // readiness, preparer and probe, and nothing of one read for the other.
        if (!faces) return@single search
        CombinedModelSource(
            linkedMapOf(
                preparer.modelId to search,
                FaceModel.AuraFaceCoreMl.modelId to PhotosOnDeviceAi.faceSource(koin, delivery, downloader),
            ),
        )
    }
    // ⚠⚠ Under the INTERFACE, and ONE for the graph: every Models screen that opens reads the
    // same repository, so a removal one of them started is *removing* on all of them (§14.18.14).
    // The repository once it exists, for the teardown: asking the graph for it there would build it.
    // ⚠ Latched: one built during or after the teardown is retired as it is handed out.
    single { RetiredWithTheGraph() }
    single<ModelsRepository> {
        val teardown: RetiredWithTheGraph = get()
        val koin: Koin = getKoin()
        teardown.built(OnDeviceModelsRepository.over(
            // ⚠ A graph with no journalling downloader has no source: *not connected*, never empty.
            source = runCatching { get<OnDeviceModelSource>() }.getOrNull(),
            // ⚠⚠ From the backends this graph BOUND, never a constant: the face switch is offered
            // only where a face backend is (two switches, absence is NO).
            features = PhotosOnDeviceAi.features(get(named("inferenceBackends"))),
            // ⚠ What the screen reads a row as before the catalog is read: the models this graph prepares.
            modelFeatures = PhotosOnDeviceAi.modelFeatures(faces),
            // Photos' own client: the removal is the operation, the consents and the pause are
            // the preference rows.
            client = get<TenshuClient>(),
            // ⚠ Its own: a removal and a check end in it whoever is still watching. Cancelled
            // with the graph ([PhotosOnDeviceAi.retire]).
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            // ⚠⚠ The indexing section (§14.19.6): this graph's foreground pass. Every input is a
            // provider asked off the main thread: the runner's construction opens the stores.
            // ⚠⚠ And none of it is BUILT for a graph already retired: a runner or a
            // store resolved for the first time there would be a second one over the live files.
            indexing = ForegroundIndexSource(
                driver = { if (graphRetired(koin)) null else koin.get<ForegroundIndexing>() },
                runnerStanding = {
                    if (graphRetired(koin)) MutableStateFlow<PassStanding>(PassStanding.Idle(null)) else koin.get<MediaIndexRunner>().standing
                },
                // ⚠⚠ The SAME holder the indexer ticks, and the same coverage the runner records.
                progress = get<IndexProgressSource>(),
                coverage = get<IndexCoverageSource>(),
                // ⚠ Not known before the custody's check: an unchecked store may hold vectors
                // about to be cleared.
                searchable = {
                    if (graphRetired(koin)) {
                        null
                    } else {
                        val custody: InferenceStoreCustody = koin.get()
                        if (custody.usable()) koin.get<EmbeddingStore>().size() else null
                    }
                },
                clearedByOsUpdate = { if (graphRetired(koin)) MutableStateFlow(false) else koin.get<InferenceStoreCustody>().clearedByOsUpdate },
                alsoWhen = { koin.get<AiReadiness>().standing },
                // ⚠⚠ The running line follows the claim, never the OS version (§15.7 Q2): below
                // iOS 26, or with the request refused, the claim stays NONE and the line says *Keep
                // Photos open*.
                continuation = { if (graphRetired(koin)) null else koin.get<ContinuedIndexing>().standings },
            ),
        ))
    }
    single(named(RECHECKS)) {
        val koin: Koin = getKoin()
        // ⚠ Its own scope: a commit asks for a check from inside the downloader and waits for none.
        CoalescedRuns(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { launch -> PhotosOnDeviceAi.recheckOnce(koin, launch) }
    }
    // ⚠⚠ The face backend only with the face flag: absent, no backend of this graph claims
    // `FACE_CLUSTERING`, and the Models screen offers no face switch ([PhotosOnDeviceAi.features]).
    single<List<InferenceBackend>>(named("inferenceBackends")) {
        if (faces) listOf(get<CoreMlInferenceBackend>(), get<VisionCoreMlFaceBackend>()) else listOf(get<CoreMlInferenceBackend>())
    }
    if (faces) {
        // ⚠⚠ The face pair's custody: its own fingerprint file, and the templates the only thing it
        // clears. The realm half (owner and `meta.epoch`) stays `InferenceStoreCustody`'s.
        single {
            FaceTemplateCustody(
                files = get(named(INFERENCE_FILE_DATABASE)),
                faces = get(),
                cancelPass = { get<MediaIndexRunner>().cancelRunningPass() },
            )
        }
        single(named(PhotosOnDeviceAi.FACE_READINESS)) {
            // ⚠ Lazy, as SigLIP's: this is constructed wherever the registry first is.
            val realm: Lazy<InferenceStoreCustody> = lazy { get<InferenceStoreCustody>() }
            coreMlFaceReadiness(
                files = { get(named(INFERENCE_FILE_DATABASE)) },
                store = { get<ModelStore>() },
                cache = { get<CoreMlModelCache>() },
                custody = { get<FaceTemplateCustody>() },
                realmUsable = { realm.value.usable() },
                cancelPass = { get<MediaIndexRunner>().cancelRunningPass() },
                closeSessions = { get<VisionCoreMlFaceBackend>().closeSessions() },
            )
        }
        single {
            // A test's stand-ins for Core ML and Vision; nothing binds them in production.
            val open: CoreMlSessionOpener? = getOrNull()
            val detector: VisionFaceDetector = getOrNull() ?: SystemVisionFaceDetector
            val decoder: FaceImageDecoder = getOrNull() ?: IosFaceImageDecoder
            val mine: AiReadiness = get(named(PhotosOnDeviceAi.FACE_READINESS))
            // ⚠⚠ As SigLIP's: no embedder is opened while an earlier graph is still unloading, nor
            // while this graph's search probe holds a pair.
            val beforeLoad: suspend () -> Unit = {
                PhotosOnDeviceAi.awaitPendingUnload(mine)
                get<Preflight>().awaitSessionsClosed()
            }
            // ⚠⚠ `faceBackendOver` refuses any readiness that is not the face slot's.
            if (open != null) {
                faceBackendOver(mine, decoder, detector, open = open::open, beforeLoad = beforeLoad, foregroundNow = { PhotosOnDeviceAi.foregroundEpoch() })
            } else {
                faceBackendOver(mine, decoder, detector, beforeLoad = beforeLoad, foregroundNow = { PhotosOnDeviceAi.foregroundEpoch() })
            }
        }
        single(named(PhotosOnDeviceAi.FACE_PREFLIGHT)) {
            // The face model's writer 2 (F6; `ios-face-grouping.md` §6.2): F4's prober over the face
            // slot, with the backend's own detector, decode and embedder spec.
            // A test's stand-ins; nothing binds them in production.
            val open: CoreMlSessionOpener? = getOrNull()
            val models: FacePreflightModels = getOrNull()
                ?: if (open != null) CoreMlFacePreflightModels(open = open::open) else CoreMlFacePreflightModels()
            val probes: FacePreflightProbeLookup? = getOrNull()
            val device: PreflightDevice = getOrNull() ?: IosPreflightDevice
            val probing: AiReadiness = get(named(PhotosOnDeviceAi.FACE_READINESS))
            val prober: FacePreflightProber = if (probes != null) FacePreflightProber(models, device, probes) else FacePreflightProber(models, device)
            val koin: Koin = getKoin()
            val built: FacePreflightBuilt = get()
            Preflight.over(
                readiness = probing,
                prober = prober,
                device = device,
                // ⚠⚠ As SigLIP's: an earlier graph's unload first, and never beside search's probe.
                beforeLoad = {
                    PhotosOnDeviceAi.awaitPendingUnload(probing)
                    koin.get<Preflight>().awaitSessionsClosed()
                },
                // ⚠⚠ The process's, and the face model's own: a slow face probe spends no search foreground.
                slowProbes = PhotosOnDeviceAi.faceSlowProbes,
                foregroundNow = { PhotosOnDeviceAi.foregroundEpoch() },
            ).also { built.preflight = it }
        }
        // ⚠ Holds the face probe once it exists, and nothing before: what a wait and a teardown read.
        single { FacePreflightBuilt() }
        single(named(PhotosOnDeviceAi.FACE_PREPARER)) {
            // The face model's prepare step (F6): SigLIP's preparer class over the face slot, its one
            // tower and its id. ⚠ The same compiled cache and step lock: the sweep keeps every
            // installed record's digests, of either model.
            val koin: Koin = getKoin()
            val readiness: AiReadiness = get(named(PhotosOnDeviceAi.FACE_READINESS))
            ModelPreparer(
                store = get(),
                compiled = get(),
                readiness = readiness,
                preflight = { afterRecompile ->
                    val foreground: Long? = PhotosOnDeviceAi.foregroundEpoch()
                    val preflight: Preflight = koin.get(named(PhotosOnDeviceAi.FACE_PREFLIGHT))
                    if (afterRecompile) preflight.recompiled(foreground) else preflight.prepared(foreground)
                },
                currentKey = { iosFacePreflightKey(koin.get<ModelStore>().installed()) },
                notPrepared = { coreMlFaceNotPrepared(koin.get(), koin.get()) },
                inForeground = { PhotosOnDeviceAi.inForeground() },
                // ⚠⚠ No first open beside either model's probe, nor while an earlier graph unloads.
                beforeLoad = {
                    PhotosOnDeviceAi.awaitPendingUnload(readiness)
                    koin.get<Preflight>().awaitSessionsClosed()
                    koin.get<Preflight>(named(PhotosOnDeviceAi.FACE_PREFLIGHT)).awaitSessionsClosed()
                },
                steps = PhotosModelDownloads.steps,
                towers = listOf(ModelArtifactRole.FACE_EMBEDDER),
                modelId = FaceModel.AuraFaceCoreMl.modelId,
                foregroundNow = { PhotosOnDeviceAi.foregroundEpoch() },
            )
        }
    }
    // ─── The foreground pass (§14.19). ─────────────────────────────────────────────────────────
    single {
        // A test's phone; nothing binds one in production.
        val signals: PhotosIndexingSignals = getOrNull() ?: SystemIndexingSignals
        val koin: Koin = getKoin()
        // ⚠⚠ A pass a continued task owns is in the foreground as far as its own bar goes —
        // the task is what keeps the app running. Asked lazily: the bridge needs the graph's scope.
        val continued: ContinuedIndexing by lazy { koin.get<ContinuedIndexing>() }
        // ⚠⚠ PR-6: the scheduled bar says yes only inside a processing wake. Lazy, as the bridge.
        val wake: ProcessingWake by lazy { koin.get<ProcessingWake>() }
        ForegroundIndexingConditions(
            read = { signals.read(PhotosOnDeviceAi.inForeground() || continued.holdsPass()) },
            inWake = { wake.carriesPass() },
        )
    }
    // ⚠⚠ Under the name the indexer and the op read: the manual bar is this, the scheduled one never.
    single<IndexingConditions>(named("indexingConditions")) { get<ForegroundIndexingConditions>() }
    // ⚠⚠ The PROCESS's: a graph that replaces this one brings a runner of its own.
    single<PassExclusion> { PhotosOnDeviceAi.passSlot }
    single(named(INDEXING_SCOPE)) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single {
        val koin: Koin = getKoin()
        val signals: PhotosIndexingSignals = getOrNull() ?: SystemIndexingSignals
        signals.observe()
        ForegroundIndexing(
            // ⚠ Its own, cancelled with the graph ([PhotosOnDeviceAi.retire]).
            scope = get(named(INDEXING_SCOPE)),
            conditions = get(),
            changes = signals.changes,
            memoryWarnings = signals.memoryWarnings,
            // ⚠ Asked on the driver's scope: the runner's construction opens the stores.
            runnerStanding = { koin.get<MediaIndexRunner>().standing },
            // ⚠⚠ Through the operation, so its four refusals are checked once, there.
            startPass = { PhotosOnDeviceAi.startPass(koin.get<TenshuClient>()) },
            cancelPass = { koin.get<MediaIndexRunner>().cancelRunningPass() },
            // ⚠ The face embedder too: nothing of a model stays loaded behind a Photos that left.
            closeSessions = {
                koin.get<CoreMlInferenceBackend>().closeSessions()
                koin.getOrNull<VisionCoreMlFaceBackend>()?.closeSessions()
            },
            // A test's window; nothing binds one in production.
            window = getOrNull() ?: AppBackgroundWindow,
            clock = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
            // The continued task, when this build and this phone can make one.
            continuation = get<ContinuedIndexing>(),
            // ⚠⚠ PR-6: a leave inside a processing wake stops nothing; the wake's end does.
            wakeCarriesPass = { koin.get<ProcessingWake>().carriesPass() },
        ).also { it.start() }
    }
    // ─── The processing wake: the library indexed overnight on the charger (PR-6, §16). ──────
    single {
        val koin: Koin = getKoin()
        ProcessingWake(
            // ⚠ The driver's, cancelled with the graph: a live wake is then completed with `false`.
            scope = get(named(INDEXING_SCOPE)),
            // A test's scheduler first; in production the one Swift installed, or none.
            scheduler = { koin.getOrNull<WakeScheduler>() ?: PhotosProcessingWake.scheduler() },
            work = { PhotosOnDeviceAi.wakeWork(koin) },
            runPass = { PhotosOnDeviceAi.runWakePass(koin) },
            // ⚠⚠ Only a scheduled pass: a press's pass the wake met is not the wake's to end.
            stopPass = { koin.get<MediaIndexRunner>().cancelRunningScheduledPass() },
            // ⚠⚠ The leave's stop if Photos is not in front: the pass stopped and BOTH models let
            // go inside a background window, before the task is completed.
            released = { koin.get<ForegroundIndexing>().continuationReleased() },
            window = getOrNull() ?: AppBackgroundWindow,
            clock = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        )
    }
    // ─── New arrivals while Photos is in front (IN-D6a, PR-6b, §17). ───────────────────────────
    // ⚠⚠ The cursor on disk beside the queue: a fresh process queues only what arrived past it.
    single { ArrivedPhotoPaths(ArrivalsLogCursor.InFile(get(named(INFERENCE_FILE_DATABASE)))) }
    single { ArrivalsMissed() }
    single {
        val koin: Koin = getKoin()
        ForegroundArrivals(
            // ⚠ The driver's, cancelled with the graph: a batch waiting or running goes with it.
            scope = get(named(INDEXING_SCOPE)),
            inForeground = { PhotosOnDeviceAi.inForeground() },
            // ⚠⚠ Which photographs arrived: the modification log's newest rows, never the tree, into
            // the graph's one arrivals queue (the common module's, in Photos' private store).
            look = { PhotosOnDeviceAi.lookForArrivals(koin) },
            // ⚠⚠ The ONE runner, capped, over the queued paths alone; never the start operation (it
            // takes no paths) and never a join.
            runBatch = { PhotosOnDeviceAi.runArrivalsBatch(koin) },
            runnerStanding = { koin.get<MediaIndexRunner>().standing },
            // A test's debounce; production's is the constant.
            debounceMs = getOrNull<Long>(named(PhotosOnDeviceAi.ARRIVALS_DEBOUNCE)) ?: ARRIVALS_DEBOUNCE_MS,
            // ⚠⚠ Before the look: no consent or no model reads no log and queues nothing.
            mayRun = { PhotosOnDeviceAi.arrivalsMayRun(koin) },
            // ⚠⚠ The look is a store read a leave cannot cancel: the batch holds a window to its end.
            window = getOrNull() ?: AppBackgroundWindow,
            // ⚠ No half may run: the log's end is marked (hashes only), so consent given later starts there.
            notAsked = { PhotosOnDeviceAi.markArrivalsLogEnd(koin) },
        ).also { it.start() }
    }
    // ─── *Index now* continued (iOS 26+). ──────────────────────────────────────────────────────
    single {
        val koin: Koin = getKoin()
        ContinuedIndexing(
            // ⚠ The driver's, cancelled with the graph: a live task is then completed with `false`.
            scope = get(named(INDEXING_SCOPE)),
            // A test's scheduler first; in production the one Swift installed on iOS 26+, or none.
            scheduler = { koin.getOrNull<ContinuedTaskScheduler>() ?: PhotosIndexNow.scheduler() },
            outcomeOf = { pass -> koin.get<MediaIndexRunner>().outcomeOf(pass) },
            // ⚠⚠ The SAME holder the indexer ticks.
            progress = { koin.get<IndexProgressHolder>().progress },
            // ⚠ The ending read while the claim still holds, then the leave's stop if not in front.
            settled = { koin.get<ForegroundIndexing>().continuationSettled() },
            released = { koin.get<ForegroundIndexing>().continuationReleased() },
            identifierPrefix = PhotosIndexNowTask.PREFIX,
            newSuffix = ::randomSuffix,
        )
    }
    // ─── The model download (§14.11). The three bindings below go together or not at all.
    // ⚠⚠ `coreml+vision` only with the face backend: the face entry is offered where it can run.
    single<Set<String>>(named("supportedModelRuntimes")) {
        if (faces) COREML_RUNTIMES + FaceModel.COREML_VISION_RUNTIME else COREML_RUNTIMES
    }
    single<ModelStoreChanges> {
        // Readiness's writer 3: the old generation stops being served before the store moves.
        // ⚠⚠ For the model the change is about: a change to any other model stands nothing down.
        if (faces) {
            ByModelChanges(
                siglip = get(),
                faces = get(named(PhotosOnDeviceAi.FACE_READINESS)),
            )
        } else {
            PinnedModelChanges(get(), VisionModel.PinnedSigLip.modelId)
        }
    }
    single<ModelDownloaderFactory> {
        val koin: Koin = getKoin()
        ModelDownloaderFactory { catalogSource, store, progress, baseUrl ->
            BackgroundModelDownloader(
                catalogSource = catalogSource,
                store = store,
                files = koin.get(named(INFERENCE_FILE_DATABASE)),
                progress = progress,
                baseUrl = baseUrl,
                // A test's transport first; in production the one Swift installed, or none.
                // ⚠ None is a refusal: nothing here falls back to a fetch that cannot be held to Wi-Fi.
                transport = { koin.getOrNull<ModelTransferPort>() ?: PhotosModelDownloads.port() },
                changes = koin.get(),
                additionalDiskNeed = CoreMlDiskBudget::additionalNeed,
                // ⚠ A graph that was torn down stays reachable, and must not install anything.
                retired = { koin.get<AiReadiness>().isRetired() },
                deliveredRefs = { PhotosModelDownloads.deliveredRefs(root) },
                // ⚠ Its own, like the in-process downloader's: a transfer's completion is settled
                // whoever is, or is not, waiting for the download.
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                // ⚠⚠ The process's, not this graph's: the graph this one replaces may be mid-step.
                steps = PhotosModelDownloads.steps,
                // ⚠⚠ A commit asks for THE check, the same one a launch and the scene ask for:
                // prepare, then the probe the state says is owed. Asked after the commit's
                // step has ended, and not waited for.
                // ⚠ When it is the model this graph prepares that was installed.
                storeChanged = { changed ->
                    // ⚠ The face model's install asks for the same check: it prepares and probes both.
                    val concerned: Boolean = koin.get<ModelPreparer>().concerns(changed) ||
                        koin.getOrNull<ModelPreparer>(named(PhotosOnDeviceAi.FACE_PREPARER))?.concerns(changed) == true
                    if (concerned) koin.get<CoalescedRuns>(named(RECHECKS)).requestLater()
                },
                // ⚠⚠ And a removal evicts the compiled towers in its own step, under the lock
                // it holds, wherever Photos is: `DeleteModel` returning means the bytes are gone.
                // ⚠⚠ Each says WHICH model: the towers discarded are the removed model's, never
                // this graph's because some other model went.
                // ⚠⚠ The face preparer evicts the face model's towers, and only those: each
                // preparer's `evicting` returns at once for a model that is not its own.
                beforeRemoval = { removed ->
                    koin.get<ModelPreparer>().evicting(removed)
                    koin.getOrNull<ModelPreparer>(named(PhotosOnDeviceAi.FACE_PREPARER))?.evicting(removed)
                },
                afterRemoval = { removed ->
                    koin.get<ModelPreparer>().evicted(removed)
                    koin.getOrNull<ModelPreparer>(named(PhotosOnDeviceAi.FACE_PREPARER))?.takeIf { it.concerns(removed) }?.evicted(removed)
                },
            )
        }
    }
    single {
        // Readiness's writer 2 (D12 ②). Its towers are opened through the same opener as the
        // backend's and never through the backend: it runs while the backend claims nothing.
        val open: CoreMlSessionOpener? = getOrNull()
        // A test's probe for a test's artifacts; nothing binds one in production, where the
        // probes are the ones this build was compiled with.
        val probes: PreflightProbeLookup? = getOrNull()
        val towers = if (open != null) CoreMlPreflightTowers(open::open) else CoreMlPreflightTowers()
        val readVocabulary: (String) -> ByteArray? =
            { path -> get<FileDatabase>(named(INFERENCE_FILE_DATABASE)).readBytes(path)?.get() }
        // A test's phone; nothing binds one in production.
        val device: PreflightDevice = getOrNull() ?: IosPreflightDevice
        // ⚠⚠ The preflight loads models too: it waits for an earlier graph's unload as the
        // backend does.
        // ⚠⚠ An EARLIER graph's, never this graph's own: the probe has claimed its towers by
        // the time it waits here, and this graph's unload waits for that claim to be let go.
        val probing: AiReadiness = get()
        val beforeLoad: suspend () -> Unit = { PhotosOnDeviceAi.awaitPendingUnload(probing) }
        if (probes != null) {
            Preflight(
                get(), towers, readVocabulary, device, beforeLoad, probes,
                slowProbes = PhotosOnDeviceAi.slowProbes, foregroundNow = { PhotosOnDeviceAi.foregroundEpoch() },
            )
        } else {
            // ⚠⚠ The process's: a graph that replaces another inside one foreground does not
            // get that foreground's slow probe a second time.
            // ⚠⚠ And a probe the scene left under records nothing.
            Preflight(
                get(), towers, readVocabulary, device, beforeLoad,
                slowProbes = PhotosOnDeviceAi.slowProbes, foregroundNow = { PhotosOnDeviceAi.foregroundEpoch() },
            )
        }
    }
}

/**
 * What the owning runtime asks about [photosOnDeviceAiModule]'s graph. Here for
 * [OwnedInferenceStores]' reason: the umbrella does not see `:inference-service-proxy`.
 */
object PhotosOnDeviceAi {

    /** The face pair's [AiReadiness] in [photosOnDeviceAiModule]'s graph; SigLIP's is unqualified. */
    const val FACE_READINESS: String = "faceReadiness"

    /** The face model's [ModelPreparer] (F6); SigLIP's is unqualified. */
    const val FACE_PREPARER: String = "facePreparer"

    /** The face model's [Preflight] (F6); SigLIP's is unqualified. */
    const val FACE_PREFLIGHT: String = "facePreflight"

    /** [koin]'s face [Preflight] if its graph has built one, else `null`. ⚠ Never constructs it ([FacePreflightBuilt]). */
    fun builtFacePreflight(koin: Koin): Preflight? =
        runCatching { koin.getOrNull<FacePreflightBuilt>()?.preflight }.getOrNull()

    /**
     * Which feature each model a graph prepares serves (`ModelsCapabilities.modelFeatures`). ⚠ Empty
     * with faces off: one model, every row search's already, and the capabilities exactly what they were.
     */
    fun modelFeatures(faces: Boolean): Map<String, InferenceCapability> =
        if (!faces) emptyMap()
        else mapOf(VisionModel.PinnedSigLip.modelId to InferenceCapability.SEMANTIC_IMAGE_SEARCH, FaceModel.AuraFaceCoreMl.modelId to InferenceCapability.FACE_CLUSTERING)

    /**
     * The face model's rows of the Models screen (F6): [JoinedModelSource] over the face slot's
     * readiness, preparer and probe, and the same journal, store and catalog as search's.
     * ⚠⚠ Nothing of SigLIP's is read: its key is the face key, its record the face record.
     */
    internal fun faceSource(koin: Koin, delivery: ModelDeliveryComponents, downloader: BackgroundModelDownloader): JoinedModelSource {
        val readiness: AiReadiness = koin.get(named(FACE_READINESS))
        val preparer: ModelPreparer = koin.get(named(FACE_PREPARER))
        val preflight: Preflight = koin.get(named(FACE_PREFLIGHT))
        val modelId: String = preparer.modelId
        return JoinedModelSource(
            modelId = modelId,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            retired = readiness.retiredNow,
            readiness = readiness.standing,
            journal = downloader.journalView,
            outcome = downloader.lastOutcome,
            progress = delivery.progress.progress,
            compiling = preparer.compiling,
            probing = preflight.probing,
            prepare = preparer.lastReport,
            prepareRefusal = preparer.lastRefusal,
            preflight = preflight.lastReport,
            readInstalled = {
                when (val index: InstalledIndex = delivery.store.read()) {
                    InstalledIndex.NotAnIndex, is InstalledIndex.Unreadable -> InstalledRead.Unreadable
                    is InstalledIndex.Records -> {
                        val record: InstalledModelData? = index.models.firstOrNull { it.modelId == modelId }
                        InstalledRead.Read(
                            record = record,
                            prepareKey = record?.let(preparer::keyFor),
                            preflightKey = record?.let { iosFacePreflightKey(index.models) },
                            onDeviceBytes = record?.let { it.byteSize + CoreMlDiskBudget.prepareBytes(it.byteSize) } ?: 0L,
                        )
                    }
                }
            },
            loadCatalog = {
                delivery.catalogSource.load()
                    ?.let { catalog ->
                        // ⚠ By id AND runtime: Android's `onnx+mediapipe` entry has the same id.
                        val entry: ModelEntryData? = catalog.models.firstOrNull { it.modelId == modelId && it.runtime == FaceModel.COREML_VISION_RUNTIME }
                        CatalogRead.Read(entry, entry?.let { it.byteSize + CoreMlDiskBudget.prepareBytes(it.byteSize) })
                    }
                    ?: CatalogRead.Unavailable
            },
            startDownload = { id, allowsCellular, admitted -> downloader.download(id, allowsCellular) { admitted() } },
            cancelDownload = { id -> downloader.cancel(id) },
            recheck = { recheck(koin) },
            isRetired = readiness::isRetired,
            // ⚠⚠ Switched off: the embedder is let go, and the operation People reads runs its
            // withdrawal-first deletion now rather than at the next People or pass. ⚠ It deletes only
            // for an EXPLICIT no (`inferenceConsentWithdrawn`); a row that is absent deletes nothing.
            facesWithdrawn = {
                koin.getOrNull<VisionCoreMlFaceBackend>()?.closeSessions()
                runCatching { koin.get<TenshuClient>().inferenceService.faceGroups(1) }
                    .onFailure { println("PhotosOnDeviceAi -> the withdrawn face data was not deleted now: $it") }
            },
            readOffered = { entry -> readiness.answerFor(iosFacePreflightKeyOf(entry)) },
            loadAgainOf = { entry ->
                val key: PreflightKey = entry?.let { iosFacePreflightKeyOf(it) } ?: iosFacePreflightKey(koin.get<ModelStore>().installed())
                readiness.loadAgain(key)
            },
        )
    }

    /**
     * Whether this build binds the face backend (`ios-face-grouping.md` FD10): the face flag **and**
     * everything [admitted] asks — the AI flag and both static gates. ⚠ Faces ride SigLIP's pass,
     * download journal and readiness machinery, so they are never bound where search is not.
     */
    fun facesAdmitted(aiBuildFlag: Boolean, facesBuildFlag: Boolean): Boolean =
        facesBuildFlag && admitted(aiBuildFlag)

    /** The same decision over a stated phone. */
    fun facesAdmitted(aiBuildFlag: Boolean, facesBuildFlag: Boolean, osMajorVersion: Int, physicalMemoryBytes: Long): Boolean =
        facesBuildFlag && admitted(aiBuildFlag, osMajorVersion, physicalMemoryBytes)

    /**
     * The capabilities a switch changes something for **in this graph**: one for each kind of
     * backend it bound, search first. ⚠⚠ What the Models screen offers consent for, so a face switch
     * appears only beside a bound face backend.
     */
    fun features(backends: List<InferenceBackend>): List<InferenceCapability> = buildList {
        if (backends.any { it is CoreMlInferenceBackend }) add(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        if (backends.any { it is VisionCoreMlFaceBackend }) add(InferenceCapability.FACE_CLUSTERING)
    }

    /**
     * The static gates (D12 ⓪ and ①, and the build flag): whether this phone gets a backend at
     * all. Decided once, before anything is offered. ⚠ With the flag off the phone is not asked.
     */
    fun admitted(buildFlag: Boolean): Boolean = admitted(buildFlag) { iosAiDevice() }

    /** The same decision over a stated phone. */
    fun admitted(buildFlag: Boolean, osMajorVersion: Int, physicalMemoryBytes: Long): Boolean =
        admitted(buildFlag) { AiStaticGates.Device(osMajorVersion, physicalMemoryBytes) }

    /**
     * The gates' decision **with its reason**, for the screen that has to say why nothing is
     * offered (§14.18.3, row 2). ⚠ The same `AiStaticGates.decide` that [admitted] asks, asked
     * again: [admitted] still answers only yes or no, and prints the reason. [modelsRepository]
     * reads it.
     */
    fun decision(buildFlag: Boolean): AiStaticGates.Decision = AiStaticGates.decide(buildFlag) { iosAiDevice() }

    /** The same over a stated phone. */
    fun decision(buildFlag: Boolean, osMajorVersion: Int, physicalMemoryBytes: Long): AiStaticGates.Decision =
        AiStaticGates.decide(buildFlag) { AiStaticGates.Device(osMajorVersion, physicalMemoryBytes) }

    /**
     * [OnDeviceModelSource] of an admitted graph, or `null`: the graph was not admitted, or it
     * was given a downloader with no journal to show. ⚠ Why a phone is not offered a model is
     * [decision]'s to say, and whoever shows it makes `ModelsAvailability.NotOffered` from that:
     * a source never does.
     */
    fun modelSource(koin: Koin): OnDeviceModelSource? = runCatching { koin.get<OnDeviceModelSource>() }.getOrNull()

    /**
     * What Photos' Models screen is built over (§14.18.14): the graph's repository on an
     * admitted phone; one that says **why nothing is offered** where the gates refused; and
     * *not connected* while no runtime is installed ([koin] is `null`) or the graph has none.
     *
     * ⚠⚠ The one producer of `ModelsAvailability.NotOffered`, from the same decision [admitted]
     * asks. ⚠ Not on the launch path: resolving the repository constructs the graph's source.
     */
    fun modelsRepository(koin: Koin?, buildFlag: Boolean): ModelsRepository = modelsRepository(koin, decision(buildFlag))

    /** The same over a stated phone. */
    fun modelsRepository(koin: Koin?, buildFlag: Boolean, osMajorVersion: Int, physicalMemoryBytes: Long): ModelsRepository =
        modelsRepository(koin, decision(buildFlag, osMajorVersion, physicalMemoryBytes))

    private fun modelsRepository(koin: Koin?, decision: AiStaticGates.Decision): ModelsRepository = when (decision) {
        is AiStaticGates.Decision.Refused -> OnDeviceModelsRepository.notOffered(
            when (decision.reason) {
                AiStaticGates.Reason.BUILD_FLAG_OFF -> ModelsAvailability.NotOffered.Why.NOT_IN_THIS_BUILD
                AiStaticGates.Reason.OS_BELOW_FLOOR -> ModelsAvailability.NotOffered.Why.OS_TOO_OLD
                AiStaticGates.Reason.MEMORY_BELOW_THRESHOLD -> ModelsAvailability.NotOffered.Why.NOT_ENOUGH_MEMORY
            },
            unbound,
        )
        AiStaticGates.Decision.Admitted ->
            koin?.let { graph -> runCatching { graph.get<ModelsRepository>() }.getOrNull() }
                ?: OnDeviceModelsRepository.notConnected(unbound)
    }

    /** For the repositories of no graph, which start nothing in it. The process's. */
    private val unbound = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun admitted(buildFlag: Boolean, device: () -> AiStaticGates.Device): Boolean =
        when (val decision: AiStaticGates.Decision = AiStaticGates.decide(buildFlag, device)) {
            AiStaticGates.Decision.Admitted -> true
            is AiStaticGates.Decision.Refused -> {
                if (decision.reason != AiStaticGates.Reason.BUILD_FLAG_OFF) {
                    println("PhotosOnDeviceAi -> not offered on this device: ${decision.reason}")
                }
                false
            }
        }

    /**
     * At launch and on every return to the foreground, after the stores' own check, in this order:
     * 1. readiness's fourth writer: a new OS build or build constant voids the verdict held;
     *    then the download journal ([PhotosModelDownloads.resume]);
     * 2. the preflight (writer 2), which probes only a prepared model that has no verdict, and
     *    for which this call is one foreground (D12: three slow probes on separate foregrounds);
     * 3. the text tower's warm-up (§3.2), at launch and after a pass, **when the user has agreed
     *    to search by description on this device** ([searchConsented]) and the model is `Ready`.
     *
     * ⚠ Off the main thread: the first call reads the model index, and a probe and a warm-up
     * block in Core ML for as long as a tower takes to open.
     *
     * @param launch whether this is the launch's check rather than a return to the foreground.
     */
    suspend fun recheck(koin: Koin, launch: Boolean = false) {
        // ⚠⚠ One sequence at a time for a graph, and a request made while one runs causes at most
        // one more: otherwise the launch's check and a rehydration's run side by side, each
        // counting a foreground for the probe.
        val runs: CoalescedRuns = runCatching { koin.get<CoalescedRuns>(named(RECHECKS)) }.getOrNull()
            ?: return recheckOnce(koin, launch)
        runs.request(launch)
    }

    /** One check: the journal, the prepare step, the probe, the warm-up. ⚠ Called through [recheck]. */
    internal suspend fun recheckOnce(koin: Koin, launch: Boolean) {
        // ⚠ The foreground pass's driver follows the runner and the phone from the first check on.
        koin.get<ForegroundIndexing>()
        koin.get<AiReadiness>().fingerprintChecked()
        // ⚠ The face pair's own writer 4, where faces are bound: a new OS build or Vision
        // revision voids its verdict, and nothing of SigLIP's is read or written by it.
        koin.getOrNull<AiReadiness>(named(FACE_READINESS))?.fingerprintChecked()
        // The download journal: what was delivered while this app was suspended or dead is
        // verified from disk and installed, through readiness's writer 3 (§14.11).
        runCatching { PhotosModelDownloads.resume(koin) }
            .onFailure { println("PhotosOnDeviceAi -> the model download was not resumed: $it") }
        // The prepare step, then the probe the STATE says is owed (§14.17): one sequence for
        // every trigger, so a commit that compiled nothing (an update that keeps both towers
        // and changes the vocabulary) is probed like any other, without a scene change.
        // ⚠ A tower the probe could not load is compiled once more and probed again, and that
        // second answer is the one that stands.
        val checked: ModelPreparer.Checked = koin.get<ModelPreparer>().check { pastTheBackoff ->
            // ⚠⚠ In WHICH foreground of the scene: the same number for every check made in it.
            // ⚠ Past the backoff when the towers were compiled a second time and no probe of
            // them has run yet (the scene left before the one that was to follow).
            val foreground: Long? = PhotosOnDeviceAi.foregroundEpoch()
            val preflight: Preflight = koin.get()
            val report: PreflightReport =
                if (pastTheBackoff) preflight.recompiledReport(foreground) else preflight.foregroundedReport(foreground)
            // ⚠⚠ And with what it is about: the key the probe ran under, AS IT IS. Nothing is
            // left out of it on the way: a narrower identity is another install's too.
            ModelPreparer.Probed(about = report.key, outcome = report.outcome)
        }
        searchAfterTheCheck(koin, launch, checked)
        // ⭐ F6: the face model's own prepare and probe, after search's and in the same sequence,
        // so the two never compile or probe side by side. ⚠ Where faces are bound, and only then.
        faceCheck(koin)
    }

    /** SigLIP's tail of a check: the text tower let go, or warmed, by search's consent. */
    private suspend fun searchAfterTheCheck(koin: Koin, launch: Boolean, checked: ModelPreparer.Checked) {
        // ⚠ Search switched off, here or on another device of the realm: what was warmed for it
        // is let go (§14.18.15). The Models screen's own switch lets go at once.
        if (!searchConsented(koin)) return koin.get<CoreMlInferenceBackend>().closeText()
        if (!PhotosOnDeviceAi.warmsAfter(launch, checked.probed)) return
        // ⚠ Whether the model is `Ready` is the backend's to say: `warmText` opens nothing for a
        // backend that does not serve.
        // ⚠⚠ Asked here rather than assumed: no model is loaded for search on behalf of
        // a user who has not switched search on. Without consent the first consented query
        // opens the tower instead, and pays the open (24–88 ms once the OS has it cached, §2.2).
        koin.get<CoreMlInferenceBackend>().warmText()
    }

    /**
     * The face model's prepare, then its probe when the face slot awaits a verdict (F6): the same
     * [ModelPreparer.check] as SigLIP's, over the face preparer and the face preflight. ⚠ Nothing is
     * warmed afterwards: the embedder opens with the first face.
     * ⚠ A failure is said and the check goes on: search's half has already run.
     */
    internal suspend fun faceCheck(koin: Koin) {
        val preparer: ModelPreparer = koin.getOrNull(named(FACE_PREPARER)) ?: return
        runCatching {
            preparer.check { pastTheBackoff ->
                val foreground: Long? = foregroundEpoch()
                val preflight: Preflight = koin.get(named(FACE_PREFLIGHT))
                val report: PreflightReport =
                    if (pastTheBackoff) preflight.recompiledReport(foreground) else preflight.foregroundedReport(foreground)
                ModelPreparer.Probed(about = report.key, outcome = report.outcome)
            }
        }.onFailure { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            println("PhotosOnDeviceAi -> the face model's check failed: $failure")
        }
    }

    /**
     * Whether the user has agreed to search by description, for the realm **and** on this
     * device: the two switches `photosLike` itself refuses without (`hasInferenceConsent`).
     *
     * Read the way an operation reads them, through this app's own [ExecutionContext], which
     * the client registry mints outside an operation too (`ProxyModule`'s task executor resolves
     * it the same way). The read needs the context's database and nothing of a running task.
     *
     * ⚠⚠ **Absence is NO, and so is anything that goes wrong:** no row, a realm that is locked
     * or not provisioned, a registry or a device id that cannot be resolved, a read that throws.
     */
    internal fun searchConsented(koin: Koin): Boolean = runCatching {
        // A test's stand-in for the read; nothing binds one in production.
        koin.getOrNull<SearchConsent>()?.let { return@runCatching it.given() }
        val context: ExecutionContext =
            koin.get<ClientRegistry>().getExecutionContext(koin.get<String>(named("platformPackageName")))
        context.hasInferenceConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH, koin.get<SyncManager>().currentDeviceId)
    }.getOrElse {
        println("PhotosOnDeviceAi -> consent could not be read, so it is not assumed: $it")
        false
    }

    /**
     * The owner's runtime is being torn down. Two halves, and only the first is waited for:
     * 1. **Now, on the caller's thread:** readiness is retired. Nothing is served from this graph
     *    again and no writer brings it back ([AiReadiness.retire]). It takes no lock.
     * 2. **Afterwards, on [teardown]:** the pass is cancelled and both towers are closed
     *    ([AiReadiness.unloadRetired]), however long a prediction in flight holds one.
     *
     * ⚠⚠ Called **before** the stores are relinquished, from the main thread (Photos' `adopt()`).
     * Nothing here may block it: a close waits, uninterruptibly, for a prediction that holds the
     * tower.
     *
     * ⚠ What a prediction in flight can still do once the stores are given up: finish, and be
     * thrown away. The backend asks readiness again after every prediction, so the vector of a
     * retired graph never leaves it and the indexer has nothing to store. The pass itself is
     * stopped, within its own budget, by the relinquish that follows.
     *
     * @return the unloading, for a test to wait on; `null` when this graph has no readiness.
     */
    fun retire(koin: Koin): Job? {
        val readiness: AiReadiness = runCatching { koin.get<AiReadiness>() }.getOrNull() ?: return null
        // ⚠⚠ The face pair's too, first and on this thread: a torn-down graph claims no face either.
        val faces: AiReadiness? = runCatching { koin.getOrNull<AiReadiness>(named(FACE_READINESS)) }.getOrNull()
        faces?.retire()
        // ⚠ Retired first, before anyone is told. [PendingUnloads.retire] retires again inside its
        // lock, with the publishing. Early is the safe side: a party that reads *retired* waits
        // for no unload. The order that must never be seen is published and not yet retired.
        readiness.retire()
        // ⚠⚠ And whoever waits on this graph's model download is told, now: a retired graph
        // completes nothing, and the next graph's downloader has waiters of its own. It waits
        // for nothing and touches neither the journal nor a file.
        PhotosModelDownloads.downloader(koin)?.tornDown()
        // ⚠⚠ And the Models screens over this graph are told *not connected* now, and nothing of
        // their repository goes on. It waits for nothing.
        runCatching { koin.get<RetiredWithTheGraph>() }.getOrNull()?.retire()
        val preflight: Preflight? = runCatching { koin.get<Preflight>() }.getOrNull()
        // ⚠ Only one this graph built: resolving it here would construct the face probe (and its
        // readiness) inside a graph being torn down.
        val facePreflight: Preflight? = builtFacePreflight(koin)
        // ⚠ The foreground pass's driver goes with the graph; its pass is stopped by the unload.
        runCatching { koin.get<CoroutineScope>(named(INDEXING_SCOPE)).cancel() }
        // ⚠ The face embedder is unloaded inside the same unload, so the next graph waits for it too.
        return unloads.retire(readiness, teardown) {
            preflight?.awaitSessionsClosed()
            facePreflight?.awaitSessionsClosed()
            faces?.let { runCatching { it.unloadRetired() }.onFailure { failure -> println("PhotosOnDeviceAi -> the face embedder was not unloaded: $failure") } }
        }
    }

    /**
     * Whether a check goes on to warm the text tower (still only with search consented, and
     * only for a backend that serves): at launch, and after a **pass**. ⚠ The check has one
     * probe (`ModelPreparer.check`), so there is one answer to read; when a tower was compiled
     * a second time it is the answer of the probe after that.
     */
    fun warmsAfter(launch: Boolean, probed: PreflightOutcome): Boolean =
        launch || probed is PreflightOutcome.Passed

    /**
     * Whether Photos is in the foreground, as the scene phase last said
     * ([foreground]). ⚠ `false` until told: a process is not assumed to be in the foreground.
     */
    fun inForeground(): Boolean = scene.active()

    /**
     * The scene phase, from the app: what [inForeground] answers. The process's, not a graph's.
     * ⚠⚠ Each change to the foreground is a **new foreground of the scene** ([foregroundEpoch]):
     * told twice that it is active, it is still the same one.
     */
    fun foreground(now: Boolean) {
        scene.set(now)
    }

    /**
     * [foreground], and the graph's readiness told first ([AiReadiness.sceneChanged]): a load of
     * the towers that is frozen now that the scene leaves, and then ended by iOS, is not counted
     * as one Photos did not survive in front of the user (§14.5). ⚠ Written before the scene
     * moves, on the caller's thread, while the process is certainly alive.
     */
    fun sceneChanged(koin: Koin?, now: Boolean) {
        val was: Long? = scene.number()
        koin?.let { graph -> runCatching { graph.get<AiReadiness>().sceneChanged(active = now, leftForeground = was) } }
            ?.onFailure { println("PhotosOnDeviceAi -> the scene's change was not recorded: $it") }
        koin?.let { graph -> runCatching { graph.getOrNull<AiReadiness>(named(FACE_READINESS))?.sceneChanged(active = now, leftForeground = was) } }
            ?.onFailure { println("PhotosOnDeviceAi -> the scene's change was not recorded for faces: $it") }
        scene.set(now)
        // ⚠⚠ After the scene moved, so the pass's own bar already says no at its next photo:
        // cancelled, both towers let go, inside a background window (§3.3, §14.19).
        if (!now) {
            koin?.let { graph -> runCatching { graph.get<ForegroundIndexing>().leftForeground() } }
                ?.onFailure { println("PhotosOnDeviceAi -> the pass was not stopped on leaving the foreground: $it") }
            // ⚠ PR-6: the overnight wake is armed now, if something is asked and photos are left.
            // Returns at once; the read is off this thread, inside a background window.
            koin?.let { graph -> runCatching { graph.get<ProcessingWake>().leftForeground() } }
                ?.onFailure { println("PhotosOnDeviceAi -> the processing wake was not armed: $it") }
            // ⚠ PR-6b: a batch of new arrivals waiting to start never starts behind Photos.
            koin?.let { graph -> runCatching { graph.get<ForegroundArrivals>().leftForeground() } }
                ?.onFailure { println("PhotosOnDeviceAi -> the arrivals batch was not stopped: $it") }
        }
        // ⚠ An ask still waiting for the phone is read again in front — a wait kept across a
        // continued task's release would otherwise have spent its re-check in the background.
        if (now) {
            koin?.let { graph -> runCatching { graph.get<ForegroundIndexing>().enteredForeground() } }
                ?.onFailure { println("PhotosOnDeviceAi -> the waiting pass was not re-checked in front: $it") }
            // ⚠ PR-6b: what arrived while Photos was away, as the library watch says.
            koin?.let { graph -> runCatching { graph.get<ForegroundArrivals>().enteredForeground() } }
                ?.onFailure { println("PhotosOnDeviceAi -> new arrivals were not looked for: $it") }
        }
    }

    /** The one pass of the process, whatever graph runs it ([PassExclusion]). */
    internal val passSlot: PassExclusion = PassExclusion.shared()

    /**
     * What a processing wake would find to do (PR-6, §16). ⚠⚠ Read the way the start operation reads
     * it, per half (IN-D3): indexing not paused here, and at least one half the pass does that is both
     * **permitted** — its two switches on, absence is NO — and **served**, a backend claiming it.
     * Then whether photos are left: unknown is *left*, and only a pass of this runner that ran to its
     * end ([PassOutcome.COMPLETED]) says the library is current.
     *
     * ⚠ Off the main thread: it reads the realm's preference rows and resolves the runner, whose
     * construction opens the stores.
     */
    internal fun wakeWork(koin: Koin): WakeWork {
        val context: ExecutionContext =
            koin.get<ClientRegistry>().getExecutionContext(koin.get<String>(named("platformPackageName")))
        val device: String = koin.get<SyncManager>().currentDeviceId
        if (context.isIndexingPaused(device)) return WakeWork.NOT_ASKED
        val permitted = context.permittedInference(koin.get<InferenceBackendRegistry>(), device)
        if (MEDIA_INDEX_CAPABILITIES.none { it in permitted }) return WakeWork.NOT_ASKED
        return workLeft(koin.get<MediaIndexRunner>().standing.value, koin.getOrNull<ArrivalsMissed>()?.since)
    }

    /**
     * Whether photos may be left, from the runner's [standing]. ⚠ Unknown is left. ⚠⚠ Only a
     * **library walk** that ran to its end says none are ([IndexPassResult.libraryWalk]): a
     * new-arrivals batch (PR-6b) is [PassOutcome.COMPLETED] to the runner over its paths alone — capped
     * or not, it says nothing of the rest of the library, and reading it as "current" armed no wake,
     * so what the cap left, or a backlog the batch never looked at, waited for a press.
     */
    fun workLeft(standing: PassStanding, missedSince: Long? = null): WakeWork = when (standing) {
        is PassStanding.Running -> WakeWork.LEFT
        is PassStanding.Idle -> {
            val last: PassEnding? = standing.last
            val walked: Boolean = last is PassEnding.Finished && last.result.libraryWalk
            // ⚠⚠ A look that may have missed arrivals ([ArrivalsMissed]) is answered only by a walk
            // that started after it.
            val after: Boolean = missedSince == null || (last != null && last.pass > missedSince)
            if (walked && after && last?.let { passOutcomeOf(it) } == PassOutcome.COMPLETED) WakeWork.NONE_LEFT else WakeWork.LEFT
        }
    }

    /** With no cursor yet, the modification log's end becomes it ([ArrivedPhotoPaths.markEndIfUnmarked]). */
    internal fun markArrivalsLogEnd(koin: Koin) {
        val context: ExecutionContext =
            koin.get<ClientRegistry>().getExecutionContext(koin.get<String>(named("platformPackageName")))
        koin.get<ArrivedPhotoPaths>().markEndIfUnmarked(context)
    }

    /** Whether a half may run (consent, model, pause) — what the arrivals trigger asks before it reads the log. */
    internal suspend fun arrivalsMayRun(koin: Koin): Boolean {
        val context: ExecutionContext =
            koin.get<ClientRegistry>().getExecutionContext(koin.get<String>(named("platformPackageName")))
        return koin.get<MediaIndexer>().anyHalfMayRun(context)
    }

    /**
     * A new-arrivals batch (PR-6b): the runner's capped batch over the queued paths alone — no walk,
     * no reconcile — at most [NEW_ARRIVALS_BATCH_CAP] photos processed; the queue settled with what
     * became of each. `null` when a pass is already running. ⚠⚠ The runner checks first that a half
     * may run — its two switches on (absence is NO) and a backend serving it — and the arrivals bar (in
     * front, heat, Low Power Mode, a known battery); a refusal still deletes what a withdrawal owes.
     */
    internal suspend fun runArrivalsBatch(koin: Koin): PathsBatch? {
        val context: ExecutionContext =
            koin.get<ClientRegistry>().getExecutionContext(koin.get<String>(named("platformPackageName")))
        val queue: ArrivalQueue = koin.get()
        val asked: Int = queue.size
        return koin.get<MediaIndexRunner>().runArrivalsBatch(context, queue, NEW_ARRIVALS_BATCH_CAP)
            ?.also { batch ->
                val result: IndexPassResult = batch.result
                println(
                    "[photos-arrivals] asked=$asked visited=${result.imagesVisited} embedded=${result.embedded} " +
                        "faces=${result.faceRecordsWritten} capped=${result.batchCapReached} deferred=${result.deferred} " +
                        "left=${queue.size}",
                )
            }
    }

    /**
     * What arrived since this graph last looked (`ArrivedPhotoPaths`), into the arrivals queue, and
     * whether anything is queued. ⚠ Off the main thread: two store reads and a small file write.
     *
     * ⚠⚠ The queue is flushed here, and `runArrivalsBatch` flushes it after settling, so on iOS every
     * change is on disk before Photos can be suspended: a leave cancels a batch (which then settles
     * nothing) and leaves the flushed queue for the next foreground or the wake. Nothing adds to it
     * anywhere else in this graph.
     */
    internal fun lookForArrivals(koin: Koin): Boolean {
        val context: ExecutionContext =
            koin.get<ClientRegistry>().getExecutionContext(koin.get<String>(named("platformPackageName")))
        val queue: ArrivalQueue = koin.get()
        val look: ArrivedPhotoPaths.Look = koin.get<ArrivedPhotoPaths>().read(context)
        look.paths.forEach(queue::add)
        // ⚠⚠ Arrivals this look could not name (no cursor yet, the cursor's row moved, a burst past the
        // window) are the wake's: the next leave arms it, whatever the last walk said.
        if (look.missed) koin.get<ArrivalsMissed>().record(koin.get<MediaIndexRunner>().standing.value)
        queue.flush()
        return queue.size > 0
    }

    /** A test's debounce for the arrivals batch (a `Long`, milliseconds). Nothing binds it in production. */
    const val ARRIVALS_DEBOUNCE: String = "photosArrivalsDebounceMs"

    /**
     * A wake's pass: the runner's **scheduled** pass (`manual = false`, so the scheduled bar, which
     * says yes only inside the wake), run to its end. ⚠⚠ Never through the `MediaIndexPass`
     * operation: on iOS that op is the retirement of the old schedule row (`ios-on-device-inference.md` §13.4 item 8).
     * A deferred or paused report is [PassOutcome.REFUSED]; a throw reaches the wake, which answers it
     * [PassOutcome.FAILED]; a cancellation (the wake's expiry) cancels the pass.
     */
    internal suspend fun runWakePass(koin: Koin): PassOutcome {
        val context: ExecutionContext =
            koin.get<ClientRegistry>().getExecutionContext(koin.get<String>(named("platformPackageName")))
        return runWakePassOver(koin.get<MediaIndexRunner>(), context) { result ->
            // ⚠ What the device runbook's three nights record: how much of the library a wake got through.
            val summary = "visited=${result.imagesVisited} embedded=${result.embedded} current=${result.alreadyCurrent} deferred=${result.deferred} paused=${result.paused}"
            PhotosProcessingWake.lastPass = summary
            println("[photos-wake] pass $summary")
        }
    }

    /** *Index now*, through the operation, typed. ⚠ Every failure is answered, never thrown. */
    internal suspend fun startPass(client: TenshuClient): StartAnswer = try {
        client.inferenceService.startMediaIndexPass().fold(
            onSuccess = { StartAnswer.Started },
            onFailure = { error -> refusalOf(error) },
        )
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        println("PhotosOnDeviceAi -> the pass could not be started: $failure")
        StartAnswer.Failed
    }

    /** ⚠ Its own function: K/N 2.4.10 miscompiles an in-place type test of some values (`tech-debt.md`). */
    private fun refusalOf(error: org.tenshu.core.TError): StartAnswer {
        val refused: IndexPassRefused = error as? IndexPassRefused ?: return StartAnswer.Failed
        return StartAnswer.Refused(refused.reason.get())
    }

    /**
     * Which foreground of the scene this is, or `null` while Photos is not in one: the number
     * the preflight counts a slow probe in (D12: three slow probes on three **separate**
     * foregrounds). ⚠ It moves when the scene becomes active and at no other time, so every
     * check made in one foreground carries the same number.
     * ⚠ Memory: a new process starts at none, and its first foreground is a new one.
     */
    fun foregroundEpoch(): Long? = scene.number()

    /** ⚠⚠ One value for both: [SceneForeground]. */
    private val scene = SceneForeground()

    /** Which foreground of the scene has had its slow probe. The process's, like the scene. */
    internal val slowProbes = SlowProbes()

    /** The face model's own (F6): a slow face probe must not spend search's foreground, nor the reverse. */
    internal val faceSlowProbes = SlowProbes()

    /**
     * Suspends while a torn-down graph of this process is still unloading its towers. What every
     * tower-opening path of a live graph awaits first.
     *
     * ⚠⚠ Why: the teardown does not wait for the unload (main thread), and the next `install`
     * does not either. Without this the new graph opens its pair while the old pair is still
     * held by a prediction in flight: two pairs resident, on a phone, is the jetsam case.
     *
     * A query or a pass on the new graph therefore **waits**; it is not refused. It is bounded by
     * its caller (search gives a source 60 s and then says it could not look, with a retry).
     * ⚠ If the old prediction never returns, the new graph never loads a model in this process.
     */
    suspend fun awaitPendingUnload(mine: AiReadiness) {
        unloads.awaitFor(mine)
    }

    /** Whether an unload is still pending. For a test. */
    fun unloadPending(): Boolean = unloads.pending()

    /** ⚠ The process's, like [teardown]: one set of models per process, whatever graphs exist. */
    private val unloads = PendingUnloads()



    /** ⚠ The process's, never an assembly's: it has to outlive the runtime it tears down. */
    private val teardown = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Whether the preflight found this phone unsuitable: what turns search's *"there isn't a
     * model yet"* into *"isn't available on this iPhone yet"*. A snapshot read.
     */
    fun unsuitable(koin: Koin): Boolean = koin.get<AiReadiness>().current() == AiReadinessState.Unsuitable
}

@OptIn(ExperimentalUuidApi::class)
private fun randomSuffix(): String = Uuid.random().toString()

/**
 * The process's continued-processing scheduler: what Photos' Swift installs on iOS 26+, behind
 * the build flag and the static gates. ⚠ The process's, not a graph's, like the model transfers:
 * the `BGTaskScheduler` outlives every runtime.
 */
object PhotosIndexNow {
    @kotlin.concurrent.Volatile
    private var installed: ContinuedTaskScheduler? = null

    /**
     * Installs [scheduler]. ⚠ Never declined (`ios-shared-store-sqlite.md` B.23): the launched task
     * takes the shared store's task token in `PhotosLocalRuntime.onIndexNowTaskLaunched`.
     */
    fun install(scheduler: ContinuedTaskScheduler) {
        installed = scheduler
    }

    /** For a test: no scheduler, as in a process that never installed one. */
    fun uninstall() {
        installed = null
    }

    internal fun scheduler(): ContinuedTaskScheduler? = installed
}

/**
 * The process's processing-wake scheduler (PR-6): what Photos' Swift installs at launch, behind the
 * build flag and the static gates. ⚠ The process's, not a graph's, like [PhotosIndexNow]'s.
 */
object PhotosProcessingWake {
    @kotlin.concurrent.Volatile
    private var installed: WakeScheduler? = null

    fun install(scheduler: WakeScheduler) {
        installed = scheduler
    }

    /** For a test: no scheduler, as in a process that never installed one. */
    fun uninstall() {
        installed = null
    }

    internal fun scheduler(): WakeScheduler? = installed

    /** The last wake pass's counts, for the wake's journal line (`how-to/photos-processing-wake.md`). */
    @kotlin.concurrent.Volatile
    var lastPass: String? = null
}

/** Whether [koin]'s graph was torn down. ⚠ Readiness is built at assembly, so asking it builds nothing. */
private fun graphRetired(koin: Koin): Boolean = runCatching { koin.get<AiReadiness>().isRetired() }.getOrDefault(true)

/**
 * The face [Preflight] of a graph, once that graph has constructed it (`ios-face-grouping.md` F6).
 * ⚠ Resolving this constructs nothing else: it is how search's loads and [PhotosOnDeviceAi.retire]
 * reach the face probe without building it (and the face readiness under it) for nothing.
 */
internal class FacePreflightBuilt {
    @kotlin.concurrent.Volatile
    var preflight: Preflight? = null
}

/** Stands in for the consent read in a test: [PhotosOnDeviceAi.searchConsented]. */
fun interface SearchConsent {
    fun given(): Boolean
}

/** The compiled cache's directory, inside the owner's inference database: `<root>/inference/coreml`. */
private const val COMPILED_BASE = "inference/coreml"
private const val RECHECKS = "onDeviceAiRechecks"
private const val INDEXING_SCOPE = "onDeviceAiIndexing"

/** What the foreground pass reads of the phone: [IosIndexingSignals], or a test's. */
interface PhotosIndexingSignals {
    fun read(foreground: Boolean): DeviceIndexingState
    val changes: Flow<Unit>
    val memoryWarnings: Flow<Unit>
    fun observe()
}

private object SystemIndexingSignals : PhotosIndexingSignals {
    override fun read(foreground: Boolean): DeviceIndexingState = IosIndexingSignals.read(foreground)
    override val changes: Flow<Unit> get() = IosIndexingSignals.changes
    override val memoryWarnings: Flow<Unit> get() = IosIndexingSignals.memoryWarnings
    override fun observe() = IosIndexingSignals.observe()
}

/**
 * `beginBackgroundTask`: time to stop the pass after Photos has left the foreground. ⚠ The
 * expiration ends it too, and each is ended once, whichever comes first.
 */
private object AppBackgroundWindow : BackgroundWindow {
    private class Window {
        val ended = AtomicInt(0)
        var id: ULong = UIBackgroundTaskInvalid
    }

    override fun begin(name: String): Any {
        val window = Window()
        window.id = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) { end(window) }
        return window
    }

    override fun end(handle: Any?) {
        val window: Window = handle as? Window ?: return
        if (!window.ended.compareAndSet(0, 1) || window.id == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(window.id)
    }
}

/**
 * [ModelStoreChanges] for a graph that serves **two** models: a commit or a removal runs inside
 * the writer 3 of the readiness it is about, and of no other; a change to any third model runs as it
 * is. ⚠ A change that does not say which model stands both down, which is the safe side.
 */
private class ByModelChanges(private val siglip: AiReadiness, private val faces: AiReadiness) : ModelStoreChanges {
    override suspend fun <T> changing(change: suspend () -> T): T = siglip.storeChanging { faces.storeChanging(change) }

    override suspend fun <T> changing(modelId: String, change: suspend () -> T): T = when (modelId) {
        VisionModel.PinnedSigLip.modelId -> siglip.storeChanging(change)
        FaceModel.AuraFaceCoreMl.modelId -> faces.storeChanging(change)
        else -> change()
    }
}
