package org.tenshu.inference.proxy.catalog

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.inference.InferenceCapability
import org.tenshu.models.ModelCatalogBuilder
import org.tenshu.models.ModelSigner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The build tool and the client, end to end.** `:model-signer` writes `catalog.json` + its
 * detached signature; this module reads and verifies them. Nothing else checks that those two
 * agree — the tool mirrors the client's wire shape in its own `@Serializable` classes because the
 * client's are `private`, and `ModelCatalog`'s parser sets `ignoreUnknownKeys = true`, so a
 * renamed key would be **silently dropped** and surface as an empty or half-populated catalog on
 * every device rather than as a build failure.
 *
 * ⚠ Digests and sizes are asserted against the real bytes on disk, never constants: the reason the
 * tool computes them is that a hand-written digest goes stale, and a test carrying one would go
 * stale the same way.
 */
class SignedCatalogRoundTripTest {

    @Test
    fun theToolsOutputParsesAndVerifiesWithTheClient() {
        val root = tempDir()
        val vision = File(root, "vision.onnx").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val vocab = File(root, "vocab.bin").apply { writeBytes(ByteArray(512) { (it % 31).toByte() }) }

        val manifest = ModelCatalogBuilder.ManifestJson(
            schemaVersion = SUPPORTED_CATALOG_SCHEMA_VERSION,
            models = listOf(
                ModelCatalogBuilder.ManifestModel(
                    id = "siglip-base-patch16",
                    name = "SigLIP base",
                    version = 3,
                    capabilities = listOf("contentTagging"),
                    labelSetVersion = 2,
                    runtime = "onnx",
                    artifactVersion = SUPPORTED_ARTIFACT_VERSION,
                    licence = "Apache-2.0",
                    artifacts = listOf(
                        ModelCatalogBuilder.ManifestArtifact(ModelArtifactRole.VISION, "vision.onnx"),
                        ModelCatalogBuilder.ManifestArtifact(ModelArtifactRole.VOCABULARY, "vocab.bin"),
                    ),
                )
            ),
        )

        val json = ModelCatalogBuilder(root).build(manifest, buildDate = "2026-09-06")

        val signer = ModelSigner()
        val keypair = signer.generateKeypair()
        val signature = signer.sign(json.toByteArray(), keypair.privateKeySeed)
        val ed25519 = BouncyCastleEd25519Backend()
        val verifier = ModelCatalogVerifier(
            verifier = { key, message, sig -> ed25519.verify(key, message, sig) },
            publicKeyHex = keypair.publicKey.joinToString("") { "%02x".format(it) },
            // ⚠ Stated, not inherited: `SUPPORTED_RUNTIMES` is a per-build claim, and a test that
            // read it would assert something different on an iOS build than on this one.
            supportedRuntimes = setOf("onnx"),
        )

        // ⚠⚠ One call covers the whole contract: signature, parse, and the runtime filter. An
        // entry this build could not execute would be dropped here, so reaching the assertions
        // below also proves the tool wrote a `runtime` the client recognises.
        val parsed = assertNotNull(
            verifier.verifyAndParse(json.toByteArray(), signature),
            "the client must accept and parse a catalog signed by the tool",
        )
        assertEquals(SUPPORTED_CATALOG_SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals("2026-09-06", parsed.buildDate)
        val entry = parsed.models.single()
        assertEquals("siglip-base-patch16", entry.modelId)
        assertEquals("SigLIP base", entry.name)
        assertEquals("onnx", entry.runtime)
        assertEquals("Apache-2.0", entry.licence)

        // ⚠⚠ Every shared field, and each fixture value is deliberately NOT the parser's default.
        // A rename on either side makes the consumer fall back to that default, so a field whose
        // fixture happens to equal it round-trips "successfully" while carrying nothing. That is
        // how `labelSetVersion` sat here unasserted: `2` was written, `0` would have been read,
        // and the test was green either way.
        assertEquals(3, entry.modelVersion)
        assertEquals(2, entry.labelSetVersion)
        assertEquals(listOf(InferenceCapability.CONTENT_TAGGING), entry.capabilities)

        val visionArtifact = assertNotNull(entry.artifacts.firstOrNull { it.role == ModelArtifactRole.VISION })
        assertEquals(vision.length(), visionArtifact.byteSize)
        assertEquals(sha256Hex(vision), visionArtifact.sha256)
        val vocabArtifact = assertNotNull(entry.artifacts.firstOrNull { it.role == ModelArtifactRole.VOCABULARY })
        assertEquals(vocab.length(), vocabArtifact.byteSize)
        assertEquals(sha256Hex(vocab), vocabArtifact.sha256)

        // ⚠⚠ And refuse an edited one — otherwise the assertions above only prove the bytes
        // round-tripped, not that anything was actually checked.
        assertNull(
            verifier.verifyAndParse(json.replace("\"version\": 3", "\"version\": 4").toByteArray(), signature),
            "a catalog edited after signing must be refused",
        )
    }

    /**
     * ⚠⚠ The one shared field a value assertion **cannot** pin, pinned through the filter instead.
     *
     * Both sides default `artifactVersion` to `1` and [SUPPORTED_ARTIFACT_VERSION] is `1`, so a
     * realistic fixture carries the default: rename the key on either side and the consumer still
     * reads `1`, and every equality assertion still passes. The drop is the detector — an entry
     * above this build's ceiling must disappear, which it can only do if the consumer actually
     * read the key.
     *
     * ⚠ So this is not primarily a test of the ceiling (`ModelCatalogTest` covers that over a
     * literal JSON string). It is here, over the **tool's own output**, because the key it pins is
     * one the tool writes.
     */
    @Test
    fun anArtifactVersionAboveThisBuildsCeilingIsDroppedFromTheToolsOwnOutput() {
        val root = tempDir()
        File(root, "vision.onnx").writeBytes(ByteArray(64))
        val manifest = ModelCatalogBuilder.ManifestJson(
            schemaVersion = SUPPORTED_CATALOG_SCHEMA_VERSION,
            models = listOf(
                ModelCatalogBuilder.ManifestModel(
                    id = "too-new", version = 1, runtime = "onnx",
                    capabilities = listOf("contentTagging"),
                    artifactVersion = SUPPORTED_ARTIFACT_VERSION + 1,
                    artifacts = listOf(ModelCatalogBuilder.ManifestArtifact(ModelArtifactRole.VISION, "vision.onnx")),
                )
            ),
        )
        val json = ModelCatalogBuilder(root).build(manifest, buildDate = "2026-09-06")

        val signer = ModelSigner()
        val keypair = signer.generateKeypair()
        val signature = signer.sign(json.toByteArray(), keypair.privateKeySeed)
        val ed25519 = BouncyCastleEd25519Backend()
        val verifier = ModelCatalogVerifier(
            verifier = { key, message, sig -> ed25519.verify(key, message, sig) },
            publicKeyHex = keypair.publicKey.joinToString("") { "%02x".format(it) },
            supportedRuntimes = setOf("onnx"),
        )

        val parsed = assertNotNull(
            verifier.verifyAndParse(json.toByteArray(), signature),
            "the catalog itself is well-formed — only the entry is too new",
        )
        assertTrue(
            parsed.models.isEmpty(),
            "an entry whose artifactVersion exceeds this build must be dropped; it survived, which " +
                "means the consumer never read the key the tool wrote",
        )
    }

    /**
     * ⚠⚠ **One checkpoint, two runtimes, one id** (`ios-on-device-inference.md` D13, *As built — PR-2b*).
     *
     * The Core ML entry shares the ONNX entry's id and version. Everything downstream of the
     * verifier resolves a model by id alone (`ModelDownloader.runDownload` takes the first match,
     * `DefaultModelStore`'s index is keyed by id, the Models screen lists one row per entry), so
     * the safety of publishing it rests on one thing: a build sees **exactly one** entry per id.
     * That is the runtime filter, and this is the tool's own output put through it.
     *
     * ⚠ The Android and desktop sets are spelled as those builds contribute them
     * (`PlatformProxyModule.kt`, `named("supportedModelRuntimes")`), not read from a constant: a
     * constant that grew `coreml` would turn this green while the shipped builds stayed as they are.
     */
    @Test
    fun twoEntriesUnderOneIdAreToldApartByRuntimeAlone() {
        val root = tempDir()
        val onnxVision = File(root, "m/1/vision.onnx").writing(ByteArray(4096) { (it % 251).toByte() })
        val coremlVision = File(root, "m/1/coreml/vision.tar").writing(ByteArray(8192) { (it % 127).toByte() })
        val vocab = File(root, "m/1/vocab.bin").writing(ByteArray(512) { (it % 31).toByte() })
        File(root, "f/1/faces.onnx").writing(ByteArray(64))

        fun siglip(runtime: String, visionPath: String, source: ModelCatalogBuilder.ModelSource? = null) =
            ModelCatalogBuilder.ManifestModel(
                id = "siglip", name = "SigLIP", version = 3,
                capabilities = listOf("semanticImageSearch"), labelSetVersion = 2,
                runtime = runtime, licence = "Apache-2.0", source = source,
                artifacts = listOf(
                    ModelCatalogBuilder.ManifestArtifact(ModelArtifactRole.VISION, visionPath),
                    // ⚠ The same path in both entries: one file, one digest, uploaded once.
                    ModelCatalogBuilder.ManifestArtifact(ModelArtifactRole.VOCABULARY, "m/1/vocab.bin"),
                ),
            )
        val manifest = ModelCatalogBuilder.ManifestJson(
            schemaVersion = SUPPORTED_CATALOG_SCHEMA_VERSION,
            models = listOf(
                siglip("onnx", "m/1/vision.onnx"),
                ModelCatalogBuilder.ManifestModel(
                    id = "faces", version = 1, capabilities = listOf("faceClustering"),
                    runtime = "onnx+mediapipe",
                    artifacts = listOf(ModelCatalogBuilder.ManifestArtifact(ModelArtifactRole.FACE_EMBEDDER, "f/1/faces.onnx")),
                ),
                siglip(
                    "coreml", "m/1/coreml/vision.tar",
                    source = ModelCatalogBuilder.ModelSource(
                        repo = "example/siglip",
                        revision = "8952a4eafcde3cb7ab46b1dd629b33f8784ca9c6",
                    ),
                ),
            ),
        )

        val json = ModelCatalogBuilder(root).build(manifest, buildDate = "2026-10-07")
        val signer = ModelSigner()
        val keypair = signer.generateKeypair()
        val signature = signer.sign(json.toByteArray(), keypair.privateKeySeed)
        val ed25519 = BouncyCastleEd25519Backend()
        fun seenBy(runtimes: Set<String>) = assertNotNull(
            ModelCatalogVerifier(
                verifier = { key, message, sig -> ed25519.verify(key, message, sig) },
                publicKeyHex = keypair.publicKey.joinToString("") { "%02x".format(it) },
                supportedRuntimes = runtimes,
            ).verifyAndParse(json.toByteArray(), signature),
            "a build contributing $runtimes must accept the catalog",
        ).models

        // ⚠ The source is in the signed bytes, and no client reads it: the parse below succeeds
        // over a key the client's `ModelJson` does not have.
        assertTrue(json.contains("\"revision\": \"8952a4eafcde3cb7ab46b1dd629b33f8784ca9c6\""), json)

        // Desktop: `{"onnx"}`.
        val desktop = seenBy(setOf("onnx"))
        assertEquals(listOf("siglip" to "onnx"), desktop.map { it.modelId to it.runtime })
        assertEquals(sha256Hex(onnxVision), desktop.single().artifact(ModelArtifactRole.VISION)?.sha256)

        // Android: `{"onnx", "onnx+mediapipe"}`. ⚠ `count == 1` is the property `firstOrNull { id }`
        // and the id-keyed index rely on; the list equality above would also pass with two rows
        // for one id if both were ONNX, which the signer refuses.
        val android = seenBy(setOf("onnx", "onnx+mediapipe"))
        assertEquals(listOf("siglip" to "onnx", "faces" to "onnx+mediapipe"), android.map { it.modelId to it.runtime })
        assertEquals(1, android.count { it.modelId == "siglip" })

        // iOS Photos: `{"coreml"}`.
        val ios = seenBy(setOf("coreml"))
        assertEquals(listOf("siglip" to "coreml"), ios.map { it.modelId to it.runtime })
        assertEquals(sha256Hex(coremlVision), ios.single().artifact(ModelArtifactRole.VISION)?.sha256)
        assertEquals(coremlVision.length() + vocab.length(), ios.single().byteSize)

        // The two entries are one checkpoint, and share the vocabulary's bytes.
        assertEquals(desktop.single().modelVersion, ios.single().modelVersion)
        assertEquals(desktop.single().labelSetVersion, ios.single().labelSetVersion)
        assertEquals(
            desktop.single().artifact(ModelArtifactRole.VOCABULARY),
            ios.single().artifact(ModelArtifactRole.VOCABULARY),
        )

        // A build that contributes nothing (iOS apps other than Photos, web) is offered nothing.
        assertEquals(emptyList(), seenBy(emptySet()))
    }

    private fun File.writing(bytes: ByteArray): File = apply { parentFile.mkdirs(); writeBytes(bytes) }

    @Test
    fun aMissingArtifactFileFailsTheBuildRatherThanPublishingA404() {
        val manifest = ModelCatalogBuilder.ManifestJson(
            models = listOf(
                ModelCatalogBuilder.ManifestModel(
                    id = "m", version = 1, runtime = "onnx",
                    // ⚠ Valid on purpose — otherwise the capability guard fires first and this
                    // case passes for the wrong reason, proving nothing about missing files.
                    capabilities = listOf("contentTagging"),
                    artifacts = listOf(ModelCatalogBuilder.ManifestArtifact("vision", "absent.onnx")),
                )
            ),
        )
        val failure = runCatching { ModelCatalogBuilder(tempDir()).build(manifest, "2026-09-06") }.exceptionOrNull()
        assertNotNull(failure, "a manifest naming a file that does not exist must not sign")
        assertTrue(
            failure.message.orEmpty().contains("absent.onnx"),
            "the failure must name the missing artifact, got: ${failure.message}",
        )
    }

    /**
     * ⚠⚠ The failure this round trip existed to find. `"photoLabels"` was the first draft of the
     * case above; it parsed, verified, and then vanished — the client drops a capability name it
     * cannot resolve, and an entry left with none is filtered out by `usableModels`. Publishing it
     * would have shipped a model every device silently ignores.
     */
    @Test
    fun aTypodCapabilityFailsTheBuildInsteadOfPublishingAnEntryEveryClientDrops() {
        val root = tempDir()
        File(root, "vision.onnx").writeBytes(ByteArray(16))
        val manifest = ModelCatalogBuilder.ManifestJson(
            models = listOf(
                ModelCatalogBuilder.ManifestModel(
                    id = "m", version = 1, runtime = "onnx",
                    capabilities = listOf("photoLabels"),
                    artifacts = listOf(ModelCatalogBuilder.ManifestArtifact("vision", "vision.onnx")),
                )
            ),
        )
        val failure = runCatching { ModelCatalogBuilder(root).build(manifest, "2026-09-06") }.exceptionOrNull()
        assertNotNull(failure, "an unknown capability must not sign")
        assertTrue(
            failure.message.orEmpty().contains("photoLabels"),
            "the failure must name the offending capability, got: ${failure.message}",
        )
    }

    @Test
    fun anEmptyManifestIsRefused() {
        val failure = runCatching {
            ModelCatalogBuilder(tempDir()).build(ModelCatalogBuilder.ManifestJson(), "2026-09-06")
        }.exceptionOrNull()
        assertNotNull(failure, "an empty catalog is indistinguishable from a broken CDN — refuse it")
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "model-catalog-test-" + System.nanoTime())
            .apply { mkdirs() }

    private fun sha256Hex(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
