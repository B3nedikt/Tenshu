package app.tenshu.fit.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.orThrow
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.recordMovementTrace
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import org.tenshu.timeline.client.timelineService
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FA-1 (`docs/proposals/fit-automatic-activity-detection.md` §14.2) — the cross-service bridge.
 *
 * ⚠ The point of this test is the **wire registration**, not the mapping. A service Fit declares
 * in its Koin `coreSchemaConfigs` but whose client module is missing dies as an opaque
 * `error/internal` at the call site rather than at compile time, and a plain unit test over a
 * hand-built repository would never notice. So this stands up `:timeline-service` **and**
 * `:health-service` in one proxy, writes a trace through the real `recordMovementTrace` op, and
 * reads it back through [FitProjectionRepository] — the same path the app takes, minus IPC.
 */
class FitTimelineBridgeTest {

    @Test
    fun fitReadsAMovementTraceWrittenThroughTheTimelineService() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        client.timelineService.recordMovementTrace(
            trace(proxy, id = "cap-trace:1000", mode = "walk", distanceM = 2400L, startMs = 1000)
        ).orThrow("record trace")

        val repo = FitProjectionRepository(client = client, context = proxy.tContext, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        val movements = repo.detectedMovements().first()

        assertEquals(1, movements.size)
        val m = movements.single()
        assertEquals("cap-trace:1000", m.traceId)
        assertEquals("walk", m.mode)
        assertEquals(2400, m.distanceM)
        assertEquals(72, m.modeConfidence)
        assertEquals(4, m.classifierVersion)
        // Derived, not stored — the promotion (FA-2) reads durationSec off this.
        assertEquals(1800, m.durationSec)
    }

    /** FA-4: a counted total crosses the service boundary; Timeline's `-1` arrives as `null`, never `0`. */
    @Test
    fun stepsCrossTheBoundaryAndUncountedIsNull() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        client.timelineService.recordMovementTrace(
            trace(proxy, id = "cap-trace:1000", mode = "walk", distanceM = 2400L, startMs = 1000, steps = 3_120L)
        ).orThrow("record trace")
        client.timelineService.recordMovementTrace(
            trace(proxy, id = "cap-trace:9000", mode = "walk", distanceM = 2400L, startMs = 9000)
        ).orThrow("record trace")

        val repo = FitProjectionRepository(client = client, context = proxy.tContext, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        val byId = repo.detectedMovements().first().associateBy { it.traceId }

        assertEquals(3_120, byId.getValue("cap-trace:1000").steps)
        assertEquals(null, byId.getValue("cap-trace:9000").steps)
    }

    @Test
    fun tracesComeBackNewestFirst() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        listOf(1_000L, 9_000L, 5_000L).forEach { start ->
            client.timelineService.recordMovementTrace(
                trace(proxy, id = "cap-trace:$start", mode = "run", distanceM = 100L, startMs = start)
            ).orThrow("record trace")
        }

        val repo = FitProjectionRepository(client = client, context = proxy.tContext, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        val ids = repo.detectedMovements().first().map { it.traceId }

        assertEquals(listOf("cap-trace:9000", "cap-trace:5000", "cap-trace:1000"), ids)
    }

    /**
     * ⚠ Not a mapping assertion — a **projection** one. `polyline` is a plain eager field, so the
     * only thing keeping the blob off this read is its absence from `TRACE_FIELDS`. A future
     * hand adding it there would decode a path per candidate and nothing else would complain.
     */
    @Test
    fun theRouteBlobIsNotLoadedByTheCandidateRead() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val fatPolyline = ByteArray(64 * 1024) { (it % 251).toByte() }
        client.timelineService.recordMovementTrace(
            trace(proxy, id = "cap-trace:1000", mode = "cycle", distanceM = 9000L, startMs = 1000, polyline = fatPolyline)
        ).orThrow("record trace")

        val repo = FitProjectionRepository(client = client, context = proxy.tContext, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        val movements = repo.detectedMovements().first()

        // The summary is present…
        assertEquals("cycle", movements.single().mode)
        // …and the model has nowhere to put a route at all, by construction. If `polyline` ever
        // joins TRACE_FIELDS, this stays green — which is why the guard below reads the field list.
        assertTrue(
            MovementTrace.Fields.POLYLINE !in FitProjectionRepository.TRACE_FIELDS,
            "polyline must stay out of the candidate projection (fit-automatic-activity-detection.md §14.2)",
        )
    }

    private fun trace(
        proxy: TestProxy,
        id: String,
        mode: String,
        distanceM: Long,
        startMs: Long,
        polyline: ByteArray = ByteArray(0),
        steps: Long = MovementTrace.STEPS_NOT_COUNTED,
    ) = MovementTrace.create(
        context = proxy.tContext,
        traceId = id,
        mode = mode,
        classifierVersion = 4L,
        modeConfidence = 72L,
        sourceActivityType = "",
        medianSpeedMmPerS = 1_400L,
        distanceM = distanceM,
        startAt = UnixInstantDto(startMs),
        endAt = UnixInstantDto(startMs + 1_800_000L),
        pointCount = 12L,
        rawPointCount = 40L,
        minLatE7 = 0L, minLngE7 = 0L, maxLatE7 = 0L, maxLngE7 = 0L,
        startLatE7 = 0L, startLngE7 = 0L, endLatE7 = 0L, endLngE7 = 0L,
        polyline = polyline,
        steps = steps,
    )

    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
            registerConfig(timelineServiceCommonModule); registerConfig(timelineServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy ->
                timelineServiceProxyImplementationFactory(id)
                    ?: healthServiceProxyImplementationFactory(id, proxy.fileDatabase)
            },
        ).also {
            it.executionContext.initHealthService()
        }
    }

    private fun clientOf(proxy: TestProxy): TenshuClient = DefaultTenshuClient(
        TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
    )
}
