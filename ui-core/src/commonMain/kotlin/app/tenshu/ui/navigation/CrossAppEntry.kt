package app.tenshu.ui.navigation

/**
 * Show [destination] as the app's **only** screen, because another app asked for it
 * (`control-cross-app-open.md` CO-D11).
 *
 * ⚠⚠ **A cross-app open is an entry point, not a navigation step, and the difference is the whole
 * of this function.** Pushing the target onto the stack leaves the app's own root underneath it, so
 * back goes *into* the app — onto a Home screen the user never asked for — and only a second back
 * returns them to the app they actually came from. That is what Drive did: tap a photo in Timeline,
 * land on the file, press back, and arrive at Drive's Home tab.
 *
 * ⚠ With one entry the nav host's back handler is disabled, so the platform's own back takes over:
 * on Android that finishes the activity and the system returns the user to the calling task, which
 * is the standard contract for an entry point that comes from outside the app. Desktop and iOS keep
 * whatever their shells already do at a single-entry stack.
 *
 * ⚠ The cost, stated rather than hidden: an app that was already open loses its place in its own
 * history. That is a navigation position and not data, and the user had already left it for the app
 * that sent them here.
 */
fun <T> MutableList<T>.enterFromAnotherApp(destination: T) {
    clear()
    add(destination)
}

/**
 * Go back one screen — or, when there is no screen to go back to, leave the app via [exitApp].
 *
 * ⚠⚠ **Popping the last entry is not a no-op, it is a crash**: `NavDisplay` requires a non-empty
 * back stack (`require(backStack.isNotEmpty())`) and every back arrow in the app is a plain pop. The
 * bottom of the stack was unreachable while it was always the app's own root; [enterFromAnotherApp]
 * makes it reachable, so this guard ships with that one and not after it.
 *
 * ⚠ And "do nothing" would be the wrong answer even without the crash: a user pressing back on the
 * one screen another app sent them to means *take me back there*. See [rememberAppExit] for what
 * [exitApp] can and cannot be — it is `null` on the platforms with no such move, and there this
 * really does nothing.
 */
fun <T> MutableList<T>.popOrLeaveApp(exitApp: (() -> Unit)?) {
    if (size > 1) removeAt(size - 1) else exitApp?.invoke()
}
