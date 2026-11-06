package app.tenshu.home.ui.screens.activity

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.home.data.MockHomeActivityRepository
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import app.tenshu.home.composeVmTest

/**
 * Compose-render coverage for the Activity tab: the seeded feed renders under day headers,
 * and the Voice filter chip narrows the list to Mia's interactions.
 */
@OptIn(ExperimentalTestApi::class)
class ActivityScreenTest {

    @Test
    fun seededFeedRendersAndVoiceFilterNarrowsIt() = composeVmTest { vms ->
        val vm = vms.own(ActivityViewModel(MockHomeActivityRepository()))
        setContent {
            TenshuTheme {
                ActivityScreen(viewModel = vm)
            }
        }

        waitUntil {
            onAllNodesWithText("Balcony door", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The seed spans two days, so a Today header is present.
        onNodeWithText("Today", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Person detected", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithText("Voice", useUnmergedTree = true).performClick()

        waitUntil {
            onAllNodesWithText("Balcony door", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        waitUntil {
            onAllNodesWithText("Mia", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
