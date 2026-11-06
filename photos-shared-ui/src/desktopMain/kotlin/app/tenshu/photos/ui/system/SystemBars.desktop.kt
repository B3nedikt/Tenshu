package app.tenshu.photos.ui.system

import androidx.compose.runtime.Composable

/**
 * ⚠⚠ A deliberate no-op: a desktop window has no status or navigation bar to hide, so there is
 * nothing here to implement and nothing missing. The viewer's own chrome still hides — that half is
 * common code — which is the whole of what immersive mode can mean on this platform.
 */
@Composable
actual fun HideSystemBars(hidden: Boolean) = Unit
