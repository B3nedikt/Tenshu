package app.tenshu.fit.ui.screens.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop kills no app to save battery and keeps no auto-start list — nothing to open, so the
 *  cue and the review-screen offer never appear. */
@Composable
actual fun rememberOemBackgroundSettings(): OemBackgroundSettings = remember {
    object : OemBackgroundSettings {
        override fun canOpen(): Boolean = false
        override fun openBackgroundSettings() = Unit
    }
}
