package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The launch-pass gating (fit-app.md §9.15): the outbox drain runs unconditionally; the
 * unattended import refresh runs only for an importer that reports [HealthImporter.autoRefreshReady]
 * — a grant-less source would abort into a permanent banner every launch, and an absent platform
 * source would fall back to fixture data, which must never be auto-written into the durable,
 * synced store.
 */
@kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HealthImportBootstrapTest {

    private class RecordingRepository(
        /** Stands in for `ControlReachability.awaitRealmWritable`; open by default, as with no probe. */
        private val realmWritable: kotlinx.coroutines.CompletableDeferred<Unit> =
            kotlinx.coroutines.CompletableDeferred(Unit),
    ) : FitRepository by MockFitRepository() {
        var drained = false
        var imports = 0
        val imported: Boolean get() = imports > 0

        override suspend fun awaitRealmWritable() = realmWritable.await()

        override suspend fun drainPendingWorkouts() {
            drained = true
        }

        /** Whether every import this fake saw declared itself unattended — see DS-2 / D4. */
        var allImportsWereAutomatic = true
            private set

        override suspend fun startImport(
            selection: Set<CategoryId>,
            window: ImportWindow,
            automatic: Boolean,
        ) {
            imports++
            if (!automatic) allImportsWereAutomatic = false
        }
    }

    private class FakeImporter(
        private val ready: suspend () -> Boolean,
    ) : HealthImporter {
        override fun availableSources(): List<ImportSource> = listOf(ImportSource.HealthConnect)
        override suspend fun autoRefreshReady(): Boolean = ready()
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome =
            ImportOutcome.Success(ImportedHealth())
    }

    /**
     * ⚠⚠ ADR 0025 §Decision 8.6: with Control's device known to have no realm, the launch pass —
     * outbox drain AND Health Connect import — must not run at all, and it must run by itself once
     * a realm appears (the held pass is the resumption; nothing has to call [refresh] again).
     */
    @Test
    fun aHeldRealmGateHoldsTheWholePassUntilItOpens() = runTest {
        val realm = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = RecordingRepository(realmWritable = realm)
        HealthImportBootstrap(repo, this, importer = FakeImporter(ready = { true }))
        advanceUntilIdle()
        assertFalse(repo.drained, "no outbox drain into a store with no realm")
        assertFalse(repo.imported, "no Health Connect import into a store with no realm")

        realm.complete(Unit)
        advanceUntilIdle()
        assertTrue(repo.drained, "the held drain runs once a realm appears")
        assertTrue(repo.imported, "and so does the held import")
    }

    /** ⚠ Several foregrounds while held collapse into ONE pass on release (the debounce still holds). */
    @Test
    fun foregroundsWhileHeldRunOnePassOnRelease() = runTest {
        val realm = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = RecordingRepository(realmWritable = realm)
        val bootstrap = HealthImportBootstrap(repo, this, importer = FakeImporter(ready = { true }))
        bootstrap.refresh("foreground")
        bootstrap.refresh("foreground")
        advanceUntilIdle()
        assertEquals(0, repo.imports)

        realm.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repo.imports, "held passes collapse into one")
    }

    /** A repository whose realm gate can release, and whose first import the gate holds. */
    private class AppearingRealmRepository(private val stopFirstImport: Boolean) :
        FitRepository by MockFitRepository() {
        val releases = kotlinx.coroutines.flow.MutableStateFlow(0L)
        var stops = 0L
        var imports = 0
        var drains = 0

        override fun realmReleaseMarker() = releases.value
        override suspend fun onEachRealmRelease(since: Long, action: suspend () -> Unit) {
            var seen = since
            releases.collect { if (it != seen) { seen = it; action() } }
        }
        override fun realmHeldStops() = stops
        override suspend fun drainPendingWorkouts() { drains++ }
        override suspend fun startImport(selection: Set<CategoryId>, window: ImportWindow, automatic: Boolean) {
            imports++
            if (stopFirstImport && imports == 1) stops++ // the gate held it (at entry or mid-run)
        }
    }

    /**
     * ⚠⚠ Codex P1 on fde421540 + P2 on 792da428e: a pass the gate held — stopped mid-import, or
     * rejected at entry — re-runs IN FULL on the next release, bypassing the debounce.
     */
    @Test
    fun aPassStoppedByTheAbsenceReRunsWhenARealmAppears() = runTest {
        val repo = AppearingRealmRepository(stopFirstImport = true)
        HealthImportBootstrap(repo, backgroundScope, importer = FakeImporter(ready = { true }))
        runCurrent()
        assertEquals(1, repo.imports)

        repo.releases.value += 1
        runCurrent()
        assertEquals(2, repo.imports, "the stopped pass re-runs once a realm appears")
        assertEquals(2, repo.drains)
    }

    /** ⚠ …and a pass that was only WAITING for the realm (it ran cleanly) is not run twice. */
    @Test
    fun aCleanPassIsNotReRunOnAppearance() = runTest {
        val repo = AppearingRealmRepository(stopFirstImport = false)
        HealthImportBootstrap(repo, backgroundScope, importer = FakeImporter(ready = { true }))
        runCurrent()
        repo.releases.value += 1
        runCurrent()
        assertEquals(1, repo.imports, "nothing was stopped, so nothing re-runs")
    }

    @Test
    fun noImporterDrainsButNeverImports() = runTest {
        val repo = RecordingRepository()
        HealthImportBootstrap(repo, this, importer = null)
        advanceUntilIdle()
        assertTrue(repo.drained, "the outbox drain is unconditional")
        assertFalse(repo.imported, "no importer bound → no unattended refresh")
    }

    @Test
    fun notReadyImporterDrainsButSkipsTheRefresh() = runTest {
        val repo = RecordingRepository()
        HealthImportBootstrap(repo, this, importer = FakeImporter(ready = { false }))
        advanceUntilIdle()
        assertTrue(repo.drained)
        assertFalse(repo.imported, "grant-less / source-absent importer → refresh skipped")
    }

    @Test
    fun readyImporterRefreshes() = runTest {
        val repo = RecordingRepository()
        HealthImportBootstrap(repo, this, importer = FakeImporter(ready = { true }))
        advanceUntilIdle()
        assertTrue(repo.drained)
        assertTrue(repo.imported)
    }

    @Test
    fun readinessFailureCountsAsNotReady() = runTest {
        val repo = RecordingRepository()
        HealthImportBootstrap(repo, this, importer = FakeImporter(ready = { error("HC probe blew up") }))
        advanceUntilIdle()
        assertTrue(repo.drained, "a broken readiness check must not take the drain down with it")
        assertFalse(repo.imported)
    }

    @Test
    fun referenceImporterIsNeverAutoRefreshReady() = runTest {
        assertFalse(ReferenceHealthImporter().autoRefreshReady(), "fixtures never refresh unattended")
    }

    /**
     * The bug this exists for: the pass was once per *process*, and a Fit process lives for days.
     * A foreground after the debounce window must pull whatever the health source has gained since
     * — on 2026-08-13 a 13-hour-old process meant that morning's ride was never imported at all.
     */
    @Test
    fun aForegroundAfterTheWindowRefreshesAgain() = runTest {
        val repo = RecordingRepository()
        val time = TestTimeSource()
        val bootstrap = HealthImportBootstrap(
            repo, this, importer = FakeImporter(ready = { true }), timeSource = time,
        )
        advanceUntilIdle()
        assertEquals(1, repo.imports, "the launch pass")

        time += 61.seconds
        bootstrap.refresh(reason = "foreground")
        advanceUntilIdle()
        assertEquals(2, repo.imports, "a foreground past the debounce window imports again")
    }

    /**
     * The cold-start pair: `startKoin` fires the launch pass and the Activity's first `onStart`
     * calls [HealthImportBootstrap.refresh] milliseconds later. One import, not two — and the same
     * guard is what keeps a rotation from re-reading Health Connect.
     */
    @Test
    fun aForegroundInsideTheWindowIsTheSamePass() = runTest {
        val repo = RecordingRepository()
        val time = TestTimeSource()
        val bootstrap = HealthImportBootstrap(
            repo, this, importer = FakeImporter(ready = { true }), timeSource = time,
        )
        advanceUntilIdle()

        time += 1.seconds
        bootstrap.refresh(reason = "foreground")
        advanceUntilIdle()
        assertEquals(1, repo.imports, "a foreground inside the debounce window folds into the launch pass")
    }

    /**
     * ⚠⚠ DS-2 / D4. The auto-import switch gates `startImport(automatic = true)`, so a bootstrap
     * that forgot the flag would keep importing from a source the user had switched off — and
     * nothing else would notice, because the import itself still succeeds.
     *
     * ⚠ This bootstrap is only *one* of three unattended callers; iOS's `FitLocalRuntime` and
     * `EpcFitClient` each re-implement the foreground refresh and are not covered here.
     */
    @Test
    fun theBootstrapDeclaresItsPassesUnattended() = runTest {
        val repo = RecordingRepository()
        HealthImportBootstrap(repo, this, importer = FakeImporter(ready = { true }))
        advanceUntilIdle()

        assertEquals(1, repo.imports)
        assertTrue(
            repo.allImportsWereAutomatic,
            "an unattended pass must pass automatic = true or it bypasses the user's switch",
        )
    }
}
