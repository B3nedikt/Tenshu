package app.tenshu.ui.accessibility

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityIsVoiceOverRunning

/**
 * Read once per composition entry. ⚠ The SwiftUI apps do not render Compose screens (`ui-core`'s
 * iOS targets serve `:ui-map`), so this exists for completeness of the `expect`, not for a live
 * consumer; the SwiftUI twin reads `UIAccessibility.isVoiceOverRunning` itself.
 */
@Composable
actual fun rememberAccessibilityPreferences(): AccessibilityPreferences = remember {
    AccessibilityPreferences(
        screenReaderActive = UIAccessibilityIsVoiceOverRunning(),
        reduceMotion = UIAccessibilityIsReduceMotionEnabled(),
    )
}
