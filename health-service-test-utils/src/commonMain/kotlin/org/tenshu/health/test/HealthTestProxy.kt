package org.tenshu.health.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import org.tenshu.sync.common.TombstoneEmitter

/**
 * [tombstoneEmitter] defaults to `null` — this harness has no sync, so a delete is
 * device-local and nothing observes the difference. Pass a capturing double when the test
 * is *about* the tombstone (`DeleteWorkout` emits one, and a delete op that silently does
 * not is the `DeleteRoutine` bug class).
 */
fun createHealthProxy(
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    tombstoneEmitter: TombstoneEmitter? = null,
) =
    TestProxy(
        schemaRegistry = createHealthTestRegistry(),
        coroutineScope = coroutineScope,
        implementationFactoryOverride = { id, proxy ->
            healthServiceProxyImplementationFactory(
                id,
                proxy.fileDatabase,
                tombstoneEmitter = tombstoneEmitter,
            )
        }
    ).also {
        // Mirror production wiring: `initHealthService` registers the DataObserver that
        // maintains `HealthService.Relations.WORKOUTS` on every saved `Workout`. Without it,
        // `RecordWorkout`'s `saveData` lands the entity in the DB but never in the relation,
        // so the workouts stream returns nothing.
        it.executionContext.initHealthService()
    }
