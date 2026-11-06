package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * One sync runtime per process, N observers (`drive-upload-progress.md` P3;
 * `docs/tech-debt.md` *"Every client session runs its own SyncScheduler"*).
 *
 * `ClientRegistry` runs the platform initialisation once per **connected app**, so before this
 * every app stood up its own `SyncScheduler` and `SyncHealthHolder`. They all published into the
 * same single-row status relations — last-writer-wins, with a freshly built holder emitting
 * `SyncHealthFigures()` defaults before its first tick — and, since the plane split, all drained
 * the same `PENDING_CONTENT_EMITS` journal, so one deferred file could upload several times.
 *
 * The observers are the one part that must stay per-session: `ExecutionContext.observerRegistry`
 * is a per-context default, so an observer only sees saves made through its own context.
 */
class SyncRuntimeElectionTest {

    private lateinit var proxy: TestProxy

    /**
     * ⚠ A `CoroutineScope` extension rather than a field initialiser, so the [TestProxy] rides the
     * caller's `TestScope`. [initSession] runs `initSyncService`, which starts a `SyncScheduler` and
     * a 1-second `ScheduleDrainLoop` on the session scope — and a field initialiser has no scope to
     * tie them to, so they outlived the class and threw into whatever `runTest` ran next
     * (`docs/tech-debt.md` fix-item 5).
     */
    private fun CoroutineScope.setup() {
        proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this,
        ).also { it.schemaRegistry.registerConfig(syncServiceCommonModule) }
    }

    /**
     * `runTest`, with [setup] already run. Every test in this class needs the fixture, so the
     * call belongs here once rather than as the first line of each body.
     *
     * ⚠⚠ It cannot be an `@BeforeTest`, and that is the whole point: [setup] takes a
     * `CoroutineScope` receiver so the [TestProxy] rides the **`TestScope`**, and `@BeforeTest`
     * runs outside `runTest` where no such scope exists. Nor can the teardown move to
     * `@AfterTest` — this is `commonTest`, where `@AfterTest` returns `Unit` and cannot suspend,
     * and `runBlocking` does not exist. Letting `runTest` own the proxy is what cancels the
     * `SyncScheduler` and 1-second `ScheduleDrainLoop` that `initSyncService` starts
     * (`docs/tech-debt.md` fix-item 5). Same shape as `:maps-shared-logic`'s `vmTest`.
     */
    private fun syncTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        setup()
        body()
    }

    private val holder = SyncRuntimeHolder()
    private val built = mutableListOf<SyncProxyComponents>()

    private fun initSession(): SyncProxyComponents {
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("mock phrase")
        return proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            runtimeHolder = holder,
            platformScheduler = NoOpPlatformScheduler,
        ).also { built += it }
    }

    @AfterTest
    fun tearDown() {
        built.forEach {
            it.scheduler.stop()
            it.scheduleDrainLoop.stop()
        }
    }

    @Test
    fun theSecondSessionReusesTheOneScheduler() = syncTest {
        val first = initSession()
        val second = initSession()

        assertSame(
            first.scheduler,
            second.scheduler,
            "a second client session must reuse the process's scheduler, not start a rival one",
        )
    }

    @Test
    fun theSecondSessionReusesTheOneHealthHolder() = syncTest {
        val first = initSession()
        val second = initSession()

        // The specific mechanism behind the flickering banner: two holders publishing into one
        // single-row relation, the newer one emitting defaults before its first tick.
        assertSame(
            first.syncHealthHolder,
            second.syncHealthHolder,
            "one writer per single-row status relation",
        )
    }

    @Test
    fun theSecondSessionReusesTheSharedRegistries() = syncTest {
        val first = initSession()
        val second = initSession()

        assertSame(first.realmRegistry, second.realmRegistry, "one realm registry")
        assertSame(first.tombstoneRegistry, second.tombstoneRegistry, "one tombstone registry")
        assertSame(first.tombstoneCascades, second.tombstoneCascades, "one cascade registry the shared drain reads (§9.14)")
        assertSame(first.scheduleDrainLoop, second.scheduleDrainLoop, "one schedule drain loop")
    }

    /**
     * The counterpart claim, and the reason this is a split rather than a hoist: each session still
     * gets its **own** observer. A shared one would only ever see saves made through whichever
     * context happened to build it, silently dropping every other app's writes.
     */
    @Test
    fun eachSessionStillGetsItsOwnObserver() = syncTest {
        val first = initSession()
        val second = initSession()

        assertNotSame(
            first.syncObserver,
            second.syncObserver,
            "an observer only sees its own context's saves — it cannot be shared",
        )
    }

    @Test
    fun withoutAHolderEverySessionBuildsItsOwnRuntime() = syncTest {
        // The legacy/test path (no holder supplied) is unchanged: tests and single-session wirings
        // that call this directly still get a complete runtime each time.
        val crypto = proxy.tContext.syncCryptoManager()
        fun standalone(): SyncProxyComponents {
            val syncManager = SyncManagerImpl(
                cryptoManager = crypto,
                storageDelegate = InMemorySecureStorageDelegate(),
                context = proxy.tContext,
            )
            syncManager.initialize("mock phrase")
            return proxy.executionContext.initSyncService(syncManager, crypto, platformScheduler = NoOpPlatformScheduler).also { built += it }
        }

        assertNotSame(
            standalone().scheduler,
            standalone().scheduler,
            "no holder ⇒ previous behaviour, so existing callers and tests are unaffected",
        )
    }

    @Test
    fun theRuntimeIsPublishedOnlyAfterItIsFullyBuilt() = syncTest {
        assertTrue(holder.runtime == null, "nothing published before the first session")
        val first = initSession()
        assertSame(
            first.scheduler,
            holder.runtime?.components?.scheduler,
            "the published runtime is the one the first session returned",
        )
    }
}
