package app.tenshu.drive.backup

import app.tenshu.ingest.backup.MediaBucket
import app.tenshu.ingest.backup.MIN_NEW_FOLDER_ITEMS
import app.tenshu.ingest.backup.scanForNewFolders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure half of the "a new folder appeared — back it up?" prompt
 * (`drive-synced-folders-new-folder-prompt.md`). Every anti-nag guarantee the
 * feature makes is a property of `scanForNewFolders`, so they are asserted here
 * rather than on-device.
 */
class NewFolderDetectorTest {

    private fun bucket(id: String, items: Int = 10) =
        MediaBucket(id = id, displayName = id.replaceFirstChar { it.uppercase() }, itemCount = items)

    @Test
    fun `empty baseline seeds silently and announces nothing`() {
        // The upgrade case: every existing install arrives with an empty known-set.
        // Announcing here would fire a notification per folder on the device.
        val scan = scanForNewFolders(
            buckets = listOf(bucket("camera"), bucket("screenshots"), bucket("whatsapp")),
            known = emptySet(),
            selected = emptySet(),
        )

        assertTrue(scan.announce.isEmpty())
        assertEquals(setOf("camera", "screenshots", "whatsapp"), scan.known)
    }

    @Test
    fun `a bucket absent from the baseline is announced`() {
        val scan = scanForNewFolders(
            buckets = listOf(bucket("camera"), bucket("whatsapp")),
            known = setOf("camera"),
            selected = setOf("camera"),
        )

        assertEquals(listOf("whatsapp"), scan.announce.map { it.id })
    }

    @Test
    fun `an announced bucket is folded into the known-set immediately`() {
        // The one-shot guarantee: ignoring the notification is a permanent "no",
        // because the *announcement*, not the answer, marks the folder known.
        val first = scanForNewFolders(
            buckets = listOf(bucket("camera"), bucket("whatsapp")),
            known = setOf("camera"),
            selected = emptySet(),
        )
        assertEquals(listOf("whatsapp"), first.announce.map { it.id })

        val second = scanForNewFolders(
            buckets = listOf(bucket("camera"), bucket("whatsapp")),
            known = first.known,
            selected = emptySet(),
        )
        assertTrue(second.announce.isEmpty())
    }

    @Test
    fun `an already-selected bucket is never announced`() {
        // A folder in active backup is not news, even if it predates the known-set.
        val scan = scanForNewFolders(
            buckets = listOf(bucket("camera")),
            known = setOf("screenshots"),
            selected = setOf("camera"),
        )

        assertTrue(scan.announce.isEmpty())
        assertTrue("camera" in scan.known)
    }

    @Test
    fun `a bucket below the item threshold is not announced and stays unknown`() {
        // Staying unknown is the point: it must still be announced once it grows.
        val small = scanForNewFolders(
            buckets = listOf(bucket("scratch", items = MIN_NEW_FOLDER_ITEMS - 1)),
            known = setOf("camera"),
            selected = emptySet(),
        )
        assertTrue(small.announce.isEmpty())
        assertTrue("scratch" !in small.known)

        val grown = scanForNewFolders(
            buckets = listOf(bucket("scratch", items = MIN_NEW_FOLDER_ITEMS)),
            known = small.known,
            selected = emptySet(),
        )
        assertEquals(listOf("scratch"), grown.announce.map { it.id })
    }

    @Test
    fun `a burst is capped and the remainder is picked up by the next scan`() {
        // A device restore can materialise a dozen buckets at once; the cap turns a
        // wall of notifications into a trickle without dropping any folder.
        val buckets = (1..5).map { bucket("bucket$it", items = it * 10) }

        val first = scanForNewFolders(
            buckets = buckets,
            known = setOf("camera"),
            selected = emptySet(),
            maxAnnouncements = 2,
        )
        // Largest first — the folder most likely worth backing up leads.
        assertEquals(listOf("bucket5", "bucket4"), first.announce.map { it.id })

        val second = scanForNewFolders(
            buckets = buckets,
            known = first.known,
            selected = emptySet(),
            maxAnnouncements = 2,
        )
        assertEquals(listOf("bucket3", "bucket2"), second.announce.map { it.id })

        val third = scanForNewFolders(
            buckets = buckets,
            known = second.known,
            selected = emptySet(),
            maxAnnouncements = 2,
        )
        assertEquals(listOf("bucket1"), third.announce.map { it.id })
    }

    @Test
    fun `a disappeared bucket does not fall out of the known-set`() {
        // An SD card unmounting must not make its folders "new" again on remount.
        val scan = scanForNewFolders(
            buckets = listOf(bucket("camera")),
            known = setOf("camera", "sdcard"),
            selected = emptySet(),
        )

        assertTrue("sdcard" in scan.known)
    }
}
