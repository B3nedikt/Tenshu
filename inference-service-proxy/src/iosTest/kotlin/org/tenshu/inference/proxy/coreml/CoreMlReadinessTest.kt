package org.tenshu.inference.proxy.coreml

import kotlin.test.assertNotEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.AiModelFacts
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.AiStaticGates
import org.tenshu.inference.proxy.FingerprintOutcome
import org.tenshu.inference.proxy.ImageEmbeddingOutcome
import org.tenshu.inference.proxy.PREFLIGHT_REVISION
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PreparedModel
import org.tenshu.inference.proxy.RuntimeFingerprint
import org.tenshu.inference.proxy.catalog.InMemoryModelStore
import org.tenshu.inference.proxy.catalog.InstalledArtifactData
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.onnx.ImageDecoder
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.inference.proxy.onnx.packedVocabulary
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backend as Photos binds it ([coreMlBackendOver]) against the real [AiReadiness], and what
 * [coreMlModelFacts] reads off a store and a cache (`ios-on-device-inference.md` §3.2, *Readiness*).
 * A fake [CoreMlSessionPort]: no model is loaded.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreMlReadinessTest {

    private val model = VisionModel.PinnedSigLip

    /**
     * ⚠⚠ The key a catalog entry would have once installed is the key the install record gives
     * after a commit of exactly that entry: what a Models screen asks of the verdict's file before
     * it offers 743 MB (§14.5 item 0). A key computed otherwise would offer a download the
     * install then meets answered, or withhold one it would not.
     */
    @Test
    fun theKeyOfAnEntryAsItWouldBeInstalledIsTheKeyOfItsInstall() {
        val store = InMemoryModelStore { it.decodeToString() }
        val entry = org.tenshu.inference.proxy.catalog.ModelEntryData(
            modelId = model.modelId, name = "SigLIP", modelVersion = 1, capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
            labelSetVersion = 0, runtime = "coreml", artifactVersion = 1,
            artifacts = listOf(
                org.tenshu.inference.proxy.catalog.ModelArtifactData(ModelArtifactRole.VISION, "v", "vision-digest", 12),
                org.tenshu.inference.proxy.catalog.ModelArtifactData(ModelArtifactRole.TEXT, "t", "text-digest", 11),
                org.tenshu.inference.proxy.catalog.ModelArtifactData(ModelArtifactRole.VOCABULARY, "w", "vocabulary-digest", 17),
            ),
            licence = "Apache-2.0",
        )
        assertNotNull(store.add(entry, entry.artifacts.associate { it.role to it.sha256.encodeToByteArray() }))

        assertEquals(iosPreflightKey(store.installed(), appBuild = "1.0 (1)", osBuild = "23A1"), iosPreflightKeyOf(entry, appBuild = "1.0 (1)", osBuild = "23A1"))
        assertNotEquals(iosPreflightKey(store.installed(), appBuild = "1.0 (1)", osBuild = "23A1"), iosPreflightKeyOf(entry, appBuild = "1.0 (1)", osBuild = "23B2"))
    }

    private class FakeSession(
        override val compiledPath: String,
        private val dimensions: Int,
        private val duringPredict: (String) -> Unit,
    ) : CoreMlSessionPort {
        var predictions = 0
        var closed = false

        override fun predict(floats: FloatArray): FloatArray = answer()
        override fun predict(ids: IntArray): FloatArray = answer()

        private fun answer(): FloatArray {
            check(!closed) { "predicted on a closed session" }
            predictions++
            duringPredict(compiledPath)
            return FloatArray(dimensions) { 0.5f + it }
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * ⚠ Opened from two `Dispatchers.Default` coroutines at once (an image and a query): an
     * `ArrayList` can lose one of the two adds, and `noTowerIsOpenedBeforeTheLoadIsAllowed` would
     * read 1 of 2. A compare-and-set list, read as a snapshot.
     */
    private val sessions = Opened()

    private class Opened : AbstractList<FakeSession>() {
        private val all = kotlin.concurrent.AtomicReference<List<FakeSession>>(emptyList())
        fun add(session: FakeSession) {
            while (true) {
                val was: List<FakeSession> = all.value
                if (all.compareAndSet(was, was + session)) return
            }
        }
        override val size: Int get() = all.value.size
        override fun get(index: Int): FakeSession = all.value[index]
    }

    /** Paints every image one colour; the bytes do not matter. */
    private object AnyImage : ImageDecoder {
        override fun decode(bytes: ByteArray, size: Int, centreCrop: Boolean): IntArray = IntArray(size * size)
        override fun decodeInto(bytes: ByteArray, size: Int, centreCrop: Boolean, target: IntArray): Boolean = true
    }

    private val modelA = PreparedModel("/coreml/a/vision.mlmodelc", "/coreml/a/text.mlmodelc", "vocabulary-a")
    private val modelB = PreparedModel("/coreml/b/vision.mlmodelc", "/coreml/b/text.mlmodelc", "vocabulary-b")

    private fun fingerprint(vision: String) = PreflightKey(
        runtime = RuntimeFingerprint(vision, "text", "cpuAndNeuralEngine", "os-1", 1),
        vocabularySha256 = "vocabulary",
        appBuild = "1.0 (1)",
        preflightRevision = 1,
    )

    private var installed = true
    private var prepared: PreparedModel? = modelA
    private var current: PreflightKey = fingerprint("vision-a")
    private var storesUsable = true

    private val disk = InMemoryFileDatabase()

    private lateinit var backend: CoreMlInferenceBackend

    private val readiness = AiReadiness(
        files = { disk },
        facts = { AiModelFacts(installed, prepared) },
        currentKey = { current },
        storesUsable = { storesUsable },
        applyFingerprint = { FingerprintOutcome.Unchanged },
        cancelPass = {},
        closeSessions = { backend.closeSessions() },
    )

    private fun bound(): CoreMlInferenceBackend = coreMlBackendOver(
        readiness = readiness,
        readVocabulary = {
            packedVocabulary(listOf("<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f, "▁" to -3f, "▁a" to -1f, "▁dog" to -1f))
        },
        decoder = AnyImage,
        open = { path, _ -> FakeSession(path, model.dimensions) { duringPredict(it) }.also { sessions.add(it) } },
    ).also { backend = it }

    /** Runs inside every prediction, on the thread that predicts. A test makes one block. */
    private var duringPredict: (String) -> Unit = {}

    private val photo: ByteArray = "photo".encodeToByteArray()

    private fun claims(): Boolean = backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH)

    @Test
    fun theBackendClaimsNothingAndRunsNothingInAnyStateButReady() = runBlocking {
        bound()
        val claimed = LinkedHashMap<AiReadinessState, Boolean>()
        suspend fun note() {
            val state: AiReadinessState = readiness.current()
            claimed[state] = claims()
            if (state != AiReadinessState.Ready) {
                assertEquals(ImageEmbeddingOutcome.Failed, backend.embedImageOutcome(photo), "$state embedded a photo")
                assertNull(backend.embedText("a dog"), "$state embedded a query")
            }
        }

        note()
        installed = false
        prepared = null
        readiness.fingerprintChecked()
        note()
        installed = true
        readiness.fingerprintChecked()
        note()
        prepared = modelA
        readiness.markerWritten()
        note()
        readiness.recordVerdict(passed = false, probed = current)
        note()
        assertTrue(sessions.isEmpty(), "a model was opened while nothing was ready: ${sessions.map { it.compiledPath }}")

        readiness.recordVerdict(passed = true, probed = current)
        note()
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.embedImageOutcome(photo))
        assertNotNull(backend.embedText("a dog"))

        assertEquals(
            mapOf(
                AiReadinessState.NoModel to false,
                AiReadinessState.Preparing to false,
                AiReadinessState.AwaitingVerdict to false,
                AiReadinessState.Unsuitable to false,
                AiReadinessState.Ready to true,
            ),
            claimed,
        )
        assertEquals(listOf(modelA.visionPath, modelA.textPath), sessions.map { it.compiledPath })
    }

    @Test
    fun leavingReadyClosesBothSessionsAndTheReplacementIsOpenedOnlyAfterItsOwnVerdict() = runBlocking {
        bound()
        readiness.recordVerdict(passed = true, probed = current)
        backend.embedImageOutcome(photo)
        backend.embedText("a dog")
        val old: List<FakeSession> = sessions.toList()
        assertEquals(2, old.size)

        // The store commits a replacement.
        readiness.storeChanging {
            prepared = null
            current = fingerprint("vision-b")
        }
        assertTrue(old.all { it.closed }, "the old model is still loaded")
        assertFalse(claims())

        // Prepared, not yet probed: the old compiled model is still on disk, and must not be used.
        prepared = modelB
        readiness.markerWritten()
        assertEquals(ImageEmbeddingOutcome.Failed, backend.embedImageOutcome(photo))
        assertNull(backend.embedText("a dog"))
        assertEquals(2, sessions.size, "a session was opened before the replacement's verdict")

        readiness.recordVerdict(passed = true, probed = current)
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.embedImageOutcome(photo))
        assertNotNull(backend.embedText("a dog"))

        assertEquals(listOf(modelB.visionPath, modelB.textPath), sessions.drop(2).map { it.compiledPath })
        assertEquals(listOf(1, 1), old.map { it.predictions }, "a prediction was served from the old model")
    }

    @Test
    fun whileTheStoresAreUnusableNothingIsClaimedAndNoSessionIsKept() = runBlocking {
        bound()
        readiness.recordVerdict(passed = true, probed = current)
        backend.embedImageOutcome(photo)
        backend.embedText("a dog")

        // ⚠ No writer runs: the custody closes at a gate, on its own schedule, and readiness is
        // still `Ready`. The backend has to notice by itself, on both towers.
        storesUsable = false

        assertFalse(claims(), "a backend was offered over stores the custody had closed")
        assertEquals(ImageEmbeddingOutcome.Failed, backend.embedImageOutcome(photo))
        assertNull(backend.embedText("a dog"))
        assertEquals(listOf(true, true), sessions.map { it.closed }, "a model stayed loaded over closed stores")
    }

    /** A torn-down runtime's graph stays reachable. Its backend unloads, and never loads again. */
    @Test
    fun aRetiredReadinessClosesBothTowersAndTheBackendNeverReopens() = runBlocking {
        bound()
        readiness.recordVerdict(passed = true, probed = current)
        backend.embedImageOutcome(photo)
        backend.embedText("a dog")
        assertEquals(listOf(false, false), sessions.map { it.closed })

        readiness.retire()
        readiness.unloadRetired()

        assertEquals(listOf(true, true), sessions.map { it.closed }, "a torn-down runtime kept a model loaded")
        assertFalse(claims())
        // Everything that could make it ready again, on the old graph.
        readiness.fingerprintChecked()
        readiness.recordVerdict(passed = true, probed = current)
        assertFalse(claims())
        assertEquals(ImageEmbeddingOutcome.Failed, backend.embedImageOutcome(photo))
        assertNull(backend.embedText("a dog"))
        assertEquals(2, sessions.size, "the old backend opened a model after its runtime was torn down")
    }

    /**
     * ⚠⚠ A prediction is a blocking call nothing can interrupt, and a teardown does not wait for
     * it. So: `retire` returns while it runs; its vector, when it comes, never leaves the backend
     * (the stores it would be written to are given up by then); and the tower is closed once the
     * prediction lets go of it.
     */
    private fun aPredictionThatOutlastsATeardown(blockedPath: String, call: suspend () -> Any?, thrownAway: Any?) = runBlocking {
        bound()
        readiness.recordVerdict(passed = true, probed = current)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        duringPredict = { path ->
            if (path == blockedPath) {
                entered.complete(Unit)
                runBlocking { release.await() }
            }
        }
        val inFlight = async(Dispatchers.Default) { call() }
        // ⚠ Whatever fails below, the prediction is let go: this scope waits for it.
        val unloading = try {
            entered.await()
            val tower: FakeSession = sessions.single { it.compiledPath == blockedPath }

            readiness.retire()

            assertFalse(claims(), "a retired backend is still offered while its prediction runs")
            launch(Dispatchers.Default) { readiness.unloadRetired() }.also {
                delay(300)
                assertFalse(tower.closed, "a tower was closed under its own prediction")
                assertFalse(it.isCompleted)
            }
        } finally {
            release.complete(Unit)
        }

        assertEquals(thrownAway, inFlight.await(), "the vector of a torn-down runtime left the backend")
        unloading.join()
        assertTrue(sessions.all { it.closed }, "a tower stayed loaded after its prediction returned")
    }

    @Test
    fun anImagePredictionThatOutlastsATeardownIsThrownAwayAndItsTowerClosedAfterwards() =
        aPredictionThatOutlastsATeardown(modelA.visionPath, { backend.embedImageOutcome(photo) }, ImageEmbeddingOutcome.Failed)

    @Test
    fun aQueryThatOutlastsATeardownIsAnsweredWithNothingAndItsTowerClosedAfterwards() =
        aPredictionThatOutlastsATeardown(modelA.textPath, { backend.embedText("a dog") }, null)

    /** Opening a tower is where memory is committed: both towers wait for `beforeLoad` first. */
    @Test
    fun noTowerIsOpenedBeforeTheLoadIsAllowed() = runBlocking {
        val allowed = CompletableDeferred<Unit>()
        val gated: CoreMlInferenceBackend = coreMlBackendOver(
            readiness = readiness,
            readVocabulary = {
                packedVocabulary(listOf("<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f, "▁" to -3f, "▁a" to -1f, "▁dog" to -1f))
            },
            decoder = AnyImage,
            open = { path, _ -> FakeSession(path, model.dimensions) {}.also { sessions.add(it) } },
            beforeLoad = { allowed.await() },
        ).also { backend = it }
        readiness.recordVerdict(passed = true, probed = current)
        val image = async(Dispatchers.Default) { gated.embedImageOutcome(photo) }
        val query = async(Dispatchers.Default) { gated.embedText("a dog") }
        try {
            delay(300)
            assertTrue(sessions.isEmpty(), "a tower was opened before the load was allowed: ${sessions.map { it.compiledPath }}")
            assertFalse(image.isCompleted || query.isCompleted, "a call was refused instead of waiting")
        } finally {
            allowed.complete(Unit)
        }
        assertIs<ImageEmbeddingOutcome.Embedded>(image.await())
        assertNotNull(query.await())
        assertEquals(2, sessions.size)
    }

    // ─── what the store and the cache hold ──────────────────────────────────────────────────────

    private val root: String = NSTemporaryDirectory() + "coreml-readiness-" + Random.nextLong().toString(16)
    private val files = IosFileDatabase(root)
    private val cache = CoreMlModelCache(files, root)
    private var record: InstalledModelData? = null

    /** A store whose one record is [record]: the paths are the test's, as a real store's are its own. */
    private val store: ModelStore = object : ModelStore by InMemoryModelStore({ it.decodeToString() }) {
        override fun installed(): List<InstalledModelData> = listOfNotNull(record)
    }

    private val vocabularyPath = "inference/models/vocabulary.model"
    private val vocabularyBytes: ByteArray = "the packed vocabulary".encodeToByteArray()

    private val visionDigest = "a".repeat(64)
    private val textDigest = "b".repeat(64)

    /**
     * Records [roles] as installed. The vocabulary's bytes are written where the record says,
     * unless [vocabularyOnDisk] says otherwise: `null` is a record whose file is gone.
     */
    private fun install(vararg roles: Pair<String, String>, vocabularyOnDisk: ByteArray? = vocabularyBytes) {
        record = InstalledModelData(
            modelId = model.modelId,
            modelVersion = 1,
            artifacts = roles.map { (role, digest) ->
                if (role == ModelArtifactRole.VOCABULARY) {
                    InstalledArtifactData(role, vocabularyPath, vocabularyBytes.size.toLong(), digest)
                } else {
                    InstalledArtifactData(role, "inference/models/$role.model", 1L, digest)
                }
            },
            labelSetVersion = 1,
            licence = "Apache-2.0",
        )
        if (roles.any { it.first == ModelArtifactRole.VOCABULARY } && vocabularyOnDisk != null) {
            files.writeRawByteArray(vocabularyPath, vocabularyOnDisk)
        }
    }

    /** A ready marker and a compiled directory, as [CoreMlModelCache.prepare] leaves them. */
    private fun markReady(role: String, digest: String, markerDigest: String = digest) {
        val directory = "$root/coreml/${digest.take(16)}"
        NSFileManager.defaultManager.createDirectoryAtPath("$directory/$role.mlmodelc", true, null, null)
        files.writeRawByteArray("coreml/${digest.take(16)}/ready", "digest=$markerDigest\nosBuild=os-1\n".encodeToByteArray())
    }

    private val allThree = arrayOf(
        ModelArtifactRole.VISION to visionDigest,
        ModelArtifactRole.TEXT to textDigest,
        ModelArtifactRole.VOCABULARY to "c".repeat(64),
    )

    @Test
    fun withNoInstallRecordNothingIsInstalled() {
        assertEquals(AiModelFacts(installed = false, prepared = null), coreMlModelFacts(store, cache, files))
    }

    @Test
    fun anInstalledArchiveWithNoReadyMarkerIsNotPrepared() {
        install(*allThree)

        assertEquals(AiModelFacts(installed = true, prepared = null), coreMlModelFacts(store, cache, files))
    }

    @Test
    fun oneTowerAloneIsNotPrepared() {
        install(*allThree)
        markReady(ModelArtifactRole.VISION, visionDigest)

        assertNull(coreMlModelFacts(store, cache, files).prepared, "search was offered with no text tower")
    }

    @Test
    fun aMarkerForAnotherArchiveIsNotThisOnes() {
        install(*allThree)
        markReady(ModelArtifactRole.VISION, visionDigest)
        markReady(ModelArtifactRole.TEXT, textDigest, markerDigest = "d".repeat(64))

        assertNull(coreMlModelFacts(store, cache, files).prepared)
    }

    @Test
    fun bothTowersWithoutAVocabularyAreNotPrepared() {
        install(ModelArtifactRole.VISION to visionDigest, ModelArtifactRole.TEXT to textDigest)
        markReady(ModelArtifactRole.VISION, visionDigest)
        markReady(ModelArtifactRole.TEXT, textDigest)

        assertNull(coreMlModelFacts(store, cache, files).prepared, "search was offered with no vocabulary")
    }

    @Test
    fun bothMarkersAndTheVocabularyArePrepared() {
        install(*allThree)
        markReady(ModelArtifactRole.VISION, visionDigest)
        markReady(ModelArtifactRole.TEXT, textDigest)

        val facts = coreMlModelFacts(store, cache, files)

        assertTrue(facts.installed)
        assertEquals(
            PreparedModel(
                visionPath = "$root/coreml/${visionDigest.take(16)}/vision.mlmodelc",
                textPath = "$root/coreml/${textDigest.take(16)}/text.mlmodelc",
                vocabularyPath = vocabularyPath,
            ),
            facts.prepared,
        )
    }

    /**
     * ⚠⚠ The record names a vocabulary and the disk does not hold it. `bothTowersWithoutAVocabulary…`
     * above never saw this: its record had no vocabulary at all, and the path was all that was read.
     */
    @Test
    fun aVocabularyTheRecordNamesAndTheDiskDoesNotHoldIsNotPrepared() {
        install(*allThree, vocabularyOnDisk = null)
        markReady(ModelArtifactRole.VISION, visionDigest)
        markReady(ModelArtifactRole.TEXT, textDigest)

        assertEquals(
            AiModelFacts(installed = true, prepared = null),
            coreMlModelFacts(store, cache, files),
            "a vocabulary that is not there would make every query fail on a phone that claims search",
        )
    }

    /**
     * ⚠⚠ A file that cannot be read is not a file that is not there. A
     * stat or a marker read that **throws** must reach readiness as *unreadable*, where nothing
     * is served and nothing is written, and not come back as `Preparing`.
     */
    @Test
    fun aVocabularyStatOrAMarkerReadThatThrowsIsNotAModelThatIsNotPrepared() {
        install(*allThree)
        markReady(ModelArtifactRole.VISION, visionDigest)
        markReady(ModelArtifactRole.TEXT, textDigest)
        assertNotNull(coreMlModelFacts(store, cache, files).prepared)

        val statFails = object : FileDatabase by files {
            override fun fileSize(filePath: String): Long? = error("I/O error")
        }
        assertFailsWith<IllegalStateException>("a stat that failed read as a missing vocabulary") {
            coreMlModelFacts(store, cache, statFails)
        }
        val markerFails = object : FileDatabase by files {
            override fun readBytes(filePath: String) = if (filePath.endsWith("/ready")) error("I/O error") else files.readBytes(filePath)
        }
        assertFailsWith<CoreMlPrepareException>("a marker that could not be read read as not prepared") {
            coreMlModelFacts(store, CoreMlModelCache(markerFails, root), files)
        }
        // ⚠⚠ And a marker that is THERE and gives no bytes (a phone before its first unlock):
        // `readBytes` answers null for it as for no file, and it read as "not prepared".
        val markerLocked = object : FileDatabase by files {
            override fun readBytes(filePath: String) = if (filePath.endsWith("/ready")) null else files.readBytes(filePath)
        }
        assertFailsWith<CoreMlPrepareException>("a marker that gave no bytes read as not prepared") {
            coreMlModelFacts(store, CoreMlModelCache(markerLocked, root), files)
        }
    }

    @Test
    fun aVocabularyThatIsNotTheRecordedSizeIsNotPrepared() {
        install(*allThree, vocabularyOnDisk = vocabularyBytes.copyOf(vocabularyBytes.size - 1))
        markReady(ModelArtifactRole.VISION, visionDigest)
        markReady(ModelArtifactRole.TEXT, textDigest)

        assertNull(coreMlModelFacts(store, cache, files).prepared, "a truncated vocabulary was offered")
    }

    @Test
    fun anArchiveWhoseDigestIsNotASha256IsNotPreparedAndDoesNotThrow() {
        install(ModelArtifactRole.VISION to "WEIGHTS", ModelArtifactRole.TEXT to textDigest, ModelArtifactRole.VOCABULARY to "v")
        markReady(ModelArtifactRole.TEXT, textDigest)

        assertEquals(AiModelFacts(installed = true, prepared = null), coreMlModelFacts(store, cache, files))
    }

    // ─── the two keys ───────────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ Two keys over one install record, with different inputs. The custody's fingerprint is
     * what made the stored vectors; the preflight's key is everything the preflight checked.
     */
    @Test
    fun aVocabularyChangeMovesThePreflightKeyAndNotTheVectorsFingerprint() {
        install(*allThree)
        val before: List<InstalledModelData> = store.installed()
        val keyBefore: PreflightKey = iosPreflightKey(before, appBuild = "1.0 (1)", osBuild = "os-1")
        assertEquals(iosRuntimeFingerprint(before, osBuild = "os-1"), keyBefore.runtime)
        assertEquals("c".repeat(64), keyBefore.vocabularySha256)
        assertEquals("1.0 (1)", keyBefore.appBuild)
        assertEquals(PREFLIGHT_REVISION, keyBefore.preflightRevision)

        install(
            ModelArtifactRole.VISION to visionDigest,
            ModelArtifactRole.TEXT to textDigest,
            ModelArtifactRole.VOCABULARY to "e".repeat(64),
        )
        val after: List<InstalledModelData> = store.installed()
        val keyAfter: PreflightKey = iosPreflightKey(after, appBuild = "1.0 (1)", osBuild = "os-1")

        assertEquals(keyBefore.runtime, keyAfter.runtime, "a vocabulary change would clear the vectors")
        assertEquals(iosRuntimeFingerprint(before, osBuild = "os-1"), iosRuntimeFingerprint(after, osBuild = "os-1"))
        assertTrue(keyBefore != keyAfter, "a vocabulary change keeps the old vocabulary's pass")
        assertTrue(keyAfter != iosPreflightKey(after, appBuild = "1.1 (2)", osBuild = "os-1"), "an app update keeps the pass")
    }

    @Test
    fun thisProcessHasAnAppBuildOfSomeKind() {
        assertTrue(iosPreflightKey(emptyList()).appBuild.isNotBlank())
    }

    // ─── this phone ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun thisDeviceReportsAVersionAndItsMemory() {
        val device: AiStaticGates.Device = iosAiDevice()

        assertTrue(device.osMajorVersion >= 16, "not an iOS version: ${device.osMajorVersion}")
        assertTrue(device.physicalMemoryBytes > 1L shl 30, "not a memory size: ${device.physicalMemoryBytes}")
    }
}
