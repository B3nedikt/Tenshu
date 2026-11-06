package app.tenshu.fit.android

import android.content.ContextWrapper
import androidx.health.connect.client.HealthConnectClient
import app.tenshu.fit.data.HealthConnectImporter
import app.tenshu.fit.data.ImportOutcome
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.ImportSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * ⚠⚠ Without Health Connect there is **no import source** — never the sample fixture
 * (`docs/proposals/fit-app.md` §9.15).
 *
 * Pins that a missing, outdated or unsupported Health Connect yields no source and
 * `SourceUnavailable`. A manual import is deliberately not gated on `autoRefreshReady`, so a
 * fallback to `ReferenceHealthImporter` would go through `FitProjectionRepository.runImport` into
 * Control's durable, synced realm — invented workouts on every paired device.
 */
class HealthConnectImporterUnavailableTest {

    /** A Context that is never asked anything: the SDK status comes from the seam. */
    private class FakeContext : ContextWrapper(null)

    private fun importer(sdkStatus: Int) =
        HealthConnectImporter(FakeContext(), sdkStatusOf = { sdkStatus })

    private val unavailableStatuses = listOf(
        HealthConnectClient.SDK_UNAVAILABLE,
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
    )

    @Test
    fun `without Health Connect the importer offers no source`() {
        for (status in unavailableStatuses) {
            val importer = importer(status)
            assertEquals(emptyList<ImportSource>(), importer.availableSources(), "sdkStatus=$status")
            assertFalse(importer.isHealthConnectAvailable(), "sdkStatus=$status")
        }
    }

    @Test
    fun `without Health Connect an import answers SourceUnavailable rather than sample data`() = runTest {
        for (status in unavailableStatuses) {
            val importer = importer(status)
            assertEquals(
                ImportOutcome.SourceUnavailable,
                importer.import(ImportSource.HealthConnect, CategoryId.entries.toSet(), ImportWindow.Recent),
                "sdkStatus=$status: no Health Connect must read as unavailable, not as sample data",
            )
            assertFalse(importer.autoRefreshReady(), "sdkStatus=$status")
        }
    }
}
