package org.tenshu.models

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tenshu.models.ModelCatalogBuilder.ManifestArtifact
import org.tenshu.models.ModelCatalogBuilder.ManifestJson
import org.tenshu.models.ModelCatalogBuilder.ManifestModel
import org.tenshu.models.ModelCatalogBuilder.ModelSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the builder refuses, and the bytes it writes (`ios-on-device-inference.md` D13, *As built — PR-2b*).
 *
 * ⚠ The client half of the same contract is `SignedCatalogRoundTripTest` in
 * `:inference-service-proxy`, which can reach the client's `internal` parser. These are the checks
 * that need the builder alone.
 */
class ModelCatalogBuilderTest {

    /**
     * ⚠⚠ **An entry without a source is written exactly as it was before `source` existed.**
     *
     * The signature is over the raw bytes and the client verifies before it parses, so nothing a
     * shipped build does depends on this. What does: the published ONNX and face entries are
     * re-signed in every catalog, and a diff against the live catalog is the maintainer's check
     * that nothing but the new entry moved. An emitted `"source": null` would fail that diff.
     */
    @Test
    fun anEntryWithoutASourceIsWrittenByteForByteAsBefore() {
        val root = tempDir()
        File(root, "vision.onnx").writeBytes(ByteArray(3) { it.toByte() })

        val json = ModelCatalogBuilder(root).build(
            ManifestJson(models = listOf(model(artifacts = listOf(ManifestArtifact("vision", "vision.onnx"))))),
            buildDate = "2026-09-25",
        )

        assertEquals(
            """
            {
                "schemaVersion": 1,
                "buildDate": "2026-09-25",
                "models": [
                    {
                        "id": "m",
                        "name": "M",
                        "version": 1,
                        "capabilities": [
                            "semanticImageSearch"
                        ],
                        "labelSetVersion": 1,
                        "runtime": "onnx",
                        "artifactVersion": 1,
                        "licence": "Apache-2.0",
                        "artifacts": [
                            {
                                "role": "vision",
                                "path": "vision.onnx",
                                "sha256": "ae4b3280e56e2faf83f414a6e3dabe9d5fbe18976544c05fed121accb85b53fc",
                                "byteSize": 3
                            }
                        ]
                    }
                ]
            }
            """.trimIndent(),
            json,
        )
    }

    @Test
    fun aSourceIsCopiedIntoTheCatalogAsWritten() {
        val root = tempDir()
        File(root, "vision.tar").writeBytes(ByteArray(8))
        val source = ModelSource(
            repo = "example/checkpoint",
            revision = COMMIT,
            files = mapOf("model.safetensors" to DIGEST),
            toolchain = JsonObject(mapOf("coremltools" to JsonPrimitive("9.0"), "pinned" to JsonPrimitive(true))),
            exportManifestSha256 = DIGEST.reversed(),
        )

        val json = ModelCatalogBuilder(root).build(
            ManifestJson(
                models = listOf(
                    model(runtime = "coreml", source = source, artifacts = listOf(ManifestArtifact("vision", "vision.tar")))
                )
            ),
            buildDate = "2026-10-07",
        )

        listOf(
            "\"repo\": \"example/checkpoint\"",
            "\"revision\": \"$COMMIT\"",
            "\"model.safetensors\": \"$DIGEST\"",
            "\"coremltools\": \"9.0\"",
            "\"pinned\": true",
            "\"exportManifestSha256\": \"${DIGEST.reversed()}\"",
        ).forEach { assertTrue(json.contains(it), "the catalog must carry $it, got:\n$json") }
    }

    /**
     * ⚠⚠ D13's signer rule. A client keeps every entry whose runtime it executes and resolves a
     * model by id alone, so two entries with one id AND one runtime leave the second unreachable.
     */
    @Test
    fun theSameIdTwiceUnderOneRuntimeIsRefused() {
        val root = tempDir()
        File(root, "a.onnx").writeBytes(ByteArray(4))
        File(root, "b.onnx").writeBytes(ByteArray(5))
        val manifest = ManifestJson(
            models = listOf(
                model(artifacts = listOf(ManifestArtifact("vision", "a.onnx"))),
                model(artifacts = listOf(ManifestArtifact("vision", "b.onnx"))),
            ),
        )

        val failure = assertNotNull(runCatching { ModelCatalogBuilder(root).build(manifest, "2026-10-07") }.exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("'m' with runtime 'onnx'"), failure.message)
    }

    @Test
    fun theSameIdUnderTwoRuntimesIsAccepted() {
        val root = tempDir()
        File(root, "a.onnx").writeBytes(ByteArray(4))
        File(root, "a.tar").writeBytes(ByteArray(5))
        val manifest = ManifestJson(
            models = listOf(
                model(artifacts = listOf(ManifestArtifact("vision", "a.onnx"))),
                model(runtime = "coreml", artifacts = listOf(ManifestArtifact("vision", "a.tar"))),
            ),
        )

        val json = ModelCatalogBuilder(root).build(manifest, "2026-10-07")
        assertTrue(json.contains("\"runtime\": \"onnx\"") && json.contains("\"runtime\": \"coreml\""), json)
    }

    /**
     * ⚠⚠ Entries sharing an id are one checkpoint: a row is stamped with (id, version), so two
     * versions under one id would make one stamp mean two models.
     */
    @Test
    fun entriesSharingAnIdMustShareAVersion() {
        val root = tempDir()
        File(root, "a.onnx").writeBytes(ByteArray(4))
        File(root, "a.tar").writeBytes(ByteArray(5))
        fun manifest(version: Int, labelSetVersion: Int) = ManifestJson(
            models = listOf(
                model(artifacts = listOf(ManifestArtifact("vision", "a.onnx"))),
                model(
                    runtime = "coreml", version = version, labelSetVersion = labelSetVersion,
                    artifacts = listOf(ManifestArtifact("vision", "a.tar")),
                ),
            ),
        )

        listOf(manifest(version = 2, labelSetVersion = 1), manifest(version = 1, labelSetVersion = 2)).forEach {
            val failure = assertNotNull(runCatching { ModelCatalogBuilder(root).build(it, "2026-10-07") }.exceptionOrNull())
            assertTrue(failure.message.orEmpty().contains("differing (version, labelSetVersion)"), failure.message)
        }
    }

    /**
     * ⚠⚠ The pin. Two exports of one checkpoint have different archive digests, and only one of
     * them passed the retrieval gate; without the pin the other signs cleanly under the same name.
     */
    @Test
    fun aFileThatIsNotThePinnedBytesIsRefused() {
        val root = tempDir()
        val file = File(root, "vision.tar").apply { writeBytes(ByteArray(8) { 7 }) }
        val real = sha256File(file).toHex()
        fun build(sha256: String, byteSize: Long?) = runCatching {
            ModelCatalogBuilder(root).build(
                ManifestJson(
                    models = listOf(
                        model(artifacts = listOf(ManifestArtifact("vision", "vision.tar", sha256 = sha256, byteSize = byteSize)))
                    )
                ),
                "2026-10-07",
            )
        }

        val wrongDigest = assertNotNull(build(DIGEST, 8).exceptionOrNull(), "another digest must not sign")
        assertTrue(wrongDigest.message.orEmpty().contains("pins $DIGEST"), wrongDigest.message)

        // ⚠ A blank pin is a malformed pin, not an absent one: only "" means unpinned.
        for (blank in listOf(" ", "\t", "   ")) {
            assertNotNull(build(blank, 8).exceptionOrNull(), "a whitespace-only pin must not sign")
        }
        assertTrue(build("", 8).isSuccess, "the default empty pin is the unpinned case")

        val wrongSize = assertNotNull(build(real, 9).exceptionOrNull(), "another length must not sign")
        assertTrue(wrongSize.message.orEmpty().contains("pins 9"), wrongSize.message)

        // ⚠ And the pinned bytes do sign, with the computed values: otherwise the two refusals
        // above would also be satisfied by a builder that refuses every pinned artifact.
        val json = build(real, 8).getOrThrow()
        assertTrue(json.contains("\"sha256\": \"$real\"") && json.contains("\"byteSize\": 8"), json)
    }

    @Test
    fun aSourceThatDoesNotNameASnapshotIsRefused() {
        val root = tempDir()
        File(root, "vision.tar").writeBytes(ByteArray(8))
        fun failureFor(source: ModelSource) = assertNotNull(
            runCatching {
                ModelCatalogBuilder(root).build(
                    ManifestJson(
                        models = listOf(
                            model(source = source, artifacts = listOf(ManifestArtifact("vision", "vision.tar")))
                        )
                    ),
                    "2026-10-07",
                )
            }.exceptionOrNull(),
            "$source must not sign",
        ).message.orEmpty()

        assertTrue(failureFor(ModelSource(repo = "r", revision = "main")).contains("not a 40-character commit"))
        assertTrue(failureFor(ModelSource(repo = " ", revision = COMMIT)).contains("no repo"))
        assertTrue(
            failureFor(ModelSource(repo = "r", revision = COMMIT, files = mapOf("config.json" to "abc")))
                .contains("[config.json]")
        )
        assertTrue(
            failureFor(ModelSource(repo = "r", revision = COMMIT, exportManifestSha256 = "3fd19181"))
                .contains("exportManifestSha256")
        )
    }

    @Test
    fun aMisspeltKeyInTheManifestIsRefusedNotDropped() {
        // ⚠ The case that matters: a misspelt pin would parse as "no pin" and sign any bytes.
        fun manifest(artifact: String) =
            """{"models":[{"id":"m","name":"M","version":1,"runtime":"coreml","capabilities":["x"],"artifacts":[$artifact]}]}"""

        val good = ModelCatalogBuilder.parseManifest(manifest("""{"role":"vision","path":"v.tar","sha256":"${"a".repeat(64)}","byteSize":8}"""))
        assertEquals("a".repeat(64), good.models.single().artifacts.single().sha256)
        for (typo in listOf(
            """{"role":"vision","path":"v.tar","sha265":"${"a".repeat(64)}","byteSize":8}""",
            """{"role":"vision","path":"v.tar","sha256":"${"a".repeat(64)}","bytesSize":8}""",
        )) {
            assertNotNull(runCatching { ModelCatalogBuilder.parseManifest(manifest(typo)) }.exceptionOrNull(), typo)
        }
        assertNotNull(
            runCatching {
                ModelCatalogBuilder.parseManifest(
                    """{"models":[{"id":"m","name":"M","version":1,"runtime":"coreml","capabilities":["x"],"artifacts":[],"source":{"repo":"r","revison":"x"}}]}"""
                )
            }.exceptionOrNull(),
            "a misspelt source key",
        )
    }

    private fun model(
        runtime: String = "onnx",
        version: Int = 1,
        labelSetVersion: Int = 1,
        source: ModelSource? = null,
        artifacts: List<ManifestArtifact>,
    ) = ManifestModel(
        id = "m", name = "M", version = version, capabilities = listOf("semanticImageSearch"),
        labelSetVersion = labelSetVersion, runtime = runtime, licence = "Apache-2.0",
        source = source, artifacts = artifacts,
    )

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "model-catalog-builder-test-" + System.nanoTime())
            .apply { mkdirs() }

    private companion object {
        const val COMMIT = "8952a4eafcde3cb7ab46b1dd629b33f8784ca9c6"
        const val DIGEST = "00df88d823790b34a4d2d0047803ffabd47497ae724fd6659b1533dd793255be"
    }
}
