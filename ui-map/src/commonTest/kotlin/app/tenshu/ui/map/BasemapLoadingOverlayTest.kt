package app.tenshu.ui.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The show/hide decision behind [BasemapLoadingOverlay]; the fade itself is visual. */
class BasemapLoadingOverlayTest {

    @Test
    fun loading_afterTheDelay_shows() {
        assertTrue(shouldShowBasemapLoading(BasemapLoadPhase.Loading, delayElapsed = true))
    }

    @Test
    fun loading_beforeTheDelay_staysHidden() {
        // A fast load must never flash the spinner.
        assertFalse(shouldShowBasemapLoading(BasemapLoadPhase.Loading, delayElapsed = false))
    }

    @Test
    fun loaded_hides() {
        assertFalse(shouldShowBasemapLoading(BasemapLoadPhase.Loaded, delayElapsed = true))
        assertFalse(shouldShowBasemapLoading(BasemapLoadPhase.Loaded, delayElapsed = false))
    }

    @Test
    fun failed_hides_soTheFailureHandlingWins() {
        assertFalse(shouldShowBasemapLoading(BasemapLoadPhase.Failed, delayElapsed = true))
        assertFalse(shouldShowBasemapLoading(BasemapLoadPhase.Failed, delayElapsed = false))
    }
}
