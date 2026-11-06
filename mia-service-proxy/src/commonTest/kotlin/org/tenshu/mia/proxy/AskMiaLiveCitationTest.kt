package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Traverse
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.TRASH_ROOT
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
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
import org.tenshu.mia.askMia
import org.tenshu.mia.plan.QueryPlanner
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **P0g — Mia never cites a photograph that is deleted or in the trash** (`mia-query-layer.md` §18).
 *
 * ⚠⚠ Every fixture here is real: files go through the file service's own upload / move / delete
 * ops, labels through a real `MediaIndexer` pass (or the row `nameFaceGroup` writes), vectors into
 * the harness's real `EmbeddingStore`, and the answer through the real chain, validator and store.
 * The rows that go stale are therefore exactly the rows production leaves behind: nothing deletes a
 * `MediaLabel` or a vector when its file goes.
 */
class AskMiaLiveCitationTest {

    private val now = 1_784_116_800_000L
    private val thisDevice = "device-a"

    private suspend fun TestProxy.ask(utterance: String): MiaAnswer {
        val result = miaService().askMia(utterance, now, "UTC")
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun MiaAnswer.paths(): List<String> =
        hits.toList().filterIsInstance<MiaHit>().map { it.targetKey }

    private fun <T> TestProxy.write(preference: Preference<T>, value: T, deviceId: String? = null) {
        executionContext.saveData(
            if (deviceId == null) {
                RealmSetting.create(
                    context = tContext,
                    preferenceId = preference.id,
                    value = preference.adapter.toData(value, tContext),
                )
            } else {
                DeviceSetting.create(
                    context = tContext,
                    deviceId = deviceId,
                    preferenceId = preference.id,
                    value = preference.adapter.toData(value, tContext),
                )
            },
        )
    }

    private suspend fun TestProxy.upload(name: String): String {
        assertTrue(
            fileService().defaultFileSystem.uploadFile("/", name, "bytes of $name".encodeToByteArray())
                is TResult.Success,
            "seeding /$name should succeed",
        )
        return "/$name"
    }

    /** Uploads [name] and runs a real content-tagging pass over it. */
    private suspend fun TestProxy.uploadTagged(name: String, vararg tags: String): String {
        val path = upload(name)
        val capability = InferenceCapability.CONTENT_TAGGING
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
        val imageTags = tags.map { ImageTag(it, 0.9f) }
        MediaIndexer(
            registry = InferenceBackendRegistry(listOf(FakeInferenceBackend(tags = imageTags))),
            deviceId = thisDevice,
            vocabulary = vocabularyOf(
                version = 1,
                labels = imageTags.map { VocabularyLabel(it.label, LabelTier.TOP, floor = 0f) },
            ),
            currentProvenance = { ModelProvenance("fake", 1) },
            conditions = IndexingConditions { true },
        ).index(
            executionContext,
            listOf(IndexableMedia(path) { "bytes of $name".encodeToByteArray() }),
            capability,
        )
        return path
    }

    /** The row `nameFaceGroup` writes when a person types a name (IN-D5): user-authored. */
    private fun TestProxy.nameByHand(path: String, name: String) {
        executionContext.saveData(
            Entity.create<MediaLabel>(
                context = tContext,
                composite = MediaLabel.create(
                    context = tContext,
                    filePath = path,
                    capability = InferenceCapability.FACE_CLUSTERING,
                    label = name,
                    confidencePerMille = 1_000,
                    producedBy = ModelProvenance.User,
                ),
            ),
        )
    }

    /**
     * Uploads [name] and seeds the vector of [phrase] for it, exactly as an index pass would (MQ-4's
     * fixture). ⚠ Two files may share a phrase so both clear `photosLike`'s relevance floor: the fake
     * embeds by hash, and two different phrases are unrelated vectors.
     */
    private suspend fun TestProxy.uploadEmbedded(name: String, phrase: String = SUNSET): String {
        val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = MIA_TEST_DEVICE)
        val path = upload(name)
        val embedding = FakeInferenceBackend().embedImage(phrase.encodeToByteArray())!!
        miaEmbeddings.put(path, embedding.vector, embedding.producedBy)
        return path
    }

    /** Photos' own trash: a MOVE to `/Trash/<id>-<name>` (`TrashRoot.kt`). Returns the trash path. */
    private suspend fun TestProxy.trash(path: String): String {
        fileService().defaultFileSystem.createFolder(TRASH_ROOT)
        val trashPath = "$TRASH_ROOT/c1-${path.substringAfterLast('/')}"
        assertTrue(fileService().defaultFileSystem.moveFile(path, trashPath) is TResult.Success, "trash $path")
        return trashPath
    }

    private suspend fun TestProxy.restore(trashPath: String, path: String) {
        assertTrue(fileService().defaultFileSystem.moveFile(trashPath, path) is TResult.Success, "restore $path")
    }

    private suspend fun TestProxy.delete(path: String) {
        assertTrue(fileService().defaultFileSystem.deleteFile(path) is TResult.Success, "delete $path")
    }

    // ── labels ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aLabelOnADeletedPhotoIsNotCited_butItsLiveNeighbourStillIs() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val gone = proxy.uploadTagged("gone-cat.jpg", "cat")
        val kept = proxy.uploadTagged("kept-cat.jpg", "cat")
        proxy.delete(gone)

        val answer = proxy.ask("show me photos of a cat")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf(kept), answer.paths())
    }

    @Test
    fun aLabelOnATrashedPhotoIsNotCited_untilItIsRestored() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val photo = proxy.uploadTagged("tabby.jpg", "cat")
        val trashPath = proxy.trash(photo)

        val whileTrashed = proxy.ask("show me photos of a cat")
        assertEquals(MiaAnswer.VERDICT_ANSWERED, whileTrashed.verdict.get())
        assertEquals(emptyList(), whileTrashed.paths())

        // ⚠⚠ No re-index: the label row was never touched, so the citation comes back on the read.
        proxy.restore(trashPath, photo)
        assertEquals(listOf(photo), proxy.ask("show me photos of a cat").paths())
    }

    @Test
    fun aLabelWhosePathIsInsideTheTrashIsNotCited() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        // A row keyed on the trash path itself — `/Trash/…` HAS a `File` row, so existence alone
        // would cite it; the trash check is what stops it.
        val photo = proxy.upload("tabby.jpg")
        val trashPath = proxy.trash(photo)
        proxy.nameByHand(trashPath, "Whiskers")

        assertEquals(emptyList(), proxy.ask("photos of Whiskers").paths())
    }

    @Test
    fun aNameTheUserTypedForADeletedPhotoIsNotCited() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val gone = proxy.upload("anna-beach.jpg")
        val kept = proxy.upload("anna-garden.jpg")
        proxy.nameByHand(gone, "Anna")
        proxy.nameByHand(kept, "Anna")
        proxy.delete(gone)

        val answer = proxy.ask("photos of Anna")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf(kept), answer.paths())
    }

    @Test
    fun staleRowsRankedFirstDoNotHideALiveOne() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        // ⚠⚠ A full page of stale rows ahead of the live one: the chain's `take` counts ROWS, so
        // without widening every slot is spent on a photo that is gone and the live one never
        // arrives — an empty answer for a question that has one.
        val stale = (1..QueryPlanner.DEFAULT_LIMIT).map { proxy.uploadTagged("old-$it.jpg", "cat") }
        val live = proxy.uploadTagged("live.jpg", "cat")
        stale.forEach { proxy.delete(it) }

        val answer = proxy.ask("show me photos of a cat")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf(live), answer.paths())
    }

    @Test
    fun aFailedExistenceReadIsUnavailable_neverNothingMatched() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.uploadTagged("tabby.jpg", "cat")
        // ⚠ The existence read is the only chain on this turn that walks the file tree, so a
        // failing walk fails exactly that read and nothing else.
        proxy.additionalImplementations[Traverse.ID] = FailingOperation

        val answer = proxy.ask("show me photos of a cat")

        // ⚠⚠ §14.1: an empty ANSWERED renders as "nothing matched", which is the lie in the one
        // direction that matters — and citing the unverified row would put the dead target back.
        assertNotEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(MiaAnswer.VERDICT_UNAVAILABLE, answer.verdict.get())
        assertEquals(emptyList(), answer.paths())
    }

    // ── visual search ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aSimilarPhotoThatWasDeletedIsNotCited_butItsLiveNeighbourStillIs() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val gone = proxy.uploadEmbedded("gone-sunset.jpg")
        val kept = proxy.uploadEmbedded("kept-sunset.jpg")
        proxy.delete(gone)

        val answer = proxy.ask("photos like a sunset over water")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf(kept), answer.paths())
    }

    @Test
    fun aSimilarPhotoInTheTrashIsNotCited_untilItIsRestored() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        val photo = proxy.uploadEmbedded("sunset.jpg")
        val trashPath = proxy.trash(photo)

        val whileTrashed = proxy.ask("photos like a sunset over water")
        assertEquals(MiaAnswer.VERDICT_ANSWERED, whileTrashed.verdict.get())
        assertEquals(emptyList(), whileTrashed.paths())

        proxy.restore(trashPath, photo)
        assertEquals(listOf(photo), proxy.ask("photos like a sunset over water").paths())
    }

    private companion object {
        /** The planner's residue for "photos like a sunset over water" — what reaches the op. */
        const val SUNSET = "sunset over water"
    }

    private object FailingOperation : AsyncOperationImplementation {
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T = error("the file tree could not be walked")
    }
}
