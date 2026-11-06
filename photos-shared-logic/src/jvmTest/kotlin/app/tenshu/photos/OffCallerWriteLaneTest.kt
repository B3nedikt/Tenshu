package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.OffCallerPhotosRepository
import app.tenshu.photos.data.PhotosRepositoryDecorator
import app.tenshu.photos.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [OffCallerPhotosRepository] runs reads side by side and writes one at a time (`photos-ios.md`
 * §8.22.2) — on a real pool, because "at the same time" is not something a test scheduler has.
 *
 * ⚠⚠ Why writes: on iOS route 1 a write is several store operations (`addToAlbum` reads the last
 * position, then saves the next), the store's lock covers each operation and not the sequence, and
 * what kept two sequences apart was only that both ran on the main thread.
 */
class OffCallerWriteLaneTest {

    /** How many calls are inside the repository at once, at most. ⚠ A blocking sleep: a store query does not suspend. */
    private class Overlap : PhotosRepositoryDecorator(MockPhotosRepository()) {
        private val inside = AtomicInteger()
        val most = AtomicInteger()

        override fun <T> stream(name: String, flow: kotlinx.coroutines.flow.Flow<T>) = flow

        override suspend fun <T> call(name: String, block: suspend () -> T): T {
            most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            try {
                Thread.sleep(20)
                return block()
            } finally {
                inside.decrementAndGet()
            }
        }
    }

    private val photo = Photo("/p.jpg", 1_000L, isVideo = false, contentId = "c")

    @Test
    fun writesNeverOverlap() = runBlocking {
        val overlap = Overlap()
        val repository = OffCallerPhotosRepository(overlap, Dispatchers.Default)

        List(8) { n ->
            async {
                when (n % 4) {
                    0 -> repository.addToAlbum("a", photo)
                    1 -> repository.setFavourite("c", true)
                    2 -> repository.trash(photo)
                    else -> repository.renameAlbum("a", "b")
                }
            }
        }.awaitAll()

        assertEquals(1, overlap.most.get(), "two writes were inside the repository together")
    }

    /** The contrast: the lane is the writes', and a read does not wait behind another read. */
    @Test
    fun readsDoOverlap() = runBlocking {
        val overlap = Overlap()
        val repository = OffCallerPhotosRepository(overlap, Dispatchers.Default)

        List(8) { async { repository.allPhotosRead() } }.awaitAll()

        assertTrue(overlap.most.get() > 1, "reads ran one at a time: a walk of the library would hold every other read back")
    }
}
