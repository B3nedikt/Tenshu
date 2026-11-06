package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportNotice
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.ImportWindow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The import must **say what it did**. Every case below once rendered as the same blank screen, so
 * "the Google Fit import doesn't work" could not be told apart from "Health Connect is empty", "the
 * permission was never granted", or "you are looking at a fixture". These lock in the distinction.
 */
class ImportStatusTest {

    @Test
    fun anEmptySourceReportsNoDataRatherThanSilence() = runTest {
        val repo = repository(this, EmptyImporter)

        assertEquals(ImportStatus.Idle, repo.importStatus().first(), "no import has run yet")
        repo.startImport(CategoryId.entries.toSet())

        // The exact case on the maintainer's phone: Health Connect is present and fully authorised,
        // but nothing has written to it — Google Fit does not feed it unless the user connects it.
        assertEquals(
            ImportStatus.NoData(ImportSource.HealthConnect),
            repo.importStatus().first(),
            "an authorised source that holds nothing must report NoData, not stay Idle",
        )
    }

    @Test
    fun aMissingGrantIsReportedAsPermissionsMissing() = runTest {
        val repo = repository(this, UnauthorisedImporter)
        repo.startImport(CategoryId.entries.toSet())

        assertEquals(ImportStatus.PermissionsMissing(ImportSource.HealthConnect), repo.importStatus().first())
    }

    /**
     * ⚠⚠ The fixture is refused, not labelled (`fit-app.md` §9.15). It used to land in the store
     * and report `Sample` — but "the status says it is fake" protected nobody once the rows had
     * synced to every paired device. To the user, sample data is simply no source.
     */
    @Test
    fun theReferenceFixtureIsRefusedAndReportsNoSource() = runTest {
        val repo = repository(this, ReferenceHealthImporter())
        repo.startImport(CategoryId.entries.toSet())

        val status = repo.importStatus().first()
        assertEquals(ImportStatus.SourceUnavailable, status, "a fixture must never read as an import")
        assertTrue(status.needsAttention, "the user is told there is no source")
    }

    @Test
    fun realRowsReportImportedAndDoNotNagTheUser() = runTest {
        val repo = repository(this, OneDayImporter)
        repo.startImport(CategoryId.entries.toSet())

        val status = repo.importStatus().first()
        assertEquals(ImportStatus.Imported(ImportSource.HealthConnect, itemCount = 1), status)
        assertFalse(status.needsAttention, "a working import has nothing to announce")
    }

    // ── the Google Fit notice (fit-app.md §16) ─────────────────────────────────────────
    //
    // The report this exists for: "full history imported 30 items but I only see one workout, and
    // Google Fit shows far more." Every layer was working — the deep-history grant was held, the
    // read really covered ten years — and Health Connect simply held one session, because Google
    // Fit writes nothing to it. Truthful and useless: the app knew why and said nothing.

    @Test
    fun googleFitWithNoWriteGrantIsTheFixableCase() {
        // The maintainer's own phone, read off it: Google Fit installed and current, holding NONE of
        // the WRITE_* grants it declares. Calling this "does not share" would be false and would
        // talk the user out of the switch that fixes it.
        assertEquals(
            ImportNotice.GoogleFitNotPermitted,
            googleFitNotice(
                writers = setOf("android", "com.alltrails.alltrails"),
                googleFitInstalled = true,
                googleFitMayWrite = false,
            ),
        )
    }

    @Test
    fun googleFitPermittedAndStillSilentIsThePermanentCase() {
        // Permission is not the obstacle here, so no amount of granting helps — only an export.
        assertEquals(
            ImportNotice.GoogleFitNotSharing,
            googleFitNotice(
                writers = setOf("android", "com.alltrails.alltrails"),
                googleFitInstalled = true,
                googleFitMayWrite = true,
            ),
        )
    }

    @Test
    fun googleFitWritingToHealthConnectRetiresTheNoticeOnItsOwn() {
        // If Fit ever does start feeding Health Connect, its data arrives through the ordinary read
        // and the claim stops being made — no constant to remember to delete. Checked with the
        // grant BOTH ways: the writers alone settle it.
        assertEquals(
            null,
            googleFitNotice(setOf("android", GOOGLE_FIT_PACKAGE), googleFitInstalled = true, googleFitMayWrite = true),
            "Google Fit among the writers means its data DID come through — saying otherwise would be false",
        )
        assertEquals(
            null,
            googleFitNotice(setOf(GOOGLE_FIT_PACKAGE), googleFitInstalled = true, googleFitMayWrite = false),
            "data that demonstrably arrived outranks a permission probe that says it could not have",
        )
    }

    @Test
    fun aPhoneWithoutGoogleFitIsNotToldAboutGoogleFit() {
        assertEquals(
            null,
            googleFitNotice(setOf("android"), googleFitInstalled = false, googleFitMayWrite = false),
            "an empty Health Connect is not evidence about an app the user does not have",
        )
    }

    @Test
    fun theNoticeSurvivesAllTheWayToTheStatusTheScreenReads() = runTest {
        val repo = repository(this, GoogleFitNotSharingImporter)
        repo.startImport(CategoryId.entries.toSet())

        val status = repo.importStatus().first()
        assertEquals(
            ImportStatus.Imported(ImportSource.HealthConnect, itemCount = 1, notice = ImportNotice.GoogleFitNotSharing),
            status,
            "the importer's finding is the only place this is knowable — dropping it here is what made it invisible",
        )
        // Deliberately still not a nag: it is an explanation beside the source it is about, not an
        // interrupting banner on Home. The import genuinely succeeded.
        assertFalse(status.needsAttention)
    }

    @Test
    fun anEmptyImportCarriesTheNoticeToo() = runTest {
        // Same question from the user's side ("where is my history?"), same answer — so NoData must
        // carry it as well, or the phone with a *completely* empty Health Connect, which is the more
        // common shape, is the one left without the explanation.
        val repo = repository(this, EmptyGoogleFitNotSharingImporter)
        repo.startImport(CategoryId.entries.toSet())

        assertEquals(
            ImportStatus.NoData(ImportSource.HealthConnect, notice = ImportNotice.GoogleFitNotSharing),
            repo.importStatus().first(),
        )
    }

    @Test
    fun aThrowingImporterIsReportedAsFailedAndNotSwallowed() = runTest {
        val repo = repository(this, ThrowingImporter)

        val thrown = runCatching { repo.startImport(CategoryId.entries.toSet()) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "the failure must propagate, not vanish into an empty result")

        val status = repo.importStatus().first()
        assertTrue(status is ImportStatus.Failed, "a broken import must be visible to the user, but was $status")
    }

    @Test
    fun aCancelledImportIsNotReportedAsAFailure() = runTest {
        val repo = repository(this, HangingImporter)

        // The everyday case: the screen that kicked off the import goes away mid-read (a rotation, a
        // cleared ViewModel). The scope cancels — nothing failed.
        val job = launch { repo.startImport(CategoryId.entries.toSet()) }
        HangingImporter.started.await()
        job.cancelAndJoin()

        val status = repo.importStatus().first()
        assertFalse(
            status is ImportStatus.Failed,
            "cancellation is not failure — a rotation must not raise a red 'Import failed' banner (was $status)",
        )
    }

    @Test
    fun twoConcurrentImportsDoNotRaceOnTheStatus() = runTest {
        // The real cold-start shape: the proxy's first-run import and the shell's post-grant import
        // can fire at once. The second must stand aside rather than let its stale outcome land last.
        val importer = CountingImporter()
        val repo = repository(this, importer)

        val first = launch { repo.startImport(CategoryId.entries.toSet()) }
        val second = launch { repo.startImport(CategoryId.entries.toSet()) }
        first.join()
        second.join()

        assertEquals(1, importer.imports, "the second concurrent import must be skipped, not run alongside the first")
        assertEquals(ImportStatus.Imported(ImportSource.HealthConnect, itemCount = 1), repo.importStatus().first())
    }

    // ── importers, each pinning one outcome of the seam ────────────────────────────────

    private object EmptyImporter : HealthImporter {
        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow) =
            ImportOutcome.Success(ImportedHealth())
    }

    private object UnauthorisedImporter : HealthImporter {
        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow) =
            ImportOutcome.PermissionsRequired(setOf("android.permission.health.READ_STEPS"))
    }

    private object OneDayImporter : HealthImporter {
        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow) =
            ImportOutcome.Success(
                ImportedHealth(dailyActivities = listOf(ImportedDailyActivity(date = "2026-07-13", steps = 4_699))),
            )
    }

    /** A real read that worked — one workout, from a writer that is not Google Fit. */
    private object GoogleFitNotSharingImporter : HealthImporter {
        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow) =
            ImportOutcome.Success(
                ImportedHealth(dailyActivities = listOf(ImportedDailyActivity(date = "2026-07-13", steps = 4_699))),
                notice = ImportNotice.GoogleFitNotSharing,
            )
    }

    private object EmptyGoogleFitNotSharingImporter : HealthImporter {
        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow) =
            ImportOutcome.Success(ImportedHealth(), notice = ImportNotice.GoogleFitNotSharing)
    }

    private object ThrowingImporter : HealthImporter {
        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome =
            error("Health Connect read blew up")
    }

    /** Suspends forever mid-read, so the caller's scope can be cancelled out from under it. */
    private object HangingImporter : HealthImporter {
        val started = CompletableDeferred<Unit>()
        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    /** Yields between start and finish, giving a second caller a real chance to slip in. */
    private class CountingImporter : HealthImporter {
        var imports = 0
            private set

        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome {
            imports++
            yield()
            return ImportOutcome.Success(
                ImportedHealth(dailyActivities = listOf(ImportedDailyActivity(date = "2026-07-13", steps = 4_699))),
            )
        }
    }

    /** The same in-process `:health-service` stack `HealthImportTest` stands up, with a chosen importer. */
    private fun repository(scope: CoroutineScope, importer: HealthImporter): FitProjectionRepository {
        val registry = createCoreTestRegistry().apply {
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        val proxy = TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy -> healthServiceProxyImplementationFactory(id, proxy.fileDatabase) },
        ).also { it.executionContext.initHealthService() }

        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        return FitProjectionRepository(client, proxy.tContext, importer = importer, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
    }
}
