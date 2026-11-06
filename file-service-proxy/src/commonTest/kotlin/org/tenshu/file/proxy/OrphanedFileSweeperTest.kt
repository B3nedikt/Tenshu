package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.file.test.createFileTestRegistry
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.TombstoneEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G4a: `File`/`Folder` rows whose ancestors are gone are deleted; everything reachable is not.
 *
 * ⚠⚠ This is a **deleter of user rows**, so the negative cases carry the weight. A sweep that
 * collects the 787 orphans and also takes one live photo is far worse than one that collects
 * nothing — §5.3 rejected a general mark-and-sweep for v1 precisely because "a bug deletes user
 * data".
 */
class OrphanedFileSweeperTest {

    /**
     * ⚠ `InMemoryObjectStore` cannot enumerate by type — `scanRowsOfType` defaults to `null` — so a
     * sweeper over it correctly *refuses*. Enumeration is the whole precondition, so the fixture
     * has to model a store that has it (as `SqliteObjectStore` and the iOS shared replica do).
     */
    private class EnumerableStore(
        private val delegate: InMemoryObjectStore = InMemoryObjectStore(),
    ) : ObjectStore by delegate {
        private val hashes = mutableSetOf<Long>()

        override fun storeObject(dbEntry: ObjectStore.DbEntry) {
            hashes += dbEntry.dataIdHash
            delegate.storeObject(dbEntry)
        }

        override fun removeObject(dataIdHash: Long) {
            hashes -= dataIdHash
            delegate.removeObject(dataIdHash)
        }

        override fun scanRowsOfType(
            typeId: String,
            afterHash: Long,
            matchLimit: Int,
            examineLimit: Int,
        ): ObjectStore.TypeScanPage {
            val out = mutableListOf<ObjectStore.DbEntry>()
            var reached = afterHash
            var examined = 0
            for (hash in hashes.sorted().filter { it > afterHash }) {
                if (examined == examineLimit) return ObjectStore.TypeScanPage(out, reached, false)
                val entry = delegate.getObject(hash)
                examined++
                reached = hash
                if (entry == null || entry.type != typeId) continue
                out += entry
                if (out.size == matchLimit) return ObjectStore.TypeScanPage(out, reached, false)
            }
            return ObjectStore.TypeScanPage(out, reached, exhausted = true)
        }
    }

    private class Fixture {
        val store = EnumerableStore()
        val proxy = TestProxy(schemaRegistry = createFileTestRegistry(), objectStore = store)
        val executionContext = proxy.executionContext

        init {
            executionContext.initFileService()
        }

        fun folder(path: String) {
            executionContext.saveData(Folder.create(proxy.tContext, path, files = emptyList()))
        }

        fun file(path: String) {
            executionContext.saveData(File.create(proxy.tContext, path))
        }

        /**
         * Strand a subtree the way the pre-fix wreckage arose: remove the folder row **beneath the
         * observers**.
         *
         * ⚠⚠ Deleting through `executionContext.deleteData` would not reproduce it —
         * [DeletedFolderSubtreeReaper] now cascades and takes the children with it, which is
         * exactly the fix that stops NEW orphans. The rows on the maintainer's devices predate it,
         * so the fixture has to bypass the observer fanout to recreate that state.
         */
        fun strandFolder(path: String) {
            proxy.database.deleteData(DataId.StringDataId.create(proxy.tContext, File.ID, path))
        }

        fun exists(path: String): Boolean = proxy.database.getData(
            DataId.StringDataId.create(proxy.tContext, File.ID, path)
        ) != null

        fun sweepToCompletion(holder: TombstoneEmitterHolder? = null, historyPulled: (() -> Boolean)? = null): Int {
            val sweeper = OrphanedFileSweeper(executionContext, store, tombstoneEmitterHolder = holder)
            sweeper.historyPulled = historyPulled
            var deleted = 0
            repeat(50) {
                val pass = sweeper.sweepOnce() ?: return deleted
                deleted += pass.deleted
                if (pass.done) return deleted
            }
            return deleted
        }
    }

    @Test
    fun aFileWhoseFolderIsGoneIsCollected() = runTest {
        // The measured shape: a folder tombstoned on a peer, its children stranded, and the paths
        // still occupied so re-upload resolves to Skipped forever.
        val f = Fixture()
        f.folder("/Backup")
        f.file("/Backup/orphan.jpg")
        f.strandFolder("/Backup")
        assertTrue(f.exists("/Backup/orphan.jpg"), "fixture: the child outlived its folder")

        f.sweepToCompletion()

        assertNull(
            f.proxy.database.getData(
                DataId.StringDataId.create(f.proxy.tContext, File.ID, "/Backup/orphan.jpg")
            ),
            "an orphan still occupies its destination path, which is what blocks re-upload",
        )
    }

    @Test
    fun aPopulationLargerThanOnePageIsStillFullyCollected() = runTest {
        // ⚠ The budgets are a tuning constant, but *resumability* is not — and nothing else here
        // exercises it, because every other fixture fits in a single page. This is what makes the
        // cursor load-bearing: raising or lowering PAGE must change only how many ticks the sweep
        // takes, never how much it collects.
        val f = Fixture()
        f.folder("/Backup")
        repeat(600) { f.file("/Backup/orphan-$it.jpg") }
        f.strandFolder("/Backup")

        val deleted = f.sweepToCompletion()

        assertTrue(
            deleted >= 600,
            "every orphan must be collected across however many passes it takes; got $deleted",
        )
        assertTrue(
            !f.exists("/Backup/orphan-0.jpg") && !f.exists("/Backup/orphan-599.jpg"),
            "including the first and last of the range, i.e. both ends of the cursor's travel",
        )
    }

    @Test
    fun theExamineBudgetBoundsAPassAndIsHonoured() = runTest {
        // ⚠ On a store with no type index this budget — not PAGE — is what paces the sweep (the
        // retired iOS file store: 33–99 matches per 2 000 rows examined, measured on device), so a
        // value passed in has to actually reach the scan rather than being decoration.
        val f = Fixture()
        f.folder("/Backup")
        repeat(400) { f.file("/Backup/orphan-$it.jpg") }
        f.strandFolder("/Backup")

        // ⚠⚠ Through `initFileService`, NOT by constructing the sweeper directly. Constructing it
        // here would test a constructor parameter that already worked and skip the code this change
        // actually adds — the plumbing. Verified by falsification: hard-coding EXAMINE_PAGE inside
        // `initFileService` leaves a direct-construction test green.
        val throttled = f.executionContext.initFileService(
            objectStore = f.store,
            sweepExamineLimit = 10,
        )
        assertNotNull(throttled, "a store was supplied, so a sweeper must come back")
        val first = throttled.sweepOnce()

        assertNotNull(first)
        assertTrue(
            first.scanned <= 10,
            "a pass must not examine past its budget; scanned=${first.scanned}",
        )
        assertTrue(
            f.exists("/Backup/orphan-399.jpg"),
            "and must therefore leave the rest for later passes rather than draining the store",
        )
    }

    @Test
    fun thePersistedCursorAdvancesBetweenPasses() = runTest {
        // ⚠⚠ [aPopulationLargerThanOnePageIsStillFullyCollected] does NOT pin this, which is why
        // this test exists. There every row was an orphan, so each pass *deleted its whole page* and
        // the next scan found new rows regardless — breaking the cursor advance entirely left that
        // test green. Convergence via deletion is not resumability.
        //
        // A store whose first page is all REACHABLE rows is the case that separates them: nothing
        // is deleted, the population does not shrink, and only an advancing cursor makes progress.
        // Assert the mechanism directly rather than inferring it from an outcome.
        val f = Fixture()
        f.folder("/Backup")
        repeat(600) { f.file("/Backup/live-$it.jpg") } // all reachable — nothing to delete

        val sweeper = OrphanedFileSweeper(f.executionContext, f.store)
        val first = sweeper.sweepOnce()
        val afterFirst = f.store.loadOrphanedFileSweepCursor()

        assertEquals(0, first?.deleted, "fixture: every row is reachable, so nothing is collected")
        assertNotNull(afterFirst, "a non-exhausted pass must persist where it reached")

        sweeper.sweepOnce()
        val afterSecond = f.store.loadOrphanedFileSweepCursor()

        assertTrue(
            afterSecond != null && afterSecond > afterFirst,
            "the cursor must move even when a pass deletes nothing — otherwise a store whose " +
                "first page is all live rows re-scans it forever and never reaches the orphans",
        )
    }

    /**
     * ⚠⚠ The re-arm on a failed cascade only helps devices that fail a cascade *after* this ships.
     * It does nothing for rows already stranded — and those are the measured ones: 1 154 under a
     * deleted `…/Camera`, on a store that had long since reached [ObjectStore.SWEEP_COMPLETE].
     * The one-shot repair is what reaches them, and "exactly once" is the whole contract: fire it
     * every launch and the store pays a full traversal forever, which is the cost the terminal
     * sentinel exists to avoid.
     */
    @Test
    fun theOneShotRepairReArmsAFinishedSweepExactlyOnce() = runTest {
        val store = EnumerableStore()
        val proxy = TestProxy(schemaRegistry = createFileTestRegistry(), objectStore = store)
        store.storeOrphanedFileSweepCursor(ObjectStore.SWEEP_COMPLETE)

        proxy.executionContext.initFileService(objectStore = store)

        assertNull(
            store.loadOrphanedFileSweepCursor(),
            "a device that finished before the reaper could re-arm must still re-walk once",
        )
        assertEquals(
            ObjectStore.FILE_SWEEP_REPAIR_VERSION, store.loadOrphanedFileSweepRepair(),
            "and record which repair generation it spent",
        )

        // It finishes again, and the app restarts.
        store.storeOrphanedFileSweepCursor(ObjectStore.SWEEP_COMPLETE)
        proxy.executionContext.initFileService(objectStore = store)

        assertEquals(
            ObjectStore.SWEEP_COMPLETE, store.loadOrphanedFileSweepCursor(),
            "a spent repair must not fire again — otherwise every launch re-walks the store",
        )
    }

    @Test
    fun aReachableFileIsNeverTouched() = runTest {
        val f = Fixture()
        f.folder("/Backup")
        f.folder("/Backup/Camera")
        f.file("/Backup/Camera/live.jpg")

        f.sweepToCompletion()

        assertTrue(f.exists("/Backup/Camera/live.jpg"), "every ancestor exists — not a candidate")
        assertTrue(f.exists("/Backup/Camera"))
        assertTrue(f.exists("/Backup"))
    }

    @Test
    fun aGapAnyWhereUpTheChainCountsNotJustTheParent() = runTest {
        // ⚠ Checking only the immediate parent would leave this row for a later cycle: its parent
        // `/a/b/c` still exists — it is `/a/b` that is missing.
        val f = Fixture()
        f.folder("/a")
        f.folder("/a/b")
        f.folder("/a/b/c")
        f.file("/a/b/c/deep.jpg")
        f.strandFolder("/a/b")

        f.sweepToCompletion()

        assertNull(
            f.proxy.database.getData(
                DataId.StringDataId.create(f.proxy.tContext, File.ID, "/a/b/c/deep.jpg")
            ),
            "one pass must catch a chain, not just its first level",
        )
    }

    @Test
    fun theRootIsNeverACandidate() = runTest {
        // ⚠⚠ Root has no parent. Judging it by the same rule as everything else would delete it —
        // and with it, via the subtree reaper, the entire tree.
        val f = Fixture()
        f.folder("/Backup")
        f.file("/Backup/live.jpg")

        f.sweepToCompletion()

        assertNotNull(
            f.proxy.database.getData(DataId.StringDataId.create(f.proxy.tContext, File.ID, "/")),
            "root is bootstrapped by initFileService and must survive every sweep",
        )
        assertTrue(f.exists("/Backup/live.jpg"))
    }

    @Test
    fun aStoreThatCannotEnumerateIsRefusedNotGuessed() = runTest {
        // ⚠ `scanRowsOfType` returning null means "this store cannot enumerate by type". Deleting
        // on a partial view is the one thing a sweep must never do.
        val f = Fixture()
        val blind = object : ObjectStore by f.store {
            override fun scanRowsOfType(
                typeId: String,
                afterHash: Long,
                matchLimit: Int,
                examineLimit: Int,
            ): ObjectStore.TypeScanPage? = null
        }
        f.folder("/Backup")
        f.file("/Backup/orphan.jpg")
        f.strandFolder("/Backup")

        val pass = OrphanedFileSweeper(f.executionContext, blind).sweepOnce()

        assertNull(pass, "no page ⇒ no pass")
        assertTrue(f.exists("/Backup/orphan.jpg"), "and emphatically no deletions")
    }

    @Test
    fun aFinishedSweepNeverScansAgain() = runTest {
        val f = Fixture()
        f.folder("/Backup")
        val sweeper = OrphanedFileSweeper(f.executionContext, f.store)
        repeat(50) { if (sweeper.sweepOnce()?.done == true) return@repeat }

        assertEquals(
            ObjectStore.SWEEP_COMPLETE, f.store.loadOrphanedFileSweepCursor(),
            "the terminal sentinel is what stops a swept store paying a full read at every launch",
        )
        assertNull(
            OrphanedFileSweeper(f.executionContext, f.store).sweepOnce(),
            "and a fresh instance must honour it, not restart the scan",
        )
    }

    /**
     * ⚠⚠ The re-arm is only worth anything if the **live** instance notices it.
     *
     * `DeletedFolderSubtreeReaper` re-arms by clearing a store row, but the sweeper that has
     * already finished is a long-lived object holding `finished = true` in memory. Trusting that
     * latch swallows every re-arm, and the stranded subtree stays uncollectable — the whole defect,
     * reintroduced one layer down. Assert against the same instance, not a fresh one.
     */
    @Test
    fun aReArmedSweepIsNoticedByTheInstanceThatAlreadyFinished() = runTest {
        val f = Fixture()
        f.folder("/Backup")
        val sweeper = OrphanedFileSweeper(f.executionContext, f.store)
        repeat(50) { if (sweeper.sweepOnce()?.done == true) return@repeat }
        assertEquals(
            ObjectStore.SWEEP_COMPLETE, f.store.loadOrphanedFileSweepCursor(), "fixture: finished",
        )
        assertNull(sweeper.sweepOnce(), "fixture: and latched")

        // A folder dies whose children could not be cascaded — orphans created AFTER completion.
        f.file("/Backup/stranded.jpg")
        f.strandFolder("/Backup")
        f.store.clearOrphanedFileSweepCursor()

        var deleted = 0
        repeat(50) {
            val pass = sweeper.sweepOnce() ?: return@repeat
            deleted += pass.deleted
            if (pass.done) return@repeat
        }

        assertTrue(deleted > 0, "the same instance must walk again after a re-arm, not stay latched")
        assertFalse(
            f.exists("/Backup/stranded.jpg"),
            "and must actually collect what the failed cascade left behind — an orphan still " +
                "occupies its destination path, which is what blocks re-upload",
        )
    }

    /** An emitter that records what it was asked to tombstone, and answers as configured. */
    private class RecordingEmitter(
        override val residentOwnerId: String?,
        private val accept: Boolean,
    ) : TombstoneEmitter {
        val emitted = mutableListOf<String>()
        override fun emit(targetEntityIdHash: String, targetEntityType: String, revokedDeviceId: String?): String? =
            error("the sweeper names an orphan by DataId")
        override fun emit(targetEntityId: DataId, targetEntityType: String, revokedDeviceId: String?): String? {
            emitted += (targetEntityId as DataId.StringDataId).keyValue.get()
            return if (accept) "block-${emitted.size}" else null
        }
    }

    /**
     * ⭐ CR-2 B3b (`content-plane-cr2-quorumless.md` §10.12 Q4, revised): with sync present, an orphan
     * goes with a real tombstone, so every device and the relay drop it at one HLC. A silent delete
     * dropped it on this device only — and the reaper now hands the sweep children it deliberately
     * kept, so that split would be routine.
     */
    @Test
    fun withSyncAnOrphanIsRemovedWithATombstone() = runTest {
        val f = Fixture()
        f.folder("/gone")
        f.file("/gone/orphan.txt")
        f.strandFolder("/gone")
        f.store.storeFirstPullMarker(ObjectStore.FIRST_PULL_COMPLETED) // a realm member that has pulled its history
        val emitter = RecordingEmitter(residentOwnerId = "owner-1", accept = true)

        val deleted = f.sweepToCompletion(TombstoneEmitterHolder().apply { this.emitter = emitter })

        assertEquals(1, deleted)
        assertFalse(f.exists("/gone/orphan.txt"))
        assertEquals(listOf("/gone/orphan.txt"), emitter.emitted, "the delete travels as a tombstone")
    }

    @Test
    fun anOrphanWhoseTombstoneIsRefusedIsKept_neverDeletedSilently() = runTest {
        // ⚠ A locked realm or paused sync refuses the tombstone. Deleting anyway would drop the row here
        // while every other device kept it; keeping it is a leak until the next pass, never a loss.
        val f = Fixture()
        f.folder("/gone")
        f.file("/gone/orphan.txt")
        f.strandFolder("/gone")
        f.store.storeFirstPullMarker(ObjectStore.FIRST_PULL_COMPLETED) // a realm member that has pulled its history
        val emitter = RecordingEmitter(residentOwnerId = "owner-1", accept = false)

        val deleted = f.sweepToCompletion(TombstoneEmitterHolder().apply { this.emitter = emitter })

        assertEquals(0, deleted)
        assertTrue(f.exists("/gone/orphan.txt"), "kept, because its delete could not be told to the peers")
    }

    @Test
    fun onADeviceInNoRealmTheDeleteStaysLocal() = runTest {
        // No realm, no peers to tell: the long-standing local delete is still right there.
        val f = Fixture()
        f.folder("/gone")
        f.file("/gone/orphan.txt")
        f.strandFolder("/gone")
        val emitter = RecordingEmitter(residentOwnerId = null, accept = false)

        val deleted = f.sweepToCompletion(TombstoneEmitterHolder().apply { this.emitter = emitter })

        assertEquals(1, deleted)
        assertFalse(f.exists("/gone/orphan.txt"))
        assertTrue(emitter.emitted.isEmpty(), "nothing to tombstone without a realm")
    }

    // ── B.22.6: a missing folder is evidence only once the store has pulled its history ──

    /**
     * ⚠⚠ The C2 backward walk applies the log newest-first, so a joining device (or a store reset to
     * re-pull) holds `/Photos/a.jpg` before `/Photos`. Read as an orphan then, the file would be
     * tombstoned on every device.
     */
    @Test
    fun aFileWhoseFolderHasNotBeenPulledYetIsNeverSwept() = runTest {
        listOf<Pair<String, (ObjectStore) -> Unit>>(
            "never pulled" to { _ -> },
            "first pull started" to { st -> st.storeFirstPullMarker(ObjectStore.FIRST_PULL_STARTED); st.storeSyncCursor(40) },
            "forward done, backward walk mid-way" to { st -> st.storeFirstPullMarker(ObjectStore.FIRST_PULL_COMPLETED); st.storeBackfillCursor(1234) },
            "an unknown marker" to { st -> st.storeFirstPullMarker(99L) },
        ).forEach { (state, arrange) ->
            val f = Fixture()
            f.file("/Photos/a.jpg") // its folder's older block is not pulled yet
            arrange(f.store)
            val emitter = RecordingEmitter(residentOwnerId = "owner-1", accept = true)
            val holder = TombstoneEmitterHolder().apply { this.emitter = emitter }

            assertEquals(0, f.sweepToCompletion(holder), state)
            assertTrue(f.exists("/Photos/a.jpg"), "$state: the file stays")
            assertTrue(emitter.emitted.isEmpty(), "$state: ⚠⚠ and no tombstone left the device")
            assertNull(f.store.loadOrphanedFileSweepCursor(), "$state: the sweep did not move, so nothing is skipped later")
        }
        // An emitter not attached yet is not "no realm": still waits.
        val early = Fixture()
        early.file("/Photos/a.jpg")
        assertEquals(0, early.sweepToCompletion(TombstoneEmitterHolder()))
        assertTrue(early.exists("/Photos/a.jpg"))
    }

    @Test
    fun onceTheHistoryIsPulledTheSweepRunsAsBefore() = runTest {
        listOf<Pair<String, (ObjectStore) -> Unit>>(
            "marker completed, no walk" to { st -> st.storeFirstPullMarker(ObjectStore.FIRST_PULL_COMPLETED) },
            "marker completed, walk complete" to { st -> st.storeFirstPullMarker(ObjectStore.FIRST_PULL_COMPLETED); st.storeBackfillCursor(0) },
            "upgraded store: no marker, cursor moved" to { st -> st.storeSyncCursor(500) },
        ).forEach { (state, arrange) ->
            val f = Fixture()
            f.file("/gone/orphan.txt")
            arrange(f.store)
            val emitter = RecordingEmitter(residentOwnerId = "owner-1", accept = true)
            assertEquals(1, f.sweepToCompletion(TombstoneEmitterHolder().apply { this.emitter = emitter }), state)
            assertEquals(listOf("/gone/orphan.txt"), emitter.emitted, state)
        }
    }

    /**
     * ⚠⚠ Fix round 2 (1): on iOS the store is the shared replica and the emitter's owner is this
     * app's own, null until it adopts Control's identity, while Control may be re-pulling the store.
     * "No realm" is a shortcut only over a store that has never pulled at all.
     */
    @Test
    fun anAppThatHasNotAdoptedYetNeverSweepsAStoreSomeoneIsStillPulling() = runTest {
        listOf<Pair<String, (ObjectStore) -> Unit>>(
            "first pull started" to { st -> st.storeFirstPullMarker(ObjectStore.FIRST_PULL_STARTED) },
            "first pull started, cursor moved" to { st -> st.storeFirstPullMarker(ObjectStore.FIRST_PULL_STARTED); st.storeSyncCursor(40) },
            "a backward walk row" to { st -> st.storeBackfillCursor(900) },
        ).forEach { (state, arrange) ->
            val f = Fixture()
            f.file("/Photos/a.jpg")
            arrange(f.store)
            val emitter = RecordingEmitter(residentOwnerId = null, accept = true)
            assertEquals(0, f.sweepToCompletion(TombstoneEmitterHolder().apply { this.emitter = emitter }), state)
            assertTrue(f.exists("/Photos/a.jpg"), "$state: ⚠⚠ kept: absence on a store being pulled is not evidence")
        }
    }

    /**
     * Fix round 2 (2): where it is wired, the scheduler decides ([OrphanedFileSweeper.historyPulled]),
     * which knows a backfill floor left by a relay that lost the merged lane is never walked again.
     */
    @Test
    fun theSchedulersAnswerDecidesWhereItIsWired() = runTest {
        val f = Fixture()
        f.file("/gone/orphan.txt")
        f.store.storeFirstPullMarker(ObjectStore.FIRST_PULL_COMPLETED)
        f.store.storeBackfillCursor(4_000) // left over: the store's own rule would wait for ever
        val emitter = RecordingEmitter(residentOwnerId = "owner-1", accept = true)
        val holder = TombstoneEmitterHolder().apply { this.emitter = emitter }
        assertEquals(0, f.sweepToCompletion(holder), "the store alone: stricter, waits")
        assertEquals(1, f.sweepToCompletion(holder, historyPulled = { true }), "the scheduler says pulled")
    }
}
