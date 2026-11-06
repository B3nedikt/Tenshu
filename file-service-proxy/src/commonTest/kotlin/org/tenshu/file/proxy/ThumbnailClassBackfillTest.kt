package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.file.proxy.ThumbnailClassBackfill.Candidate
import org.tenshu.file.proxy.ThumbnailClassBackfill.Mint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure pass (`drive-synced-thumbnails.md` §15.3 D-6, §15.11): what it mints,
 * what stops it, that a decline is recorded and never asked again, and that the walk runs on every
 * tick with the marker as a report.
 */
class ThumbnailClassBackfillTest {

    private val xlarge = GetStoredThumbnail.CLASS_XLARGE
    private val motion = GetStoredThumbnail.CLASS_MOTION

    private fun photo(name: String, local: Boolean = true) =
        Candidate(path = "/$name.jpg", contentId = "c-$name", category = FileCategory.Image, hasLocalBytes = local)

    private fun video(name: String, local: Boolean = true) =
        Candidate(path = "/$name.mp4", contentId = "c-$name", category = FileCategory.Video, hasLocalBytes = local)

    private class Harness(
        var wanted: Set<String>? = emptySet(),
        var files: List<Candidate>? = emptyList(),
        val present: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        val declined: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        var motionSupported: Boolean = true,
        var roomFor: (String) -> Boolean = { true },
        var mintResult: (Candidate, String) -> Mint = { _, _ -> Mint.Stored(100) },
        var maxFiles: Int = 1_000,
        var maxBytes: Long = Long.MAX_VALUE,
        /** Whether the content plane can take bytes at all right now. */
        var canPublish: Boolean = true,
    ) {
        val mints = mutableListOf<Pair<String, String>>()
        val markerWrites = mutableListOf<String>()
        val marker: String? get() = markerWrites.lastOrNull()

        fun pass() = ThumbnailClassBackfill(
            wantedClasses = { wanted },
            candidates = { files?.asSequence() },
            presentClasses = { present[it.contentId] ?: emptySet() },
            declinedClasses = { declined[it.contentId] ?: emptySet() },
            canMint = { thumbnailClass, candidate ->
                when (thumbnailClass) {
                    GetStoredThumbnail.CLASS_MOTION -> candidate.category == FileCategory.Video && motionSupported
                    else -> candidate.category != FileCategory.Other
                }
            },
            hasRoomFor = { roomFor(it) },
            mint = { candidate, thumbnailClass ->
                mints += candidate.path to thumbnailClass
                mintResult(candidate, thumbnailClass).also {
                    if (it is Mint.Stored) present.getOrPut(candidate.contentId) { mutableSetOf() } += thumbnailClass
                }
            },
            recordDecline = { candidate, thumbnailClass ->
                declined.getOrPut(candidate.contentId) { mutableSetOf() } += thumbnailClass
            },
            writeMarker = { markerWrites += it },
            canPublish = { canPublish },
            maxFilesPerRun = maxFiles,
            maxBytesPerRun = maxBytes,
        )
    }

    /**
     * ⚠⚠ A pass that cannot publish renders nothing and latches nothing. Minting into a closed gate
     * fills the disk with blobs that can only wait in the journal or be evicted — and the walk runs
     * again next tick, so nothing is lost by waiting.
     */
    @Test
    fun mintsNothingWhileContentCannotBePublished() = runTest {
        val h = Harness(
            wanted = setOf(xlarge),
            files = listOf(photo("a")),
            canPublish = false,
        )

        val outcome = h.pass().run()

        assertEquals(emptyList(), h.mints)
        assertNull(h.marker, "an incomplete pass never latches")
        assertEquals("content cannot be published right now", outcome.skipped)
    }

    // ---- wanted − present − declined ----------------------------------------------------------

    @Test
    fun mintsOnlyWhatIsWantedAndMissing_onFilesWithLocalBytes() = runTest {
        val h = Harness(
            wanted = setOf(xlarge, motion),
            files = listOf(photo("a"), video("b"), photo("evicted", local = false), photo("done")),
            present = mutableMapOf("c-done" to mutableSetOf(xlarge)),
        )

        val outcome = h.pass().run()

        assertEquals(
            listOf("/a.jpg" to xlarge, "/b.mp4" to motion, "/b.mp4" to xlarge),
            h.mints,
            "a photo gets the hero still, a video the clip and the still; the evicted and the served file get nothing",
        )
        assertEquals(2, outcome.filesTouched)
        assertEquals(3, outcome.classesMinted)
        assertTrue(outcome.complete, "a clean walk is complete")
        assertEquals(ThumbnailClassBackfill.markerFor(setOf(xlarge, motion)), h.marker)
    }

    /** §15.2: `LIST` and `LARGE` are implicit everywhere and never acted on, whatever a row says. */
    @Test
    fun listAndLargeInTheWantedSetAreIgnored() = runTest {
        val h = Harness(wanted = setOf(GetStoredThumbnail.CLASS_LIST, GetStoredThumbnail.CLASS_LARGE), files = listOf(photo("a")))

        val outcome = h.pass().run()

        assertTrue(h.mints.isEmpty())
        assertTrue(outcome.complete)
        assertEquals(ThumbnailClassBackfill.markerFor(emptySet()), h.marker, "the marker is for the empty *declarable* set")
    }

    /** A platform with no clip encoder skips `MOTION` and still serves `XLARGE` — and is complete. */
    @Test
    fun aClassThisPlatformCannotMintIsSkippedNotFailed() = runTest {
        val h = Harness(wanted = setOf(xlarge, motion), files = listOf(video("b")), motionSupported = false)

        val outcome = h.pass().run()

        assertEquals(listOf("/b.mp4" to xlarge), h.mints)
        assertTrue(outcome.complete, "what this device cannot mint is not this device's incompleteness")
    }

    // ---- declines are recorded, once ------------------------------------------------------------

    /** A decline is rendered exactly once, ever — the record hides it from every later walk. */
    @Test
    fun aDeclineIsRecordedAndNeverRenderedAgain() = runTest {
        val h = Harness(wanted = setOf(xlarge), files = listOf(photo("corrupt"), photo("b")))
        h.mintResult = { c, _ -> if (c.path == "/corrupt.jpg") Mint.Declined else Mint.Stored(10) }

        val first = h.pass().run()
        assertEquals(1, first.declined)
        assertEquals<Set<String>?>(setOf(xlarge), h.declined["c-corrupt"], "the decline is persisted through the seam")
        assertTrue(first.complete, "a decline is terminal, not a failure")

        val second = h.pass().run()
        assertEquals(2, h.mints.size, "neither the declined nor the served file is rendered again")
        assertEquals(0, second.declined, "a recorded decline is invisible to the next run")
        assertEquals(0, second.filesTouched)
    }

    /**
     * Declining files still cost a budget slot on the tick they decline, so
     * one run is bounded — but they are recorded, so the next run walks past them to the rest.
     */
    @Test
    fun decliningFilesCannotStallTheWalkBelowTheBudget() = runTest {
        val declining = (1..3).map { photo("bad$it") }
        val h = Harness(wanted = setOf(xlarge), files = declining + photo("good"), maxFiles = 2)
        h.mintResult = { c, _ -> if (c.path.startsWith("/bad")) Mint.Declined else Mint.Stored(10) }

        val first = h.pass().run()
        assertEquals("budget", first.stoppedBy, "three declines and a budget of two: the first tick stops")
        assertEquals(2, first.declined)

        val second = h.pass().run()
        assertEquals(1, second.declined, "the third decline is met on the second tick, not the first two again")
        assertEquals(listOf("/bad1.jpg", "/bad2.jpg", "/bad3.jpg", "/good.jpg"), h.mints.map { it.first })
        assertTrue(second.complete, "the walk reached the good file behind the declines")
    }

    /** A render that threw is a transient: counted, not recorded, and the marker is withheld. */
    @Test
    fun aFailedMintIsNotRecordedAndWithholdsTheMarker() = runTest {
        val h = Harness(wanted = setOf(xlarge), files = listOf(photo("a"), photo("b")))
        h.mintResult = { c, _ -> if (c.path == "/a.jpg") Mint.Failed else Mint.Stored(10) }

        val outcome = h.pass().run()

        assertEquals(2, h.mints.size, "a failure on one file does not stop the others")
        assertEquals(1, outcome.failed)
        assertTrue(h.declined.isEmpty(), "a failure is not a decline")
        assertFalse(outcome.complete)
        assertNull(h.marker)

        h.mintResult = { _, _ -> Mint.Stored(10) }
        h.pass().run()
        assertEquals(3, h.mints.size, "the failed file is retried on the next tick")
    }

    // ---- stops ---------------------------------------------------------------------------------

    @Test
    fun aQuotaStopEndsTheRun() = runTest {
        val h = Harness(wanted = setOf(xlarge), files = listOf(photo("a"), photo("b")))
        var checks = 0
        h.roomFor = { checks++ == 0 }

        val outcome = h.pass().run()

        assertEquals(listOf("/a.jpg" to xlarge), h.mints, "the second file is never rendered once the quota says no")
        assertEquals("quota", outcome.stoppedBy)
        assertFalse(outcome.complete)
        assertNull(h.marker)
    }

    @Test
    fun theFileBudgetEndsTheRun_andTheNextRunResumes() = runTest {
        val h = Harness(wanted = setOf(xlarge), files = listOf(photo("a"), photo("b"), photo("c")), maxFiles = 2)

        val first = h.pass().run()
        assertEquals(2, first.filesTouched)
        assertEquals("budget", first.stoppedBy)
        assertNull(h.marker)

        val second = h.pass().run()
        assertEquals(listOf("/a.jpg" to xlarge, "/b.jpg" to xlarge, "/c.jpg" to xlarge), h.mints)
        assertEquals(1, second.filesTouched, "the served files cost a stat each and are not touched again")
        assertTrue(second.complete)
    }

    @Test
    fun theByteBudgetEndsTheRun() = runTest {
        val h = Harness(wanted = setOf(xlarge), files = listOf(photo("a"), photo("b")), maxBytes = 50)
        h.mintResult = { _, _ -> Mint.Stored(60) }

        val outcome = h.pass().run()

        assertEquals(1, outcome.filesTouched)
        assertEquals("budget", outcome.stoppedBy)
        assertFalse(outcome.complete)
    }

    // ---- the walk runs every tick; the marker is a report ---------------------------------------

    /** Bytes that arrive after a complete run are reached on the next tick, because the walk is not gated. */
    @Test
    fun theWalkRunsEveryTick_soBytesArrivingLaterAreReached() = runTest {
        val h = Harness(wanted = setOf(xlarge), files = listOf(photo("a"), photo("later", local = false)))
        h.pass().run()
        assertEquals(1, h.mints.size)
        assertEquals(ThumbnailClassBackfill.markerFor(setOf(xlarge)), h.marker)

        h.files = listOf(photo("a"), photo("later", local = true))
        val again = h.pass().run()

        assertNull(again.skipped, "a complete run does not gate the next walk")
        assertEquals(listOf("/a.jpg" to xlarge, "/later.jpg" to xlarge), h.mints)
        assertEquals(1, again.filesTouched)
        assertEquals(2, h.markerWrites.size, "the marker is re-written as this run's report")
    }

    @Test
    fun aNewClassInTheRealmIsMintedOnTheNextTick() = runTest {
        val h = Harness(wanted = setOf(xlarge), files = listOf(photo("a"), video("b")))
        h.pass().run()
        assertEquals(listOf("/a.jpg" to xlarge, "/b.mp4" to xlarge), h.mints)

        h.wanted = setOf(xlarge, motion)
        h.pass().run()

        assertEquals(listOf("/a.jpg" to xlarge, "/b.mp4" to xlarge, "/b.mp4" to motion), h.mints)
        assertEquals(ThumbnailClassBackfill.markerFor(setOf(xlarge, motion)), h.marker)
    }

    @Test
    fun theMarkerChangesWithTheMintFormat() {
        assertNotEquals(
            ThumbnailClassBackfill.markerFor(setOf(xlarge), mintFormat = "v1"),
            ThumbnailClassBackfill.markerFor(setOf(xlarge), mintFormat = "v2"),
        )
        assertEquals(
            ThumbnailClassBackfill.markerFor(setOf(motion, xlarge)),
            ThumbnailClassBackfill.markerFor(setOf(xlarge, motion)),
            "order-insensitive: the set is the identity",
        )
    }

    /** `null` is "could not read", never "nothing wanted". */
    @Test
    fun anUnreadableWantedSetOrStoreMintsNothingAndWritesNoMarker() = runTest {
        val unreadable = Harness(wanted = null, files = listOf(photo("a")))
        val o1 = unreadable.pass().run()
        assertEquals("the wanted set could not be read", o1.skipped)
        assertNull(unreadable.marker)
        assertTrue(unreadable.mints.isEmpty())

        val cannotEnumerate = Harness(wanted = setOf(xlarge), files = null)
        val o2 = cannotEnumerate.pass().run()
        assertEquals("the store cannot enumerate files", o2.skipped)
        assertNull(cannotEnumerate.marker)
    }

    @Test
    fun nothingWantedWritesTheEmptyMarkerWithoutEnumerating() = runTest {
        val h = Harness(wanted = emptySet(), files = null)

        val outcome = h.pass().run()

        assertTrue(outcome.complete)
        assertEquals(listOf(ThumbnailClassBackfill.markerFor(emptySet())), h.markerWrites)
    }
}
