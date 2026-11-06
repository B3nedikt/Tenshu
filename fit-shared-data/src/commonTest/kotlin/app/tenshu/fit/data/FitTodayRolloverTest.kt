package app.tenshu.fit.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `today` must follow the clock, because nothing else in Fit does.
 *
 * The bug (2026-08-13, the maintainer's own phone): `today` was a `val` evaluated in the
 * constructor, and this repository is a **process-scoped Koin singleton**. The Fit process had
 * been up 13 h 26 m — started the previous evening, never restarted — so `today` was still
 * 12 Aug, and `dayLabel(daysAgo <= 0 → "Today")` drew a `Today` header over the whole of the
 * previous day: a run that had happened at 18:54 the night before was the top row of "Today" at
 * 10:39 in the morning. Nothing about the times themselves was wrong; only the day they were
 * filed under.
 *
 * ⚠ Revert `today` to a `val` and this fails on the second assertion — which is the whole point,
 * since the real trigger (a process living across midnight) is unreachable from a test.
 */
class FitTodayRolloverTest {

    /** Wall clock under the test's control; the only seam the repository needs for this. */
    private class MovableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    @Test
    fun todayFollowsTheClockAcrossMidnight() = runTest {
        val zone = TimeZone.currentSystemDefault()
        val clock = MovableClock(LocalDateTime(2026, 8, 12, 21, 17).toInstant(zone))
        val repo = repository(this, clock)

        assertEquals(LocalDate(2026, 8, 12), repo.today, "the evening the process started")

        // The same process, twelve hours later. No restart, no reconstruction — exactly what a
        // phone left overnight does.
        clock.instant = LocalDateTime(2026, 8, 13, 10, 39).toInstant(zone)
        assertEquals(
            LocalDate(2026, 8, 13),
            repo.today,
            "a repository that outlives midnight must not keep serving yesterday's date",
        )
    }

    /** The same in-process `:health-service` stack `HealthImportTest` stands up. */
    private fun repository(scope: CoroutineScope, clock: Clock): FitProjectionRepository {
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
        return FitProjectionRepository(client, proxy.tContext, clock = clock, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
    }
}
