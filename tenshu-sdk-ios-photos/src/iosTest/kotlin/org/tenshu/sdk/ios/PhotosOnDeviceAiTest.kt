@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi::class)

package org.tenshu.sdk.ios

import org.tenshu.inference.proxy.WakeWork
import org.tenshu.inference.proxy.IndexPassResult
import org.tenshu.inference.proxy.PassEnding
import app.tenshu.control.inference.ForegroundIndexStanding
import app.tenshu.control.ui.screens.models.ModelsMessage
import app.tenshu.control.ui.screens.models.ModelsRepository
import app.tenshu.control.ui.screens.models.ModelsViewModel
import kotlinx.coroutines.launch
import app.tenshu.control.di.INFERENCE_FILE_DATABASE
import app.tenshu.control.di.OwnedInferenceStores
import app.tenshu.control.di.PhotosOnDeviceAi
import app.tenshu.control.di.PhotosIndexingSignals
import kotlinx.coroutines.flow.MutableSharedFlow
import org.tenshu.inference.proxy.BackgroundWindow
import org.tenshu.inference.proxy.DeviceIndexingState
import org.tenshu.inference.proxy.ArrivalQueue
import org.tenshu.inference.proxy.InferenceStoreCustody
import org.tenshu.inference.proxy.ForegroundArrivals
import org.tenshu.inference.proxy.ForegroundIndexing
import org.tenshu.inference.proxy.ForegroundIndexingState
import org.tenshu.inference.proxy.IndexNowAnswer
import org.tenshu.inference.proxy.IndexingBlock
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.PassExclusion
import org.tenshu.inference.proxy.PassStanding
import org.tenshu.inference.proxy.ThermalState
import app.tenshu.control.di.SearchConsent
import app.tenshu.photos.model.SemanticSearchStatus
import app.tenshu.photos.ui.screens.search.PhotoSearchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.entity.Entity
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.RealmSetting
import org.tenshu.sync.common.SyncManager
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.SemanticSearchUnavailable
import org.tenshu.inference.client.inferenceService
import org.tenshu.file.client.fileService
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.inference.photosLike
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.AiStaticGates
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.proxy.ImageEmbeddingOutcome
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.IndexableMedia
import org.tenshu.inference.proxy.MediaSource
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.Preflight
import org.tenshu.inference.proxy.PreflightDevice
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PreflightOutcome
import org.tenshu.inference.proxy.PreflightProbe
import org.tenshu.inference.proxy.PreflightProbeLookup
import org.tenshu.inference.proxy.PreparedModel
import org.tenshu.inference.proxy.RuntimeFingerprint
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.coreml.CoreMlCompiler
import org.tenshu.inference.proxy.coreml.CoreMlInferenceBackend
import org.tenshu.inference.proxy.coreml.CoreMlModelCache
import org.tenshu.inference.proxy.coreml.CoreMlSessionOpener
import org.tenshu.inference.proxy.coreml.CoreMlSessionPort
import org.tenshu.inference.proxy.coreml.CoreMlTowerSpec
import org.tenshu.inference.proxy.coreml.coreMlModelFacts
import org.tenshu.inference.proxy.coreml.iosPreflightKey
import org.tenshu.inference.proxy.coreml.iosRuntimeFingerprint
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `ios-on-device-inference.md` §14: Photos binds the Core ML backend behind a
 * build flag and two static gates, once, and everything dynamic is `AiReadiness`.
 *
 * ⭐ Every graph here is `PhotosLocalRuntime.assemble`'s own, as in [PhotosOwnedInferenceStoresTest].
 * ⚠ Real dispatchers and `Main` redirected, for [DriveLocalRuntimeTest]'s reasons.
 */
@OptIn(ExperimentalEncodingApi::class)
class PhotosOnDeviceAiTest {

    @BeforeTest
    fun redirectMain() {
        Dispatchers.setMain(Dispatchers.Default)
        // ⚠ The scene is active, as it is whenever a user sees Photos: a probe is counted in a
        // foreground of the scene, and nothing is probed or compiled without one. A new one for
        // each case (the phase is the process's).
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)
    }

    @AfterTest
    fun restore() {
        // ⚠ The surface and the installed runtime are process globals: never leave one behind.
        // ⚠ Only an installed one: with nothing installed, `deprovision()` erases by path in the
        // host's REAL container and purges its real Keychain bucket.
        if (PhotosLocalRuntime.isInstalled()) PhotosLocalRuntime.deprovision()
        PhotosSurface.installNotConnected()
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        Dispatchers.resetMain()
    }

    private val files = NSFileManager.defaultManager

    private fun temp(name: String): String = NSTemporaryDirectory() + "$name-" + Random.nextLong().toString(16)

    private fun material() = RealmKeyMaterial(
        sharedIdentityKey = Random.nextBytes(32),
        sharedVolumeKey = Random.nextBytes(32),
        deviceSigningBlob = (
            Base64.encode(Random.nextBytes(32)) + "|" + Base64.encode(Random.nextBytes(32))
            ).encodeToByteArray(),
        ownerId = "owner-a",
        deviceId = "device-1",
        realmEncryptionPublicKey = Random.nextBytes(32),
        realmEncryptionPrivateKey = Random.nextBytes(32),
    )

    private val eightGigabytes = 8L * 1024 * 1024 * 1024
    private val admittedPhone = PhotosLocalRuntime.OnDeviceAiInputs(buildFlag = true, osMajorVersion = 17, physicalMemoryBytes = eightGigabytes)

    /**
     * This test's phone's shared replica, one per test: a relaunch is on the same store. ⚠ Its
     * epoch is half the inference realm stamp, so a new replica per process would be a store
     * replaced between launches, and the index would be cleared.
     */
    private val replica: String = temp("photos-ai-replica")

    /** Photos, as a new process over [root]. `null` inputs is production: the Info.plist and the phone. */
    private fun photos(
        root: String = temp("photos-ai"),
        onDeviceAi: PhotosLocalRuntime.OnDeviceAiInputs? = admittedPhone,
        overrides: Module? = null,
    ): PhotosLocalRuntime.Assembly = PhotosLocalRuntime.assemble(
        replicaDirectory = replica,
        secureStorage = InMemorySecureStorageDelegate(),
        handedOffMaterial = material(),
        relayBaseUrl = "http://127.0.0.1:1",
        inferenceRoot = root,
        deviceRealmOwner = { "owner-a" },
        onDeviceAi = onDeviceAi,
        // ⚠⚠ No catalog under any graph of this class: a Models screen here collects the model
        // source, which would otherwise ask the real CDN (`ios-on-device-inference.md` §14.18.17).
        onDeviceAiOverrides = noCatalogThen(overrides),
    )

    private suspend fun eventually(what: String, condition: suspend () -> Boolean) {
        runCatching { withTimeout(30_000) { while (!condition()) delay(20) } }
        assertTrue(condition(), what)
    }

    private suspend fun PhotosLocalRuntime.Assembly.photosLikeRefusal(): String {
        val answer = tenshuClient.inferenceService.photosLike("a dog", 5)
        val refusal = assertIs<TResult.Failure<*>>(answer, "photosLike answered: $answer").error
        return assertIs<SemanticSearchUnavailable>(refusal).reason.get()
    }

    private suspend fun PhotoSearchViewModel.ask(query: String = "dog"): SemanticSearchStatus {
        if (state.value.query == query) retry() else onQueryChanged(query)
        eventually("the search for \"$query\" settled") { state.value.settled && !state.value.searching }
        return state.value.semanticStatus
    }

    private val noModelYet = "Searching photos by what's in them needs a model on this device, and there isn't one yet."
    private val notOnThisIphone = "Search by description isn't available on this iPhone yet."

    // ─── not admitted: the flag, and each static gate ───────────────────────────────────────────

    /** What Photos' graph and its search are when nothing is bound. */
    private suspend fun assertNothingIsBound(photos: PhotosLocalRuntime.Assembly, why: String) {
        val koin: Koin = photos.koin
        assertFalse(photos.onDeviceAi, why)
        assertNull(koin.getOrNull<List<InferenceBackend>>(named("inferenceBackends")), "$why: a backend is bound")
        assertNull(koin.getOrNull<CoreMlInferenceBackend>(), why)
        assertNull(koin.getOrNull<AiReadiness>(), why)
        assertNull(koin.getOrNull<CoreMlModelCache>(), why)
        assertNull(koin.getOrNull<Set<String>>(named("supportedModelRuntimes")), "$why: a model is offered")
        assertNull(koin.getOrNull<IndexingConditions>(named("indexingConditions")), "$why: a pass may run")
        assertNull(koin.getOrNull<ForegroundArrivals>(), "$why: new arrivals may be indexed")
        assertTrue(koin.get<InferenceBackendRegistry>().available.isEmpty(), why)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        assertEquals(SemanticSearchUnavailable.Reasons.NO_BACKEND, photos.photosLikeRefusal(), why)

        PhotosLocalRuntime.install(photos)
        val owned = OwnedViewModels()
        try {
            val search = owned.own(PhotosSurface.search(device = "iPhone"))
            assertEquals(SemanticSearchStatus.NotBuiltHere("iPhone"), search.state.value.semanticStatus, "$why: known before any search")
            // ⚠ Still `NotBuiltHere` once a search has settled: asked, the proxy would have made it
            // `Unavailable("noBackend")`.
            assertEquals(SemanticSearchStatus.NotBuiltHere("iPhone"), search.ask(), "$why: the proxy was asked")
            assertEquals(listOf(notOnThisIphone), search.state.value.layout.notes, why)
        } finally {
            // Joined before the graph it reads is torn down.
            owned.closeAll()
            PhotosLocalRuntime.deprovision()
        }
        owned.assertNothingActive()
    }

    /**
     * ⚠⚠ The fixture's guard, independent of the network: every admitted graph of this class pins
     * no catalog key, with and without a case's own overrides. Without it, the Models screen's
     * case asks the real CDN on every run (`ios-on-device-inference.md` §14.18.17).
     */
    @Test
    fun noGraphOfThisClassTrustsTheRealCatalog() {
        assertEquals("", photos().koin.get<String>(named("modelCatalogPublicKeyHex")))
        assertEquals("", photos(overrides = module { }).koin.get<String>(named("modelCatalogPublicKeyHex")))
    }

    @Test
    fun withTheBuildFlagOffNothingIsBoundAndSearchIsNotBuilt() = runBlocking {
        assertNothingIsBound(
            photos(onDeviceAi = PhotosLocalRuntime.OnDeviceAiInputs(false, osMajorVersion = 26, physicalMemoryBytes = eightGigabytes)),
            "the flag is off",
        )
    }

    /** No inputs at all: what a build reads when its Info.plist does not say `YES`. This host's does not. */
    @Test
    fun aBuildWhoseInfoPlistDoesNotSayYesBindsNothing() = runBlocking {
        assertFalse(PhotosLocalRuntime.onDeviceAiBuildFlag(), "the test host's bundle switches on-device AI on")
        assertNothingIsBound(photos(onDeviceAi = null), "production, with no flag")
    }

    @Test
    fun onlyTheExactYesSwitchesTheFlagOn() {
        listOf<Any?>(null, "", "NO", "no", "yes", "true", "1", "\$(TENSHU_PHOTOS_ON_DEVICE_AI)", 1, true).forEach { raw ->
            assertFalse(PhotosLocalRuntime.onDeviceAiBuildFlag(raw), "'$raw' switched on-device AI on")
        }
        assertTrue(PhotosLocalRuntime.onDeviceAiBuildFlag("YES"))
        assertEquals("TenshuPhotosOnDeviceAi", PhotosLocalRuntime.ON_DEVICE_AI_INFO_KEY)
    }

    @Test
    fun belowIos17NothingIsBoundAndSearchIsNotBuilt() = runBlocking {
        assertNothingIsBound(
            photos(onDeviceAi = PhotosLocalRuntime.OnDeviceAiInputs(true, osMajorVersion = 16, physicalMemoryBytes = eightGigabytes)),
            "iOS 16",
        )
    }

    @Test
    fun belowTheMemoryThresholdNothingIsBoundAndSearchIsNotBuilt() = runBlocking {
        val below: Long = AiStaticGates.PLACEHOLDER_MIN_PHYSICAL_MEMORY_BYTES - 1
        assertNothingIsBound(
            photos(onDeviceAi = PhotosLocalRuntime.OnDeviceAiInputs(true, osMajorVersion = 26, physicalMemoryBytes = below)),
            "too little memory",
        )
        assertFalse(PhotosOnDeviceAi.admitted(buildFlag = true, osMajorVersion = 26, physicalMemoryBytes = below))
        assertTrue(PhotosOnDeviceAi.admitted(buildFlag = true, osMajorVersion = 26, physicalMemoryBytes = below + 1))
    }

    // ─── admitted ───────────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalStdlibApi::class)
    private fun sha256(bytes: ByteArray): String = Sha256Digest().apply { update(bytes, bytes.size) }.digest().toHexString()

    private fun PhotosLocalRuntime.Assembly.fingerprint(): RuntimeFingerprint =
        iosRuntimeFingerprint(koin.get<ModelStore>().installed())

    /** What a verdict on this assembly's install is keyed on: what the graph's readiness computes. */
    private fun PhotosLocalRuntime.Assembly.key(): PreflightKey = iosPreflightKey(koin.get<ModelStore>().installed())

    /** Installs the checkpoint's three artifacts, as the store would after a verified download. */
    private fun PhotosLocalRuntime.Assembly.installModel(generation: String) = installModel(
        listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT, ModelArtifactRole.VOCABULARY)
            .associateWith { role -> "$generation-$role".encodeToByteArray() },
    )

    private fun PhotosLocalRuntime.Assembly.installModel(bytes: Map<String, ByteArray>) {
        val entry = ModelEntryData(
            modelId = VisionModel.PinnedSigLip.modelId,
            name = "SigLIP",
            modelVersion = 1,
            capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
            labelSetVersion = 1,
            runtime = "coreml",
            artifactVersion = 1,
            artifacts = bytes.map { (role, content) -> ModelArtifactData(role, "models/$role", sha256(content), content.size.toLong()) },
            licence = "Apache-2.0",
        )
        assertNotNull(koin.get<ModelStore>().add(entry, bytes))
    }

    /** What the prepare step leaves for the installed towers: a compiled directory and its marker, written last. */
    private fun PhotosLocalRuntime.Assembly.markPrepared(root: String) {
        val fingerprint: RuntimeFingerprint = fingerprint()
        listOf("vision" to fingerprint.visionArchiveSha256!!, "text" to fingerprint.textArchiveSha256!!).forEach { (role, digest) ->
            // ⚠ Under `inference/`: the tree a purge removes when the backup exclusion is lost.
            val directory = "$root/inference/coreml/${digest.take(16)}"
            files.createDirectoryAtPath("$directory/$role.mlmodelc", true, null, null)
            IosFileDatabase(root).writeRawByteArray(
                "inference/coreml/${digest.take(16)}/ready",
                "digest=$digest\nosBuild=os\n".encodeToByteArray(),
            )
        }
    }

    @Test
    fun onAnAdmittedPhoneTheBackendIsBoundAndWithNoModelClaimsNothing() = runBlocking {
        val photos = photos()
        val koin: Koin = photos.koin

        assertTrue(photos.onDeviceAi)
        val backends: List<InferenceBackend> = koin.get(named("inferenceBackends"))
        assertSame<Any>(koin.get<CoreMlInferenceBackend>(), backends.single())
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        PhotosOnDeviceAi.recheck(koin)

        assertEquals(AiReadinessState.NoModel, koin.get<AiReadiness>().current())
        assertTrue(koin.get<InferenceBackendRegistry>().available.isEmpty(), "a backend with no model claimed a capability")
        assertEquals(SemanticSearchUnavailable.Reasons.NO_BACKEND, photos.photosLikeRefusal())
        // ⚠⚠ Bound (§14.19): the foreground bar, and the scheduled one never.
        assertFalse(koin.get<IndexingConditions>(named("indexingConditions")).allowIndexing(), "a scheduled pass may run")
    }

    /**
     * §5's row: readiness flips in both directions with **no relaunch and one graph** — the same
     * assembled runtime, the same backend, the same registry and the same search view model.
     */
    @Test
    fun theBackendIsBoundOnceAndTheSearchLineFollowsReadinessOnOneViewModel() = vmBlocking { owned ->
        val root = temp("photos-ai")
        val photos = photos(root)
        val koin: Koin = photos.koin
        val backend: CoreMlInferenceBackend = koin.get()
        val backends: List<InferenceBackend> = koin.get(named("inferenceBackends"))
        val registry: InferenceBackendRegistry = koin.get()
        val readiness: AiReadiness = koin.get()
        val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH

        PhotosLocalRuntime.install(photos)
        eventually("install checked the stores") { PhotosLocalRuntime.inferenceStoresUsable() }
        val search: PhotoSearchViewModel = owned.own(PhotosSurface.search(device = "iPhone"))
        assertEquals(SemanticSearchStatus.Available, search.state.value.semanticStatus, "the route is AskThisDevice")

        // No model.
        assertEquals(SemanticSearchStatus.Unavailable("noBackend"), search.ask())
        assertEquals(listOf(noModelYet), search.state.value.layout.notes)

        // Installed, not prepared.
        readiness.storeChanging { photos.installModel("a") }
        assertEquals(AiReadinessState.Preparing, readiness.current())
        assertEquals(SemanticSearchStatus.Unavailable("noBackend"), search.ask())

        // Prepared, no verdict.
        photos.markPrepared(root)
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.markerWritten())
        assertNull(registry.backendFor(capability))
        assertEquals(listOf(noModelYet), search.also { it.ask() }.state.value.layout.notes)

        // Unsuitable: the same refusal from the proxy, another sentence.
        assertEquals(AiReadinessState.Unsuitable, readiness.recordVerdict(passed = false, probed = photos.key()))
        assertEquals(SemanticSearchUnavailable.Reasons.NO_BACKEND, photos.photosLikeRefusal())
        assertEquals(SemanticSearchStatus.NotBuiltHere("iPhone"), search.ask())
        assertEquals(listOf(notOnThisIphone), search.state.value.layout.notes)

        // Ready: the backend is found, and the proxy's next answer is its own.
        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = photos.key()))
        assertSame<Any?>(backend, registry.backendFor(capability))
        assertEquals(SemanticSearchUnavailable.Reasons.NO_CONSENT, photos.photosLikeRefusal())
        assertEquals(SemanticSearchStatus.Unavailable("noConsent"), search.ask())

        // A replacement: not served from the commit, and not on its predecessor's pass.
        readiness.storeChanging { photos.installModel("b") }
        assertNull(registry.backendFor(capability), "the old model is served after the store replaced it")
        photos.markPrepared(root)
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.markerWritten())
        assertEquals(SemanticSearchStatus.Unavailable("noBackend"), search.ask())

        // ⚠ One graph throughout: nothing was rebound to get here.
        assertSame(backends, koin.get<List<InferenceBackend>>(named("inferenceBackends")))
        assertSame(backend, koin.get<CoreMlInferenceBackend>())
        assertSame(registry, koin.get<InferenceBackendRegistry>())
        assertSame(readiness, koin.get<AiReadiness>())
    }

    // ─── the prepare step, through Photos' own binding ──────────────────────────────────────────

    /** Stands in for `MLModel.compileModel` and the first open: leaves a directory, loads nothing. */
    private class CompilesNothing : CoreMlCompiler {
        override fun compile(packagePath: String): String {
            val temporary = "${NSTemporaryDirectory()}compiled-${Random.nextLong().toString(16)}.mlmodelc"
            NSFileManager.defaultManager.createDirectoryAtPath(temporary, true, null, null)
            NSFileManager.defaultManager.createFileAtPath("$temporary/model.mil", null, null)
            return temporary
        }

        override fun firstOpen(compiledPath: String, spec: CoreMlTowerSpec) = Unit
    }

    /** A ustar archive holding one `<stem>.mlpackage` directory, as the export script writes a tower. */
    private fun towerArchive(stem: String): ByteArray {
        fun entry(name: String, content: ByteArray, type: Char): ByteArray {
            val header = ByteArray(512)
            fun put(offset: Int, text: String) = text.encodeToByteArray().copyInto(header, offset)
            put(0, name)
            put(100, "0000644")
            put(108, "0000000")
            put(116, "0000000")
            put(124, content.size.toString(8).padStart(11, '0'))
            put(136, "00000000000")
            header[156] = type.code.toByte()
            put(257, "ustar")
            put(263, "00")
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            put(148, header.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0'))
            header[154] = 0
            return header + content.copyOf((content.size + 511) / 512 * 512)
        }
        return entry("$stem.mlpackage", ByteArray(0), '5') +
            entry("$stem.mlpackage/Manifest.json", "{}".encodeToByteArray(), '0') +
            entry("$stem.mlpackage/Data/weights/weight.bin", stem.encodeToByteArray(), '0') +
            ByteArray(1024)
    }

    /**
     * ⚠⚠ The archive is read by the **model store's** key, which already begins with
     * `inference/models/`, through the database the cache was bound with. A cache bound over a
     * re-rooted view of that database looks for it at `inference/inference/models/…`, finds
     * nothing, and never writes a marker once a download exists.
     */
    @Test
    fun aPrepareThroughPhotosOwnBindingFindsTheStoresArchiveAndKeepsItsOutputUnderInference() = runBlocking {
        val root = temp("photos-ai")
        val photos = photos(root, overrides = module { single<CoreMlCompiler> { CompilesNothing() } })
        val koin: Koin = photos.koin
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        val vocabulary: ByteArray = "the vocabulary".encodeToByteArray()
        photos.installModel(
            mapOf(
                ModelArtifactRole.VISION to towerArchive("vision_fp16"),
                ModelArtifactRole.TEXT to towerArchive("text_fp16"),
                ModelArtifactRole.VOCABULARY to vocabulary,
            ),
        )
        val record = koin.get<ModelStore>().installed().single()
        val cache: CoreMlModelCache = koin.get()
        val readiness: AiReadiness = koin.get()
        assertEquals(AiReadinessState.Preparing, readiness.fingerprintChecked())

        val compiled: Map<String, String> = listOf(
            CoreMlTowerSpec.vision(VisionModel.PinnedSigLip),
            CoreMlTowerSpec.text(VisionModel.PinnedSigLip),
        ).associate { spec ->
            val artifact = record.artifacts.single { it.role == spec.role }
            // The store's own key: this is what a real install hands the prepare step.
            assertTrue(artifact.localPath.trimStart('/').startsWith("inference/models/"), artifact.localPath)
            spec.role to cache.prepare(spec, artifact.localPath, artifact.sha256)
        }

        record.artifacts.filter { it.role in compiled }.forEach { artifact ->
            val directory = "$root/inference/coreml/${artifact.sha256.take(16)}"
            assertEquals("$directory/${artifact.role}.mlmodelc", compiled[artifact.role])
            assertTrue(files.fileExistsAtPath("$directory/${artifact.role}.mlmodelc/model.mil"), "no compiled ${artifact.role}")
            assertTrue(files.fileExistsAtPath("$directory/ready"), "no ready marker for ${artifact.role}")
        }
        assertFalse(files.fileExistsAtPath("$root/inference/coreml/tmp/vision"), "the unpacked package was left")
        // Nothing beside the tree a purge removes, and nothing under a doubled prefix.
        assertFalse(files.fileExistsAtPath("$root/coreml"), "compiled models outside inference/")
        assertFalse(files.fileExistsAtPath("$root/inference/inference"), "a doubled prefix")
        assertEquals(listOf("inference"), files.contentsOfDirectoryAtPath(root, error = null))

        // Every reader of the prepare step takes its paths from the same two places: the markers
        // through the cache, the vocabulary by the store's key through the same database.
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.markerWritten())
        val prepared: PreparedModel = assertNotNull(coreMlModelFacts(koin.get(), cache, koin.get(named(INFERENCE_FILE_DATABASE))).prepared)
        assertEquals(compiled[ModelArtifactRole.VISION], prepared.visionPath)
        assertEquals(compiled[ModelArtifactRole.TEXT], prepared.textPath)
        assertTrue(
            vocabulary.contentEquals(koin.get<FileDatabase>(named(INFERENCE_FILE_DATABASE)).readBytes(prepared.vocabularyPath)?.get()),
            "the vocabulary is not where its path says",
        )
        assertTrue(cache.sizeOnDisk() > 0)

        assertEquals(emptyList<String>(), OwnedInferenceStores.purge(root))
        assertFalse(files.fileExistsAtPath("$root/inference"), "the purge left the compiled models")
    }

    /**
     * ⚠⚠ A catalog update that replaces the vocabulary and keeps both towers. The vectors were
     * made by towers that did not change, so they stay; the pass was earned
     * with another vocabulary, so it does not.
     */
    @Test
    fun aVocabularyOnlyReplacementIsNotReadyOnTheOldPassAndTheVectorsStay() = runBlocking {
        val root = temp("photos-ai")
        val photos = photos(root)
        val koin: Koin = photos.koin
        val readiness: AiReadiness = koin.get()
        val towers: Map<String, ByteArray> = mapOf(
            ModelArtifactRole.VISION to "the vision archive".encodeToByteArray(),
            ModelArtifactRole.TEXT to "the text archive".encodeToByteArray(),
        )
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.installModel(towers + (ModelArtifactRole.VOCABULARY to "vocabulary one".encodeToByteArray()))
        photos.markPrepared(root)
        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(passed = true, probed = photos.key()))
        assertTrue(koin.get<EmbeddingStore>().put("/a.jpg", floatArrayOf(1f, 0f), ModelProvenance("siglip", 1)))
        val vectorsFingerprint: RuntimeFingerprint = photos.fingerprint()
        val passedKey: PreflightKey = photos.key()

        readiness.storeChanging { photos.installModel(towers + (ModelArtifactRole.VOCABULARY to "vocabulary two".encodeToByteArray())) }

        // Both towers' markers still stand, so it is prepared; what is missing is its own verdict.
        assertEquals(AiReadinessState.AwaitingVerdict, readiness.current(), "the new vocabulary is served on the old one's pass")
        assertFalse(koin.get<InferenceBackendRegistry>().isAvailable(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        assertTrue(passedKey != photos.key())
        assertEquals(vectorsFingerprint, photos.fingerprint(), "the custody's fingerprint moved with the vocabulary")
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        assertEquals(1, koin.get<EmbeddingStore>().size(), "a vocabulary change cleared the index")

        // The next process, over the same container: the pass on disk is not this install's.
        val relaunched = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(relaunched))
        PhotosOnDeviceAi.recheck(relaunched.koin)
        assertEquals(AiReadinessState.AwaitingVerdict, relaunched.koin.get<AiReadiness>().current())
        assertEquals(1, relaunched.koin.get<EmbeddingStore>().size())
    }

    /** Readiness's fourth writer, where the runtime calls it: at install, and on every foreground. */
    @Test
    fun installAndEveryReturnToTheForegroundRecheckReadiness() = runBlocking {
        val root = temp("photos-ai")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            installModel("a")
            markPrepared(root)
            assertEquals(AiReadinessState.Ready, koin.get<AiReadiness>().recordVerdict(passed = true, probed = key()))
        }

        // The next process, over the same container.
        val relaunched = photos(root)
        val readiness: AiReadiness = relaunched.koin.get()
        assertEquals(AiReadinessState.NoModel, readiness.current(), "a verdict was honoured before anything checked it")

        PhotosLocalRuntime.install(relaunched)
        eventually("install rechecked readiness") { readiness.current() == AiReadinessState.Ready }
        assertTrue(relaunched.koin.get<InferenceBackendRegistry>().isAvailable(InferenceCapability.SEMANTIC_IMAGE_SEARCH))

        // Suspended, and a compiled model's marker goes (a purge, a sweep, an interrupted prepare).
        val vision: String = relaunched.fingerprint().visionArchiveSha256!!
        assertTrue(files.removeItemAtPath("$root/inference/coreml/${vision.take(16)}/ready", error = null))
        PhotosLocalRuntime.checkAtForeground()
        eventually("the foreground hook rechecked readiness") { readiness.current() == AiReadinessState.Preparing }
        assertFalse(relaunched.koin.get<InferenceBackendRegistry>().isAvailable(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
    }

    /**
     * Readiness and the store custody (§13), in the assembled graph: a `Ready` model is not offered over
     * stores no check has opened. ⚠ No writer runs between the two halves of this test.
     */
    @Test
    fun aReadyModelIsNotOfferedUntilTheStoresAreUsable() = runBlocking {
        val root = temp("photos-ai")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            installModel("a")
            markPrepared(root)
            assertEquals(AiReadinessState.Ready, koin.get<AiReadiness>().recordVerdict(passed = true, probed = key()))
        }
        val relaunched = photos(root)
        val registry: InferenceBackendRegistry = relaunched.koin.get()
        val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH

        // Readiness first, before any store check has run in this process.
        PhotosOnDeviceAi.recheck(relaunched.koin)
        assertEquals(AiReadinessState.Ready, relaunched.koin.get<AiReadiness>().current())
        assertFalse(registry.isAvailable(capability), "a model was offered over stores nothing had checked")

        assertTrue(PhotosLocalRuntime.checkInferenceStores(relaunched))
        assertTrue(registry.isAvailable(capability), "the stores opening needed a readiness writer to be noticed")
    }

    // ─── teardown ───────────────────────────────────────────────────────────────────────────────

    private class RecordedSession(override val compiledPath: String, private val duringPredict: () -> Unit) : CoreMlSessionPort {
        var closed = false
        override fun predict(floats: FloatArray): FloatArray {
            duringPredict()
            return FloatArray(VisionModel.PinnedSigLip.dimensions) { 1f }
        }

        override fun predict(ids: IntArray): FloatArray = FloatArray(VisionModel.PinnedSigLip.dimensions) { 1f }
        override fun close() {
            closed = true
        }
    }

    /** Every tower any graph of this test opened. ⚠ Shared across assemblies: a process has one set of models. */
    private val opened = ArrayList<RecordedSession>()

    private fun recordingSessions(): Module =
        module {
            single<CoreMlSessionOpener> {
                CoreMlSessionOpener { path, _ ->
                    RecordedSession(path) { duringPredict() }.also {
                        opened += it
                        mostLoadedAtOnce = maxOf(mostLoadedAtOnce, opened.count { session -> !session.closed })
                    }
                }
            }
        }

    /** The most towers that were loaded at the same moment, read each time one is opened. */
    private var mostLoadedAtOnce = 0

    /** Runs inside every image prediction, on the thread that predicts. A test makes one block. */
    private var duringPredict: () -> Unit = {}

    /** A 2 × 2 photograph ImageIO reads: an uncompressed 24-bit BMP. */
    private fun aPhotograph(): ByteArray {
        fun le(value: Int, bytes: Int): ByteArray = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
        val pixels = ByteArray(16) { 0x7F } // two rows of two pixels, each row padded to four bytes
        return "BM".encodeToByteArray() + le(54 + pixels.size, 4) + le(0, 4) + le(54, 4) +
            le(40, 4) + le(2, 4) + le(2, 4) + le(1, 2) + le(24, 2) + le(0, 4) + le(pixels.size, 4) +
            le(2835, 4) + le(2835, 4) + le(0, 4) + le(0, 4) + pixels
    }

    /** Photos with a prepared, passed model, installed and with its vision tower loaded. */
    private suspend fun installedWithALoadedTower(root: String, firstRun: Boolean, extra: Module? = null): PhotosLocalRuntime.Assembly {
        val photos = photos(root, overrides = if (extra == null) recordingSessions() else module { includes(recordingSessions(), extra) })
        if (firstRun) {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
            photos.installModel("a")
            photos.markPrepared(root)
            assertEquals(AiReadinessState.Ready, photos.koin.get<AiReadiness>().recordVerdict(passed = true, probed = photos.key()))
        }
        PhotosLocalRuntime.install(photos)
        val backend: CoreMlInferenceBackend = photos.koin.get()
        eventually("the installed runtime is ready") { backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val before: Int = opened.size
        // ⚠ Not a photograph: the tower is opened before the decoder is asked, and stays open.
        assertEquals(ImageEmbeddingOutcome.Unreadable, backend.embedImageOutcome("not an image".encodeToByteArray()))
        assertEquals(before + 1, opened.size)
        assertFalse(opened.last().closed)
        return photos
    }

    /**
     * ⚠⚠ The graph outlives the runtime: a view model keeps it reachable after a sign-out. The
     * model it loaded must go with the runtime, and its backend must never load one again.
     */
    @Test
    fun deprovisionUnloadsTheModelAndTheOldBackendNeverReopens() = runBlocking {
        val root = temp("photos-ai")
        val photos = installedWithALoadedTower(root, firstRun = true)
        val backend: CoreMlInferenceBackend = photos.koin.get()
        val readiness: AiReadiness = photos.koin.get()
        val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH

        PhotosLocalRuntime.deprovision()
        // The unload runs behind the teardown, on a scope of its own.
        assertNotNull(photos.unloading, "the deprovision did not retire readiness").join()

        assertTrue(opened.single().closed, "a signed-out Photos kept its model loaded")
        assertFalse(backend.supports(capability))
        assertEquals(AiReadinessState.NoModel, readiness.current())

        // Everything a retained reference to the old graph can still do. ⚠ Its stores stay closed
        // too (a deprovision locks its `SyncManager`, so the custody reads a stale runtime); the
        // backend-level case proves the retirement holds without that second lock.
        PhotosLocalRuntime.checkInferenceStores(photos)
        PhotosOnDeviceAi.recheck(photos.koin)
        readiness.recordVerdict(passed = true, probed = photos.key())
        assertFalse(backend.supports(capability), "the old backend is offered again")
        assertNull(photos.koin.get<InferenceBackendRegistry>().backendFor(capability))
        assertEquals(ImageEmbeddingOutcome.Failed, backend.embedImageOutcome("not an image".encodeToByteArray()))
        assertEquals(1, opened.size, "the old backend opened a model after its runtime was torn down")
    }

    /**
     * ⚠⚠ `deprovision` is called from Photos' `adopt()`, on the **main thread**, and a prediction
     * is a blocking call into Core ML that nothing interrupts. The teardown must not wait for it,
     * so it never closes the towers inside a `runBlocking`: a close waits for the tower.
     */
    @Test
    fun aDeprovisionDoesNotWaitForAPredictionAndItsVectorIsThrownAway() = runBlocking {
        val root = temp("photos-ai")
        val photos = installedWithALoadedTower(root, firstRun = true)
        val backend: CoreMlInferenceBackend = photos.koin.get()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        duringPredict = {
            entered.complete(Unit)
            runBlocking { release.await() }
        }
        val inFlight = async(Dispatchers.Default) { backend.embedImageOutcome(aPhotograph()) }
        // ⚠ Whatever fails below, the prediction is let go: this scope waits for it, and a red
        // run that left it blocked would hang the host and never report.
        val unloading = try {
            withTimeout(30_000) { entered.await() }

            val teardown = async(Dispatchers.Default) { PhotosLocalRuntime.deprovision() }
            val returned: Boolean = withTimeoutOrNull(5_000) { teardown.await() } != null
            assertTrue(returned, "the teardown waited for a prediction: on a phone this is the main thread")

            // Returned, with the prediction still running: nothing is offered, the tower is not yet closed.
            assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
            assertFalse(PhotosLocalRuntime.isInstalled())
            assertNotNull(photos.unloading, "the deprovision did not retire readiness").also {
                assertFalse(it.isCompleted, "the tower was unloaded under its own prediction")
                assertFalse(opened.single().closed)
            }
        } finally {
            release.complete(Unit)
        }

        // ⚠ The stores were given up while it ran. Its vector must reach nobody who could store it.
        assertEquals(ImageEmbeddingOutcome.Failed, inFlight.await(), "a vector left a backend whose stores are relinquished")
        unloading.join()
        assertTrue(opened.single().closed, "the tower stayed loaded after its prediction returned")
        assertEquals(0, photos.koin.get<EmbeddingStore>().size())
    }

    /**
     * ⚠⚠ Exactly what the product does: deprovision, then install, with nothing waited for in
     * between. The teardown does not wait for the unload and the next
     * install does not either, so the wait is where a tower is opened.
     */
    @Test
    fun provisionDeprovisionProvisionNeverHasTwoModelsLoaded() = runBlocking {
        val root = temp("photos-ai")
        installedWithALoadedTower(root, firstRun = true)

        PhotosLocalRuntime.deprovision()
        // No join, no delay: the next runtime is installed and asked for a vector at once.
        installedWithALoadedTower(root, firstRun = false)

        assertEquals(1, mostLoadedAtOnce, "two runtimes' models were loaded side by side")
        assertEquals(listOf(true, false), opened.map { it.closed })
        // ⚠ And nothing is left pending: the next open in this process must not wait on a job
        // that finished long ago, or on one that was never cleared.
        eventually("the finished unload is no longer pending") { !PhotosOnDeviceAi.unloadPending() }
    }

    /**
     * The same, with the old runtime's tower held by a prediction that has not returned. The new
     * graph assembles, installs and is ready; what it may not do is load its own model yet.
     */
    @Test
    fun aNewRuntimeLoadsNoModelWhileTheOldOneIsStillHeldByAPrediction() = runBlocking {
        val root = temp("photos-ai")
        val first = installedWithALoadedTower(root, firstRun = true)
        val oldBackend: CoreMlInferenceBackend = first.koin.get()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        duringPredict = {
            if (entered.complete(Unit)) runBlocking { release.await() }
        }
        val held = async(Dispatchers.Default) { oldBackend.embedImageOutcome(aPhotograph()) }
        val waiting = try {
            withTimeout(30_000) { entered.await() }

            PhotosLocalRuntime.deprovision()
            val second = photos(root, overrides = recordingSessions())
            PhotosLocalRuntime.install(second)
            val newBackend: CoreMlInferenceBackend = second.koin.get()
            eventually("the new runtime is ready") { newBackend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }

            // Asked for a vector: it waits. It is not refused, and it opens nothing.
            async(Dispatchers.Default) { newBackend.embedImageOutcome("not an image".encodeToByteArray()) }.also {
                delay(500)
                assertFalse(it.isCompleted, "the new runtime answered while the old model was still loaded: it should wait")
                assertEquals(1, opened.size, "a second model was loaded beside one a prediction still holds")
                assertTrue(PhotosOnDeviceAi.unloadPending())
            }
        } finally {
            release.complete(Unit)
        }

        assertEquals(ImageEmbeddingOutcome.Failed, held.await())
        // ⚠ `Unreadable`: the tower was opened, then the decoder refused the bytes.
        assertEquals(ImageEmbeddingOutcome.Unreadable, withTimeout(30_000) { waiting.await() })
        assertEquals(listOf(true, false), opened.map { it.closed }, "the old model outlived its prediction, or the new one never loaded")
        assertEquals(1, mostLoadedAtOnce)
    }

    /**
     * Teardowns stack: a sign-out, a sign-in and a sign-out again while the first runtime's tower
     * is still held. The second unload has nothing to close and would finish at once; the handle
     * must still stand for the first.
     */
    @Test
    fun aSecondTeardownDoesNotHideAnUnloadThatIsStillWaiting() = runBlocking {
        val root = temp("photos-ai")
        val first = installedWithALoadedTower(root, firstRun = true)
        val oldBackend: CoreMlInferenceBackend = first.koin.get()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        duringPredict = {
            if (entered.complete(Unit)) runBlocking { release.await() }
        }
        val held = async(Dispatchers.Default) { oldBackend.embedImageOutcome(aPhotograph()) }
        val waiting = try {
            withTimeout(30_000) { entered.await() }
            PhotosLocalRuntime.deprovision()

            // A second runtime that never loads anything, torn down in its turn.
            PhotosLocalRuntime.install(photos(root, overrides = recordingSessions()))
            PhotosLocalRuntime.deprovision()

            val third = photos(root, overrides = recordingSessions())
            PhotosLocalRuntime.install(third)
            val newBackend: CoreMlInferenceBackend = third.koin.get()
            eventually("the third runtime is ready") { newBackend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
            async(Dispatchers.Default) { newBackend.embedImageOutcome("not an image".encodeToByteArray()) }.also {
                delay(500)
                assertEquals(1, opened.size, "a model was loaded while the first runtime's is still held")
                assertFalse(it.isCompleted)
            }
        } finally {
            release.complete(Unit)
        }

        assertEquals(ImageEmbeddingOutcome.Failed, held.await())
        assertEquals(ImageEmbeddingOutcome.Unreadable, withTimeout(30_000) { waiting.await() })
        assertEquals(1, mostLoadedAtOnce)
    }

    @Test
    fun aDeprovisionedPhotosIsBackToNotBuilt() = vmBlocking { owned ->
        val photos = photos()
        PhotosLocalRuntime.install(photos)
        assertEquals(SemanticSearchStatus.Available, owned.own(PhotosSurface.search("iPhone")).state.value.semanticStatus)

        PhotosLocalRuntime.deprovision()

        assertEquals(SemanticSearchStatus.NotBuiltHere("iPhone"), owned.own(PhotosSurface.search("iPhone")).state.value.semanticStatus)
    }

    // ─── the preflight (§14.6) ──────────────────────────────────────────────────────────────────

    private val width: Int = VisionModel.PinnedSigLip.dimensions

    // ─── the foreground pass (§14.19) ──────────────────────────────────────────────────────────

    /** The phone a case says, its heat and power moved by the case. */
    private class FakeSignals : PhotosIndexingSignals {
        var phone = DeviceIndexingState(foreground = true, thermal = ThermalState.NOMINAL, lowPowerMode = false, batteryLevel = null, charging = null)
        override fun read(foreground: Boolean): DeviceIndexingState = phone.copy(foreground = foreground)
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val memoryWarnings = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override fun observe() = Unit
    }

    private val windows = ArrayList<String>()

    private fun indexing(signals: FakeSignals): Module = module {
        single<PhotosIndexingSignals> { signals }
        single<BackgroundWindow> {
            object : BackgroundWindow {
                override fun begin(name: String): Any = "w".also { windows += "begin" }
                override fun end(handle: Any?) {
                    windows += "end"
                }
            }
        }
    }

    /** ⚠ The three ways *Index now* answers, through Photos' own graph and the operation, on the simulator. */
    @Test
    fun indexNowThroughPhotosGraphRefusesWaitsForTheHeatAndStarts() = runBlocking {
        val signals = FakeSignals()
        val bare = photos(overrides = indexing(signals))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(bare))
        assertEquals(IndexNowAnswer.Refused(org.tenshu.inference.IndexPassRefused.Reasons.NO_BACKEND), bare.koin.get<ForegroundIndexing>().indexNow(), "no model, said by the op")

        val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = indexing(signals))
        photos.consentToSearch(realm = true, device = true)
        val driver: ForegroundIndexing = photos.koin.get()
        signals.phone = signals.phone.copy(thermal = ThermalState.SERIOUS)
        assertEquals(IndexNowAnswer.Waiting(IndexingBlock.TOO_WARM), driver.indexNow())
        assertIs<ForegroundIndexingState.Waiting>(driver.standing.value.state)

        signals.phone = signals.phone.copy(thermal = ThermalState.NOMINAL)
        assertEquals(IndexNowAnswer.Started, driver.indexNow())
        // An empty library: the pass ends at once, and the ask with it.
        eventually("the pass over an empty library ended") { driver.standing.value.state == ForegroundIndexingState.NotAsked }
        assertIs<PassStanding.Idle>(photos.koin.get<MediaIndexRunner>().standing.value)
        Unit
    }

    /**
     * §14.19.6: the Models screen's indexing section over Photos' **own** graph — the real
     * driver, the real runner, the real store's count — through the shared view model.
     */
    @Test
    fun theModelsScreensIndexingSectionReadsPhotosOwnPassAndStore() = runBlocking {
        val signals = FakeSignals()
        val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = indexing(signals))
        photos.consentToSearch(realm = true, device = true)
        val repository: ModelsRepository = photos.koin.get()
        assertTrue(repository.capabilities.indexing, "the graph's repository has no indexing section")
        val owned = OwnedViewModels()
        val vm = owned.own(ModelsViewModel(repository))
        try {
            eventually("the store was not counted: ${vm.uiState.value.storedIndex}") { vm.uiState.value.storedIndex?.searchable == 0 }
            eventually("the driver was not read: ${vm.uiState.value.foregroundIndex}") { vm.uiState.value.foregroundIndex == ForegroundIndexStanding.NotAsked }

            signals.phone = signals.phone.copy(thermal = ThermalState.SERIOUS)
            vm.indexNow()
            eventually("a warm phone's press did not wait: ${vm.uiState.value.foregroundIndex}") {
                vm.uiState.value.foregroundIndex == ForegroundIndexStanding.Waiting(ForegroundIndexStanding.Waiting.Why.WARM) && !vm.uiState.value.startingIndex
            }
            assertNull(vm.uiState.value.message, "a press the phone holds back is the section's to say")

            signals.phone = signals.phone.copy(thermal = ThermalState.NOMINAL)
            vm.indexNow()
            eventually("the press was not answered: ${vm.uiState.value.message}") { vm.uiState.value.message == ModelsMessage.IndexingStarted }
            // An empty library: the pass ends at once, and the ask with it.
            eventually("the ask did not end: ${vm.uiState.value.foregroundIndex}") { vm.uiState.value.foregroundIndex == ForegroundIndexStanding.NotAsked }
        } finally {
            owned.closeAll()
        }
        owned.assertNothingActive()
    }

    /** ⚠⚠ §3.3: leaving the foreground lets go of the towers, inside a background window that ends. */
    @Test
    fun leavingTheForegroundClosesTheTowersInsideABackgroundWindow() = runBlocking {
        val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = indexing(FakeSignals()))
        // The driver follows the runner from the launch check on.
        eventually("the launch check resolved the driver") { runCatching { photos.koin.get<ForegroundIndexing>() }.isSuccess }
        val tower: RecordedSession = opened.last()

        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)

        eventually("the vision tower was closed when Photos left the foreground") { tower.closed }
        eventually("the window ended") { windows == listOf("begin", "end") }
        assertFalse(photos.koin.get<IndexingConditions>(named("indexingConditions")).allowManualIndexing(), "a pass may run in the background")
    }

    // ─── New arrivals while Photos is in front (PR-6b, §17) ────────────────────────────────────

    /** A test's debounce for the arrivals batch: short, so a case does not wait three seconds per batch. */
    private val quickArrivals: Module = module { single<Long>(named(PhotosOnDeviceAi.ARRIVALS_DEBOUNCE)) { 50L } }

    /**
     * ⚠⚠ Through Photos' **own** graph, its **production** media source and the shared SQLite store:
     * a photograph uploaded through the file service is found in the modification log and indexed as
     * a batch (the runner's automatic pass, never a press) once Photos is in front with the battery
     * known — and nothing starts behind Photos. The test graph's library watch does not vouch (no feed
     * observes it), so every foreground looks.
     */
    @Test
    fun anUploadIsIndexedInPhotosOwnGraphInFrontAndNeverBehind() = runBlocking {
        val signals = FakeSignals().apply { phone = phone.copy(batteryLevel = 0.8f, charging = false) }
        val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = module { includes(indexing(signals), quickArrivals) })
        val runner: MediaIndexRunner = photos.koin.get()
        val vectors: EmbeddingStore = photos.koin.get()
        val trigger: ForegroundArrivals = photos.koin.get()
        // ⚠ The launch's batch: no consent yet, so no look — the log's end is marked, no pass ran.
        eventually("the launch asked no batch") { trigger.batchesStarted >= 1 }
        delay(500)
        assertEquals(PassStanding.Idle(null), runner.standing.value, "a batch ran with nothing arrived")
        photos.consentToSearch(realm = true, device = true)

        // Behind Photos: an upload starts nothing. ⚠ Counted on the trigger: a refused batch never moves the standing.
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        delay(500)
        val behind: Int = trigger.batchesStarted
        val files = photos.tenshuClient.fileService.defaultFileSystem
        assertIs<TResult.Success<*>>(files.createFolder("/arrivals"))
        assertIs<TResult.Success<*>>(files.uploadFile("/arrivals", "a.bmp", aPhotograph()))
        delay(500)
        assertEquals(behind, trigger.batchesStarted, "a batch started behind Photos")

        // In front: one batch over the upload alone.
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)
        eventually("no batch indexed the upload: ${runner.standing.value}") { vectors.visited("/arrivals/a.bmp") }
        eventually("the batch did not end: ${runner.standing.value}") { runner.standing.value is PassStanding.Idle }
        val ended = assertIs<PassEnding.Finished>(assertIs<PassStanding.Idle>(runner.standing.value).last)
        assertEquals(1, ended.pass, "the batch was not this graph's only pass")
        assertEquals(1, ended.result.imagesVisited, "the batch looked at more than the upload")
        assertEquals(emptyList(), photos.koin.get<ArrivalQueue>().paths(), "the batch left the upload queued")
    }

    /** ⚠⚠ A batch stopped at its cap leaves photos for the wake: the next leave still arms one (PR-6 §16.3 decision 4). */
    @Test
    fun aCappedBatchLeavesPhotosForTheWake() {
        val capped = PassStanding.Idle(PassEnding.Finished(1, IndexPassResult(batchCapReached = true)))
        assertEquals(WakeWork.LEFT, PhotosOnDeviceAi.workLeft(capped), "a capped batch read as a current library")
        assertEquals(WakeWork.LEFT, PhotosOnDeviceAi.workLeft(PassStanding.Idle(PassEnding.Finished(1, IndexPassResult()))), "a batch that was no library walk read as a current library")
        assertEquals(WakeWork.NONE_LEFT, PhotosOnDeviceAi.workLeft(PassStanding.Idle(PassEnding.Finished(1, IndexPassResult(libraryWalk = true)))))
        assertEquals(WakeWork.LEFT, PhotosOnDeviceAi.workLeft(PassStanding.Idle(PassEnding.Finished(1, IndexPassResult(deferred = true)))))
        // ⚠⚠ A look that may have missed arrivals: only a walk that started after it answers it.
        val walked = PassStanding.Idle(PassEnding.Finished(3, IndexPassResult(libraryWalk = true)))
        assertEquals(WakeWork.LEFT, PhotosOnDeviceAi.workLeft(walked, missedSince = 3), "a walk before the miss answered it")
        assertEquals(WakeWork.NONE_LEFT, PhotosOnDeviceAi.workLeft(walked, missedSince = 2))
    }

    /**
     * ⚠⚠ The arrived paths are the realm's (IN-D6a): Photos' graph binds ONE queue, and the custody
     * that clears the realm's derived stores clears it too — an unbound queue made that a no-op.
     */
    @Test
    fun theArrivalsQueueIsTheOneTheCustodyClears() = runBlocking {
        val signals = FakeSignals()
        val photos = installedWithALoadedTower(temp("photos-ai-queue"), firstRun = true, extra = module { includes(indexing(signals), quickArrivals) })
        val queue: ArrivalQueue = assertNotNull(photos.koin.getOrNull<ArrivalQueue>(), "Photos' graph binds no arrivals queue")
        assertSame(queue, photos.koin.get<ArrivalQueue>(), "two arrivals queues: the look fills one and the batch drains another")
        queue.add("/arrivals/a.jpg")
        queue.flush()
        photos.koin.get<InferenceStoreCustody>().relinquish()
        assertEquals(emptyList(), queue.paths(), "relinquishing the realm's stores kept its arrived paths")
    }

    // ─── *Index now* continued (§15) ───────────────────────────────────────────────────────────

    private class RecordedTask : IndexNowContinuedTask {
        val completions = ArrayList<Boolean>()
        val progress = ArrayList<Pair<Long, Long>>()
        override fun progress(done: Long, total: Long) {
            progress += done to total
        }

        override fun complete(success: Boolean) {
            completions += success
        }
    }

    /**
     * ⚠⚠ Through Photos' **own** graph and the Swift-facing runtime calls: a press's pass is
     * offered to the installed scheduler once it has started; with its task launched, leaving
     * Photos neither cancels the pass nor closes the tower, and the pass's bar still lets it run;
     * the pass ends, the task completes with success, and only then is the leave's stop done.
     */
    @Test
    fun aContinuedPassGoesOnThroughALeaveInPhotosOwnGraph() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val submitted = ArrayList<String>()
        val signals = FakeSignals()
        val library = module {
            // One photograph whose bytes wait for the case: the pass is in flight until then.
            single<MediaSource> {
                MediaSource {
                    listOf(
                        IndexableMedia("/photos/a.bmp") {
                            gate.await()
                            aPhotograph()
                        },
                    )
                }
            }
        }
        PhotosLocalRuntime.installIndexNowContinuation(
            object : IndexNowContinuationBridge {
                override fun submit(identifier: String, title: String, subtitle: String) {
                    submitted += identifier
                }
            },
        )
        try {
            val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = module { includes(indexing(signals), library) })
            photos.consentToSearch(realm = true, device = true)
            val tower: RecordedSession = opened.last()
            val driver: ForegroundIndexing = photos.koin.get()

            assertEquals(IndexNowAnswer.Started, driver.indexNow())
            assertEquals(1, submitted.size, "the press's pass was not offered: $submitted")
            assertTrue(submitted.single().startsWith("app.tenshu.photos.ios.indexnow."), submitted.single())
            val task = RecordedTask()
            PhotosLocalRuntime.onIndexNowTaskLaunched(submitted.single(), task)

            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            delay(200)
            assertFalse(tower.closed, "leaving closed the tower of a pass its task owns")
            assertTrue(windows.isEmpty(), "leaving began the stop of a pass its task owns: $windows")
            assertIs<PassStanding.Running>(photos.koin.get<MediaIndexRunner>().standing.value, "leaving stopped a pass its task owns")
            assertTrue(photos.koin.get<IndexingConditions>(named("indexingConditions")).allowManualIndexing(), "the pass's own bar stops a pass its task owns")

            gate.complete(Unit)
            eventually("the task was not completed: ${task.completions}") { task.completions.isNotEmpty() }
            assertEquals(listOf(true), task.completions)
            // Photos is still not in front: once the task let go, the leave's stop.
            eventually("the stop was not done once the task let go: $windows") { windows == listOf("begin", "end") }
            eventually("the tower was not closed once the task let go") { tower.closed }
            assertFalse(photos.koin.get<IndexingConditions>(named("indexingConditions")).allowManualIndexing(), "a pass may still run in the background")
        } finally {
            app.tenshu.control.di.PhotosIndexNow.uninstall()
        }
    }

    /** One timeline for the store's task token and the task it protects (B.23). */
    private class TokenTimeline(
        /** The token's release waits (at most 5 s) for an expiry to arrive while it runs. */
        private val releaseWaitsForAnExpiry: Boolean = false,
    ) {
        private val expiry = platform.Foundation.NSCondition()
        private var expired = false
        private val lock = platform.Foundation.NSRecursiveLock()
        private val events = ArrayList<String>()
        fun add(event: String) {
            lock.lock()
            events += event
            lock.unlock()
        }

        fun all(): List<String> {
            lock.lock()
            try {
                return events.toList()
            } finally {
                lock.unlock()
            }
        }

        /** What `SharedReplicaEntries.protectTask` makes with a binding installed: a token on this timeline. */
        fun protection(reason: String) = org.tenshu.core.common.proxy.db.sqlite.SharedReplicaTaskProtection(
            reason,
            acquire = {
                add("token")
                object : org.tenshu.core.common.proxy.db.sqlite.SharedReplicaTaskToken {
                    override fun release() {
                        if (!releaseWaitsForAnExpiry) return add("release")
                        add("releasing")
                        expiry.lock()
                        val deadline = platform.Foundation.NSDate.dateWithTimeIntervalSinceNow(5.0)
                        while (!expired) if (!expiry.waitUntilDate(deadline)) break
                        expiry.unlock()
                        add("released")
                    }

                    override fun expire() {
                        add("expire")
                        expiry.lock()
                        expired = true
                        expiry.broadcast()
                        expiry.unlock()
                    }
                }
            },
        )

        fun task() = object : IndexNowContinuedTask {
            override fun progress(done: Long, total: Long) = Unit
            override fun complete(success: Boolean) = add("complete($success)")
        }
    }

    /**
     * ⚠⚠ B.23 (`ios-on-device-inference.md` §15.4, Q1): with the shared store's protection installed,
     * *Index now* is **not declined**: the press is offered, the launched task takes the
     * store's **task token** before the claim goes live, and the token is released before the task
     * is completed. Through Photos' own graph and the Swift-facing calls.
     */
    @Test
    fun withTheStoresProtectionInstalledAPressIsContinuedUnderTheTasksToken() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val submitted = ArrayList<String>()
        val timeline = TokenTimeline()
        val library = module {
            single<MediaSource> { MediaSource { listOf(IndexableMedia("/photos/a.bmp") { gate.await(); aPhotograph() }) } }
        }
        val saved = PhotosLocalRuntime.taskProtection
        PhotosLocalRuntime.taskProtection = timeline::protection
        PhotosLocalRuntime.installIndexNowContinuation(
            object : IndexNowContinuationBridge {
                override fun submit(identifier: String, title: String, subtitle: String) {
                    submitted += identifier
                }
            },
        )
        try {
            val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = module { includes(indexing(FakeSignals()), library) })
            photos.consentToSearch(realm = true, device = true)
            val driver: ForegroundIndexing = photos.koin.get()

            assertEquals(IndexNowAnswer.Started, driver.indexNow())
            assertEquals(1, submitted.size, "the press was declined: $submitted")
            PhotosLocalRuntime.onIndexNowTaskLaunched(submitted.single(), timeline.task())
            assertEquals(listOf("token"), timeline.all(), "the launch takes the store's task token")
            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            delay(200)
            assertIs<PassStanding.Running>(photos.koin.get<MediaIndexRunner>().standing.value, "leaving stopped a pass its task owns")

            gate.complete(Unit)
            eventually("the task was not completed: ${timeline.all()}") { timeline.all().any { it.startsWith("complete") } }
            assertEquals(listOf("token", "release", "complete(true)"), timeline.all(), "the token ends before the task is completed")
        } finally {
            PhotosLocalRuntime.taskProtection = saved
            app.tenshu.control.di.PhotosIndexNow.uninstall()
        }
    }

    /**
     * ⚠⚠ B.23: the task's `expirationHandler` makes the token's end an **expiry** (B.15.3's
     * interrupt and drain), and the task is completed only after it, with `false`.
     */
    @Test
    fun anExpiredContinuedTaskExpiresTheStoresTokenBeforeItIsCompleted() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val submitted = ArrayList<String>()
        val timeline = TokenTimeline()
        val library = module {
            single<MediaSource> { MediaSource { listOf(IndexableMedia("/photos/a.bmp") { gate.await(); aPhotograph() }) } }
        }
        val saved = PhotosLocalRuntime.taskProtection
        PhotosLocalRuntime.taskProtection = timeline::protection
        PhotosLocalRuntime.installIndexNowContinuation(
            object : IndexNowContinuationBridge {
                override fun submit(identifier: String, title: String, subtitle: String) {
                    submitted += identifier
                }
            },
        )
        try {
            val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = module { includes(indexing(FakeSignals()), library) })
            photos.consentToSearch(realm = true, device = true)
            val driver: ForegroundIndexing = photos.koin.get()
            assertEquals(IndexNowAnswer.Started, driver.indexNow())
            PhotosLocalRuntime.onIndexNowTaskLaunched(submitted.single(), timeline.task())
            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            delay(200)

            PhotosLocalRuntime.onIndexNowTaskExpired(submitted.single())
            assertEquals(listOf("token", "expire", "complete(false)"), timeline.all(), "expired, then completed, before the handler returned")
            gate.complete(Unit)
            delay(200)
            assertEquals(listOf("token", "expire", "complete(false)"), timeline.all(), "nothing ended or completed twice")
        } finally {
            PhotosLocalRuntime.taskProtection = saved
            app.tenshu.control.di.PhotosIndexNow.uninstall()
        }
    }

    /**
     * ⚠⚠ The task expires while the pass's release is still quiescing. The
     * hold must still be found, the release turned into an expiry, and the handler must return only
     * once the token has ended — never with the drain still running.
     */
    @Test
    fun anExpiryDuringTheReleaseFindsTheHoldAndReturnsOnlyOnceTheTokenEnded() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val submitted = ArrayList<String>()
        val timeline = TokenTimeline(releaseWaitsForAnExpiry = true)
        val library = module {
            single<MediaSource> { MediaSource { listOf(IndexableMedia("/photos/a.bmp") { gate.await(); aPhotograph() }) } }
        }
        val saved = PhotosLocalRuntime.taskProtection
        PhotosLocalRuntime.taskProtection = timeline::protection
        PhotosLocalRuntime.installIndexNowContinuation(
            object : IndexNowContinuationBridge {
                override fun submit(identifier: String, title: String, subtitle: String) {
                    submitted += identifier
                }
            },
        )
        try {
            val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = module { includes(indexing(FakeSignals()), library) })
            photos.consentToSearch(realm = true, device = true)
            val driver: ForegroundIndexing = photos.koin.get()
            assertEquals(IndexNowAnswer.Started, driver.indexNow())
            PhotosLocalRuntime.onIndexNowTaskLaunched(submitted.single(), timeline.task())
            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            delay(200)

            gate.complete(Unit)
            eventually("the release did not start: ${timeline.all()}") { "releasing" in timeline.all() }
            PhotosLocalRuntime.onIndexNowTaskExpired(submitted.single())
            timeline.add("handler returned")
            eventually("the task was not completed: ${timeline.all()}") { timeline.all().any { it.startsWith("complete") } }
            assertEquals(
                listOf("token", "releasing", "expire", "released"),
                timeline.all().take(4),
                "the expiry did not find the hold and turn the release into an expiry",
            )
            val events = timeline.all()
            assertTrue(events.indexOf("released") < events.indexOf("handler returned"), "the handler returned with the release in flight: $events")
        } finally {
            PhotosLocalRuntime.taskProtection = saved
            app.tenshu.control.di.PhotosIndexNow.uninstall()
        }
    }

    /**
     * The scene becoming active reads a waiting ask again (`enteredForeground`) —
     * with no phone change to wake it, a wait whose block has cleared is resumed in front.
     */
    @Test
    fun theSceneBecomingActiveReadsAWaitingAskAgain() = runBlocking {
        val signals = FakeSignals()
        val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = indexing(signals))
        photos.consentToSearch(realm = true, device = true)
        val driver: ForegroundIndexing = photos.koin.get()
        signals.phone = signals.phone.copy(lowPowerMode = true)
        assertEquals(IndexNowAnswer.Waiting(IndexingBlock.LOW_POWER_MODE), driver.indexNow())
        // ⚠ Past the wait's own first re-check (1 ms after it began, still in Low Power Mode):
        // otherwise that re-check, not the scene, is what resumes it, and a mutation survives.
        delay(500)
        assertIs<ForegroundIndexingState.Waiting>(driver.standing.value.state)

        // Switched off with no notification; then the scene is told it is active.
        signals.phone = signals.phone.copy(lowPowerMode = false)
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)
        eventually("the wait was not read again in front: ${driver.standing.value.state}") {
            driver.standing.value.state !is ForegroundIndexingState.Waiting
        }
        Unit
    }

    /** ⚠⚠ Every task handed to the runtime is completed: with no graph, or one that never asked. */
    @Test
    fun aContinuedTaskNoGraphAskedForIsCompletedAtOnceWithFailure() = runBlocking {
        val orphan = RecordedTask()
        PhotosLocalRuntime.onIndexNowTaskLaunched("app.tenshu.photos.ios.indexnow.nobody", orphan)
        assertEquals(listOf(false), orphan.completions, "no runtime installed")

        installedWithALoadedTower(temp("photos-ai"), firstRun = true)
        val stranger = RecordedTask()
        PhotosLocalRuntime.onIndexNowTaskLaunched("app.tenshu.photos.ios.indexnow.nobody", stranger)
        assertEquals(listOf(false), stranger.completions, "an identifier the graph never submitted")
    }

    // ─── The processing wake (PR-6, §16) ───────────────────────────────────────────────────────

    /** What Swift's driver was asked: submissions and withdrawals, with what each submission required. */
    private class WakeRequests : ProcessingWakeBridge {
        private val lock = platform.Foundation.NSRecursiveLock()
        private val log = ArrayList<String>()
        override fun submit(identifier: String, requiresExternalPower: Boolean, requiresNetworkConnectivity: Boolean) {
            lock.lock()
            log += "submit($identifier, power=$requiresExternalPower, network=$requiresNetworkConnectivity)"
            lock.unlock()
        }

        override fun withdraw(identifier: String) {
            lock.lock()
            log += "withdraw($identifier)"
            lock.unlock()
        }

        fun all(): List<String> {
            lock.lock()
            try {
                return log.toList()
            } finally {
                lock.unlock()
            }
        }

        fun clear() {
            lock.lock()
            log.clear()
            lock.unlock()
        }
    }

    private val armed = "submit(app.tenshu.photos.processing, power=true, network=false)"
    private val withdrawn = "withdraw(app.tenshu.photos.processing)"

    private fun TokenTimeline.wakeTask() = object : ProcessingWakeTask {
        override fun complete(success: Boolean) = add("complete($success)")
    }

    /**
     * ⚠⚠ Through Photos' **own** graph and the Swift-facing calls, in the background as a wake runs:
     * the wake takes the store's **task token** first, re-arms, runs the runner's **scheduled** pass
     * (the scheduled bar says yes inside the wake, and only there), a leave meanwhile stops nothing,
     * and the pass's end releases the token **before** the task is completed with success; the
     * request is then withdrawn (the library is current), the towers are let go in the background,
     * and the next leave arms nothing.
     */
    @Test
    fun aWakeIndexesInPhotosOwnGraphUnderTheTasksTokenAndLetsGoAfter() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val timeline = TokenTimeline()
        val requests = WakeRequests()
        val library = module {
            single<MediaSource> { MediaSource { listOf(IndexableMedia("/photos/a.bmp") { gate.await(); aPhotograph() }) } }
        }
        val saved = PhotosLocalRuntime.taskProtection
        PhotosLocalRuntime.taskProtection = timeline::protection
        PhotosLocalRuntime.installProcessingWake(requests)
        try {
            val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = module { includes(indexing(FakeSignals()), library) })
            photos.consentToSearch(realm = true, device = true)
            val bar: IndexingConditions = photos.koin.get(named("indexingConditions"))
            // A background launch: Photos is not in front.
            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            eventually("the leave did not arm the wake: ${requests.all()}") { armed in requests.all() }
            eventually("the leave's stop did not end: $windows") { windows.count { it == "end" } == windows.count { it == "begin" } && windows.isNotEmpty() }
            requests.clear()
            windows.clear()
            assertFalse(bar.allowIndexing(), "the scheduled bar said yes with no wake")

            PhotosLocalRuntime.runProcessingWake("wake-1", timeline.wakeTask())
            assertEquals(listOf("token"), timeline.all(), "the wake takes the store's task token first")
            eventually("the wake did not start its pass: ${photos.koin.get<MediaIndexRunner>().standing.value}") {
                photos.koin.get<MediaIndexRunner>().standing.value == PassStanding.Running(1, manual = false)
            }
            assertEquals(listOf(armed), requests.all(), "the wake did not re-arm before it worked")
            assertTrue(bar.allowIndexing(), "the scheduled bar said no inside the wake")

            // A leave while the wake carries the pass: nothing is stopped or closed, no window begun.
            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            delay(200)
            assertEquals(emptyList(), windows, "a leave began the stop of a pass the wake carries")
            assertIs<PassStanding.Running>(photos.koin.get<MediaIndexRunner>().standing.value, "a leave stopped the wake's pass")

            gate.complete(Unit)
            eventually("the wake was not completed: ${timeline.all()}") { timeline.all().any { it.startsWith("complete") } }
            assertEquals(listOf("token", "release", "complete(true)"), timeline.all(), "the token ends before the task is completed")
            assertEquals(listOf(armed, withdrawn), requests.all(), "a current library still wakes the phone")
            // The journal the runbook's three nights read: a launch, then an end with its counts.
            val journal: String = ProcessingWakeJournal.read(photos.inferenceRoot)
            val launchedAt = journal.indexOf(" launched")
            val endedAt = journal.indexOf(" ended minutes=")
            assertTrue(launchedAt >= 0 && endedAt > launchedAt, "the wake's journal: $journal")
            assertTrue(Regex(" ended minutes=\\S+ success=true expired=false visited=1 embedded=\\d+ ").containsMatchIn(journal), "the end's line: $journal")
            assertFalse(bar.allowIndexing(), "the scheduled bar still says yes after the wake")
            // The pass opened the tower for its photo; the wake's end let it go, inside a window.
            eventually("the towers were not let go once the wake ended") { opened.last().closed }
            eventually("the stop's window did not end: $windows") { windows == listOf("begin", "end") }

            requests.clear()
            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            delay(300)
            assertEquals(emptyList(), requests.all(), "a leave over a current library armed a wake")
        } finally {
            PhotosLocalRuntime.taskProtection = saved
            app.tenshu.control.di.PhotosProcessingWake.uninstall()
        }
    }

    /**
     * ⚠⚠ An expiry mid-pass: the token's end is made an expiry and ended before the handler returns,
     * the pass stops at its next photo, and the task is completed with `false`, once.
     */
    @Test
    fun anExpiredWakeExpiresTheTokenStopsThePassAndIsCompletedOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val timeline = TokenTimeline()
        val requests = WakeRequests()
        val library = module {
            single<MediaSource> { MediaSource { listOf(IndexableMedia("/photos/a.bmp") { gate.await(); aPhotograph() }) } }
        }
        val saved = PhotosLocalRuntime.taskProtection
        PhotosLocalRuntime.taskProtection = timeline::protection
        PhotosLocalRuntime.installProcessingWake(requests)
        try {
            val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = module { includes(indexing(FakeSignals()), library) })
            photos.consentToSearch(realm = true, device = true)
            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            PhotosLocalRuntime.runProcessingWake("wake-2", timeline.wakeTask())
            val runner: MediaIndexRunner = photos.koin.get()
            eventually("the wake did not start its pass") { runner.standing.value == PassStanding.Running(1, manual = false) }

            PhotosLocalRuntime.onProcessingWakeExpired("wake-2")
            timeline.add("handler returned")
            eventually("the wake was not completed: ${timeline.all()}") { timeline.all().any { it.startsWith("complete") } }
            val events = timeline.all()
            assertEquals(listOf("token", "expire"), events.take(2), "the token's end was not made an expiry: $events")
            assertTrue(events.indexOf("expire") < events.indexOf("handler returned"), "the handler returned before the token ended: $events")
            assertEquals(listOf("complete(false)"), events.filter { it.startsWith("complete") }, "$events")
            assertFalse("release" in events, "the token was released, not expired: $events")
            assertEquals(PassStanding.Idle(PassEnding.Stopped(1)), runner.standing.value, "the pass did not stop")
            gate.complete(Unit)
            delay(300)
            assertEquals(1, timeline.all().count { it.startsWith("complete") }, "completed twice: ${timeline.all()}")
            assertFalse(photos.koin.get<IndexingConditions>(named("indexingConditions")).allowIndexing())
        } finally {
            PhotosLocalRuntime.taskProtection = saved
            app.tenshu.control.di.PhotosProcessingWake.uninstall()
        }
    }

    /** ⚠⚠ Absence is NO: with no consent a wake runs nothing, re-arms nothing, and a leave arms nothing. */
    @Test
    fun withNoConsentAWakeDoesNothingAndNothingIsArmed() = runBlocking {
        val timeline = TokenTimeline()
        val requests = WakeRequests()
        val saved = PhotosLocalRuntime.taskProtection
        PhotosLocalRuntime.taskProtection = timeline::protection
        PhotosLocalRuntime.installProcessingWake(requests)
        try {
            val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = indexing(FakeSignals()))
            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            delay(300)
            assertEquals(emptyList(), requests.all(), "a leave with no consent armed a wake")

            PhotosLocalRuntime.runProcessingWake("wake-3", timeline.wakeTask())
            eventually("the wake was not completed: ${timeline.all()}") { timeline.all().any { it.startsWith("complete") } }
            assertEquals(listOf("token", "release", "complete(true)"), timeline.all())
            assertEquals(emptyList(), requests.all(), "a wake with no consent re-armed")
            assertEquals(PassStanding.Idle(null), photos.koin.get<MediaIndexRunner>().standing.value, "a wake with no consent ran a pass")
        } finally {
            PhotosLocalRuntime.taskProtection = saved
            app.tenshu.control.di.PhotosProcessingWake.uninstall()
        }
    }

    /** ⚠⚠ Every wake handed to the runtime is completed: with no runtime, and after its own expiry. */
    @Test
    fun aWakeWithNoRuntimeOrAlreadyExpiredIsCompletedAtOnceWithFailure() = runBlocking {
        val orphan = TokenTimeline()
        PhotosLocalRuntime.runProcessingWake("wake-orphan", orphan.wakeTask())
        assertEquals(listOf("complete(false)"), orphan.all(), "no runtime installed")

        installedWithALoadedTower(temp("photos-ai"), firstRun = true)
        val late = TokenTimeline()
        PhotosLocalRuntime.onProcessingWakeBegan("wake-late")
        PhotosLocalRuntime.onProcessingWakeExpired("wake-late")
        PhotosLocalRuntime.runProcessingWake("wake-late", late.wakeTask())
        assertEquals(listOf("complete(false)"), late.all(), "a wake that expired before it ran")
        // An expiry for a wake already completed keeps no place for it.
        PhotosLocalRuntime.onProcessingWakeExpired("wake-late")
        PhotosLocalRuntime.onProcessingWakeExpired("wake-never")
        assertEquals(0, PhotosLocalRuntime.wakeEntryCount(), "an expiry left a wake's place behind")
    }

    /**
     * ⚠⚠ An expiry in the window after the wake is marked running and before it is launched (the
     * journal's write): it finds no wake current and ends the token, so the launch must still be told,
     * or the wake would run past its own expiry and never be completed.
     */
    @Test
    fun anExpiryJustBeforeTheLaunchIsNotLost() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val timeline = TokenTimeline()
        val requests = WakeRequests()
        val library = module {
            single<MediaSource> { MediaSource { listOf(IndexableMedia("/photos/a.bmp") { gate.await(); aPhotograph() }) } }
        }
        val saved = PhotosLocalRuntime.taskProtection
        PhotosLocalRuntime.taskProtection = timeline::protection
        PhotosLocalRuntime.installProcessingWake(requests)
        try {
            val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true, extra = module { includes(indexing(FakeSignals()), library) })
            photos.consentToSearch(realm = true, device = true)
            PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
            PhotosLocalRuntime.onProcessingWakeBegan("wake-window")
            PhotosLocalRuntime.beforeWakeLaunchForTests = { PhotosLocalRuntime.onProcessingWakeExpired("wake-window") }
            PhotosLocalRuntime.runProcessingWake("wake-window", timeline.wakeTask())
            eventually("the wake was never completed: ${timeline.all()}") { timeline.all().any { it.startsWith("complete") } }
            delay(300)
            val events = timeline.all()
            assertEquals(listOf("complete(false)"), events.filter { it.startsWith("complete") }, "$events")
            assertTrue(events.indexOf("expire") in 0 until events.indexOf("complete(false)"), "the token did not end first: $events")
            assertEquals(0, PhotosLocalRuntime.wakeEntryCount())
            gate.complete(Unit)
            Unit
        } finally {
            PhotosLocalRuntime.beforeWakeLaunchForTests = null
            PhotosLocalRuntime.taskProtection = saved
            app.tenshu.control.di.PhotosProcessingWake.uninstall()
        }
    }

    /** Photos left: unknown is left, a pass of this runner that ran to its end is not. */
    @Test
    fun photosAreLeftUnlessAPassRanToItsEnd() {
        assertEquals(WakeWork.LEFT, PhotosOnDeviceAi.workLeft(PassStanding.Idle(null)))
        assertEquals(WakeWork.LEFT, PhotosOnDeviceAi.workLeft(PassStanding.Running(3, manual = true)))
        assertEquals(WakeWork.LEFT, PhotosOnDeviceAi.workLeft(PassStanding.Idle(PassEnding.Stopped(2))))
        assertEquals(WakeWork.LEFT, PhotosOnDeviceAi.workLeft(PassStanding.Idle(PassEnding.Finished(2, IndexPassResult(deferred = true)))))
        assertEquals(WakeWork.LEFT, PhotosOnDeviceAi.workLeft(PassStanding.Idle(PassEnding.Failed(2, "x"))))
        assertEquals(WakeWork.NONE_LEFT, PhotosOnDeviceAi.workLeft(PassStanding.Idle(PassEnding.Finished(2, IndexPassResult(libraryWalk = true)))))
    }

    @Test
    fun everyGraphOfTheProcessSharesOnePassSlot() {
        assertSame(photos().koin.get<PassExclusion>(), photos().koin.get<PassExclusion>(), "two graphs, two single flights")
    }

    /** ⚠⚠ The backend's open is recorded (§14.18.16 item 9), and the record is cleared once it opened. */
    @Test
    fun aTowerTheBackendOpensIsRecordedAndClearedOnceItOpened() = runBlocking {
        val photos = installedWithALoadedTower(temp("photos-ai"), firstRun = true)
        val record: String? = photos.koin.get<FileDatabase>(named(INFERENCE_FILE_DATABASE))
            .readBytes(AiReadiness.VERDICT_PATH + ".open.vision")?.get()?.decodeToString()
        assertEquals("""{"begun":null}""", record, "the open was not recorded, or its record outlived it")
    }

    /** A packed vocabulary in which "a" is id 3 and "b" is id 4; `</s>`, id 1, ends and pads a row. */
    private fun probeVocabulary(): ByteArray {
        fun le(value: Int): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }
        val pieces: List<ByteArray> = listOf("<pad>", "</s>", "<unk>", "▁a", "▁b").map { it.encodeToByteArray() }
        return "TSHU".encodeToByteArray() + le(1) + le(pieces.size) + byteArrayOf(1) + le(2) +
            pieces.map { piece -> le((-1f).toRawBits()) + byteArrayOf(piece.size.toByte()) + piece }.reduce(ByteArray::plus)
    }

    private fun row(id: Int): IntArray = IntArray(VisionModel.PinnedSigLip.textSequenceLength) { 1 }.also { it[0] = id }

    /** A vector of [length] whose cosine with the first axis is [along]. */
    private fun vector(length: Float, along: Float, other: Int): FloatArray = FloatArray(width).also {
        it[0] = length * along
        it[other] = length * kotlin.math.sqrt(1f - along * along)
    }

    /** A tower that answers the probe as a sound model would: "a" describes the image, "b" does not. */
    private inner class ProbeSession(override val compiledPath: String, val role: String) : CoreMlSessionPort {
        var closed = false

        override fun predict(floats: FloatArray): FloatArray {
            duringProbe()
            return FloatArray(width).also { it[0] = 10f }
        }

        override fun predict(ids: IntArray): FloatArray = if (ids[0] == 3) vector(20f, 0.3f, other = 1) else vector(20f, -0.05f, other = 2)

        override fun close() {
            closed = true
        }
    }

    /** Every tower the preflight or the backend opened, in any graph of this test. */
    private val probed = ArrayList<ProbeSession>()
    private var mostProbedAtOnce = 0

    /** Runs inside every vision prediction. A test makes one block. */
    private var duringProbe: () -> Unit = {}

    /**
     * Fake towers, a cool phone, and a probe for the artifacts [probeable] installs. ⚠ Looked up
     * by the vocabulary's digest as the build's own probes are by all three: a model installed
     * with any other vocabulary has no probe here either.
     */
    private fun probing(): Module = module {
        single<CoreMlSessionOpener> {
            CoreMlSessionOpener { path, spec ->
                ProbeSession(path, spec.role).also {
                    probed += it
                    mostProbedAtOnce = maxOf(mostProbedAtOnce, probed.count { session -> !session.closed })
                }
            }
        }
        single<PreflightDevice> {
            object : PreflightDevice {
                override fun thermalState(): Int = 0
                override fun memoryWarnings(): Long = 0
                override fun footprintBytes(): Long? = null
                override fun availableBytes(): Long? = null
                override fun nowEpochMs(): Long = 1_000_000
            }
        }
        single<PreflightProbeLookup> {
            PreflightProbeLookup { key ->
                if (key.vocabularySha256 != sha256(probeVocabulary())) return@PreflightProbeLookup null
                PreflightProbe(
                    id = "test",
                    visionArchiveSha256 = key.runtime.visionArchiveSha256.orEmpty(),
                    textArchiveSha256 = key.runtime.textArchiveSha256.orEmpty(),
                    vocabularySha256 = key.vocabularySha256.orEmpty(),
                    imageRgbSha256 = "",
                    imageTensorSha256 = "",
                    captionDescribes = "a",
                    captionDoesNot = "b",
                    tokensDescribes = row(3),
                    tokensDoesNot = row(4),
                    hostMargin = 0.35,
                    minMargin = 0.1,
                    hostVisionNorm = 10.0,
                    hostDescribesNorm = 20.0,
                    hostDoesNotNorm = 20.0,
                    normBand = 2.0,
                )
            }
        }
    }

    /** Photos with a prepared model that has no verdict, a vocabulary that parses, and a probe for it. */
    private suspend fun probeable(root: String): PhotosLocalRuntime.Assembly {
        val photos = photos(root, overrides = probing())
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.installModel(
            mapOf(
                ModelArtifactRole.VISION to "a-vision".encodeToByteArray(),
                ModelArtifactRole.TEXT to "a-text".encodeToByteArray(),
                ModelArtifactRole.VOCABULARY to probeVocabulary(),
            ),
        )
        photos.markPrepared(root)
        return photos
    }

    /**
     * The two switches for search by description, written as the Settings screen writes them:
     * the realm's row and this device's. `null` leaves a row absent.
     */
    private fun PhotosLocalRuntime.Assembly.consentToSearch(realm: Boolean?, device: Boolean?) {
        val context = koin.get<ClientRegistry>().getExecutionContext(PhotosLocalRuntime.PHOTOS_CLIENT_ID)
        val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        if (realm != null) {
            context.saveData(
                Entity.create<RealmSetting>(
                    context = context.context,
                    composite = RealmSetting.create(
                        context = context.context,
                        preferenceId = capability.realmConsent.id,
                        value = capability.realmConsent.adapter.toData(realm, context.context),
                    ),
                ),
            )
        }
        if (device != null) {
            context.saveData(
                Entity.create<DeviceSetting>(
                    context = context.context,
                    composite = DeviceSetting.create(
                        context = context.context,
                        deviceId = koin.get<SyncManager>().currentDeviceId,
                        preferenceId = capability.deviceConsent.id,
                        value = capability.deviceConsent.adapter.toData(device, context.context),
                    ),
                ),
            )
        }
    }

    /** Readiness's writer 2, where the runtime starts it: the launch check finds a model awaiting its verdict. */
    @Test
    fun aPreparedModelIsProbedAtInstallBecomesReadyAndItsTextTowerIsWarmed() = runBlocking {
        val root = temp("photos-ai")
        val photos = probeable(root)
        photos.consentToSearch(realm = true, device = true)
        val readiness: AiReadiness = photos.koin.get()
        assertEquals(AiReadinessState.NoModel, readiness.current())

        PhotosLocalRuntime.install(photos)

        eventually("the launch check probed the model, and it passed") { readiness.current() == AiReadinessState.Ready }
        assertTrue(photos.koin.get<InferenceBackendRegistry>().isAvailable(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        eventually("the text tower was warmed after the pass") { probed.size == 3 }
        // The probe's own pair, opened outside the backend and closed; then the backend's text tower, kept.
        assertEquals(listOf(CoreMlTowerSpec.VISION_ROLE, CoreMlTowerSpec.TEXT_ROLE, CoreMlTowerSpec.TEXT_ROLE), probed.map { it.role })
        assertEquals(listOf(true, true, false), probed.map { it.closed })
        assertEquals(2, mostProbedAtOnce, "the backend opened a tower beside the probe's pair")
        assertIs<PreflightOutcome.Passed>(photos.koin.get<Preflight>().outcomeFor(photos.key()))

        // And the verdict is the next process's too.
        PhotosLocalRuntime.deprovision()
        val relaunched = photos(root, overrides = probing())
        relaunched.consentToSearch(realm = true, device = true)
        PhotosLocalRuntime.install(relaunched)
        eventually("the pass outlived the process") { relaunched.koin.get<AiReadiness>().current() == AiReadinessState.Ready }
        eventually("a launch that finds a ready model warms its text tower") { probed.size == 4 }
        assertEquals(CoreMlTowerSpec.TEXT_ROLE, probed.last().role, "a model with a pass was probed again")
    }

    /**
     * ⚠⚠ Production's own lookup: the probes this build was compiled with. A model whose digests
     * are not an export it has a record for is never loaded to be probed, and never `Ready`.
     */
    @Test
    fun aModelThisBuildCarriesNoProbeForIsNeverLoadedAndNeverReady() = runBlocking {
        val root = temp("photos-ai")
        val photos = photos(root, overrides = recordingSessions())
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.installModel("a")
        photos.markPrepared(root)
        val preflight: Preflight = photos.koin.get()

        PhotosLocalRuntime.install(photos)

        eventually("the launch check asked the preflight") { preflight.lastReport.value != null }
        assertEquals(PreflightOutcome.NoProbe, preflight.outcomeFor(photos.key()))
        assertEquals(AiReadinessState.AwaitingVerdict, photos.koin.get<AiReadiness>().current())
        assertFalse(photos.koin.get<InferenceBackendRegistry>().isAvailable(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        assertTrue(opened.isEmpty(), "a tower was opened for an export with no probe")
    }

    @Test
    fun theWarmUpIsTheLaunchsAndNotEveryForegrounds() = runBlocking {
        val root = temp("photos-ai")
        val photos = probeable(root)
        photos.consentToSearch(realm = true, device = true)
        assertEquals(AiReadinessState.Ready, photos.koin.get<AiReadiness>().recordVerdict(passed = true, probed = photos.key()))

        PhotosOnDeviceAi.recheck(photos.koin)
        assertTrue(probed.isEmpty(), "a return to the foreground opened a tower")

        PhotosOnDeviceAi.recheck(photos.koin, launch = true)
        assertEquals(listOf(CoreMlTowerSpec.TEXT_ROLE), probed.map { it.role })
        assertFalse(probed.single().closed)
    }

    @Test
    fun aPassOnALaterForegroundWarmsTheTextTowerToo() = runBlocking {
        val root = temp("photos-ai")
        val photos = probeable(root)
        photos.consentToSearch(realm = true, device = true)

        // Not the launch: the prepare step finished while Photos was open, and this is the next foreground.
        PhotosOnDeviceAi.recheck(photos.koin)

        assertEquals(AiReadinessState.Ready, photos.koin.get<AiReadiness>().current())
        assertEquals(listOf(true, true, false), probed.map { it.closed }, "the probe's pair, then the backend's text tower")
        assertEquals(CoreMlTowerSpec.TEXT_ROLE, probed.last().role)
    }

    /**
     * ⚠⚠ Two-switch consent, absence is NO (`inference-service.md`, IN-D3). The warm-up loads a
     * model for search, so it asks what `photosLike` asks, through the same rows.
     */
    @Test
    fun theWarmUpLoadsNoTowerUntilBothConsentSwitchesAreOn() = runBlocking {
        val root = temp("photos-ai")
        val photos = probeable(root)
        assertEquals(AiReadinessState.Ready, photos.koin.get<AiReadiness>().recordVerdict(passed = true, probed = photos.key()))
        suspend fun launchCheck(why: String, expectWarm: Boolean) {
            PhotosOnDeviceAi.recheck(photos.koin, launch = true)
            assertEquals(if (expectWarm) 1 else 0, probed.size, why)
        }

        launchCheck("no row at all: silence is not consent", expectWarm = false)
        photos.consentToSearch(realm = true, device = null)
        launchCheck("the realm's switch alone", expectWarm = false)
        photos.consentToSearch(realm = false, device = true)
        launchCheck("this device's switch alone", expectWarm = false)
        photos.consentToSearch(realm = true, device = false)
        launchCheck("this device switched off", expectWarm = false)

        photos.consentToSearch(realm = true, device = true)
        launchCheck("both switches on", expectWarm = true)
        assertEquals(CoreMlTowerSpec.TEXT_ROLE, probed.single().role)
    }

    @Test
    fun aPassWithoutConsentLoadsNothingForSearch() = runBlocking {
        val root = temp("photos-ai")
        val photos = probeable(root)

        PhotosOnDeviceAi.recheck(photos.koin, launch = true)

        assertEquals(AiReadinessState.Ready, photos.koin.get<AiReadiness>().current())
        // The probe's own pair, opened for a download the user asked for, and closed. Nothing after it.
        assertEquals(listOf(true, true), probed.map { it.closed }, "a tower was loaded for search without consent")
    }

    /**
     * ⚠⚠ §14.18.16 item 7: a launch over a model that passed, with consent still off, probes
     * nothing and loads no tower. ⚠ Readiness does pass through `AwaitingVerdict` for one
     * publication on the way to `Ready` — the fingerprint is applied before the model is served
     * (`aPassOutlivesTheProcessAndIsEnteredInOrder`) — and nothing else: no `Preparing`, no second
     * `AwaitingVerdict`. A Models screen collecting at that instant could draw it as *Checking*
     * (seen here first, 2026-11-09; the doc's item 7 says what that means for a user).
     * ⚠ **What this case pins is only the half that matters: no probe, no tower loaded, `Ready` at
     * the end, no second hop.** It accepts both `[Ready]` and `[AwaitingVerdict, Ready]` and does
     * not pin the hop: a `StateFlow` conflates, so whether a collector sees the brief
     * `AwaitingVerdict` depends on the dispatcher, not on readiness. A test that required it would
     * test the collector.
     */
    @Test
    fun aLaunchOverAPassWithConsentOffProbesNothingAndLoadsNoTower() = runBlocking {
        val root = temp("photos-ai")
        val first = probeable(root)
        PhotosOnDeviceAi.recheck(first.koin, launch = true)
        assertEquals(AiReadinessState.Ready, first.koin.get<AiReadiness>().current())
        assertEquals(listOf(true, true), probed.map { it.closed }, "the first launch's probe")

        val relaunched = photos(root, overrides = probing())
        val readiness: AiReadiness = relaunched.koin.get()
        val seen = ArrayList<AiReadinessState>()
        val watching = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch { readiness.state.collect { seen += it } }
        try {
            PhotosOnDeviceAi.recheck(relaunched.koin, launch = true)
            assertEquals(AiReadinessState.Ready, readiness.current())
        } finally {
            watching.cancel()
        }
        assertEquals(2, probed.size, "a launch over a pass loaded a tower with consent off: ${probed.map { it.role }}")
        val path: List<AiReadinessState> = seen.filterNot { it == AiReadinessState.NoModel }
        assertTrue(
            path == listOf(AiReadinessState.Ready) || path == listOf(AiReadinessState.AwaitingVerdict, AiReadinessState.Ready),
            "the launch took another path to Ready: $seen",
        )
        val asked: PreflightOutcome? = relaunched.koin.get<Preflight>().outcomeFor(relaunched.key())
        assertTrue(asked == null || asked == PreflightOutcome.NotAwaiting, "the launch's preflight answered $asked")
    }

    @Test
    fun aConsentReadThatThrowsIsNo() = runBlocking {
        val root = temp("photos-ai")
        val failing = module { single<SearchConsent> { SearchConsent { error("the realm is locked") } } }
        val photos = photos(root, overrides = module { includes(probing(), failing) })
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.installModel(
            mapOf(
                ModelArtifactRole.VISION to "a-vision".encodeToByteArray(),
                ModelArtifactRole.TEXT to "a-text".encodeToByteArray(),
                ModelArtifactRole.VOCABULARY to probeVocabulary(),
            ),
        )
        photos.markPrepared(root)
        // The rows say yes; the read fails.
        photos.consentToSearch(realm = true, device = true)
        assertEquals(AiReadinessState.Ready, photos.koin.get<AiReadiness>().recordVerdict(passed = true, probed = photos.key()))

        PhotosOnDeviceAi.recheck(photos.koin, launch = true)

        assertTrue(probed.isEmpty(), "a consent that could not be read was taken as given")
    }

    /**
     * The preflight loads models too. A runtime torn down while its probe is inside a prediction
     * still holds two towers the backend's close does not reach: the next runtime must load
     * nothing, its own probe included, until they are closed.
     */
    @Test
    fun aRuntimeTornDownUnderAProbeKeepsTheNextOneFromLoadingUntilItsTowersAreClosed() = runBlocking {
        val root = temp("photos-ai")
        val first = probeable(root)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        duringProbe = {
            if (entered.complete(Unit)) runBlocking { release.await() }
        }
        val second = try {
            PhotosLocalRuntime.install(first)
            withTimeout(30_000) { entered.await() }
            assertEquals(2, probed.count { !it.closed }, "the probe is not holding its pair")

            PhotosLocalRuntime.deprovision()
            assertTrue(PhotosOnDeviceAi.unloadPending(), "the teardown did not count the probe's towers as still loaded")
            photos(root, overrides = probing()).also { next ->
                PhotosLocalRuntime.install(next)
                delay(700)
                assertEquals(2, probed.size, "the next runtime loaded a model beside a pair a prediction still holds")
                assertTrue(PhotosOnDeviceAi.unloadPending())
            }
        } finally {
            release.complete(Unit)
        }

        eventually("the next runtime probed once the old towers were closed") {
            second.koin.get<AiReadiness>().current() == AiReadinessState.Ready
        }
        // ⚠ The torn-down probe reports no outcome: its caller's scope was cancelled with the runtime.
        // What it must have done is close its pair and record nothing.
        assertTrue(probed.take(2).all { it.closed }, "the torn-down runtime's probe left a tower open")
        assertEquals(2, mostProbedAtOnce, "two runtimes' towers were loaded side by side")
        assertFalse(first.koin.get<AiReadiness>().serves())
    }

    /**
     * The backend's own load waits for its graph's probe, not only for an earlier graph's unload.
     *
     * ⚠ `recordVerdict` is called by hand here, which the product does only from the preflight:
     * it stands in for the one route by which a model can become `Ready` under a running probe,
     * a store commit that restores an archive with a pass on disk, which the download (§14.11) makes.
     */
    @Test
    fun theBackendLoadsNoTowerWhileItsOwnGraphsProbeStillHoldsAPair() = runBlocking {
        val root = temp("photos-ai")
        val photos = probeable(root)
        val backend: CoreMlInferenceBackend = photos.koin.get()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        duringProbe = {
            if (entered.complete(Unit)) runBlocking { release.await() }
        }
        val waiting = try {
            PhotosLocalRuntime.install(photos)
            withTimeout(30_000) { entered.await() }
            assertEquals(AiReadinessState.Ready, photos.koin.get<AiReadiness>().recordVerdict(passed = true, probed = photos.key()))

            async(Dispatchers.Default) { backend.embedImageOutcome("not an image".encodeToByteArray()) }.also {
                delay(500)
                assertFalse(it.isCompleted, "the backend answered while the probe still held its pair: it should wait")
                assertEquals(2, probed.size, "the backend loaded a tower beside the probe's pair")
            }
        } finally {
            release.complete(Unit)
        }

        // ⚠ `Unreadable`: the tower was opened, then the decoder refused the bytes.
        assertEquals(ImageEmbeddingOutcome.Unreadable, withTimeout(30_000) { waiting.await() })
        assertEquals(2, mostProbedAtOnce)
    }

    // ─── the other route-1 apps ─────────────────────────────────────────────────────────────────

    /**
     * ⚠ Beside [PhotosOwnedInferenceStoresTest]'s fourth-lock cases, which already pin that these
     * graphs bind no backend: the Core ML binding must be Photos' alone too.
     */
    @Test
    fun noOtherAppsGraphHoldsAReadinessStateABackendOrACompiledCache() = runBlocking {
        val drive = DriveLocalRuntime.assemble(temp("drive-no-ai"), InMemorySecureStorageDelegate())
        val mia = MiaLocalRuntime.assemble(temp("mia-no-ai"), InMemorySecureStorageDelegate())

        listOf("Drive" to drive.koin, "Mia" to mia.koin).forEach { (who, koin) ->
            assertNull(koin.getOrNull<AiReadiness>(), "$who holds a readiness state")
            assertNull(koin.getOrNull<CoreMlInferenceBackend>(), "$who binds the backend")
            assertNull(koin.getOrNull<CoreMlModelCache>(), "$who holds a compiled cache")
            assertNull(koin.getOrNull<Preflight>(), "$who holds a preflight")
            assertNull(koin.getOrNull<List<InferenceBackend>>(named("inferenceBackends")), "$who binds a backend list")
            assertTrue(koin.get<InferenceBackendRegistry>().available.isEmpty(), "$who has a backend")
        }
    }
}
