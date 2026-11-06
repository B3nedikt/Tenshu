package app.tenshu.control.ui.screens.services

import androidx.compose.runtime.Composable

/**
 * The foreground UI a store's purchase sheet attaches to: the `Activity` on Android, null where no
 * store sells (desktop).
 */
@Composable
expect fun rememberStorePurchaseHost(): Any?
