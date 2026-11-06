package app.tenshu.fit.ui.screens.datasource

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.BatchImportSummary
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceRow
import app.tenshu.fit.model.DataSourceState
import app.tenshu.fit.ui.screens.profile.ProfileScreen
import app.tenshu.fit.ui.screens.profile.ProfileViewModel
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import app.tenshu.fit.composeVmTest

/**
 * DS-4 — the restructured *Data sources* section
 * (`docs/proposals/fit-data-sources-restructure.md` §4).
 *
 * What is under test is the **shape** of the section: every row is a source, the actions have
 * gone into the source they belong to, and the batch importer is its own section because a file
 * import is an act rather than a connection (D11).
 */
@OptIn(ExperimentalTestApi::class)
class DataSourceSectionTest {

    private class SectionRepository(
        private val rows: List<DataSourceRow>,
        private val batch: BatchImportSummary?,
        private val delegate: FitRepository = MockFitRepository(),
    ) : FitRepository by delegate {
        override fun dataSources(): Flow<List<DataSourceRow>> = flowOf(rows)
        override fun batchImport(): Flow<BatchImportSummary?> = flowOf(batch)
    }

    private fun localHealthConnect() = DataSourceRow(
        kind = DataSourceKind.HealthConnect,
        deviceId = "device-A",
        deviceLabel = null,
        isThisDevice = true,
        state = DataSourceState.Imported(itemCount = 165L, at = 1_754_000_000_000L),
        autoImport = true,
    )

    private fun remoteAppleHealth() = DataSourceRow(
        kind = DataSourceKind.AppleHealth,
        deviceId = "device-B",
        deviceLabel = "iPhone 15",
        isThisDevice = false,
        state = DataSourceState.Imported(itemCount = 89L, at = 1_753_900_000_000L),
        autoImport = true,
    )

    /**
     * ⚠⚠ The ask that started this track: an iPhone's Apple Health, visible on Android. Before
     * DS-1 the section derived its rows from local workout provenance, so this was not a
     * rendering problem — it was unrepresentable.
     */
    @Test
    fun anotherDevicesSourceIsListedAndNamed() = composeVmTest { vms ->
        val vm = vms.own(ProfileViewModel(
            SectionRepository(listOf(localHealthConnect(), remoteAppleHealth()), batch = null)
        ))
        setContent { TenshuTheme { ProfileScreen(viewModel = vm) } }

        waitUntil {
            onAllNodesWithText("Apple Health", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Apple Health", useUnmergedTree = true).assertIsDisplayed()
        // Named, so "89 items" cannot be mistaken for something this phone did.
        onNodeWithText("On iPhone 15", useUnmergedTree = true).assertIsDisplayed()
    }

    /** Every row opens its own source — the fix for a row that had nowhere to go. */
    @Test
    fun aSourceRowOpensThatSourceIncludingItsDevice() = composeVmTest { vms ->
        var opened: Pair<DataSourceKind, String?>? = null
        val vm = vms.own(ProfileViewModel(
            SectionRepository(listOf(localHealthConnect(), remoteAppleHealth()), batch = null)
        ))
        setContent {
            TenshuTheme {
                ProfileScreen(viewModel = vm, onOpenDataSource = { kind, id -> opened = kind to id })
            }
        }

        waitUntil {
            onAllNodesWithText("Apple Health", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Apple Health", useUnmergedTree = true).performClick()

        // ⚠ The device travels with the kind: two phones in one realm mean two rows, and a
        // destination keyed on kind alone would open one device's screen showing the other's data.
        assertEquals(DataSourceKind.AppleHealth to "device-B", opened)
    }

    /**
     * ⚠⚠ The three rows that used to sit beside the source are gone from the top level. They were
     * an action, an action and a diagnostic rendered as though they were sources — the clutter
     * this track exists to remove.
     */
    @Test
    fun theActionRowsNoLongerSitBesideTheSources() = composeVmTest { vms ->
        val vm = vms.own(ProfileViewModel(SectionRepository(listOf(localHealthConnect()), batch = null)))
        setContent { TenshuTheme { ProfileScreen(viewModel = vm) } }

        waitUntil {
            onAllNodesWithText("Health Connect", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithText("Import your full history", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Check sharing setup", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Import from Google Takeout", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theBatchSectionCarriesTheLastImportDate() = composeVmTest { vms ->
        val vm = vms.own(ProfileViewModel(
            SectionRepository(
                listOf(localHealthConnect()),
                // 2026-08-01T12:00:00Z. ⚠ Midday, not midnight: `formatRunDate` renders in the
                // system zone, and a midnight-UTC instant would print as the 31st west of it.
                batch = BatchImportSummary(lastRunAt = 1_785_585_600_000L, lastStatus = null, runCount = 1),
            )
        ))
        setContent { TenshuTheme { ProfileScreen(viewModel = vm) } }

        waitUntil {
            onAllNodesWithText("BATCH IMPORT", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Google Takeout", useUnmergedTree = true).assertIsDisplayed()
        // The shape the trigger asked for, verbatim.
        onNodeWithText("Last import: 01.08.2026", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * ⚠ Absent, not empty. A batch-import row on a shell that can pick no file is a dead
     * affordance — and `batchImport() == null` is precisely how the repository says so (D11).
     */
    @Test
    fun theBatchSectionIsAbsentWhereNoFileCanBePicked() = composeVmTest { vms ->
        val vm = vms.own(ProfileViewModel(SectionRepository(listOf(localHealthConnect()), batch = null)))
        setContent { TenshuTheme { ProfileScreen(viewModel = vm) } }

        waitUntil {
            onAllNodesWithText("Health Connect", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // ⚠ `SectionHeader` uppercases, so this is what a reader would actually see absent.
        onAllNodesWithText("BATCH IMPORT", useUnmergedTree = true).assertCountEquals(0)
    }

    /**
     * ⚠⚠ An unread registry must not become a claim. The section renders nothing rather than an
     * empty state — "you have no sources" is a statement about the user's realm, and at this
     * moment we have not read it.
     */
    @Test
    fun anUnreadRegistryClaimsNothing() = composeVmTest { vms ->
        val vm = vms.own(ProfileViewModel(SectionRepository(rows = emptyList(), batch = null)))
        setContent { TenshuTheme { ProfileScreen(viewModel = vm) } }

        waitUntil {
            onAllNodesWithText("DATA SOURCES", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithText("Health Connect", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("No sources connected", useUnmergedTree = true).assertCountEquals(0)
    }
}
