package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The per-lane tombstone cursor (`sync-lane-cursor-split.md` L1).
 *
 * An absent tombstone-cursor row reads as `0`: every writer moves both lanes together, so absence
 * means a fresh store. The lanes are independent once written.
 */
class TombstoneCursorSeedTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate()
        )
    }

    @Test
    fun afreshStoreStartsBothLanesAtZero() {
        assertEquals(0L, syncManager.lastSyncTimestamp)
        assertEquals(0L, syncManager.lastTombstoneSyncTimestamp)
    }

    @Test
    fun anAbsentTombstoneCursorReadsZero_notTheBlockCursor() {
        // Every writer moves both lanes together, so an absent tombstone row is a fresh store and
        // must back-fill from zero — never inherit the block lane's position.
        syncManager.lastSyncTimestamp = 29_044L

        assertEquals(0L, syncManager.lastTombstoneSyncTimestamp)
    }

    @Test
    fun theLanesAreIndependentOnceBothAreWritten() {
        syncManager.lastSyncTimestamp = 29_044L
        syncManager.lastTombstoneSyncTimestamp = 29_544L

        // The block lane races ahead; the tombstone lane must stay exactly where it was.
        syncManager.lastSyncTimestamp = 71_391L

        assertEquals(71_391L, syncManager.lastSyncTimestamp)
        assertEquals(
            29_544L,
            syncManager.lastTombstoneSyncTimestamp,
            "Advancing one lane must not move the other — that coupling was the bug"
        )
    }

    @Test
    fun aWrittenZeroIsNotMistakenForAbsent() {
        // The absent-vs-zero distinction the nullable return exists for: a device legitimately
        // sitting at 0 on the tombstone lane while the block lane has advanced must NOT be
        // re-seeded forward, or it would skip every tombstone below the block cursor.
        syncManager.lastSyncTimestamp = 29_044L
        syncManager.lastTombstoneSyncTimestamp = 0L

        assertEquals(0L, syncManager.lastTombstoneSyncTimestamp)
    }
}
