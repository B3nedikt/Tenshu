package app.tenshu.control.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fast unit coverage for [MockShareManager] — the preview/screen-test backend.
 * Pins the contract the `SharesScreen` (C3) binds against: newest-first
 * ordering, revoked rows retained for the badge, optimistic in-place revoke,
 * empty state, and — since SH-0 — the shared-folder half, including the
 * converge-after-removal the real manager gets from its roster re-read.
 */
class MockShareManagerTest {

    @Test
    fun shares_areNewestFirst_andKeepRevokedRows() = runTest {
        val rows = MockShareManager().shares().first()
        assertEquals(MockShareManager.SAMPLE.size, rows.size, "revoked rows are kept")
        val timestamps = rows.map { it.createdAtMillis }
        assertEquals(timestamps.sortedDescending(), timestamps, "newest-first")
    }

    @Test
    fun revoke_flipsTheRowInPlace_withoutDropping() = runTest {
        val manager = MockShareManager()
        val target = manager.shares().first().first { !it.revoked }
        manager.revoke(target.shareId)
        val after = manager.shares().first()
        assertEquals(MockShareManager.SAMPLE.size, after.size, "row stays in the list")
        assertTrue(after.single { it.shareId == target.shareId }.revoked)
    }

    @Test
    fun emptyInitial_rendersEmptyState() = runTest {
        assertEquals(emptyList(), MockShareManager(initial = emptyList()).shares().first())
    }

    @Test
    fun sharedFolders_areOwnedFirst_andCarryTheUnnamedCase() = runTest {
        val folders = MockShareManager().sharedFolders().first()
        assertEquals(MockShareManager.SAMPLE_FOLDERS.size, folders.size)
        assertTrue(folders.first().ownerSelf, "owned folders sort first")
        assertTrue(
            folders.any { it.name == null && it.memberCount == null },
            "the sample keeps a realm whose wrapper root hasn't synced yet",
        )
    }

    @Test
    fun members_areScopedToTheirRealm() = runTest {
        val manager = MockShareManager()
        assertEquals(3, manager.members("realm-holiday").first().size)
        assertEquals(2, manager.members("realm-band").first().size)
        assertEquals(emptyList(), manager.members("realm-unknown").first())
    }

    @Test
    fun removeMember_dropsTheRow_andDecrementsTheFolderCount() = runTest {
        val manager = MockShareManager()
        manager.removeMember("realm-holiday", "member-jo")

        val roster = manager.members("realm-holiday").first()
        assertEquals(2, roster.size)
        assertTrue(roster.none { it.memberId == "member-jo" })
        assertEquals(
            2,
            manager.sharedFolders().first().single { it.realmId == "realm-holiday" }.memberCount,
            "the folder row converges with the roster",
        )
    }

    @Test
    fun removeMember_onAnUnknownRealm_isANoOp() = runTest {
        // The real op fails owner-only; a preview backend cannot model that, and
        // throwing here would make previews brittle rather than honest.
        MockShareManager().removeMember("realm-unknown", "member-1")
    }
}
