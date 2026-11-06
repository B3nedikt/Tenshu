package app.tenshu.fit.ui.screens.profile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.ImportNotice
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportStatus
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import app.tenshu.fit.composeVmTest

/**
 * Compose-render coverage for Profile: the seeded profile flows through [ProfileViewModel]
 * into the header + rows (past the `profile == null` early-return guard).
 */
@OptIn(ExperimentalTestApi::class)
class ProfileScreenTest {

    @Test
    fun seededProfileRenders() = composeVmTest { vms ->
        val vm = vms.own(ProfileViewModel(MockFitRepository()))
        setContent {
            TenshuTheme { ProfileScreen(viewModel = vm) }
        }

        // ⭐ ONE node. The `LargeTopAppBar` this screen used to carry kept a collapsed *and* an
        // expanded title in the tree at once and cross-faded between them, so an exact-match lookup
        // found two and failed — hence the `onAllNodesWithText(...)[0]` this replaces. The M3 small
        // bar (§18.15) draws its title once, so the plain matcher works again, and the assertion is
        // now strict enough to catch a second title reappearing.
        onNodeWithText("Profile", useUnmergedTree = true).assertIsDisplayed()
        // The Name row — proves the screen rendered past the null-profile guard. (Was the
        // identity header's "You" fallback until that header was removed; the name now lives
        // as an ordinary Personal data row, which is its only editor.)
        waitUntil {
            onAllNodesWithText("Name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The Google Fit explanation reaches the screen (fit-app.md §16).
     *
     * Asserted at the *render*, not at the view model: the finding survived every layer below this
     * one for the whole of its previous life — the importer logged it, the status could have
     * carried it — and still never reached a user, because nothing drew it. A model-level
     * assertion would have passed the entire time the bug existed.
     */
    /**
     * The fixable case must hand off to the wizard, NOT to a Takeout export. Getting this backwards
     * is worse than saying nothing: it talks someone out of the switch that works.
     *
     * It goes to the wizard rather than straight into Health Connect (as the first cut did) because
     * that jump was one-way — nothing then confirmed the user found the switch, and any *other*
     * unmet step stayed invisible behind it.
     */
    @Test
    fun theNotPermittedNoticeHandsOffToTheWizardNotToAnExport() = composeVmTest { vms ->
        var wizardOpened = 0
        val vm = vms.own(ProfileViewModel(
            NoticeRepository(ImportNotice.GoogleFitNotPermitted),
            filePicker = {},
            permissionRequester = SettingsRequester,
        ))
        setContent {
            TenshuTheme { ProfileScreen(viewModel = vm, onOpenSharingWizard = { wizardOpened++ }) }
        }

        waitUntil {
            onAllNodesWithText("Google Fit is not sharing with Health Connect yet", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Fix this", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Google Fit is not sharing with Health Connect yet", useUnmergedTree = true).performClick()
        waitUntil { wizardOpened == 1 }
        // A picker IS bound here, so the export line is available — and must still not be the
        // remedy offered, because permission is the actual obstacle.
        onAllNodesWithText("Import a Google Takeout export to bring that history in.", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun theGoogleFitNoticeIsRenderedBesideTheSource() = composeVmTest { vms ->
        // A picker bound, as every shell that can reach Health Connect has — this is the shape the
        // user actually meets.
        val vm = vms.own(ProfileViewModel(NoticeRepository(ImportNotice.GoogleFitNotSharing), filePicker = {}))
        setContent {
            TenshuTheme { ProfileScreen(viewModel = vm) }
        }

        waitUntil {
            onAllNodesWithText("Google Fit is allowed to share, but has not", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The remedy, not just the diagnosis: an explanation the user cannot act on is a dead end.
        onNodeWithText("Import a Google Takeout export to bring that history in.", useUnmergedTree = true)
            .assertIsDisplayed()
        // `assertExists`, not `assertIsDisplayed`: the row it points at sits further down a
        // scrolling column and is off-screen at the test window's height. Its presence is the claim
        // — that the notice names something the screen really offers.
        //
        // ⚠ DS-4 moved that row into its own *Batch import* section, so the row the notice points
        // at is now "Google Takeout" rather than "Import from Google Takeout". The claim under
        // test is unchanged: the notice must not name a remedy the screen does not offer.
        onNodeWithText("Google Takeout", useUnmergedTree = true).assertExists()
    }

    /**
     * Without a picker there is no Takeout row, so the notice must not name one. Found by this test
     * failing: the first cut told every user to run an import that half the shells do not offer.
     */
    @Test
    fun theNoticeNamesNoRemedyWhereNoneIsOffered() = composeVmTest { vms ->
        val vm = vms.own(ProfileViewModel(NoticeRepository(ImportNotice.GoogleFitNotSharing)))
        setContent {
            TenshuTheme { ProfileScreen(viewModel = vm) }
        }

        waitUntil {
            onAllNodesWithText("Google Fit is allowed to share, but has not", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithText("Import a Google Takeout export to bring that history in.", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /** The negative control: no notice, no prose — otherwise the row above is untested decoration. */
    @Test
    fun anOrdinaryImportRendersNoNotice() = composeVmTest { vms ->
        val vm = vms.own(ProfileViewModel(NoticeRepository(notice = null)))
        setContent {
            TenshuTheme { ProfileScreen(viewModel = vm) }
        }

        waitUntil {
            onAllNodesWithText("Name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithText("Google Fit is allowed to share, but has not", useUnmergedTree = true)
            .assertCountEquals(0)
        onAllNodesWithText("Google Fit is not sharing with Health Connect yet", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /** A shell that can reach the platform's health settings — what makes the remedy offerable. */
    private object SettingsRequester : HealthPermissionRequester {
        override fun request() = Unit
    }

    /** [MockFitRepository] with one thing changed: what the last import concluded. */
    private class NoticeRepository(
        private val notice: ImportNotice?,
        private val delegate: MockFitRepository = MockFitRepository(),
    ) : FitRepository by delegate {
        override fun importStatus(): StateFlow<ImportStatus> =
            MutableStateFlow(ImportStatus.Imported(ImportSource.HealthConnect, itemCount = 30, notice = notice))
    }
}
