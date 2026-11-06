package app.tenshu.macos

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Pins the picker primitive's explicit no-op contract off macOS without opening UI in tests. */
class MacSecurityScopedFolderPickerTest {
    @Test
    fun offMacOs_thePickerDegradesWithoutTouchingAppKit() {
        val mac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
        if (mac) return

        assertFalse(MacSecurityScopedFolderPicker.isAvailable)
        assertNull(MacSecurityScopedFolderPicker.pickFolder())
    }
}
