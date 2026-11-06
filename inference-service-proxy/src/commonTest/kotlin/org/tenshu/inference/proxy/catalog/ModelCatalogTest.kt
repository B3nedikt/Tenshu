package org.tenshu.inference.proxy.catalog

import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signed model catalog (IN-2, IN-D10) — the client's only discovery surface, and the one place
 * a compromised CDN has to get past.
 */
class ModelCatalogTest {

    private val key = "0a0b0c"
    private val goodSignature = byteArrayOf(1, 2, 3)

    /** Accepts only the exact signature, so "was it checked at all" is observable. */
    private val verifier = SignatureVerifier { _, _, signature -> signature.contentEquals(goodSignature) }

    private fun catalogJson(
        schemaVersion: Int = 1,
        artifactVersion: Int = 1,
        capabilities: String = """["textInImages"]""",
    ) = """
        {
          "schemaVersion": $schemaVersion,
          "buildDate": "2026-08-12",
          "models": [
            {
              "id": "siglip-base-patch16",
              "version": 3,
              "capabilities": $capabilities,
              "labelSetVersion": 2,
              "runtime": "onnx",
              "artifactVersion": $artifactVersion,
              "artifacts": [
                { "role": "vision", "path": "models/siglip/vision.onnx", "sha256": "abc123", "byteSize": 1024 },
                { "role": "text", "path": "models/siglip/text.onnx", "sha256": "def456", "byteSize": 2048 },
                { "role": "vocabulary", "path": "models/siglip/vocab.bin", "sha256": "099aaa", "byteSize": 32 }
              ],
              "licence": "Apache-2.0"
            }
          ]
        }
    """.trimIndent().encodeToByteArray()

    /**
     * ⚠ States the runtime it is exercising rather than inheriting the build's. Production's
     * [SUPPORTED_RUNTIMES] is **empty** until a backend is bound (IN-D13), so a parsing test that
     * took the default would assert against a catalog filtered down to nothing and pass for the
     * wrong reason.
     */
    private fun parse(
        bytes: ByteArray = catalogJson(),
        signature: ByteArray = goodSignature,
        publicKeyHex: String = key,
        runtimes: Set<String> = setOf("onnx"),
    ) = ModelCatalogVerifier(verifier, publicKeyHex, runtimes).verifyAndParse(bytes, signature)

    @Test
    fun aSignedCatalogParses() {
        val catalog = assertNotNull(parse())
        val model = catalog.models.single()

        assertEquals("siglip-base-patch16", model.modelId)
        assertEquals(listOf(InferenceCapability.TEXT_IN_IMAGES), model.capabilities)
        assertEquals("Apache-2.0", model.licence, "a model whose licence we cannot state is one we must not ship")
    }

    @Test
    fun anUnsignedOrTamperedCatalogIsRefused() {
        // ⚠⚠ The property the whole design rests on: a compromised CDN cannot swap a model,
        // because the client checks the signature before it reads a single field.
        assertNull(parse(signature = byteArrayOf(9, 9, 9)))
        assertNull(parse(bytes = "{}".encodeToByteArray(), signature = byteArrayOf(9)))
    }

    @Test
    fun aBlankPinnedKeyRefusesEverything() {
        // ⚠⚠ The single most dangerous default available here: a verifier that treats "no key
        // configured" as "skip the check". Nothing downloads until the release key is pinned, and
        // that is correct — loudly nothing, rather than quietly anything.
        assertNull(parse(publicKeyHex = ""))
        assertNull(parse(publicKeyHex = "not hex"))
    }

    @Test
    fun aNewerCatalogSchemaIsRefusedRatherThanGuessedAt() {
        assertNull(parse(catalogJson(schemaVersion = SUPPORTED_CATALOG_SCHEMA_VERSION + 1)))
    }

    @Test
    fun anArtifactThisBuildCannotReadIsFilteredOut() {
        // ⚠ The `tileSchemaVersion` precedent: one catalog serves every client version at once, so
        // an unreadable entry is skipped rather than downloaded and puzzled over.
        val catalog = assertNotNull(parse(catalogJson(artifactVersion = SUPPORTED_ARTIFACT_VERSION + 1)))

        assertEquals(emptyList(), catalog.models)
    }

    @Test
    fun anEntryServingNoCapabilityWeKnowIsDropped() {
        // A future catalog naming a capability this build has never heard of would otherwise have
        // us download bytes nothing can consume.
        val catalog = assertNotNull(parse(catalogJson(capabilities = """["timeTravel"]""")))

        assertEquals(emptyList(), catalog.models)
    }

    @Test
    fun anUnknownCapabilityDoesNotDiscardTheOnesWeDoKnow() {
        val catalog = assertNotNull(
            parse(catalogJson(capabilities = """["timeTravel", "textInImages"]""")),
        )

        // ⚠ A newer catalog must still deliver the capabilities this build has.
        assertEquals(listOf(InferenceCapability.TEXT_IN_IMAGES), catalog.models.single().capabilities)
    }

    @Test
    fun unknownFieldsDoNotBreakAnOlderClient() {
        val withExtras = """
            {
              "schemaVersion": 1,
              "buildDate": "2026-08-12",
              "somethingNew": true,
              "models": [
                {
                  "id": "m", "version": 1, "capabilities": ["textInImages"],
                  "runtime": "onnx", "artifactVersion": 1, "licence": "Apache-2.0", "extra": 5,
                  "artifacts": [
                    { "role": "vision", "path": "p", "sha256": "a", "byteSize": 1, "alsoNew": 1 }
                  ]
                }
              ]
            }
        """.trimIndent().encodeToByteArray()

        assertTrue(assertNotNull(parse(withExtras)).models.isNotEmpty())
    }

    @Test
    fun garbageIsNullRatherThanAThrow() {
        // ⚠ This runs over bytes from a CDN. "We could not trust that" leaves the models already
        // installed exactly where they are.
        assertNull(parse("not json".encodeToByteArray()))
        assertNull(parse(ByteArray(0)))
    }

    @Test
    fun anEntryThisBuildCannotExecuteIsDroppedRatherThanDownloaded() {
        // ⚠⚠ IN-D13's filter. `runtime` has been carried as data since IN-2 and nothing read it,
        // because no build could execute anything — so a catalog naming `coreml` would have been
        // downloaded in full, over a metered connection, and puzzled over at load time.
        val catalog = parse(runtimes = setOf("coreml"))

        assertEquals(emptyList(), catalog?.models)
    }

    @Test
    fun withNoRuntimeBoundNothingIsUsable() {
        // ⚠⚠ The production default today, and it is the correct one: a build with no inference
        // backend must download no weights at all. ⚠ The failure direction matters — an allowlist
        // that is empty costs nothing, while a denylist that is empty costs every byte in the
        // catalog.
        val catalog = parse(runtimes = emptySet())

        assertEquals(emptyList(), catalog?.models)
    }
}
