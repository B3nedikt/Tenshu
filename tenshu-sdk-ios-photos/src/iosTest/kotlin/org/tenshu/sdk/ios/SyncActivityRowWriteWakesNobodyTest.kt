package org.tenshu.sdk.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.collection.list.flow
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.sync.client.catchUp
import org.tenshu.sync.client.syncService
import platform.Foundation.NSTemporaryDirectory
import kotlin.concurrent.AtomicInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does writing the catch-up row re-run **unrelated** observers — the Photos feed above all?
 * (`ios-sync-activity-indicator.md` §8, "not determined"; §3.1 kept the row at two writes per
 * catch-up partly because nobody knew.)
 *
 * A real route-1 Photos runtime over a test replica, with three observers open:
 * the feed's paging flow, another single-row relation of the same service, and the catch-up row
 * itself. The row is then written forty times.
 *
 * ⚠ This counts what the proxy re-delivers, not what `FeedGridView` redraws: the simulator has no
 * provisioned realm, so a real runtime cannot be put under the grid there. A feed that is handed
 * no new `PagingData` has nothing to draw.
 */
class SyncActivityRowWriteWakesNobodyTest {

    @Test
    fun fortyWritesOfTheStatusRow_wakeItsOwnReader_andNeitherTheFeedNorASiblingRelation() = runBlocking {
        val assembly = PhotosLocalRuntime.assemble(
            replicaDirectory = NSTemporaryDirectory() + "sync-activity-wakes-" + Random.nextLong().toString(16),
            secureStorage = InMemorySecureStorageDelegate(),
        )
        val sync = assembly.tenshuClient.syncService
        val feed = AtomicInt(0)
        val sibling = AtomicInt(0)
        val own = AtomicInt(0)
        val observers = CoroutineScope(Dispatchers.Default)
        observers.launch { assembly.photosRepository.feedPaging().collect { feed.incrementAndGet() } }
        observers.launch { sync.contentUploadProgresses.flow().collect { sibling.incrementAndGet() } }
        observers.launch { sync.catchUp("app.tenshu.photos.ios").collect { own.incrementAndGet() } }
        try {
            withTimeout(30_000) { sync.catchUp("app.tenshu.photos.ios").first() }
            delay(3_000) // every observer has had its first value

            val idleFeed = feed.value
            val idleSibling = sibling.value
            delay(4_000) // the control: the same length of time with no writes
            val controlFeed = feed.value - idleFeed
            val controlSibling = sibling.value - idleSibling

            val (feedBefore, siblingBefore, ownBefore) = Triple(feed.value, sibling.value, own.value)
            val pulses = SyncActivitySurface.debugPulseRow(times = 20, gapMs = 100)
            delay(1_000)
            val feedDuring = feed.value - feedBefore
            val siblingDuring = sibling.value - siblingBefore
            val ownDuring = own.value - ownBefore

            println(
                "SyncActivityRowWriteWakesNobodyTest: rowWrites=${pulses * 2} ownReader=$ownDuring " +
                    "feed=$feedDuring (control $controlFeed) siblingRelation=$siblingDuring (control $controlSibling)",
            )
            assertEquals(20, pulses, "the real row was written")
            assertTrue(ownDuring >= 10, "the row's own reader followed it: $ownDuring")
            assertEquals(controlFeed, feedDuring, "the feed was handed new pages by a status-row write")
            assertEquals(controlSibling, siblingDuring, "a sibling relation's reader was woken")
        } finally {
            observers.cancel()
        }
    }
}
