package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordWorkout
import org.tenshu.core.schema.Type
import org.tenshu.health.client.healthService
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for Fit's empty History / Home-workout-teaser bug: the reactive workouts read
 * `workouts.sortByDescending { start }.take(n).select { … }` must round-trip over the EPC wire.
 *
 * The original `sortByDescending(Workout.Start)` (property overload) shipped a raw
 * `StaticPropertyRelation` whose reference is the START **schema field** id; the wire
 * reference-weight system decodes a field id as a `FieldReference` (weight 3 > property's 2), so
 * the `StaticPropertyRelation` couldn't be reconstructed and the whole callstack died with
 * `tenshu/core/error/internal` on the proxy. The fix uses the lambda overload
 * (`sortByDescending { start }`) which ships a plain field read that round-trips — the same shape
 * as `notifications.sortByDescending { timestamp }`.
 *
 * Drives the chain through **genuine serialization** (client RO → BinarySerializer →
 * ProxyGateway parse → execute → serialized TResult), which the in-process pipeline test
 * (`FitPipelineIntegrationTest.reactiveWorkoutsFlowReflectsAddWorkout`) cannot — the local
 * connection passes live objects and skips the parser.
 */
class WorkoutSortWireTest {

    @Test
    fun workoutsSortByStartRoundTripsAndOrdersNewestFirst() = runTest {
        val testProxy = createHealthProxy(this)
        val client = serializingClientOf(testProxy)
        val context = testProxy.executionContext.context

        // Three workouts, out of order by start time.
        fun record(id: String, startMs: Long) = Workout.create(
            context = context,
            workoutId = id,
            activityType = "Running",
            source = "HealthKit",
            start = UnixInstantDto(startMs),
            end = UnixInstantDto(startMs + 3_600_000L),
            durationSec = 3_600,
        )
        client.healthService.recordWorkout(record("old", 1_000_000L))
        client.healthService.recordWorkout(record("new", 3_000_000L))
        client.healthService.recordWorkout(record("mid", 2_000_000L))

        // The exact reactive-read chain (minus the stream terminal): sort by start desc, window,
        // project — over the wire. Before the fix this threw `tenshu/core/error/internal`.
        val result = client.healthService.workouts
            .sortByDescending { start }
            .take(2)
            .select { fields(Workout.Fields.WORKOUT_ID, Workout.Fields.START) }
            .getAll()

        assertTrue(result is TResult.Success, "sort chain must round-trip over the wire, got: $result")
        @Suppress("UNCHECKED_CAST")
        val rows = (result as TResult.Success<TList<Entity<out Workout>>>).value!!.toList()
        val ids = rows.map { it.getFieldValue<org.tenshu.core.TString>(Workout.Fields.WORKOUT_ID).get() }
        assertEquals(listOf("new", "mid"), ids, "newest-first, windowed to 2 — sort round-tripped over the wire")
    }

    /**
     * A real client whose executor frames each task exactly like `RemoteBinaryTaskExecutor`
     * (serialize the callstack → proxy gateway parses + executes → parse the `TResult`) — the
     * genuine wire round-trip minus the transport. (Mirrors `RecordImportedHealthUnitTest`.)
     */
    private fun serializingClientOf(testProxy: TestProxy): DefaultTenshuClient {
        val wireExecutor = object : AsyncTaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> = error("wire test executes async only")

            override suspend fun executeAsync(task: TList<Data>): TResult<Data> {
                val requestBytes = testProxy.serializer.serialize(
                    task,
                    isTopLevel = true,
                    expectedType = Type.create(baseSchema = TList.Schema),
                )
                val responseBytes = testProxy.handleRequest(requestBytes)
                return testProxy.parser.parse(
                    data = responseBytes,
                    expectedType = Type.create(baseSchema = TResult.Schema),
                )
            }
        }
        val env = TenshuEnvironment(
            taskExecutor = wireExecutor,
            schemaRegistry = testProxy.schemaRegistry,
            tContext = testProxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(wireExecutor, ctx) },
        )
        return DefaultTenshuClient(env)
    }
}
