package org.tenshu.file.proxy

import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.file.impl.selectKeepOfflineDownloads
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The offline keeper's download-only selection (`OfflineKeeper`,
 * `drive-offline-online.md` §3.2): pick files whose **effective** policy is
 * `KEEP_OFFLINE` but whose bytes are absent locally. Never picks a file that
 * already has bytes, and never picks an `ONLINE_ONLY` file — so the keeper only
 * ever *adds* local copies (download-only).
 */
class OfflineKeeperSelectionTest {

    @Test
    fun picksOnly_keepOfflineFilesMissingBytes() {
        // Device keeps everything offline, except /Archive is online-only.
        val rules = SyncRuleSet(
            deviceDefault = SyncPolicy.KEEP_OFFLINE,
            overrides = listOf(PathSyncOverride("/Archive", SyncPolicy.ONLINE_ONLY)),
        )
        val files = listOf(
            "/a.txt" to false,              // KEEP_OFFLINE, missing  → download
            "/b.txt" to true,               // KEEP_OFFLINE, present  → skip
            "/Archive/c.txt" to false,      // ONLINE_ONLY, missing   → skip
            "/Photos/d.jpg" to false,       // KEEP_OFFLINE, missing  → download
        )

        assertEquals(
            listOf("/a.txt", "/Photos/d.jpg"),
            selectKeepOfflineDownloads(rules, files),
        )
    }

    @Test
    fun onlineOnlyDefault_downloadsNothing() {
        val rules = SyncRuleSet(SyncPolicy.ONLINE_ONLY, emptyList())
        val files = listOf("/a.txt" to false, "/b.txt" to false)
        assertEquals(emptyList(), selectKeepOfflineDownloads(rules, files))
    }

    @Test
    fun nestedKeepOfflineOverride_underOnlineOnlyDefault_isPicked() {
        // Online-only device, but one pinned subfolder is kept offline.
        val rules = SyncRuleSet(
            deviceDefault = SyncPolicy.ONLINE_ONLY,
            overrides = listOf(PathSyncOverride("/Trip", SyncPolicy.KEEP_OFFLINE)),
        )
        val files = listOf(
            "/Trip/photo.jpg" to false,     // effective KEEP_OFFLINE → download
            "/other.txt" to false,          // effective ONLINE_ONLY  → skip
        )
        assertEquals(listOf("/Trip/photo.jpg"), selectKeepOfflineDownloads(rules, files))
    }

    /** `control-background-work-off-fgs.md` §5.6: a metered network selects nothing to download. */
    @Test
    fun aMeteredNetwork_downloadsNothing() {
        val rules = SyncRuleSet(deviceDefault = SyncPolicy.KEEP_OFFLINE, overrides = emptyList())
        val files = listOf("/a.txt" to false)
        assertEquals(listOf("/a.txt"), selectKeepOfflineDownloads(rules, files, metered = false))
        assertEquals(emptyList(), selectKeepOfflineDownloads(rules, files, metered = true))
    }
}
