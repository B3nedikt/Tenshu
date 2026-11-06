package app.tenshu.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/**
 * Regression coverage for the desktop [KmpNavHost]: an entry that sits *below*
 * the top of the back stack must keep its saveable UI state (here, a
 * `LazyColumn` scroll position) across a push/pop. This is the contract that
 * broke the Drive file list — open a photo, hit back, and the list had snapped
 * to the top — because the desktop host had no `SaveableStateHolder` and cleared
 * each entry's state on dispose rather than on removal from the back stack.
 *
 * Deliberately paging-free: the windowed paging regression is a separate concern
 * (see `FilesScreenViewModel.pagedFiles` / the `@Ignore`d
 * `FilesPagingReactivityUiTest`), and the static test repos can't reproduce it
 * faithfully. This test targets exactly the nav-host state-retention contract.
 */
class KmpNavHostTest {

    private sealed interface Dest {
        data object List : Dest
        data object Detail : Dest
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun listScrollPositionSurvivesNavigationRoundTrip() = runComposeUiTest {
        setContent {
            val backStack = remember { mutableStateListOf<Dest>(Dest.List) }
            KmpNavHost(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryProvider = kmpEntryProvider {
                    entry<Dest.List> {
                        Column {
                            // Nav trigger lives outside the scrolling area so it
                            // stays reachable after we scroll the list down.
                            Text("open", modifier = Modifier
                                .testTag("open")
                                .clickable { backStack.add(Dest.Detail) })
                            LazyColumn(Modifier.testTag("list")) {
                                items((0 until 100).toList()) { i ->
                                    Text("item-$i")
                                }
                            }
                        }
                    }
                    entry<Dest.Detail> {
                        Text("detail", modifier = Modifier
                            .testTag("back")
                            .clickable { backStack.removeLastOrNull() })
                    }
                }
            )
        }

        // Scroll far enough down that item-0 is off-screen (not composed).
        onNodeWithTag("list").performScrollToIndex(60)
        onNodeWithText("item-60").assertIsDisplayed()

        // Push the detail entry, then pop straight back.
        onNodeWithTag("open").performClick()
        onNodeWithText("detail").assertIsDisplayed()
        onNodeWithTag("back").performClick()

        // The list is back — and, crucially, still scrolled to where we left it.
        // Pre-fix the desktop host rebuilt the list from scratch and this snapped
        // back to item-0.
        onNodeWithText("item-60").assertIsDisplayed()
    }
}
