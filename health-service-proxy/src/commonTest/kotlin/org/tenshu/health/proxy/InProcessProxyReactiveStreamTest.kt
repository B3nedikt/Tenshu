package org.tenshu.health.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.InProcessProxy
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.fold
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordWorkout
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.health.client.healthService
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import kotlin.test.Test

/**
 * Regression guard for [InProcessProxy]'s live reactive delivery. [InProcessProxy] is the
 * production single-process client graph every in-process app boots (Fit, Timeline); its defining
 * job over the test-only `TestProxy` is wiring the session [org.tenshu.core.common.proxy.ClientConnection]'s
 * inbound stream elements to the interactor so a client `.flow()` receives **post-subscription**
 * mutations.
 *
 * The bug this pins: that wiring was missing, so a list stream's initial snapshot arrived (returned
 * synchronously by the stream-start op) but subsequent per-write array-change events were dropped —
 * "writes persist but never appear live" (Fit: a recorded run never showed until restart). A single
 * real service (`:health-service`) is the minimal streamable-relation fixture; the subject under
 * test is [InProcessProxy] itself, not the service.
 */
class InProcessProxyReactiveStreamTest {

    @Test
    fun clientFlowReceivesLiveUpdatesForWritesAfterSubscription() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val proxy = InProcessProxy(
                clientId = "test-client",
                serviceScope = backgroundScope,
                syncCryptoManager = BootstrapSyncCryptoManager(),
                operationImplementationFactory = OperationImplementationFactory { id ->
                    healthServiceProxyImplementationFactory(
                        id,
                        org.tenshu.core.common.proxy.InMemoryFileDatabase(),
                    ) ?: implementationFactory(id)
                },
                schemaRegistryFactory = { tContextProvider ->
                    createDefaultSchemaRegistry(
                        tContextProvider = tContextProvider,
                        extraConfigs = listOf(healthServiceCommonModule, healthServiceClientModule),
                    )
                },
                initialization = { initHealthService() },
            )
            val client = proxy.client

            // A live subscriber to the workouts relation — the shape every reactive screen uses.
            val ids = MutableStateFlow<Set<String>>(emptySet())
            backgroundScope.launch(Dispatchers.Default) {
                client.healthService.workouts.select { fields(Workout.Fields.WORKOUT_ID) }.flow()
                    .collect { result ->
                        ids.value = result.fold(
                            onSuccess = { list ->
                                list.toList().map { it.getFieldValue<TString>(Workout.Fields.WORKOUT_ID).get() }.toSet()
                            },
                            onFailure = { emptySet() },
                        )
                    }
            }

            // Write AFTER the subscription is live — the on-device record path.
            client.healthService.recordWorkout(
                Workout.create(
                    context = proxy.tContext,
                    workoutId = "workout-live-1",
                    activityType = "Running",
                    source = "Recorded",
                    start = UnixInstantDto(1_700_000_000_000L),
                    end = UnixInstantDto(1_700_000_600_000L),
                    durationSec = 600,
                    title = "Live run",
                    distanceM = 2_000,
                    activeEnergyKj = 500,
                    avgHr = 150,
                ),
            )

            // The already-subscribed flow must surface the freshly written workout.
            withContext(Dispatchers.Default) {
                withTimeout(15_000) {
                    while ("workout-live-1" !in ids.value) delay(50)
                }
            }
        }
}
