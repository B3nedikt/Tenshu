package org.tenshu.inference.proxy.catalog

import kotlinx.serialization.json.Json
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.models.ModelCatalogBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tracked manifest's Core ML entry, and what the builds already in users' hands do with a
 * catalog that carries it (`ios-on-device-inference.md` D13, *As built — PR-2b*).
 *
 * ⚠⚠ **The failure this exists for costs a user ~740 MB or their search, and is silent.** The
 * Core ML entry has the ONNX entry's id. A build that kept both would install whichever came first
 * under that id; on Android or desktop that could be two `.mlpackage` archives ONNX Runtime cannot
 * open, recorded as an installed model.
 */
class CoreMlCatalogEntryTest {

    // ⚠ The signer's own strict parse: a misspelt key in the tracked manifest fails here.
    private val manifest: ModelCatalogBuilder.ManifestJson =
        ModelCatalogBuilder.parseManifest(File(repoRoot(), "deploy/models/models.json").readText())
    private val siglip = manifest.models.filter { it.id == VisionModel.PinnedSigLip.modelId }
    private val onnx = siglip.single { it.runtime == "onnx" }
    private val coreml = siglip.single { it.runtime == "coreml" }

    @Test
    fun theManifestPassesTheSignersOwnChecks() {
        ModelCatalogBuilder.checkManifest(manifest)
    }

    @Test
    fun itIsTheSameCheckpointAsTheOnnxEntryUnderAnotherRuntime() {
        assertEquals(2, siglip.size, "one entry per runtime, and two runtimes")
        assertEquals(VisionModel.PinnedSigLip.modelVersion, coreml.version)
        assertEquals(onnx.version, coreml.version)
        assertEquals(onnx.labelSetVersion, coreml.labelSetVersion)
        assertEquals(onnx.capabilities, coreml.capabilities)
        assertEquals(onnx.name, coreml.name)
        assertEquals(onnx.licence, coreml.licence)
        assertEquals(onnx.artifactVersion, coreml.artifactVersion)
    }

    @Test
    fun itCarriesTheThreeRolesAndSharesTheVocabularysPath() {
        assertEquals(
            listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT, ModelArtifactRole.VOCABULARY),
            coreml.artifacts.map { it.role },
        )
        fun path(model: ModelCatalogBuilder.ManifestModel, role: String) = model.artifacts.single { it.role == role }.path
        // ⚠ One path means one object on the CDN and one digest in both entries. A second copy
        // under `coreml/` would be a second thing that could differ from the first.
        assertEquals(path(onnx, ModelArtifactRole.VOCABULARY), path(coreml, ModelArtifactRole.VOCABULARY))
        // ⚠ And the towers must NOT share a path with anything: artifacts are served immutable.
        val onnxPaths = onnx.artifacts.map { it.path }.toSet()
        listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT).forEach { role ->
            val towerPath = path(coreml, role)
            assertTrue(towerPath !in onnxPaths, towerPath)
            assertTrue(towerPath.startsWith("${coreml.id}/${coreml.version}/coreml/"), towerPath)
            assertTrue(towerPath.endsWith(".mlpackage.tar"), towerPath)
        }
    }

    /**
     * ⚠⚠ Every Core ML artifact is pinned. Two exports of one checkpoint have different archive
     * digests and only one passed the retrieval gate; an unpinned artifact lets the other be signed.
     */
    @Test
    fun everyArtifactIsPinnedToTheGatedExport() {
        coreml.artifacts.forEach { artifact ->
            assertTrue(Regex("[0-9a-f]{64}").matches(artifact.sha256), "${artifact.path} has no sha256 pin")
            assertTrue((artifact.byteSize ?: 0) > 0, "${artifact.path} has no byteSize pin")
        }
    }

    /**
     * ⚠ The Core ML entry names its upstream snapshot; the ONNX entry does not, and must not gain
     * one by copy: nothing records which snapshot the published ONNX files were exported from.
     */
    @Test
    fun onlyTheCoreMlEntryStatesASource() {
        val source = assertNotNull(coreml.source)
        assertEquals("google/siglip-base-patch16-256-multilingual", source.repo)
        assertTrue(source.files.containsKey("model.safetensors"))
        assertNotNull(source.toolchain)
        assertNull(onnx.source, "nothing proves which snapshot the ONNX files came from")
    }

    /**
     * ⚠⚠ The tracked manifest through the shipped filter, with the runtime sets the shipped builds
     * contribute. One entry per id on each, and never the Core ML one off iOS.
     */
    @Test
    fun shippedBuildsSeeTheOnnxEntryAndOnlyPhotosOnIosSeesThisOne() {
        val entries = manifest.models.map { model ->
            ModelEntryData(
                modelId = model.id, name = model.name, modelVersion = model.version,
                capabilities = listOf(org.tenshu.inference.InferenceCapability.SEMANTIC_IMAGE_SEARCH),
                labelSetVersion = model.labelSetVersion, runtime = model.runtime,
                artifactVersion = model.artifactVersion,
                artifacts = model.artifacts.map { ModelArtifactData(it.role, it.path, "00", 1) },
                licence = model.licence,
            )
        }
        val id = VisionModel.PinnedSigLip.modelId
        val desktop = usableModels(entries, supportedRuntimes = SUPPORTED_RUNTIMES)
        val android = usableModels(entries, supportedRuntimes = SUPPORTED_RUNTIMES + FaceModel.RUNTIME)
        val ios = usableModels(entries, supportedRuntimes = setOf("coreml"))

        assertEquals(listOf(id to "onnx"), desktop.map { it.modelId to it.runtime })
        assertEquals(listOf("onnx"), android.filter { it.modelId == id }.map { it.runtime })
        assertEquals(listOf(id to "coreml"), ios.map { it.modelId to it.runtime })
        // ⚠ The build claim itself. If `SUPPORTED_RUNTIMES` ever gains `coreml`, Android and
        // desktop keep both entries for one id, and the two assertions above say so first.
        assertTrue("coreml" !in SUPPORTED_RUNTIMES + FaceModel.RUNTIME)
    }

    /**
     * ⚠⚠ **The catalog every shipped build has already read**, byte for byte as served on
     * 2026-10-07 (built 2026-09-25, two entries, no `source`), under the pinned key and the real
     * Ed25519. It must keep verifying and parsing: it is the old shape, and it is also the proof
     * that the pin in this tree is the key the published catalog was signed with.
     */
    @Test
    fun thePublishedCatalogStillVerifiesAndParses() {
        val bytes = resource("catalog.json")
        val signature = resource("catalog.json.sig")
        val ed25519 = BouncyCastleEd25519Backend()
        fun seenBy(runtimes: Set<String>) = ModelCatalogVerifier(
            verifier = { key, message, sig -> ed25519.verify(key, message, sig) },
            supportedRuntimes = runtimes,
        ).verifyAndParse(bytes, signature)

        val android = assertNotNull(seenBy(SUPPORTED_RUNTIMES + FaceModel.RUNTIME), "the pinned key must accept it")
        assertEquals("2026-09-25", android.buildDate)
        assertEquals(
            listOf(VisionModel.PinnedSigLip.modelId to "onnx", FaceModel.PinnedAuraFace.modelId to FaceModel.RUNTIME),
            android.models.map { it.modelId to it.runtime },
        )
        // ⚠ The vocabulary the Core ML entry reuses is this object: same path, same digest, same size.
        val published = assertNotNull(android.models.first().artifact(ModelArtifactRole.VOCABULARY))
        val reused = coreml.artifacts.single { it.role == ModelArtifactRole.VOCABULARY }
        assertEquals(published.path, reused.path)
        assertEquals(published.sha256, reused.sha256)
        assertEquals(published.byteSize, reused.byteSize)
        // A Core ML build reading the catalog as it is today is offered nothing, not the ONNX entry.
        assertEquals(emptyList(), assertNotNull(seenBy(setOf("coreml"))).models)
    }

    private fun resource(name: String): ByteArray = checkNotNull(
        javaClass.getResourceAsStream("/catalog/published-2026-09-25/$name")
    ) { "missing test resource $name" }.use { it.readBytes() }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }
    }
}
