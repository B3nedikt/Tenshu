package app.tenshu.drive.integration.sync

import org.tenshu.sync.proxy.NoOpPlatformScheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.stopKoin
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.MockSyncRelay
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.sync.proxy.initSyncService
import org.tenshu.sync.proxy.syncProxyImplementationFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiProxySyncIntegrationTest {

    @Before
    fun setup() {
        stopKoin()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun testTwoProxiesSynchronizeFileMetadata() = runTest {
        val serverRegistry = createSyntheticTestRegistry().apply {
            registerConfig(syncServiceCommonModule)
        }
        val serverContext = createTestContext(serverRegistry)
        val relay = MockSyncRelay(serverContext)

        // Each device gets its own child scope of `backgroundScope`, and BOTH the proxy's sessions
        // and the loops `initSyncService` starts ride it. Passing `backgroundScope` itself (as this
        // test did) only gets the loops *cancelled* when `runTest` ends — a cancel is not a join,
        // and `TestProxy.shutdown()` cannot reach them either: `initSyncService(coroutineScope = …)`
        // puts the scheduler, the drain loop and the health collectors on THAT scope, not on the
        // session scope `shutdownAndJoin` quiesces. So the `finally` below quiesces the sessions,
        // then cancels-and-joins each device scope while the test scheduler is still live — nothing
        // of either device is still unwinding when this `runTest` returns and the next one arms the
        // global exception collector.
        val proxies = mutableListOf<TestProxy>()
        val devices = mutableListOf<CoroutineScope>()
        fun deviceScope(): CoroutineScope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job),
        ).also { devices += it }
        try {

            // 1. Setup Device 1
            val registry1 = createSyntheticTestRegistry()
            registry1.registerConfig(syncServiceCommonModule)

            lateinit var syncManager1: SyncManager
            lateinit var crypto1: SyncCryptoManager

            val device1 = deviceScope()
            val proxy1 = TestProxy(
                schemaRegistry = registry1,
                additionalComponents = mapOf(SyncRelay.ID to relay),
                coroutineScope = device1
            ) { id, _ ->
                syncProxyImplementationFactory(id, relay, syncManager1, crypto1)
            }.also { proxies += it }

            crypto1 = proxy1.tContext.syncCryptoManager()
            syncManager1 = SyncManagerImpl(
                cryptoManager = crypto1,
                storageDelegate = InMemorySecureStorageDelegate(),
                context = proxy1.tContext
            )
            syncManager1.initialize("phrase-1")

            proxy1.executionContext.initSyncService(
                syncManager = syncManager1,
                cryptoManager = crypto1,
                coroutineScope = device1,
                pollingIntervalMs = 100,
                platformScheduler = NoOpPlatformScheduler,
            )

            // 2. Setup Device 2
            val registry2 = createSyntheticTestRegistry()
            registry2.registerConfig(syncServiceCommonModule)

            lateinit var syncManager2: SyncManager
            lateinit var crypto2: SyncCryptoManager

            val device2 = deviceScope()
            val proxy2 = TestProxy(
                schemaRegistry = registry2,
                additionalComponents = mapOf(SyncRelay.ID to relay),
                coroutineScope = device2
            ) { id, _ ->
                syncProxyImplementationFactory(id, relay, syncManager2, crypto2)
            }.also { proxies += it }

            crypto2 = proxy2.tContext.syncCryptoManager()
            syncManager2 = SyncManagerImpl(
                cryptoManager = crypto2,
                storageDelegate = InMemorySecureStorageDelegate(),
                context = proxy2.tContext
            )
            syncManager2.initialize("phrase-1") // SAME PHRASE

            proxy2.executionContext.initSyncService(
                syncManager = syncManager2,
                cryptoManager = crypto2,
                coroutineScope = device2,
                pollingIntervalMs = 100,
                platformScheduler = NoOpPlatformScheduler,
            )

            // 3. Device 1: Add some data
            val entity = Entity.create<SyntheticTestData>(
                context = proxy1.tContext,
                composite = SyntheticTestData.Factory.create(
                    context = proxy1.tContext,
                    name = "synced.txt",
                    value = 123L
                ),
                properties = emptyMap(),
            )
            proxy1.executionContext.saveData(entity)

            // 4. Wait for polling and verify Device 2 receives it
            var receivedData: SyntheticTestData? = null
            val dataId = proxy1.executionContext.getDataId(entity)!!

            repeat(100) {
                receivedData = proxy2.database.getData(dataId) as? SyntheticTestData
                if (receivedData != null) return@repeat
                delay(100)
            }

            assertTrue(receivedData != null, "Device 2 should have received the synthetic metadata")
            assertEquals(123L, receivedData.value.get())
        } finally {
            proxies.asReversed().forEach { it.shutdown() }
            devices.asReversed().forEach { it.coroutineContext.job.cancelAndJoin() }
        }
    }
}
