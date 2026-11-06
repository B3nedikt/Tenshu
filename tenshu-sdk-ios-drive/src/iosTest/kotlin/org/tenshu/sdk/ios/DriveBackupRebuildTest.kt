package org.tenshu.sdk.ios

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `ios-shared-store-sqlite.md` B.16 (228): Drive's backup loop is a
 * process-level component built over one graph. When the shared store's host stops that graph the
 * loop's scope is cancelled and the controller over it dropped, so the rebuild's install starts
 * one over the new graph, and `backupNow()` never runs the stopped one. Over two real assemblies.
 */
class DriveBackupRebuildTest {

    private fun assembly() = DriveLocalRuntime.assemble(
        replicaDirectory = NSTemporaryDirectory() + "drive-backup-rebuild-" + Random.nextLong().toString(16),
        secureStorage = InMemorySecureStorageDelegate(),
    )

    private var started: DriveLocalRuntime.Assembly? = null

    @AfterTest
    fun detach() {
        started?.let { DriveBackupSurface.detachIfOver(it.client.tenshuClient) }
    }

    @Test
    fun aStoppedGraphsBackupLoopEndsAndTheRebuildStartsOneOverTheNewGraph() {
        val old = assembly()
        val new = assembly()
        DriveBackupSurface.start(old.client.tenshuClient, old.client.filesRepository)
        started = old
        val oldController = assertNotNull(DriveBackupSurface.controllerOrNull())
        val oldScope = assertNotNull(DriveBackupSurface.controllerScopeOrNull())

        // Another graph's stop leaves this loop alone.
        DriveBackupSurface.detachIfOver(new.client.tenshuClient)
        assertSame(oldController, DriveBackupSurface.controllerOrNull())

        // The old graph stopped: its loop ends, nothing of it is left to run.
        DriveBackupSurface.detachIfOver(old.client.tenshuClient)
        assertFalse(oldScope.isActive, "the stopped graph's loop is cancelled")
        assertNull(DriveBackupSurface.controllerOrNull())
        assertFalse(runBlocking { DriveBackupSurface.backupNow() }, "no stopped loop is run")

        // The rebuild's install: a loop over the new graph.
        DriveBackupSurface.start(new.client.tenshuClient, new.client.filesRepository)
        started = new
        val rebuilt = assertNotNull(DriveBackupSurface.controllerOrNull())
        assertTrue(rebuilt !== oldController, "a controller over the rebuilt graph")
        assertTrue(assertNotNull(DriveBackupSurface.controllerScopeOrNull()).isActive)
    }

    @Test
    fun aLoopOverAnotherGraphIsReplacedEvenWhenNothingDetachedIt() {
        // The rebuilt graph's start must not keep a stale loop.
        val old = assembly()
        val new = assembly()
        DriveBackupSurface.start(old.client.tenshuClient, old.client.filesRepository)
        started = old
        val oldController = assertNotNull(DriveBackupSurface.controllerOrNull())
        val oldScope = assertNotNull(DriveBackupSurface.controllerScopeOrNull())
        DriveBackupSurface.start(new.client.tenshuClient, new.client.filesRepository)
        started = new
        assertFalse(oldScope.isActive, "the stale loop ended")
        assertTrue(assertNotNull(DriveBackupSurface.controllerOrNull()) !== oldController, "replaced, never kept")
        val again = DriveBackupSurface.controllerOrNull()
        DriveBackupSurface.start(new.client.tenshuClient, new.client.filesRepository)
        assertSame(again, DriveBackupSurface.controllerOrNull(), "idempotent over the same graph")
    }

    @Test
    fun aNewFolderAnswerWithNoServingGraphIsKeptAndTheNextGraphAppliesIt() = runBlocking {
        // B.16 (237): never a silent loss, never the stopped graph's
        // decisions.
        val saved = DriveBackupSurface.switched
        DriveBackupSurface.switched = { true }
        try {
            val old = assembly()
            val new = assembly()
            DriveBackupSurface.start(old.client.tenshuClient, old.client.filesRepository)
            started = old
            DriveBackupSurface.detachIfOver(old.client.tenshuClient)
            val before = DriveBackupSurface.keptAnswerCount()
            DriveBackupSurface.answerNewFolderPrompt("made-up-album", accept = true)
            assertEquals(before + 1, DriveBackupSurface.keptAnswerCount(), "kept, not dropped")
            DriveBackupSurface.start(new.client.tenshuClient, new.client.filesRepository)
            started = new
            val prefs = app.tenshu.ingest.backup.TenshuBackupPreferences(new.client.tenshuClient)
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            while ("made-up-album" !in prefs.readSelectedBuckets() && start.elapsedNow().inWholeMilliseconds < 10_000) {
                kotlinx.coroutines.delay(20)
            }
            assertTrue("made-up-album" in prefs.readSelectedBuckets(), "the new graph applied the kept answer")
            val dropped = kotlin.time.TimeSource.Monotonic.markNow()
            while (DriveBackupSurface.keptAnswerCount() != 0 && dropped.elapsedNow().inWholeMilliseconds < 10_000) kotlinx.coroutines.delay(20)
            assertEquals(0, DriveBackupSurface.keptAnswerCount(), "dropped once written (243)")
        } finally {
            DriveBackupSurface.switched = saved
        }
    }

    @Test
    fun withTheSwitchOffAnAnswerWithNoGraphIsDroppedAsBefore() = runBlocking {
        val saved = DriveBackupSurface.switched
        DriveBackupSurface.switched = { false }
        try {
            val old = assembly()
            DriveBackupSurface.start(old.client.tenshuClient, old.client.filesRepository)
            started = old
            DriveBackupSurface.detachIfOver(old.client.tenshuClient)
            val before = DriveBackupSurface.keptAnswerCount()
            DriveBackupSurface.answerNewFolderPrompt("made-up-album", accept = true)
            assertEquals(before, DriveBackupSurface.keptAnswerCount(), "unchanged off: nothing kept")
        } finally {
            DriveBackupSurface.switched = saved
        }
    }

    private suspend fun selectedIn(a: DriveLocalRuntime.Assembly): List<String> =
        app.tenshu.ingest.backup.TenshuBackupPreferences(a.client.tenshuClient).readSelectedBuckets()

    private suspend fun eventually(what: String, done: suspend () -> Boolean) {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        while (!done() && start.elapsedNow().inWholeMilliseconds < 10_000) kotlinx.coroutines.delay(20)
        assertTrue(done(), what)
    }

    private inline fun <T> seamed(block: () -> T): T {
        val switched = DriveBackupSurface.switched
        val protect = DriveBackupSurface.protect
        val write = DriveBackupSurface.writeAnswer
        DriveBackupSurface.switched = { true }
        try {
            return block()
        } finally {
            DriveBackupSurface.switched = switched
            DriveBackupSurface.protect = protect
            DriveBackupSurface.writeAnswer = write
            DriveBackupSurface.dropKeptAnswers()
        }
    }

    @Test
    fun aKeptAnswerStaysUntilItsWriteSucceeds() = runBlocking {
        // B.16 (243): no protected time, a failed write, or the replay's
        // scope cancelled first (a rebuild that lands on the not-ready stand-in, whose graph is
        // stopped as it is handed over) all keep the answer; only a write that succeeded drops it.
        seamed {
            val stand = assembly()
            val new = assembly()
            DriveBackupSurface.dropKeptAnswers()
            // No protected time for a live answer: kept.
            DriveBackupSurface.protect = { null }
            DriveBackupSurface.start(stand.client.tenshuClient, stand.client.filesRepository)
            started = stand
            DriveBackupSurface.answerNewFolderPrompt("made-up-album", accept = true)
            assertEquals(1, DriveBackupSurface.keptAnswerCount(), "no protected time: kept")
            // The rebuild lands on a stand-in: its replay waits for its entry, and the stand-in's
            // graph is stopped (its scope cancelled) before it runs.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            DriveBackupSurface.protect = { block -> gate.await(); block() }
            DriveBackupSurface.detachIfOver(stand.client.tenshuClient)
            DriveBackupSurface.start(stand.client.tenshuClient, stand.client.filesRepository)
            DriveBackupSurface.detachIfOver(stand.client.tenshuClient)
            gate.complete(Unit)
            kotlinx.coroutines.delay(200)
            assertEquals(1, DriveBackupSurface.keptAnswerCount(), "a cancelled replay keeps it")
            // A write that fails keeps it.
            DriveBackupSurface.protect = { block -> block() }
            DriveBackupSurface.writeAnswer = { _, _, _ -> error("made up: the store refused the write") }
            DriveBackupSurface.start(new.client.tenshuClient, new.client.filesRepository)
            started = new
            kotlinx.coroutines.delay(300)
            assertEquals(1, DriveBackupSurface.keptAnswerCount(), "a failed write keeps it")
            // The next serving graph writes it, and only then drops it.
            DriveBackupSurface.writeAnswer = { d, bucket, accept -> if (accept) d.accept(bucket) else d.dismiss(bucket) }
            DriveBackupSurface.detachIfOver(new.client.tenshuClient)
            DriveBackupSurface.start(new.client.tenshuClient, new.client.filesRepository)
            eventually("applied and dropped") { DriveBackupSurface.keptAnswerCount() == 0 && "made-up-album" in selectedIn(new) }
        }
    }

    @Test
    fun aLiveAnswerSupersedesAKeptOneForItsBucket() = runBlocking {
        // B.16 (243): an older kept answer never overwrites a newer live one.
        seamed {
            val old = assembly()
            val new = assembly()
            DriveBackupSurface.dropKeptAnswers()
            DriveBackupSurface.start(old.client.tenshuClient, old.client.filesRepository)
            started = old
            DriveBackupSurface.detachIfOver(old.client.tenshuClient)
            DriveBackupSurface.answerNewFolderPrompt("made-up-album", accept = true)
            assertEquals(1, DriveBackupSurface.keptAnswerCount())
            // The new graph's replay is held at its entry; the user declines meanwhile.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            var first = true
            DriveBackupSurface.protect = { block ->
                // Bounded: a replay that never asks (a broken start) must fail here, not hang the suite.
                if (first) { first = false; kotlinx.coroutines.withTimeout(10_000) { gate.await() } }
                block()
            }
            DriveBackupSurface.start(new.client.tenshuClient, new.client.filesRepository)
            started = new
            kotlinx.coroutines.delay(100)
            DriveBackupSurface.answerNewFolderPrompt("made-up-album", accept = false)
            gate.complete(Unit)
            kotlinx.coroutines.delay(500)
            assertEquals(0, DriveBackupSurface.keptAnswerCount())
            assertFalse("made-up-album" in selectedIn(new), "the newer decline stands")
        }
    }

    @Test
    fun keptAnswersAreDroppedWhenTheirGraphIsGivenUp() = runBlocking {
        // B.16 (244): a deprovision (the graph's abandon) drops what waited for that realm.
        seamed {
            val old = assembly()
            DriveBackupSurface.dropKeptAnswers()
            DriveBackupSurface.start(old.client.tenshuClient, old.client.filesRepository)
            started = old
            DriveBackupSurface.detachIfOver(old.client.tenshuClient)
            DriveBackupSurface.answerNewFolderPrompt("made-up-album", accept = true)
            assertEquals(1, DriveBackupSurface.keptAnswerCount())
            DriveBackupSurface.dropKeptAnswers()
            assertEquals(0, DriveBackupSurface.keptAnswerCount(), "never applied to another realm")
        }
    }
    @Test
    fun aKeptAnswerIsReplayedAtTheNextForegroundAndWithTheNextAnswer() = runBlocking {
        // B.17 (265): a graph that serves but could not apply a kept
        // answer at its start applies it at the next foreground, or with the next live answer,
        // not only at the next graph's start (which a healthy graph never gives way to).
        seamed {
            val graph = assembly()
            DriveBackupSurface.dropKeptAnswers()
            DriveBackupSurface.start(graph.client.tenshuClient, graph.client.filesRepository)
            started = graph
            DriveBackupSurface.protect = { null }
            DriveBackupSurface.answerNewFolderPrompt("made-up-album-a", accept = true)
            assertEquals(1, DriveBackupSurface.keptAnswerCount(), "no protected time: kept")
            DriveBackupSurface.protect = { block -> block() }
            // The foreground hook (`onCurrentGraphForeground`) while this graph serves.
            DriveBackupSurface.replayKeptAnswers()
            eventually("replayed at the foreground") { DriveBackupSurface.keptAnswerCount() == 0 && "made-up-album-a" in selectedIn(graph) }
            // Kept again, then the next live answer takes it along.
            DriveBackupSurface.protect = { null }
            DriveBackupSurface.answerNewFolderPrompt("made-up-album-b", accept = true)
            assertEquals(1, DriveBackupSurface.keptAnswerCount())
            DriveBackupSurface.protect = { block -> block() }
            DriveBackupSurface.answerNewFolderPrompt("made-up-album-c", accept = true)
            assertEquals(0, DriveBackupSurface.keptAnswerCount(), "applied with the live answer")
            assertTrue("made-up-album-b" in selectedIn(graph))
            assertTrue("made-up-album-c" in selectedIn(graph))
        }
    }

    @Test
    fun anAnswerThatEndsAfterItsGraphWasGivenUpIsNotKeptForTheNextRealm() = runBlocking {
        // B.17 (266): a late keep after the abandon's drop would wait for the next realm's graph.
        seamed {
            val graph = assembly()
            DriveBackupSurface.dropKeptAnswers()
            DriveBackupSurface.start(graph.client.tenshuClient, graph.client.filesRepository)
            started = graph
            DriveBackupSurface.detachIfOver(graph.client.tenshuClient)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            // The answer's entry is still being refused when the graph is given up.
            DriveBackupSurface.protect = { _ -> gate.await(); null }
            val answer = launch { DriveBackupSurface.answerNewFolderPrompt("made-up-album", accept = true) }
            kotlinx.coroutines.delay(100)
            DriveBackupSurface.dropKeptAnswers()
            gate.complete(Unit)
            answer.join()
            assertEquals(0, DriveBackupSurface.keptAnswerCount(), "⚠⚠ never kept across the drop")
            // An answer begun after the drop is kept as before.
            DriveBackupSurface.protect = { null }
            DriveBackupSurface.answerNewFolderPrompt("made-up-album", accept = true)
            assertEquals(1, DriveBackupSurface.keptAnswerCount())
        }
    }
}
