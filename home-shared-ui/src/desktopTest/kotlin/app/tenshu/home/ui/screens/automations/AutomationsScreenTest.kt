package app.tenshu.home.ui.screens.automations

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.tenshu.home.data.MockAutomationsRepository
import app.tenshu.home.data.MockHomeDevicesRepository
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import app.tenshu.home.composeVmTest

/**
 * Compose-render coverage for the Automations tab: seeded rows render with the shared
 * human-readable summary (trigger · first action, device ids resolved to names), and the
 * editor renders the §5 sections with a prefilled draft on edit.
 */
@OptIn(ExperimentalTestApi::class)
class AutomationsScreenTest {

    @Test
    fun seededAutomationsRenderWithResolvedSummaries() = composeVmTest { vms ->
        val vm = vms.own(AutomationsViewModel(MockAutomationsRepository(), MockHomeDevicesRepository()))
        setContent {
            TenshuTheme {
                AutomationsScreen(viewModel = vm, onCreate = {}, onEdit = {})
            }
        }

        waitUntil {
            onAllNodesWithText("Welcome home", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Lights out", useUnmergedTree = true).assertIsDisplayed()
        // The summary resolves the device id to its household name.
        assertTrue(
            onAllNodesWithText("Turn on Sofa lamp", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        // The schedule trigger renders as a formatted time.
        assertTrue(
            onAllNodesWithText("At 22:30", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun editorPrefillsAnExistingAutomation() = composeVmTest { vms ->
        val vm = vms.own(AutomationEditorViewModel(
            MockAutomationsRepository(),
            MockHomeDevicesRepository(),
            automationId = "automation-camera",
        ))
        setContent {
            TenshuTheme {
                AutomationEditorScreen(viewModel = vm, isNew = false, onBack = {})
            }
        }

        waitUntil {
            onAllNodesWithText("Someone at the door", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The taxonomy sections are present…
        onNodeWithText("When", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Only if", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Then", useUnmergedTree = true).assertIsDisplayed()
        // …and the prefilled trigger/condition/action chips resolve names.
        assertTrue(
            onAllNodesWithText("Front door camera", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            onAllNodesWithText("Only when nobody is home", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
