package app.tenshu.control.ui.common

import androidx.compose.runtime.Composable

/**
 * No-op. Android dialogs are drawn in the host window's own hierarchy, so there is no sibling
 * window that could occlude one — the failure this seam exists for cannot occur here.
 */
@Composable
actual fun RaiseDialogToFront() = Unit
