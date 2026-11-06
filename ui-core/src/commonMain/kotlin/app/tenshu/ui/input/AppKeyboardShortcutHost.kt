package app.tenshu.ui.input

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A subtree whose keyboard [shortcuts] fire without the user having clicked into it first.
 *
 * ### When to use
 *
 * Around a screen, or a whole tab, that should answer keys the moment it appears — a photo grid, a
 * full-screen viewer — and that has nothing of its own which takes focus. ⚠⚠ Bare
 * [keyboardShortcuts] is **inert** there: it only sees key events routed to a focused node inside it,
 * and a window opens with nothing focused, so every press goes to the root. This host is a focus
 * target and asks for focus once when it enters composition.
 *
 * Not for a screen built around a text field — typing belongs to the field, and a host would ask for
 * focus over it on entry. A click on a child that takes focus keeps the shortcuts working, because the
 * child is still inside the subtree they watch.
 *
 * ### Variants
 *
 * - Any number of [KeyboardShortcut]s, including the [AppShortcuts] conventions.
 * - ⚠ A held key's auto-repeat does **not** fire a shortcut again unless it says
 *   [KeyboardShortcut.repeats] — a held Delete would otherwise run a destructive action once per
 *   repeat. Arrow-key paging opts in.
 *
 * ### Accessibility
 *
 * Takes keyboard focus on entry, which is what lets a keyboard user act without a pointer first. ⚠ It
 * asks once per entry into composition, never on recomposition, so it does not pull focus back out of
 * a control the user moved into. It adds no semantics and draws nothing: shortcuts complement, never
 * replace, the on-screen controls that do the same things.
 *
 * @param shortcuts the keys this subtree answers.
 * @param modifier applied to the host's `Box`.
 * @param content a `Box`'s content, so the host can replace a screen's outer `Box` without moving its
 *   children.
 */
@Composable
fun AppKeyboardShortcutHost(
    vararg shortcuts: KeyboardShortcut,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    val repeats = remember { KeyRepeatFilter() }
    Box(
        modifier
            // ⚠ Before the focus target, so it sees the subtree losing focus — a key released while
            // focus was elsewhere never sends its key-up here, and would read as held forever.
            .onFocusChanged { if (!it.hasFocus) repeats.reset() }
            .onPreviewKeyEvent { event -> repeats.swallow(event.type, event.key, shortcuts) }
            .keyboardShortcuts(*shortcuts)
            .focusRequester(focus)
            .focusable(),
    ) {
        content()
    }
    // ⚠ `runCatching`: a host composed while its window cannot take focus (a test with no window,
    // a detached subcomposition) is not an error worth crashing for.
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
}

/**
 * Tells a key's auto-repeat from a fresh press, so a shortcut fires once per press.
 *
 * ⚠ Tracked by key-down and key-up rather than read off the event: common Compose exposes no repeat
 * count, and a platform's native event is not reachable from common code.
 */
internal class KeyRepeatFilter {
    private val held = mutableSetOf<Key>()

    /** Forget every held key — the subtree lost focus, so the key-ups will not arrive here. */
    fun reset() = held.clear()

    /** `true` to consume [key] without firing: a repeat of a held key whose shortcut does not repeat. */
    fun swallow(type: KeyEventType, key: Key, shortcuts: Array<out KeyboardShortcut>): Boolean = when (type) {
        KeyEventType.KeyUp -> {
            held.remove(key)
            false
        }
        KeyEventType.KeyDown -> !held.add(key) && shortcuts.any { it.key == key && !it.repeats }
        else -> false
    }
}
