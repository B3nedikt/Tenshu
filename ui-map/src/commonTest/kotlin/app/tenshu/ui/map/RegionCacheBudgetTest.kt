package app.tenshu.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The streamed basemap's disk budget (`maps-region-delivery.md` §7.1 track 1a). The policy is pure, so
 * every rule that matters is decided here rather than on a device.
 */
class RegionCacheBudgetTest {

    private val mb = 1024L * 1024

    private fun dir(
        name: String,
        mbytes: Long,
        lastUsedMs: Long? = 1_000L,
        evictable: Boolean = true,
    ) = CachedRegionDir(name, mbytes * mb, lastUsedMs, evictable)

    @Test
    fun withinBudgetEvictsNothing() {
        val evict = regionCacheEvictions(
            listOf(dir("at_1", 40), dir("ch_1", 50)),
            ceilingBytes = 128 * mb,
        )
        assertTrue(evict.isEmpty())
    }

    @Test
    fun exactlyAtTheCeilingIsWithinBudget() {
        // Off-by-one guard: the ceiling is the largest allowed total, not the first disallowed one.
        val evict = regionCacheEvictions(listOf(dir("at_1", 128)), ceilingBytes = 128 * mb)
        assertTrue(evict.isEmpty())
    }

    @Test
    fun evictsColdestFirstAndStopsAsSoonAsItFits() {
        val evict = regionCacheEvictions(
            listOf(
                dir("warm", 60, lastUsedMs = 3_000),
                dir("coldest", 60, lastUsedMs = 1_000),
                dir("cold", 60, lastUsedMs = 2_000),
            ),
            ceilingBytes = 128 * mb,
        )
        // 180 MB total, ceiling 128 → dropping the coldest 60 leaves 120, which fits. The second-coldest
        // must survive: an LRU that kept evicting past the point of fitting would be a cache-wipe.
        assertEquals(listOf("coldest"), evict)
    }

    @Test
    fun aLiveOrOpeningCacheIsNeverEvictedButStillCounts() {
        val evict = regionCacheEvictions(
            listOf(
                dir("streaming-now", 100, lastUsedMs = 1_000, evictable = false),
                dir("idle", 50, lastUsedMs = 9_000),
            ),
            ceilingBytes = 128 * mb,
        )
        // The pinned one is the coldest and the biggest, and still must not go — evicting it would strand
        // a live instance claiming ranges the file no longer has. So the newer, smaller one pays.
        assertEquals(listOf("idle"), evict)
    }

    @Test
    fun unknownAgeIsEvictedLastNotFirst() {
        val evict = regionCacheEvictions(
            listOf(
                dir("undateable", 60, lastUsedMs = null),
                dir("dated", 60, lastUsedMs = 5_000),
                dir("dated-older", 60, lastUsedMs = 1_000),
            ),
            ceilingBytes = 128 * mb,
        )
        // A filesystem that cannot report a time must not have its whole cache classed as coldest and
        // wiped; the conservative failure is the mass eviction, not the retention.
        assertEquals(listOf("dated-older"), evict)
    }

    @Test
    fun whenEverythingLeftIsPinnedItEvictsAllItCanAndDoesNotPretend() {
        val evict = regionCacheEvictions(
            listOf(
                dir("pinned-a", 100, evictable = false),
                dir("pinned-b", 100, evictable = false),
                dir("free", 10, lastUsedMs = 1),
            ),
            ceilingBytes = 128 * mb,
        )
        // Still 200 MB over afterwards — but returning the evictable one is the best available, and the
        // caller logs the shortfall rather than the budget silently claiming to hold.
        assertEquals(listOf("free"), evict)
    }

    @Test
    fun tiesAreBrokenDeterministically() {
        val entries = listOf(dir("b_1", 60, lastUsedMs = 1_000), dir("a_1", 60, lastUsedMs = 1_000))
        // Same instant: directory-listing order must not decide which cache survives.
        assertEquals(listOf("a_1"), regionCacheEvictions(entries, ceilingBytes = 100 * mb))
        assertEquals(listOf("a_1"), regionCacheEvictions(entries.reversed(), ceilingBytes = 100 * mb))
    }

    @Test
    fun theShippedCeilingIsAFiniteBudget() {
        // The whole point of the slice: guard against it being set to 0 (= evict everything on every
        // open) or to Long.MAX_VALUE (= the unbounded behaviour this replaced) by a later edit.
        assertTrue(REGION_CACHE_CEILING_BYTES in (16 * mb)..(2048 * mb), "ceiling: $REGION_CACHE_CEILING_BYTES")
    }
}
