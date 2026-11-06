package app.tenshu.ui.navigation

import androidx.compose.runtime.Composable

/**
 * Leave this app the way the platform's own back gesture would, or `null` where the platform has no
 * such move.
 *
 * ⚠⚠ **This exists because [enterFromAnotherApp] can leave a back stack with exactly one entry that
 * is not the app's own root.** Every `navigateUp` in this codebase pops, and popping the last entry
 * is not merely wrong — `NavDisplay` *requires* a non-empty back stack and throws, so a back arrow
 * on a cross-app entry would crash the app rather than do nothing.
 *
 * ⚠ The honest fallback is not "pop anyway" and not "disable the arrow": the user pressing back on
 * a screen they arrived at from another app means *take me out of here*, and on Android finishing
 * the activity is exactly that — the system returns them to the task that sent them.
 *
 * ⚠ `null` on desktop and iOS. A desktop window's exit is its own close button, and closing it
 * because a detail screen ran out of history would be a surprise, not a service. Callers must treat
 * `null` as "do nothing" and never as "pop the last entry".
 */
@Composable
expect fun rememberAppExit(): (() -> Unit)?
