package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `control-background-work-off-fgs.md` §5.7, the background hand-off. An automatic backup uploads each
 * file inside the client's own call, and the push job's drain answers that entry "someone already
 * emitting". A job that counted only what the drain sent read one long video as two minutes of nothing
 * sent, ended `STALLED` and parked — and with it went Control's network, the moment the client's own
 * job ended mid-upload. Chunks that land on ANY path are the push making progress.
 */
class SyncPushInlineProgressTest {

    @Test
    fun chunksThatLandedOnAClientsCall_countAsSent() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(cryptoManager = crypto, storageDelegate = InMemorySecureStorageDelegate(), context = proxy.tContext)
        var chunks = 0L
        var emits = 0L
        val scheduler = SyncScheduler(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            syncProcessor = SyncProcessor(executionContext = proxy.executionContext, syncManager = syncManager, cryptoManager = crypto, wireIdResolver = WireIdResolver(syncManager)),
            cryptoManager = crypto,
            coroutineScope = this,
            contentChunksLandedTotal = { chunks },
            contentEmitsLandedTotal = { emits },
        )
        syncManager.initialize("mock phrase")

        assertEquals(false, scheduler.drainNowStalled().sent, "nothing queued and nothing uploaded")
        chunks += 3                                   // an inline upload's chunks land between drains
        val chunksOnly = scheduler.drainNowStalled()
        assertEquals(true, chunksOnly.sent, "the plane moved, whichever path moved it")
        // Chunks are not a completion — a file changing under every attempt re-sends its chunks for
        // ever, and counting them would chain more runs.
        assertEquals(false, chunksOnly.completed, "chunks alone finished nothing")
        assertEquals(false, scheduler.drainNowStalled().sent, "and only once: the next drain saw nothing new")
        assertEquals(0L, scheduler.contentProgressTotal(), "chunks never un-park a stalled push")
        chunks += 1
        emits += 1                                    // the file's last chunk and its manifest landed
        val finished = scheduler.drainNowStalled()
        assertEquals(true, finished.completed, "a whole file landed")
        assertEquals(1L, scheduler.contentProgressTotal(), "a completed file is what un-parks a stalled push (§5.7)")
    }

    @Test
    fun anInlineUploadMovesTheObserversChunkCount() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(context = proxy.tContext, cryptoManager = crypto, storageDelegate = InMemorySecureStorageDelegate())
        syncManager.initialize("mock phrase")
        val files = InMemoryFileDatabase()
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            contentBlobStore = InMemoryContentBlobStore(proxy.tContext),
            fileDatabase = files,
            contentGate = SyncGate { true },
        )
        files.writeRawByteArray("/v.bin", ByteArray(64) { it.toByte() })

        val emitted = observer.emitFileContent(
            targetEntityId = DataId.StringDataId.create(context = proxy.tContext, type = SyntheticTestData.ID, keyValue = "/v.bin"),
            propertyId = "${SyntheticTestData.ID}/data",
            filePath = "/v.bin",
        )

        assertTrue(emitted, "precondition: the inline upload landed")
        assertTrue(observer.contentChunksLandedTotal > 0, "its chunks are what the push job reads as progress")
        assertEquals(1L, observer.contentEmitsLandedTotal, "and the completed file is what ends a park")
    }

    /**
     * While the content plane waits the drain tries no entry, so counting them as pushable would run a
     * push job ~2 minutes for nothing; and the banner must say why the files sit.
     */
    @Test
    fun aWaitingPlane_takesContentOutOfThePushableBacklog_andIsReportedAsRetrying() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(cryptoManager = crypto, storageDelegate = InMemorySecureStorageDelegate(), context = proxy.tContext)
        var waiting = false
        var retries = 0
        val health = SyncHealthHolder()
        val scheduler = SyncScheduler(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            syncProcessor = SyncProcessor(executionContext = proxy.executionContext, syncManager = syncManager, cryptoManager = crypto, wireIdResolver = WireIdResolver(syncManager)),
            cryptoManager = crypto,
            coroutineScope = this,
            pendingContentEmitCount = { DeferredContentFigures(count = 3, bytes = 300) },
            contentPlaneWaiting = { waiting },
            retryContentPlane = { retries++ },
            syncHealthHolder = health,
        )
        syncManager.initialize("mock phrase")

        assertEquals(300L, scheduler.drainablePendingContentBytes(), "precondition: the plane answers, the files are pushable")
        scheduler.drainNow()
        assertEquals(false, health.current().contentRetrying)

        waiting = true
        assertEquals(0L, scheduler.drainablePendingContentBytes(), "nothing the drain would try is pushable")
        scheduler.drainNow()
        assertEquals(true, health.current().contentRetrying, "the banner is told why the files are not moving")

        scheduler.notifyForegrounded()
        scheduler.retryContentPlaneNow()
        assertEquals(2, retries, "the app in front and a user-started upload half-open the plane")
        scheduler.stop()
    }
}
