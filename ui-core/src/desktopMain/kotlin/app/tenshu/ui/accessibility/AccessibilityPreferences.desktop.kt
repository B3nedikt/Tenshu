package app.tenshu.ui.accessibility

import androidx.compose.runtime.Composable

/** ⚠ No reliable screen-reader or reduced-motion signal on the JVM desktop: both report off. */
@Composable
actual fun rememberAccessibilityPreferences(): AccessibilityPreferences = AccessibilityPreferences()
