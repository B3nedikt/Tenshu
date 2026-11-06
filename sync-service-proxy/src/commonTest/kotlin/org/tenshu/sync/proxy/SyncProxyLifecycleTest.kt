package org.tenshu.sync.proxy

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Proxy Background Lifecycle" tech-debt — Phase 1 ownership invariant: the
 * long-lived loops [initSyncService] starts ([SyncScheduler], [ScheduleDrainLoop])
 * must launch on the receiver's [org.tenshu.core.common.proxy.ExecutionContext.sessionScope],
 * not a detached `CoroutineScope(Dispatchers.Default)`. That way the
 * `sessionScope.cancel()` `ClientRegistry` already issues on teardown actually
 * cancels them, instead of leaving them ticking against a half-disposed context.
 *
 * Before the fix the loops launched on a detached scope, so they were never
 * children of `sessionScope` — the `children.count()` assertion below would fail.
 */
class SyncProxyLifecycleTest {

    @Test
    fun backgroundLoops_launchOnSessionScope_andAreCancelledOnTeardown() = runTest {
        // `coroutineScope = this` routes the session through the TestScope's
        // backgroundScope, so the never-completing loop coroutines don't trip
        // runTest's UncompletedCoroutinesError. We never advance virtual time,
        // so the loop bodies stay parked — `start()` only registers them as
        // children, which is all this test inspects.
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        syncManager.initialize("mock phrase")

        val executionContext = proxy.executionContext
        val sessionJob = executionContext.sessionScope.coroutineContext[Job]!!
        val childrenBefore = sessionJob.children.count()

        // No `coroutineScope` arg → defaults to the receiver's sessionScope.
        executionContext.initSyncService(syncManager, crypto, platformScheduler = NoOpPlatformScheduler)

        assertTrue(
            sessionJob.children.count() > childrenBefore,
            "initSyncService loops must launch on the session scope, not a detached one"
        )

        proxy.clientRegistry.shutdown()

        assertTrue(
            sessionJob.isCancelled,
            "session teardown must cancel the scope the loops run on"
        )
    }
}
