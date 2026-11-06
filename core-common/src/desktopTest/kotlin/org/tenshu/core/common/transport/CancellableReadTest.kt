package org.tenshu.core.common.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ReadCancelledException
import org.tenshu.core.common.request.CancellableReadTaskExecutor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ **A read whose caller is cancelled must stop consuming the proxy** (`core-background-
 * processing.md` §6.2.1; measured on a device as a cancelled Photos feed page answered 1.9–8.2 s
 * after its caller had gone, `photos-app.md` §37).
 *
 * Every assertion is on **proxy-side progress** — rows walked, steps run — never only on the
 * client's result: the client unwinding is not the defect, the proxy carrying on is. Each transport
 * is driven the way production drives it: the real `UnixSocketServer` + `UnixSocketTaskTransport`
 * on desktop, and [BinderShapedTransport] — a thread blocked in the call, like a Binder transaction —
 * for Android.
 */
class CancellableReadTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanup.reversed().forEach { runCatching(it) }
        scope.cancel()
    }

    /** How many rows a walk may still take after its cancel — one in progress plus scheduling. */
    private val slack = 10

    // ── desktop: the real socket server and transport ────────────────────────────────────────────

    private fun socketExecutor(rig: CancellableReadRig): RemoteBinaryTaskExecutor {
        val socketPath = Files.createTempDirectory("uds-read").resolve("t.sock").toAbsolutePath().toString()
        UnixSocketServer(rig.proxy.gateway, rig.proxy.serializer, socketPath).start()
        val parser = org.tenshu.core.common.parser.BinaryParser(rig.proxy.schemaRegistry, rig.proxy.schemaRegistry)
        val executor = rig.executor(UnixSocketTaskTransport(parser, socketPath), scope)
        executor.bind()
        cleanup += { executor.unbind(); Files.deleteIfExists(Path.of(socketPath)) }
        return executor
    }

    @Test
    fun socket_aCancelledReadStopsWalkingInTheProxy() = runBlocking {
        val rig = CancellableReadRig()
        val executor = socketExecutor(rig)
        assertCancelledReadStops(rig) { executor.executeAsyncCancellableRead(rig.walk(2_000)) }
    }

    @Test
    fun socket_aCancelledReadStopsBetweenChainSteps() = runBlocking {
        val rig = CancellableReadRig()
        val executor = socketExecutor(rig)
        // 200 × 20 ms = 4 s if nothing stops it.
        val caller = scope.launch { executor.executeAsyncCancellableRead(rig.steps(200)) }
        withTimeout(5_000) { rig.walking.await() }
        delay(100)
        caller.cancelAndJoin()
        val atCancel = rig.stepped.get()
        delay(500)
        val after = rig.stepped.get() - atCancel
        assertTrue(after <= 3, "the chain kept stepping after its read was cancelled: $after more steps")
    }

    /**
     * ⚠⚠ The contract the opt-in protects: a plain call is **not** stopped by its caller, on any
     * transport — a write launched from a screen that closes still lands. If this ever goes red,
     * plain calls became cancellable and every fire-and-forget save is at risk.
     */
    @Test
    fun socket_aPlainCallIsNotStoppedByItsCaller() = runBlocking {
        val rig = CancellableReadRig()
        val executor = socketExecutor(rig)
        val rows = 100
        val caller = scope.launch { executor.executeAsync(rig.walk(rows)) }
        withTimeout(5_000) { rig.walking.await() }
        caller.cancelAndJoin()
        withTimeout(5_000) { while (rig.walked.get() < rows) delay(20) }
        assertEquals(rows, rig.walked.get(), "a plain call stopped when its caller was cancelled")
    }

    @Test
    fun socket_cancellingOneReadLeavesAnotherOnTheSameConnectionRunning() = runBlocking {
        val rig = CancellableReadRig(rowMs = 2)
        val executor = socketExecutor(rig)
        val rows = 400 // ~0.8 s alone
        val survivor = scope.async { executor.executeAsyncCancellableRead(rig.walk(rows)) }
        val victim = scope.launch { executor.executeAsyncCancellableRead(rig.walk(rows)) }
        withTimeout(5_000) { rig.walking.await() }
        delay(100)
        victim.cancelAndJoin()
        val result = withTimeout(10_000) { survivor.await() }
        assertTrue(result is TResult.Success<*>, "the other read must be answered, got $result")
        assertTrue(
            rig.walked.get() >= rows,
            "the surviving read must have walked all $rows rows; total walked ${rig.walked.get()}",
        )
    }

    @Test
    fun helloRevisionIsReadConservatively() {
        assertEquals(2, StreamFramer.helloRevision(StreamFramer.helloBody(2)))
        assertEquals(1, StreamFramer.helloRevision(StreamFramer.helloBody(1)))
        // Anything that is not a well-formed hello must not be read as "can stop reads".
        assertEquals(1, StreamFramer.helloRevision(ByteArray(0)))
        assertEquals(1, StreamFramer.helloRevision("garbage".encodeToByteArray()))
    }

    // ── Android: Binder-shaped (a thread blocked in the call) ────────────────────────────────────

    @Test
    fun binder_aCancelledReadStopsWalkingInTheProxy() = runBlocking {
        val rig = CancellableReadRig()
        val transport = BinderShapedTransport(rig.proxy)
        val executor = rig.executor(transport, scope)
        assertCancelledReadStops(rig) { executor.executeAsyncCancellableRead(rig.walk(2_000)) }
        assertEquals(1, transport.cancelsSent.size, "exactly one oneway cancelRead for one abandoned read")
    }

    /**
     * ⚠⚠ The device defect, reproduced: over a blocking transport, a cancelled **plain** call walks
     * to the end. This is today's `call()` and stays so by design — it is the baseline the
     * cancellable read is measured against, and the contract writes rely on.
     */
    @Test
    fun binder_aPlainCallWalksToTheEndAfterItsCallerIsCancelled() = runBlocking {
        val rig = CancellableReadRig()
        val executor = rig.executor(BinderShapedTransport(rig.proxy), scope)
        val rows = 100
        val caller = scope.launch { executor.executeAsync(rig.walk(rows)) }
        withTimeout(5_000) { rig.walking.await() }
        caller.cancel()
        withTimeout(5_000) { while (rig.walked.get() < rows) delay(20) }
        assertEquals(rows, rig.walked.get())
    }

    // ── gateway: a cancel reaches exactly one read ───────────────────────────────────────────────

    /**
     * ⚠⚠ A cancel must never stop another caller's read. Three reads share the id `"1"`: the one
     * cancelled, one from another connection of the same client (two processes of one desktop app
     * each count their call ids from 1) and one from another client. Only the first may stop.
     */
    @Test
    fun gateway_aCancelReachesOnlyTheReadItNames() = runBlocking {
        val rig = CancellableReadRig(rowMs = 2)
        rig.proxy.admitSchemaSession("c1")
        rig.proxy.admitSchemaSession("c2")
        val rows = 300
        val connA = Any()
        val connB = Any()
        val walkBytes = rig.bytes(rig.walk(rows))

        val target = scope.async {
            runCatching { rig.proxy.gateway.call("c1", walkBytes, readId = "1", readOwner = connA) }
        }
        val sameClientOtherConnection = scope.async {
            rig.proxy.gateway.call("c1", walkBytes, readId = "1", readOwner = connB)
        }
        val otherClient = scope.async {
            rig.proxy.gateway.call("c2", walkBytes, readId = "1", readOwner = connA)
        }
        withTimeout(5_000) { rig.walking.await() }
        delay(50)
        rig.proxy.gateway.cancelRead("c1", connA, "1")

        val stopped = withTimeout(5_000) { target.await() }
        assertTrue(
            stopped.exceptionOrNull() is ReadCancelledException,
            "the named read must stop as cancelled-by-client, got $stopped",
        )
        withTimeout(10_000) { sameClientOtherConnection.await() }
        withTimeout(10_000) { otherClient.await() }
        assertTrue(rig.walked.get() >= 2 * rows, "two un-cancelled reads walked ${rig.walked.get()} rows")
        assertEquals(0, rig.proxy.gateway.inFlightCount.value, "a read is never a task")
        assertEquals(0, rig.proxy.gateway.inFlightReadCount(), "every read deregistered")
    }

    @Test
    fun gateway_aCancelThatOvertakesItsReadStillStopsIt() = runBlocking {
        val rig = CancellableReadRig()
        rig.proxy.admitSchemaSession("c1")
        rig.proxy.gateway.cancelRead("c1", null, "early")
        assertFailsWith<ReadCancelledException> {
            withTimeout(5_000) { rig.proxy.gateway.call("c1", rig.bytes(rig.walk(100)), readId = "early", readOwner = null) }
        }
        assertEquals(0, rig.walked.get(), "a read cancelled before it registered must not walk")
    }

    /**
     * ⚠ A teardown is not a client cancel: the Binder endpoint answers an empty array only to a read
     * its client stopped — an empty reply to a caller still waiting would parse as unreadable and
     * be reported as version skew.
     */
    @Test
    fun gateway_onlyAClientCancelIsAnsweredWithNothing() = runBlocking {
        val rig = CancellableReadRig()
        rig.proxy.admitSchemaSession("c1")
        val reply = scope.async {
            rig.proxy.gateway.callCancellableRead("c1", rig.bytes(rig.walk(2_000)), null, "r")
        }
        withTimeout(5_000) { rig.walking.await() }
        rig.proxy.gateway.cancelRead("c1", null, "r")
        assertEquals(0, withTimeout(5_000) { reply.await() }.size)
    }

    // ── in-process ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun inProcess_aCancelledReadStopsWalking() = runBlocking {
        val rig = CancellableReadRig()
        val executor = rig.proxy.localTaskExecutor as CancellableReadTaskExecutor
        assertCancelledReadStops(rig) {
            // In-process the walk runs on the caller's own coroutine, so put it on a thread of its own.
            withContext(Dispatchers.IO) { executor.executeAsyncCancellableRead(rig.walk(2_000)) }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun assertCancelledReadStops(
        rig: CancellableReadRig,
        read: suspend () -> TResult<Data>,
    ) {
        val caller = scope.launch {
            try {
                read()
            } catch (_: CancellationException) {
            }
        }
        withTimeout(5_000) { rig.walking.await() }
        delay(100) // well into the walk: 2 000 rows × 5 ms = 10 s if nothing stops it
        val settled = withTimeoutOrNull(3_000) { caller.cancelAndJoin() }
        assertNotNull(settled, "the caller could not unwind from its own cancellation")
        val atCancel = rig.walked.get()
        delay(500) // ~100 more rows if the proxy is still walking
        val after = rig.walked.get() - atCancel
        assertTrue(
            after <= slack,
            "the proxy kept walking after the read was cancelled: $after more rows in 500 ms " +
                "(walked ${rig.walked.get()} of 2000)",
        )
    }
}
