package app.tenshu.timeline.data.capture

import app.tenshu.timeline.data.ImportedPlace
import app.tenshu.timeline.data.ImportedMoment
import app.tenshu.timeline.data.ImportedTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory [VisitOutboxStore] — insertion-ordered, like the file stores. */
class FakeVisitOutboxStore : VisitOutboxStore {
    val stored = linkedMapOf<String, String>()
    override fun append(id: String, payload: String) { stored[id] = payload }
    override fun entries(): List<VisitOutboxEntry> = stored.map { VisitOutboxEntry(it.key, it.value) }
    override fun remove(id: String) { stored.remove(id) }
}

/** The durable visit outbox (tech-debt "silent visit loss before onboarding"). */
class CapturedVisitOutboxTest {

    private val timeline = ImportedTimeline(
        places = listOf(ImportedPlace(placeId = "525163:133780", latE7 = 525_163_000, lngE7 = 133_780_000, capturedAtMs = 1_000)),
        moments = listOf(
            ImportedMoment(momentId = "cv:525163:133780:1000", timestampMs = 1_000, kind = "Visit", title = "Visit"),
        ),
    )

    @Test
    fun persistedTimelineRoundTrips() {
        val store = FakeVisitOutboxStore()
        val outbox = CapturedVisitOutbox(store)

        val id = outbox.persist(timeline)

        assertEquals(listOf(id to timeline), outbox.pending())
        outbox.remove(id)
        assertTrue(outbox.pending().isEmpty())
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun corruptPayloadIsDroppedAndPurgedNotWedged() {
        val store = FakeVisitOutboxStore()
        val outbox = CapturedVisitOutbox(store)
        store.append("0-corrupt", "{ not json")
        val good = outbox.persist(timeline)

        val pending = outbox.pending()

        assertEquals(listOf(good), pending.map { it.first })
        // The corrupt entry is gone from the store too — it can never wedge the replay loop.
        assertEquals(setOf(good), store.stored.keys.toSet())
    }

    @Test
    fun pendingIsOldestFirstByStoreOrder() {
        val store = FakeVisitOutboxStore()
        val outbox = CapturedVisitOutbox(store)
        val older = outbox.persist(timeline)
        val newer = outbox.persist(
            timeline.copy(moments = listOf(timeline.moments.single().copy(momentId = "cv:x:2000", timestampMs = 2_000))),
        )

        assertEquals(listOf(older, newer), outbox.pending().map { it.first })
    }
}
