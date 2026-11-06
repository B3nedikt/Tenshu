package app.tenshu.photos.ui.system

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * ⚠ `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, so a swipe from the edge brings the bars back
 * temporarily without leaving immersive mode. Without it a user who wants the clock has to find the
 * app's own toggle, and the gesture they will try first does nothing.
 *
 * ⚠⚠ The `DisposableEffect` restores the bars on the way out. The controller belongs to the
 * **window**, not to this composable, so a viewer closed while immersive would otherwise hand the
 * feed back with no status bar.
 */
@Composable
actual fun HideSystemBars(hidden: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    DisposableEffect(activity, hidden) {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
