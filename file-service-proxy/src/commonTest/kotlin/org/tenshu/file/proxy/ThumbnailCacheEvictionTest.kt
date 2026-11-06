package org.tenshu.file.proxy

import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.file.impl.CachedThumbnail
import org.tenshu.file.impl.selectThumbnailEvictions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The thumbnail ceiling's **decision** (`drive-xxlarge-thumbnail-rung.md` D-20), the twin of
 * `LocalCacheEvictionTest` one plane over. `OfflineKeeperThumbnailEvictionTest` covers the wiring.
 */
class ThumbnailCacheEvictionTest {

    private fun blob(
        name: String,
        sizeBytes: Long = 100,
        lastAccessMs: Long? = 0,
        thumbnailClass: String = GetStoredThumbnail.CLASS_XLARGE,
    ) = CachedThumbnail(
        path = "/.thumbnails/$name/$thumbnailClass",
        filePath = "/$name.jpg",
        contentId = name,
        thumbnailClass = thumbnailClass,
        propertyId = "tenshu/file/thumbnail${thumbnailClass}",
        sizeBytes = sizeBytes,
        lastAccessMs = lastAccessMs,
    )

    private fun select(
        cached: List<CachedThumbnail>,
        maxThumbnailBytes: Long = 1_000,
        availableSpaceBytes: Long? = 10_000,
        minFreeSpaceBytes: Long = 1_000,
    ) = selectThumbnailEvictions(cached, maxThumbnailBytes, availableSpaceBytes, minFreeSpaceBytes)

    @Test
    fun underTheCeilingNothingIsEvicted() {
        assertEquals(emptyList(), select(listOf(blob("a"), blob("b"))))
    }

    @Test
    fun overTheCeilingTheColdestGoFirstAndTheSweepStopsOnceUnder() {
        val plan = select(
            cached = listOf(
                blob("warm", lastAccessMs = 300),
                blob("coldest", lastAccessMs = 100),
                blob("cold", lastAccessMs = 200),
            ),
            maxThumbnailBytes = 150,
        )

        assertEquals(
            listOf("/.thumbnails/coldest/xlarge", "/.thumbnails/cold/xlarge"),
            plan.map { it.path },
            "300 bytes against a 150-byte ceiling: two blobs free 200, and the third is spared",
        )
    }

    /** ⚠ A platform that cannot report a time must not read as maximally cold. */
    @Test
    fun unknownRecencyIsEvictedLast() {
        val plan = select(
            cached = listOf(blob("unknown", lastAccessMs = null), blob("known", lastAccessMs = 500)),
            maxThumbnailBytes = 100,
        )

        assertEquals(listOf("/.thumbnails/known/xlarge"), plan.map { it.path })
    }

    @Test
    fun equallyColdBlobsAreOrderedDeterministically() {
        val plan = select(
            cached = listOf(blob("b", lastAccessMs = 1), blob("a", lastAccessMs = 1)),
            maxThumbnailBytes = 100,
        )

        assertEquals(listOf("/.thumbnails/a/xlarge"), plan.map { it.path })
    }

    /**
     * ⚠⚠ The opposite of the file cache's `0`. These blobs are derivatives nobody asked for by name,
     * so the ceiling ships on and `0` is a real choice — "keep no thumbnail cache".
     */
    @Test
    fun aCeilingOfZeroKeepsNothingAndOnlyANegativeOneMeansUnlimited() {
        val cached = listOf(blob("a"), blob("b"))

        assertEquals(2, select(cached, maxThumbnailBytes = 0).size, "0 keeps no cache")
        assertEquals(emptyList(), select(cached, maxThumbnailBytes = -1), "negative is the no-ceiling escape hatch")
    }

    @Test
    fun theFreeDiskFloorFiresUnderTheCeiling() {
        val plan = select(
            cached = listOf(blob("a", lastAccessMs = 1), blob("b", lastAccessMs = 2)),
            maxThumbnailBytes = 10_000,
            availableSpaceBytes = 950,
            minFreeSpaceBytes = 1_000,
        )

        assertEquals(listOf("/.thumbnails/a/xlarge"), plan.map { it.path }, "50 bytes short of the floor ⇒ one blob")
    }

    @Test
    fun unknownFreeSpaceSkipsTheFloor() {
        assertEquals(
            emptyList(),
            select(listOf(blob("a")), maxThumbnailBytes = 10_000, availableSpaceBytes = null, minFreeSpaceBytes = 1_000),
            "unknown free space is not zero free space",
        )
    }

    @Test
    fun theLargerOfTheTwoPressuresWins() {
        val cached = listOf(
            blob("a", sizeBytes = 50, lastAccessMs = 1),
            blob("b", sizeBytes = 50, lastAccessMs = 2),
            blob("c", sizeBytes = 50, lastAccessMs = 3),
        )

        assertEquals(
            1,
            select(cached, maxThumbnailBytes = 130, availableSpaceBytes = 10_000, minFreeSpaceBytes = 1_000).size,
            "the ceiling alone wants 20 bytes, which one blob covers",
        )
        assertEquals(
            2,
            select(cached, maxThumbnailBytes = 130, availableSpaceBytes = 900, minFreeSpaceBytes = 1_000).size,
            "the floor wants 100 bytes, so it decides and takes a second blob",
        )
    }

}
