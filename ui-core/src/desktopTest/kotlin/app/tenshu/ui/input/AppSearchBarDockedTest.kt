package app.tenshu.ui.input

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.tenshu.ui.theme.TenshuTheme
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.ui.test.NoMotion

/**
 * The wide-window [AppSearchBar]: a docked field whose results open in a panel over the screen,
 * instead of Material's full-screen search view taking the whole window on a click of the lens.
 */
@OptIn(ExperimentalTestApi::class)
class AppSearchBarDockedTest {

    private class Host {
        var query by mutableStateOf("")
        var active by mutableStateOf(false)
        var resultClicks = 0
        /** System Back, through the same dispatcher Android's back button feeds. */
        val back = DirectNavigationEventInput()
    }

    private fun ComposeUiTest.host(): Host {
        val host = Host()
        setContent {
            val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(host.back)
                onDispose { dispatcher.removeInput(host.back) }
            }
            TenshuTheme(useDarkTheme = false) {
                Column(Modifier.fillMaxSize()) {
                    AppSearchBar(
                        query = host.query,
                        onQueryChange = { host.query = it },
                        active = host.active,
                        onActiveChange = { host.active = it },
                        onSearch = {},
                        modifier = Modifier.testTag("bar"),
                    ) {
                        Text("a result", Modifier.fillMaxWidth().height(48.dp))
                        TextButton(onClick = {
                            host.resultClicks++
                            host.active = false
                        }) { Text("open result") }
                    }
                    Text("the listing", Modifier.fillMaxWidth().height(400.dp).testTag("listing"))
                }
            }
        }
        return host
    }

    private fun ComposeUiTest.fieldBounds(): Rect =
        onNode(hasSetTextAction()).getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    @Test
    fun theFieldDoesNotMoveAndTheListingStaysWhenResultsOpen() = runDesktopComposeUiTest(1024, 768, effectContext = NoMotion) {
        val host = host()
        val listingBefore = onNodeWithTag("listing").getBoundsInRoot()
        val collapsed = fieldBounds()

        host.active = true
        waitForIdle()

        assertEquals(collapsed, fieldBounds(), "expanding must not move or resize the bar")
        assertEquals(listingBefore, onNodeWithTag("listing").getBoundsInRoot(), "results overlay the listing, never push it")
        onNodeWithText("a result").assertExists()
        onNodeWithText("the listing").assertExists()
    }

    @Test
    fun aClickOutsideThePanelClosesIt() = runDesktopComposeUiTest(1024, 768, effectContext = NoMotion) {
        val host = host()
        host.active = true
        waitForIdle()

        onNodeWithTag("listing").performMouseInput { click(bottomCenter.copy(y = bottomCenter.y - 4f)) }
        waitForIdle()

        assertFalse(host.active)
    }

    @Test
    fun clickingIntoTheFieldWhileOpenKeepsTheSearch() = runDesktopComposeUiTest(1024, 768, effectContext = NoMotion) {
        val host = host()
        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performTextInput("dog")
        waitForIdle()
        assertTrue(host.active)

        // Placing the caret with the mouse is part of searching, not a click away from it.
        onNode(hasSetTextAction()).performMouseInput { click(center) }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        assertTrue(host.active, "a click in the field must not close the results")
        assertEquals("dog", host.query)
    }

    @Test
    fun aResultInThePanelIsClickable() = runDesktopComposeUiTest(1024, 768, effectContext = NoMotion) {
        val host = host()
        host.active = true
        waitForIdle()

        onNodeWithText("open result").performClick()
        waitForIdle()

        assertEquals(1, host.resultClicks)
        assertFalse(host.active)
    }

    @Test
    fun escapeClosesAndEnterDoesNot() = runDesktopComposeUiTest(1024, 768, effectContext = NoMotion) {
        val host = host()
        onNode(hasSetTextAction()).performClick()
        waitForIdle()
        assertTrue(host.active, "focusing the field opens the panel")

        onNode(hasSetTextAction()).performImeAction()
        waitForIdle()
        assertTrue(host.active, "Enter keeps the results up")

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(host.active, "Escape closes them")
    }

    @Test
    fun systemBackClosesTheResults() = runDesktopComposeUiTest(1024, 768, effectContext = NoMotion) {
        val host = host()
        host.active = true
        waitForIdle()

        runOnIdle { host.back.backCompleted() }
        waitForIdle()

        assertFalse(host.active)
    }

    @Test
    fun typingIntoAClosedFieldReopensThePanel() = runDesktopComposeUiTest(1024, 768, effectContext = NoMotion) {
        val host = host()
        onNode(hasSetTextAction()).performClick()
        waitForIdle()
        host.active = false
        waitForIdle()

        onNode(hasSetTextAction()).performTextInput("q")
        waitForIdle()

        assertTrue(host.active)
        assertEquals("q", host.query)
    }

    @Test
    fun aPhoneKeepsTheFullScreenSearchView() = runDesktopComposeUiTest(400, 768, effectContext = NoMotion) {
        val host = host()
        val collapsed = fieldBounds()
        host.active = true
        waitForIdle()

        val expanded = fieldBounds()
        assertTrue(expanded.width > collapsed.width, "the phone bar goes edge to edge when expanded")
        assertEquals(400f, expanded.width, 1f)
    }
}
