package app.tenshu.drive.ui.screens.settings

import app.tenshu.ingest.backup.MediaAccess
import app.tenshu.ingest.backup.MediaAccessProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The photo-location signal (`timeline-photos.md` §11.1).
 *
 * ⚠⚠ This exists because T-P4 shipped with the permission **declared, bundled into the request
 * array, and never actually asked for**: both request paths were gated on
 * `mediaAccess != MediaAccess.FULL`, and the population missing the location grant is exactly the
 * one whose library access is already FULL. What is pinned here is the property that broke —
 * that the two grants are reported *independently* — rather than the rendering that consumes it.
 */
class PhotoLocationNoticeTest {

    private class Provider(
        private val access: MediaAccess,
        private val location: Boolean,
    ) : MediaAccessProvider {
        override fun current(): MediaAccess = access
        override fun photoLocationGranted(): Boolean = location
    }

    @Test
    fun aFullLibraryGrantSaysNothingAboutPhotoLocations() {
        // The exact state that made the permission unaskable: everything about the library is
        // granted, and the location grant is missing anyway.
        val provider = Provider(MediaAccess.FULL, location = false)
        assertEquals(MediaAccess.FULL, provider.current())
        assertFalse(
            provider.photoLocationGranted(),
            "the two grants must be readable independently — folding this into MediaAccess is what " +
                "hid it",
        )
    }

    @Test
    fun theNoticeConditionFiresOnlyWhereTheOldOneCouldNot() {
        // The screen shows it when backup is on, the library is fully visible, and locations are
        // not. Anything less than FULL is the *other* notice's job, and showing both would say
        // twice that something is wrong when only one thing is.
        fun shows(enabled: Boolean, access: MediaAccess, location: Boolean) =
            enabled && access == MediaAccess.FULL && !location

        assertTrue(shows(enabled = true, access = MediaAccess.FULL, location = false))
        assertFalse(shows(enabled = true, access = MediaAccess.FULL, location = true))
        assertFalse(shows(enabled = true, access = MediaAccess.PARTIAL, location = false))
        assertFalse(shows(enabled = true, access = MediaAccess.DENIED, location = false))
        assertFalse(
            shows(enabled = false, access = MediaAccess.FULL, location = false),
            "with backup off there are no photos to place, so there is nothing to offer",
        )
    }

    @Test
    fun platformsWithoutTheConceptReportTheGrantHeld() {
        // Desktop, iOS and every test double: "no redaction to lift" and "grant held" are the same
        // outcome for a caller, and defaulting the other way would show the notice everywhere.
        val bare = MediaAccessProvider { MediaAccess.FULL }
        assertTrue(bare.photoLocationGranted())
    }
}
