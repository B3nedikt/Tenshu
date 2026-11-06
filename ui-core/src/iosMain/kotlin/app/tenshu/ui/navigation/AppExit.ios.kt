package app.tenshu.ui.navigation

import androidx.compose.runtime.Composable

/**
 * ⚠ Nothing. iOS has no "finish the activity" and an app may not terminate itself — the way back to
 * the previous app is the system's own back-to-app affordance, which is outside this app entirely.
 */
@Composable
actual fun rememberAppExit(): (() -> Unit)? = null
