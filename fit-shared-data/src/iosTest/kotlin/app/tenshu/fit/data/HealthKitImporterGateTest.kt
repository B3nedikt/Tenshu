package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two App Review guards on [HealthKitImporter]:
 *
 * 1. No health store (an iPad on iPadOS 16) → **no source and no data**, never the reference fixture.
 *    The fixture used to come back as `Sample`, and a manual import wrote it into the real realm.
 * 2. The HealthKit sheet follows a tap: [HealthKitImporter.autoRefreshReady] stays false until the
 *    read request has been answered once, so the launch/foreground refresh cannot be what asks.
 */
class HealthKitImporterGateTest {

    private fun importer(
        available: Boolean,
        declared: Boolean = true,
        answered: Boolean = false,
    ) = HealthKitImporter(
        healthDataAvailable = { available },
        usageDescriptionDeclared = { declared },
        readRequestAnswered = { _, _ -> answered },
    )

    @Test
    fun without_a_health_store_there_is_no_source_and_no_fixture() = runTest {
        val importer = importer(available = false)

        assertEquals(emptyList(), importer.availableSources())
        assertEquals(
            ImportOutcome.SourceUnavailable,
            importer.import(ImportSource.AppleHealth, CategoryId.entries.toSet(), ImportWindow.Recent),
            "no HealthKit must read as unavailable, not as sample data",
        )
        assertFalse(importer.autoRefreshReady())
    }

    @Test
    fun an_unanswered_read_request_keeps_the_unattended_refresh_off() = runTest {
        // The fresh-install case: HealthKit exists and the plist declares the usage string, but the
        // user has never been asked. An automatic pass here would put the consent sheet up unasked.
        assertFalse(importer(available = true, answered = false).autoRefreshReady())
    }

    @Test
    fun an_answered_read_request_lets_the_unattended_refresh_run() = runTest {
        assertTrue(importer(available = true, answered = true).autoRefreshReady())
    }

    @Test
    fun a_host_without_the_usage_string_never_refreshes_unattended() = runTest {
        assertFalse(importer(available = true, declared = false, answered = true).autoRefreshReady())
    }
}
