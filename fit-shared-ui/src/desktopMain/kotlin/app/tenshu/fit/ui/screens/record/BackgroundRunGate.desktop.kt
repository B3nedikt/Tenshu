package app.tenshu.fit.ui.screens.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop kills no long-running process to save battery — always exempt, nothing to ask for, so
 *  the pre-start cue never shows and Start proceeds straight through. */
@Composable
actual fun rememberBackgroundRunGate(): BackgroundRunGate = remember {
    object : BackgroundRunGate {
        override fun isExempt(): Boolean = true
        override fun requestExemption(force: Boolean) = Unit
    }
}
