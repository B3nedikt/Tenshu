package org.tenshu.inference.proxy.catalog

import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The install index and what a UI reads off it (IN-2, IN-D10). */
class ModelStoreTest {

    /** A stand-in digest — the real one is the platform's sha256; what is tested is the *gate*. */
    private fun digest(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16) }

    private val bytes = byteArrayOf(1, 2, 3)

    private fun entry(
        modelId: String = "siglip",
        sha256: String = digest(bytes),
        capabilities: List<InferenceCapability> = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
        modelVersion: Int = 1,
    ) = ModelEntryData(
        modelId = modelId,
        name = "SigLIP",
        modelVersion = modelVersion,
        capabilities = capabilities,
        labelSetVersion = 1,
        runtime = "onnx",
        artifactVersion = 1,
        // ⚠ One artifact: these suites are about the index and the staging mechanics, not about
        // the multi-file checkpoint — `ModelDownloaderTest` owns that.
        artifacts = listOf(
            ModelArtifactData(role, "models/$modelId.onnx", sha256, bytes.size.toLong()),
        ),
        licence = "Apache-2.0",
    )

    private val role = ModelArtifactRole.VISION

    private fun store() = InMemoryModelStore(::digest)

    @Test
    fun bytesThatDoNotMatchTheSignedDigestNeverLand() {
        val store = store()

        // ⚠⚠ The gate everything downstream rests on. A CDN that serves the wrong bytes fails
        // HERE, not in an inference run three weeks later.
        assertNull(store.add(entry(sha256 = "something-else"), mapOf(role to bytes)))
        assertEquals(emptyList(), store.installed())
    }

    @Test
    fun aVerifiedArtifactIsInstalledWithItsLicence() {
        val store = store()

        val installed = assertNotNull(store.add(entry(), mapOf(role to bytes)))

        assertEquals("siglip", installed.modelId)
        // ⚠ The licence travels with the copy on disk: "which licence is this model under" must be
        // answerable from the device, not only from a catalog it may no longer be able to fetch.
        assertEquals("Apache-2.0", installed.licence)
        assertEquals(listOf(installed), store.installed())
    }

    @Test
    fun removingAModelLeavesTheIndexEmpty() {
        val store = store()
        store.add(entry(), mapOf(role to bytes))

        store.remove("siglip")

        assertEquals(emptyList(), store.installed())
        assertNull(store.bytesOf("siglip", role))
    }

    @Test
    fun theThreeStatesAreDistinguishable() {
        val store = store()
        val inventory = ModelInventory(store)
        val model = entry()

        assertEquals(ModelInstallState.Absent, inventory.stateOf(model))
        assertEquals(
            ModelInstallState.Downloading(512, 1024),
            inventory.stateOf(model, ModelInstallState.Downloading(512, 1024)),
        )
        store.add(model, mapOf(role to bytes))
        assertTrue(inventory.stateOf(model) is ModelInstallState.Installed)
    }

    @Test
    fun anOlderInstalledVersionStillReadsAsInstalled() {
        val store = store()
        store.add(entry(modelVersion = 1), mapOf(role to bytes))
        val inventory = ModelInventory(store)

        // ⚠ It still works. Presenting it as missing would push the user into a download they do
        // not need to make; staleness is `ModelProvenance`'s job, and that re-derives ROWS rather
        // than re-downloading weights.
        assertTrue(inventory.stateOf(entry(modelVersion = 2)) is ModelInstallState.Installed)
    }

    @Test
    fun downloadedCapabilitiesAreWhatIsOnDisk_notWhatIsOffered() {
        val store = store()
        val downloaded = entry(modelId = "siglip")
        val catalog = ModelCatalogData(
            schemaVersion = 1,
            buildDate = "2026-08-12",
            models = listOf(
                downloaded,
                entry(modelId = "ocr", capabilities = listOf(InferenceCapability.TEXT_IN_IMAGES)),
            ),
        )
        store.add(downloaded, mapOf(role to bytes))

        // ⚠⚠ "Downloaded" and "enabled" are different questions and this answers only one. IN-D10
        // requires the UI to say which one an action does — deleting a model must not silently
        // disable a capability, and vice versa — and keeping consent out of here is what makes
        // that hard to get wrong.
        assertEquals(
            setOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
            ModelInventory(store).downloadedCapabilities(catalog),
        )
    }
}
