package org.tenshu.file.proxy

import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.file.impl.CachedFile
import org.tenshu.file.impl.selectCacheEvictions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The local-cache ceiling's pure decision (`retention-quota-model.md` Face B).
 *
 * Everything here is the *selection*, not the deletion — the keeper's evict half
 * is one `deleteFile` per returned path, so getting the list right is the whole
 * of the correctness question.
 */
class LocalCacheEvictionTest {

    private val onlineOnly = SyncRuleSet(
        deviceDefault = SyncPolicy.ONLINE_ONLY,
        overrides = emptyList(),
    )

    private fun file(path: String, size: Long, modified: Long?) =
        CachedFile(path = path, sizeBytes = size, lastModifiedMs = modified)

    @Test
    fun underTheCeilingNothingIsEvicted() {
        val plan = selectCacheEvictions(
            ruleSet = onlineOnly,
            cached = listOf(file("/a", 100, 1), file("/b", 100, 2)),
            maxLocalBytes = 1_000,
            availableSpaceBytes = 10_000,
            minFreeSpaceBytes = 1_000,
        )

        assertEquals(emptyList(), plan)
    }

    @Test
    fun aCeilingOfZeroMeansUnlimited() {
        // ⚠ The shipped default. A zero read as "keep nothing" would wipe every
        // device's cache on the first sweep after upgrade.
        val plan = selectCacheEvictions(
            ruleSet = onlineOnly,
            cached = listOf(file("/a", 5_000, 1)),
            maxLocalBytes = 0,
            availableSpaceBytes = 10_000,
            minFreeSpaceBytes = 1_000,
        )

        assertEquals(emptyList(), plan)
    }

    @Test
    fun overTheCeilingEvictsColdestFirstAndStopsOnceUnder() {
        val plan = selectCacheEvictions(
            ruleSet = onlineOnly,
            cached = listOf(
                file("/hot", 100, modified = 900),
                file("/cold", 100, modified = 100),
                file("/tepid", 100, modified = 500),
            ),
            maxLocalBytes = 250,
            availableSpaceBytes = 10_000,
            minFreeSpaceBytes = 1_000,
        )

        // 300 held, 250 allowed ⇒ free 50 ⇒ one file, the coldest. It must stop
        // there: evicting the whole excess-adjacent tail would be a cache flush.
        assertEquals(listOf("/cold"), plan)
    }

    @Test
    fun pinnedContentIsNeitherEvictedNorCountedAgainstTheCeiling() {
        val pinned = SyncRuleSet(
            deviceDefault = SyncPolicy.ONLINE_ONLY,
            overrides = listOf(PathSyncOverride("/keep", SyncPolicy.KEEP_OFFLINE)),
        )

        val plan = selectCacheEvictions(
            ruleSet = pinned,
            cached = listOf(
                file("/keep/pinned.bin", 5_000, modified = 1),  // far over the ceiling, and coldest
                file("/other.bin", 100, modified = 900),
            ),
            maxLocalBytes = 550,
            availableSpaceBytes = 10_000,
            minFreeSpaceBytes = 1_000,
        )

        // ⚠⚠ The corrected D6: this is a *cache* ceiling. Counting the 5 000 pinned
        // bytes would put us 4 550 over a limit no eviction could ever satisfy, and
        // the only visible effect would be that /other.bin — well inside the
        // ceiling — is evicted forever. The cache is 100 bytes; 100 <= 550; nothing
        // goes.
        assertEquals(emptyList(), plan)
    }

    @Test
    fun aCeilingSmallerThanThePinnedSetIsStillSatisfiable() {
        val pinned = SyncRuleSet(
            deviceDefault = SyncPolicy.ONLINE_ONLY,
            overrides = listOf(PathSyncOverride("/keep", SyncPolicy.KEEP_OFFLINE)),
        )

        val plan = selectCacheEvictions(
            ruleSet = pinned,
            cached = listOf(
                file("/keep/pinned.bin", 5_000, modified = 1),
                file("/cold.bin", 100, modified = 2),
                file("/warm.bin", 100, modified = 900),
            ),
            maxLocalBytes = 150,
            availableSpaceBytes = 10_000,
            minFreeSpaceBytes = 1_000,
        )

        // 200 bytes of cache against a 150-byte ceiling ⇒ free 50 ⇒ the coldest
        // cached file. The pin is irrelevant to the arithmetic, which is the point:
        // the setting keeps working no matter how much the user pins.
        assertEquals(listOf("/cold.bin"), plan)
    }

    @Test
    fun theFreeDiskFloorFiresIndependentlyOfTheCeiling() {
        val plan = selectCacheEvictions(
            ruleSet = onlineOnly,
            cached = listOf(file("/a", 100, 1), file("/b", 100, 2)),
            maxLocalBytes = 0,                 // no ceiling at all
            availableSpaceBytes = 40,          // device nearly full
            minFreeSpaceBytes = 100,
        )

        // A cache's correct response to a full *device* is to yield, whatever the
        // user's own limit says — 60 bytes short ⇒ the coldest file goes.
        assertEquals(listOf("/a"), plan)
    }

    @Test
    fun unknownFreeSpaceSkipsTheFloorInsteadOfReadingAsZero() {
        val plan = selectCacheEvictions(
            ruleSet = onlineOnly,
            cached = listOf(file("/a", 100, 1)),
            maxLocalBytes = 0,
            availableSpaceBytes = null,        // platform cannot report it
            minFreeSpaceBytes = 1_000_000,
        )

        // Reading null as 0 free would make every sweep on such a platform a
        // full cache flush.
        assertEquals(emptyList(), plan)
    }

    @Test
    fun filesWithUnknownRecencyAreEvictedLastNotFirst() {
        val plan = selectCacheEvictions(
            ruleSet = onlineOnly,
            cached = listOf(
                file("/unknown", 100, modified = null),
                file("/known-cold", 100, modified = 1),
            ),
            maxLocalBytes = 100,
            availableSpaceBytes = 10_000,
            minFreeSpaceBytes = 1_000,
        )

        // Sorting null as "epoch 0" would make a platform that cannot report
        // modification times evict its entire cache ahead of anything datable.
        assertEquals(listOf("/known-cold"), plan)
    }

    @Test
    fun equallyColdFilesAreOrderedDeterministically() {
        val cached = listOf(
            file("/b", 100, modified = 5),
            file("/a", 100, modified = 5),
            file("/c", 100, modified = 5),
        )

        val first = selectCacheEvictions(onlineOnly, cached, 100, 10_000, 1_000)
        val second = selectCacheEvictions(onlineOnly, cached.reversed(), 100, 10_000, 1_000)

        assertEquals(listOf("/a", "/b"), first)
        assertEquals(first, second, "a sweep must not depend on walk order")
    }

    @Test
    fun theLargerOfTheTwoPressuresWins() {
        val plan = selectCacheEvictions(
            ruleSet = onlineOnly,
            cached = listOf(file("/a", 100, 1), file("/b", 100, 2), file("/c", 100, 3)),
            maxLocalBytes = 250,      // demands 50 freed
            availableSpaceBytes = 10, // demands 190 freed — the binding one
            minFreeSpaceBytes = 200,
        )

        assertEquals(listOf("/a", "/b"), plan)
    }
}
