package org.tenshu.inference.proxy.onnx

import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.inference.proxy.catalog.DefaultModelStore
import org.tenshu.inference.proxy.catalog.InstalledArtifactData
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.catalog.ModelStoreOwnership
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which copy of the weights a wired device loads (§22.4).
 *
 * ⚠ Over a real temp directory rather than a fake filesystem: the whole question is what `isFile`
 * says, and a fake that answers it from a map would be testing the map.
 */
class OnnxModelFilesTest {

    private val root: File = Files.createTempDirectory("onnx-model-files").toFile()
    private val modelId = VisionModel.PinnedSigLip.modelId

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /**
     * A store whose index names [at] (relative to [root]) for the vision role, and nothing else.
     *
     * ⚠ Hand-rolled rather than [InMemoryModelStore]: that one always reports `memory://` paths,
     * which is right for it and useless here — the question is what happens when the index names a
     * real file on disk.
     */
    private fun storeHolding(at: String?): ModelStore = object : ModelStore {
        override fun pathOf(modelId: String, role: String): String? =
            at.takeIf { modelId == this@OnnxModelFilesTest.modelId && role == ModelArtifactRole.VISION }

        override fun installed(): List<InstalledModelData> = emptyList()
        override fun add(entry: ModelEntryData, bytes: Map<String, ByteArray>) = null
        override fun remove(modelId: String) = Unit
        override fun beginStaging(modelId: String, role: String, expectedSha256: String) = 0L
        override fun readStaged(modelId: String, role: String, offset: Long, length: Int) = null
        override fun writeStaged(modelId: String, role: String, offset: Long, bytes: ByteArray) = Unit
        override fun commitStaged(entry: ModelEntryData, stagedBytes: Map<String, Long>) =
            error("not used")
        override fun abortStaging(modelId: String, role: String) = Unit
    }

    private fun write(relative: String): File {
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    private fun sideLoaded(): File = write("${OnnxModelFiles.DIRECTORY}/${OnnxModelFiles.fileName()}")

    @Test
    fun withAnEmptyStoreTheSideLoadedFileIsUsed() {
        val expected = sideLoaded()

        val locate = OnnxModelFiles.storeLocator(storeHolding(null), root, ModelArtifactRole.VISION)

        assertEquals(expected.absolutePath, locate())
    }

    /**
     * ⚠⚠ The precedence that matters. A side-loaded file is unverified — nothing checked its digest
     * and nothing ties it to its siblings — so it must never shadow a checkpoint the store vouches
     * for. Both exist here, and the store's copy is the one that must win.
     */
    @Test
    fun anInstalledCheckpointWinsOverASideLoadedFileOfTheSameName() {
        sideLoaded()
        val installed = write("inference/models/$modelId.vision.model")

        val locate = OnnxModelFiles.storeLocator(
            storeHolding("inference/models/$modelId.vision.model"),
            root,
            ModelArtifactRole.VISION,
        )

        assertEquals(installed.absolutePath, locate())
    }

    /**
     * ⚠ An index record whose bytes were deleted from under it must fall through rather than name a
     * file that is not there — the backend would otherwise fail to open a path we handed it.
     */
    @Test
    fun anIndexRecordWithNoBytesFallsBackToTheSideLoadedFile() {
        val expected = sideLoaded()

        val locate = OnnxModelFiles.storeLocator(
            storeHolding("inference/models/$modelId.vision.model"), // never written
            root,
            ModelArtifactRole.VISION,
        )

        assertEquals(expected.absolutePath, locate())
    }

    /**
     * A real store over an index it cannot read: JSON that is not this build's record, which is
     * the form of it Android and desktop can meet.
     */
    private fun storeWithAnIndexItCannotRead(): ModelStore {
        val delegate = InMemorySecureStorageDelegate()
        delegate.storeString("inference.models.index", """{"models":[]}""")
        return DefaultModelStore(
            delegate,
            InMemoryFileDatabase(),
            modelsDir = "inference/models",
            digestOf = { it.size.toString() },
            ownership = ModelStoreOwnership.SoleProcess,
        )
    }

    /**
     * ⚠⚠ Not the side-load path: that is for a device whose store holds no verified checkpoint,
     * and an index that cannot be read does not say so. Nothing is claimed, and nothing throws
     * out of a `supports` call.
     */
    @Test
    fun anIndexThatCannotBeReadClaimsNothingAndDoesNotFallBackToTheSideLoadedFile() {
        sideLoaded()

        val locate = OnnxModelFiles.storeLocator(storeWithAnIndexItCannotRead(), root, ModelArtifactRole.VISION)

        assertNull(locate())
    }

    @Test
    fun anIndexThatCannotBeReadClaimsNoFaceModelEither() {
        write("${OnnxModelFiles.DIRECTORY}/${OnnxModelFiles.faceFileName(face, ModelArtifactRole.FACE_DETECTOR)}")

        val locate = OnnxModelFiles.faceStoreLocator(
            storeWithAnIndexItCannotRead(), root, ModelArtifactRole.FACE_DETECTOR, face,
        )

        assertNull(locate())
    }

    @Test
    fun withNeitherCopyNothingIsClaimed() {
        val locate = OnnxModelFiles.storeLocator(storeHolding(null), root, ModelArtifactRole.VISION)

        assertNull(locate())
    }

    /** ⚠ Each role resolves its own file — the text tower must not answer for the vision one. */
    @Test
    fun eachRoleFallsBackToItsOwnSideLoadedName() {
        val text = write("${OnnxModelFiles.DIRECTORY}/${OnnxModelFiles.textFileName()}")
        val vocab = write("${OnnxModelFiles.DIRECTORY}/${OnnxModelFiles.vocabularyFileName()}")
        val store = storeHolding(null)

        assertEquals(
            text.absolutePath,
            OnnxModelFiles.storeLocator(store, root, ModelArtifactRole.TEXT)(),
        )
        assertEquals(
            vocab.absolutePath,
            OnnxModelFiles.storeLocator(store, root, ModelArtifactRole.VOCABULARY)(),
        )
        // ⚠ The vision tower was never written, and neither of the two above may stand in for it.
        assertNull(OnnxModelFiles.storeLocator(store, root, ModelArtifactRole.VISION)())
    }

    /**
     * ⚠ Resolved per call, not captured once: a model pushed or downloaded after the proxy started
     * must be picked up without a restart, and one deleted must stop being claimed.
     */
    @Test
    fun theLocatorReflectsAFileThatAppearsAfterItWasBuilt() {
        val locate = OnnxModelFiles.storeLocator(storeHolding(null), root, ModelArtifactRole.VISION)
        assertNull(locate())

        val arrived = sideLoaded()

        assertEquals(arrived.absolutePath, locate())
    }

    // ---- the face pair --------------------------------------------------------------------------

    private val face = FaceModel.PinnedAuraFace

    /** A store whose index holds the face pair at [version], its embedder at [embedderAt]. */
    private fun faceStore(version: Int, embedderAt: String): ModelStore {
        val record = InstalledModelData(
            modelId = face.modelId,
            modelVersion = version,
            artifacts = listOf(InstalledArtifactData(ModelArtifactRole.FACE_EMBEDDER, embedderAt, 3, "00")),
            labelSetVersion = 0,
            licence = "Apache-2.0",
        )
        val base = storeHolding(null)
        return object : ModelStore by base {
            override fun installed(): List<InstalledModelData> = listOf(record)
            override fun pathOf(modelId: String, role: String): String? =
                record.pathOf(role).takeIf { modelId == face.modelId }
        }
    }

    @Test
    fun theInstalledFaceEmbedderIsLocatedWhenItsVersionIsThePinnedOne() {
        val installed = write("inference/models/${face.modelId}.faceEmbedder.model")

        val locate = OnnxModelFiles.faceStoreLocator(
            faceStore(face.modelVersion, "inference/models/${face.modelId}.faceEmbedder.model"),
            root, ModelArtifactRole.FACE_EMBEDDER, face,
        )

        assertEquals(installed.absolutePath, locate())
    }

    /**
     * ⚠⚠ An installed version this build did not pin is REFUSED — loading it under the pinned
     * stamp would mix two embedding spaces in one template store. ⚠ Refused outright, not sent to
     * the side-load path: the store's record is the truth about what this device holds under that
     * id.
     */
    @Test
    fun anInstalledFaceModelOfAnotherVersionIsRefused() {
        write("inference/models/${face.modelId}.faceEmbedder.model")
        write("${OnnxModelFiles.DIRECTORY}/${OnnxModelFiles.faceFileName(face, ModelArtifactRole.FACE_EMBEDDER)}")

        val locate = OnnxModelFiles.faceStoreLocator(
            faceStore(face.modelVersion + 1, "inference/models/${face.modelId}.faceEmbedder.model"),
            root, ModelArtifactRole.FACE_EMBEDDER, face,
        )

        assertNull(locate())
    }

    @Test
    fun withNoInstalledFaceModelTheSideLoadedPairIsUsed() {
        val detector = write(
            "${OnnxModelFiles.DIRECTORY}/${OnnxModelFiles.faceFileName(face, ModelArtifactRole.FACE_DETECTOR)}",
        )

        val locate = OnnxModelFiles.faceStoreLocator(storeHolding(null), root, ModelArtifactRole.FACE_DETECTOR, face)

        assertEquals(detector.absolutePath, locate())
    }

    /**
     * ⚠⚠ A file whose size is not the record's — truncated or replaced under it — is refused,
     * never loaded under the record's stamp.
     */
    @Test
    fun aFaceFileWhoseSizeIsNotTheRecordsIsRefused() {
        val file = write("inference/models/${face.modelId}.faceEmbedder.model")
        file.writeBytes(ByteArray(7)) // the record says 3

        val locate = OnnxModelFiles.faceStoreLocator(
            faceStore(face.modelVersion, "inference/models/${face.modelId}.faceEmbedder.model"),
            root, ModelArtifactRole.FACE_EMBEDDER, face,
        )

        assertNull(locate())
    }
}
