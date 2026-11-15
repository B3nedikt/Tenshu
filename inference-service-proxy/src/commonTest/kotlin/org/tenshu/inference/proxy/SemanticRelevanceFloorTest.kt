package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.InferenceService
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.SimilarPhoto
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.photosLike
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `photosLike`'s **relevance floor**.
 *
 * ⚠⚠ The defect this closes was found on the first device ever to run semantic search for real: a
 * two-photo library returned **both photographs for "cat" and both for "xylophone"**, merely
 * re-ordered, each badged *"looks like this"* by Photos. The op ranked everything and cut at
 * `limit`; no score was ever compared against anything.
 *
 * ⚠⚠ Every case here drives the floor through a **scripted** backend rather than
 * `FakeInferenceBackend`, and that is not incidental: the fake's vectors all lie in the positive
 * orthant, so any two of them are highly similar and no floor would ever reject one. A fixture that
 * cannot produce a poor match cannot test the rejection of poor matches.
 */
class SemanticRelevanceFloorTest {

    private val thisDevice = "device-a"
    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    /** A backend whose query vector and calibration the test chooses outright. */
    private class ScriptedBackend(
        private val textVector: FloatArray,
        private val calibration: ScoreCalibration?,
    ) : InferenceBackend {
        override val id: String get() = "scripted"

        override fun supports(capability: InferenceCapability): Boolean =
            capability == InferenceCapability.SEMANTIC_IMAGE_SEARCH

        override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? = null

        override suspend fun embedText(text: String): ImageEmbedding =
            ImageEmbedding(textVector, ModelProvenance.Fake)

        override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag> =
            emptyList()

        override suspend fun recognizeText(bytes: ByteArray): List<TextRegion> = emptyList()

        override fun scoreCalibration(): ScoreCalibration? = calibration
    }

    /**
     * A calibration **shaped like** a real sigmoid-loss model: p = 0.5 at cos ≈ 0.11, so an unrelated
     * pair (cos ≈ 0) lands far below the floor and a true match clears it easily.
     *
     * ⚠⚠ Deliberately NOT the pinned checkpoint's own constants. A first draft used
     * `scale = 10, bias = 0` — which puts p = 0.5 at cos = 0, so two *orthogonal* vectors scored 0.5
     * and sailed over the floor, and the test failed while the code was right. The fixture has to be
     * able to produce the case under test; that is the same trap this class's KDoc names about
     * `FakeInferenceBackend`, one level down.
     *
     * ⚠ Not copied from `VisionModel.PinnedSigLip` either, so these cases keep meaning the same
     * thing the day a different checkpoint ships — that claim is pinned separately, below.
     */
    private val siglipShaped = ScoreCalibration(scale = 100f, bias = -11f)

    private suspend fun storeOf(vararg photos: Pair<String, FloatArray>): EmbeddingStore {
        val store = InMemoryEmbeddingStore()
        photos.forEach { (path, vector) -> store.put(path, vector, ModelProvenance.Fake) }
        return store
    }

    private fun proxy(embeddings: EmbeddingStore, backend: InferenceBackend): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
        },
        implementationFactoryOverride = { id, _ ->
            inferenceServiceProxyImplementationFactory(
                id,
                InferenceBackendRegistry(listOf(backend)),
                embeddings,
                thisDevice,
            )
        },
    ).apply {
        write(search.realmConsent, true)
        write(search.deviceConsent, true, deviceId = thisDevice)
    }

    private fun <T> TestProxy.write(preference: Preference<T>, value: T, deviceId: String? = null) {
        executionContext.saveData(
            if (deviceId == null) {
                Entity.create<RealmSetting>(
                    context = tContext,
                    composite = RealmSetting.create(
                        context = tContext,
                        preferenceId = preference.id,
                        value = preference.adapter.toData(value, tContext),
                    ),
                )
            } else {
                Entity.create<DeviceSetting>(
                    context = tContext,
                    composite = DeviceSetting.create(
                        context = tContext,
                        deviceId = deviceId,
                        preferenceId = preference.id,
                        value = preference.adapter.toData(value, tContext),
                    ),
                )
            },
        )
    }

    private fun service(proxy: TestProxy): InferenceService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(InferenceService.ID)
    }

    private suspend fun hitsFrom(service: InferenceService, text: String = "cat"): List<String> =
        (service.photosLike(text, 30) as TResult.Success).value!!.toList()
            .filterIsInstance<SimilarPhoto>()
            .map { it.filePath.get() }

    /**
     * ⚠⚠ **The device case, as a test.** The query points one way and both photographs point the
     * other; before the floor, both came back.
     */
    @Test
    fun aQueryThatMatchesNothingReturnsNothing() = runTest {
        val store = storeOf(
            "/photos/qr.jpg" to floatArrayOf(0f, 1f),
            "/photos/cat.jpg" to floatArrayOf(0f, -1f),
        )
        val service = service(proxy(store, ScriptedBackend(floatArrayOf(1f, 0f), siglipShaped)))

        assertEquals(emptyList(), hitsFrom(service, "xylophone"))
    }

    @Test
    fun aRealMatchStillComesBack() = runTest {
        val store = storeOf(
            "/photos/cat.jpg" to floatArrayOf(1f, 0f),
            "/photos/qr.jpg" to floatArrayOf(0f, 1f),
        )
        val service = service(proxy(store, ScriptedBackend(floatArrayOf(1f, 0f), siglipShaped)))

        assertEquals(listOf("/photos/cat.jpg"), hitsFrom(service))
    }

    /**
     * ⚠⚠ **The floor is on the model's probability, never on the raw cosine.** This calibration is
     * the degenerate case that proves it: a *perfect* cosine of 1.0 still converts to p ≈ 0.0001, so
     * an implementation that compared the cosine to 0.10 would return the photograph and this fails.
     */
    @Test
    fun theFloorReadsTheProbabilityRatherThanTheCosine() = runTest {
        val store = storeOf("/photos/cat.jpg" to floatArrayOf(1f, 0f))
        val flattening = ScoreCalibration(scale = 1f, bias = -10f)
        val service = service(proxy(store, ScriptedBackend(floatArrayOf(1f, 0f), flattening)))

        assertEquals(emptyList(), hitsFrom(service), "cos = 1.0 but p ≈ 0.0001 under this model")
    }

    /**
     * ⚠⚠ A backend that cannot say how its scores convert **does not filter**, deliberately: the
     * alternative is inventing a raw-cosine threshold, which `inference-service.md` §19.3 forbids
     * and which §3.7.2 shows would be meaningless across two different scales.
     */
    @Test
    fun aBackendWithNoCalibrationRanksWithoutFiltering() = runTest {
        val store = storeOf(
            "/photos/qr.jpg" to floatArrayOf(0f, 1f),
            "/photos/cat.jpg" to floatArrayOf(0f, -1f),
        )
        val service = service(proxy(store, ScriptedBackend(floatArrayOf(1f, 0f), calibration = null)))

        assertEquals(2, hitsFrom(service, "xylophone").size, "today's behaviour, kept on purpose")
    }

    /** ⚠ Survivors are still ranked best-first — the floor removes, it does not reorder. */
    @Test
    fun whatSurvivesTheFloorIsStillRankedBestFirst() = runTest {
        val store = storeOf(
            "/photos/far.jpg" to floatArrayOf(0.71f, 0.71f),
            "/photos/near.jpg" to floatArrayOf(1f, 0f),
        )
        val service = service(proxy(store, ScriptedBackend(floatArrayOf(1f, 0f), siglipShaped)))

        assertEquals(listOf("/photos/near.jpg", "/photos/far.jpg"), hitsFrom(service))
    }

    /** ⚠ The pinned checkpoint's own constants: p = 0.5 at cos ≈ 0.116 (`models.md` §3.7.2). */
    @Test
    fun thePinnedModelCarriesItsOwnCheckpointsConstants() {
        val calibration = org.tenshu.inference.proxy.onnx.VisionModel.PinnedSigLip.calibration
        assertTrue(calibration != null, "the pinned model must state how its cosines convert")

        val boundary = calibration!!.probability(0.116f)
        assertTrue(
            kotlin.math.abs(boundary - 0.5f) < 0.02f,
            "p at the documented boundary was $boundary",
        )
        // And the top score ever observed on a real library, ≈ 0.25, must clear the floor.
        assertTrue(calibration.probability(0.25f) > PhotosLikeImplementation.SEMANTIC_RELEVANCE_FLOOR)
    }

    /** A unit vector whose cosine with the unit query `(1, 0)` is [cosine]. */
    private fun at(cosine: Float): FloatArray =
        floatArrayOf(cosine, kotlin.math.sqrt(1f - cosine * cosine))

    private val pinned get() = org.tenshu.inference.proxy.onnx.VisionModel.PinnedSigLip.calibration!!

    private suspend fun searchAt(cosines: Map<String, Float>, text: String): List<String> {
        val store = storeOf(*cosines.map { (path, cosine) -> path to at(cosine) }.toTypedArray())
        return hitsFrom(service(proxy(store, ScriptedBackend(floatArrayOf(1f, 0f), pinned))), text)
    }

    /**
     * ⚠⚠ **The device case that measured the floor** (`inference-service.md` §35.1): an iPhone's
     * 22 indexed photographs, five of them cats, searched for "cat" on the Core ML towers. These
     * are the cosines it printed. The floor carried over from another checkpoint (cosine 0.0915)
     * returned **nothing**; the five cats must come back, best first, and nothing else.
     */
    @Test
    fun theIphonesFiveCatsAreFoundAndNothingElse() = runTest {
        val cats = listOf(0.08375903f, 0.083610006f, 0.08327898f, 0.08067404f, 0.08015285f)
        // The other 17, to four places: the device printed them, and the host's Core ML towers
        // reproduce the device's scores to 1e-7.
        val others = listOf(
            0.0213f, 0.0168f, 0.0139f, 0.0131f, 0.0093f, 0.0078f, 0.0077f, 0.0077f, 0.0068f,
            0.0054f, 0.0051f, 0.0050f, -0.0010f, -0.0012f, -0.0015f, -0.0114f, -0.0229f,
        )
        val library = cats.mapIndexed { i, c -> "/Cats/$i.jpg" to c } + others.mapIndexed { i, c -> "/Sintra/$i.jpg" to c }

        assertEquals(cats.indices.map { "/Cats/$it.jpg" }, searchAt(library.toMap(), "cat"))
    }

    /**
     * ⚠⚠ **A nonsense query over an unrelated library returns nothing** — the defect the floor
     * exists for (§35). The same iPhone's 22 photographs against "xylophone" on the Core ML towers,
     * and the five best of a 554-photo camera roll on ONNX and on Core ML: none may come back.
     *
     * ⚠⚠ Plus **the instruments that set the floor**: the worst of the five measured over that camera
     * roll, harp on ONNX (0.0567) and accordion on Core ML (0.0558). Without them a floor as low as
     * cosine ~0.048 would pass this test; with them, one at 0.055 fails it.
     */
    @Test
    fun xylophoneOverAnUnrelatedLibraryReturnsNothing() = runTest {
        val iphone = listOf(
            0.0209f, 0.0088f, 0.0073f, 0.0054f, -0.0041f, -0.0043f, -0.0052f, -0.0114f, -0.014f, -0.0164f, -0.0165f,
            -0.0175f, -0.0179f, -0.0191f, -0.0207f, -0.0209f, -0.0213f, -0.0265f, -0.0268f, -0.0291f, -0.0394f, -0.0468f,
        )
        val cameraRollCoreMl = listOf(0.0477f, 0.044f, 0.0414f, 0.0413f, 0.0411f)
        val cameraRollOnnx = listOf(0.0300f, 0.0291f, 0.0286f, 0.0279f, 0.0245f)
        val harpCameraRollOnnx = listOf(0.0567f, 0.0525f, 0.0485f, 0.0485f, 0.0459f)
        val accordionCameraRollCoreMl = listOf(0.0558f, 0.0506f, 0.0506f, 0.0491f, 0.0467f)

        val libraries = listOf(
            "iPhone" to iphone,
            "camera roll, Core ML" to cameraRollCoreMl,
            "camera roll, ONNX" to cameraRollOnnx,
            "camera roll, harp, ONNX" to harpCameraRollOnnx,
            "camera roll, accordion, Core ML" to accordionCameraRollCoreMl,
        )
        for ((name, cosines) in libraries) {
            val library = cosines.mapIndexed { i, c -> "/photos/$i.jpg" to c }.toMap()
            assertEquals(emptyList(), searchAt(library, "xylophone"), name)
        }
    }

    /**
     * The measured cut, **through the op** and under the constants production runs: cosine 0.06 is
     * admitted and 0.0599 is not. ⚠ Not pinned to the `Float`: the floor sits ~0.2 % below the
     * score of 0.06, thousands of ulps, so no platform's `exp` moves it across.
     */
    @Test
    fun theOpCutsAtTheMeasuredCosine() = runTest {
        val library = mapOf("/photos/just-under.jpg" to 0.0599f, "/photos/at-the-cut.jpg" to 0.06f)

        assertEquals(listOf("/photos/at-the-cut.jpg"), searchAt(library, "cat"))
    }
}
