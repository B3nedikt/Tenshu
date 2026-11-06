package app.tenshu.fit.ui.screens.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop keeps no per-app record of how a process died — nothing to report, so the review screen
 *  keeps its neutral "was interrupted" wording. */
@Composable
actual fun rememberProcessDeathReporter(): ProcessDeathReporter = remember {
    object : ProcessDeathReporter {
        override fun lastSystemKillWallMs(): Long? = null
    }
}
