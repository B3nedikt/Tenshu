package app.tenshu.ui.navigation

import androidx.compose.runtime.Composable

/** ⚠ Nothing: a desktop window is closed by the user, never by running out of back stack. */
@Composable
actual fun rememberAppExit(): (() -> Unit)? = null
