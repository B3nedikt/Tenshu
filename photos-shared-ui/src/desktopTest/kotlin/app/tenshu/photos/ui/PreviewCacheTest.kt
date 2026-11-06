package app.tenshu.photos.ui

import app.tenshu.photos.data.PhotoPreview
import app.tenshu.photos.ui.components.PreviewCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The grid's byte memory (`photos-app.md` PH-G9). */
class PreviewCacheTest {

    private fun bytes(n: Int) = PhotoPreview(ByteArray(n) { 1 })

    @Test
    fun aPathIsFetchedOnceAndRememberedAfterwards() = runTest {
        val cache = PreviewCache()
        var fetches = 0

        repeat(5) { cache.bytes("/a.jpg") { fetches++; bytes(10) } }

        assertEquals(1, fetches, "the second reader must be served from memory")
    }

    /**
     * ⚠⚠ The rule that makes this safe on a byte-less device. `null` means *"the thumbnail has not
     * arrived yet"* — a wait, not an answer — so caching it would freeze the tile as a placeholder
     * for as long as the screen is open, and the blob landing a second later would change nothing.
     */
    @Test
    fun anAbsentThumbnailIsNotRemembered_becauseItMayArriveLater() = runTest {
        val cache = PreviewCache()
        var attempts = 0

        assertNull(cache.bytes("/a.jpg") { attempts++; null })
        assertNull(cache.bytes("/a.jpg") { attempts++; null })
        val arrived = cache.bytes("/a.jpg") { attempts++; bytes(10) }

        assertEquals(3, attempts, "an absent thumbnail is retried, not cached")
        assertEquals(10, arrived?.size)
        cache.bytes("/a.jpg") { attempts++; bytes(10) }
        assertEquals(3, attempts, "and once it arrives it is remembered like any other")
    }

    /**
     * ⚠⚠ Empty bytes are no answer (`photos-app.md` §36). Seen on a phone: a face's preview arrived as
     * 0 bytes, was cached, and the circle stayed blank until Photos restarted. It must come back as
     * `null` and be asked for again.
     */
    @Test
    fun emptyBytesAreNoAnswer_neverRememberedAndRetried() = runTest {
        val cache = PreviewCache()
        var attempts = 0

        assertNull(cache.bytes("/a.jpg") { attempts++; PhotoPreview(ByteArray(0)) })
        val arrived = cache.bytes("/a.jpg") { attempts++; bytes(10) }

        assertEquals(2, attempts, "the empty answer was not kept")
        assertEquals(10, arrived?.size)
    }

    /**
     * ⚠⚠ The same rule one size up (`drive-synced-thumbnails.md` §16). A large tile whose hero class
     * has not been minted yet is served the `LARGE` class as a stand-in — right to draw, wrong to
     * keep, or the tile stays blurry for the session after the backfill mints the real one.
     */
    @Test
    fun aStandInIsServedButNotRemembered_becauseTheClassItStandsInForMayArrive() = runTest {
        val cache = PreviewCache()
        var attempts = 0

        val standIn = cache.bytes("1024:/a.jpg") { attempts++; PhotoPreview(ByteArray(4), standIn = true) }
        val hero = cache.bytes("1024:/a.jpg") { attempts++; bytes(40) }
        cache.bytes("1024:/a.jpg") { attempts++; bytes(40) }

        assertEquals(4, standIn?.size, "the stand-in is still drawn")
        assertEquals(40, hero?.size, "the next read asks again and gets the real class")
        assertEquals(2, attempts, "which is then remembered")
        assertEquals(40, cache.heldBytesForTest(), "and the stand-in never took up budget")
    }

    /**
     * ⚠ Bounded in BYTES, not entries: the ladder mints WebP on Android and PNG elsewhere, and a
     * lossless 256 px PNG is an order of magnitude larger than its WebP twin, so an entry cap tuned
     * against one would be wrong on the other.
     */
    @Test
    fun theOldestEntriesAreEvictedWhenTheBudgetIsSpent() = runTest {
        val cache = PreviewCache(maxBytes = 100)

        cache.bytes("/a.jpg") { bytes(40) }
        cache.bytes("/b.jpg") { bytes(40) }
        cache.bytes("/c.jpg") { bytes(40) }   // 120 > 100 ⇒ /a.jpg goes

        assertTrue(cache.heldBytesForTest() <= 100, "the budget is respected")

        var refetched = 0
        cache.bytes("/a.jpg") { refetched++; bytes(40) }
        assertEquals(1, refetched, "the evicted entry is fetched again")

        var kept = 0
        cache.bytes("/c.jpg") { kept++; bytes(40) }
        assertEquals(0, kept, "the newest entry is still held")
    }

    /**
     * ⚠ A read counts as use. Without this the map is insertion-ordered rather than an LRU, and the
     * entry the grid keeps asking for is the one it evicts.
     */
    @Test
    fun readingAnEntryKeepsItFromBeingEvictedNext() = runTest {
        val cache = PreviewCache(maxBytes = 100)

        cache.bytes("/a.jpg") { bytes(40) }
        cache.bytes("/b.jpg") { bytes(40) }
        cache.bytes("/a.jpg") { bytes(40) }   // touch /a.jpg, so /b.jpg is now the oldest
        cache.bytes("/c.jpg") { bytes(40) }

        var aRefetched = 0
        cache.bytes("/a.jpg") { aRefetched++; bytes(40) }
        assertEquals(0, aRefetched, "the recently read entry survived")
    }

    /** ⚠ One payload bigger than the whole budget is served but not held — keeping it would evict
     * everything else for one tile. */
    @Test
    fun aPayloadLargerThanTheBudgetIsServedButNotHeld() = runTest {
        val cache = PreviewCache(maxBytes = 100)

        assertEquals(200, cache.bytes("/big.jpg") { bytes(200) }?.size)
        assertEquals(0, cache.heldBytesForTest(), "nothing is held")
    }

    /**
     * ⚠⚠ The flood half of PH-G9. A fling composes and disposes tiles faster than the proxy can
     * answer; without a ceiling every one of them is a chain read in flight at once.
     */
    @Test
    fun noMoreThanTheConfiguredNumberOfFetchesAreInFlightAtOnce() = runTest {
        val cache = PreviewCache(maxInFlight = 2)
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0

        val readers = (1..8).map { i ->
            async {
                cache.bytes("/p$i.jpg") {
                    running++
                    if (running > peak) peak = running
                    gate.await()
                    running--
                    bytes(10)
                }
            }
        }
        // ⚠ Let every reader reach the semaphore before releasing any of them, or the peak measures
        // scheduling rather than the ceiling.
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        readers.awaitAll()

        assertEquals(2, peak, "at most two fetches ran at once")
    }
}
