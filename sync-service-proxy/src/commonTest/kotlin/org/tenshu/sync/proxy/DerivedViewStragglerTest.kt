package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.sync.SyncService
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.client.syncHealth
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A derived view's straggler must blame itself, not the next test in the JVM.**
 *
 * ## The bug
 *
 * `docs/tech-debt.md` §"The full gate is flaky under parallel load" records the `:system-test-utils`
 * suite as failing with *"a different test fails each run"*. The victim is arbitrary because the
 * mechanism is a **cross-test replay**, not a defect in whatever went red:
 *
 * 1. [initSyncHealth] / [initContentTransferStatus] / [initContentUploadProgress] collect a
 *    [SyncHealthHolder] flow on a long-lived scope and write inside `database.transaction { … }`,
 *    which is a `withLock` — **synchronous and not cancellable**.
 * 2. `TestSystem.shutdown` cancels the client scope with a **2 s join cap**, so a straggler inside
 *    that lock can be abandoned alive.
 * 3. `koinApp.close()` then empties the Koin graph, and the straggler's next component lookup
 *    returns null into a non-null cast: `NullPointerException: null cannot be cast to non-null type
 *    …SchemaProvider`.
 * 4. Unhandled in a `StandaloneCoroutine`, that goes to kotlinx-coroutines-test's **global**
 *    `ExceptionCollector`, which replays it into the next `runTest` as
 *    `UncaughtExceptionsBeforeTest`.
 *
 * Observed 2026-09-02 with the harness's own cap-trip log one screen above the failure it caused.
 *
 * ## What this test forces, and why that is the honest assertion
 *
 * Step 3 is reproduced exactly rather than simulated: `createTestContext` backs `components` with a
 * live `MutableMap`, so removing [SchemaProvider] is precisely what `koinApp.close()` does to the
 * straggler — same lookup, same cast, same `NullPointerException`.
 *
 * ⚠⚠ The assertion is on the **handler**, not on the suite. "A later test stops failing" is
 * unobservable from inside one test and is the very non-determinism being removed; whether the
 * throw *escapes its coroutine* is deterministic, and escaping is the whole mechanism of the
 * hopping. So the scope carries a [CoroutineExceptionHandler] and the test asserts it never fires.
 *
 * ⚠ It also asserts the collector **survives** and the scope stays active. Absorbing the throw by
 * killing the collector would leave the view permanently stale for the session — a quieter bug
 * than the one being fixed, and one this assertion refuses.
 *
 * **Forced pair** (`guardDerivedViewTick` removed from the three collectors → restored):
 * deterministically red without it (`escaped` holds the `SchemaProvider` NPE), green with it.
 */
class DerivedViewStragglerTest {

    @Test
    fun aWriteThatOutlivesItsGraph_doesNotEscapeTheCollector() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(syncServiceClientModule)

        // Anything reaching this handler would, in the real suite, have gone to kotlinx-test's
        // global collector instead and been replayed into an unrelated `runTest`.
        var escaped: Throwable? = null
        val handler = CoroutineExceptionHandler { _, t -> escaped = t }
        val collectorScope = CoroutineScope(coroutineContext + Job() + handler)

        val holder = SyncHealthHolder()
        proxy.executionContext.initSyncHealth(collectorScope, holder)

        // Healthy first, so the collector is genuinely running and writing before the graph goes.
        val service = environmentFor(proxy).getService<SyncService>(SyncService.ID)
        val initial = service.syncHealth().filterNotNull().first()
        assertNotNull(initial, "the derived view is live before the graph is closed")

        // ⚠ THE FORCING, and it is the real fault rather than a stand-in: `createTestContext`
        // backs `components` with a `MutableMap`, so dropping SchemaProvider is exactly what
        // `koinApp.close()` leaves the straggler looking at.
        @Suppress("UNCHECKED_CAST")
        val components = proxy.tContext.components as MutableMap<String, TComponent>
        val removed = components.remove(SchemaProvider.COMPONENT_ID)
        assertNotNull(removed, "the forcing only means something if SchemaProvider was there")

        // Now tick the holder: the collector wakes, writes, and the write's `getDataId` resolves
        // SchemaProvider off a graph that no longer has one.
        holder.onTickComplete(online = false, pendingBlockCount = 7, lastSyncedAtMillis = 99L)
        repeat(50) { yield() }

        assertNull(
            escaped,
            "the upsert's throw must be absorbed by `guardDerivedViewTick`, not escape the " +
                "StandaloneCoroutine — escaping is what kotlinx-test replays into the next test." +
                "\nESCAPED FROM:\n" + escaped?.stackTraceToString()?.lines()?.take(12)?.joinToString("\n"),
        )
        assertTrue(
            collectorScope.isActive,
            "the collector must survive the dropped write; killing it would leave the derived " +
                "view permanently stale for the session",
        )

        // And it must still be collecting, not merely un-crashed: restore the component and prove
        // the next tick lands. This is what distinguishes "absorbed and continued" from
        // "absorbed and quietly dead".
        components[SchemaProvider.COMPONENT_ID] = removed
        holder.onTickComplete(online = true, pendingBlockCount = 0, lastSyncedAtMillis = 1234L)
        val recovered = service.syncHealth()
            .filterNotNull()
            .first { it.lastSyncedAtMillis.get() == 1234L }
        assertEquals(0L, recovered.pendingBlockCount.get(), "the view re-derives on the next tick")

        collectorScope.coroutineContext[Job]!!.cancel()
    }

    private fun environmentFor(proxy: TestProxy): TenshuEnvironment = TenshuEnvironment(
        taskExecutor = proxy.localTaskExecutor,
        schemaRegistry = proxy.schemaRegistry,
        tContext = proxy.tContext,
        requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
    )
}
