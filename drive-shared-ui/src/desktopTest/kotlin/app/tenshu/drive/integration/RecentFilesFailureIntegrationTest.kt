package app.tenshu.drive.integration

import app.tenshu.drive.data.FilesRepository
import app.tenshu.liststate.ListLoadState
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.dsl.module
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.koin.core.qualifier.named
import kotlin.test.assertTrue

/**
 * The load-bearing half of the zero-state fix, and the one no UI test can reach.
 *
 * `FilesList` can be handed a `FAILED` phase directly, but that proves only that it renders it.
 * Before this change `TenshuFilesRepository.recentFiles` folded `TResult.Failure` into
 * `emptyList()`, so the phase could never *arrive*: every consumer downstream was structurally
 * incapable of telling a broken read from an empty drive, however many branches it grew.
 *
 * The failure is induced the way `StreamSubscribeRetryTest` induces it — withhold the `Stream`
 * operation's implementation, so the subscribe cannot be dispatched and comes back as a failure
 * rather than as an empty stream. That is a faithful model of the real cause: a read issued before
 * the schema session is admitted.
 */
class RecentFilesFailureIntegrationTest {

    private lateinit var filesRepository: FilesRepository

    private lateinit var appKoin: AppIntegrationKoin

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(
            clientId = "app.tenshu.drive.test",
            additionalModules = listOf(
                module {
                    single<OperationImplementationFactory> {
                        // ⚠ Delegate to the harness's **platform** chain, not to core's
                        // `implementationFactory` alone. Core's knows only the generic ops, so
                        // narrowing to it withholds far more than `Stream` — it silently disabled
                        // every sync/file/notification op, and proxy init's fire-and-forget
                        // `registerOfflineKeeperSchedule()` then threw `No implementation found
                        // for operation: …/schedule` into a scope with no handler. That uncaught
                        // exception is reported against whichever test next calls `runTest`, so
                        // this test broke a *different*, innocent one and read as flakiness.
                        val platform =
                            get<(String) -> OperationImplementation>(named("platformImplementationFactory"))
                        OperationImplementationFactory { id ->
                            if (id == Stream.ID) null else platform(id)
                        }
                    }
                }
            ),
        )
        filesRepository = appKoin.get()
    }

    @After
    fun tearDown() {
        appKoin.stop()
    }

    @Test
    fun aReadThatCannotSubscribeReportsFailedRatherThanAnEmptyDrive() = runBlocking {
        val state = withTimeout(10_000) {
            filesRepository.recentFiles(limit = 20).first { it !is ListLoadState.Loading }
        }

        assertTrue(
            state is ListLoadState.Failed,
            "a read that could not subscribe must not answer with a list; got $state"
        )
    }
}
