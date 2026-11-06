package app.tenshu.fit.ui.screens.browse

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.CategoryId
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import app.tenshu.fit.composeVmTest

/**
 * Compose-render coverage for Browse: the seven categories flow through [BrowseViewModel]
 * into category cards, and tapping one routes through `onOpenCategory`.
 */
@OptIn(ExperimentalTestApi::class)
class BrowseScreenTest {

    @Test
    fun categoriesRenderAndAreClickable() = composeVmTest { vms ->
        val opened = mutableListOf<CategoryId>()
        val vm = vms.own(BrowseViewModel(MockFitRepository()))
        setContent {
            TenshuTheme { BrowseScreen(viewModel = vm, onOpenCategory = { opened += it }, onImport = {}) }
        }

        onNodeWithText("Browse", useUnmergedTree = true).assertIsDisplayed()
        waitUntil {
            onAllNodesWithText("Activity", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Activity", useUnmergedTree = true).performClick()
        waitUntil { opened.isNotEmpty() }
        assertTrue(opened.contains(CategoryId.Activity), "tapping the Activity card must open it")
    }
}
