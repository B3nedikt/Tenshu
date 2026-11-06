package org.tenshu.photo.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.albumId
import org.tenshu.photo.contentId
import org.tenshu.photo.createdAt
import org.tenshu.photo.filePath
import org.tenshu.photo.name
import org.tenshu.photo.position
import org.tenshu.photo.PhotoService
import org.tenshu.photo.proxy.impl.allAlbumEntries
import org.tenshu.photo.addToAlbum
import org.tenshu.photo.deleteAlbum
import org.tenshu.photo.removeFromAlbum
import org.tenshu.photo.saveAlbum
import org.tenshu.photo.setPhotoFlags
import org.tenshu.photo.test.albumEntryRow
import org.tenshu.photo.test.albumRow
import org.tenshu.photo.test.createPhotoProxy
import org.tenshu.photo.test.environmentFor
import org.tenshu.photo.test.photoFlagsRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The album write path, end to end through storage (`photos-app.md` PH-2).
 *
 * ⚠⚠ Two of these are about what an op **must not** do — delete a photograph, and mint an entry it
 * cannot key. Those are the failures that would be discovered by a user rather than by a test.
 */
class AlbumOperationsTest {

    private val now = 1_785_000_000_000L

    private fun service(proxy: TestProxy): PhotoService =
        environmentFor(proxy).getService(PhotoService.ID)

    @Test
    fun savingAnAlbumTwicePreservesItsCreationDate() = runTest {
        var clock = now
        val proxy = createPhotoProxy(backgroundScope, nowEpochMs = { clock })
        val service = service(proxy)

        service.saveAlbum("holiday", "Holiday")
        clock = now + 86_400_000L
        service.saveAlbum("holiday", "Summer holiday")

        val album = assertNotNull(proxy.albumRow("holiday"))
        assertEquals("Summer holiday", album.name.get())
        // ⚠ The rename must not re-date the album — see SaveAlbum for why `createdAt` is not a
        // parameter at all.
        assertEquals(now, album.createdAt.get())
    }

    @Test
    fun addingTheSamePhotoTwiceMakesOneEntry() = runTest {
        val proxy = createPhotoProxy(backgroundScope, nowEpochMs = { now })
        val service = service(proxy)
        service.saveAlbum("holiday", "Holiday")

        service.addToAlbum("holiday", contentId = "c1", filePath = "/Backup/Phone/a.jpg")
        service.addToAlbum("holiday", contentId = "c1", filePath = "/Backup/Phone/a.jpg")

        assertEquals(1, entriesOf(proxy, "holiday").size, "add is idempotent by key")
    }

    @Test
    fun entriesLandAtTheEndInTheOrderTheyWereAdded() = runTest {
        val proxy = createPhotoProxy(backgroundScope, nowEpochMs = { now })
        val service = service(proxy)
        service.saveAlbum("holiday", "Holiday")

        service.addToAlbum("holiday", "c1", "/a.jpg")
        service.addToAlbum("holiday", "c2", "/b.jpg")
        service.addToAlbum("holiday", "c3", "/c.jpg")

        val byPosition = entriesOf(proxy, "holiday").sortedBy { it.position.get() }
        assertEquals(listOf("c1", "c2", "c3"), byPosition.map { it.contentId.get() })
        // Sparse by AlbumEntry.POSITION_STEP, so an insert between two neighbours is one write.
        assertEquals(
            listOf(0L, AlbumEntry.POSITION_STEP, AlbumEntry.POSITION_STEP * 2),
            byPosition.map { it.position.get() },
        )
    }

    @Test
    fun reAddingDoesNotReorder_butDoesHealTheStalePathHint() = runTest {
        val proxy = createPhotoProxy(backgroundScope, nowEpochMs = { now })
        val service = service(proxy)
        service.saveAlbum("holiday", "Holiday")
        service.addToAlbum("holiday", "c1", "/Backup/Phone/a.jpg")
        service.addToAlbum("holiday", "c2", "/Backup/Phone/b.jpg")

        // The user moved the first photo in Drive; the album read fell back to a content-id lookup
        // and re-adds it with the path it found. That is the self-healing hint in AlbumEntry.
        service.addToAlbum("holiday", "c1", "/Albums/2026/a.jpg")

        val entry = assertNotNull(proxy.albumEntryRow("holiday", "c1"))
        assertEquals("/Albums/2026/a.jpg", entry.filePath.get(), "the hint is refreshed")
        assertEquals(0L, entry.position.get(), "add is not a reorder")
    }

    @Test
    fun aPhotoWithNoContentIdIsRefused() = runTest {
        val proxy = createPhotoProxy(backgroundScope, nowEpochMs = { now })
        val service = service(proxy)
        service.saveAlbum("holiday", "Holiday")

        val result = service.addToAlbum("holiday", contentId = "", filePath = "/old.jpg")

        assertTrue(
            result is TResult.Failure,
            "⚠⚠ every empty-contentId entry would key on the same string and overwrite the last",
        )
        assertTrue(entriesOf(proxy, "holiday").isEmpty())
    }

    @Test
    fun deletingAnAlbumTakesItsEntriesAndNothingElse() = runTest {
        val proxy = createPhotoProxy(backgroundScope, nowEpochMs = { now })
        val service = service(proxy)
        service.saveAlbum("holiday", "Holiday")
        service.saveAlbum("pets", "Pets")
        service.addToAlbum("holiday", "c1", "/a.jpg")
        service.addToAlbum("holiday", "c2", "/b.jpg")
        service.addToAlbum("pets", "c3", "/c.jpg")

        service.deleteAlbum("holiday")

        assertNull(proxy.albumRow("holiday"))
        assertTrue(entriesOf(proxy, "holiday").isEmpty(), "orphan entries would sync to every device")
        // ⚠ The other album is untouched — the cascade is scoped by albumId, not by "everything".
        assertEquals(1, entriesOf(proxy, "pets").size)
    }

    @Test
    fun removingFromAnAlbumLeavesTheOthersWhereTheyWere() = runTest {
        val proxy = createPhotoProxy(backgroundScope, nowEpochMs = { now })
        val service = service(proxy)
        service.saveAlbum("holiday", "Holiday")
        service.addToAlbum("holiday", "c1", "/a.jpg")
        service.addToAlbum("holiday", "c2", "/b.jpg")
        service.addToAlbum("holiday", "c3", "/c.jpg")

        service.removeFromAlbum("holiday", "c2")

        val remaining = entriesOf(proxy, "holiday").sortedBy { it.position.get() }
        assertEquals(listOf("c1", "c3"), remaining.map { it.contentId.get() })
        // Ordering is the sequence of positions, not their values — the gap is not a bug.
        assertEquals(listOf(0L, AlbumEntry.POSITION_STEP * 2), remaining.map { it.position.get() })
    }

    @Test
    fun flagsRoundTripAndAreWrittenEvenWhenBothAreFalse() = runTest {
        val proxy = createPhotoProxy(backgroundScope, nowEpochMs = { now })
        val service = service(proxy)

        service.setPhotoFlags("c1", favourite = true, hidden = false)
        assertEquals(true, proxy.photoFlagsRow("c1")?.favourite?.get())

        service.setPhotoFlags("c1", favourite = false, hidden = false)
        // ⚠ A row, not a deletion: "un-favourited" and "never marked" must stay distinguishable,
        // or a peer's stale set wins by resurrection. See SetPhotoFlags.
        val row = assertNotNull(proxy.photoFlagsRow("c1"))
        assertEquals(false, row.favourite.get())
    }

    /** ⚠ The proxy's own read, which is `internal` and visible here because the test shares the
     * module — so what the assertions see is exactly what the ops see. */
    private fun entriesOf(proxy: TestProxy, albumId: String): List<AlbumEntry> =
        proxy.executionContext.allAlbumEntries().filter { it.albumId.get() == albumId }
}
