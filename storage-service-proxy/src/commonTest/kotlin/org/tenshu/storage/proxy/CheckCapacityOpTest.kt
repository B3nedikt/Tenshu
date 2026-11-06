package org.tenshu.storage.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.storage.CapacityResult
import org.tenshu.storage.StorageService
import org.tenshu.storage.checkCapacity
import org.tenshu.storage.client.status
import org.tenshu.storage.test.FakeStorageProvider
import org.tenshu.storage.test.createStorageProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end (SDK chain) Tier-3 [org.tenshu.storage.CheckCapacity] op: the
 * client call resolves the op, the proxy reads the provider-fed figures, and a
 * typed [CapacityResult] comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckCapacityOpTest {

    @Test
    fun checkCapacity_fitsAndReportsAvailable() = runTest {
        val provider = FakeStorageProvider(
            StorageFigures(hasStorage = true, usedBytes = 200L, limitBytes = 1_000L),
        )
        val service = serviceFor(createStorageProxy(provider, backgroundScope))
        // Wait until the provider has fed the capacity holder (the same collect
        // that upserts the status row sets the holder first).
        service.status().filterNotNull().first()

        val fitting = service.checkCapacity(500L).result()
        assertTrue(fitting.fits.get(), "200 + 500 <= 1000 fits")
        assertEquals(800L, fitting.availableBytes.get())

        val tooBig = service.checkCapacity(900L).result()
        assertFalse(tooBig.fits.get(), "200 + 900 > 1000 does not fit")
    }

    @Test
    fun checkCapacity_noStorage_doesNotFit() = runTest {
        val provider = FakeStorageProvider(
            StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
        )
        val service = serviceFor(createStorageProxy(provider, backgroundScope))
        service.status().filterNotNull().first()

        val result = service.checkCapacity(0L).result()
        assertFalse(result.fits.get(), "no storage ⇒ nothing fits")
        assertEquals(0L, result.availableBytes.get())
    }

    @Test
    fun checkCapacity_subtractsBytesAlreadyQueuedToUpload() = runTest {
        // ⚠⚠ ST-7. `usedBytes` is what the relay *holds*; a queue waiting to go up is invisible to
        // it. This is the FOLDER-upload pre-flight — the one surface whose whole job is answering
        // "will this job work" before a long run — so measuring against raw headroom is exactly how
        // a folder upload gets waved onto a realm that cannot hold the backlog in front of it.
        val holder = StorageCapacityHolder()
        val provider = FakeStorageProvider(
            StorageFigures(hasStorage = true, usedBytes = 200L, limitBytes = 1_000L),
        )
        val service = serviceFor(createStorageProxy(provider, backgroundScope, holder))
        service.status().filterNotNull().first()

        // 800 bytes of raw headroom, but 600 of them are already spoken for.
        holder.queuedContentBytes = 600L

        val result = service.checkCapacity(500L).result()
        assertFalse(
            result.fits.get(),
            "500 does not fit behind a 600-byte queue in 800 bytes of headroom",
        )
        assertEquals(
            200L,
            result.availableBytes.get(),
            "available must be net of the queue, or it offers room this call just refused",
        )

        // ⚠ The negative: a queue that still leaves room must not refuse.
        assertTrue(service.checkCapacity(200L).result().fits.get())
    }

    @Test
    fun checkCapacity_relayAlreadyRefused_reportsNoRoomWhateverTheFiguresSay() = runTest {
        // The relay is the authority (D11): a `507` means no, even when the arithmetic is happy.
        val holder = StorageCapacityHolder()
        val provider = FakeStorageProvider(
            StorageFigures(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L),
        )
        val service = serviceFor(createStorageProxy(provider, backgroundScope, holder))
        service.status().filterNotNull().first()

        holder.relayRejected = true

        val result = service.checkCapacity(100L).result()
        assertFalse(result.fits.get())
        assertEquals(0L, result.availableBytes.get())
    }

    private fun TResult<CapacityResult?>.result(): CapacityResult {
        if (this is TResult.Failure<*>) error("checkCapacity failed: $error")
        return (this as TResult.Success<*>).value as CapacityResult
    }

    private fun serviceFor(proxy: TestProxy): StorageService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val environment = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = proxy.tContext,
        )
        return environment.getService<StorageService>(StorageService.ID)
    }
}
