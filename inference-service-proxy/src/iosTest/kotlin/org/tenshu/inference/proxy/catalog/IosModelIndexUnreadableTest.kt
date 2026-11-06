package org.tenshu.inference.proxy.catalog

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.AiModelFacts
import org.tenshu.inference.proxy.coreml.CoreMlModelCache
import org.tenshu.inference.proxy.coreml.coreMlModelFacts
import org.tenshu.inference.proxy.onnx.VisionModel
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.posix.chmod
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The iOS owner's store over the **real** [IosFileDatabase], with Foundation itself failing the
 * read (`ios-on-device-inference.md` §13.7).
 *
 * ⚠ The index file's permissions are taken away, which is how this suite makes
 * `NSData.dataWithContentsOfFile` answer `nil` for a file a stat still finds. On a phone the same
 * answer comes from a file still under data protection before the first unlock; no case here
 * reproduces *that*.
 */
@OptIn(ExperimentalForeignApi::class)
class IosModelIndexUnreadableTest {

    private val root: String = "${NSTemporaryDirectory()}model-index-unreadable-${Random.nextLong()}"
    private val files = IosFileDatabase(root)
    private val modelId = VisionModel.PinnedSigLip.modelId
    private val role = ModelArtifactRole.VISION
    private val bytes = "WEIGHTS".encodeToByteArray()
    private val indexFile = "$root/$DEFAULT_MODELS_DIR/inference.models.index.json"

    private fun digest(value: ByteArray) = value.decodeToString().hashCode().toString()

    private fun store() = DefaultModelStore(
        FileModelIndexStorage(files),
        files,
        modelsDir = DEFAULT_MODELS_DIR,
        digestOf = ::digest,
        ownership = ModelStoreOwnership.SoleProcess,
    )

    private val entry = ModelEntryData(
        modelId = modelId,
        name = "SigLIP",
        modelVersion = 1,
        capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        labelSetVersion = 1,
        runtime = "coreml",
        artifactVersion = 1,
        artifacts = listOf(ModelArtifactData(role, "models/siglip", digest(bytes), bytes.size.toLong())),
        licence = "Apache-2.0",
    )

    @AfterTest
    fun cleanUp() {
        chmod(indexFile, 0x180u) // 0600
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun anIndexFileFoundationCannotReadIsUnreadableAndTheModelSurvivesIt() {
        val path = assertNotNull(store().add(entry, mapOf(role to bytes))).pathOf(role)!!
        val index = assertNotNull(files.readBytes("$DEFAULT_MODELS_DIR/inference.models.index.json")).get()
        assertEquals(0, chmod(indexFile, 0u))
        // The premise, on this database: no bytes, and a stat that still finds the file.
        assertNull(files.readBytes("$DEFAULT_MODELS_DIR/inference.models.index.json"))
        assertEquals(index.size.toLong(), files.fileSize("$DEFAULT_MODELS_DIR/inference.models.index.json"))

        val next = store()
        assertFailsWith<ModelIndexUnreadableException> { next.installed() }
        assertFailsWith<ModelIndexUnreadableException> { next.remove(modelId) }
        // ⚠ What readiness is handed: a throw, which it reads as *unreadable*, not `NoModel`.
        assertFailsWith<ModelIndexUnreadableException> { coreMlModelFacts(next, CoreMlModelCache(files, root), files) }
        assertContentEquals(bytes, files.readBytes(path)?.get(), "the model was swept on an index that was not read")

        assertEquals(0, chmod(indexFile, 0x180u))
        assertContentEquals(index, files.readBytes("$DEFAULT_MODELS_DIR/inference.models.index.json")?.get())
        assertEquals(listOf(modelId), next.installed().map { it.modelId })
        assertEquals(AiModelFacts(installed = true, prepared = null), coreMlModelFacts(next, CoreMlModelCache(files, root), files))
        assertContentEquals(bytes, files.readBytes(path)?.get())
    }
}
