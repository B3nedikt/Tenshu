package app.tenshu.sdk.internal

import app.tenshu.sdk.SdkRuntimeTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.db.ObjectStore
import kotlin.test.Test
import kotlin.test.assertEquals

// A1 on the web (`sync-cursor-store-lifecycle.md`): the pull cursors are rows in the SAME object
// store the `Database` runs over, so they survive a page reload and are wiped with the data.
//
// ⚠⚠ `JsRuntime` built its `SyncManagerImpl` without `objectStore`, so the cursors went to the
// constructor's throwaway `InMemoryObjectStore`: every reload re-pulled the relay log from 0 and
// re-walked the backfill, while the IndexedDB store kept its rows — the cursor/store split A1
// exists to make unrepresentable. Nothing failed; the device just quietly re-did all of it.
//
// The reload is simulated the way `IndexedDbObjectStoreTest` does it: a second, independent
// `IndexedDbObjectStore.open` over the runtime's own database name hydrates from disk alone.
class JsSyncCursorDurabilityTest : SdkRuntimeTest() {

    @Test
    fun syncCursors_landInTheRuntimeStore_andSurviveAReload() = runTest {
        JsRuntime.awaitReady()
        // ⚠⚠ Quiet the live scheduler for this test's window. A tick landing inside `flush()` runs
        // `pullMergedLog`, which writes `max(min(forward, tombstone), …)` into BOTH cursors — against
        // the unreachable test sink that is the tombstone value, so the forward cursor "reloads" as
        // 314159. Latent all along and timing-only: web-app-suite run B1's `ContactsKeyringTest`
        // shifted the suite's timing and it went red 6/6 (green with that suite removed). `stop()`
        // alone is not enough — an in-flight tick runs on (see `SyncScheduler.stopAndAwait`).
        val scheduler = JsRuntime.syncSchedulerHolder.scheduler
        scheduler?.stopAndAwait()
        val syncManager = JsRuntime.syncManager
        val originalForward = syncManager.lastSyncTimestamp
        val originalTombstone = syncManager.lastTombstoneSyncTimestamp
        // ⚠ Absent and 0 differ for this row (absent seeds from the forward cursor), so the restore
        // below must delete a row that was never there rather than write one.
        val tombstoneRowExisted = JsRuntime.objectStore.loadTombstoneCursor() != null
        val forward = originalForward + 7_340_017L
        val tombstone = originalTombstone + 3_141_59L
        try {
            syncManager.lastSyncTimestamp = forward
            syncManager.lastTombstoneSyncTimestamp = tombstone

            // The runtime's own store holds them — not a private one beside it.
            assertEquals(forward, JsRuntime.objectStore.loadSyncCursor(), "forward cursor row")
            assertEquals(tombstone, JsRuntime.objectStore.loadTombstoneCursor(), "tombstone cursor row")

            // And they are on disk: a fresh hydration of the same database reads them back.
            JsRuntime.objectStore.flush()
            val reloadScope = CoroutineScope(Dispatchers.Default)
            val reloaded = IndexedDbObjectStore.open(
                dbName = JsRuntime.OBJECT_STORE_DB_NAME,
                scope = reloadScope,
                // ⚠ Read-only: claiming would fence the live runtime's own writes out.
                claim = false,
            )
            try {
                assertEquals(forward, reloaded.loadSyncCursor(), "forward cursor after reload")
                assertEquals(tombstone, reloaded.loadTombstoneCursor(), "tombstone cursor after reload")
            } finally {
                reloaded.close()
                reloadScope.cancel()
            }
        } finally {
            syncManager.lastSyncTimestamp = originalForward
            if (tombstoneRowExisted) {
                syncManager.lastTombstoneSyncTimestamp = originalTombstone
            } else {
                JsRuntime.objectStore.removeObject(ObjectStore.TOMBSTONE_CURSOR_HASH)
            }
            JsRuntime.objectStore.flush()
            scheduler?.start()
        }
    }
}
