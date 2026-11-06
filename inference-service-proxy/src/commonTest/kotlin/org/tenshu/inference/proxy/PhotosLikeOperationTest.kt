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
import org.tenshu.inference.SemanticSearchUnavailable
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
 * `photosLike` end-to-end over the op wire (IN-4b) — RO → param map → proxy scan → returned
 * composites, and every refusal path.
 *
 * ⭐⭐ **No model, no runtime, no hardware.** The retrieval is real: real vectors from
 * `FakeInferenceBackend`, a real store, a real ranking, and the real op surface. When IN-3b binds
 * ONNX Runtime, only the backend changes.
 *
 * ⚠ The fake embeds text and image bytes with the *same* hash, so a photo whose bytes are
 * `"a sunset"` is what searching for `"a sunset"` finds. That is a property of the fixture and
 * never a claim about a real model — see `FakeInferenceBackend.embedText`.
 */
class PhotosLikeOperationTest {

    private val thisDevice = "device-a"
    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    private fun proxy(
        embeddings: EmbeddingStore,
        registry: InferenceBackendRegistry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
    ): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
        },
        implementationFactoryOverride = { id, _ ->
            inferenceServiceProxyImplementationFactory(id, registry, embeddings, thisDevice, storeGate = storeGate)
        },
    )

    /** `photosLike`'s store gate; `null` is every graph but the one that stamps its stores. */
    private var storeGate: (suspend () -> Boolean)? = null

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

    private fun TestProxy.consent() {
        write(search.realmConsent, true)
        write(search.deviceConsent, true, deviceId = thisDevice)
    }

    /** A store holding one vector per phrase, embedded exactly as the indexer would. */
    private suspend fun storeOf(vararg phrases: String): EmbeddingStore {
        val backend = FakeInferenceBackend()
        val store = InMemoryEmbeddingStore()
        phrases.forEach { phrase ->
            val embedding = backend.embedImage(phrase.encodeToByteArray())!!
            store.put("/photos/$phrase.jpg", embedding.vector, embedding.producedBy)
        }
        return store
    }

    private suspend fun InferenceService.search(text: String, limit: Int = 10) =
        photosLike(text, limit)

    private fun TResult<*>.errorReason(): String {
        val failure = this as? TResult.Failure ?: error("expected a failure, got $this")
        val error = failure.error as? SemanticSearchUnavailable
            ?: error("expected SemanticSearchUnavailable, got ${failure.error}")
        return error.reason.get()
    }

    @Test
    fun theNearestPhotoComesBackFirst() = runTest {
        // ⚠⚠ The match is seeded LAST on purpose. With `"a sunset"` first, an implementation that
        // ranked nothing at all — returning the store in insertion order — would pass this test
        // and every ranking claim in it would be untested.
        val store = storeOf("a receipt", "a dog", "a sunset")
        val service = service(proxy(store).apply { consent() })

        val hits = service.search("a sunset")

        val paths = (hits as TResult.Success).value!!.toList()
            .filterIsInstance<SimilarPhoto>()
            .map { it.filePath.get() }
        assertEquals("/photos/a sunset.jpg", paths.first())
    }

    @Test
    fun theCapDropsTheLeastSimilarRatherThanAnArbitrarySlice() = runTest {
        // Seeded last again: `take(1)` off an unranked store would return `"a receipt"`.
        val store = storeOf("a receipt", "a dog", "a sunset")
        val service = service(proxy(store).apply { consent() })

        val hits = (service.search("a sunset", limit = 1) as TResult.Success).value!!.toList()

        assertEquals(1, hits.size)
        assertEquals("/photos/a sunset.jpg", (hits.first() as SimilarPhoto).filePath.get())
    }

    @Test
    fun theScoreCrossesTheWireAsASignedPerMilleInteger() = runTest {
        val store = storeOf("a receipt", "a sunset")
        val service = service(proxy(store).apply { consent() })

        val best = (service.search("a sunset") as TResult.Success).value!!.toList()
            .filterIsInstance<SimilarPhoto>()
            .first()

        // An exact self-match is cosine 1.0 → 1000‰. ⚠ The point is the *unit*: a float smuggled
        // through the schema's integer field would arrive as 0 or 1 and every ranking would tie.
        assertEquals(1_000L, best.scorePerMille.get())
    }

    @Test
    fun withoutConsentItFailsRatherThanReturningNothing() = runTest {
        val store = storeOf("a sunset")
        val service = service(proxy(store)) // ⚠ no consent written

        // ⚠⚠ The whole reason this op throws. An empty list here would read as "you have no
        // photos of a sunset" — sending the user to look for a photo they do have, and hiding the
        // switch that would make it work.
        assertEquals(SemanticSearchUnavailable.Reasons.NO_CONSENT, service.search("a sunset").errorReason())
    }

    @Test
    fun withNoBackendItSaysSoRatherThanFindingNothing() = runTest {
        val store = storeOf("a sunset")
        val service = service(proxy(store, registry = InferenceBackendRegistry.Empty).apply { consent() })

        assertEquals(SemanticSearchUnavailable.Reasons.NO_BACKEND, service.search("a sunset").errorReason())
    }

    /**
     * ⚠⚠ Neither a model nor consent: the model is the answer. The consent refusal names the switch
     * to turn on, and on a device that can never run a model that switch changes nothing.
     */
    @Test
    fun withNeitherAModelNorConsentTheMissingModelIsTheAnswer() = runTest {
        val service = service(proxy(storeOf("a sunset"), registry = InferenceBackendRegistry.Empty))

        assertEquals(SemanticSearchUnavailable.Reasons.NO_BACKEND, service.search("a sunset").errorReason())
    }

    @Test
    fun anUnindexedLibraryIsItsOwnAnswer() = runTest {
        val service = service(proxy(InMemoryEmbeddingStore()).apply { consent() })

        // ⚠ Distinct from NO_BACKEND: everything is installed and switched on, the pass simply has
        // not run yet. "Wait" and "download a model" are different instructions.
        assertEquals(SemanticSearchUnavailable.Reasons.NOT_INDEXED, service.search("a sunset").errorReason())
    }

    @Test
    fun aBackendWithNoTextSpaceIsNotAMissingBackend() = runTest {
        val store = storeOf("a sunset")
        // Tagging only — present, working, and no joint image–text space. Apple's Vision feature
        // print is exactly this shape.
        val taggingOnly = InferenceBackendRegistry(
            listOf(FakeInferenceBackend(capabilities = setOf(InferenceCapability.CONTENT_TAGGING))),
        )
        val service = service(proxy(store, registry = taggingOnly).apply { consent() })

        // ⚠⚠ Reporting this as NO_BACKEND would send someone to re-download a model they have.
        assertEquals(
            SemanticSearchUnavailable.Reasons.NO_BACKEND,
            service.search("a sunset").errorReason(),
            "the registry has no SEMANTIC_IMAGE_SEARCH backend at all, so it is genuinely absent",
        )
    }

    @Test
    fun vectorsFromAnotherModelAreNeverRankedAgainstTheQuery() = runTest {
        val store = InMemoryEmbeddingStore()
        // A store left behind by an earlier model — the state a version bump produces mid-flight.
        store.put("/photos/old.jpg", floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), org.tenshu.inference.ModelProvenance("siglip", 1))
        val service = service(proxy(store).apply { consent() })

        // ⚠⚠ The silent-corruption case. Cosine across two models' spaces computes fine and means
        // nothing; only the stamp can tell, and only here.
        assertEquals(SemanticSearchUnavailable.Reasons.NOT_INDEXED, service.search("a sunset").errorReason())
    }

    @Test
    fun anEmptyQueryIsACallerMistakeNotAnUnavailableFeature() = runTest {
        val store = storeOf("a sunset")
        val service = service(proxy(store).apply { consent() })

        // ⚠ Deliberately NOT an error: nothing is wrong with the device, the caller asked for
        // nothing. Reporting "search unavailable" here would be as misleading in the other
        // direction.
        val hits = service.search("   ")

        assertTrue(hits is TResult.Success)
        assertEquals(0, (hits as TResult.Success).value!!.toList().size)
    }

    // ─── the store gate (`ios-on-device-inference.md` §3.1) ─────────────────────────────────────

    @Test
    fun aClosedStoreGateRefusesBeforeTheStoreIsRead() = runTest {
        val store = storeOf("a sunset")
        storeGate = { false }
        val service = service(proxy(store).apply { consent() })

        // ⚠ Never an empty success: the vectors are there and may not be read.
        assertEquals(SemanticSearchUnavailable.Reasons.NO_BACKEND, service.search("a sunset").errorReason())
    }

    @Test
    fun theStoreGateRunsBeforeAnythingElseOnEveryQuery() = runTest {
        // ⚠ What a realm-stamp check does on a mismatch: it empties the store, then lets the query
        // through. The query must see the emptied store — so the gate ran first — and be told the
        // device is not indexed, not handed the other realm's photograph.
        val store = storeOf("a sunset")
        var asked = 0
        storeGate = {
            asked++
            store.clear()
            true
        }
        val service = service(proxy(store).apply { consent() })

        assertEquals(SemanticSearchUnavailable.Reasons.NOT_INDEXED, service.search("a sunset").errorReason())
        service.search("a sunset")
        assertEquals(2, asked)
    }

    @Test
    fun theStoreGateIsAskedEvenWithNoBackend() = runTest {
        var asked = 0
        storeGate = { asked++; true }
        val service = service(proxy(storeOf("a sunset"), InferenceBackendRegistry(emptyList())))

        assertEquals(SemanticSearchUnavailable.Reasons.NO_BACKEND, service.search("a sunset").errorReason())
        assertEquals(1, asked)
    }

    /**
     * The gate as the iOS owner binds it: a custody. ⚠ No launch check has run; the query is the
     * first thing to reach it, the realm matches, and the vectors were made with another archive.
     */
    @Test
    fun aQueryNeverSeesVectorsAnotherArchiveMade() = runTest {
        val store = storeOf("a sunset")
        val files = org.tenshu.core.common.proxy.InMemoryFileDatabase()
        fun fingerprint(vision: String) = RuntimeFingerprint(vision, "t1", "CPU_AND_NEURAL_ENGINE", "24A434", 1)
        fun custody(vision: String) = InferenceStoreCustody(
            files = files,
            embeddings = store,
            faces = InMemoryFaceTemplateStore(),
            cancelPass = {},
            currentRealm = { InferenceRealm("owner-a") },
            currentFingerprint = { fingerprint(vision) },
        )
        // A previous process stamped the realm and the fingerprint; then the vector was written.
        val vector = store.entries().single()
        assertTrue(custody("v1").ensureCurrentRealm())
        store.put(vector.first, vector.second, FakeInferenceBackend().embedImage("a sunset".encodeToByteArray())!!.producedBy)
        assertEquals(1, store.size())

        storeGate = custody("v2").let { next -> { next.open(fromPass = false) } }
        val service = service(proxy(store).apply { consent() })

        assertEquals(SemanticSearchUnavailable.Reasons.NOT_INDEXED, service.search("a sunset").errorReason())
        assertEquals(0, store.size())
    }
}
