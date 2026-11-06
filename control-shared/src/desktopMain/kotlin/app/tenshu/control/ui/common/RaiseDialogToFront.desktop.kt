package app.tenshu.control.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import app.tenshu.control.data.requestForegroundForThisProcess

@Composable
actual fun RaiseDialogToFront() {
    LaunchedEffect(Unit) {
        // ⚠ One frame first. This runs on the dialog's first composition, at which point its
        // platform window is still being realised — raising it before it is showing is a no-op, and
        // a silent one.
        withFrameNanos { }
        raiseDialogWindows()
    }
}

/**
 * ⚠⚠ **Both halves, in this order.** Activation first: if Control is not the active application,
 * nothing done to the window ordering *inside* it can be seen. Then the within-application
 * ordering, for a dialog created behind its own parent.
 *
 * ⚠ Matches any showing [java.awt.Dialog] rather than a specific Compose class. The previous
 * version filtered on `ComposeDialog`, which was never confirmed to be what these dialogs are — a
 * filter that matches nothing fails **silently**, which is consistent with two attempts that
 * changed nothing on the device. `java.awt.Dialog` is the primitive underneath either wrapper.
 *
 * ⚠ The main window is a `Frame`, not a `Dialog`, so it is excluded by construction — this can
 * never yank the whole app's main window forward on its own.
 */
private fun raiseDialogWindows() {
    runCatching { requestForegroundForThisProcess() }

    val showing = java.awt.Window.getWindows().filter { it.isShowing }
    // ⚠ Temporary, and load-bearing while this is unverified: three fixes here have failed with no
    // signal at all. If the raise does not work on device, this line says whether the window list is
    // even what we think it is — a diagnosis the previous attempts had no way to make.
    println(
        "RaiseDialogToFront: showing windows = " +
            showing.joinToString { "${it::class.simpleName}(dialog=${it is java.awt.Dialog})" }
    )

    showing.filterIsInstance<java.awt.Dialog>().forEach {
        runCatching {
            it.toFront()
            it.requestFocus()
        }
    }
}
