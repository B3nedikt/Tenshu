package org.tenshu.sdk.ios

import app.tenshu.ingest.backup.MediaBucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The iOS half of the new-album backup prompt
 * (`drive-synced-folders-new-folder-prompt.md`).
 *
 * PhotoKit and `UNUserNotificationCenter` are framework surfaces a unit test cannot
 * reach, so what is asserted here is the seam: that the announcer forwards exactly
 * what Swift needs, and that a missing bridge degrades to inert rather than
 * throwing. The album/library overlap rule is enforced in `IosMediaSourceProvider`
 * against a live library and is covered by the on-device pass.
 */
class NewFolderPromptIosTest {

    private class RecordingBridge : NewFolderPromptBridge {
        val announced = mutableListOf<Triple<String, String, Int>>()
        override fun announce(bucketId: String, displayName: String, itemCount: Int) {
            announced += Triple(bucketId, displayName, itemCount)
        }
    }

    @Test
    fun announcer_forwardsBucketIdentityToSwift() = runTest {
        val bridge = RecordingBridge()
        val announcer = IosNewFolderAnnouncer { bridge }

        announcer.announce(
            MediaBucket(id = "ABC-123/L0/040", displayName = "Holiday", itemCount = 42)
        )

        assertEquals(listOf(Triple("ABC-123/L0/040", "Holiday", 42)), bridge.announced)
    }

    @Test
    fun announcer_isInertWhenSwiftNeverInstalledABridge() = runTest {
        // DEV harnesses and unit runs never install one. The album is still recorded as
        // known by the caller, so this reads as "announced and ignored" — never a
        // repeat-forever loop, and never a crash.
        val announcer = IosNewFolderAnnouncer { null }

        announcer.announce(MediaBucket(id = "x", displayName = "X", itemCount = 3))
    }

    @Test
    fun announcer_readsTheBridgeLazilySoInstallOrderDoesNotMatter() = runTest {
        // start() runs during runtime install; the Swift bridge is installed from
        // App.init. Neither ordering is guaranteed, so the slot is read per announce.
        var bridge: NewFolderPromptBridge? = null
        val announcer = IosNewFolderAnnouncer { bridge }

        announcer.announce(MediaBucket(id = "early", displayName = "Early", itemCount = 5))

        val late = RecordingBridge()
        bridge = late
        announcer.announce(MediaBucket(id = "late", displayName = "Late", itemCount = 7))

        assertEquals(1, late.announced.size)
        assertTrue(late.announced.single().first == "late")
    }
}
