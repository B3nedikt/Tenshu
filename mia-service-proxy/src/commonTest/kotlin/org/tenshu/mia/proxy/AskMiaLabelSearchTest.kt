package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.file.File
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.FakeInferenceBackend
import org.tenshu.inference.proxy.ImageTag
import org.tenshu.inference.proxy.IndexableMedia
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.MediaIndexer
import org.tenshu.inference.proxy.vocabulary.LabelTier
import org.tenshu.inference.proxy.vocabulary.VocabularyLabel
import org.tenshu.inference.proxy.vocabulary.vocabularyOf
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.MiaSource
import org.tenshu.mia.askMia
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **"Show me photos of a cat"** (IN-4) — the whole way through, over labels the **fake** produced.
 *
 * ⭐⭐ This is what IN-1's `FakeInferenceBackend` was for: an index pass, real `MediaLabel` rows, a
 * real chain, a real validator, real citations — and **no model, no runtime, no hardware**. When
 * IN-3b binds ONNX Runtime, only the backend changes; nothing in this path does.
 *
 * ⭐ IN-D12's claim, exercised: label search is an ordinary MQ-2 source adapter that lowers to
 * `filter { contains }`. The similarity search it distinguishes itself from — nearest-neighbour
 * over embeddings — still needs an op, and is not this.
 */
class AskMiaLabelSearchTest {

    private val now = 1_784_116_800_000L
    private val zone = "UTC"
    private val thisDevice = "device-a"
    private val capability = InferenceCapability.CONTENT_TAGGING

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, zone)
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun MiaAnswer.hitList(): List<MiaHit> = hits.toList().filterIsInstance<MiaHit>()

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

    /** Runs a real index pass, so the rows under test are the ones IN-3a actually writes. */
    private suspend fun TestProxy.index(vararg photos: Pair<String, List<ImageTag>>) {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
        // ⚠⚠ The photographs exist. Mia cites a label only once its file is live (P0g,
        // `mia-query-layer.md` §18), so a label over a path with no `File` row is exactly the
        // stale row it now refuses to cite.
        fileService().defaultFileSystem.createFolder("/photos")
        photos.forEach { (name, tags) ->
            assertTrue(
                fileService().defaultFileSystem.uploadFile("/photos", name, "bytes of $name".encodeToByteArray())
                    is TResult.Success,
                "seeding /photos/$name should succeed",
            )
            MediaIndexer(
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend(tags = tags))),
                deviceId = thisDevice,
                // ⚠⚠ A vocabulary is required for any label to be written (IN-D9 / §27): the empty
                // default emits nothing however confident the model is, because an uncalibrated
                // label is not better than no label. Built from this test's own tags so the fixture
                // keeps saying what it always said — the subject here is Mia's label SEARCH, not
                // the selection rule, which `LabelSelectionTest` owns.
                vocabulary = vocabularyOf(
                    version = 1,
                    labels = tags.map { VocabularyLabel(it.label, LabelTier.TOP, floor = 0f) },
                ),
                currentProvenance = { ModelProvenance("fake", 1) },
                conditions = IndexingConditions { true },
            ).index(
                executionContext,
                listOf(IndexableMedia("/photos/$name") { "bytes of $name".encodeToByteArray() }),
                capability,
            )
        }
    }

    @Test
    fun photosOfACatFindsTheCat() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.index(
            "tabby.jpg" to listOf(ImageTag("cat", 0.9f)),
            "spaniel.jpg" to listOf(ImageTag("dog", 0.8f)),
        )

        val answer = ask(proxy, "show me photos of a cat")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf("tabby.jpg"), answer.hitList().map { it.title.get() })
        // ⚠⚠ Cited as the FILE it is about, never as a label row: the user asked about photographs,
        // and a "MediaLabel" among the results would be showing our working.
        assertEquals(File.ID, answer.hitList().single().targetType)
        assertEquals("cat", answer.hitList().single().subtitle.get())
    }

    @Test
    fun ofIsWhatSeparatesContentFromPlaceAndTime() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.index("tabby.jpg" to listOf(ImageTag("cat", 0.9f)))

        // ⚠⚠ Three prepositions, three intents: "of" is content, "in/from" is place, a time word is
        // time. Guessing between them without one is the confidently-wrong failure MQ-D4 avoids.
        assertEquals(listOf("tabby.jpg"), ask(proxy, "pictures of cat").hitList().map { it.title.get() })
        assertTrue(
            ask(proxy, "pictures from munich").hitList().isEmpty(),
            "a place question must not be answered from labels",
        )
    }

    @Test
    fun aLabelNobodyAppliedFindsNothing_ratherThanEverything() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.index("tabby.jpg" to listOf(ImageTag("cat", 0.9f)))

        val answer = ask(proxy, "photos of a giraffe")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(emptyList(), answer.hitList())
    }

    @Test
    fun theIndexMarkerIsNeverACitation() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.index("tabby.jpg" to emptyList())

        // ⚠⚠ IN-3a writes a `__indexed` marker row per file so an opinion-less photo is not
        // re-read forever. It is machinery, and a user searching for anything must never see it —
        // it only surfaces if someone searches for its literal name, which is why it is prefixed.
        assertEquals(emptyList(), ask(proxy, "photos of indexed").hitList())
    }

    @Test
    fun revokingTheLabelGrantRefusesByName() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.index("tabby.jpg" to listOf(ImageTag("cat", 0.9f)))
        proxy.write(MiaSource.LABELS.preference, false)

        val answer = ask(proxy, "photos of a cat")

        // ⚠ MQ-D6: a revoked source drops out of planning and Mia SAYS WHICH — "I can't answer
        // that" would be indistinguishable from not understanding.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertTrue(answer.explanation.get().contains("photo contents"), answer.explanation.get())
    }

    @Test
    fun theLabelGrantIsSeparateFromTheFilesGrant() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.index("tabby.jpg" to listOf(ImageTag("cat", 0.9f)))
        proxy.write(MiaSource.FILES.preference, false)

        // ⚠⚠ "Mia may read my file names" and "Mia may read what a model thinks is IN my photos"
        // are different permissions. Folding them into one would have made enabling either enable
        // both, silently.
        assertEquals(listOf("tabby.jpg"), ask(proxy, "photos of a cat").hitList().map { it.title.get() })
    }
}
