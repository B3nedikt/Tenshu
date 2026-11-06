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

    /**
     * ⚠⚠ The carried-over cut, **through the op** and under the constants production runs: the
     * smallest cosine the floor admitted before the constants were corrected is still admitted,
     * and the `Float` below it still is not (`SemanticFloorCarryOverTest` has the sweep).
     *
     * ⚠ The query is a unit axis, so each photograph's cosine is exactly its first component.
     */
    @Test
    fun theOpCutsAtTheCarriedOverCosine() = runTest {
        val admitted = Float.fromBits(SemanticFloorCarryOverTest.CARRIED_OVER_COSINE_BITS)
        val rejected = Float.fromBits(SemanticFloorCarryOverTest.CARRIED_OVER_COSINE_BITS - 1)
        val store = storeOf(
            "/photos/just-under.jpg" to floatArrayOf(rejected, 0f),
            "/photos/at-the-cut.jpg" to floatArrayOf(admitted, 0f),
        )
        val pinned = org.tenshu.inference.proxy.onnx.VisionModel.PinnedSigLip.calibration!!
        val service = service(proxy(store, ScriptedBackend(floatArrayOf(1f, 0f), pinned)))

        assertEquals(listOf("/photos/at-the-cut.jpg"), hitsFrom(service))
    }
}
