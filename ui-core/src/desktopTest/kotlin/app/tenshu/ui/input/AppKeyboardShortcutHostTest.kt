package app.tenshu.ui.input

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⚠⚠ The failure this exists for is silent: shortcuts attached with [keyboardShortcuts] to a
 * screen with nothing focusable in it never fire, and nothing says so. Each test presses a key with
 * no prior click, which is exactly what a user does in a window that just opened.
 */
@OptIn(ExperimentalTestApi::class)
class AppKeyboardShortcutHostTest {

    @Test
    fun aShortcutFiresBeforeAnythingWasClicked() = runDesktopComposeUiTest {
        var fired = 0
        setContent {
            AppKeyboardShortcutHost(KeyboardShortcut(Key.Escape) { fired++ }) {
                Box(Modifier.size(100.dp))
            }
        }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(1, fired)
    }

    @Test
    fun aShortcutStillFiresAfterAChildTookFocus() = runDesktopComposeUiTest {
        var fired = 0
        var clicked = 0
        setContent {
            AppKeyboardShortcutHost(KeyboardShortcut(Key.DirectionRight) { fired++ }) {
                Column {
                    Button(onClick = { clicked++ }, modifier = Modifier.testTag("child")) { Text("child") }
                }
            }
        }
        waitForIdle()

        onNodeWithTag("child").performClick()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()

        assertEquals(1, clicked, "precondition: the click landed")
        assertEquals(1, fired)
    }

    /**
     * ⚠⚠ Pins the held-key repeat: a held Delete auto-repeats, and each repeat must not trash the
     * selection again. Sent as raw key-downs with no key-up between them — what a held key
     * produces, and what the
     * test injector refuses to express (it will not press a key that is already down).
     */
    // ⚠ `InternalComposeUiApi`: the raw `KeyEvent(key, type)` constructor is the only way to send a
    // second key-down without a key-up, and a test is the one place an upgrade breaking it is cheap.
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun aHeldKeysRepeatFiresOnce_unlessItsShortcutRepeats() = runDesktopComposeUiTest {
        var deletes = 0
        var pages = 0
        setContent {
            AppKeyboardShortcutHost(
                KeyboardShortcut(Key.Delete) { deletes++ },
                KeyboardShortcut(Key.DirectionRight, repeats = true) { pages++ },
            ) {
                Box(Modifier.size(100.dp))
            }
        }
        waitForIdle()

        repeat(3) { onRoot().performKeyPress(KeyEvent(Key.Delete, KeyEventType.KeyDown)) }
        onRoot().performKeyPress(KeyEvent(Key.Delete, KeyEventType.KeyUp))
        onRoot().performKeyPress(KeyEvent(Key.Delete, KeyEventType.KeyDown))
        repeat(3) { onRoot().performKeyPress(KeyEvent(Key.DirectionRight, KeyEventType.KeyDown)) }
        waitForIdle()

        assertEquals(2, deletes, "one per press, however long the key is held")
        assertEquals(3, pages, "paging keeps its repeat")
    }

    @Test
    fun anUnmatchedKeyIsNotConsumedAsAShortcut() = runDesktopComposeUiTest {
        var fired = 0
        setContent {
            AppKeyboardShortcutHost(KeyboardShortcut(Key.Escape) { fired++ }) {
                Box(Modifier.size(100.dp))
            }
        }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.A) }
        waitForIdle()

        assertEquals(0, fired)
    }
}
