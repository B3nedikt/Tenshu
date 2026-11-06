package org.tenshu.core.common.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamInteractor
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.EndStream
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.initSyntheticService
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.syntheticService
import org.tenshu.core.common.syntheticServiceModule
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.fold
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.Type
import org.tenshu.core.security.BootstrapSyncCryptoManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ A re-subscription that **overlaps** the previous subscription's end (`docs/tech-debt.md`, "A
 * re-subscription is handed the ended subscription's still-queued elements", the overlap residual).
 *
 * Stream ids are callstack hashes, so two collectors of one chain share an id, and
 * `DefaultInteractorProvider` replaced a closed interactor at once — before its `EndStream` had
 * reached the proxy. One collector ending while another subscribes (a screen transition: the
 * outgoing view model cleared while the incoming one subscribes) then raced the end at the proxy:
 *
 * - **(b)** the proxy removes a listener **by id** (`ListStreamEndImplementation`), so an end it
 *   processed after the new start removed the **new** subscription's listener: the snapshot
 *   arrived, the updates never did — a silently frozen list;
 * - **(a)** until the end was processed the old registration was the live one, so what it sent
 *   after the client's new registration passed both the receipt stamp and the proxy's token.
 *
 * The fix: the provider waits for a closing predecessor's end before sending the new start, and the
 * new entry drops everything while it waits. Only then — a normal subscribe never waits.
 *
 * Forced deterministically by holding the old subscription's `EndStream`: in process by blocking the
 * real `ListStreamEndImplementation`, over IPC by a transport whose fake proxy holds the call. The
 * IPC half runs the real client stack — `RemoteBinaryTaskExecutor`'s push lane (which stamps
 * receipts) into `DefaultInteractorProvider` — against a proxy modelled to the one fact that
 * matters: a listener per stream id, which an end removes by id.
 */
class StreamResubscribeOverlapTest {

    // ---- (b), in process, end to end ----------------------------------------------------------

    @Test
    fun anOverlappedEndDoesNotRemoveTheNextSubscriptionsListener_inProcess() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val heldEnd = HeldEnd()
        try {
            val proxy = InProcessProxy(
                clientId = "stream-overlap-client",
                serviceScope = scope,
                syncCryptoManager = BootstrapSyncCryptoManager(),
                operationImplementationFactory = OperationImplementationFactory { id ->
                    if (id == EndStream.ID) heldEnd.wrapping(implementationFactory(id) as SyncOperationImplementation)
                    else implementationFactory(id)
                },
                schemaRegistryFactory = { tContextProvider ->
                    createDefaultSchemaRegistry(tContextProvider, extraConfigs = listOf(syntheticServiceModule))
                },
                initialization = { initSyntheticService() },
            )
            val context = proxy.executionContext
            context.saveData(SyntheticTestData.Factory.create(context.context, "alpha", 1))

            // The outgoing collector: subscribed, then cleared — its end is held at the proxy.
            val outgoing = CopyOnWriteArrayList<Set<String>>()
            val first = launch(Dispatchers.Default) { names(proxy).collect { outgoing += it } }
            withTimeout(WAIT_MS) { while (outgoing.isEmpty()) delay(POLL_MS) }
            heldEnd.holdNext()
            first.cancel()
            assertTrue(heldEnd.entered.await(WAIT_MS, TimeUnit.MILLISECONDS), "the outgoing end reached the proxy")

            // The incoming collector of the same chain subscribes while that end is still held.
            val incoming = CopyOnWriteArrayList<Set<String>>()
            val second = launch(Dispatchers.Default) { names(proxy).collect { incoming += it } }
            // Latched, not timed: unfixed, its start is processed now (its snapshot arrives); fixed,
            // it is waiting for the end.
            withTimeout(WAIT_MS) {
                while (incoming.isEmpty() && proxy.interactorProvider.overlapWaits.value < 1) delay(POLL_MS)
            }
            heldEnd.release()

            withTimeout(WAIT_MS) { while (incoming.isEmpty()) delay(POLL_MS) }
            context.saveData(SyntheticTestData.Factory.create(context.context, "beta", 2))
            withTimeoutOrNull(WAIT_MS) { while (incoming.last() != setOf("alpha", "beta")) delay(POLL_MS) }
            assertEquals(
                setOf("alpha", "beta"),
                incoming.last(),
                "the incoming read froze: the outgoing end removed its listener by id; every emission: $incoming",
            )
            second.cancel()
        } finally {
            heldEnd.release()
            scope.cancel()
        }
    }

    // ---- (b) and (a), over the IPC executor -------------------------------------------------------

    @Test
    fun anOverlappedEndDoesNotRemoveTheNextSubscriptionsListener_overIpc() = runBlocking<Unit> {
        Ipc().use { ipc ->
            val (a, cbA) = ipc.subscribe()
            withTimeout(WAIT_MS) { cbA.awaitValue("snapshot#1") }

            val end = ipc.proxy.holdNextEnd()
            val aEnd = launch(Dispatchers.Default) { a.endStream(cbA) }
            withTimeout(WAIT_MS) { end.entered.await() }

            val b = async(Dispatchers.Default) { ipc.subscribe() }
            // Unfixed, the start reaches the proxy now; fixed, it waits for the end.
            ipc.awaitStartedOrWaiting(starts = 2, waits = 1)
            end.release.complete(Unit)
            aEnd.join()
            val (_, cbB) = withTimeout(WAIT_MS) { b.await() }
            withTimeout(WAIT_MS) { cbB.awaitValue("snapshot#2") }

            ipc.proxy.update("gamma")
            withTimeoutOrNull(WAIT_MS) { cbB.awaitValue("gamma") }
            assertTrue(
                "gamma" in cbB.values,
                "the new subscription's listener was removed by the overlapped end: it got ${cbB.values}; proxy log ${ipc.proxy.log}",
            )
        }
    }

    @Test
    fun aResubscribeIsNotHandedWhatTheOverlappedRegistrationSentBeforeItsEnd_overIpc() = runBlocking<Unit> {
        Ipc().use { ipc ->
            val (a, cbA) = ipc.subscribe()
            withTimeout(WAIT_MS) { cbA.awaitValue("snapshot#1") }

            val end = ipc.proxy.holdNextEnd()
            val start = ipc.proxy.holdNextStart()
            val aEnd = launch(Dispatchers.Default) { a.endStream(cbA) }
            withTimeout(WAIT_MS) { end.entered.await() }

            val b = async(Dispatchers.Default) { ipc.subscribe() }
            // Unfixed, the start is now at the proxy (held, before it replaces the registration);
            // fixed, it is waiting for the end. Either way the new entry is registered client-side.
            withTimeout(WAIT_MS) { while (!start.entered.isCompleted && ipc.provider.overlapWaits.value < 1) delay(POLL_MS) }
            // The old registration is still the live one, and sends: an update it evaluated
            // before the client's new subscription — the new snapshot will not account for it.
            ipc.proxy.update("stale")
            start.release.complete(Unit)
            end.release.complete(Unit)
            aEnd.join()
            val (_, cbB) = withTimeout(WAIT_MS) { b.await() }
            withTimeout(WAIT_MS) { cbB.awaitValue("snapshot#2") }
            delay(200) // anything still in the push lane would land here

            assertTrue(
                "stale" !in cbB.values,
                "the new subscription was handed what the overlapped registration sent before its end: ${cbB.values}",
            )
        }
    }

    // ---- Liveness: the wait can neither hang nor leak --------------------------------------------

    /**
     * An end that never returns costs the next subscribe one bounded wait — and only that one: the
     * subscribes after it are not taxed again by the same hung end.
     */
    @Test
    fun anEndThatNeverReturnsHoldsTheNextStartOnlyBoundedly() = runBlocking<Unit> {
        Ipc().use { ipc ->
            val (a, cbA) = ipc.subscribe()
            withTimeout(WAIT_MS) { cbA.awaitValue("snapshot#1") }
            val end = ipc.proxy.holdNextEnd() // never released
            ipc.scope.launch { a.endStream(cbA) }
            withTimeout(WAIT_MS) { end.entered.await() }

            val (b, cbB) = withTimeout(WAIT_MS * 2) { ipc.subscribe() }
            withTimeout(WAIT_MS) { cbB.awaitValue("snapshot#2") }

            // Two more subscriptions of the id, each after the last one ended: no wait for either.
            b.endStream(cbB)
            val (c, cbC) = withTimeout(PROMPT_MS) { ipc.subscribe() }
            withTimeout(WAIT_MS) { cbC.awaitValue("snapshot#3") }
            c.endStream(cbC)
            val (_, cbD) = withTimeout(PROMPT_MS) { ipc.subscribe() }
            withTimeout(WAIT_MS) { cbD.awaitValue("snapshot#4") }
            assertEquals(1, ipc.provider.overlapWaits.value, "only the first successor waited on the hung end")
            end.release.complete(Unit)
        }
    }

    /**
     * ⚠⚠ A waiter whose interactor is ended **while it waits** must not then send its start: a
     * joiner reused the entry (it is not closed) and left, which sent the end and dropped the entry.
     * A start sent after that end leaves a proxy listener nobody reads, and the waiter's own caller
     * holds a closed interactor that never delivers. It subscribes afresh instead.
     */
    @Test
    fun aWaiterEndedByAJoinerWhileItWaitsDoesNotStartTheDeadInteractor() = runBlocking<Unit> {
        Ipc().use { ipc ->
            val (a, cbA) = ipc.subscribe()
            withTimeout(WAIT_MS) { cbA.awaitValue("snapshot#1") }
            val endA = ipc.proxy.holdNextEnd()
            val aEnd = launch(Dispatchers.Default) { a.endStream(cbA) }
            withTimeout(WAIT_MS) { endA.entered.await() }

            val b = async(Dispatchers.Default) { ipc.subscribe() }
            ipc.awaitStartedOrWaiting(starts = 2, waits = 1)
            // A joiner of the waiting entry comes and goes; its end is the entry's end.
            val (joined, cbJ) = withTimeout(WAIT_MS) { ipc.subscribe() }
            joined.endStream(cbJ)
            endA.release.complete(Unit)
            aEnd.join()

            val (bInteractor, cbB) = withTimeout(WAIT_MS) { b.await() }
            withTimeoutOrNull(WAIT_MS) { cbB.awaitValue("snapshot#2") }
            assertTrue("snapshot#2" in cbB.values, "the waiter's caller got a dead interactor: ${cbB.values}; proxy log ${ipc.proxy.log}")
            ipc.proxy.update("gamma")
            withTimeoutOrNull(WAIT_MS) { cbB.awaitValue("gamma") }
            assertTrue("gamma" in cbB.values, "the waiter's subscription is not live: ${cbB.values}; proxy log ${ipc.proxy.log}")
            bInteractor.endStream(cbB)
            assertEquals(0, ipc.proxy.liveCount(), "an orphaned proxy listener outlived every subscription: ${ipc.proxy.log}")
        }
    }

    @Test
    fun anEndThatFailsReleasesTheNextStart() = runBlocking<Unit> {
        Ipc().use { ipc ->
            val (a, cbA) = ipc.subscribe()
            withTimeout(WAIT_MS) { cbA.awaitValue("snapshot#1") }
            val end = ipc.proxy.holdNextEnd()
            val aEnd = launch(Dispatchers.Default) { a.endStream(cbA) }
            withTimeout(WAIT_MS) { end.entered.await() }

            val b = async(Dispatchers.Default) { ipc.subscribe() }
            ipc.awaitStartedOrWaiting(starts = 2, waits = 1)
            end.release.completeExceptionally(IllegalStateException("made up: the proxy went away mid-end"))
            aEnd.join()
            // Well inside the bounded wait: the failure released it.
            val (_, cbB) = withTimeout(WAIT_MS / 4) { b.await() }
            withTimeout(WAIT_MS) { cbB.awaitValue("snapshot#2") }
        }
    }

    /**
     * Two overlaps in a row, the middle subscriber cancelled while it waits: it sends its own end at
     * once, and the third must still not start until the **first** end is processed, or that end
     * removes the third's listener.
     */
    @Test
    fun aSubscriberCancelledWhileItWaitsDoesNotReleaseTheNextOneEarly() = runBlocking<Unit> {
        Ipc().use { ipc ->
            val (a, cbA) = ipc.subscribe()
            withTimeout(WAIT_MS) { cbA.awaitValue("snapshot#1") }
            val endA = ipc.proxy.holdNextEnd()
            val aEnd = launch(Dispatchers.Default) { a.endStream(cbA) }
            withTimeout(WAIT_MS) { endA.entered.await() }

            val b = launch(Dispatchers.Default) { ipc.subscribe() }
            ipc.awaitStartedOrWaiting(starts = 2, waits = 1)
            b.cancelAndJoin()

            val c = async(Dispatchers.Default) { ipc.subscribe() }
            ipc.awaitStartedOrWaiting(starts = 3, waits = 2)
            endA.release.complete(Unit)
            aEnd.join()
            val (_, cbC) = withTimeout(WAIT_MS) { c.await() }
            withTimeout(WAIT_MS) { cbC.awaitValue("snapshot#2") }

            ipc.proxy.update("gamma")
            withTimeoutOrNull(WAIT_MS) { cbC.awaitValue("gamma") }
            assertTrue("gamma" in cbC.values, "the third subscription's listener was removed: ${cbC.values}; proxy log ${ipc.proxy.log}")
            assertEquals(2, ipc.proxy.starts.get(), "the cancelled waiter never sent a start; proxy log ${ipc.proxy.log}")
        }
    }

    /**
     * ⚠ The bound is measured on a real clock. Under `runTest` the end's round trip runs on a real
     * dispatcher, so the test scheduler goes idle while the start waits — and a virtual-time timeout
     * then fires at once, sending the start ahead of the end: a false green for any overlap test
     * written that way.
     */
    @Test
    fun theWaitIsMeasuredOnARealClockEvenUnderRunTest() = runTest {
        Ipc().use { ipc ->
            val (a, cbA) = ipc.subscribe()
            withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { cbA.awaitValue("snapshot#1") } }
            val end = ipc.proxy.holdNextEnd()
            ipc.scope.launch { a.endStream(cbA) }
            withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { end.entered.await() } }

            val b = async { ipc.subscribe() } // on the test dispatcher, as a runTest caller would be
            withContext(Dispatchers.Default) {
                ipc.awaitStartedOrWaiting(starts = 2, waits = 1)
                delay(300) // real time: a virtual-clock wait has long given up by now
            }
            end.release.complete(Unit)
            b.await()
            val log = ipc.proxy.log.filter { it.startsWith("start") || it.startsWith("end") }
            assertEquals(listOf("start 4242", "end 4242", "start 4242"), log, "the start went ahead of the end")
        }
    }

    /** A proxy restart while a start waits: the waiter is not replayed early, and starts once. */
    @Test
    fun aResubscribeAllWhileAStartWaitsDoesNotSendItEarly() = runBlocking<Unit> {
        Ipc().use { ipc ->
            val (a, cbA) = ipc.subscribe()
            withTimeout(WAIT_MS) { cbA.awaitValue("snapshot#1") }
            val end = ipc.proxy.holdNextEnd()
            val aEnd = launch(Dispatchers.Default) { a.endStream(cbA) }
            withTimeout(WAIT_MS) { end.entered.await() }

            val b = async(Dispatchers.Default) { ipc.subscribe() }
            ipc.awaitStartedOrWaiting(starts = 2, waits = 1)
            ipc.provider.resubscribeAll()
            assertEquals(1, ipc.proxy.starts.get(), "a waiting start was replayed ahead of the end: ${ipc.proxy.log}")
            end.release.complete(Unit)
            aEnd.join()
            val (_, cbB) = withTimeout(WAIT_MS) { b.await() }
            withTimeout(WAIT_MS) { cbB.awaitValue("snapshot#2") }
            assertEquals(2, ipc.proxy.starts.get(), "one start per subscription: ${ipc.proxy.log}")
        }
    }

    // ---- Fixture -----------------------------------------------------------------------------------

    /** The real `EndStream`, except the next one blocks its (proxy) thread until [release]. */
    private class HeldEnd {
        @Volatile private var armed = false
        val entered = CountDownLatch(1)
        private val gate = CountDownLatch(1)

        fun holdNext() { armed = true }
        fun release() = gate.countDown()

        fun wrapping(real: SyncOperationImplementation) = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T {
                if (armed) {
                    armed = false
                    entered.countDown()
                    gate.await(WAIT_MS, TimeUnit.MILLISECONDS)
                }
                return real.execute(executionContext, operationContext, implementationFactory, caller, params)
            }
        }
    }

    private class Hold {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /**
     * A proxy reduced to the fact the overlap turns on: one listener per stream id, registered by a
     * start (which sends its snapshot first, as the real one does) and removed **by id** by an end.
     */
    private class FakeProxy(private val context: TContext) : TaskTransport {
        private val serializer = BinarySerializer()
        private val parser = BinaryParser(context.schemaProvider(), context.dataFactory())
        private val live = mutableSetOf<Long>()
        val starts = AtomicInteger(0)
        val log = CopyOnWriteArrayList<String>()
        @Volatile private var nextEnd: Hold? = null
        @Volatile private var nextStart: Hold? = null

        fun holdNextEnd() = Hold().also { nextEnd = it }
        fun liveCount() = synchronized(live) { live.size }
        fun holdNextStart() = Hold().also { nextStart = it }

        /** What a write makes every live registration send. */
        fun update(row: String) {
            val ids = synchronized(live) { live.toList() }
            ids.forEach { push(it, row) }
        }

        private fun push(streamId: Long, row: String) {
            log += "push $streamId $row"
            onCallback?.invoke(serializer.serialize(StreamElementValueDto(streamId, TStringDto(row))))
        }

        override val isConnected = true
        override fun bind() {}
        override fun unbind() {}
        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null

        override suspend fun call(request: ByteArray): ByteArray {
            val task = parser.parse<TList<Data>>(data = request, expectedType = Type.create(baseSchema = TList.Schema))
            val op = task.toList().last() as Operation
            when (op.id) {
                EndStream.ID -> {
                    val streamId = (op.params[EndStream.Params.STREAM_ID] as TInteger).get()
                    nextEnd?.let { hold ->
                        nextEnd = null
                        hold.entered.complete(Unit)
                        try {
                            hold.release.await()
                        } catch (e: IllegalStateException) {
                            log += "end $streamId failed"
                            throw e
                        }
                    }
                    synchronized(live) { live.remove(streamId) }
                    log += "end $streamId"
                }
                Stream.ID -> {
                    val streamId = (op.params[Stream.Params.STREAM_ID] as TInteger).get()
                    nextStart?.let { hold ->
                        nextStart = null
                        hold.entered.complete(Unit)
                        hold.release.await()
                    }
                    val n = starts.incrementAndGet()
                    log += "start $streamId"
                    push(streamId, "snapshot#$n")
                    synchronized(live) { live.add(streamId) }
                }
                else -> error("unexpected op ${op.id}")
            }
            return serializer.serialize(TResult.Success.create(context, TStringDto("OK")))
        }
    }

    private class Recording : StreamCallback<TString> {
        val values = CopyOnWriteArrayList<String>()
        override fun start(error: TError?) {}
        override fun element(streamElement: StreamElement<TString>) {
            if (streamElement is StreamElement.Value) values += streamElement.payload.get()
        }
        override fun catchUp(elements: List<StreamElement<TString>>) = elements.forEach { element(it) }
        override fun end(error: TError?) {}

        suspend fun awaitValue(value: String) {
            while (value !in values) delay(POLL_MS)
        }
    }

    /** The real client stack over [FakeProxy]: executor push lane → provider, as `wireClientPushDispatch` wires it. */
    private class Ipc : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context: TContext = createTestContext(createSyntheticTestRegistry())
        val proxy = FakeProxy(context)
        private val executor = RemoteBinaryTaskExecutor(
            serializer = BinarySerializer(),
            parser = BinaryParser(context.schemaProvider(), context.dataFactory()),
            transport = proxy,
            context = context,
            externalScope = scope,
        )
        val provider = DefaultInteractorProvider(scope) { executor }

        init {
            executor.streamElementHandler = { element -> if (element is StreamElement<*>) provider.dispatchElement(element) }
        }

        /** Subscribes the one chain every call shares (same id), and registers a recording consumer. */
        suspend fun subscribe(): Pair<StreamInteractor<TString>, Recording> {
            val ro = object : OperationRo(
                requestChain = LocalRequestChain(executor, context, streamId = STREAM_ID)
                    .addArray(
                        OperationDto(
                            schema = Stream.Schema,
                            params = mapOf(Stream.Params.STREAM_ID to STREAM_ID.toTInteger(context)),
                        )
                    ),
                context = context,
            ) {
                override val schema = Stream.Schema
            }
            @Suppress("UNCHECKED_CAST")
            val interactor = provider.getInteractor<TString>(ro) as StreamInteractor<TString>
            val callback = Recording()
            interactor.registerCallback(callback)
            return interactor to callback
        }

        /**
         * Latched, never timed: either the subscription's start reached the proxy ([starts], the
         * unfixed behaviour) or it is waiting for the overlapped end ([waits], the fixed one).
         */
        suspend fun awaitStartedOrWaiting(starts: Int, waits: Int) = withTimeout(WAIT_MS) {
            while (proxy.starts.get() < starts && provider.overlapWaits.value < waits) delay(POLL_MS)
        }

        override fun close() = scope.cancel()
    }

    private fun names(proxy: InProcessProxy) = kotlinx.coroutines.flow.flow {
        proxy.client.syntheticService.items.flow().collect { result ->
            emit(result.fold(onSuccess = { list -> list.toList().map { it.name.get() }.toSet() }, onFailure = { emptySet() }))
        }
    }

    private companion object {
        const val STREAM_ID = 4242L
        const val WAIT_MS = 15_000L
        const val POLL_MS = 10L
        /** Well under the 5 s overlap bound: a subscribe that did not wait at all. */
        const val PROMPT_MS = 2_500L
    }
}
