package org.tenshu.core.common.proxy

import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.os.TransactionTooLargeException
import app.tenshu.control.android.IClientInterface
import org.tenshu.core.InternalError
import org.tenshu.core.TByteArray
import org.tenshu.core.id
import org.tenshu.core.common.client.FileByteArrayDto
import org.tenshu.core.common.impl.InternalErrorDto
import org.tenshu.core.common.impl.StreamElementEndDto
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.util.toTString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidClientConnectionTest {

    private val serializer = BinarySerializer()

    /** What the out-of-band path carried; `available = false` is a device below API 27. */
    private class RecordingCarrier(var available: Boolean = true) : OversizeCarrier {
        val carried = mutableListOf<ByteArray>()
        override fun carry(service: IClientInterface, bytes: ByteArray): Boolean {
            if (!available) return false
            service.callbackShared(null)
            carried += bytes
            return true
        }
    }

    /** Off by default: most tests here model the inline path; the out-of-band ones switch it on. */
    private val carrier = RecordingCarrier(available = false)

    /** The held-end timer, run by hand: (delay, task) in the order scheduled. */
    private val timer = mutableListOf<Pair<Long, () -> Unit>>()
    private val schedule: (Long, () -> Unit) -> Unit = { delay, task -> timer += delay to task }

    private fun runTimer() = timer.removeAt(0).second()

    private fun connection(client: FakeClient? = null) = AndroidClientConnection(
        context = StubContext(),
        serializer = serializer,
        fileDatabase = NoopFileDatabase(),
        clientPackageName = "test.package",
        inlineLimitBytes = LIMIT,
        oversizeCarrier = carrier,
        scheduleRetry = schedule,
    ).also {
        if (client != null) {
            it.clientService = client
            it.isConnected = true
        }
    }

    @Test
    fun connectSetsClientServiceAndSendUsesIt() {
        val client = FakeClient()
        val connection = connection(client)

        connection.send("test".toTString())

        assertEquals(1, client.callbacks.size, "The callback on the provided binder should have been invoked")
    }

    /**
     * Regression for the photo-details `DeadObjectException` crash:
     * `send()` must not throw when the remote binder is dead. It should
     * clear the service reference, queue the message for the next bind,
     * and stay alive so the stream-listener coroutine can keep emitting.
     */
    @Test
    fun send_swallowsDeadObjectException_andClearsService() {
        val client = FakeClient(alive = false).apply { failCallback = { DeadObjectException("simulated remote death") } }
        val connection = connection(client)

        // Pre-fix: this would throw DeadObjectException out of the stream
        // listener and tear it down.
        connection.send("payload".toTString())

        assertNull(connection.clientService, "service ref must clear on dead binder")
        assertFalse(connection.isConnected, "isConnected must flip to false")
    }

    /**
     * ⚠⚠ The 2026-10-23 defect (`core-client-resubscription.md` §4.8): a too-large transaction was
     * read as a dead binder, the service ref was cleared, and since the binding was alive nothing
     * ever restored it. A non-death failure must leave the connection exactly as it was.
     */
    @Test
    fun aTooLargeTransactionDoesNotTakeTheConnectionDown() {
        val client = FakeClient()
        client.failCallback = { bytes -> if (bytes.size > LIMIT) TransactionTooLargeException() else null }
        val connection = connection(client)

        connection.send(StreamElementValueDto(STREAM_A, "x".repeat(LIMIT * 2).toTString()))

        assertSame(client, connection.clientService, "a live binding must survive a failed transaction")
        assertTrue(connection.isConnected)

        // A later send goes straight out — it is neither queued nor dropped.
        val later = StreamElementValueDto(STREAM_B, "small".toTString())
        connection.send(later)
        assertContentEquals(serializer.serialize(later), client.callbacks.last())
    }

    /**
     * The failed element is not silently dropped: its stream is ended with a typed error, so that
     * one subscription sees a failure instead of waiting for an emission that will never come —
     * and every other stream keeps delivering.
     */
    @Test
    fun anUndeliverableElementEndsOnlyItsOwnStreamWithATypedError() {
        val client = FakeClient()
        client.failCallback = { bytes -> if (bytes.size > LIMIT) TransactionTooLargeException() else null }
        val connection = connection(client)

        connection.send(StreamElementValueDto(STREAM_B, "before".toTString()))
        connection.send(StreamElementValueDto(STREAM_A, "x".repeat(LIMIT * 2).toTString()))
        connection.send(StreamElementValueDto(STREAM_B, "after".toTString()))

        assertEquals(3, client.callbacks.size, "B before, A's end, B after — and nothing of A's oversize element")
        assertContentEquals(serializer.serialize(StreamElementValueDto(STREAM_B, "before".toTString())), client.callbacks[0])
        val end = client.callbacks[1]
        assertContentEquals(
            serializer.serialize(StreamElementEndDto(STREAM_A, InternalErrorDto(internalDetail = expectedDetail(client.failedSizes.single())))),
            end,
            "stream A ends with InternalError naming the transport failure",
        )
        assertTrue(end.size < 1024, "the end element is small enough to fit where the element did not")
        assertContentEquals(serializer.serialize(StreamElementValueDto(STREAM_B, "after".toTString())), client.callbacks[2])
    }

    /**
     * ⚠ `DeadObjectException` is also what Android throws for a transaction that fails "on a small
     * parcel" — the receiver's buffer full — while the remote process lives. The binder decides.
     */
    @Test
    fun aDeadObjectExceptionFromALiveBinderIsNotDeath() {
        val client = FakeClient(alive = true)
        var failNext = true
        client.failCallback = { if (failNext) { failNext = false; DeadObjectException("buffer full") } else null }
        val connection = connection(client)

        connection.send(StreamElementValueDto(STREAM_A, "one".toTString()))

        assertSame(client, connection.clientService)
        assertTrue(connection.isConnected)
        assertEquals(1, client.callbacks.size, "only stream A's end went out")
    }

    @Test
    fun aGenericRemoteExceptionFromALiveBinderKeepsTheService() {
        val client = FakeClient()
        var failNext = true
        client.failCallback = { if (failNext) { failNext = false; RemoteException("transient") } else null }
        val connection = connection(client)

        // Must not throw.
        connection.send("payload".toTString())

        assertSame(client, connection.clientService)
    }

    /**
     * ⚠⚠ The symptom the user saw: after one oversize emission, every workout detail failed with
     * *ParcelFileDescriptor … not found in cache*, because `receiveFile` went to a cleared service
     * ref (a no-op) while the token was still written. After a TTLE, a file payload must still
     * travel, descriptor first.
     */
    @Test
    fun aFileTokenStillTravelsAfterATooLargeTransaction() {
        val client = FakeClient()
        client.failCallback = { bytes -> if (bytes.size > LIMIT) TransactionTooLargeException() else null }
        val connection = connection(client)
        val file = java.io.File.createTempFile("streams", ".bin").apply { deleteOnExit() }

        connection.send(StreamElementValueDto(STREAM_A, "x".repeat(LIMIT * 2).toTString()))
        connection.send(StreamElementValueDto(STREAM_B, FileByteArrayDto(file)))

        assertEquals(listOf(file.absolutePath), client.receivedFiles, "the descriptor went out")
        assertEquals(2, client.callbacks.size, "A's end, then B's element carrying the token")
    }

    /** A token is written only once its descriptor went out; otherwise the message fails as a whole. */
    @Test
    fun aFileWhoseDescriptorDidNotGoOutIsNeverTokenised() {
        val client = FakeClient()
        client.failReceiveFile = { TransactionTooLargeException() }
        val connection = connection(client)
        val file = java.io.File.createTempFile("streams", ".bin").apply { deleteOnExit() }

        connection.send(StreamElementValueDto(STREAM_B, FileByteArrayDto(file)))

        assertEquals(1, client.callbacks.size)
        val end = client.callbacks.single()
        assertContentEquals(
            serializer.serialize(
                StreamElementEndDto(
                    STREAM_B,
                    InternalErrorDto(internalDetail = "IPC: a file payload could not be handed to the client (receiveFile: TransactionTooLargeException)"),
                )
            ),
            end,
            "stream B ends; no payload with a dangling token is sent",
        )
        assertSame(client, connection.clientService)
    }

    @Test
    fun withNoBindingAFileIsATypedFailureNotADanglingToken() {
        val connection = connection(client = null)
        val file = java.io.File.createTempFile("streams", ".bin").apply { deleteOnExit() }

        val thrown = runCatching { connection.tokenize(FileByteArrayDto(file)) }.exceptionOrNull()

        val error = assertNotNull(thrown as? org.tenshu.core.TenshuException).error
        assertEquals(InternalError.ID, error.id)
    }

    // ---- the root fix: a message too large for one transaction still arrives (§4.8) ----

    /** ⚠⚠ The 2026-10-23 emission, end to end on Control's side: it arrives, out of band, whole. */
    @Test
    fun anOversizeEmissionArrivesOutOfBand() {
        carrier.available = true
        val client = FakeClient()
        client.failCallback = { bytes -> if (bytes.size > LIMIT) TransactionTooLargeException() else null }
        val connection = connection(client)
        val oversize = StreamElementValueDto(STREAM_A, "x".repeat(LIMIT * 4).toTString())

        connection.send(oversize)
        connection.send(StreamElementValueDto(STREAM_B, "small".toTString()))

        assertEquals(1, carrier.carried.size)
        assertContentEquals(serializer.serialize(oversize), carrier.carried.single(), "the same bytes, not a summary")
        assertEquals(1, client.sharedCallbacks)
        assertEquals(1, client.callbacks.size, "the small one stays inline")
        assertTrue(client.failedSizes.isEmpty(), "nothing was ever tried inline at that size")
    }

    @Test
    fun aMessageAtTheLimitStaysInline() {
        carrier.available = true
        val client = FakeClient()
        val connection = connection(client)

        connection.send("small".toTString())

        assertTrue(carrier.carried.isEmpty())
    }

    /** Below API 27 there is no `SharedMemory`: the message goes inline, as it always did. */
    @Test
    fun withNoSharedMemoryTheMessageGoesInline() {
        carrier.available = false
        val client = FakeClient()
        val connection = connection(client)
        val oversize = StreamElementValueDto(STREAM_A, "x".repeat(LIMIT * 2).toTString())

        connection.send(oversize)

        assertContentEquals(serializer.serialize(oversize), client.callbacks.single())
    }

    /** The out-of-band transaction can fail too (the descriptor table full, say); same rule as inline. */
    @Test
    fun aFailedOutOfBandTransactionEndsOnlyItsStream() {
        carrier.available = true
        val client = FakeClient()
        client.failShared = { RemoteException("no descriptors left") }
        val connection = connection(client)

        connection.send(StreamElementValueDto(STREAM_A, "x".repeat(LIMIT * 2).toTString()))
        connection.send(StreamElementValueDto(STREAM_B, "small".toTString()))

        assertEquals(2, client.callbacks.size, "A's end, then B")
        assertContentEquals(serializer.serialize(StreamElementValueDto(STREAM_B, "small".toTString())), client.callbacks[1])
        assertSame(client, connection.clientService)
    }

    /**
     * ⚠⚠ An end that does not go out is the one loss a client cannot recover from — its subscription
     * waits on it. It is held, resent before the next message, and its stream's later elements are
     * dropped meanwhile (a `Removed` slipping past would leave a ghost row behind an end never seen).
     */
    @Test
    fun anEndThatDidNotGoOutIsResentBeforeTheNextSend() {
        val client = FakeClient()
        var refuseSmall = true
        client.failCallback = { bytes ->
            when {
                bytes.size > LIMIT -> TransactionTooLargeException()
                refuseSmall -> RemoteException("buffer full").also { refuseSmall = false }
                else -> null
            }
        }
        val connection = connection(client)

        connection.send(StreamElementValueDto(STREAM_A, "x".repeat(LIMIT * 2).toTString()))
        assertTrue(client.callbacks.isEmpty(), "neither the element nor its end went out")

        refuseSmall = true // the next attempt fails too: still held
        connection.send(StreamElementValueDto(STREAM_A, "a later element of the ended stream".toTString()))
        assertTrue(client.callbacks.isEmpty(), "A's later element is dropped while its end is held")

        val b = StreamElementValueDto(STREAM_B, "b".toTString())
        connection.send(b)
        assertEquals(2, client.callbacks.size)
        assertContentEquals(
            serializer.serialize(StreamElementEndDto(STREAM_A, InternalErrorDto(internalDetail = expectedDetail(client.failedSizes.first())))),
            client.callbacks[0],
            "A's end first",
        )
        assertContentEquals(serializer.serialize(b), client.callbacks[1])

        connection.send(b)
        assertEquals(3, client.callbacks.size, "delivered once, the end is not sent again")
    }

    /** The same for an end Control itself emits (an evaluation error, a stream that finished). */
    @Test
    fun aHeldEndOfControlsOwnIsResentToo() {
        val client = FakeClient()
        var refuse = true
        client.failCallback = { if (refuse) RemoteException("buffer full").also { refuse = false } else null }
        val connection = connection(client)
        val end = StreamElementEndDto(STREAM_A, null)

        connection.send(end)
        connection.send(StreamElementValueDto(STREAM_B, "b".toTString()))

        assertContentEquals(serializer.serialize(end), client.callbacks.first())
        assertEquals(2, client.callbacks.size)
    }

    /**
     * ⚠⚠ Stream ids are callstack hashes, so the same query subscribed again reuses the id. A held
     * end of the OLD subscription must not reach the new one, nor hold back its snapshot: Control
     * forgets the hold when the id is unregistered or registered again (`StreamRegistry`).
     */
    @Test
    fun aHeldEndDoesNotReachAReSubscriptionUnderTheSameId() {
        val client = FakeClient()
        var refuse = true
        client.failCallback = { if (refuse) RemoteException("buffer full") else null }
        val connection = connection(client)

        connection.send(StreamElementEndDto(STREAM_A, InternalErrorDto(internalDetail = "old")))
        assertTrue(client.callbacks.isEmpty(), "held")

        // The app ends S and subscribes the same query again: Control unregisters and re-registers S.
        connection.forgetStream(STREAM_A)
        connection.forgetStream(STREAM_A)
        refuse = false

        val snapshot = StreamElementValueDto(STREAM_A, "new snapshot".toTString())
        connection.send(snapshot)
        assertEquals(1, client.callbacks.size, "the old end is gone")
        assertContentEquals(serializer.serialize(snapshot), client.callbacks.single(), "and the snapshot is not dropped")
        timer.toList().forEach { it.second() }
        assertEquals(1, client.callbacks.size, "nor does the timer bring the old end back")
    }

    /**
     * ⚠⚠ A held end must never reach a NEW app process. The app dies while its end is held; its
     * successor binds and re-subscribes, possibly under the same id. A retry that finds the binder
     * dead drops the hold rather than putting it back, so the next binder never sees it.
     */
    @Test
    fun aHeldEndNeverReachesTheNextAppProcess() {
        val old = FakeClient()
        old.failCallback = { RemoteException("buffer full") }
        val connection = connection(old)
        connection.send(StreamElementEndDto(STREAM_A, InternalErrorDto(internalDetail = "old")))

        // The app process dies; the timed retry finds the binder dead.
        old.alive = false
        old.failCallback = { DeadObjectException("gone") }
        runTimer()

        // A new process binds and re-subscribes under the same id.
        val next = FakeClient()
        connection.clientService = next
        connection.isConnected = true
        val snapshot = StreamElementValueDto(STREAM_A, "new snapshot".toTString())
        connection.send(snapshot)
        timer.toList().forEach { it.second() }

        assertEquals(1, next.callbacks.size, "the new process sees its snapshot and nothing of the old end")
        assertContentEquals(serializer.serialize(snapshot), next.callbacks.single())
    }

    /** A hold is delivered only to the binder it failed against, never to one that replaced it. */
    @Test
    fun aHeldEndIsNeverDeliveredToAReplacementBinder() {
        val old = FakeClient()
        old.failCallback = { RemoteException("buffer full") }
        val connection = connection(old)
        connection.send(StreamElementEndDto(STREAM_A, null))

        val next = FakeClient()
        connection.clientService = next
        timer.toList().forEach { it.second() }
        connection.send(StreamElementValueDto(STREAM_B, "b".toTString()))

        assertEquals(1, next.callbacks.size, "only B")
        assertTrue(timer.size <= 1, "and the dropped hold re-arms nothing new")
    }

    @Test
    fun destroyDropsHeldEndsAndTheTimerDoesNothing() {
        val client = FakeClient()
        var refuse = true
        client.failCallback = { if (refuse) RemoteException("buffer full") else null }
        val connection = connection(client)
        connection.send(StreamElementEndDto(STREAM_A, null))

        connection.destroy()
        connection.clientService = client
        refuse = false
        timer.toList().forEach { it.second() }

        assertTrue(client.callbacks.isEmpty())
    }

    /** ⚠ A quiet app gets its held end anyway: a timer retries with a backoff, a bounded number of times. */
    @Test
    fun aHeldEndIsRetriedWithoutASend() {
        val client = FakeClient()
        var refusals = 3
        client.failCallback = { if (refusals-- > 0) RemoteException("buffer full") else null }
        val connection = connection(client)

        connection.send(StreamElementEndDto(STREAM_A, null)) // refused: held, timer armed
        assertEquals(listOf(1_000L), timer.map { it.first })
        runTimer() // refused again
        assertEquals(listOf(2_000L), timer.map { it.first }, "backing off")
        runTimer() // refused a third time
        runTimer() // delivered
        assertEquals(1, client.callbacks.size)
        assertTrue(timer.isEmpty(), "nothing left to retry")
    }

    @Test
    fun theTimedRetryIsBounded() {
        val client = FakeClient()
        client.failCallback = { RemoteException("buffer full") }
        val connection = connection(client)

        connection.send(StreamElementEndDto(STREAM_A, null))
        var runs = 0
        while (timer.isNotEmpty()) { runTimer(); runs++ }
        assertEquals(HeldEnds.MAX_TIMED_ATTEMPTS, runs)
        assertEquals(30_000L, timer.lastOrNull()?.first ?: 30_000L)
    }

    /** ⚠ Each held end is in flight at most once, however many sends race to retry it. */
    @Test
    fun aHeldEndIsInFlightAtMostOnce() {
        val client = FakeClient()
        var refuse = true
        var reentered = false
        lateinit var connection: AndroidClientConnection
        client.failCallback = { bytes ->
            if (refuse) {
                refuse = false
                RemoteException("buffer full")
            } else {
                // While the held end is being delivered, another send arrives (a second listener).
                if (!reentered) {
                    reentered = true
                    connection.send(StreamElementValueDto(STREAM_B, "concurrent".toTString()))
                }
                null
            }
        }
        connection = connection(client)
        val end = serializer.serialize(StreamElementEndDto(STREAM_A, null))

        connection.send(StreamElementEndDto(STREAM_A, null))
        connection.send(StreamElementValueDto(STREAM_B, "b".toTString()))

        assertEquals(1, client.callbacks.count { it.contentEquals(end) }, "the end went out once")
    }

    /**
     * ⚠ An end for a binder that is gone is not queued: the queue drains into the NEXT process,
     * which may hold a new subscription under the same id.
     */
    @Test
    fun anEndForAGoneBinderIsNotQueuedForTheNextProcess() {
        val dead = FakeClient(alive = false).apply { failCallback = { DeadObjectException("gone") } }
        val connection = connection(dead)

        connection.send(StreamElementEndDto(STREAM_A, null))
        connection.send(StreamElementValueDto(STREAM_B, "queued".toTString()))

        val next = FakeClient()
        connection.clientService = next
        connection.send(StreamElementValueDto(STREAM_B, "after".toTString()))
        assertEquals(1, next.callbacks.size, "only the new message; nothing of A's end")
    }

    /** A local failure of the carrier (not a remote one) fails that message only. */
    @Test
    fun aCarrierThatThrowsLocallyFailsOnlyItsMessage() {
        val client = FakeClient()
        val connection = AndroidClientConnection(
            context = StubContext(),
            serializer = serializer,
            fileDatabase = NoopFileDatabase(),
            clientPackageName = "test.package",
            inlineLimitBytes = LIMIT,
            oversizeCarrier = { _, _ -> throw IllegalStateException("mapReadWrite failed") },
            scheduleRetry = schedule,
        ).also {
            it.clientService = client
            it.isConnected = true
        }

        connection.send(StreamElementValueDto(STREAM_A, "x".repeat(LIMIT * 2).toTString()))
        connection.send(StreamElementValueDto(STREAM_B, "b".toTString()))

        assertEquals(2, client.callbacks.size, "A's end, then B")
        assertSame(client, connection.clientService)
    }

    private fun expectedDetail(size: Int) =
        "IPC: a $size-byte stream element could not be delivered (TransactionTooLargeException)"

    private companion object {
        const val LIMIT = 4096
        const val STREAM_A = 11L
        const val STREAM_B = 22L
    }
}

/** A client binder whose transactions record, or fail as the test says. */
internal class FakeClient(
    var alive: Boolean = true,
) : IClientInterface, android.os.IBinder {
    val callbacks = mutableListOf<ByteArray>()
    var sharedCallbacks = 0
    var failShared: () -> RemoteException? = { null }
    val failedSizes = mutableListOf<Int>()
    val receivedFiles = mutableListOf<String>()
    var failCallback: (ByteArray) -> RemoteException? = { null }
    var failReceiveFile: () -> RemoteException? = { null }

    override fun callback(request: ByteArray) {
        failCallback(request)?.let {
            failedSizes += request.size
            throw it
        }
        callbacks += request
    }

    override fun receiveFile(fileDescriptor: ParcelFileDescriptor?, dataId: ByteArray?) {
        failReceiveFile()?.let { throw it }
        receivedFiles += String(dataId!!)
    }

    override fun receiveSharedMemory(memory: SharedMemory?, dataId: ByteArray?) {}
    override fun receiveLaunchTicket(ticket: android.app.PendingIntent?, ticketId: ByteArray?) {}

    override fun ipcRevision(): Int = org.tenshu.core.common.transport.BinderIpc.CLIENT_REVISION

    override fun callbackShared(message: SharedMemory?) {
        failShared()?.let { throw it }
        sharedCallbacks++
    }

    override fun asBinder(): android.os.IBinder = this

    override fun getInterfaceDescriptor(): String? = null
    override fun pingBinder(): Boolean = alive
    override fun isBinderAlive(): Boolean = alive
    override fun queryLocalInterface(descriptor: String): android.os.IInterface = this
    override fun dump(fd: java.io.FileDescriptor, args: Array<out String>?) {}
    override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<out String>?) {}
    override fun transact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean = true
    override fun linkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int) {}
    override fun unlinkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int): Boolean = true
}

private class StubContext : android.content.ContextWrapper(null) {
    override fun unbindService(conn: android.content.ServiceConnection) {}

    override fun bindService(
        service: android.content.Intent,
        conn: android.content.ServiceConnection,
        flags: Int
    ): Boolean = false
}

private class NoopFileDatabase : AndroidFileDatabase {
    override fun getFile(androidInternalFilePath: String): java.io.File = java.io.File(androidInternalFilePath)
    override fun writeRawByteArray(filePath: String, bytes: ByteArray) {}
    override fun writeByteArray(filePath: String, bytes: TByteArray) {}
    override fun readBytes(filePath: String): TByteArray? = null
}
