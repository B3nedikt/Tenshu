package org.tenshu.sdk.ios

import org.tenshu.viewmodel.test.withOwnedViewModels
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CX-2b wiring: *Keep in sync* needs a repository that stores mirrors, so the not-connected surface
 * offers *Copy once* only — and refreshing on foreground is a no-op rather than a crash.
 */
class CalendarSurfaceMirrorTest {

    @Test
    fun the_not_connected_surface_copies_once_and_refreshes_nothing() = withOwnedViewModels { vms ->
        CalendarSurface.installNotConnected()

        // ⚠ Owned with no `Main` installed: the join waits on nothing only because this model
        // launches no work until `open()`. A test that calls `open()` must install a `Main` first.
        val copy = vms.own(CalendarSurface.externalCopyViewModel())
        assertTrue(copy.supported, "EventKit is always there to copy from")
        assertFalse(copy.canKeepInSync, "nothing stores a mirror without a real repository")

        CalendarSurface.refreshMirrors()
    }
}
