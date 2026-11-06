package app.tenshu.ui.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A keyboard shortcut definition. When the user presses a key combination matching
 * this shortcut, [onTriggered] is invoked.
 *
 * The [ctrl] flag maps to the *platform-appropriate primary modifier* — Ctrl on
 * Windows/Linux and Cmd on macOS — so app-level shortcuts feel native on every OS.
 */
data class KeyboardShortcut(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    /**
     * Whether a held key's auto-repeat fires this again. Honoured by [AppKeyboardShortcutHost]; ⚠ bare
     * [keyboardShortcuts] cannot tell a repeat from a press and fires on both.
     */
    val repeats: Boolean = false,
    val onTriggered: () -> Unit
)

/**
 * True when the platform's primary command modifier is pressed: Ctrl on Windows/Linux,
 * Cmd (Meta) on macOS.
 */
val KeyEvent.isPrimaryModifierPressed: Boolean
    get() = isCtrlPressed || isMetaPressed

/**
 * Attaches keyboard shortcuts to a subtree. The modifier intercepts key events on the
 * preview channel, so shortcuts take priority over child composables. Returns `true`
 * (consumed) when a shortcut matches, preventing other handlers from firing.
 *
 * For shortcuts to fire, some composable in the subtree must have focus. On desktop
 * this is typically the window; on Android, attach [androidx.compose.foundation.focusable]
 * to a root container and request focus on first composition.
 *
 * Usage:
 * ```
 * Scaffold(
 *     modifier = Modifier.keyboardShortcuts(
 *         AppShortcuts.focusSearch { searchFocusRequester.requestFocus() },
 *         AppShortcuts.dismiss { onDismiss() }
 *     )
 * ) { ... }
 * ```
 */
fun Modifier.keyboardShortcuts(vararg shortcuts: KeyboardShortcut): Modifier =
    onPreviewKeyEvent { event -> handleShortcut(event, shortcuts) }

private fun handleShortcut(event: KeyEvent, shortcuts: Array<out KeyboardShortcut>): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    for (sc in shortcuts) {
        if (event.key == sc.key &&
            event.isPrimaryModifierPressed == sc.ctrl &&
            event.isShiftPressed == sc.shift &&
            event.isAltPressed == sc.alt
        ) {
            sc.onTriggered()
            return true
        }
    }
    return false
}

/**
 * Conventional app shortcuts. Use these instead of hand-rolling [KeyboardShortcut]s
 * so the same combos remain consistent across screens.
 */
object AppShortcuts {
    /** Ctrl+F (Cmd+F on macOS) — focus the search field. */
    fun focusSearch(onTrigger: () -> Unit) =
        KeyboardShortcut(Key.F, ctrl = true, onTriggered = onTrigger)

    /** Esc — dismiss the top-most dismissable surface (sheet, dialog, or selection). */
    fun dismiss(onTrigger: () -> Unit) =
        KeyboardShortcut(Key.Escape, onTriggered = onTrigger)

    /** Delete — remove the current selection. */
    fun deleteSelection(onTrigger: () -> Unit) =
        KeyboardShortcut(Key.Delete, onTriggered = onTrigger)

    /** Ctrl+N (Cmd+N on macOS) — create new item. */
    fun newItem(onTrigger: () -> Unit) =
        KeyboardShortcut(Key.N, ctrl = true, onTriggered = onTrigger)

    /** Ctrl+A (Cmd+A on macOS) — select all. */
    fun selectAll(onTrigger: () -> Unit) =
        KeyboardShortcut(Key.A, ctrl = true, onTriggered = onTrigger)
}
