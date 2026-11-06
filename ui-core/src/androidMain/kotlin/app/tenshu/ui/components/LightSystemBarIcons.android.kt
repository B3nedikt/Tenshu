package app.tenshu.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun LightSystemBarIcons() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val statusWas = controller.isAppearanceLightStatusBars
        val navigationWas = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = statusWas
            controller.isAppearanceLightNavigationBars = navigationWas
        }
    }
}
