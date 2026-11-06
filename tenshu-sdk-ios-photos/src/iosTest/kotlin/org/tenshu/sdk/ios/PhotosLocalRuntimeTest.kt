package org.tenshu.sdk.ios

import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.NotConnectedPhotosRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PIO-1 coverage (`photos-ios.md`): the route-1 in-process Photos runtime assembles the FULL
 * production proxy graph in this process and serves Photos' reads over a replica directory. The
 * Photos twin of [ContactsLocalRuntimeTest].
 *
 * ⚠ Written on Linux, where nothing iOS compiles: the first Mac pass (HANDOFF H1) is its first run.
 */
class PhotosLocalRuntimeTest {

    private fun assemble() = PhotosLocalRuntime.assemble(
        replicaDirectory = NSTemporaryDirectory() + "photos-local-runtime-test-" + Random.nextLong().toString(16),
        secureStorage = InMemorySecureStorageDelegate(),
    )

    @Test
    fun theRuntimeReachesTheInProcessFileService() = runBlocking {
        // ⚠ Emptiness alone proves nothing — an unwired service reads as empty too. The
        // reachability verdict separates "reached a real service, it has nothing" from "never
        // reached one". ⚠⚠ Wait for it: `null` is "not yet known" (ContactsLocalRuntimeTest).
        val assembly = assemble()
        val reachable = withTimeout(30_000) {
            assembly.photosRepository.controlReachable().first { it == true }
        }
        assertEquals(true, reachable)
    }

    @Test
    fun theAlbumsReadReachesTheInProcessPhotoService() = runBlocking {
        // `:photo-service` is the one service Photos owns; `initPhotoService` in the iOS
        // PlatformProxyModule is the claim "no new service wiring" rests on.
        val albums = assemble().photosRepository.albums()
        assertIs<AlbumsResult.Albums>(albums, "an unwired photo-service answers Unreachable/Failed: $albums")
        assertTrue(albums.albums.isEmpty(), "a fresh replica has no albums")
    }

    @Test
    fun anAlbumWrittenIsReadBack() = runBlocking {
        val repository = assemble().photosRepository
        val id = repository.createAlbum("Lisbon")
        assertNotNull(id, "createAlbum must succeed before the read can be blamed")
        val albums = withTimeout(30_000) {
            var read = repository.albums()
            while (read !is AlbumsResult.Albums || read.albums.none { it.albumId == id }) read = repository.albums()
            read
        }
        assertEquals("Lisbon", albums.albums.single { it.albumId == id }.name)
    }

    @Test
    fun theSurfaceDefaultsToNotConnected() = runBlocking {
        PhotosSurface.installNotConnected()
        assertEquals(false, PhotosSurface.controlReachable().first())
        PhotosSurface.installRepository(NotConnectedPhotosRepository())
    }
}
