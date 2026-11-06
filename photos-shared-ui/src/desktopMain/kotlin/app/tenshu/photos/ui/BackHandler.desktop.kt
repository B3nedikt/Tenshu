package app.tenshu.photos.ui

import androidx.compose.runtime.Composable

/** ⚠ A desktop window has no back gesture — nothing to intercept, so nothing to implement. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
