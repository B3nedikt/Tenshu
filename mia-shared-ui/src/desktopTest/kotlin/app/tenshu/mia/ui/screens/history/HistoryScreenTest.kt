package app.tenshu.mia.ui.screens.history

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.mia.data.MockConversationRepository
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import app.tenshu.mia.composeVmTest

@OptIn(ExperimentalTestApi::class)
class HistoryScreenTest {

    @Test
    fun seededSessionsListAndOpen() = composeVmTest { vms ->
        val vm = vms.own(MiaHistoryViewModel(MockConversationRepository()))
        var opened: String? = null
        setContent {
            TenshuTheme {
                HistoryScreen(viewModel = vm, onOpen = { opened = it })
            }
        }

        waitUntil {
            onAllNodesWithText("Show me photos from the lake", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Where was that ramen place?", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithText("Show me photos from the lake", useUnmergedTree = true).performClick()
        waitUntil { opened != null }
        assertEquals("conversation-1", opened)
    }
}
