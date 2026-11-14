package org.tenshu.timeline.proxy

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.sync.common.RelationSliceSnapshot
import org.tenshu.timeline.common.slice.MomentSliceEntry
import org.tenshu.timeline.test.createTimelineProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C9's producer, driven through a **real** timeline proxy — so a slice cannot silently disagree
 * with the feed it stands in for. The shape mirrors `FileSliceSnapshotProducerTest`; what is new
 * is that the relation is a realm-wide singleton, so the partition is invented rather than given.
 */
class MomentSliceSnapshotProducerTest {

    private val producer = MomentSliceSnapshotProducer()

    /** 2026-08-06T12:00:00Z — mid-day, so ±hours stay inside the same UTC bucket. */
    private val noon = 1_786_017_600_000L
    private val day = "2026-08-06"
    private val dayBefore = "2026-08-05"
    private val millisPerDay = 24L * 60 * 60 * 1000

    @Test
    fun sliceKeys_areTheUtcDaysTheDeviceActuallyHolds() = runTest {
        val proxy = createTimelineProxy(backgroundScope)
        record(proxy, "m-today", noon)
        record(proxy, "m-today-2", noon + 3_600_000)
        record(proxy, "m-yesterday", noon - millisPerDay)

        val keys = producer.sliceKeys(proxy.executionContext, TimelineService.Relations.MOMENTS)

        assertEquals(
            listOf(day, dayBefore), keys,
            "newest first, one key per day the device holds — not one per moment",
        )
    }

    @Test
    fun sliceKeys_ignoreARelationItDoesNotOwn() = runTest {
        val proxy = createTimelineProxy(backgroundScope)
        record(proxy, "m1", noon)
        assertEquals(
            emptyList(),
            producer.sliceKeys(proxy.executionContext, "tenshu/file/folder/files"),
        )
    }

    @Test
    fun encodeSlice_carriesOnlyThatDayAndEveryFieldTheFeedRenders() = runTest {
        val proxy = createTimelineProxy(backgroundScope)
        record(proxy, "m-today", noon)
        record(proxy, "m-yesterday", noon - millisPerDay)

        val slice = assertNotNull(
            producer.encodeSlice(proxy.executionContext, TimelineService.Relations.MOMENTS, day)
        )
        val entries = assertNotNull(MomentSliceEntry.decode(slice.entries))

        assertEquals(1, slice.entryCount, "entryCount must agree with what entries encodes")
        val one = entries.single()
        assertEquals("m-today", one.momentId)
        assertEquals(noon, one.timestampMillis)
        assertEquals(Moment.MomentKind.VISIT, one.kind)
        assertEquals("Visit", one.title)
        assertEquals(SubjectKind.PLACE, one.subjectKind)
        assertEquals("place-1", one.subjectId)
        assertEquals(1_800, one.durationSec)
    }

    @Test
    fun encodeSlice_ofADayWithNothingInItIsEmpty_notMissing() = runTest {
        // ⚠ The distinction the reader depends on, and it is sharper here than for a folder: an
        // empty day is a *normal* answer for a feed, so conflating it with "cannot produce" would
        // make every quiet day pay a fallback.
        val proxy = createTimelineProxy(backgroundScope)
        record(proxy, "m-today", noon)

        val slice = assertNotNull(
            producer.encodeSlice(proxy.executionContext, TimelineService.Relations.MOMENTS, dayBefore)
        )
        assertEquals(0, slice.entryCount)
        assertContentEquals(emptyList(), MomentSliceEntry.decode(slice.entries))
    }

    @Test
    fun encodeSlice_ofAQuietDayIsEmpty_evenLongBeforeAnythingThisDeviceHolds() = runTest {
        // ⚠ An UNtruncated window has seen everything this device holds, so a day it does not
        // contain really is empty — and the writer only publishes once catch-up is complete, so
        // "this device holds nothing then" is a safe proxy for "the realm holds nothing then".
        val proxy = createTimelineProxy(backgroundScope)
        record(proxy, "m-today", noon)

        val slice = assertNotNull(
            producer.encodeSlice(
                proxy.executionContext, TimelineService.Relations.MOMENTS, "2019-01-01",
            ),
        )
        assertEquals(0, slice.entryCount)
    }

    @Test
    fun encodeSlice_isNullOnceTheWindowTruncates_neverAnEmptyDay() = runTest {
        // ⚠⚠ The honesty rule, and the one the first implementation got wrong. Once the window is
        // FULL there may be older rows it cannot see, so answering "empty" would assert a day was
        // blank when it was merely out of reach — a joiner would render a real day as nothing.
        val proxy = createTimelineProxy(backgroundScope)
        record(proxy, "m-1", noon)
        record(proxy, "y-1", noon - millisPerDay)
        record(proxy, "y-2", noon - millisPerDay + 3_600_000)
        val tiny = MomentSliceSnapshotProducer(recentWindow = 2)

        // Today is wholly inside the window (an older day is in it too) and still encodes…
        assertNotNull(
            tiny.encodeSlice(proxy.executionContext, TimelineService.Relations.MOMENTS, day),
        )
        // …but an older day is now unknowable rather than empty.
        assertNull(
            tiny.encodeSlice(proxy.executionContext, TimelineService.Relations.MOMENTS, "2026-08-04"),
        )
    }

    @Test
    fun encodeSlice_isNullForTheOldestDayAFullWindowReaches_neverPartOfADay() = runTest {
        // ⚠⚠ The window's oldest day is cut too: here it holds y-1 and y-2, and the window saw only
        // y-2. Sealing it would hand a joiner one of yesterday's two moments as the whole day.
        val proxy = createTimelineProxy(backgroundScope)
        record(proxy, "m-1", noon)
        record(proxy, "y-1", noon - millisPerDay)
        record(proxy, "y-2", noon - millisPerDay + 3_600_000)
        val tiny = MomentSliceSnapshotProducer(recentWindow = 2)

        assertNull(
            tiny.encodeSlice(proxy.executionContext, TimelineService.Relations.MOMENTS, dayBefore),
        )
    }

    @Test
    fun encodeSlice_sealsTheOldestDayOfAStoreHoldingExactlyTheWindow_butNotOfOneRowMore() = runTest {
        // A store of exactly the window is wholly seen, so its oldest day is complete; one row more
        // and that day may continue past the window. (A two-row window stands in for 2 000.)
        val proxy = createTimelineProxy(backgroundScope)
        record(proxy, "m-1", noon)
        record(proxy, "y-1", noon - millisPerDay)
        val tiny = MomentSliceSnapshotProducer(recentWindow = 2)

        val whole = assertNotNull(
            tiny.encodeSlice(proxy.executionContext, TimelineService.Relations.MOMENTS, dayBefore),
            "exactly the window: the oldest day is whole and sealed",
        )
        assertEquals(1, whole.entryCount)

        record(proxy, "y-0", noon - millisPerDay - 3_600_000)
        assertNull(
            tiny.encodeSlice(proxy.executionContext, TimelineService.Relations.MOMENTS, dayBefore),
            "one row more: the oldest day may be cut",
        )
    }

    @Test
    fun encodeSlice_ofTheFullestDayTheWindowCanSealFitsTheWriterBudget() = runTest {
        // The large corpus: a store of exactly the default window, all on one day — the most a
        // sealed day can hold — each shaped like a Takeout visit (`GoogleTimelineParser`:
        // `gv:<placeId>:<startMs>`) with a long place name. Every name is made up. ⚠ If the window
        // grows, or an entry gains bytes, this goes red before a joiner's seed day stops being sealed.
        val proxy = createTimelineProxy(backgroundScope)
        val window = MomentSliceSnapshotProducer.RECENT_WINDOW
        val start = noon - 11L * 3_600_000
        repeat(window) { i ->
            val at = start + i * 10_000L
            val placeId = "ChIJ" + i.toString().padStart(23, '0')
            recordVisit(
                proxy,
                momentId = "gv:$placeId:$at",
                atMs = at,
                title = "Bäckerei am Großen Marktplatz Nummer ${i.toString().padStart(4, '0')}",
                placeId = placeId,
            )
        }

        val slice = assertNotNull(
            producer.encodeSlice(proxy.executionContext, TimelineService.Relations.MOMENTS, day),
        )
        assertEquals(window, slice.entryCount)
        val envelope = RelationSliceSnapshot.encode(
            RelationSliceSnapshot(
                asOfSeq = Long.MAX_VALUE,
                relationId = TimelineService.Relations.MOMENTS,
                sliceKey = day,
                entryCount = slice.entryCount,
                entries = slice.entries,
            ),
        )
        assertTrue(
            envelope.size <= RelationSliceSnapshot.MAX_ENCODED_BYTES,
            "the fullest sealable day is ${envelope.size} bytes, over the ${RelationSliceSnapshot.MAX_ENCODED_BYTES}-byte budget",
        )
    }

    @Test
    fun bootstrapSeedIsTodayAndYesterday_becauseTheKeyIsUtcAndTheFeedIsLocal() = runTest {
        val proxy = createTimelineProxy(backgroundScope)

        val seed = producer.bootstrapSliceKeys(
            proxy.executionContext, TimelineService.Relations.MOMENTS,
        )

        assertEquals(2, seed.size, "a local day straddles two UTC buckets for most of the world")
        assertTrue(seed.all { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) })
        assertEquals(
            emptyList(),
            producer.bootstrapSliceKeys(proxy.executionContext, "tenshu/file/folder/files"),
        )
    }

    @Test
    fun applySlice_rebuildsTheDayOnADeviceThatHasNoneOfIt() = runTest {
        // The C9 round trip: produce where the feed exists, apply where it does not, and the
        // second device's own slice must agree with the first's.
        val source = createTimelineProxy(backgroundScope)
        record(source, "m-1", noon)
        record(source, "m-2", noon + 3_600_000)
        val slice = assertNotNull(
            producer.encodeSlice(source.executionContext, TimelineService.Relations.MOMENTS, day)
        )

        val joiner = createTimelineProxy(backgroundScope)
        val created = producer.applySlice(
            joiner.executionContext, TimelineService.Relations.MOMENTS, day, slice.entries, isDeleted = { false },
        )

        assertEquals(2, created)
        val rebuilt = assertNotNull(
            producer.encodeSlice(joiner.executionContext, TimelineService.Relations.MOMENTS, day)
        )
        assertContentEquals(
            MomentSliceEntry.decode(slice.entries)!!.map { it.momentId }.sorted(),
            MomentSliceEntry.decode(rebuilt.entries)!!.map { it.momentId }.sorted(),
        )
    }

    @Test
    fun applySlice_readsTheStoreLinearlyInTheSliceItApplies() = runTest {
        // ⚠⚠ The iOS `HOLD_MAX` defect (`tech-debt.md` "Public CI was red on every run"), pinned
        // where it is deterministic. Applying N visits under the production wiring used to run
        // `PlaceRoleInferencePass.inferNow` inline every fifth save, and each run reads the whole
        // `MOMENTS` relation: N/5 scans, O(N²) row reads, each scan one write-locked access on the
        // SQLite replica. Measured before the fix: 76,441 row reads for these 600 visits (127 per entry).
        val store = CountingStore()
        val joiner = createTimelineProxy(backgroundScope, objectStore = store)
        val entries = List(600) { i ->
            MomentSliceEntry(
                momentId = "gv:place-$i:${noon + i}",
                timestampMillis = noon + i,
                kind = Moment.MomentKind.VISIT,
                title = "Visit $i",
                subjectKind = SubjectKind.PLACE,
                subjectId = "place-$i",
                durationSec = 1_800,
                supersededBy = "",
            )
        }
        store.reads = 0

        val created = producer.applySlice(
            joiner.executionContext, TimelineService.Relations.MOMENTS, day,
            MomentSliceEntry.encode(entries), isDeleted = { false },
        )

        assertEquals(entries.size, created)
        val perEntry = store.reads / entries.size
        assertTrue(
            perEntry <= 20,
            "the apply must read the store O(1) times per entry, not rescan the relation: " +
                "${store.reads} reads for ${entries.size} entries ($perEntry per entry)",
        )

        // The passes still run, once each, after the burst: coalesced, not dropped.
        store.reads = 0
        advanceTimeBy(PlaceRoleInferencePass.COALESCE_MS + 1)
        runCurrent()
        assertTrue(
            store.reads >= entries.size,
            "the coalesced passes must still scan the relation once the burst is over: ${store.reads} reads",
        )
    }

    private class CountingStore(
        private val delegate: ObjectStore = InMemoryObjectStore(),
    ) : ObjectStore by delegate {
        var reads = 0

        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            reads++
            return delegate.getObject(dataIdHash)
        }
    }

    @Test
    fun applySlice_neverOverwritesAMomentTheDeviceAlreadyHas() = runTest {
        // ⚠⚠ Create-only, and for Timeline the stake is concrete: the projection omits `sources`,
        // the mergeable set MomentMerger unions across devices. Applying over a real row would
        // silently empty it.
        val joiner = createTimelineProxy(backgroundScope)
        record(joiner, "m-1", noon)

        val entries = MomentSliceEntry.encode(
            listOf(
                MomentSliceEntry(
                    momentId = "m-1",
                    timestampMillis = noon,
                    kind = Moment.MomentKind.VISIT,
                    title = "FROM THE SNAPSHOT",
                    subjectKind = SubjectKind.PLACE,
                    subjectId = "place-1",
                    durationSec = 99,
                    supersededBy = "",
                ),
            ),
        )

        assertEquals(
            0,
            producer.applySlice(
                joiner.executionContext, TimelineService.Relations.MOMENTS, day, entries, isDeleted = { false },
            ),
            "the moment already existed, so nothing may be written",
        )
        val after = assertNotNull(
            producer.encodeSlice(joiner.executionContext, TimelineService.Relations.MOMENTS, day)
        )
        val one = MomentSliceEntry.decode(after.entries)!!.single()
        assertEquals("Visit", one.title, "the real row's title, not the snapshot's claim")
        assertEquals(1_800, one.durationSec)
    }

    @Test
    fun applySlice_ofBytesItCannotParseAppliesNothing() = runTest {
        val joiner = createTimelineProxy(backgroundScope)
        assertEquals(
            0,
            producer.applySlice(
                joiner.executionContext, TimelineService.Relations.MOMENTS, day, byteArrayOf(9, 9), isDeleted = { false },
            ),
        )
    }

    @Test
    fun applySlice_ignoresARelationItDoesNotOwn() = runTest {
        val joiner = createTimelineProxy(backgroundScope)
        assertEquals(
            0,
            producer.applySlice(
                joiner.executionContext,
                "tenshu/file/folder/files",
                "/",
                MomentSliceEntry.encode(emptyList()), isDeleted = { false },
            ),
        )
    }

    private fun recordVisit(proxy: TestProxy, momentId: String, atMs: Long, title: String, placeId: String) =
        proxy.executionContext.saveData(
            Moment.create(
                context = proxy.tContext,
                momentId = momentId,
                timestamp = UnixInstantDto(atMs),
                kind = Moment.MomentKind.VISIT,
                title = title,
                subject = SubjectRef.create(proxy.tContext, SubjectKind.PLACE, placeId),
                durationSec = 1_800,
                sources = listOf("takeout"),
            ),
        )

    private fun record(proxy: TestProxy, momentId: String, atMs: Long) =
        proxy.executionContext.saveData(
            Moment.create(
                context = proxy.tContext,
                momentId = momentId,
                timestamp = UnixInstantDto(atMs),
                kind = Moment.MomentKind.VISIT,
                title = "Visit",
                subject = SubjectRef.create(proxy.tContext, SubjectKind.PLACE, "place-1"),
                durationSec = 1_800,
                sources = listOf("capture"),
            ),
        )
}
