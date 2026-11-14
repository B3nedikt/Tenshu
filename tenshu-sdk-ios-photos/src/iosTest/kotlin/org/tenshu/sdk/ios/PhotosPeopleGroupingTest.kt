@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.control.di.OwnedInferenceStores
import app.tenshu.control.di.PhotosOnDeviceAi
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsLayout
import app.tenshu.photos.ui.screens.collections.CollectionsUiState
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.ui.screens.collections.SmartPage
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.qualifier.named
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.StoredFace
import org.tenshu.inference.ModelProvenance
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.entity.Entity
import org.tenshu.preference.RealmSetting
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.coreml.iosFacePreflightKey
import org.tenshu.inference.proxy.onnx.FaceModel
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ios-face-grouping.md` F7: People's `groupingRunsHere` is the installed graph's answer, never a
 * Swift literal — `true` only where the assembly bound the face backend (F5's `facesAdmitted`) and
 * its preflight has not found the phone unsuitable — and where it speaks, People names Photos'
 * Settings → Models.
 *
 * ⭐ Every graph is `PhotosLocalRuntime.assemble`'s own, as in [PhotosFaceBindingTest].
 */
@OptIn(ExperimentalEncodingApi::class)
class PhotosPeopleGroupingTest {

    @BeforeTest
    fun redirectMain() {
        Dispatchers.setMain(Dispatchers.Default)
        OwnedInferenceStores.clearPriorWritersForTests()
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)
    }

    @AfterTest
    fun restore() {
        PhotosSurface.installNotConnected()
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        OwnedInferenceStores.clearPriorWritersForTests()
        Dispatchers.resetMain()
    }

    private val files = NSFileManager.defaultManager
    private fun temp(name: String): String = NSTemporaryDirectory() + "$name-" + Random.nextLong().toString(16)

    private fun material() = RealmKeyMaterial(
        sharedIdentityKey = Random.nextBytes(32),
        sharedVolumeKey = Random.nextBytes(32),
        deviceSigningBlob = (Base64.encode(Random.nextBytes(32)) + "|" + Base64.encode(Random.nextBytes(32))).encodeToByteArray(),
        ownerId = "owner-a",
        deviceId = "device-1",
        realmEncryptionPublicKey = Random.nextBytes(32),
        realmEncryptionPrivateKey = Random.nextBytes(32),
    )

    private val eightGigabytes = 8L * 1024 * 1024 * 1024

    private fun inputs(ai: Boolean = true, faces: Boolean = true) =
        PhotosLocalRuntime.OnDeviceAiInputs(buildFlag = ai, osMajorVersion = 17, physicalMemoryBytes = eightGigabytes, facesBuildFlag = faces)

    private val replica: String = temp("photos-people-replica")

    private fun photos(root: String = temp("photos-people"), onDeviceAi: PhotosLocalRuntime.OnDeviceAiInputs? = inputs()) =
        PhotosLocalRuntime.assemble(
            replicaDirectory = replica,
            secureStorage = InMemorySecureStorageDelegate(),
            handedOffMaterial = material(),
            relayBaseUrl = "http://127.0.0.1:1",
            inferenceRoot = root,
            deviceRealmOwner = { "owner-a" },
            replicaEpoch = { 1L },
            onDeviceAi = onDeviceAi,
            onDeviceAiOverrides = noCatalogThen(null),
        )

    /** A library with photographs and a People read that has said "not indexed yet". */
    private val unindexed = CollectionsUiState(
        years = listOf(SmartAlbum("2024", "2024", "1 photo", listOf(Photo("/a.jpg", 1L, isVideo = false, contentId = "a")))),
        peopleStatus = PeopleStatus.Unavailable(FaceGroupsUnavailable.Reasons.NOT_INDEXED),
    )

    private fun peopleLine(): String? =
        (PhotosSurface.collectionsLayout(unindexed) as CollectionsLayout.Content).people?.statusLine

    private fun peopleLineFor(reason: String): String? =
        (PhotosSurface.collectionsLayout(unindexed.copy(peopleStatus = PeopleStatus.Unavailable(reason))) as CollectionsLayout.Content)
            .people?.statusLine

    @Test
    fun aGraphWithNoFaceBackendGroupsNoFaces() {
        assertNull(PhotosLocalRuntime.faceGrouping(photos(onDeviceAi = inputs(faces = false))), "the face flag is off")
        assertNull(PhotosLocalRuntime.faceGrouping(photos(onDeviceAi = inputs(ai = false))), "the AI flag is off")
        assertNull(PhotosLocalRuntime.faceGrouping(photos(onDeviceAi = null)), "production: the Info.plist says NO")
    }

    @Test
    fun aGraphThatBindsTheFaceBackendGroupsFaces() {
        val grouping = assertNotNull(PhotosLocalRuntime.faceGrouping(photos()))
        assertTrue(grouping.runsHere(), "bound, with no model yet: People says what to do about it")
        assertFalse(grouping.modelPending(), "no model installed: 'download' is the right line")
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun sha256(bytes: ByteArray): String = Sha256Digest().apply { update(bytes, bytes.size) }.digest().toHexString()

    /** ⚠ A phone that cannot run the face model is never told to download it. */
    @Test
    fun aPhoneTheFacePreflightFoundUnsuitableGroupsNoFaces() = runBlocking {
        val root = temp("photos-people-unsuitable")
        val photos = photos(root)
        val weights = "AURAFACE-COREML".encodeToByteArray()
        val entry = ModelEntryData(
            modelId = FaceModel.AuraFaceCoreMl.modelId,
            name = "AuraFace",
            modelVersion = 1,
            capabilities = listOf(InferenceCapability.FACE_CLUSTERING),
            labelSetVersion = 1,
            runtime = FaceModel.COREML_VISION_RUNTIME,
            artifactVersion = 1,
            artifacts = listOf(ModelArtifactData(ModelArtifactRole.FACE_EMBEDDER, "models/faceEmbedder", sha256(weights), weights.size.toLong())),
            licence = "Apache-2.0",
        )
        assertNotNull(photos.koin.get<ModelStore>().add(entry, mapOf(ModelArtifactRole.FACE_EMBEDDER to weights)))
        val digest = sha256(weights)
        files.createDirectoryAtPath("$root/inference/coreml/${digest.take(16)}/${ModelArtifactRole.FACE_EMBEDDER}.mlmodelc", true, null, null)
        IosFileDatabase(root).writeRawByteArray("inference/coreml/${digest.take(16)}/ready", "digest=$digest\nosBuild=os\n".encodeToByteArray())
        PhotosOnDeviceAi.recheck(photos.koin)
        val readiness: AiReadiness = photos.koin.get(named(PhotosOnDeviceAi.FACE_READINESS))
        val grouping = assertNotNull(PhotosLocalRuntime.faceGrouping(photos))
        assertTrue(grouping.runsHere(), "prepared, awaiting its verdict")
        assertTrue(grouping.modelPending(), "installed and awaiting its verdict: not 'download it'")
        PhotosSurface.installFaceGrouping(grouping)
        assertEquals("People appear once the face model is ready on this device.", peopleLineFor(FaceGroupsUnavailable.Reasons.NO_BACKEND))
        // The first emission is the readiness as it stands; the verdict must be a SECOND one.
        val collecting = CompletableDeferred<Unit>()
        val changed = async(Dispatchers.Default) {
            PhotosSurface.faceGroupingChanges().onEach { collecting.complete(Unit) }.take(2).toList()
        }
        withTimeout(10_000) { collecting.await() }
        assertEquals(
            AiReadinessState.Unsuitable,
            readiness.recordVerdict(false, iosFacePreflightKey(photos.koin.get<ModelStore>().installed())),
        )
        assertFalse(grouping.runsHere(), "unsuitable: no line may send the user to a model this phone cannot run")
        assertFalse(grouping.modelPending())
        assertEquals(2, withTimeout(10_000) { changed.await() }.size, "the verdict is a change the tab redraws for")
    }

    /**
     * `ios-face-grouping.md` §17.6: the Collections view model's `indexChanges` is the INSTALLED
     * grouping's pass changes — a pass that wrote face templates re-reads People, which the library
     * watch cannot see.
     */
    @Test
    fun theCollectionsTabFollowsTheInstalledGraphsPassChanges() = runBlocking {
        PhotosSurface.installFaceGrouping(PhotosFaceGrouping({ true }, { false }, emptyFlow(), passChanges = flowOf(Unit, Unit)))
        assertEquals(2, withTimeout(10_000) { PhotosSurface.faceIndexChanges().take(2).toList() }.size)
    }

    /**
     * ⚠⚠ End to end over a REAL graph: a pass that changes the face store — here a withdrawal's
     * clear — moves its runner's count, and `faceIndexChanges` (the installed grouping, through
     * `flatMapLatest`) emits exactly once. ⚠ Red if the runner never resolved: then nothing emits.
     */
    @Test
    fun aFaceStoreChangingPassOnTheRealGraphIsExactlyOneEmission() = runBlocking {
        val photos = photos()
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        val store = photos.koin.get<FaceTemplateStore>()
        assertTrue(store.put("/a.jpg", listOf(StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), floatArrayOf(1f, 0f))), ModelProvenance("auraface", 1)))
        PhotosSurface.installFaceGrouping(assertNotNull(PhotosLocalRuntime.faceGrouping(photos)))
        val emissions = MutableStateFlow(0)
        val collector = launch(Dispatchers.Default) { PhotosSurface.faceIndexChanges().collect { emissions.update { it + 1 } } }
        // ⚠ The runner is resolved off-main on first collect, and that is itself ONE invalidation.
        withTimeout(10_000) { emissions.first { it > 0 } }
        delay(500)
        assertEquals(1, emissions.value, "resolving the runner is one invalidation, nothing more")

        photos.withdrawFaceClustering()
        val runner = photos.koin.get<MediaIndexRunner>()
        assertTrue(runner.start(photos.context(), manual = true))
        withTimeout(10_000) { while (runner.isRunning()) delay(20) }

        assertEquals(0, store.size(), "the withdrawal cleared the store")
        withTimeout(10_000) { emissions.first { it > 1 } }
        delay(500)
        assertEquals(2, emissions.value, "one pass, one re-read of People")
        collector.cancel()
    }

    /**
     * ⚠⚠ The window Codex found: the view model's first walk starts before the runner is resolved
     * (off-main), so a pass that ends in between would be the silent baseline — and later passes over
     * the rows it wrote move nothing, leaving People stale on every clean return. Resolving is one
     * invalidation, so such a pass is still walked for.
     */
    @Test
    fun aPassThatEndedBeforeTheRunnerResolvedIsStillWalkedFor() = runBlocking {
        val photos = photos()
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        val store = photos.koin.get<FaceTemplateStore>()
        assertTrue(store.put("/a.jpg", listOf(StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), floatArrayOf(1f, 0f))), ModelProvenance("auraface", 1)))
        // The pass ends — the store changes — while the tab's walk is reading and nobody has subscribed.
        photos.withdrawFaceClustering()
        val runner = photos.koin.get<MediaIndexRunner>()
        assertTrue(runner.start(photos.context(), manual = true))
        withTimeout(10_000) { while (runner.isRunning()) delay(20) }
        assertEquals(0, store.size())

        PhotosSurface.installFaceGrouping(assertNotNull(PhotosLocalRuntime.faceGrouping(photos)))
        val emissions = MutableStateFlow(0)
        val collector = launch(Dispatchers.Default) { PhotosSurface.faceIndexChanges().collect { emissions.update { it + 1 } } }
        withTimeout(10_000) { emissions.first { it > 0 } }
        delay(500)

        assertEquals(1, emissions.value, "the subscription that missed the pass still invalidates, once")
        collector.cancel()
    }

    /** ⚠⚠ LOW-2: a rebuild's grouping replaces the old runner's count — the switch itself is one emission. */
    @Test
    fun aGroupingThatReplacesAnotherIsOneEmission() = runBlocking {
        PhotosSurface.installFaceGrouping(PhotosFaceGrouping({ true }, { false }, emptyFlow()))
        val emissions = MutableStateFlow(0)
        val collector = launch(Dispatchers.Default) { PhotosSurface.faceIndexChanges().collect { emissions.update { it + 1 } } }
        delay(500)
        assertEquals(0, emissions.value, "the grouping current at subscribe is the baseline")

        PhotosSurface.installFaceGrouping(PhotosFaceGrouping({ true }, { false }, emptyFlow()))
        withTimeout(10_000) { emissions.first { it > 0 } }
        PhotosSurface.installFaceGrouping(null)
        delay(500)

        assertEquals(1, emissions.value, "a replacement walks once; a teardown to none does not")
        collector.cancel()
    }

    private fun PhotosLocalRuntime.Assembly.context() =
        koin.get<ClientRegistry>().getExecutionContext(PhotosLocalRuntime.PHOTOS_CLIENT_ID)

    /** The explicit switch-off: the realm row for face clustering, written `false`. */
    private fun PhotosLocalRuntime.Assembly.withdrawFaceClustering() {
        val context = context()
        val preference = InferenceCapability.FACE_CLUSTERING.realmConsent
        context.saveData(
            Entity.create<RealmSetting>(
                context = context.context,
                composite = RealmSetting.create(
                    context = context.context,
                    preferenceId = preference.id,
                    value = preference.adapter.toData(false, context.context),
                ),
            ),
        )
    }

    /** ⚠ B.16: a graph the shared store's host stopped answers `false` until the rebuild installs its own. */
    @Test
    fun aTornDownGraphGroupsNoFaces() = runBlocking {
        val photos = photos()
        val grouping = assertNotNull(PhotosLocalRuntime.faceGrouping(photos))
        PhotosSurface.installFaceGrouping(grouping)
        assertTrue(PhotosSurface.groupingRunsHere())
        PhotosLocalRuntime.teardownGraph(photos)
        photos.unloading?.join()
        assertFalse(grouping.runsHere(), "the torn-down graph's readiness is retired")
        assertFalse(PhotosSurface.groupingRunsHere())
    }

    /**
     * ⚠⚠ With no face backend a synced name is still a person in the state, but naming could never
     * save there: the page is no person's — no header, no naming, no "With…".
     */
    @Test
    fun aPersonsPageIsAPlainGridWhereThisPhoneGroupsNoFaces() {
        val mia = SmartAlbum("person:name:Mia", "Mia", "", listOf(Photo("/m.jpg", 1L, isVideo = false, contentId = "m")))
        val state = CollectionsUiState(people = listOf(mia))
        val page = SmartPage(mia, loading = false, failed = false)
        PhotosSurface.installNotConnected()
        val off = PhotosSurface.personPage(state, mia.id, page)
        assertNull(off.header)
        assertNull(off.nameId)
        PhotosSurface.installFaceGrouping(PhotosLocalRuntime.faceGrouping(photos()))
        val on = PhotosSurface.personPage(state, mia.id, page)
        assertNotNull(on.header)
        assertEquals(mia.id, on.nameId)
    }

    @Test
    fun theTabAsksTheInstalledGraph_andNamesPhotosModelsScreen_andForgetsItOnTeardown() {
        PhotosSurface.installNotConnected()
        assertFalse(PhotosSurface.groupingRunsHere())
        assertNull(peopleLine(), "nothing installed: no nag")

        PhotosSurface.installFaceGrouping(PhotosLocalRuntime.faceGrouping(photos()))
        assertTrue(PhotosSurface.groupingRunsHere())
        val line = assertNotNull(peopleLine())
        assertTrue("Index now" in line && "Photos" in line && "Control" !in line, line)

        PhotosSurface.installFaceGrouping(PhotosLocalRuntime.faceGrouping(photos(onDeviceAi = inputs(faces = false))))
        assertFalse(PhotosSurface.groupingRunsHere(), "a graph without the face backend")
        assertNull(peopleLine())

        PhotosSurface.installFaceGrouping(PhotosLocalRuntime.faceGrouping(photos()))
        PhotosSurface.installNotConnected()
        assertFalse(PhotosSurface.groupingRunsHere(), "a teardown forgets the graph's answer")
    }
}
