package app.tenshu.fit.ui.screens.sharing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.AppSharingState
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.SharingReadiness
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import app.tenshu.fit.composeVmTest

/**
 * The wizard has to be *drawn* to be worth anything, and one thing in particular has to reach the
 * screen: the step whose action we cannot perform must still offer the one move that helps.
 */
@OptIn(ExperimentalTestApi::class)
class SharingWizardScreenTest {

    @Test
    fun aBlockedGoogleFitOffersHealthConnect() = composeVmTest { vms ->
        val requester = CountingRequester()
        val vm = vms.own(SharingWizardViewModel(repository(AppSharingState.NotPermitted), requester))
        setContent { TenshuTheme { SharingWizardScreen(viewModel = vm, onBack = {}) } }

        waitUntil {
            onAllNodesWithText("Let Google Fit share with Health Connect", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Open Health Connect", useUnmergedTree = true).assertIsDisplayed().performClick()
        // The button is the whole point of the step: the grant belongs to Google Fit, so navigating
        // is the only move we have, and a step that merely described it would be a dead end.
        waitUntil { requester.settingsOpened == 1 }
    }

    @Test
    fun theCopyNeverWalksTheUserThroughGooglesOwnScreens() = composeVmTest { vms ->
        val vm = vms.own(SharingWizardViewModel(repository(AppSharingState.NotPermitted), CountingRequester()))
        setContent { TenshuTheme { SharingWizardScreen(viewModel = vm, onBack = {}) } }

        waitUntil {
            onAllNodesWithText("Let Google Fit share with Health Connect", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // ⚠ Health Connect's own labels are Google's localised strings; they move between versions
        // and nothing here can assert they still read this way. The wizard re-probes instead, so
        // the copy must never quote them — this pins that decision rather than trusting it.
        listOf("App permissions", "tap ", "Tap ", "then select").forEach { forbidden ->
            onAllNodesWithText(forbidden, substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun aFullySetUpDeviceSaysSoAndOffersNothing() = composeVmTest { vms ->
        val vm = vms.own(SharingWizardViewModel(repository(AppSharingState.Permitted), CountingRequester()))
        setContent { TenshuTheme { SharingWizardScreen(viewModel = vm, onBack = {}) } }

        waitUntil {
            onAllNodesWithText("Everything is set up", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithText("Open Health Connect", useUnmergedTree = true).assertCountEquals(0)
    }

    private fun repository(googleFit: AppSharingState): FitRepository = FakeRepository(
        SharingReadiness(
            storePresent = true,
            canRead = true,
            historyAccess = HistoryAccess.Granted,
            googleFit = googleFit,
        ),
    )

    private class CountingRequester : HealthPermissionRequester {
        var settingsOpened = 0
            private set

        override fun request() = Unit
        override fun openHealthSettings() { settingsOpened++ }
    }

    private class FakeRepository(
        readiness: SharingReadiness,
        private val delegate: MockFitRepository = MockFitRepository(),
    ) : FitRepository by delegate {
        private val state = MutableStateFlow(readiness)
        override fun sharingReadiness(): StateFlow<SharingReadiness> = state
        override suspend fun refreshSharingReadiness() = Unit
        override suspend fun refreshHistoryAccess() = Unit
    }
}
