package app.tenshu.control.ui.common

import androidx.compose.runtime.Composable

/**
 * Bring the dialog this is called from to the front, and the application with it.
 *
 * ## ⚠⚠ Why a modal can be invisible at all
 *
 * On desktop a Compose `AlertDialog` is a real platform window (`AppDialog` → `AlertDialog` →
 * `androidx.compose.ui.window.Dialog`, all the same primitive). A dialog opened straight from a
 * click inherits the front-most position its parent already has — which is why every dialog in
 * Control looks fine. One raised **asynchronously** does not: by the time it exists the user may be
 * in another application, and it is created behind its own parent, behind that application, or both.
 *
 * Device-observed 2026-08-26 on the (since-removed) forget-outcome dialog: Compose reported it
 * composed, the state behind it was correct, the composition was alive — and nothing was on screen
 * until Control's main window was closed out of the way.
 *
 * ## ⚠⚠ It takes BOTH raises
 *
 * Two independent failures, identical to the user, each with a fix that does nothing for the other:
 *
 *  - **Behind its own parent** — one of our windows under another of our windows. `toFront()`
 *    reorders *within* an application, which is exactly this.
 *  - **The whole application is behind another app.** `toFront()` was measured to do nothing here:
 *    macOS will not let an inactive process steal activation that way. This needs cross-application
 *    activation — `Desktop.requestForeground` → `[NSApp activateIgnoringOtherApps:]`
 *    (`control-keep-device-unlocked.md` §6.1).
 *
 * ## ⚠⚠ UNVERIFIED, and three attempts have already failed here
 *
 * An earlier version of this seam shipped `toFront()` alone, then `toFront()` plus activation, and
 * **neither made the dialog appear**. The most likely reason is that it filtered the window list to
 * `ComposeDialog` — a class it never confirmed these dialogs are — so it may simply have matched
 * nothing and done nothing, silently. This version matches any showing `java.awt.Dialog`, which is
 * strictly broader, and **logs what it found** so the next device run settles it either way rather
 * than producing a fourth guess.
 *
 * ⚠ The focus-steal objection is answered by *what this is for*: a modal the flow cannot continue
 * past until the user acts on it. Raising a window someone is being made to wait on is not a steal.
 * Do not reach for this on anything the user has not been made to wait for.
 *
 * No-op on Android, where dialogs are drawn in the host window and cannot be occluded this way.
 */
@Composable
expect fun RaiseDialogToFront()
