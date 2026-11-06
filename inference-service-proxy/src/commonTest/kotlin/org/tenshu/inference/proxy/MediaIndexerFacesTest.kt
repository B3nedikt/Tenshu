package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.entity.Entity
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Faces as the **third half** of the index pass.
 *
 * ⚠⚠ §32's rule is the thing under test: *"each half stands alone: its own backend, its own consent,
 * its own freshness."* Halves that share tagging's three are invisible against the fake — it
 * supports everything — and fatal on a device.
 */
class MediaIndexerFacesTest {

    private val thisDevice = "device-a"
    private val faceCapability = InferenceCapability.FACE_CLUSTERING

    private fun proxy(): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
        },
    )

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

    private fun TestProxy.consentTo(capability: InferenceCapability) {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    private fun indexer(
        store: FaceTemplateStore?,
        embeddings: EmbeddingStore? = null,
        capabilities: Set<InferenceCapability> = setOf(InferenceCapability.FACE_CLUSTERING),
    ) = MediaIndexer(
        registry = InferenceBackendRegistry(listOf(FakeInferenceBackend(capabilities = capabilities))),
        deviceId = thisDevice,
        embeddings = embeddings,
        faces = store,
        conditions = IndexingConditions { true },
    )

    /** ⚠ The fake reads face identities out of the bytes — see `FakeInferenceBackend.detectFaces`. */
    private fun media(vararg photos: Pair<String, String>) =
        photos.map { (path, faces) -> IndexableMedia(path) { faces.encodeToByteArray() } }

    /**
     * §12.15: a detection the embedder calls not a human face (a cat, a doll) is left out — and it is
     * NOT the embedder failing. ⚠⚠ Handled like `null`, it would latch the face half off for the rest
     * of the pass and leave this photograph unrecorded for ever.
     */
    @Test
    fun aNonHumanFaceIsLeftOut_andNeitherLatchesThePassNorLeavesThePhotoUndone() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }

        val result = indexer(store).index(
            proxy.executionContext,
            media("/a.jpg" to "anna,~cat", "/b.jpg" to "bob"),
            capability = faceCapability,
        )

        assertEquals(3, result.facesFound)
        assertEquals(1, result.facesNotHuman)
        assertEquals(2, result.facesEmbedded, "anna and bob — the pass went on past the cat")
        assertEquals(1, store.facesOf("/a.jpg").size, "anna kept, the cat left out")
        assertEquals(0, result.embeddingsFailed, "not a failure")
        assertEquals(2, result.photosSearchedForFaces)
    }

    @Test
    fun aPassEmbedsEveryFaceItFinds() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }

        val result = indexer(store).index(
            proxy.executionContext,
            media("/a.jpg" to "anna", "/b.jpg" to "anna,bob"),
            capability = faceCapability,
        )

        assertEquals(3, result.facesFound)
        assertEquals(3, result.facesEmbedded)
        assertEquals(2, result.photosSearchedForFaces)
        assertEquals(2, store.facesOf("/b.jpg").size)
    }

    /**
     * ⚠⚠ The majority case in a real library, and the one that must be recorded: a photograph with
     * no faces is *looked at*, so no later pass opens it again.
     */
    @Test
    fun aPhotographWithNoFacesIsRecordedAsVisited() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }

        val result = indexer(store).index(
            proxy.executionContext,
            media("/landscape.jpg" to ""),
            capability = faceCapability,
        )

        assertTrue(store.visited("/landscape.jpg"))
        assertEquals(0, result.facesFound)
        assertEquals(1, result.photosSearchedForFaces, "it was looked at, which is the point")
    }

    /** ⚠ And a second pass over it reads no bytes at all. */
    @Test
    fun aSecondPassOverAFacelessLibraryReadsNothing() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        val reads = mutableListOf<String>()
        val once = listOf(
            IndexableMedia("/landscape.jpg") { reads += "/landscape.jpg"; "".encodeToByteArray() },
        )

        indexer(store).index(proxy.executionContext, once, capability = faceCapability)
        val afterFirst = reads.size
        indexer(store).index(proxy.executionContext, once, capability = faceCapability)

        assertEquals(1, afterFirst)
        assertEquals(1, reads.size, "the second pass must not re-read a photograph it has seen")
    }

    /**
     * ⚠⚠ **Its own consent.** A user who switched on search and not faces must get vectors and no
     * templates — the §32.2 defect, in the new half.
     */
    @Test
    fun searchConsentAloneWritesNoTemplates() = runTest {
        val store = InMemoryFaceTemplateStore()
        val embeddings = InMemoryEmbeddingStore()
        val proxy = proxy().apply { consentTo(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }

        val result = indexer(
            store,
            embeddings,
            capabilities = setOf(
                InferenceCapability.FACE_CLUSTERING,
                InferenceCapability.SEMANTIC_IMAGE_SEARCH,
            ),
        ).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        assertEquals(0, result.facesFound, "faces were not consented to")
        assertEquals(0, store.size())
        assertFalse(store.visited("/a.jpg"), "and nothing was recorded about it")
        assertEquals(1, result.embedded, "while the half that WAS consented to ran")
    }

    /** ⚠ And the reverse: faces on, search off. */
    @Test
    fun faceConsentAloneWritesNoVectors() = runTest {
        val store = InMemoryFaceTemplateStore()
        val embeddings = InMemoryEmbeddingStore()
        val proxy = proxy().apply { consentTo(faceCapability) }

        val result = indexer(
            store,
            embeddings,
            capabilities = setOf(
                InferenceCapability.FACE_CLUSTERING,
                InferenceCapability.SEMANTIC_IMAGE_SEARCH,
            ),
        ).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        assertEquals(1, result.facesEmbedded)
        assertEquals(0, result.embedded)
        assertEquals(0, embeddings.size())
    }

    /**
     * ⚠⚠ A backend that cannot detect must not make the pass a no-op for the halves that can. §32.1
     * is the scar: the pass asked for "the" backend and skipped every photograph on every device.
     */
    @Test
    fun aBackendWithoutFacesStillRunsTheOtherHalves() = runTest {
        val store = InMemoryFaceTemplateStore()
        val embeddings = InMemoryEmbeddingStore()
        val proxy = proxy().apply {
            consentTo(faceCapability)
            consentTo(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        }

        val result = indexer(
            store,
            embeddings,
            capabilities = setOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        ).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        assertEquals(1, result.embedded, "search has a backend and consent, so it runs")
        assertEquals(0, result.facesFound, "faces have consent and no backend, so they do not")
        assertEquals(0, store.size())
    }

    /** ⚠ With no sink wired at all, the pass is exactly the pass without faces. */
    @Test
    fun withNoFaceStoreThePassIsUnchanged() = runTest {
        val embeddings = InMemoryEmbeddingStore()
        val proxy = proxy().apply {
            consentTo(faceCapability)
            consentTo(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        }

        val result = indexer(
            store = null,
            embeddings = embeddings,
            capabilities = setOf(
                InferenceCapability.FACE_CLUSTERING,
                InferenceCapability.SEMANTIC_IMAGE_SEARCH,
            ),
        ).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        assertEquals(0, result.facesFound)
        assertEquals(0, result.photosSearchedForFaces)
        assertEquals(1, result.embedded)
    }

    /**
     * ⚠⚠ One byte read serves all three halves. A separate face pass would re-walk the library to
     * get what this one already holds — IN-D8's argument, extended.
     */
    @Test
    fun allThreeHalvesShareOneByteRead() = runTest {
        val reads = mutableListOf<String>()
        val store = InMemoryFaceTemplateStore()
        val embeddings = InMemoryEmbeddingStore()
        val proxy = proxy().apply {
            consentTo(faceCapability)
            consentTo(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        }
        val item = listOf(IndexableMedia("/a.jpg") { reads += "/a.jpg"; "anna".encodeToByteArray() })

        val result = indexer(
            store,
            embeddings,
            capabilities = setOf(
                InferenceCapability.FACE_CLUSTERING,
                InferenceCapability.SEMANTIC_IMAGE_SEARCH,
            ),
        ).index(proxy.executionContext, item, capability = faceCapability)

        assertEquals(1, reads.size, "one read, not one per half")
        assertEquals(1, result.facesEmbedded)
        assertEquals(1, result.embedded)
    }

    // ---- the two "no faces" that are NOT the same thing -------------------------------------

    /**
     * A backend that supports the capability and cannot deliver.
     *
     * ⚠⚠ `FakeInferenceBackend` cannot produce this: it answers `Failed` only when the capability is
     * absent, and a registry with no such backend never reaches `detectFaces` at all. So the failure
     * path is unreachable through the fake — which is exactly how it would have gone untested.
     */
    private class FailingDetector(
        private val outcome: FaceDetectionOutcome,
    ) : InferenceBackend {
        var calls = 0
        override val id: String get() = "failing-detector"
        override fun supports(capability: InferenceCapability): Boolean =
            capability == InferenceCapability.FACE_CLUSTERING

        override suspend fun detectFaces(bytes: ByteArray): FaceDetectionOutcome {
            calls++
            return outcome
        }

        override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? = null
        override suspend fun embedText(text: String): ImageEmbedding? = null
        override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag> = emptyList()
        override suspend fun recognizeText(bytes: ByteArray): List<TextRegion> = emptyList()
    }

    private fun indexerWith(backend: InferenceBackend, store: FaceTemplateStore) = MediaIndexer(
        registry = InferenceBackendRegistry(listOf(backend)),
        deviceId = thisDevice,
        faces = store,
        conditions = IndexingConditions { true },
    )

    /**
     * ⚠⚠ **A detector that could not run says NOTHING about the photograph**, so nothing is recorded
     * against it. Remembering a failure here would keep the photo out of People for good —
     * `ImageEmbeddingOutcome`'s lesson (§32.4), in the half where the negative case is the majority.
     */
    @Test
    fun aDetectorThatFailsRemembersNothingAboutThePhotograph() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }

        val result = indexerWith(FailingDetector(FaceDetectionOutcome.Failed), store).index(
            proxy.executionContext,
            media("/a.jpg" to "anna"),
            capability = faceCapability,
        )

        assertFalse(store.visited("/a.jpg"), "a failure is not a fact about the file")
        assertEquals(0, store.size())
        assertEquals(1, result.embeddingsFailed, "and it is counted, not silently dropped")
    }

    /** ⚠ Bytes this device cannot decode ARE a fact about the file, and are remembered. */
    @Test
    fun bytesThatCannotBeDecodedAreRememberedAsVisited() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }

        indexerWith(FailingDetector(FaceDetectionOutcome.Unreadable), store).index(
            proxy.executionContext,
            media("/broken.jpg" to "anna"),
            capability = faceCapability,
        )

        assertTrue(store.visited("/broken.jpg"), "stable for this file on this device")
        assertEquals(0, store.size(), "and it holds no templates")
    }

    /**
     * ⚠⚠ A detector that will not open will not open for the next photograph either. Without the
     * latch, a broken model costs a session open **per image** across the whole library.
     */
    @Test
    fun aFailingDetectorIsAskedOnceNotOncePerPhotograph() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        val detector = FailingDetector(FaceDetectionOutcome.Failed)

        val result = indexerWith(detector, store).index(
            proxy.executionContext,
            media("/a.jpg" to "anna", "/b.jpg" to "bob", "/c.jpg" to "cara"),
            capability = faceCapability,
        )

        assertEquals(1, detector.calls, "asked once, then latched")
        assertEquals(1, result.photosSearchedForFaces)
    }

    // ---- a face that could not be embedded is not a fact about the file ------------------------

    /**
     * A detector that finds faces beside an embedder that cannot run — the partial case.
     *
     * ⚠ `FakeInferenceBackend` cannot produce it either: it has no way to succeed at detection and
     * fail at embedding, which is exactly the combination the contract singles out.
     */
    private class HalfWorkingBackend(
        private val faces: Int,
        private val embedsBeforeFailing: Int,
    ) : InferenceBackend {
        var embedCalls = 0
        override val id: String get() = "half-working"
        override fun supports(capability: InferenceCapability): Boolean =
            capability == InferenceCapability.FACE_CLUSTERING

        override suspend fun detectFaces(bytes: ByteArray): FaceDetectionOutcome =
            FaceDetectionOutcome.Detected(
                (0 until faces).map {
                    DetectedFace(
                        box = FaceBox(0f, 0f, 1f, 1f),
                        landmarks = FaceLandmarks(
                            leftEye = FacePoint(40f, 50f),
                            rightEye = FacePoint(72f, 50f),
                            noseTip = FacePoint(56f, 70f),
                            mouthLeft = FacePoint(42f, 92f),
                            mouthRight = FacePoint(70f, 92f),
                        ),
                    )
                },
            )

        override suspend fun embedFace(bytes: ByteArray, face: DetectedFace): ImageEmbedding? {
            embedCalls++
            return if (embedCalls > embedsBeforeFailing) {
                null
            } else {
                ImageEmbedding(FloatArray(4) { 0.5f }, ModelProvenance("half-working", 1))
            }
        }

        override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? = null
        override suspend fun embedText(text: String): ImageEmbedding? = null
        override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag> = emptyList()
        override suspend fun recognizeText(bytes: ByteArray): List<TextRegion> = emptyList()
    }

    /**
     * ⚠⚠ **A photograph whose faces were only PARTLY embedded must not be remembered as done.**
     * `embedFace` documents `null` as "the model could not run", never "not a face" — whether the
     * bytes decode was already settled by `detectFaces`. Storing the survivors and marking the file
     * visited loses the rest permanently: every later pass reads `visited` and stays away.
     */
    @Test
    fun aPhotographWhoseFacesCouldNotAllBeEmbeddedIsNotRecorded() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        // Two faces; the first embeds, the second cannot.
        val backend = HalfWorkingBackend(faces = 2, embedsBeforeFailing = 1)

        val result = indexerWith(backend, store).index(
            proxy.executionContext,
            media("/a.jpg" to "anna"),
            capability = faceCapability,
        )

        assertFalse(store.visited("/a.jpg"), "a model that could not run is not a fact about the file")
        assertEquals(0, store.size(), "and the half that DID embed is not kept either")
        assertEquals(1, result.embeddingsFailed)
    }

    /** ⚠ The whole-success case, so the test above cannot pass by never storing anything. */
    @Test
    fun aPhotographWhoseFacesAllEmbedIsRecorded() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        val backend = HalfWorkingBackend(faces = 2, embedsBeforeFailing = 2)

        val result = indexerWith(backend, store).index(
            proxy.executionContext,
            media("/a.jpg" to "anna"),
            capability = faceCapability,
        )

        assertTrue(store.visited("/a.jpg"))
        assertEquals(2, result.facesEmbedded)
    }

    /**
     * ⚠⚠ **A photograph the latched detector never opened is not "already indexed".** Once the
     * detector fails, every remaining file short-circuits; counting those as `current` reports a
     * library as done that was never looked at, and the telemetry is the only place the difference
     * survives.
     *
     * ⚠⚠ **The pass runs under `CONTENT_TAGGING`, not `FACE_CLUSTERING`, and that is load-bearing.**
     * `tagger` is resolved as `backendFor(capability)`, so a face-only test that passes the face
     * capability makes the face backend double as the TAGGER — `needsTags` is then true, the
     * short-circuit below is never reached, and the assertion passes without exercising the line it
     * names. The first version of this test did exactly that and survived the mutation.
     */
    @Test
    fun photographsSkippedByALatchedDetectorAreNotCountedAsCurrent() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }

        val result = indexerWith(FailingDetector(FaceDetectionOutcome.Failed), store).index(
            proxy.executionContext,
            media("/a.jpg" to "anna", "/b.jpg" to "bob", "/c.jpg" to "cara"),
            capability = InferenceCapability.CONTENT_TAGGING,
        )

        assertEquals(0, result.alreadyCurrent, "none of the three was indexed, so none is current")
        assertEquals(3, result.embeddingsFailed, "and all three are reported as not done")
    }

    /**
     * ⚠⚠ An embedder that could not run for one face will not run for the next photograph's either.
     * Without the latch, a broken embedder costs a model call per FACE across the whole library.
     */
    @Test
    fun anEmbedderThatCouldNotRunIsNotReAskedForEveryPhotograph() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        val backend = HalfWorkingBackend(faces = 1, embedsBeforeFailing = 0)

        indexerWith(backend, store).index(
            proxy.executionContext,
            media("/a.jpg" to "anna", "/b.jpg" to "bob", "/c.jpg" to "cara"),
            capability = InferenceCapability.CONTENT_TAGGING,
        )

        assertEquals(1, backend.embedCalls, "asked once, then latched")
    }

    /**
     * A store that refuses every write — a foreign stamp, or a width it does not hold.
     *
     * ⚠ Distinct from a broken model, and the pass report is the only place that distinction
     * survives: one is `embeddingsRejected`, the other `embeddingsFailed`. Folding them together
     * loses the difference between "this device's store disagrees" and "the model would not run",
     * which are fixed by completely different things.
     */
    private class RefusingFaceStore : FaceTemplateStore {
        /** ⚠ The latch is only observable here: a refusal counted per file looks identical. */
        var putCalls = 0
        override suspend fun visited(filePath: String): Boolean = false
        override suspend fun put(
            filePath: String,
            faces: List<StoredFace>,
            producedBy: ModelProvenance,
        ): Boolean {
            putCalls++
            return false
        }

        override suspend fun facesOf(filePath: String): List<StoredFace> = emptyList()
        override suspend fun rows(): List<FaceRow> = emptyList()
        override suspend fun remove(filePath: String) = Unit
        override suspend fun remembered(): Set<String> = emptySet()
        override suspend fun forget(paths: Collection<String>): Removal = Removal(removed = 0)
        override suspend fun provenance(): ModelProvenance? = null
        override suspend fun size(): Int = 0
        override suspend fun clear() = Unit
    }

    @Test
    fun aStoreThatRefusesIsReportedAsRejectedNotFailed() = runTest {
        val proxy = proxy().apply { consentTo(faceCapability) }
        val backend = HalfWorkingBackend(faces = 1, embedsBeforeFailing = 99)

        val result = indexerWith(backend, RefusingFaceStore()).index(
            proxy.executionContext,
            media("/a.jpg" to "anna", "/b.jpg" to "bob", "/c.jpg" to "cara"),
            capability = InferenceCapability.CONTENT_TAGGING,
        )

        assertEquals(3, result.embeddingsRejected, "the refusal, and the two it latched")
        assertEquals(0, result.embeddingsFailed, "nothing here FAILED — the model ran fine")
    }

    /**
     * ⚠⚠ **A refusal on the "no faces here" path is a refusal too.** A store already carrying a
     * foreign stamp turns these down exactly as it turns down a real batch; ignoring the return
     * meant a library of undecodable files re-ran the detector for every one of them, reported no
     * rejection at all, and never latched the incompatibility the `Detected` branch latches on.
     */
    @Test
    fun aRefusedUnreadableRecordIsCountedAndLatched() = runTest {
        val proxy = proxy().apply { consentTo(faceCapability) }

        val store = RefusingFaceStore()

        val result = indexerWith(FailingDetector(FaceDetectionOutcome.Unreadable), store).index(
            proxy.executionContext,
            media("/a.jpg" to "anna", "/b.jpg" to "bob", "/c.jpg" to "cara"),
            capability = InferenceCapability.CONTENT_TAGGING,
        )

        assertEquals(3, result.embeddingsRejected, "the refusal, and the two it latched")
        assertEquals(0, result.embeddingsFailed, "nothing FAILED — the detector answered fine")
        // ⚠⚠ The counts alone cannot see the latch — a per-file refusal totals the same. Only the
        // store knowing it was asked once distinguishes "latched" from "re-asked every time".
        assertEquals(1, store.putCalls, "asked once, then latched")
    }

    // ---- the model's own stamp, and withdrawal --------------------------------------------------

    /** A fake that answers for its model, as `MediaPipeFaceBackend` does. */
    private class StampedBackend(
        inner: InferenceBackend,
        override val provenance: ModelProvenance,
    ) : InferenceBackend by inner, ModelBackedBackend

    /**
     * ⚠⚠ The store's stamp must be the MODEL's, or a weight swap appends a second embedding space
     * onto the first. The backend-id fallback is the same string for every checkpoint the backend
     * ever loads.
     */
    @Test
    fun aModelBackedBackendStampsTheStoreWithItsOwnModel() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        val model = ModelProvenance("auraface-v1-int8", 1)
        val indexer = MediaIndexer(
            registry = InferenceBackendRegistry(listOf(StampedBackend(FakeInferenceBackend(), model))),
            deviceId = thisDevice,
            faces = store,
            conditions = IndexingConditions { true },
        )

        indexer.index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        assertEquals(model, store.provenance())
    }

    /**
     * ⚠⚠ Switching faces off on THIS device deletes the templates at the next pass — "stop growing"
     * is not what the user asked for.
     */
    @Test
    fun withdrawingThisDevicesConsentDeletesTheTemplates() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        indexer(store).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)
        assertEquals(1, store.size())

        proxy.write(faceCapability.deviceConsent, false, deviceId = thisDevice)
        indexer(store).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        assertEquals(0, store.size())
        assertEquals(null, store.provenance(), "the stamp goes too — nothing of the old store remains")
        assertFalse(store.visited("/a.jpg"))
    }

    /** ⚠ And the realm switch, which another device may have flipped and sync delivered here. */
    @Test
    fun withdrawingTheRealmConsentDeletesTheTemplates() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        indexer(store).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        proxy.write(faceCapability.realmConsent, false)
        indexer(store).index(proxy.executionContext, media("/b.jpg" to "bob"), capability = faceCapability)

        assertEquals(0, store.size())
        assertEquals(null, store.provenance())
    }

    /** ⚠ Even with no face backend left — a deleted model does not take the templates with it. */
    @Test
    fun withdrawalDeletesTheTemplatesEvenWithNoFaceBackend() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        indexer(store).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        proxy.write(faceCapability.deviceConsent, false, deviceId = thisDevice)
        indexer(store, capabilities = emptySet())
            .index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        assertEquals(0, store.size())
    }

    /** ⚠⚠ The guard's other side: consent standing, nothing is deleted. */
    @Test
    fun aPassWithConsentKeepsTheTemplates() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        indexer(store).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)
        indexer(store).index(proxy.executionContext, media("/b.jpg" to "bob"), capability = faceCapability)

        assertEquals(2, store.size())
    }

    /**
     * ⚠⚠ An app update that moves the pinned model REBASES the store. Freshness is `visited` alone,
     * so without it every photograph seen under the old model would be skipped, every new one
     * refused, and People would keep serving the old space's clusters.
     */
    @Test
    fun aDifferentModelsTemplatesAreClearedAndTheLibraryReIndexed() = runTest {
        val store = InMemoryFaceTemplateStore()
        val old = ModelProvenance("auraface-v1-int8", 1)
        store.put("/a.jpg", listOf(StoredFace(FaceBox(0f, 0f, 1f, 1f), FloatArray(4) { 1f })), old)
        val proxy = proxy().apply { consentTo(faceCapability) }
        val current = ModelProvenance("auraface-v1-int8", 2)
        val indexer = MediaIndexer(
            registry = InferenceBackendRegistry(listOf(StampedBackend(FakeInferenceBackend(), current))),
            deviceId = thisDevice,
            faces = store,
            conditions = IndexingConditions { true },
        )

        val result = indexer.index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)

        assertEquals(current, store.provenance())
        assertEquals(1, result.facesEmbedded, "the photograph was re-read under the new model, not skipped")
        assertEquals(0, result.embeddingsRejected, "the old stamp must not refuse the new model")
    }

    /** ⚠ The guard's other side: the same model's store is left alone. */
    @Test
    fun theSameModelsTemplatesSurviveAPass() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        indexer(store).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)
        val reads = mutableListOf<String>()
        indexer(store).index(
            proxy.executionContext,
            listOf(IndexableMedia("/a.jpg") { reads += "/a.jpg"; "anna".encodeToByteArray() }),
            capability = faceCapability,
        )

        assertEquals(emptyList(), reads, "a visited photograph under the same model is not re-read")
        assertEquals(1, store.size())
    }

    /**
     * ⚠⚠ A switch-off DURING a pass deletes what the pass already made, not only at the next pass.
     * The second photograph's read withdraws consent, as a user flipping the switch mid-pass would;
     * the third file's check must then clear the store.
     */
    @Test
    fun withdrawingConsentMidPassDeletesTheTemplatesAlreadyMade() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        val library = listOf(
            IndexableMedia("/a.jpg") { "anna".encodeToByteArray() },
            IndexableMedia("/b.jpg") {
                proxy.write(faceCapability.deviceConsent, false, deviceId = thisDevice)
                "bob".encodeToByteArray()
            },
            IndexableMedia("/c.jpg") { "carl".encodeToByteArray() },
        )

        indexer(store).index(proxy.executionContext, library, capability = faceCapability)

        assertEquals(0, store.size(), "templates made before the switch-off must not survive the pass")
        assertEquals(null, store.provenance())
    }

    // ---- indexing from a preview when the original is not here ----------------------------------

    /** Records the orientation each face call was given, and answers like the fake. */
    private class OrientationRecordingBackend(private val inner: FakeInferenceBackend) : InferenceBackend by inner {
        val detectOrientations = mutableListOf<Int?>()
        override suspend fun detectFaces(bytes: ByteArray, orientation: Int?): FaceDetectionOutcome {
            detectOrientations += orientation
            return inner.detectFaces(bytes)
        }
        override suspend fun embedFace(bytes: ByteArray, face: DetectedFace, orientation: Int?): ImageEmbedding? =
            inner.embedFace(bytes, face)
    }

    /**
     * ⚠⚠ A phone may hold only a few of the library's originals, so a photograph stored only on
     * the relay is indexed from its preview — and with NO explicit orientation: a rung is stored
     * upright, and turning it by the original's orientation turns every iPhone-minted face a
     * second time.
     */
    @Test
    fun aPhotoStoredOnlyOnTheRelayIsIndexedFromItsUprightPreview() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        val backend = OrientationRecordingBackend(FakeInferenceBackend())
        val remote = IndexableMedia(
            filePath = "/remote.jpg",
            bytes = { null },
            preview = { PreviewBytes("anna".encodeToByteArray()) },
        )

        val result = indexerWith(backend, store).index(proxy.executionContext, listOf(remote), capability = faceCapability)

        assertEquals(1, result.fromPreview)
        assertEquals(0, result.notOnDevice)
        assertEquals(1, store.facesOf("/remote.jpg").size)
        assertEquals(listOf<Int?>(null), backend.detectOrientations, "null = the bytes' own tag, never the row's")
    }

    /** An original that IS here is used as-is; the preview is never asked for. */
    @Test
    fun anOriginalOnTheDeviceNeverConsultsThePreview() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        val backend = OrientationRecordingBackend(FakeInferenceBackend())
        var previewAsked = false
        val here = IndexableMedia(
            filePath = "/here.jpg",
            bytes = { "anna".encodeToByteArray() },
            preview = { previewAsked = true; null },
        )

        val result = indexerWith(backend, store).index(proxy.executionContext, listOf(here), capability = faceCapability)

        assertFalse(previewAsked)
        assertEquals(0, result.fromPreview)
        // ⚠ No explicit orientation: an original carries its own EXIF.
        assertEquals(listOf<Int?>(null), backend.detectOrientations)
    }

    /** Neither original nor preview: counted as not on this device, never recorded as visited. */
    @Test
    fun noOriginalAndNoPreviewIsNotOnThisDevice() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        val nowhere = IndexableMedia(filePath = "/nowhere.jpg", bytes = { null }, preview = { null })

        val result = indexer(store).index(proxy.executionContext, listOf(nowhere), capability = faceCapability)

        assertEquals(1, result.notOnDevice)
        assertFalse(store.visited("/nowhere.jpg"), "a later pass must be able to try again")
    }

    /**
     * ⚠⚠ Mid-pass, one consent read comes back EMPTY — the row is gone for a moment. That photo's
     * faces are skipped and nothing is deleted; only an explicit switch-off deletes, so a vanished
     * row never wipes the whole store.
     */
    @Test
    fun aConsentRowThatVanishesMidPassDeletesNothing() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(faceCapability) }
        indexer(store).index(proxy.executionContext, media("/a.jpg" to "anna"), capability = faceCapability)
        assertEquals(1, store.size())

        val deviceRow = org.tenshu.core.entity.DataId.StringDataId.create(
            context = proxy.tContext,
            type = DeviceSetting.ID,
            keyValue = DeviceSetting.compositeKey(thisDevice, faceCapability.deviceConsent.id),
        )
        val library = listOf(
            IndexableMedia("/b.jpg") {
                // The row disappears between this photo and the next, as a glitch would make it.
                proxy.executionContext.deleteData(deviceRow)
                "bob".encodeToByteArray()
            },
            IndexableMedia("/c.jpg") { "carl".encodeToByteArray() },
        )
        indexer(store).index(proxy.executionContext, library, capability = faceCapability)

        assertTrue(store.size() >= 1, "Anna's template survives a read that merely found nothing")
        assertTrue(store.visited("/a.jpg"))
    }

    /** ⚠ And a store with no consent rows at all is left alone at the start of a pass. */
    @Test
    fun aPassWithNoConsentRowsAtAllDeletesNothing() = runTest {
        // ⚠ Stamped as the pass itself stamps (the fake backend's id at version 1), so the
        // model-change rebase — a different, correct reason to clear — does not fire here.
        val stamp = ModelProvenance(FakeInferenceBackend().id, 1)
        val store = InMemoryFaceTemplateStore().apply {
            put("/a.jpg", listOf(StoredFace(FaceBox(0f, 0f, 1f, 1f), FloatArray(4) { 1f })), stamp)
        }
        val proxy = proxy() // ⚠ no consent written

        indexer(store).index(proxy.executionContext, media("/b.jpg" to "bob"), capability = faceCapability)

        assertEquals(1, store.size())
    }
}
