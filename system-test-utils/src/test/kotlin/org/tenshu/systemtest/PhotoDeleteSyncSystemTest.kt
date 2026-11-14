package org.tenshu.systemtest

import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.data.TrashResult
import app.tenshu.photos.model.Photo
import kotlinx.coroutines.runBlocking
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.TrashedPhoto
import org.tenshu.photo.albumId
import org.tenshu.photo.client.photoService
import org.tenshu.photo.contentId
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `:photo-service`'s deletes across two devices, over the real relay. `SyncObserver.onDataDeleted`
 * emits nothing, so each delete op owes its own tombstone; without one the row goes on the device
 * that deleted it and stays on every other (an album deleted on the phone is still on the tablet).
 *
 * Each test also re-creates the deleted row afterwards, because every one of these keys is reused —
 * the same photo goes back into the same album, or back into the trash — and a tombstone must not
 * outlive a later save of the same id (ADR 0006: a newer block recreates the entity).
 *
 * ⚠ The peer's rows are read raw where the repository's read would hide a missing tombstone: an
 * album's entries vanish from `albumsFor` once the album does, whether or not they were deleted.
 *
 * ## Stepped
 *
 * The system is stepped (`TestSystem` KDoc, `docs/testing.md` §12). Every wait is [converge]: first
 * [TestSystem.syncUntilQuiet], so both devices have pushed and pulled everything (a count of rounds,
 * not a 1 s poll per hop), then an await of the state through the repository — because what the
 * repository reads (the photo feed above all) is derived from the synced rows on the device's own
 * coroutines after ingest, which no sync step drives.
 */
class PhotoDeleteSyncSystemTest {

    @Test fun anAlbumDeletedOnOneDevice_isGoneOnThePeer_withItsEntries() = runBlocking {
        withPairedPhotos { host, joiner, hostPhotos, joinerPhotos ->
            val photo = uploadOnePhoto(host, hostPhotos)
            val albumId = assertNotNull(hostPhotos.createAlbum("Holiday"))
            assertTrue(hostPhotos.addToAlbum(albumId, photo))
            converge {
                joinerPhotos.albumIds() == listOf(albumId) && joiner.entryKeys() == listOf(albumId to photo.contentId)
            }

            assertTrue(hostPhotos.deleteAlbum(albumId))
            converge {
                joinerPhotos.albumIds() == emptyList<String>() && joiner.entryKeys() == emptyList<Pair<String, String>>()
            }
            assertEquals(emptyList(), joinerPhotos.albumIds(), "the album is gone on the peer")
            assertEquals(emptyList(), joiner.entryKeys(), "and so are its entries — the cascade's tombstones")

            // A new album of the same name is a new id, and reaches the peer as usual.
            val again = assertNotNull(hostPhotos.createAlbum("Holiday"))
            converge { joinerPhotos.albumIds() == listOf(again) }
        }
    }

    @Test fun aPhotoRemovedFromAnAlbumOnThePeer_leavesItOnTheHost_andReAddingComesBack() = runBlocking {
        withPairedPhotos { host, joiner, hostPhotos, joinerPhotos ->
            val photo = uploadOnePhoto(host, hostPhotos)
            val albumId = assertNotNull(hostPhotos.createAlbum("Family"))
            assertTrue(hostPhotos.addToAlbum(albumId, photo))
            converge {
                joinerPhotos.albumsFor(photo.contentId).map { it.albumId } == listOf(albumId)
            }

            // Removed on the PEER, so the tombstone travels the other way.
            assertTrue(joinerPhotos.removeFromAlbum(albumId, photo.contentId))
            converge { host.entryKeys().isEmpty() }
            assertEquals(emptyList(), hostPhotos.albumsFor(photo.contentId), "the host no longer lists it in the album")
            assertEquals(listOf(albumId), hostPhotos.albumIds(), "removing a photo leaves the album")

            // The same entry key again: the tombstone must not hold it down.
            assertTrue(hostPhotos.addToAlbum(albumId, photo))
            converge {
                joinerPhotos.albumsFor(photo.contentId).map { it.albumId } == listOf(albumId)
            }
        }
    }

    @Test fun aTrashRecordCleared_byRestoreOrDeleteForever_isClearedOnThePeer() = runBlocking {
        withPairedPhotos { host, joiner, hostPhotos, joinerPhotos ->
            val photo = uploadOnePhoto(host, hostPhotos)
            assertTrue(hostPhotos.trash(photo))
            converge { joinerPhotos.trashedIds() == listOf(photo.contentId) }

            // Restored on the PEER: the host's trash record must go too.
            assertTrue(joinerPhotos.restore(photo.contentId))
            converge { host.trashRecordIds().isEmpty() }
            assertEquals(emptyList(), host.trashRecordIds(), "a restore on the peer clears the host's record")

            // Trashed again — the same key — then deleted for good on the host.
            val restored = system.advanceUntilValue(hostPhotos) { feed -> feed.singleOrNull { it.contentId == photo.contentId } }
            assertTrue(hostPhotos.trash(restored))
            converge { joinerPhotos.trashedIds() == listOf(photo.contentId) }
            assertTrue(hostPhotos.deleteForever(photo.contentId))
            converge { joiner.trashRecordIds().isEmpty() }
            assertEquals(emptyList(), joiner.trashRecordIds(), "a delete-forever on the host clears the peer's record")
        }
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    private class Scope(val system: TestSystem) {
        /** Sync both devices until nothing moves, then await [predicate] — see the class KDoc. */
        suspend fun converge(predicate: suspend () -> Boolean) {
            system.syncUntilQuiet()
            system.advanceUntil(timeout = 30.seconds) { predicate() }
        }
    }

    private suspend fun withPairedPhotos(
        body: suspend Scope.(TestClient, TestClient, TenshuPhotosRepository, TenshuPhotosRepository) -> Unit,
    ) {
        val system = TestSystem.builder().stepped().build()
        try {
            val (host, joiner) = pairedHostAndJoiner(system)
            Scope(system).body(
                host,
                joiner,
                TenshuPhotosRepository(host.tenshuClient, reachability = null /* test */),
                TenshuPhotosRepository(joiner.tenshuClient, reachability = null /* test */),
            )
        } finally {
            system.shutdown()
        }
    }

    private suspend fun Scope.uploadOnePhoto(host: TestClient, photos: TenshuPhotosRepository): Photo {
        host.tenshuClient.fileService.defaultFileSystem
            .uploadFile("/", "photo.jpg", jpegBytes(64, 48), sourceModifiedAt = CAPTURED_AT)
            .valueOrFail("host uploadFile(/photo.jpg)")
        return system.advanceUntilValue(photos) { it.singleOrNull() }
            .also { assertTrue(it.contentId.isNotEmpty(), "an album or trash record is keyed on the contentId") }
    }

    /** The first non-null [pick] of [photos]' feed, advancing the system until there is one. */
    private suspend fun TestSystem.advanceUntilValue(
        photos: TenshuPhotosRepository,
        pick: (List<Photo>) -> Photo?,
    ): Photo {
        var found: Photo? = null
        syncUntilQuiet()
        advanceUntil(timeout = 30.seconds) {
            found = (photos.feedPage(Long.MAX_VALUE, pageSize = 10) as? PhotoPageResult.Page)?.photos?.let(pick)
            found != null
        }
        return assertNotNull(found)
    }

    private suspend fun TenshuPhotosRepository.albumIds(): List<String>? =
        (albums() as? AlbumsResult.Albums)?.albums?.map { it.albumId }?.sorted()

    private suspend fun TenshuPhotosRepository.trashedIds(): List<String>? =
        (trashed() as? TrashResult.Items)?.items?.map { it.contentId }

    /** Every `AlbumEntry` row, raw — including one whose album is already gone. */
    private suspend fun TestClient.entryKeys(): List<Pair<String, String>> =
        tenshuClient.photoService.albumEntries
            .select { fields(AlbumEntry.Field.ALBUM_ID, AlbumEntry.Field.CONTENT_ID) }
            .getAll()
            .valueOrFail("albumEntries")!!.toList()
            .map { it.albumId.get() to it.contentId.get() }

    /** Every `TrashedPhoto` row, raw — including one whose file has already moved. */
    private suspend fun TestClient.trashRecordIds(): List<String> =
        tenshuClient.photoService.trashedPhotos
            .select { fields(TrashedPhoto.Field.CONTENT_ID) }
            .getAll()
            .valueOrFail("trashedPhotos")!!.toList()
            .map { it.contentId.get() }

    private suspend fun pairedHostAndJoiner(system: TestSystem): Pair<TestClient, TestClient> {
        val host = system.client(deviceId = "host", role = TestClientRole.RealmHost)
        val joiner = system.client(deviceId = "joiner", role = TestClientRole.JoiningDevice)
        system.pair(host = host, joiner = joiner, hostName = "Host Desktop", joinerName = "Joiner Phone")
        return host to joiner
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) {
            image.setRGB(x, y, (x * 255 / width shl 16) or (y * 255 / height shl 8) or 0x40)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private companion object {
        const val CAPTURED_AT = 1_700_000_000_000L
    }
}
