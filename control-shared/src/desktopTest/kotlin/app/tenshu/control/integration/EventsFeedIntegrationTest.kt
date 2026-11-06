package app.tenshu.control.integration

import androidx.paging.testing.asSnapshot
import app.tenshu.control.data.Event
import app.tenshu.control.data.EventRepository
import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.onFailure
import org.tenshu.file.client.fileService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [EventRepository.getEvents] over the real in-process
 * proxy chain — the events-feed twin of Drive's `FilesPagingIntegrationTest`
 * (`docs/proposals/notification-burst-aggregation.md` §2.3). Pins the pieces
 * the unit tests can't: the chain `sortByDescending { timestamp }` actually
 * sorting the notification relation proxy-side, the windowed
 * `drop().take().flow()` reads riding it, and the producer-coalesced
 * `FileAddedNotification` (`count`) surfacing as ONE "N files added" event.
 */
class EventsFeedIntegrationTest {

    @Before
    fun setup() {
        startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
    }

    @After
    fun tearDown() {
        stopAppIntegrationKoin()
    }

    @Test
    fun burstUpload_surfacesAsOneCoalescedEvent_sortedNewestFirst() = runTest {
        val client = GlobalContext.get().get<TenshuClient>()
        val events = GlobalContext.get().get<EventRepository>()

        // A folder creation (its own event) + a three-file burst (uploads run
        // milliseconds apart — well inside the producer's 60 s window, so they
        // coalesce into ONE FileAddedNotification row with count = 3).
        client.fileService.defaultFileSystem.createFolder("/docs").onFailure { error(it) }
        repeat(3) { n ->
            client.fileService.defaultFileSystem
                .uploadFile("/", "file$n.txt", byteArrayOf(1, 2, 3))
                .onFailure { error(it) }
        }

        val snapshot = events.getEvents().asSnapshot()

        // The burst is one MultipleAdded event, not three Added rows.
        val multi = snapshot.filterIsInstance<Event.FileEvent.MultipleAdded>()
        assertEquals(1, multi.size, "a 3-file burst must surface as ONE coalesced event, got $snapshot")
        assertEquals(2, multi.single().otherFilesCount)
        assertEquals("/file2.txt", multi.single().firstFileName, "the burst headline is the NEWEST file")
        assertEquals(
            emptyList(),
            snapshot.filterIsInstance<Event.FileEvent.Added>().filter { !it.isFolder },
            "no per-file Added rows may leak past the producer coalescing"
        )

        // The folder creation is its own event.
        assertEquals(1, snapshot.count { it is Event.FileEvent.Added && it.isFolder })

        // The chain sort delivered newest-first (timestamps non-increasing).
        assertTrue(
            snapshot.zipWithNext().all { (a, b) -> a.timestamp >= b.timestamp },
            "events must be sorted newest-first by the proxy-side chain sort, got $snapshot"
        )
    }
}
