package org.tenshu.core.common.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.impl.UnannouncedSessionErrorDto
import org.tenshu.core.TResult
import org.tenshu.core.UnannouncedSessionError
import org.tenshu.core.collection.list.TList
import org.tenshu.core.id
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.request.CallOutcome
import org.tenshu.core.schema.Type
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A call that reaches a restarted proxy's new session before the client has re-announced
 * (`docs/tech-debt.md`, *"A call can beat the re-announce after a Control restart"*, 2026-10-28).
 *
 * A transport publishes the new connection, wakes the callers parked in `awaitConnected`, and only
 * then fires `onReconnected` — which is what re-arms the announce. A caller woken in between still
 * holds the previous session's announce, sends unannounced, and the proxy's reject-if-silent gate
 * refuses it with `unannouncedSession`. Seen on an emulator after a Control reinstall: Fit's
 * `probeControl()` and its `workouts` stream, each flashing failed for about a second.
 *
 * [SessionTransport] reproduces the window exactly: [SessionTransport.connect] wakes the waiters and
 * the test runs them before it delivers the late `onReconnected`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteBinaryTaskExecutorReannounceTest {

    /**
     * A transport over a proxy with sessions. Each connect is a new session that refuses every call
     * until [announce] admits it, as `LocalTaskExecutor.schemaSessionGate` does.
     */
    private inner class SessionTransport : TaskTransport {
        private var signal = CompletableDeferred<Unit>()
        private var connected = true
        private var session = 1
        private var announcedSession = 0
        private var onReconnected: (() -> Unit)? = null

        /** When true the proxy never admits anything: every ordinary call is refused. */
        var refuseForever = false

        /** Yields inside [call], so concurrent callers all send before any reply arrives. */
        var yieldInCall = false

        var announces = 0
            private set
        var calls = 0
            private set
        var refusals = 0
            private set

        override val isConnected: Boolean get() = connected

        override fun setOnReconnected(callback: (() -> Unit)?) {
            onReconnected = callback
        }

        override fun bind() {}
        override fun unbind() {}

        /** The admitted announce for the current session — what `sessionPreflight` runs. */
        fun announce() {
            announces++
            if (!refuseForever) announcedSession = session
        }

        /** Control died: the next connect is a new session. */
        fun drop() {
            connected = false
            signal = CompletableDeferred()
            session++
        }

        /** The new session is up and its waiters are woken — `onReconnected` has NOT run yet. */
        fun connect() {
            connected = true
            signal.complete(Unit)
        }

        /** The transport's late `onReconnected`, as the pre-fix Binder order delivered it. */
        fun reconnectedCallback() {
            onReconnected?.invoke()
        }

        override suspend fun awaitConnected(timeoutMillis: Long): Boolean {
            if (connected) return true
            val waitOn = signal
            withTimeoutOrNull(timeoutMillis) { waitOn.await() }
            return connected
        }

        override suspend fun call(request: ByteArray): ByteArray {
            calls++
            // A guard against the resend becoming a loop: a runaway shows as a count, not a hang.
            check(calls <= 10) { "runaway resend" }
            val admitted = announcedSession == session
            if (!admitted) refusals++
            if (yieldInCall) yield()
            return if (admitted) success else refused
        }

        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null
    }

    private val proxy = TestProxy()
    private val parser = BinaryParser(proxy.schemaRegistry, proxy.schemaRegistry)

    private fun serialize(result: TResult<*>): ByteArray = BinarySerializer().serialize(
        result,
        isTopLevel = true,
        expectedType = Type.create(baseSchema = TResult.Schema),
    )

    private val success = serialize(TResult.Success.create<Data?>(context = proxy.tContext, value = null))
    private val refused = serialize(
        TResult.Failure.create<Data>(
            context = proxy.tContext,
            error = UnannouncedSessionError.create(proxy.tContext, "session has no admitted schema announce"),
        )
    )

    private fun task(): TList<Data> =
        listOf<Data>("workouts".toTString(proxy.tContext)).toTList(proxy.tContext)

    private fun TestScope.executorOver(transport: SessionTransport, outcomes: MutableList<CallOutcome>) =
        RemoteBinaryTaskExecutor(BinarySerializer(), parser, transport, proxy.tContext, this).apply {
            sessionPreflight = { transport.announce() }
            setOnCallOutcome { outcomes += it }
        }

    @Test
    fun aCallerWokenBeforeTheReannounceIsResentOnTheNewSession() = runTest {
        val transport = SessionTransport()
        val outcomes = mutableListOf<CallOutcome>()
        val executor = executorOver(transport, outcomes)
        assertIs<TResult.Success<*>>(executor.executeAsync(task()), "session 1 announces and is served")

        transport.drop()
        val pending = async { executor.executeAsync(task()) }
        runCurrent() // parked in awaitConnected
        transport.connect()
        runCurrent() // ⚠ the woken caller runs here — before the transport's onReconnected
        transport.reconnectedCallback()
        val result = pending.await()

        assertEquals(1, transport.refusals, "the woken caller did reach the new session unannounced")
        assertIs<TResult.Success<*>>(result, "…and was announced and resent, not failed")
        assertEquals(2, transport.announces, "one announce per session")
        assertEquals(
            listOf(CallOutcome.Answered, CallOutcome.Answered),
            outcomes,
            "the refusal that was resent is not reported — a reachability engine must not flash unreachable",
        )
    }

    @Test
    fun aSecondRefusalIsTheAnswerNotALoop() = runTest {
        val transport = SessionTransport().apply { refuseForever = true }
        val outcomes = mutableListOf<CallOutcome>()
        val executor = executorOver(transport, outcomes)

        val result = executor.executeAsync(task())

        assertIs<TResult.Failure<*>>(result)
        assertEquals(UnannouncedSessionError.ID, result.error.id, "the caller sees the proxy's refusal")
        assertEquals(2, transport.calls, "sent once and resent once — never again")
        assertEquals(2, transport.announces, "the first announce, and one re-announce")
        assertEquals(listOf(CallOutcome.AnsweredWithError), outcomes, "only the answer the caller got is reported")
    }

    /**
     * The re-announce itself fails: the proxy refuses the admission (a typed `TenshuException`, as
     * `SchemaAnnouncer` throws for a refused verdict) or the channel drops mid-announce. The caller
     * gets that failure and the call is not sent again.
     */
    private fun TestScope.reannounceThatFails(failure: Throwable): Pair<SessionTransport, RemoteBinaryTaskExecutor> {
        val transport = SessionTransport().apply { refuseForever = true }
        val executor = RemoteBinaryTaskExecutor(BinarySerializer(), parser, transport, proxy.tContext, this).apply {
            sessionPreflight = {
                val first = transport.announces == 0
                transport.announce()
                if (!first) throw failure
            }
        }
        return transport to executor
    }

    @Test
    fun aRefusedReannounceFailsTheCallerWithoutAResend() = runTest {
        val (transport, executor) = reannounceThatFails(
            TenshuException(UnannouncedSessionErrorDto("schema session refused for test"))
        )

        val result = executor.executeAsync(task())

        assertIs<TResult.Failure<*>>(result)
        assertEquals(UnannouncedSessionError.ID, result.error.id, "the refused admission is the caller's answer")
        assertEquals(1, transport.calls, "the call is not resent after a failed re-announce")
        assertEquals(2, transport.announces)
    }

    @Test
    fun aReannounceThatLosesTheChannelFailsTheCallerWithoutAResend() = runTest {
        val (transport, executor) = reannounceThatFails(IllegalStateException("Proxy died mid-call"))

        val result = executor.executeAsync(task())

        assertIs<TResult.Failure<*>>(result)
        assertEquals(GenericError.ID, result.error.id, "a failed announce reads as not connected")
        assertEquals(1, transport.calls, "the call is not resent after a failed re-announce")
        assertEquals(2, transport.announces)
    }

    @Test
    fun callersRefusedByOneStaleSessionShareOneReannounce() = runTest {
        val transport = SessionTransport()
        val executor = executorOver(transport, mutableListOf())
        assertIs<TResult.Success<*>>(executor.executeAsync(task()))

        transport.drop()
        transport.yieldInCall = true
        val first = async { executor.executeAsync(task()) }
        val second = async { executor.executeAsync(task()) }
        runCurrent()
        transport.connect()
        runCurrent()
        transport.reconnectedCallback()

        assertIs<TResult.Success<*>>(first.await())
        assertIs<TResult.Success<*>>(second.await())
        assertEquals(2, transport.refusals, "both callers reached the new session unannounced")
        assertEquals(2, transport.announces, "the second refusal finds the session already re-announced")
    }
}
