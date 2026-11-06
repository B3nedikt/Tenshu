package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.health.SleepSession
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordImportedHealth
import org.tenshu.core.schema.Type
import org.tenshu.health.client.healthService
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole-batch op round-trips **through the wire**: the client RO builds the real callstack,
 * which is serialized and parsed exactly the way `RemoteBinaryTaskExecutor` frames an EPC/Binder
 * request — so the five `TList` params cross genuine serialization, which the in-process pipeline
 * test cannot cover (fit-app.md §9.16).
 */
class RecordImportedHealthUnitTest {

    @Test
    fun batchedImportRoundTripsAllFiveKindsOverTheWire() = runTest {
        val testProxy = createHealthProxy(this)
        val client = serializingClientOf(testProxy)
        val context = testProxy.executionContext.context

        val result = client.healthService.recordImportedHealth(
            workouts = listOf(
                Workout.create(
                    context = context,
                    workoutId = "workout-1",
                    activityType = "Running",
                    source = "HealthKit",
                    start = UnixInstantDto(1_700_000_000_000L),
                    end = UnixInstantDto(1_700_003_600_000L),
                    durationSec = 3_600,
                    title = "Morning run",
                    distanceM = 10_000,
                    activeEnergyKj = 2_500,
                    avgHr = 148,
                ),
                Workout.create(
                    context = context,
                    workoutId = "workout-2",
                    activityType = "Walking",
                    source = "HealthKit",
                    start = UnixInstantDto(1_700_090_000_000L),
                    end = UnixInstantDto(1_700_092_400_000L),
                    durationSec = 2_400,
                    title = "Evening walk",
                    distanceM = 3_000,
                    activeEnergyKj = 600,
                    avgHr = 96,
                ),
            ),
            dailyActivities = listOf(
                DailyActivity.create(
                    context = context,
                    date = "2026-05-24",
                    steps = 6_120,
                    distanceM = 4_500,
                    activeEnergyKj = 1_940,
                    activeMinutes = 42,
                ),
            ),
            sleepSessions = listOf(
                SleepSession.create(
                    context = context,
                    date = "2026-05-24",
                    start = UnixInstantDto(1_700_000_000_000L),
                    end = UnixInstantDto(1_700_025_920_000L),
                    durationSec = 25_920,
                    source = "HealthKit",
                    awakeMin = 14,
                    remMin = 96,
                    lightMin = 232,
                    deepMin = 90,
                ),
            ),
            healthSamples = listOf(
                HealthSample.create(
                    context = context,
                    metric = "Weight",
                    value = "74.2",
                    unit = "kg",
                    time = UnixInstantDto(1_700_000_000_000L),
                    source = "HealthKit",
                    resting = false,
                ),
            ),
            medicalRecords = listOf(
                MedicalRecord.create(
                    context = context,
                    recordId = "fhir-med-1",
                    kind = "Medication",
                    title = "Cetirizine",
                    detail = "10 mg tablet · once daily",
                    effectiveDate = "2026-03-02",
                    issuer = "Dr. A. Neumann · Charité",
                    source = "HealthKit",
                ),
            ),
        )
        assertTrue(result is TResult.Success, "batched op failed: $result")

        // Every kind landed through its relation maintainer.
        assertEquals(
            setOf("workout-1", "workout-2"),
            streamKeys(testProxy, 1L, HealthService.Relations.WORKOUTS) { (it as Workout).workoutId.get() },
        )
        assertEquals(
            setOf("2026-05-24"),
            streamKeys(testProxy, 2L, HealthService.Relations.DAILY_ACTIVITIES) { (it as DailyActivity).date.get() },
        )
        assertEquals(
            setOf("2026-05-24"),
            streamKeys(testProxy, 3L, HealthService.Relations.SLEEP_SESSIONS) { (it as SleepSession).date.get() },
        )
        assertEquals(
            setOf("Weight"),
            streamKeys(testProxy, 4L, HealthService.Relations.HEALTH_SAMPLES) { (it as HealthSample).metric.get() },
        )
        assertEquals(
            setOf("fhir-med-1"),
            streamKeys(testProxy, 5L, HealthService.Relations.MEDICAL_RECORDS) { (it as MedicalRecord).recordId.get() },
        )
    }

    @Test
    fun emptyListsAreValidOverTheWire() = runTest {
        val testProxy = createHealthProxy(this)
        val client = serializingClientOf(testProxy)
        val context = testProxy.executionContext.context

        // Only one kind populated — the other four ride as (required) empty lists.
        val result = client.healthService.recordImportedHealth(
            dailyActivities = listOf(
                DailyActivity.create(
                    context = context,
                    date = "2026-05-25",
                    steps = 900,
                    distanceM = 700,
                    activeEnergyKj = 210,
                    activeMinutes = 8,
                ),
            ),
        )
        assertTrue(result is TResult.Success, "batched op failed: $result")

        assertEquals(
            setOf("2026-05-25"),
            streamKeys(testProxy, 1L, HealthService.Relations.DAILY_ACTIVITIES) { (it as DailyActivity).date.get() },
        )
    }

    /**
     * A real client whose executor frames each task exactly like `RemoteBinaryTaskExecutor`
     * (serialize the callstack `TList` → proxy gateway parses + executes → parse the `TResult`
     * response) — the genuine wire round-trip, minus only the transport.
     */
    /**
     * The bulk path is the one the importers actually use, so it needs the fold too: a Takeout
     * batch followed by a steps-only Health Connect batch for the same day must not lose the
     * day's distance / energy / active minutes (see [DailyActivityFold]).
     */
    @Test
    fun aStepsOnlyBatchDoesNotClobberAnEarlierRichBatch() = runTest {
        val testProxy = createHealthProxy(this)
        val client = serializingClientOf(testProxy)
        val context = testProxy.executionContext.context

        client.healthService.recordImportedHealth(
            dailyActivities = listOf(
                DailyActivity.create(
                    context = context, date = "2026-07-11",
                    steps = 8_000, distanceM = 6_000, activeEnergyKj = 1_200, activeMinutes = 45,
                ),
            ),
        )
        client.healthService.recordImportedHealth(
            dailyActivities = listOf(
                DailyActivity.create(context = context, date = "2026-07-11", steps = 7_500),
            ),
        )

        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(HealthService.Relations.DAILY_ACTIVITIES),
                *idToUuid(Stream.ID),
                *1L.toByteArray(),
            ),
        )
        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == 1L }
        assertTrue(valueUpdates.isNotEmpty(), "no dailyActivities emission")

        @Suppress("UNCHECKED_CAST")
        val days = (valueUpdates.last().payload as TList<DailyActivity>).toList()
        assertEquals(1, days.size)
        assertEquals(7_500L, days.first().steps.get())
        assertEquals(6_000L, days.first().distanceM.get())
        assertEquals(1_200L, days.first().activeEnergyKj.get())
        assertEquals(45L, days.first().activeMinutes.get())
    }

    private fun serializingClientOf(testProxy: TestProxy): DefaultTenshuClient {
        val wireExecutor = object : AsyncTaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> =
                error("wire test executes async only")

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

    private suspend fun streamKeys(
        testProxy: TestProxy,
        flowId: Long,
        relationId: String,
        key: (Any?) -> String,
    ): Set<String> {
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(relationId),
                *idToUuid(Stream.ID),
                *flowId.toByteArray(),
            ),
        )
        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty(), "no stream value for $relationId")
        return (valueUpdates.first().payload as TList<*>).toList().map(key).toSet()
    }
}
