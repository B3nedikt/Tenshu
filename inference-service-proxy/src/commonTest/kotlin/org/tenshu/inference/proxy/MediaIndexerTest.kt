package org.tenshu.inference.proxy

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.entity.Entity
import org.tenshu.inference.INDEX_MARKER_LABEL
import org.tenshu.inference.IndexingPaused
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.vocabulary.LabelTier
import org.tenshu.inference.proxy.vocabulary.VocabularyLabel
import org.tenshu.inference.proxy.vocabulary.vocabularyOf
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The index pass (IN-3) — run entirely against `FakeInferenceBackend`, which is what IN-1's fake
 * was built for: the consumer stack is provable with **zero models and zero hardware**.
 */
class MediaIndexerTest {

    private val thisDevice = "device-a"
    private val capability = InferenceCapability.CONTENT_TAGGING

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

    private fun TestProxy.consent(capability: InferenceCapability = this@MediaIndexerTest.capability) {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    private fun media(vararg names: String) = names.map { name ->
        IndexableMedia(filePath = "/photos/$name") { "bytes of $name".encodeToByteArray() }
    }

    private fun indexer(
        registry: InferenceBackendRegistry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
        provenance: ModelProvenance = ModelProvenance("fake", 1),
        embeddings: EmbeddingStore? = null,
        progress: IndexProgressHolder? = null,
        conditions: IndexingConditions = IndexingConditions { true },
    ) = MediaIndexer(
        registry,
        thisDevice,
        // ⚠⚠ A vocabulary is now REQUIRED for any label to be written (Phase 2, IN-D9): the empty
        // one emits nothing however confident the model is, because an uncalibrated label is not
        // better than no label. `FakeInferenceBackend` tags `cat` at 0.9, so this is the smallest
        // vocabulary that keeps these tests about the *indexer* rather than about the rule —
        // `LabelSelectionTest` owns the rule.
        vocabulary = vocabularyOf(
            version = 1,
            labels = listOf(VocabularyLabel("cat", LabelTier.TOP, floor = 0.5f)),
        ),
        currentProvenance = { provenance },
        embeddings = embeddings,
        progress = progress,
        conditions = conditions,
    )

    private fun TestProxy.labelsOf(filePath: String): List<String> =
        InferenceCapability.entries.flatMap { capability ->
            listOf("cat", INDEX_MARKER_LABEL).mapNotNull { label ->
                val id = org.tenshu.core.entity.DataId.StringDataId.create(
                    context = tContext,
                    type = MediaLabel.ID,
                    keyValue = MediaLabel.labelKey(filePath, capability, label),
                )
                executionContext.database.getData(id)?.let { label }
            }
        }

    @Test
    fun withNoConsentNothingIsRead() = runTest {
        val proxy = proxy()
        var reads = 0
        val watched = listOf(IndexableMedia("/photos/a.jpg") { reads++; byteArrayOf(1) })

        val result = indexer().index(proxy.executionContext, watched, capability)

        // ⚠⚠ Not merely "no rows written" — the bytes are never even fetched. A pass that reads a
        // library it has no consent to look at has already done the thing consent was about.
        assertEquals(0, reads)
        assertEquals(1, result.skippedNoConsent)
        assertEquals(0, result.labelled)
    }

    @Test
    fun withNoBackendThePassIsANoOp() = runTest {
        val proxy = proxy().apply { consent() }

        val result = indexer(registry = InferenceBackendRegistry.Empty)
            .index(proxy.executionContext, media("a.jpg"), capability)

        // ⚠ The production state until a model exists. "Nothing can do this here" is an ordinary
        // answer — the pass reports it rather than failing.
        assertEquals(1, result.skippedNoBackend)
    }

    @Test
    fun aConsentedPassWritesLabelsWithTheirProvenance() = runTest {
        val proxy = proxy().apply { consent() }

        val result = indexer().index(proxy.executionContext, media("a.jpg"), capability)

        assertTrue(result.labelled >= 1)
        assertTrue(proxy.labelsOf("/photos/a.jpg").contains("cat"))
    }

    @Test
    fun asecondPassSkipsWhatIsAlreadyCurrent() = runTest {
        val proxy = proxy().apply { consent() }
        indexer().index(proxy.executionContext, media("a.jpg"), capability)

        val second = indexer().index(proxy.executionContext, media("a.jpg"), capability)

        // ⚠⚠ The whole reason `producedBy` exists: without it a second pass re-runs the model over
        // a library it has already read — hours of battery to produce byte-identical rows.
        assertEquals(1, second.alreadyCurrent)
        assertEquals(0, second.labelled)
    }

    @Test
    fun aNewerModelReIndexes() = runTest {
        val proxy = proxy().apply { consent() }
        indexer(provenance = ModelProvenance("fake", 1)).index(proxy.executionContext, media("a.jpg"), capability)

        val afterUpgrade = indexer(provenance = ModelProvenance("fake", 2))
            .index(proxy.executionContext, media("a.jpg"), capability)

        assertEquals(0, afterUpgrade.alreadyCurrent)
        assertTrue(afterUpgrade.labelled >= 1)
    }

    @Test
    fun aFileTheModelHasNoOpinionAboutIsStillMarkedIndexed() = runTest {
        val proxy = proxy().apply { consent() }
        // A backend that finds nothing — the common case for an unremarkable photo.
        val silent = FakeInferenceBackend(tags = emptyList())

        indexer(InferenceBackendRegistry(listOf(silent)))
            .index(proxy.executionContext, media("a.jpg"), capability)
        val second = indexer(InferenceBackendRegistry(listOf(silent)))
            .index(proxy.executionContext, media("a.jpg"), capability)

        // ⚠⚠ "It ran" and "it found something" are different facts. Judging currency on "has any
        // label" would re-read every unremarkable photo on every pass, forever.
        assertEquals(1, second.alreadyCurrent)
    }

    @Test
    fun revokingConsentMidPassStopsIt() = runTest {
        val proxy = proxy().apply { consent() }
        val items = listOf(
            IndexableMedia("/photos/a.jpg") {
                // The user flips the switch while the first file is being read.
                proxy.write(capability.deviceConsent, false, deviceId = thisDevice)
                byteArrayOf(1)
            },
            IndexableMedia("/photos/b.jpg") { byteArrayOf(2) },
        )

        val result = indexer().index(proxy.executionContext, items, capability)

        // ⚠⚠ Consent is re-read per FILE. A check hoisted to the top of the pass would keep
        // grinding through a library the user just switched off — for minutes, or hours.
        assertEquals(1, result.skippedNoConsent)
    }

    @Test
    fun anUnreadableFileIsCountedRatherThanSwallowed() = runTest {
        val proxy = proxy().apply { consent() }

        val result = indexer().index(
            proxy.executionContext,
            listOf(IndexableMedia("/photos/gone.jpg") { null }),
            capability,
        )

        // ⚠ "Indexed 0 of 4 000" with no reason is the report that gets filed as "the feature does
        // nothing".
        assertEquals(1, result.notOnDevice)
        assertEquals(1, result.skipped)
    }

    @Test
    fun onePassProducesLabelsAndVectorsTogether() = runTest {
        val proxy = proxy().apply {
            consent()
            consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        }
        val embeddings = InMemoryEmbeddingStore()

        val result = indexer(embeddings = embeddings)
            .index(proxy.executionContext, media("a.jpg"), capability)

        // ⭐ IN-D8's argument made concrete: the library walk is the expensive part, so the vector
        // comes out of the same visit as the labels rather than a second pass over every photo.
        assertTrue(result.labelled >= 1)
        assertEquals(1, result.embedded)
        assertEquals(1, embeddings.size())
    }

    @Test
    fun taggingConsentAloneDoesNotBuildASearchableIndex() = runTest {
        // ⚠⚠ Tagging is on; semantic search is NOT. Two capabilities, two switches (IN-D3) — a
        // user who wants labels but not a searchable index of their photos gets exactly that,
        // and reusing the tagging check would silently widen one consent into two.
        val proxy = proxy().apply { consent() }
        val embeddings = InMemoryEmbeddingStore()

        val result = indexer(embeddings = embeddings)
            .index(proxy.executionContext, media("a.jpg"), capability)

        assertTrue(result.labelled >= 1, "labels are still written")
        assertEquals(0, result.embedded)
        assertEquals(0, embeddings.size())
    }

    @Test
    fun aVectorFromAnotherModelIsCountedRatherThanThrown() = runTest {
        val proxy = proxy().apply {
            consent()
            consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        }
        // A store left behind by an earlier model — the state a version bump produces mid-flight.
        val embeddings = InMemoryEmbeddingStore().apply {
            put("/photos/old.jpg", FloatArray(8), ModelProvenance("other", 1))
        }

        val result = indexer(embeddings = embeddings)
            .index(proxy.executionContext, media("a.jpg"), capability)

        // ⚠ Counted and carried on, like an unreadable file: a pass that meets a stale sink
        // half-way through a library should finish and report, not abort three hours of work.
        assertEquals(0, result.embedded)
        assertEquals(1, result.embeddingsRejected)
        assertTrue(result.labelled >= 1)
    }

    @Test
    fun withNoSinkThePassIsExactlyItsOldSelf() = runTest {
        val proxy = proxy().apply {
            consent()
            consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        }

        val result = indexer(embeddings = null).index(proxy.executionContext, media("a.jpg"), capability)

        // ⚠ The wiring a build without a sink still gets: labels, and no claim to have indexed
        // anything for search.
        assertTrue(result.labelled >= 1)
        assertEquals(0, result.embedded)
    }

    /**
     * §7.7's "true and useless" check needs a numerator and a denominator, and only the pass has
     * both.
     *
     * ⚠⚠ Counted per **image**, never per row: "on how many photographs did this fire" is the
     * question, and a label writing two rows for one photograph would otherwise read as twice as
     * common as it is.
     */
    @Test
    fun aPassCountsHowManyImagesEachLabelFiredOn() = runTest {
        val proxy = proxy()
        proxy.consent()

        val result = indexer().index(
            proxy.executionContext,
            media("a.jpg", "b.jpg", "c.jpg"),
            capability,
        )

        assertEquals(3, result.consideredImages)
        assertEquals(mapOf("cat" to 3), result.labelFiringCounts)
    }

    /**
     * ⚠⚠ A skipped image is **not** considered. An incremental pass over an already-indexed library
     * must not report a rate off a denominator of nothing — it would read as a library-wide finding
     * and be a sample of none.
     */
    @Test
    fun aSecondPassConsidersNothingAndSoClaimsNoRates() = runTest {
        val proxy = proxy()
        proxy.consent()
        val photos = media("a.jpg", "b.jpg")

        indexer().index(proxy.executionContext, photos, capability)
        val second = indexer().index(proxy.executionContext, photos, capability)

        assertEquals(0, second.consideredImages)
        assertEquals(emptyMap(), second.labelFiringCounts)
    }

    // ─── IN-D6: visible and pausable (1e-2).

    /**
     * ⚠⚠ A pause **stops** the pass, it does not skip through it. A user whose phone is hot does
     * not want the remaining nine thousand images each considered and rejected — the pass would go
     * on "running" for minutes after they asked it to stop.
     */
    @Test
    fun aPausedDeviceStopsThePassRatherThanWalkingTheRestOfTheLibrary() = runTest {
        val proxy = proxy()
        proxy.consent()
        proxy.write(IndexingPaused, true, deviceId = thisDevice)
        var reads = 0
        val watched = (1..5).map { IndexableMedia("/photos/$it.jpg") { reads++; byteArrayOf(1) } }

        val result = indexer().index(proxy.executionContext, watched, capability)

        assertTrue(result.paused, "the pass must say it stopped because it was asked to")
        assertEquals(0, reads, "a paused pass reads nothing")
        assertEquals(0, result.labelled)
        // ⚠⚠ 1, not 5: it STOPPED at the first image rather than walking the remaining four and
        // rejecting each. Stopping and skipping write identical rows, so this counter is the only
        // thing that can tell them apart — a mutant swapping `break` for `continue` survived every
        // other assertion in this file.
        assertEquals(1, result.imagesVisited)
    }

    /**
     * ⚠⚠ Read **per image**, so a pause the user flips mid-pass takes effect on the next one — the
     * same reasoning consent already uses. A check hoisted to the top of the pass would keep
     * grinding through a library for hours after the button was pressed.
     */
    @Test
    fun aPauseFlippedMidPassStopsTheNextImage() = runTest {
        val proxy = proxy()
        proxy.consent()
        val seen = mutableListOf<String>()
        val watched = (1..4).map { n ->
            IndexableMedia("/photos/$n.jpg") {
                seen += "$n.jpg"
                // Pause from inside the first image's byte read; the loop must notice at the second.
                if (n == 1) proxy.write(IndexingPaused, true, deviceId = thisDevice)
                byteArrayOf(1)
            }
        }

        val result = indexer().index(proxy.executionContext, watched, capability)

        assertTrue(result.paused)
        assertEquals(listOf("1.jpg"), seen, "only the image in flight when the pause landed")
    }

    /**
     * ⚠ The bar counts **every** image visited, skips included: "how far through my library are
     * you" is the question, and a bar that only moved on newly-indexed photographs would sit still
     * for the whole of an incremental pass and read as stuck.
     */
    /**
     * ⚠⚠ Observed **synchronously from inside the pass**, never by collecting the `StateFlow`.
     * `progress` conflates and the pass runs to completion without suspending, so a collector is
     * scheduled only after `finish()` has already set it back to null — it would see nothing and
     * the test would be asserting on a race. (The same trap that made `1c-3`'s download-progress
     * assertion flaky: §25's note.) Reading `holder.progress.value` at a known point in the loop is
     * a fact, not a timing.
     */
    @Test
    fun progressAdvancesOverEveryImageIncludingSkippedOnes() = runTest {
        val proxy = proxy()
        proxy.consent()
        val holder = IndexProgressHolder()

        // First pass indexes a.jpg, so the second pass skips it.
        indexer(progress = holder).index(proxy.executionContext, media("a.jpg"), capability)

        var doneWhenSecondImageRan: Int? = null
        val mixed = listOf(
            // Skipped — already current.
            IndexableMedia("/photos/a.jpg") { "bytes of a.jpg".encodeToByteArray() },
            // New: its byte read is the synchronous observation point.
            IndexableMedia("/photos/b.jpg") {
                doneWhenSecondImageRan = holder.progress.value?.done
                "bytes of b.jpg".encodeToByteArray()
            },
        )

        indexer(progress = holder).index(proxy.executionContext, mixed, capability)

        // ⚠ 2, not 1: the skipped a.jpg advanced the bar too. A bar that only moved on newly
        // indexed photographs would sit still through an entire incremental pass and read as stuck.
        assertEquals(2, doneWhenSecondImageRan)
    }

    /** ⚠ The bar's denominator is the whole library handed to the pass, not what it will index. */
    @Test
    fun theBarsTotalIsEveryImageThePassWasHanded() = runTest {
        val proxy = proxy()
        proxy.consent()
        val holder = IndexProgressHolder()
        var totalSeen: Int? = null
        val photos = listOf(
            IndexableMedia("/photos/a.jpg") {
                totalSeen = holder.progress.value?.total
                byteArrayOf(1)
            },
            IndexableMedia("/photos/b.jpg") { byteArrayOf(1) },
            IndexableMedia("/photos/c.jpg") { byteArrayOf(1) },
        )

        indexer(progress = holder).index(proxy.executionContext, photos, capability)

        assertEquals(3, totalSeen)
    }

    /**
     * ⚠⚠ Always cleared. A bar that is not cleared says "indexing" forever, which is worse than
     * never showing one: the user cannot tell a stuck pass from a finished one, and there is
     * nothing they can do about either.
     */
    @Test
    fun theBarIsClearedWhenThePassFinishes() = runTest {
        val proxy = proxy()
        proxy.consent()
        val holder = IndexProgressHolder()

        indexer(progress = holder).index(proxy.executionContext, media("a.jpg"), capability)

        assertNull(holder.progress.value)
    }

    /** ⚠ And cleared on the paused exit too — the path most likely to leave it stuck. */
    @Test
    fun theBarIsClearedWhenThePassIsPaused() = runTest {
        val proxy = proxy()
        proxy.consent()
        proxy.write(IndexingPaused, true, deviceId = thisDevice)
        val holder = IndexProgressHolder()

        val result = indexer(progress = holder).index(proxy.executionContext, media("a.jpg"), capability)

        assertTrue(result.paused)
        assertNull(holder.progress.value)
    }

    // ─── §32: each half stands alone — its own backend, consent and freshness.

    /** The shape of the one backend production registers: search, and nothing else. */
    private fun searchOnlyRegistry() = InferenceBackendRegistry(
        listOf(FakeInferenceBackend(capabilities = setOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH))),
    )

    private fun counted(reads: MutableList<String>, vararg names: String) = names.map { name ->
        IndexableMedia("/photos/$name") {
            reads += name
            "bytes of $name".encodeToByteArray()
        }
    }

    /**
     * ⚠⚠ The defect that made the whole track inert on a device. The pinned ONNX backend supports
     * search and not tagging, and a pass that asked only for a tagging backend skipped every photo
     * as "no backend" — while the default fake, which supports everything, kept this file green.
     */
    @Test
    fun aBackendThatOnlySearchesStillBuildsTheIndex() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val embeddings = InMemoryEmbeddingStore()

        val result = indexer(registry = searchOnlyRegistry(), embeddings = embeddings)
            .index(proxy.executionContext, media("a.jpg"), capability)

        assertEquals(0, result.skippedNoBackend)
        assertEquals(1, result.embedded)
        assertEquals(1, embeddings.size())
    }

    /** ⚠⚠ Two switches, two halves (IN-D3): agreeing to search and not to labels is an index, not nothing. */
    @Test
    fun searchConsentAloneBuildsAnIndexAndWritesNoLabels() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val embeddings = InMemoryEmbeddingStore()

        val result = indexer(embeddings = embeddings).index(proxy.executionContext, media("a.jpg"), capability)

        assertEquals(0, result.skippedNoConsent)
        assertEquals(1, result.embedded)
        assertEquals(0, result.labelled)
        assertEquals(emptyList(), proxy.labelsOf("/photos/a.jpg"), "no marker either — labels were not agreed to")
    }

    /**
     * ⚠⚠ The marker SYNCS and the vector does not. A second device receives the first one's markers
     * and none of its vectors, so judging vectors by the marker left every device but the first with
     * no index at all. One proxy stands in for the realm, two stores for the two devices.
     */
    @Test
    fun aSecondDeviceEmbedsPhotosAnotherDeviceAlreadyTagged() = runTest {
        val proxy = proxy().apply {
            consent()
            consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        }
        indexer(embeddings = InMemoryEmbeddingStore()).index(proxy.executionContext, media("a.jpg"), capability)

        val secondDevice = InMemoryEmbeddingStore()
        val result = indexer(embeddings = secondDevice).index(proxy.executionContext, media("a.jpg"), capability)

        assertEquals(1, result.embedded)
        assertEquals(0, result.labelled, "the labels arrived by sync and are current")
        assertEquals(1, secondDevice.size())
    }

    /** ⚠⚠ The battery guard: with both halves current, a second pass reads no bytes at all. */
    @Test
    fun aSecondPassWithBothHalvesCurrentReadsNothing() = runTest {
        val proxy = proxy().apply {
            consent()
            consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        }
        val embeddings = InMemoryEmbeddingStore()
        indexer(embeddings = embeddings).index(proxy.executionContext, media("a.jpg", "b.jpg"), capability)

        val reads = mutableListOf<String>()
        val second = indexer(embeddings = embeddings)
            .index(proxy.executionContext, counted(reads, "a.jpg", "b.jpg"), capability)

        assertEquals(emptyList(), reads)
        assertEquals(2, second.alreadyCurrent)
    }

    /**
     * ⚠ A refusal is the sink's, not the photo's: a store stamped by another model refuses every
     * vector. The pass learns that from one image rather than re-embedding the library to be refused
     * once per photograph — and still counts every photo it could not index.
     */
    @Test
    fun aSinkThatRefusesIsLearnedFromOneImageNotEvery() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val foreign = InMemoryEmbeddingStore().apply {
            put("/photos/old.jpg", FloatArray(8), ModelProvenance("other", 1))
        }
        val reads = mutableListOf<String>()

        val result = indexer(embeddings = foreign)
            .index(proxy.executionContext, counted(reads, "a.jpg", "b.jpg", "c.jpg"), capability)

        assertEquals(listOf("a.jpg"), reads)
        assertEquals(3, result.embeddingsRejected)
        assertEquals(0, result.embedded)
    }

    /**
     * A search backend that cannot decode some files, and whose model can fail. ⚠ While failing it
     * still CLAIMS search — the shape of an ONNX session that will not open or a run that throws,
     * which leave `supports()` true. ⚠ [failsOn] is the other `Failed`: the model runs, and what it
     * answers for THIS photo is refused, every time.
     */
    private class PickyBackend(
        private val undecodable: Set<String>,
        private val failsOn: Set<String> = emptySet(),
    ) : InferenceBackend {
        private val inner = FakeInferenceBackend(capabilities = setOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        var modelFailing = false
        override val id: String get() = inner.id
        override fun supports(capability: InferenceCapability) = inner.supports(capability)
        override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? =
            (embedImageOutcome(bytes) as? ImageEmbeddingOutcome.Embedded)?.embedding
        override suspend fun embedImageOutcome(bytes: ByteArray): ImageEmbeddingOutcome = when {
            modelFailing || bytes.decodeToString() in failsOn -> ImageEmbeddingOutcome.Failed
            bytes.decodeToString() in undecodable -> ImageEmbeddingOutcome.Unreadable
            else -> ImageEmbeddingOutcome.Embedded(inner.embedImage(bytes)!!)
        }
        override suspend fun embedText(text: String) = inner.embedText(text)
        override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>) = inner.tagImage(bytes, vocabulary)
        override suspend fun recognizeText(bytes: ByteArray) = inner.recognizeText(bytes)
    }

    /**
     * ⚠⚠ With the marker gone from the vector half, a file this device cannot decode has to be
     * remembered device-locally — or every idle-and-charging pass re-reads it, and a library in a
     * format this platform cannot decode is re-read in full, forever.
     */
    @Test
    fun aFileThisDeviceCannotDecodeIsNotReadAgain() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val registry = InferenceBackendRegistry(listOf(PickyBackend(undecodable = setOf("bytes of b.raw"))))
        val embeddings = InMemoryEmbeddingStore()
        indexer(registry, embeddings = embeddings).index(proxy.executionContext, media("a.jpg", "b.raw"), capability)

        val reads = mutableListOf<String>()
        indexer(registry, embeddings = embeddings)
            .index(proxy.executionContext, counted(reads, "a.jpg", "b.raw"), capability)

        assertEquals(emptyList(), reads)
    }

    /**
     * ⚠⚠ The other side of that rule: a model that fails says nothing about the photo — and a failing
     * ONNX session still CLAIMS search, so the backend's claim cannot be what tells the two apart.
     * Remembering the failure would keep the photo out of the index for good.
     *
     * ⚠ `Failed` is also what a backend answers for a vector its output guard refused (NaN, all
     * zeros, another width), so this is that photo's path too: counted, nothing stored, not visited.
     */
    @Test
    fun aModelFailureIsNotRememberedAgainstThePhoto() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val backend = PickyBackend(undecodable = emptySet())
        val registry = InferenceBackendRegistry(listOf(backend))
        val embeddings = InMemoryEmbeddingStore()
        val items = listOf(
            IndexableMedia("/photos/a.jpg") { "bytes of a.jpg".encodeToByteArray() },
            IndexableMedia("/photos/b.jpg") {
                backend.modelFailing = true
                "bytes of b.jpg".encodeToByteArray()
            },
        )
        val first = indexer(registry, embeddings = embeddings).index(proxy.executionContext, items, capability)
        assertEquals(1, first.embeddingsFailed)
        assertEquals(1, first.embedded)
        assertEquals(1, embeddings.size(), "the failed photo has no vector")
        assertEquals(false, embeddings.visited("/photos/b.jpg"), "the failed photo is not marked as indexed")
        assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH), "search is still claimed")
        backend.modelFailing = false

        val result = indexer(registry, embeddings = embeddings)
            .index(proxy.executionContext, media("a.jpg", "b.jpg"), capability)

        assertEquals(1, result.embedded, "b.jpg is embedded once the model works again")
    }

    // ─── A failure STREAK stands the vector half down; one failed photo does not.

    private fun failingOn(vararg names: String) =
        PickyBackend(undecodable = emptySet(), failsOn = names.mapTo(mutableSetOf()) { "bytes of $it" })

    private fun names(count: Int, prefix: String = "p") = Array(count) { "$prefix$it.jpg" }

    /**
     * ⚠⚠ A `Failed` may be this photo's and not the model's — a vector the backend's output guard
     * refused. Latching on the first one ended the vector half of every pass at that photo, and
     * nothing walked after it was ever embedded.
     */
    @Test
    fun onePhotoTheModelFailsOnDoesNotStopThePhotosAfterIt() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val embeddings = InMemoryEmbeddingStore()
        val reads = mutableListOf<String>()

        val result = indexer(InferenceBackendRegistry(listOf(failingOn("b.jpg"))), embeddings = embeddings)
            .index(proxy.executionContext, counted(reads, "a.jpg", "b.jpg", "c.jpg", "d.jpg"), capability)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg"), reads)
        assertEquals(3, result.embedded)
        assertEquals(3, embeddings.size(), "the failed photo has no vector")
        assertTrue(embeddings.visited("/photos/c.jpg") && embeddings.visited("/photos/d.jpg"))
        assertFalse(embeddings.visited("/photos/b.jpg"), "and is not marked, so the next pass asks again")
        assertEquals(1, result.embeddingsFailed)
        assertEquals(0, result.embeddingsNotAttempted)
        assertFalse(result.embeddingStoodDown, "one failed photo is not a failing model")
        val line = result.summaryLine()
        assertTrue("embedded=3 " in line && "embedFailed=1 " in line && "embedNotAttempted=0 " in line, line)
        assertTrue("embedStopped=no " in line, line)
    }

    /**
     * ⚠ A model that will not run will not do better on the next photograph of the same pass — for
     * ONNX a failed open is a 100 MB session attempt per image. The pass asks exactly
     * [EMBED_FAILURE_STREAK_LIMIT] times, then stops reading for vectors, and says how many it left.
     */
    @Test
    fun aFailingModelIsLearnedFromAStreakNotFromEveryImage() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val backend = PickyBackend(undecodable = emptySet()).apply { modelFailing = true }
        val embeddings = InMemoryEmbeddingStore()
        val reads = mutableListOf<String>()
        val all = names(EMBED_FAILURE_STREAK_LIMIT + 4)

        val result = indexer(InferenceBackendRegistry(listOf(backend)), embeddings = embeddings)
            .index(proxy.executionContext, counted(reads, *all), capability)

        assertEquals(all.take(EMBED_FAILURE_STREAK_LIMIT), reads, "asked exactly the limit, not every photo")
        assertEquals(EMBED_FAILURE_STREAK_LIMIT, result.embeddingsFailed)
        assertEquals(4, result.embeddingsNotAttempted, "every photo left without a vector is still counted")
        assertTrue(result.embeddingStoodDown)
        assertEquals(0, result.embedded)
        assertEquals(0, result.alreadyCurrent, "a photo not looked at is not reported as done")
        all.forEach { assertFalse(embeddings.visited("/photos/$it"), "$it is not remembered") }
        val line = result.summaryLine()
        assertTrue("embedFailed=$EMBED_FAILURE_STREAK_LIMIT " in line && "embedNotAttempted=4 " in line, line)
        assertTrue("embedStopped=modelFailing " in line, line)
    }

    /** ⚠ The streak is CONSECUTIVE: a vector made in between shows the model works, and resets it. */
    @Test
    fun failuresWithAVectorBetweenThemNeverStandTheHalfDown() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val reads = mutableListOf<String>()
        val bad = names(EMBED_FAILURE_STREAK_LIMIT - 1, "bad")
        val more = names(EMBED_FAILURE_STREAK_LIMIT - 1, "worse")
        val last = names(EMBED_FAILURE_STREAK_LIMIT - 1, "worst")
        val walk = arrayOf(*bad, "good.jpg", *more, "fine.jpg", *last)

        val result = indexer(
            InferenceBackendRegistry(listOf(failingOn(*bad, *more, *last))),
            embeddings = InMemoryEmbeddingStore(),
        ).index(proxy.executionContext, counted(reads, *walk), capability)

        assertEquals(walk.toList(), reads, "every photo is asked")
        assertEquals(2, result.embedded)
        assertEquals(3 * (EMBED_FAILURE_STREAK_LIMIT - 1), result.embeddingsFailed)
        assertEquals(0, result.embeddingsNotAttempted)
        assertFalse(result.embeddingStoodDown)
    }

    /**
     * ⚠ The verdict is its own field: a streak that completes on the last photos of the walk left
     * nothing un-asked, and the counts alone would read as "some photos failed".
     */
    @Test
    fun aStreakThatEndsTheWalkIsStillReportedAsAFailingModel() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val bad = names(EMBED_FAILURE_STREAK_LIMIT, "bad")

        val result = indexer(InferenceBackendRegistry(listOf(failingOn(*bad))), embeddings = InMemoryEmbeddingStore())
            .index(proxy.executionContext, media("good.jpg", *bad), capability)

        assertEquals(1, result.embedded)
        assertEquals(EMBED_FAILURE_STREAK_LIMIT, result.embeddingsFailed)
        assertEquals(0, result.embeddingsNotAttempted)
        assertTrue(result.embeddingStoodDown)
    }

    /**
     * ⚠⚠ Per pass, and in memory: nothing about a stand-down is remembered, by the store or by the
     * indexer. The SAME indexer instance asks again on the next pass, from the first photo.
     */
    @Test
    fun aStandDownIsNotCarriedIntoTheNextPass() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val backend = PickyBackend(undecodable = emptySet()).apply { modelFailing = true }
        val embeddings = InMemoryEmbeddingStore()
        val all = names(EMBED_FAILURE_STREAK_LIMIT + 2)
        val indexer = indexer(InferenceBackendRegistry(listOf(backend)), embeddings = embeddings)
        val first = indexer.index(proxy.executionContext, media(*all), capability)
        assertTrue(first.embeddingStoodDown)
        assertEquals(2, first.embeddingsNotAttempted)
        backend.modelFailing = false

        val second = indexer.index(proxy.executionContext, media(*all), capability)

        assertEquals(all.size, second.embedded, "every photo, including the ones never asked about")
        assertEquals(0, second.embeddingsFailed)
        assertFalse(second.embeddingStoodDown)
    }

    /**
     * ⚠ An undecodable file is no evidence about the model either way: it neither resets the streak
     * nor adds to it. And it is still remembered, exactly as before.
     */
    @Test
    fun anUndecodableFileNeitherResetsNorAdvancesTheStreak() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val bad = names(EMBED_FAILURE_STREAK_LIMIT, "bad")
        fun backend() = PickyBackend(
            undecodable = setOf("bytes of x.raw"),
            failsOn = bad.mapTo(mutableSetOf()) { "bytes of $it" },
        )

        // Does not reset: the limit is still reached across it, and the photo after is not asked.
        // ⚠ `first.jpg` gives the store its stamp: a visit on an unstamped store is refused.
        val across = InMemoryEmbeddingStore()
        val reads = mutableListOf<String>()
        val walk = arrayOf("first.jpg", bad[0], "x.raw", *bad.drop(1).toTypedArray(), "good.jpg")
        val stopped = indexer(InferenceBackendRegistry(listOf(backend())), embeddings = across)
            .index(proxy.executionContext, counted(reads, *walk), capability)
        assertTrue(stopped.embeddingStoodDown)
        assertEquals(walk.toList() - "good.jpg", reads)
        assertEquals(1, stopped.embeddingsNotAttempted)
        assertTrue(across.visited("/photos/x.raw"), "the undecodable file is remembered")
        assertEquals(EMBED_FAILURE_STREAK_LIMIT, stopped.embeddingsFailed, "and is not a failure")

        // Does not advance: one short of the limit plus an undecodable file is not the limit.
        val short = InMemoryEmbeddingStore()
        val under = arrayOf("first.jpg", *bad.drop(1).toTypedArray(), "x.raw", "good.jpg")
        val carried = indexer(InferenceBackendRegistry(listOf(backend())), embeddings = short)
            .index(proxy.executionContext, media(*under), capability)
        assertFalse(carried.embeddingStoodDown)
        assertEquals(2, carried.embedded)
        assertTrue(short.visited("/photos/x.raw"))
    }

    // ─── §32: plugged in and unattended, asked by the pass itself.

    /**
     * ⚠⚠ The scheduler stores the pass's charging/idle constraints and does not enforce them, so the
     * pass is the only thing between opening Control and a multi-hour run on battery. A device that is
     * not ready reads nothing, and the bar never claims a pass is running.
     */
    @Test
    fun aDeviceThatIsNotPluggedInAndIdleStartsNothing() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        val holder = IndexProgressHolder()
        val reads = mutableListOf<String>()

        val result = indexer(
            registry = searchOnlyRegistry(),
            embeddings = InMemoryEmbeddingStore(),
            progress = holder,
            conditions = IndexingConditions { false },
        ).index(proxy.executionContext, counted(reads, "a.jpg", "b.jpg"), capability)

        assertTrue(result.deferred)
        assertEquals(emptyList(), reads)
        assertEquals(0, result.imagesVisited)
        assertNull(holder.progress.value)
    }

    /** ⚠⚠ Per image, and it stops: unplugging mid-pass means "stop now", not "skip the rest". */
    @Test
    fun unpluggingMidPassStopsAtTheNextImage() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        var ready = true
        val seen = mutableListOf<String>()
        val photos = (1..4).map { n ->
            IndexableMedia("/photos/$n.jpg") {
                seen += "$n.jpg"
                if (n == 1) ready = false
                "bytes of $n.jpg".encodeToByteArray()
            }
        }

        val result = indexer(
            registry = searchOnlyRegistry(),
            embeddings = InMemoryEmbeddingStore(),
            conditions = IndexingConditions { ready },
        ).index(proxy.executionContext, photos, capability)

        assertTrue(result.deferred)
        assertEquals(listOf("1.jpg"), seen)
        assertEquals(2, result.imagesVisited, "stopped at the second image rather than walking all four")
    }

    /** ⚠⚠ Fail-closed: a wiring that forgets the conditions indexes nothing rather than everything. */
    @Test
    fun anIndexerWiredWithoutConditionsNeverIndexes() = runTest {
        val proxy = proxy().apply { consent(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }

        val result = MediaIndexer(searchOnlyRegistry(), thisDevice, embeddings = InMemoryEmbeddingStore())
            .index(proxy.executionContext, media("a.jpg"), capability)

        assertTrue(result.deferred)
        assertEquals(0, result.embedded)
    }

    /** ⚠⚠ Every unknown is a no — a signal the platform could not read is not evidence of anything. */
    @Test
    fun theConditionsPolicyReadsEveryUnknownAsNo() {
        assertTrue(indexingConditionsAllow(onExternalPower = true, batteryLow = false, inUse = false))
        assertTrue(!indexingConditionsAllow(onExternalPower = false, batteryLow = false, inUse = false))
        assertTrue(!indexingConditionsAllow(onExternalPower = true, batteryLow = true, inUse = false))
        assertTrue(!indexingConditionsAllow(onExternalPower = true, batteryLow = false, inUse = true))
        assertTrue(!indexingConditionsAllow(onExternalPower = null, batteryLow = false, inUse = false))
        assertTrue(!indexingConditionsAllow(onExternalPower = true, batteryLow = null, inUse = false))
        assertTrue(!indexingConditionsAllow(onExternalPower = true, batteryLow = false, inUse = null))
    }

    /**
     * ⚠⚠ The bar is cleared however the pass ends — asserted on [MediaIndexer] itself, because a proxy
     * that wires no `MediaIndexRunner` (and every direct caller) has only this `finally`. A throw
     * unwinds past the ordinary clear at the bottom of the walk, and a holder left non-null is a bar
     * that says "indexing" for the life of the process, with *Index now* disabled behind it.
     */
    @Test
    fun aPassThatThrowsClearsTheBar() = runTest {
        val progress = IndexProgressHolder()
        val indexer = MediaIndexer(
            registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
            deviceId = thisDevice,
            embeddings = InMemoryEmbeddingStore(),
            conditions = IndexingConditions { true },
            progress = progress,
        )
        val proxy = proxy().apply { consent(InferenceCapability.CONTENT_TAGGING) }

        assertFailsWith<IllegalStateException> {
            indexer.index(
                proxy.executionContext,
                listOf(IndexableMedia("/a.jpg") { throw IllegalStateException("disk full") }),
            )
        }

        assertNull(progress.progress.value, "a failed pass must not leave a bar running forever")
    }

    /**
     * ⚠⚠ `IndexingConditions.Never` is the fail-closed sentinel for a platform whose wiring
     * contributed nothing — and it must be closed to BOTH bars. Written as a SAM lambda it implements
     * `allowIndexing` only and inherits `allowManualIndexing`'s permissive default, so a pressed pass
     * would run a neural network over the whole library on battery, on exactly the platform that
     * forgot to answer the question.
     */
    @Test
    fun theNeverConditionsRefuseAPressedPassToo() {
        assertFalse(IndexingConditions.Never.allowIndexing())
        assertFalse(IndexingConditions.Never.allowManualIndexing())
    }
}
