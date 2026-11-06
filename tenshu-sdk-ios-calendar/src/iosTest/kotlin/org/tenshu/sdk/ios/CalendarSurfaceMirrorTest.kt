package org.tenshu.sdk.ios

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CX-2b wiring: *Keep in sync* needs a repository that stores mirrors, so the not-connected surface
 * offers *Copy once* only — and refreshing on foreground is a no-op rather than a crash.
 */
class CalendarSurfaceMirrorTest {

    @Test
    fun the_not_connected_surface_copies_once_and_refreshes_nothing() {
        CalendarSurface.installNotConnected()

        val copy = CalendarSurface.externalCopyViewModel()
        assertTrue(copy.supported, "EventKit is always there to copy from")
        assertFalse(copy.canKeepInSync, "nothing stores a mirror without a real repository")

        CalendarSurface.refreshMirrors()
    }
}
