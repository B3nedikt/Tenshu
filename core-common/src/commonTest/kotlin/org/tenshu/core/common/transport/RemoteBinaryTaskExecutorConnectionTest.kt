package org.tenshu.core.common.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.request.CallOutcome
import org.tenshu.core.core.dataFactory
import org.tenshu.core.schema.Type
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins that the first IPC call after process start waits for the connection
 * instead of crashing with **"Proxy not connected"**.
 *
 * The Android Binder bind is asynchronous: `RemoteBinaryTaskExecutor.bind()`
 * returns before `onServiceConnected` lands, so the first IPC call after process
 * start races the connection, and `BinderTaskTransport.call` throws
 * `IllegalStateException("Proxy not connected")` if it wins. Early auto-firing
 * consumers (background-backup status reporting / source registration) make that
 * call at launch, on a worker thread.
 *
 * A **system test cannot reproduce this** — it runs over the in-process or Unix
 * socket transport, both of which connect synchronously, so the window never
 * exists. The reproduction has to model an *asynchronously*-connecting transport,
 * which is exactly what [LatchTransport] does here.
 *
 * [RemoteBinaryTaskExecutor.roundTrip] awaits the connection
 * (`transport.awaitConnected()`) after `bind()`, and surfaces a `Failure` rather
 * than throwing if it never comes up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteBinaryTaskExecutorConnectionTest {

    /**
     * A transport that connects asynchronously, mirroring [BinderTaskTransport]:
     * [bind] does not connect; the connection only completes when the test calls
     * [connect]. [awaitConnected] suspends on the same latch the real
     * `onServiceConnected` completes.
     */
    private class LatchTransport : TaskTransport {
        private val signal = CompletableDeferred<Unit>()
        private var connected = false

        /** Set by [call]; proves we never invoked IPC while disconnected. */
        var calledWhileDisconnected = false
            private set
        var callCount = 0
            private set

        /** Bytes returned from a successful [call] (a serialized `TResult`). */
        var response: ByteArray = ByteArray(0)

        override val isConnected: Boolean get() = connected

        override fun bind() { /* async — connection completes via connect(), not here */ }

        fun connect() {
            connected = true
            signal.complete(Unit)
        }

        override fun unbind() { connected = false }

        override suspend fun awaitConnected(timeoutMillis: Long): Boolean {
            if (connected) return true
            withTimeoutOrNull(timeoutMillis) { signal.await() }
            return connected
        }

        override suspend fun call(request: ByteArray): ByteArray {
            callCount++
            if (!connected) {
                calledWhileDisconnected = true
                error("Proxy not connected")
            }
            return response
        }

        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null
    }

    private val proxy = TestProxy()
    private val parser = BinaryParser(proxy.schemaRegistry, proxy.schemaRegistry)

    private fun task(): TList<Data> =
        listOf<Data>("ping".toTString(proxy.tContext)).toTList(proxy.tContext)

    private fun successResponseBytes(): ByteArray {
        val success = TResult.Success.create<Data?>(context = proxy.tContext, value = null)
        return BinarySerializer().serialize(
            success,
            isTopLevel = true,
            expectedType = Type.create(baseSchema = TResult.Schema),
        )
    }

    @Test
    fun executeAsync_awaitsConnection_thenCalls() = runTest {
        val transport = LatchTransport().apply { response = successResponseBytes() }
        val executor = RemoteBinaryTaskExecutor(
            BinarySerializer(), parser, transport, proxy.tContext, this,
        )

        val result = async { executor.executeAsync(task()) }

        // Reach the suspension point: the executor must be waiting on the
        // connection, not hammering a disconnected transport.
        runCurrent()
        assertEquals(0, transport.callCount, "no IPC call is made before the transport connects")

        transport.connect()
        val resolved = result.await()

        assertIs<TResult.Success<*>>(resolved, "the call succeeds once connected")
        assertEquals(1, transport.callCount, "exactly one IPC call, made after connecting")
        assertFalse(transport.calledWhileDisconnected, "call() is never invoked while disconnected")
    }

    @Test
    fun executeAsync_returnsFailure_whenNeverConnects() = runTest {
        val transport = LatchTransport() // never connect()ed

        val executor = RemoteBinaryTaskExecutor(
            BinarySerializer(), parser, transport, proxy.tContext, this,
        )

        val result = executor.executeAsync(task())

        assertIs<TResult.Failure<*>>(result, "a never-connecting proxy yields a Failure, not a crash")
        assertEquals(0, transport.callCount, "the transport is never called while disconnected")
        assertFalse(transport.calledWhileDisconnected)
    }

    /**
     * The request side of this class's stated contract — *callers get a `TResult`, never a throw*.
     *
     * Both the response parse and the request `serialize` sit inside the try. A request this build
     * cannot encode — `LinearSerializer`'s *"Missing non-optional field"* is the reachable one —
     * would otherwise throw straight past every guard here and land in whichever repository `catch`
     * is nearest.
     *
     * ⚠⚠ The asymmetry this closes: `LocalTaskExecutor` is `core-error-channel.md`'s designated
     * **single catch-all** and folds everything, including a failed request parse. Without the fold
     * here an in-process runtime is protected and a Binder/UDS one is not — the reverse of where the
     * wire boundary is.
     *
     * ⚠ No `CallOutcome` is emitted: nothing was sent, so there is no round trip to classify. In
     * particular not `Unintelligible`, which means *the two builds disagree* — failing to encode our
     * own request is this build's own registry, and "update Control" is the wrong instruction for it.
     *
     * ⚠ The request is genuinely unserialisable rather than a stubbed throw, so with the fold
     * removed this fails with the production exception verbatim: *"Field
     * tenshu/core/result/failure/error is null … but was marked mandatory in the schema"*.
     */
    @Test
    fun executeAsync_returnsFailure_whenTheRequestCannotBeSerialised() = runTest {
        val transport = LatchTransport().apply { response = successResponseBytes(); connect() }
        val outcomes = mutableListOf<CallOutcome>()
        val executor = RemoteBinaryTaskExecutor(
            BinarySerializer(), parser, transport, proxy.tContext, this,
        ).apply { setOnCallOutcome { outcomes += it } }

        val result = executor.executeAsync(unserialisableTask())

        assertIs<TResult.Failure<*>>(result, "an unserialisable request folds instead of throwing")
        assertEquals(0, transport.callCount, "nothing was sent")
        assertTrue(outcomes.isEmpty(), "no round trip happened, so there is nothing to classify")
    }

    /**
     * A request the serializer genuinely cannot encode: a `TResult.Failure` whose non-optional
     * `error` field is absent, which is `LinearSerializer`'s one `error(...)`. Built through the
     * public `DataFactory`, whose `fields: List<Data?>` makes the hole expressible.
     */
    private fun unserialisableTask(): TList<Data> {
        val malformed: Data = proxy.tContext.dataFactory()
            .createComposite<Composite>(TResult.Failure.ID, listOf(null))
        return listOf(malformed).toTList(proxy.tContext)
    }
}
