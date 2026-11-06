@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.async
import app.tenshu.control.di.INFERENCE_FILE_DATABASE
import app.tenshu.control.di.OwnedInferenceStores
import app.tenshu.control.di.buildPlatformProxyModule
import app.tenshu.control.di.buildProxyModule
import app.tenshu.photos.model.SemanticSearchStatus
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.preference.RealmSetting
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.InProcessDatabaseLock
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.sharedReplicaDirectory
import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.SemanticSearchUnavailable
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.faceGroups
import org.tenshu.inference.photosLike
import org.tenshu.core.collection.list.toList
import org.tenshu.inference.proxy.DefaultEmbeddingStore
import org.tenshu.inference.proxy.DefaultFaceTemplateStore
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.InferenceStoreCustody
import org.tenshu.inference.proxy.InferenceStoreGate
import org.tenshu.inference.proxy.InferenceStoreState
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.StoredFace
import org.tenshu.inference.proxy.UnownedEmbeddingStore
import org.tenshu.inference.proxy.UnownedFaceTemplateStore
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.onnx.VisionModel
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PIO-D10 (`ios-on-device-inference.md` §3.1, §13): on iOS **Photos** owns the inference
 * stores, in its own container, and every other route-1 graph binds stores that cannot write.
 *
 * ⭐ Every graph here is the production one: the per-app `assemble`, or for Control the two modules
 * `ControlSurface.installRealRuntime()` boots. A lock that held only in a hand-built graph would
 * hold nowhere.
 *
 * ⚠ Real dispatchers and `Main` redirected, for [DriveLocalRuntimeTest]'s reasons.
 */
@OptIn(ExperimentalEncodingApi::class)
class PhotosOwnedInferenceStoresTest {

    @BeforeTest
    fun redirectMain() {
        Dispatchers.setMain(Dispatchers.Default)
        // B.16 (248): each test starts from a process holding no stopped graph's writer.
        OwnedInferenceStores.clearPriorWritersForTests()
    }

    @AfterTest
    fun restoreMain() {
        Dispatchers.resetMain()
        OwnedInferenceStores.clearPriorWritersForTests()
    }

    private val files = NSFileManager.defaultManager
    private val model = ModelProvenance("siglip", 1)

    private fun temp(name: String): String = NSTemporaryDirectory() + "$name-" + Random.nextLong().toString(16)

    /** Where a store over the SHARED database would put its logs: the App Group on a phone. */
    private val sharedInference: String =
        sharedReplicaDirectory(IosFileDatabase.CONTENT_SUBDIRECTORY) + "/inference"

    private fun material(ownerId: String) = RealmKeyMaterial(
        sharedIdentityKey = Random.nextBytes(32),
        sharedVolumeKey = Random.nextBytes(32),
        deviceSigningBlob = (
            Base64.encode(Random.nextBytes(32)) + "|" + Base64.encode(Random.nextBytes(32))
            ).encodeToByteArray(),
        ownerId = ownerId,
        deviceId = "device-1",
        realmEncryptionPublicKey = Random.nextBytes(32),
        realmEncryptionPrivateKey = Random.nextBytes(32),
    )

    /** The realm the DEVICE is on — what Control's hand-off says in production. A test moves it. */
    private var deviceOwner: String? = "owner-a"

    /**
     * Photos, as a new process over [root] and [secureStorage]. Adopted as [graphOwner], so its
     * `SyncManager` holds that owner the way an installed runtime does.
     */
    private fun photos(
        root: String,
        graphOwner: String = "owner-a",
        secureStorage: SecureStorageDelegate = InMemorySecureStorageDelegate(),
        backupExclusion: ((String) -> String?)? = null,
        replica: String = temp("photos-owned-inference-replica"),
    ): PhotosLocalRuntime.Assembly = PhotosLocalRuntime.assemble(
        replicaDirectory = replica,
        secureStorage = secureStorage,
        handedOffMaterial = material(graphOwner),
        relayBaseUrl = "http://127.0.0.1:1",
        inferenceRoot = root,
        deviceRealmOwner = { deviceOwner },
        inferenceBackupExclusion = backupExclusion,
    )

    private fun text(path: String): String? = files.contentsAtPath(path)?.let {
        NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString()
    }

    private fun face() = StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), floatArrayOf(1f, 0f))

    private suspend fun PhotosLocalRuntime.Assembly.index(path: String = "/a.jpg") {
        assertTrue(koin.get<EmbeddingStore>().put(path, floatArrayOf(1f, 0f), model))
        assertTrue(koin.get<FaceTemplateStore>().put(path, listOf(face()), model))
    }

    private suspend fun PhotosLocalRuntime.Assembly.vectors(): Int = koin.get<EmbeddingStore>().size()
    private suspend fun PhotosLocalRuntime.Assembly.templates(): Int = koin.get<FaceTemplateStore>().size()

    @OptIn(ExperimentalStdlibApi::class)
    private fun sha256(bytes: ByteArray): String = Sha256Digest().apply { update(bytes, bytes.size) }.digest().toHexString()

    /** The pinned checkpoint's vision tower, as [weights]. */
    private fun checkpoint(weights: ByteArray) = ModelEntryData(
        modelId = VisionModel.PinnedSigLip.modelId,
        name = "SigLIP",
        modelVersion = 1,
        capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        labelSetVersion = 1,
        runtime = "coreml",
        artifactVersion = 1,
        artifacts = listOf(
            ModelArtifactData(ModelArtifactRole.VISION, "models/vision.tar", sha256(weights), weights.size.toLong()),
        ),
        licence = "Apache-2.0",
    )

    private fun PhotosLocalRuntime.Assembly.installModel(weights: ByteArray = "WEIGHTS".encodeToByteArray()) {
        assertNotNull(koin.get<ModelStore>().add(checkpoint(weights), mapOf(ModelArtifactRole.VISION to weights)))
    }

    private suspend fun eventually(what: String, condition: suspend () -> Boolean) {
        runCatching { withTimeout(30_000) { while (!condition()) delay(20) } }
        assertTrue(condition(), what)
    }

    // ─── every other graph: the fourth lock ─────────────────────────────────────────────────────

    /**
     * What a graph that is not Photos' must be: no backend, no conditions, no runtime, stores that
     * hold no database, and nothing written under the shared container whatever is asked of it.
     */
    private suspend fun assertOwnsNoInference(koin: Koin, client: TenshuClient, who: String) {
        files.removeItemAtPath("$sharedInference/embeddings.log", error = null)
        files.removeItemAtPath("$sharedInference/faces.log", error = null)

        assertSame<Any>(UnownedEmbeddingStore, koin.get<EmbeddingStore>(), "$who binds a vector store that can write")
        assertSame<Any>(UnownedFaceTemplateStore, koin.get<FaceTemplateStore>(), "$who binds a template store that can write")
        assertTrue(koin.get<InferenceBackendRegistry>().available.isEmpty(), "$who has a backend")
        assertNull(koin.getOrNull<List<InferenceBackend>>(named("inferenceBackends")))
        assertNull(koin.getOrNull<IndexingConditions>(named("indexingConditions")), "$who may index")
        assertNull(koin.getOrNull<Set<String>>(named("supportedModelRuntimes")), "$who is offered models")
        assertNull(koin.getOrNull<InferenceStoreCustody>())
        assertSame(InferenceStoreGate.Unstamped, koin.get<InferenceStoreGate>())
        // The model store stays where it was, over the shared database; with no runtime nothing
        // can be committed to it.
        assertSame(koin.get<FileDatabase>(), koin.get<FileDatabase>(named(INFERENCE_FILE_DATABASE)))

        // Everything a process can ask of the stores: an op that resolves them, a write, the
        // clear the realm-wipe hook makes.
        val answer = client.inferenceService.photosLike("a dog", 5)
        val refusal = assertIs<TResult.Failure<*>>(answer, "$who answered photosLike: $answer").error
        assertEquals(
            SemanticSearchUnavailable.Reasons.NO_BACKEND,
            assertIs<SemanticSearchUnavailable>(refusal).reason.get(),
        )
        assertFalse(koin.get<EmbeddingStore>().put("/a.jpg", floatArrayOf(1f, 0f), model))
        assertFalse(koin.get<FaceTemplateStore>().put("/a.jpg", listOf(face()), model))
        koin.get<EmbeddingStore>().clear()
        koin.get<FaceTemplateStore>().clear()

        assertFalse(files.fileExistsAtPath("$sharedInference/embeddings.log"), "$who wrote the shared vector log")
        assertFalse(files.fileExistsAtPath("$sharedInference/faces.log"), "$who wrote the shared template log")
    }

    @Test
    fun drivesRuntimeOwnsNoInference() = runBlocking {
        val drive = DriveLocalRuntime.assemble(temp("drive-owns-no-inference"), InMemorySecureStorageDelegate())
        assertOwnsNoInference(drive.koin, drive.koin.get(), "Drive")
    }

    /**
     * ⚠ Mia, where the plan's row names Calendar: `CalendarLocalRuntime.assemble` constructs
     * `IosNotificationPresenter`, and `UNUserNotificationCenter` aborts a process with no bundle,
     * which the headless test host is. Calendar's one override is that presenter. Mia is the
     * better witness anyway: it is the app that asks `photosLike`.
     */
    @Test
    fun miasRuntimeOwnsNoInference() = runBlocking {
        val mia = MiaLocalRuntime.assemble(temp("mia-owns-no-inference"), InMemorySecureStorageDelegate())
        assertOwnsNoInference(mia.koin, mia.tenshuClient, "Mia")
    }

    @Test
    fun controlsRuntimeOwnsNoInference() = runBlocking {
        // `ControlSurface.installRealRuntime()`'s two modules. ⚠ The overrides are isolation only —
        // its store, lock and Keychain would otherwise be the test host's real ones.
        val koin = koinApplication {
            modules(
                buildProxyModule(),
                buildPlatformProxyModule(),
                module {
                    single<SecureStorageDelegate> { InMemorySecureStorageDelegate() }
                    single<ObjectStore> { InMemoryObjectStore() }
                    single<DatabaseLock> { InProcessDatabaseLock() }
                },
            )
        }.koin
        assertOwnsNoInference(koin, koin.get(), "Control")
    }

    // ─── Photos: the container ──────────────────────────────────────────────────────────────────

    @Test
    fun photosKeepsItsStoresInItsOwnContainerAndNothingInTheSharedOne() = runBlocking {
        files.removeItemAtPath("$sharedInference/embeddings.log", error = null)
        files.removeItemAtPath("$sharedInference/faces.log", error = null)
        val root = temp("photos-inference")
        val photos = photos(root)

        assertNotSame(photos.koin.get<FileDatabase>(), photos.koin.get<FileDatabase>(named(INFERENCE_FILE_DATABASE)))
        // B.16 (247): behind the writer barrier, built on first use.
        val embeddings = assertIs<app.tenshu.control.di.AfterPriorWriterEmbeddingStore>(photos.koin.get<EmbeddingStore>())
        val faces = assertIs<app.tenshu.control.di.AfterPriorWriterFaceTemplateStore>(photos.koin.get<FaceTemplateStore>())
        embeddings.size()
        faces.size()
        assertIs<DefaultEmbeddingStore>(embeddings.builtOrNull)
        assertIs<DefaultFaceTemplateStore>(faces.builtOrNull)
        // B.16 (246): the gate is the custody's, behind the stopped-writer barrier.
        assertSame<Any>(photos.koin.get<InferenceStoreCustody>(), assertIs<app.tenshu.control.di.WriterBarrierGate>(photos.koin.get<InferenceStoreGate>()).custody)

        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.index()
        photos.installModel()

        assertTrue(files.fileExistsAtPath("$root/inference/embeddings.log"))
        assertTrue(files.fileExistsAtPath("$root/inference/faces.log"))
        assertTrue(files.fileExistsAtPath("$root/inference/realm.stamp"))
        assertTrue(files.fileExistsAtPath("$root/inference/runtime.fingerprint"))
        assertTrue(files.fileExistsAtPath("$root/inference/models/inference.models.index.json"))
        assertFalse(files.fileExistsAtPath("$sharedInference/embeddings.log"))
        assertFalse(files.fileExistsAtPath("$sharedInference/faces.log"))
    }

    /**
     * The PRODUCTION root, resolved, with nothing passed for it. ⚠ `inference` as the root's own
     * name would put the logs at `inference/inference/…`: the stores' logical paths begin with it.
     */
    @Test
    fun thePrivateRootIsPhotosPrivateAndNoSegmentIsDoubled() = runBlocking {
        val root = OwnedInferenceStores.privateRoot()
        assertTrue(root.endsWith("/Application Support/photos-private"), root)
        val photos = PhotosLocalRuntime.assemble(
            replicaDirectory = temp("photos-owned-inference-replica"),
            secureStorage = InMemorySecureStorageDelegate(),
            handedOffMaterial = material("owner-a"),
            relayBaseUrl = "http://127.0.0.1:1",
            deviceRealmOwner = { deviceOwner },
        )
        try {
            assertEquals(root, photos.inferenceRoot)
            assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
            photos.index()

            assertTrue(files.fileExistsAtPath("$root/inference/embeddings.log"))
            assertTrue(files.fileExistsAtPath("$root/inference/realm.stamp"))
            assertFalse(files.fileExistsAtPath("$root/inference/inference"))

            // And the erase that names no root finds the same place.
            assertTrue(OwnedInferenceStores.relinquish(koin = null))
            assertFalse(files.fileExistsAtPath("$root/inference/embeddings.log"), "the default erase looked elsewhere")
            assertFalse(files.fileExistsAtPath("$root/inference/realm.stamp"))
        } finally {
            files.removeItemAtPath(root, error = null)
        }
    }

    @Test
    fun photosInferenceDirectoryIsExcludedFromBackup() = runBlocking {
        val root = temp("photos-inference-backup")
        photos(root).koin.get<FileDatabase>(named(INFERENCE_FILE_DATABASE))

        val excluded = memScoped {
            val value = alloc<ObjCObjectVar<Any?>>()
            NSURL.fileURLWithPath(root, isDirectory = true)
                .getResourceValue(value.ptr, forKey = NSURLIsExcludedFromBackupKey, error = null)
            (value.value as? NSNumber)?.boolValue
        }
        assertEquals(true, excluded)
    }

    @Test
    fun aDirectoryThatCannotBeExcludedFromBackupHoldsNothingAndNothingIsUsable() = runBlocking {
        val root = temp("photos-inference-not-excluded")
        val photos = photos(root, backupExclusion = { "refused by the test" })

        assertFalse(PhotosLocalRuntime.checkInferenceStores(photos))

        assertFalse(OwnedInferenceStores.usable(photos.koin))
        val state = assertIs<InferenceStoreState.ClearFailed>(photos.koin.get<InferenceStoreCustody>().state())
        assertTrue("excluded from backup" in generateSequence(state.cause) { it.cause }.joinToString { it.message.orEmpty() })
        // Whatever is asked of the stores afterwards, nothing reaches the directory.
        runCatching { photos.koin.get<EmbeddingStore>().put("/a.jpg", floatArrayOf(1f, 0f), model) }
        runCatching { photos.koin.get<FaceTemplateStore>().put("/a.jpg", listOf(face()), model) }
        assertTrue(runCatching { photos.installModel() }.isFailure, "a model was committed to a directory that is backed up")
        assertFalse(PhotosLocalRuntime.checkInferenceStores(photos))
        assertTrue(files.subpathsAtPath(root).orEmpty().isEmpty(), "written: ${files.subpathsAtPath(root)}")
    }

    /**
     * The flag was lost, or cannot be set again, on a directory that already holds everything:
     * it is being backed up as it stands.
     */
    @Test
    fun aDirectoryThatLosesItsExclusionIsPurgedModelsIncludedAndThenRefuses() = runBlocking {
        val root = temp("photos-inference-lost-exclusion")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
            installModel()
        }
        assertTrue(files.fileExistsAtPath("$root/inference/embeddings.log"), "the fixture")
        assertTrue(files.fileExistsAtPath("$root/inference/models/inference.models.index.json"), "the fixture")

        val relaunched = photos(root, backupExclusion = { "refused by the test" })
        assertFalse(PhotosLocalRuntime.checkInferenceStores(relaunched))

        assertFalse(OwnedInferenceStores.usable(relaunched.koin))
        // ⚠ Everything, the weights and their index too: behind the refusing database nothing can
        // read them, and left there they are several hundred MB in the user's backup.
        assertTrue(files.subpathsAtPath(root).orEmpty().isEmpty(), "left in a backed-up directory: ${files.subpathsAtPath(root)}")
        assertTrue(relaunched.koin.get<ModelStore>().installed().isEmpty(), "a model is reported whose bytes were purged")
        // And nothing new arrives.
        runCatching { relaunched.koin.get<EmbeddingStore>().put("/a.jpg", floatArrayOf(1f, 0f), model) }
        assertTrue(runCatching { relaunched.installModel() }.isFailure)
        assertTrue(files.subpathsAtPath(root).orEmpty().isEmpty(), "written: ${files.subpathsAtPath(root)}")
    }

    @Test
    fun aPurgeThatFailsStillYieldsARefusingStore() = runBlocking {
        val root = temp("photos-inference-purge-fails")
        IosFileDatabase(root).writeRawByteArray("/inference/embeddings.log", "old".encodeToByteArray())

        for (purge in listOf<(String) -> List<String>>({ error("the purge threw") }, { listOf("$root/inference") })) {
            val database = OwnedInferenceStores.openDatabase(root, excludeFromBackup = { "refused by the test" }, purge = purge)

            assertNull(database.readBytes("/inference/embeddings.log"), "the leftover is served")
            assertNull(database.fileSize("/inference/embeddings.log"))
            assertTrue(runCatching { database.writeRawByteArray("/inference/realm.stamp", "x".encodeToByteArray()) }.isFailure)
            assertFalse(files.fileExistsAtPath("$root/inference/realm.stamp"))
        }
    }

    /** What a clear does — an empty write, then a delete — reaches the real directory even then. */
    @Test
    fun whatAPurgeLeftCanStillBeErasedThroughTheRefusingStore() = runBlocking {
        val root = temp("photos-inference-erase-through")
        IosFileDatabase(root).writeRawByteArray("/inference/faces.log", "old".encodeToByteArray())
        val database = OwnedInferenceStores.openDatabase(root, excludeFromBackup = { "refused" }, purge = { listOf("left") })

        database.writeRawByteArray("/inference/faces.log", ByteArray(0))
        assertEquals(0UL, (files.attributesOfItemAtPath("$root/inference/faces.log", error = null)?.get(NSFileSize) as NSNumber).unsignedLongLongValue)
        database.deleteFile("/inference/faces.log")

        assertFalse(files.fileExistsAtPath("$root/inference/faces.log"))
        // ⚠ And an empty write where nothing is makes nothing.
        database.writeRawByteArray("/inference/embeddings.log", ByteArray(0))
        assertFalse(files.fileExistsAtPath("$root/inference/embeddings.log"))
    }

    // ─── a purge that failed, and everything that must still remove what it left ───────────────

    private fun permissions(path: String, mode: Short) {
        assertTrue(files.setAttributes(mapOf<Any?, Any?>(NSFilePosixPermissions to NSNumber(short = mode)), ofItemAtPath = path, error = null))
    }

    /**
     * Photos, started over a directory that holds vectors, templates and a model, on which the
     * backup exclusion fails **and the purge fails too**: the tree is made unwritable, as a full
     * or damaged volume would leave it. The store therefore comes up refusing, reading as empty,
     * with every file still on disk. [body] runs once the tree is removable again — unless
     * [stillUnremovable].
     */
    private suspend fun afterAFailedPurge(stillUnremovable: Boolean = false, body: suspend (PhotosLocalRuntime.Assembly, String) -> Unit) {
        val root = temp("photos-inference-failed-purge")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
            installModel()
        }
        val tree = "$root/inference"
        permissions(tree, 0x140) // r-x: nothing in it can be unlinked
        try {
            val relaunched = photos(root, backupExclusion = { "refused by the test" })
            // The open: the purge runs, and fails.
            relaunched.koin.get<FileDatabase>(named(INFERENCE_FILE_DATABASE))
            assertTrue(files.fileExistsAtPath("$tree/faces.log"), "the fixture: the purge failed and the templates are on disk")
            assertEquals(0, relaunched.templates(), "the fixture: the store cannot see them")
            // ⚠ Everything is built NOW, while the tree is still unremovable: a store's constructor
            // reads its log, and a read retries the purge. Built later, that read would do the
            // removing and no test below would be about the path it names.
            assertEquals(0, relaunched.vectors())
            relaunched.koin.get<InferenceStoreCustody>()
            relaunched.koin.get<ModelStore>().installed()
            relaunched.koin.get<MediaIndexRunner>()
            relaunched.context()
            assertTrue(files.fileExistsAtPath("$tree/faces.log"), "the fixture: a retry while unwritable removes nothing")

            if (!stillUnremovable) permissions(tree, 0x1C0)
            body(relaunched, root)
        } finally {
            if (files.fileExistsAtPath(tree)) permissions(tree, 0x1C0)
        }
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

    private fun assertNothingIsLeft(root: String, by: String) {
        assertTrue(files.subpathsAtPath(root).orEmpty().isEmpty(), "$by left in a backed-up directory: ${files.subpathsAtPath(root)}")
    }

    @Test
    fun aWithdrawalThroughFaceGroupsRemovesWhatAFailedPurgeLeft() = runBlocking {
        afterAFailedPurge { photos, root ->
            photos.withdrawFaceClustering()

            val answer = photos.tenshuClient.inferenceService.faceGroups(10)

            assertIs<TResult.Failure<*>>(answer, "People was served from a directory that is not excluded: $answer")
            assertNothingIsLeft(root, "a withdrawal through faceGroups")
            assertFalse(OwnedInferenceStores.usable(photos.koin))
        }
    }

    /**
     * ⚠⚠ The case where nothing but the withdrawal's own question reaches the directory: with no
     * realm the gate answers before it reads anything. The store's stamp is asked from memory and
     * says "nothing here" over a directory that holds everything.
     */
    @Test
    fun aWithdrawalWithNoRealmStillRemovesWhatAFailedPurgeLeft() = runBlocking {
        afterAFailedPurge { photos, root ->
            photos.withdrawFaceClustering()
            deviceOwner = null

            photos.tenshuClient.inferenceService.faceGroups(10)

            assertNothingIsLeft(root, "a withdrawal with no realm")
        }
    }

    @Test
    fun theRefusingStorePurgesAtOpenAndStopsOnceAPurgeIsClean() = runBlocking {
        val root = temp("photos-inference-purge-once")
        IosFileDatabase(root).writeRawByteArray("/inference/faces.log", "old".encodeToByteArray())
        var purges = 0
        val database = OwnedInferenceStores.openDatabase(
            root,
            excludeFromBackup = { "refused by the test" },
            purge = { purges++; OwnedInferenceStores.purge(it) },
        )

        assertFalse(files.fileExistsAtPath("$root/inference/faces.log"), "nothing was purged until something asked")
        database.readBytes("/inference/realm.stamp")
        database.fileSize("/inference/faces.log")
        database.deleteFile("/inference/faces.log")
        assertEquals(1, purges, "a clean purge was repeated")
    }

    @Test
    fun aWithdrawalThroughARefusedPassRemovesWhatAFailedPurgeLeft() = runBlocking {
        afterAFailedPurge { photos, root ->
            photos.withdrawFaceClustering()

            val runner = photos.koin.get<MediaIndexRunner>()
            assertTrue(runner.start(photos.context(), manual = true))
            eventually("the pass ended") { !runner.isRunning() }

            assertNothingIsLeft(root, "a withdrawal through a refused pass")
            assertFalse(OwnedInferenceStores.usable(photos.koin))
        }
    }

    @Test
    fun theRealmWipeHooksClearsRemoveWhatAFailedPurgeLeft() = runBlocking {
        afterAFailedPurge { photos, root ->
            // What `onLocalStoreWiped` does, in its order.
            photos.koin.get<MediaIndexRunner>().cancelRunningPass()
            photos.koin.get<EmbeddingStore>().clear()
            photos.koin.get<FaceTemplateStore>().clear()

            assertNothingIsLeft(root, "the realm-wipe hook")
        }
    }

    @Test
    fun deprovisionRemovesWhatAFailedPurgeLeft() = runBlocking {
        afterAFailedPurge { photos, root ->
            PhotosLocalRuntime.deprovision(photos)

            assertNothingIsLeft(root, "deprovision")
        }
    }

    @Test
    fun aWithdrawalWhileThePurgeStillFailsDoesNotThrowAndTheStoreStillRefuses() = runBlocking {
        afterAFailedPurge(stillUnremovable = true) { photos, root ->
            photos.withdrawFaceClustering()

            val answer = photos.tenshuClient.inferenceService.faceGroups(10)
            val runner = photos.koin.get<MediaIndexRunner>()
            assertTrue(runner.start(photos.context(), manual = true))
            eventually("the pass ended") { !runner.isRunning() }

            assertIs<TResult.Failure<*>>(answer)
            // ⚠ A CLEAR is another matter, and rightly loud: it is the store's contract that a
            // clear which erased nothing says so, and the realm-wipe hook relies on it.
            assertTrue(runCatching { photos.koin.get<FaceTemplateStore>().clear() }.isFailure)
            assertFalse(OwnedInferenceStores.usable(photos.koin))
            assertTrue(runCatching { photos.installModel() }.isFailure, "the store took a write")
            assertTrue(files.fileExistsAtPath("$root/inference/faces.log"), "the fixture: still unremovable")
        }
    }

    @Test
    fun photosBindsNoBackendYet() = runBlocking {
        // Not admitted here (`ios-on-device-inference.md` §14): the stamp and its checks are in
        // place with nothing to write a vector.
        val photos = photos(temp("photos-inference"))
        assertTrue(photos.koin.get<InferenceBackendRegistry>().available.isEmpty())
        assertNull(photos.koin.getOrNull<IndexingConditions>(named("indexingConditions")))
        assertNull(photos.koin.getOrNull<Set<String>>(named("supportedModelRuntimes")))
    }

    // ─── Photos: the model index dies with the bytes ────────────────────────────────────────────

    @Test
    fun aReinstallWithTheBytesGoneReportsNoModelInstalled() = runBlocking {
        val root = temp("photos-inference")
        // ⚠ ONE delegate across both lives: on a phone it is the Keychain, which an uninstall leaves.
        val keychain = InMemorySecureStorageDelegate()
        photos(root, secureStorage = keychain).apply {
            installModel()
            assertEquals(1, koin.get<ModelStore>().installed().size)
        }

        // The uninstall: Photos' container goes.
        assertTrue(files.removeItemAtPath(root, error = null))

        val reinstalled = photos(root, secureStorage = keychain)
        assertTrue(reinstalled.koin.get<ModelStore>().installed().isEmpty(), "a model is reported whose bytes are gone")
        assertNull(reinstalled.koin.get<ModelStore>().pathOf(VisionModel.PinnedSigLip.modelId, ModelArtifactRole.VISION))
    }

    // ─── Photos: the realm stamp ────────────────────────────────────────────────────────────────

    @Test
    fun aResetPhotosDidNotSeeIsClearedAtTheNextLaunchBeforeAnythingIsUsable() = runBlocking {
        val root = temp("photos-inference")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
            installModel()
        }

        // Control reset the realm while Photos was not running; Photos starts on the new one.
        deviceOwner = "owner-b"
        val relaunched = photos(root, graphOwner = "owner-b")
        assertFalse(OwnedInferenceStores.usable(relaunched.koin), "usable before the stamp was checked")

        assertTrue(PhotosLocalRuntime.checkInferenceStores(relaunched))

        assertEquals(0, relaunched.vectors())
        assertEquals(0, relaunched.templates())
        assertTrue(OwnedInferenceStores.usable(relaunched.koin))
        assertEquals(1, relaunched.koin.get<ModelStore>().installed().size, "the weights are every realm's")
    }

    @Test
    fun theSameRealmKeepsItsVectorsAcrossALaunch() = runBlocking {
        val root = temp("photos-inference")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
        }
        val relaunched = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(relaunched))
        assertEquals(1, relaunched.vectors())
        assertEquals(1, relaunched.templates())
    }

    @Test
    fun aGraphStoppedByTheSharedStoresHostKeepsItsIndexForTheRebuiltGraph() = runBlocking {
        // ios-shared-store-sqlite.md B.16 (228): a fence is not leaving
        // the realm. The stopped graph's teardown must not erase what a relaunch keeps.
        val root = temp("photos-inference")
        val stopped = photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
        }
        PhotosLocalRuntime.teardownGraph(stopped)
        val rebuilt = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(rebuilt))
        assertEquals(1, rebuilt.vectors(), "the vectors survive the rebuild")
        assertEquals(1, rebuilt.templates(), "the face templates survive the rebuild")
    }

    @Test
    fun aStoppedGraphsWriterThatOutlivesFiveSecondsKeepsTheStoresClosedUntilItEnds() = runBlocking {
        // B.16 (246): no deadline. A writer of a stopped graph that is
        // still running after 5 s keeps the next graph's stores unopened and unusable; they open
        // once it ends, and not before.
        val root = temp("photos-inference")
        val writer = kotlinx.coroutines.Job()
        OwnedInferenceStores.holdOpensUntil(writer)
        try {
            val rebuilt = photos(root)
            val check = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                PhotosLocalRuntime.checkInferenceStores(rebuilt)
            }
            val gate = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                rebuilt.koin.get<InferenceStoreGate>().open(fromPass = false)
            }
            delay(5_500)
            assertFalse(check.isCompleted, "the check waits for the writer, past any 5 s deadline")
            assertFalse(gate.isCompleted, "and so does the gate")
            assertFalse(OwnedInferenceStores.usable(rebuilt.koin), "unusable meanwhile, as during any pending check")
            writer.complete()
            assertTrue(check.await(), "opened once the writer ended")
            assertTrue(gate.await())
            assertTrue(OwnedInferenceStores.usable(rebuilt.koin))
        } finally {
            writer.complete()
        }
    }

    @Test
    fun noStoreReadsItsLogWhileAStoppedGraphsWriterRuns() = runBlocking {
        // B.16 (247): resolving the stores, the custody and the gate,
        // as every path does (the Models screen, the runner, queries and passes), builds nothing
        // and reads no log while the writer runs; a call waits, never crashes; once the writer
        // ends the stores are built and serve.
        val root = temp("photos-inference")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
        }
        val writer = kotlinx.coroutines.Job()
        OwnedInferenceStores.holdOpensUntil(writer)
        try {
            val rebuilt = photos(root)
            val embeddings = assertIs<app.tenshu.control.di.AfterPriorWriterEmbeddingStore>(rebuilt.koin.get<EmbeddingStore>())
            val faces = assertIs<app.tenshu.control.di.AfterPriorWriterFaceTemplateStore>(rebuilt.koin.get<FaceTemplateStore>())
            rebuilt.koin.get<InferenceStoreCustody>()
            rebuilt.koin.get<InferenceStoreGate>()
            runCatching { rebuilt.koin.get<org.tenshu.inference.proxy.MediaIndexRunner>() }
            val size = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async { embeddings.size() }
            val faceSize = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async { faces.size() }
            delay(300)
            assertFalse(embeddings.isBuilt, "no embeddings log read while the writer runs")
            assertFalse(faces.isBuilt, "no face log read while the writer runs")
            assertFalse(size.isCompleted, "a call waits")
            assertFalse(faceSize.isCompleted, "a face call waits")
            writer.complete()
            assertEquals(1, size.await(), "built after the writer, over the whole log")
            assertEquals(1, faceSize.await())
            assertTrue(embeddings.isBuilt)
            assertTrue(faces.isBuilt)
        } finally {
            writer.complete()
        }
    }

    @Test
    fun aHungWriterStillHoldsTheStoresAfterALaterWriterEnds() = runBlocking {
        // B.16 (248): chained, never overwritten. Graph 1's writer hangs, graph 2's
        // ends at once: graph 3 still waits for graph 1's.
        val root = temp("photos-inference")
        val first = kotlinx.coroutines.Job()
        OwnedInferenceStores.holdOpensUntil(first)
        OwnedInferenceStores.holdOpensUntil(kotlinx.coroutines.Job().apply { complete() })
        try {
            val third = photos(root)
            val check = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async { PhotosLocalRuntime.checkInferenceStores(third) }
            delay(500)
            assertFalse(check.isCompleted, "the hung first writer still holds the stores")
            first.complete()
            assertTrue(check.await())
        } finally {
            first.complete()
        }
    }

    @Test
    fun aClearThatThrowsLeavesTheStoresUnusableAndTheOldStamp() = runBlocking {
        val root = temp("photos-inference")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
        }
        val stamp = files.contentsAtPath("$root/inference/realm.stamp")
        // The vector log becomes something no write can replace: a directory with a file in it.
        val log = "$root/inference/embeddings.log"
        assertTrue(files.removeItemAtPath(log, error = null))
        assertTrue(files.createDirectoryAtPath("$log/occupied", withIntermediateDirectories = true, attributes = null, error = null))

        deviceOwner = "owner-b"
        val relaunched = photos(root, graphOwner = "owner-b")
        assertFalse(PhotosLocalRuntime.checkInferenceStores(relaunched))

        assertFalse(OwnedInferenceStores.usable(relaunched.koin))
        assertIs<InferenceStoreState.ClearFailed>(relaunched.koin.get<InferenceStoreCustody>().state())
        assertEquals(stamp, files.contentsAtPath("$root/inference/realm.stamp"), "the stamp moved over uncleared content")
        assertEquals(1, relaunched.templates(), "the stamp is the last step, so this clear was not reached")
    }

    @Test
    fun aResetWhilePhotosIsResidentIsClearedBeforeTheNextPhotosLike() = runBlocking {
        val photos = photos(temp("photos-inference"))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.index()

        // The reset lands while Photos is resident: its runtime still holds owner-a.
        deviceOwner = "owner-b"
        val (hits, status) = photos.photosRepository.searchSemantic("a dog", 5)

        assertTrue(hits.isEmpty())
        assertEquals(SemanticSearchStatus.Unavailable(SemanticSearchUnavailable.Reasons.NO_BACKEND), status)
        assertEquals(0, photos.vectors(), "the old realm's vectors outlived the query")
        assertEquals(0, photos.templates())
        // ⚠ And they stay unusable: this runtime was assembled for the realm the device left.
        assertEquals(InferenceStoreState.StaleRuntime, photos.koin.get<InferenceStoreCustody>().state())
    }

    @Test
    fun aResetWhilePhotosIsResidentIsClearedBeforeTheNextFaceGroups() = runBlocking {
        val photos = photos(temp("photos-inference"))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.index()

        deviceOwner = "owner-b"
        val answer = photos.tenshuClient.inferenceService.faceGroups(10)

        assertIs<TResult.Failure<*>>(answer, "People answered over a store it may not read: $answer")
        assertEquals(0, photos.templates(), "the old realm's templates outlived the People request")
        assertEquals(0, photos.vectors())
    }

    @Test
    fun aResetWhilePhotosIsResidentIsCaughtAtTheStartOfTheNextPass() = runBlocking {
        val root = temp("photos-inference")
        val photos = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.index()

        deviceOwner = "owner-b"
        val runner = photos.koin.get<MediaIndexRunner>()
        val context = photos.koin.get<ClientRegistry>().getExecutionContext(PhotosLocalRuntime.PHOTOS_CLIENT_ID)
        assertTrue(runner.start(context, manual = true))
        eventually("the pass ended") { !runner.isRunning() }

        // ⚠ The STAMP is the evidence, not the empty stores: a pass over an empty library
        // reconciles both logs to nothing by itself, with or without the check.
        val stamp = text("$root/inference/realm.stamp")
        assertTrue("owner-b" in stamp.orEmpty(), "the pass started without checking the stamp: $stamp")
        assertEquals(InferenceStoreState.StaleRuntime, photos.koin.get<InferenceStoreCustody>().state())
        assertEquals(0, photos.vectors())
        assertEquals(0, photos.templates())
    }

    @Test
    fun installChecksTheStampAndSoDoesEveryReturnToTheForeground() = runBlocking {
        val root = temp("photos-inference")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
        }
        deviceOwner = "owner-b"
        val photos = photos(root, graphOwner = "owner-b")
        try {
            assertFalse(PhotosLocalRuntime.inferenceStoresUsable(), "nothing is installed yet")

            PhotosLocalRuntime.install(photos)
            eventually("install checked the stamp") { PhotosLocalRuntime.inferenceStoresUsable() }
            assertEquals(0, photos.vectors(), "install made the stores usable over the old realm's vectors")

            // Indexed for owner-b, then a reset while resident and suspended.
            photos.index()
            deviceOwner = "owner-c"
            PhotosLocalRuntime.checkAtForeground()
            eventually("the foreground hook checked the stamp") { photos.vectors() == 0 }
            assertFalse(PhotosLocalRuntime.inferenceStoresUsable())
        } finally {
            PhotosLocalRuntime.deprovision()
        }
    }

    // ─── Photos: a caller that reaches the gate before the launch check ────────────────────────

    /** One vector made before a checkpoint arrived, then Photos is started again and NOT checked. */
    private suspend fun relaunchedOverVectorsAnotherArchiveMade(root: String): PhotosLocalRuntime.Assembly {
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
            installModel()
        }
        return photos(root).also { assertFalse(OwnedInferenceStores.usable(it.koin)) }
    }

    @Test
    fun aQueryThatBeatsTheLaunchCheckNeverReadsVectorsAnotherArchiveMade() = runBlocking {
        val relaunched = relaunchedOverVectorsAnotherArchiveMade(temp("photos-inference"))
        assertEquals(1, relaunched.vectors(), "the fixture: the stale vector is on disk and the realm matches")

        relaunched.photosRepository.searchSemantic("a dog", 5)

        assertEquals(0, relaunched.vectors(), "the query's gate opened on the realm alone")
        assertTrue(OwnedInferenceStores.usable(relaunched.koin))
    }

    @Test
    fun aPassThatBeatsTheLaunchCheckAppliesTheFingerprintFirst() = runBlocking {
        val root = temp("photos-inference")
        val relaunched = relaunchedOverVectorsAnotherArchiveMade(root)
        val before = text("$root/inference/runtime.fingerprint")

        val runner = relaunched.koin.get<MediaIndexRunner>()
        val context = relaunched.koin.get<ClientRegistry>().getExecutionContext(PhotosLocalRuntime.PHOTOS_CLIENT_ID)
        assertTrue(runner.start(context, manual = true))
        eventually("the pass ended") { !runner.isRunning() }

        // ⚠ The sidecar is the evidence: a pass over an empty library empties the log by itself.
        val after = text("$root/inference/runtime.fingerprint")
        assertNotNull(before, "the fixture: the first life wrote a sidecar")
        assertFalse(sha256("WEIGHTS".encodeToByteArray()) in before, "the fixture: the sidecar predates the archive")
        assertTrue(sha256("WEIGHTS".encodeToByteArray()) in after.orEmpty(), "the pass started on the realm alone: $after")
    }

    // ─── Photos: the runtime fingerprint ────────────────────────────────────────────────────────

    @Test
    fun aChangedVisionArchiveClearsTheVectorsAndASecondCheckKeepsThem() = runBlocking {
        val photos = photos(temp("photos-inference"))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.index()

        // A checkpoint arrives: the vectors on disk were made with no archive this store knows.
        photos.installModel()
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        assertEquals(0, photos.vectors())
        assertEquals(1, photos.templates(), "a face template is another model's")

        photos.index("/b.jpg")
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        assertEquals(1, photos.vectors(), "an unchanged fingerprint cleared the store")

        // A replacement with other bytes.
        photos.installModel("OTHER WEIGHTS".encodeToByteArray())
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        assertEquals(0, photos.vectors())
    }

    // ─── Photos: deprovision ────────────────────────────────────────────────────────────────────

    @Test
    fun deprovisionClearsTheVectorsAndTemplatesAndLeavesTheModels() = runBlocking {
        val root = temp("photos-inference")
        val photos = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.index()
        photos.installModel()

        PhotosLocalRuntime.deprovision(photos)

        assertFalse(files.fileExistsAtPath("$root/inference/embeddings.log"))
        assertFalse(files.fileExistsAtPath("$root/inference/faces.log"))
        assertFalse(files.fileExistsAtPath("$root/inference/realm.stamp"))
        assertFalse(OwnedInferenceStores.usable(photos.koin))
        assertTrue(files.fileExistsAtPath("$root/inference/models/inference.models.index.json"))
    }

    @Test
    fun aDeprovisionBeforeAnyGraphBootedClearsTheFilesByPath() = runBlocking {
        val root = temp("photos-inference")
        photos(root).apply {
            assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
            index()
            installModel()
        }

        assertTrue(OwnedInferenceStores.relinquish(koin = null, root = root))

        assertFalse(files.fileExistsAtPath("$root/inference/embeddings.log"))
        assertFalse(files.fileExistsAtPath("$root/inference/faces.log"))
        assertFalse(files.fileExistsAtPath("$root/inference/realm.stamp"))
        assertTrue(files.fileExistsAtPath("$root/inference/models/inference.models.index.json"))
        // And a launch after it starts from nothing, whatever realm it is on.
        val relaunched = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(relaunched))
        assertEquals(0, relaunched.vectors())
    }
}
