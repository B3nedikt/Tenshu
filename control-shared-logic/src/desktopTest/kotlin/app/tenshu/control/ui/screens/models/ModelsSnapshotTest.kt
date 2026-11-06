package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.download
import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure join of the verified catalog with the local install index (IN-2c).
 *
 * ⚠ No client and no Compose: the rules that decide what a user is told are all here, and they are
 * the ones worth pinning.
 */
class ModelsSnapshotTest {

    private fun facts(
        id: String = "siglip",
        name: String = "SigLIP",
        bytes: Long = 383,
        version: Int = 3,
    ) = ModelFacts(
        modelId = id,
        name = name,
        byteSize = bytes,
        modelVersion = version,
        capabilityIds = listOf("inference.image.semanticImageSearch"),
        licence = "Apache-2.0",
    )

    @Test
    fun aCataloguedModelThisDeviceLacksIsOfferedWithItsFullSize() {
        val snapshot = modelsSnapshot(installed = emptyList(), available = listOf(facts()))

        val row = snapshot.models.single()
        assertFalse(row.installed)
        assertFalse(row.updateAvailable)
        assertEquals(383L, (row.setup as ModelSetup.Absent).model.downloadBytes)
        assertEquals("SigLIP", row.name)
        assertEquals(ModelsAvailability.Catalog.READ, snapshot.catalog)
    }

    @Test
    fun aResidentModelAtTheCatalogVersionIsInstalledWithNoUpdate() {
        val snapshot = modelsSnapshot(
            installed = listOf(facts(version = 3)),
            available = listOf(facts(version = 3)),
        )

        assertTrue(snapshot.models.single().installed)
        assertFalse(snapshot.models.single().updateAvailable)
    }

    /**
     * ⚠ An older resident copy is **installed with an update**, never absent: it still works, and
     * showing it as missing would push the user into a ~383 MB download they do not have to make.
     */
    @Test
    fun anOlderResidentCopyIsInstalledAndOffersAnUpdate() {
        val snapshot = modelsSnapshot(
            installed = listOf(facts(version = 2)),
            available = listOf(facts(version = 3)),
        )

        val row = snapshot.models.single()
        assertTrue(row.installed)
        assertTrue(row.updateAvailable)
    }

    /** ⚠ A *newer* resident copy is not "stale" — there is no downgrade to offer. */
    @Test
    fun aNewerResidentCopyOffersNoUpdate() {
        val snapshot = modelsSnapshot(
            installed = listOf(facts(version = 4)),
            available = listOf(facts(version = 3)),
        )

        assertFalse(snapshot.models.single().updateAvailable)
    }

    /**
     * ⚠⚠ The distinction the whole snapshot exists for. A null catalog is "we could not look" and
     * must keep showing what the device holds — those are the models that still work precisely
     * because nothing is needed from the network.
     */
    @Test
    fun withNoCatalogTheInstalledModelsStillShowAndTheScreenIsToldWhy() {
        val snapshot = modelsSnapshot(installed = listOf(facts()), available = null)

        assertEquals(ModelsAvailability.Catalog.UNAVAILABLE, snapshot.catalog)
        assertEquals(listOf("siglip"), snapshot.models.map { it.modelId })
        assertTrue(snapshot.models.single().installed)
    }

    /**
     * ⚠ And it claims no staleness: with nothing to compare against there is no claim to make.
     * ⚠ Nor is one ruled out: *unknown* is its own answer, not *none*.
     */
    @Test
    fun withNoCatalogNoUpdateIsClaimed() {
        val snapshot = modelsSnapshot(installed = listOf(facts(version = 1)), available = null)

        assertFalse(snapshot.models.single().updateAvailable)
        assertEquals(ModelSetup.Update.Unknown, (snapshot.models.single().setup as ModelSetup.Held).update)
    }

    /** ⚠ An empty catalog is a different answer: we looked, and there is nothing for this device. */
    @Test
    fun anEmptyCatalogIsNotTheSameAsAnUnavailableOne() {
        val snapshot = modelsSnapshot(installed = emptyList(), available = emptyList())

        assertEquals(ModelsAvailability.Catalog.READ, snapshot.catalog)
        assertTrue(snapshot.models.isEmpty())
    }

    /**
     * ⚠⚠ A model on disk that the catalog no longer lists must still appear — it is occupying the
     * space either way, and a management screen that cannot show it cannot delete it. That is
     * exactly how a withdrawn model becomes unreclaimable.
     */
    @Test
    fun aWithdrawnModelStillOnDiskIsStillListed() {
        val snapshot = modelsSnapshot(
            installed = listOf(facts(id = "withdrawn", name = "withdrawn")),
            available = listOf(facts(id = "current")),
        )

        assertEquals(listOf("current", "withdrawn"), snapshot.models.map { it.modelId })
        assertTrue(snapshot.models.first { it.modelId == "withdrawn" }.installed)
    }

    /**
     * ⚠⚠ Every row Control makes on Android and desktop is one of five, and none carries what an
     * in-process download cannot know. The join has no parameter for a standing, a held download
     * or an unreadable journal; this pins that nothing it returns has one, over every combination
     * of what it is given.
     */
    @Test
    fun noRowCarriesWhatThisPlatformCannotKnow() {
        val failed = LastDownload(3, ModelSetup.DownloadSaid.Failed(integrity = true))
        for (resident in listOf(null, 2, 3, 4)) for (catalogued in listOf(null, 3)) for (tick in listOf(null, ModelBytes("m", 1, 9)))
            for (running in listOf(false, true)) for (last in listOf(emptyMap(), mapOf("siglip" to failed))) {
                val snapshot = modelsSnapshot(
                    installed = listOfNotNull(resident?.let { facts(version = it) }),
                    available = catalogued?.let { listOf(facts(version = it)) },
                    fetching = if (running) mapOf("siglip" to tick) else emptyMap(),
                    last = last,
                )
                assertEquals(ModelsAvailability.Downloads.KNOWN, snapshot.downloads)
                val what = "resident=$resident catalogued=$catalogued running=$running"
                val row = snapshot.models.singleOrNull() ?: run {
                    assertTrue(resident == null && catalogued == null && !running, what)
                    continue
                }
                when (val setup = row.setup) {
                    is ModelSetup.Absent -> assertTrue(resident == null && !running, what)
                    is ModelSetup.Fetching -> {
                        assertTrue(resident == null && running, what)
                        assertNull(setup.download.held, what)
                    }
                    is ModelSetup.Held -> {
                        assertEquals(ModelSetup.Standing.Ready, setup.standing, what)
                        assertEquals(running, setup.update is ModelSetup.Update.Fetching, what)
                        assertNull((setup.update as? ModelSetup.Update.Fetching)?.download?.held, what)
                        if (catalogued == null && !running) assertEquals(ModelSetup.Update.Unknown, setup.update, what)
                    }
                }
            }
    }

    /**
     * ⚠⚠ How a download last ended is about one version. It is shown under the catalog entry it
     * asked for and not under the one that replaced it, nor once the model is installed at it.
     */
    @Test
    fun howADownloadEndedIsShownOnlyUnderTheVersionItAskedFor() {
        val said = ModelSetup.DownloadSaid.Failed(integrity = true)
        val last = mapOf("siglip" to LastDownload(3, said))
        fun absent(catalogVersion: Int) =
            modelsSnapshot(emptyList(), listOf(facts(version = catalogVersion)), last = last).models.single().setup as ModelSetup.Absent

        assertEquals(said, absent(catalogVersion = 3).last)
        assertNull(absent(catalogVersion = 4).last)

        val update = modelsSnapshot(listOf(facts(version = 2)), listOf(facts(version = 3)), last = last)
        assertEquals(said, ((update.models.single().setup as ModelSetup.Held).update as ModelSetup.Update.Available).last)
        val newer = modelsSnapshot(listOf(facts(version = 2)), listOf(facts(version = 4)), last = last)
        assertNull(((newer.models.single().setup as ModelSetup.Held).update as ModelSetup.Update.Available).last)
    }

    /** A download in progress: the bar is the stream's, and the total the catalog's until it says. */
    @Test
    fun aDownloadIsARowWithItsBytesWhateverTheCatalogSays() {
        val notTicked = modelsSnapshot(emptyList(), listOf(facts()), fetching = mapOf("siglip" to null)).models.single()
        assertEquals(ModelSetup.Download(true, null, 383, null), (notTicked.setup as ModelSetup.Fetching).download)

        val update = modelsSnapshot(listOf(facts(version = 2)), listOf(facts()), fetching = mapOf("siglip" to ModelBytes("m", 5, 400))).models.single()
        assertTrue(update.installed, "an update leaves the model installed while it is fetched")
        assertEquals(ModelSetup.Download(true, 5, 400, null), update.setup.download)

        // ⚠ With no catalog it is still a row: its id for a name, and the stream's total.
        val nameless = modelsSnapshot(emptyList(), null, fetching = mapOf("siglip" to ModelBytes("m", 5, 400))).models.single()
        assertEquals("siglip", nameless.name)
        assertEquals(ModelSetup.Fetching(null, ModelSetup.Download(true, 5, 400, null)), nameless.setup)

        val stale = modelsSnapshot(emptyList(), listOf(facts()), stale = true)
        assertEquals(ModelsAvailability.Catalog.STALE, stale.catalog)
        assertEquals(1, stale.models.size)
    }

    /** IN-6 F4: the face switch is one of the offered ones, so the UiState seeds a row for it. */
    @Test
    fun theFaceSwitchIsOffered() {
        assertTrue(InferenceCapability.FACE_CLUSTERING in OfferedConsents)
        assertEquals(OfferedConsents, ModelsUiState(ControlModelsCapabilities).consents.map { it.capability })
    }
}
