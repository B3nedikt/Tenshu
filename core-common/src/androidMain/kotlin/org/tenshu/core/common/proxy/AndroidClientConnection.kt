package org.tenshu.core.common.proxy

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import app.tenshu.control.android.IClientInterface
import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.TError
import org.tenshu.core.TValue
import org.tenshu.core.TenshuException
import org.tenshu.core.id
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.client.FileByteArray
import org.tenshu.core.common.client.InMemoryByteArray
import org.tenshu.core.common.client.SharedMemoryByteArrayDto
import org.tenshu.core.common.impl.InternalErrorDto
import org.tenshu.core.common.impl.StreamElementEndDto
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.parser.Token
import org.tenshu.core.common.parser.TokenString
import org.tenshu.core.common.parser.append
import org.tenshu.core.common.parser.buildTokenString
import org.tenshu.core.common.transport.BinderIpc
import java.nio.ByteBuffer

/**
 * Control's half of the Binder channel **to** one client app: stream elements go out through
 * `IClientInterface.callback`, byte payloads ahead of them through `receiveFile` /
 * `receiveSharedMemory` ([tokenize]).
 *
 * ⚠⚠ **Only a dead binder is a dead connection** (`core-client-resubscription.md` §4.8, R9). A
 * transaction can fail with the binding perfectly alive — a parcel over Binder's ~1 MB ceiling
 * (`TransactionTooLargeException`), or the receiver's buffer momentarily full (which Android
 * reports as a `DeadObjectException` "on a small parcel"). This class used to treat every
 * `RemoteException` as death: it nulled [clientService] and queued the message, and since the binding
 * was alive no `onServiceConnected` ever came to undo it — every later send queued, and every file
 * token went into a payload whose descriptor had never been sent. One oversize Fit list stream took
 * every workout detail down with it (2026-10-23). Now:
 *  - death is a [DeadObjectException] **and** a binder that no longer answers `isBinderAlive`;
 *  - any other failure fails **that message only**: a stream element is replaced by a small
 *    `StreamElement.End` carrying a typed error, so the one subscription ends with a failure its
 *    client sees once and retries with a backoff that grows to 15 s while the refusals continue
 *    (`TList.flow()` resets it only on a delivered element), and nothing is queued for a replay
 *    that would fail the same way; an end that does not go out is held and resent;
 *  - a token is written into a payload only after its delivery went out ([tokenize]).
 *
 * And a message too large for one transaction still arrives: above [inlineLimitBytes] it travels in
 * a `SharedMemory` region through `callbackShared` (`IClientInterface` revision 1), with only the
 * descriptor in the transaction. Every shipped app has that method (1.0.1 re-cut: no older app
 * exists), so it is not asked first; a device with no `SharedMemory` gets the inline `callback` —
 * and, if that fails, the typed end above.
 */
class AndroidClientConnection internal constructor(
    private val context: Context,
    private val serializer: BinarySerializer,
    private val fileDatabase: AndroidFileDatabase,
    private val clientPackageName: String,
    /** Above this, a message goes through `callbackShared` when the app has it — see [deliver]. */
    private val inlineLimitBytes: Int,
    private val oversizeCarrier: OversizeCarrier,
    /** Where [heldEnds] schedules its timed retries. */
    scheduleRetry: (delayMs: Long, task: () -> Unit) -> Unit,
) : ClientConnection {

    constructor(
        context: Context,
        serializer: BinarySerializer,
        fileDatabase: AndroidFileDatabase,
        clientPackageName: String,
    ) : this(
        context,
        serializer,
        fileDatabase,
        clientPackageName,
        BinderIpc.CALLBACK_INLINE_LIMIT_BYTES,
        SharedMemoryCarrier,
        HeldEnds.defaultSchedule,
    )

    @Volatile
    internal var clientService: IClientInterface? = null

    @Volatile
    var isConnected: Boolean = false
    private val messageQueue = mutableListOf<ByteArray>()
    private var deathRecipient: IBinder.DeathRecipient? = null

    private val serviceConnection: ServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "onServiceConnected for $clientPackageName")
            val boundService = IClientInterface.Stub.asInterface(service)
            // ⚠ Before the new binder is published: no retry may find it with an old process's end.
            heldEnds.clear()
            clientService = boundService
            isConnected = true

            // Proactive death detection. `onServiceDisconnected` is fired by
            // the framework but can lag behind the actual binder death; the
            // window between "remote process killed" and "framework notices"
            // is exactly when a stream emission would hit
            // `DeadObjectException` from `transactNative` (see the
            // photo-details crash). `linkToDeath` lets us flip
            // `clientService` to null the moment the kernel notices.
            val recipient = IBinder.DeathRecipient {
                Log.w(TAG, "Binder for $clientPackageName died; clearing service ref")
                clientService = null
                isConnected = false
                // Self-heal: attempt to rebind. Framework will fire
                // `onServiceDisconnected` shortly after this anyway, but
                // proactively kicking the rebind shortens the window where
                // queued messages stall.
                tryRebind()
            }
            deathRecipient = recipient
            try {
                service.linkToDeath(recipient, 0)
            } catch (e: RemoteException) {
                // Binder already dead at the moment of bind — extremely
                // unlikely but harmless to ignore; the connection will
                // fail on first send and tear down via the normal path.
                Log.w(TAG, "linkToDeath failed on connect for $clientPackageName", e)
            }

            drainQueue(boundService)
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected for $clientPackageName")
            isConnected = false
            clientService = null
            tryRebind()
        }
    }

    override fun init() {
        bind()
    }

    private fun bind() {
        val intent = Intent("app.tenshu.control.android.IClientInterface")
        intent.setPackage(clientPackageName)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Re-bind after binder death. `bindService` is idempotent against an
     * existing connection: if the framework hasn't torn down the old
     * binding yet the call is a no-op; once the remote process restarts
     * the framework drives `onServiceConnected` and the queue drains.
     */
    private fun tryRebind() {
        try {
            bind()
        } catch (e: Throwable) {
            // SecurityException / IllegalStateException in production;
            // RuntimeException from the host-test stub library that
            // doesn't mock `Intent.setPackage` / `Context.bindService`.
            // Either way: rebinding is best-effort recovery, never a
            // path that should kill the calling stream listener.
            warn("Rebind failed for $clientPackageName", e)
        }
    }

    override fun destroy() {
        context.unbindService(serviceConnection)
        isConnected = false
        clientService = null
        deathRecipient = null
        heldEnds.close()
        synchronized(messageQueue) {
            messageQueue.clear()
        }
    }

    /**
     * Push a launch [ticket] to this client (`control-cross-app-open.md` §5c, Option B).
     *
     * ⚠ Returns whether the transaction landed. A queued-for-later ticket would be worse than a
     * refusal: the gesture it belongs to is over by the time a later bind drains it, so this
     * deliberately does **not** fall back to [messageQueue] the way [send] does.
     */
    fun sendLaunchTicket(ticket: Any, ticketId: ByteArray): Boolean {
        val boundService = clientService ?: return false
        val pendingIntent = ticket as? android.app.PendingIntent ?: return false
        return transact("receiveLaunchTicket($clientPackageName)", boundService) {
            boundService.receiveLaunchTicket(pendingIntent, ticketId)
        } == Outcome.Delivered
    }

    override fun send(data: Data) {
        // An end that did not go out goes before anything else, and its stream's later elements
        // do not go at all: the client is still waiting on that stream (see [HeldEnds]).
        heldEnds.retry()
        val element = data as? StreamElement<*>
        if (element != null) {
            val streamId = element.streamId.get()
            if (element is StreamElement.End) {
                heldEnds.forget(streamId) // this end supersedes the held one
            } else if (heldEnds.isHeld(streamId)) {
                warn("Dropped a ${data.id} for $clientPackageName: stream $streamId has an end still to deliver")
                return
            }
        }

        // How many out-of-band deliveries this message's tokens point at. A message that carries
        // any is never queued: its descriptors went to the process that just died, and a replay
        // into the next one would carry tokens that resolve to nothing there.
        var tokens = 0
        val bytes = try {
            serializer.serialize(
                data = data,
                // Delegate to the shared [tokenize] method so the sync-call reply
                // path ([ProxyGateway.call]) and this stream-push path use a
                // single source of truth for OOB byte wrapping.
                interceptValueTokenization = { value -> tokenize(value)?.also { tokens++ } },
            )
        } catch (e: TenshuException) {
            // [tokenize] could not deliver a file this message refers to.
            refuse(data, e.error)
            return
        }

        if (bytes.size > inlineLimitBytes) {
            info("callback($clientPackageName): ${bytes.size} bytes over the ${inlineLimitBytes}-byte inline limit — ${describe(data)}")
        }

        val target = clientService
        when (val outcome = if (target == null) Outcome.NoService else deliverTo(target, bytes)) {
            Outcome.Delivered -> Unit
            Outcome.NoService, Outcome.Dead ->
                if (element is StreamElement.End) {
                    // ⚠ Never queued: the queue drains into the NEXT app process, which has no such
                    // subscription — and may have a new one under the same id (ids are callstack
                    // hashes) that this end would kill. That process re-subscribes on its own (R1).
                    warn("Dropped the end of stream ${element.streamId.get()} for $clientPackageName: the binder is gone")
                } else if (tokens == 0) {
                    synchronized(messageQueue) { messageQueue.add(bytes) }
                } else {
                    warn("Dropped a ${bytes.size}-byte message for $clientPackageName: its $tokens out-of-band payload(s) went to a binder that is gone")
                }

            is Outcome.Failed ->
                if (element is StreamElement.End) {
                    holdEnd(element.streamId.get(), bytes, outcome, target!!)
                } else {
                    refuse(data, transportError(bytes.size, outcome.label))
                }
        }
    }

    private val heldEnds = HeldEnds(
        deliver = { target, bytes ->
            val service = target as IClientInterface
            // Only to the binder the end failed against: a successor is a new app process.
            if (clientService !== service) {
                HeldEnds.Result.GONE
            } else {
                when (deliverTo(service, bytes)) {
                    Outcome.Delivered -> HeldEnds.Result.DELIVERED
                    Outcome.NoService, Outcome.Dead -> HeldEnds.Result.GONE
                    is Outcome.Failed -> HeldEnds.Result.FAILED
                }
            }
        },
        schedule = scheduleRetry,
    )

    private fun holdEnd(streamId: Long, bytes: ByteArray, outcome: Outcome, target: IClientInterface) {
        warn("End of stream $streamId for $clientPackageName not delivered (${outcome.label}); held for retry")
        heldEnds.hold(streamId, bytes, target)
    }

    /** Control registered [streamId] afresh or unregistered it — see [HeldEnds] for why it matters. */
    override fun forgetStream(streamId: Long) {
        heldEnds.forget(streamId)
    }

    /**
     * One message, in one transaction. Never throws a remote error; the [Outcome] says what became
     * of it.
     *
     * ⚠⚠ Over [inlineLimitBytes] the bytes go out of band through `callbackShared`, which every
     * shipped app implements ([BinderIpc.CLIENT_REVISION_SHARED_CALLBACK] is the floor). ⚠ A
     * method added in a LATER revision must be gated on the app's `ipcRevision()`: a transaction
     * code an older stub does not know is answered with an empty reply that reads as success, so
     * the message would vanish without a trace. A device with no `SharedMemory` (below API 27)
     * gets the inline `callback`, which may still fit, and if it does not, fails as a
     * [Outcome.Failed] like any other.
     */
    private fun deliver(bytes: ByteArray): Outcome = deliverTo(clientService ?: return Outcome.NoService, bytes)

    /** [deliver] to one given binder. */
    private fun deliverTo(service: IClientInterface, bytes: ByteArray): Outcome {
        // The app refuses a region over this (a caller-sized allocation is the one thing an
        // out-of-band message adds), so nothing larger is worth a transaction.
        if (bytes.size > BinderIpc.MAX_SHARED_MESSAGE_BYTES) {
            return Outcome.Failed("over the ${BinderIpc.MAX_SHARED_MESSAGE_BYTES}-byte message cap")
        }
        if (bytes.size > inlineLimitBytes) {
            var carried = false
            val outcome = transact("callbackShared($clientPackageName, ${bytes.size} bytes)", service) {
                carried = oversizeCarrier.carry(service, bytes)
            }
            if (outcome != Outcome.Delivered || carried) return outcome
        }
        return transact("callback($clientPackageName, ${bytes.size} bytes)", service) {
            service.callback(bytes)
        }
    }

    private fun drainQueue(service: IClientInterface) {
        val queued = synchronized(messageQueue) {
            messageQueue.toList().also { messageQueue.clear() }
        }
        queued.forEach { message ->
            val outcome = if (clientService === service) deliver(message) else Outcome.NoService
            // ⚠ Never re-queued: a message that fails on a binder that has only just connected
            // will fail the same way on the next one.
            if (outcome != Outcome.Delivered) warn("Dropped a queued message for $clientPackageName: ${outcome.label}")
        }
    }

    /**
     * [data] could not go out, though the binding is alive. If it is a stream element, its stream
     * is ended with [error] — a small transaction, so it fits where the element did not — and the
     * client's subscription sees a `TResult.Failure` instead of waiting for ever; an end that does
     * not go out either is held and resent ([HeldEnds]). Anything else is dropped with a log
     * line; the element itself is never queued, because a replay would fail the same way.
     */
    private fun refuse(data: Data, error: TError) {
        val element = data as? StreamElement<*>
        if (element == null || element is StreamElement.End) {
            warn("Dropped an undeliverable ${data.id} for $clientPackageName: ${error.id}")
            return
        }
        val streamId = element.streamId.get()
        val end = serializer.serialize(data = StreamElementEndDto(streamId, error))
        val target = clientService
        val outcome = if (target == null) Outcome.NoService else deliverTo(target, end)
        warn("Ended stream $streamId of $clientPackageName: its element could not be delivered (${error.id}); end: ${outcome.label}")
        when (outcome) {
            Outcome.Delivered -> Unit
            // No binder: dropped, never queued — the next process has no such subscription (see send).
            Outcome.NoService, Outcome.Dead -> Unit
            is Outcome.Failed -> holdEnd(streamId, end, outcome, target!!)
        }
    }

    /**
     * Android impl of [ClientConnection.tokenize]: wrap each
     * [FileByteArray] / [InMemoryByteArray] in a `ParcelFileDescriptor` or
     * `SharedMemory` region, deliver it OOB via the proxy's bound
     * `clientService` (the existing `receiveFile` / `receiveSharedMemory`
     * AIDL calls the stream transport already relies on), and return a
     * small [TokenString] substitute the serializer writes into the
     * payload instead of the inline bytes.
     *
     * Called from two sites that share this implementation:
     * - [send] (stream-push path).
     * - `ProxyGateway.call` (sync-call reply path, post §2.4 Phase 1) — so
     *   a `.search { … }.select { relations(DataProperty) }.get()` chain
     *   carrying a 12 MB JPEG no longer blows the Binder reply parcel's
     *   ~1 MB cap. See `docs/proposals/core-chain-entity-typing.md` §2.4.
     *
     * ⚠⚠ **A token is returned only once its delivery went out.** A token whose descriptor never
     * arrived is the worst outcome available: the payload parses, and the read fails later, far
     * away, as *descriptor not found in cache*. So:
     * - an [InMemoryByteArray] that cannot go out of band (no binding, a failed transaction, or a
     *   device below API 27 with no `SharedMemory`) is written **inline** — the bytes are already
     *   in this heap, and a message that grows too large then fails as a whole;
     * - a [FileByteArray] has no inline fallback worth the name (it can be gigabytes), so an
     *   undeliverable one throws a typed [TenshuException] and fails the message it was part of.
     *
     * Anything other than the two byte-array shapes falls through to
     * inline serialization (returns `null`).
     */
    override fun tokenize(value: TValue): TokenString? {
        return when (value) {
            is FileByteArray -> {
                val fileId = value.fileId
                val service = clientService
                    ?: throw undeliverableFile("no callback binding to $clientPackageName")
                val file = fileDatabase.getFile(String(fileId))

                val outcome = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    .use { fileDescriptor ->
                        transact("receiveFile($clientPackageName)", service) {
                            service.receiveFile(fileDescriptor, fileId)
                        }
                    }
                if (outcome != Outcome.Delivered) throw undeliverableFile("receiveFile: ${outcome.label}")

                buildTokenString {
                    append(Token.ByteArrayToken(value.fileId))
                }
            }

            is InMemoryByteArray -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
                val service = clientService ?: return null
                val uniqueIdBytes = value.uniqueId

                val outcome = if (value is SharedMemoryByteArrayDto) {
                    transact("receiveSharedMemory($clientPackageName)", service) {
                        service.receiveSharedMemory(value.sharedMemory, uniqueIdBytes)
                    }
                } else {
                    sendUsingSharedMemory(service, uniqueId = uniqueIdBytes, byteArray = value.get())
                }
                if (outcome != Outcome.Delivered) return null

                buildTokenString {
                    append(Token.ByteArrayToken(uniqueIdBytes))
                }
            }

            else -> null
        }
    }

    /** What became of one transaction. */
    internal sealed interface Outcome {
        /** For log lines and error details. */
        val label: String

        data object Delivered : Outcome {
            override val label get() = "delivered"
        }

        /** There was no binding to send on. */
        data object NoService : Outcome {
            override val label get() = "no binding"
        }

        /** The remote process is gone: the service ref is cleared and a rebind is under way. */
        data object Dead : Outcome {
            override val label get() = "binder dead"
        }

        /**
         * It did not go out and the binder is alive — too large, the buffer full, over
         * [BinderIpc.MAX_SHARED_MESSAGE_BYTES], or the app's stub threw.
         */
        data class Failed(override val label: String) : Outcome
    }

    /**
     * Run one AIDL transaction on [service] without letting a remote error escape into the
     * stream-listener coroutine that emitted the data. Never throws.
     *
     * ⚠⚠ Death is decided by the binder, not by the exception type. A [DeadObjectException] is
     * also what Android throws when a transaction fails "on a small parcel" — the receiver's
     * buffer full — with the remote process alive; only a binder that no longer answers
     * `isBinderAlive` is dead. Every other failure ([android.os.TransactionTooLargeException]
     * first) leaves the connection exactly as it was.
     */
    private inline fun transact(label: String, service: IClientInterface, block: () -> Unit): Outcome {
        return try {
            block()
            Outcome.Delivered
        } catch (e: RemoteException) {
            if (e is DeadObjectException && !binderAlive(service)) {
                warn("$label failed: remote binder dead", e)
                // Only the binder that died: a fresh connection may already have replaced it.
                if (clientService === service) {
                    clientService = null
                    isConnected = false
                }
                tryRebind()
                Outcome.Dead
            } else {
                warn("$label failed with ${e::class.simpleName}; the binding is alive and stays up", e)
                Outcome.Failed(e::class.simpleName ?: "RemoteException")
            }
        } catch (e: RuntimeException) {
            // The app's stub threw (a `SecurityException` from its caller check, a parse failure
            // Binder carried back), or the out-of-band carrier failed locally. Either way an IPC
            // outcome for this one message, never a reason to kill the emitting listener.
            warn("$label failed with ${e::class.simpleName}; the binding stays up", e)
            Outcome.Failed(e::class.simpleName ?: "RuntimeException")
        }
    }

    private fun binderAlive(service: IClientInterface): Boolean =
        try {
            service.asBinder().isBinderAlive
        } catch (_: Throwable) {
            false
        }

    private fun undeliverableFile(reason: String) = TenshuException(
        InternalErrorDto(internalDetail = "IPC: a file payload could not be handed to the client ($reason)")
    )

    private fun transportError(size: Int, reason: String): TError = InternalErrorDto(
        internalDetail = "IPC: a $size-byte stream element could not be delivered ($reason)"
    )

    /**
     * `Log.w` wrapper that survives host-side unit tests where
     * `android.util.Log` is not mocked. The Android stub library throws
     * "method not mocked" by default; the recoverable path here doesn't
     * depend on logging actually happening.
     */
    private fun warn(message: String, e: Throwable? = null) {
        try {
            if (e != null) Log.w(TAG, message, e) else Log.w(TAG, message)
        } catch (_: Throwable) {
            // Host test runtime — swallow.
        }
    }

    private fun info(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
            // Host test runtime — swallow.
        }
    }

    /** Which message an oversize one is, for the log line — the stream, and what its payload holds. */
    private fun describe(data: Data): String {
        val element = data as? StreamElement<*> ?: return data.id
        val payload = (element as? StreamElement.Value<*>)?.payload
        val rows = (payload as? TList<*>)?.toList()
        val what = when {
            rows != null -> "${rows.size} × ${(rows.firstOrNull() as? Data)?.id ?: "nothing"}"
            payload != null -> payload.id
            else -> element.id
        }
        return "stream ${element.streamId.get()}, $what"
    }

    @SuppressLint("NewApi")
    private fun sendUsingSharedMemory(service: IClientInterface, uniqueId: ByteArray, byteArray: ByteArray): Outcome {
        val sharedMemory = SharedMemory.create(TAG, byteArray.size)

        var byteBuffer: ByteBuffer? = null
        try {
            byteBuffer = sharedMemory.mapReadWrite()
            byteBuffer.put(byteArray)
        } finally {
            if (byteBuffer != null) {
                SharedMemory.unmap(byteBuffer)
            }
        }

        sharedMemory.setProtect(OsConstants.PROT_READ)

        return transact("receiveSharedMemory($clientPackageName, copy)", service) {
            service.receiveSharedMemory(sharedMemory, uniqueId)
        }
    }

    private companion object {
        const val TAG = "AndroidClientConnection"
    }
}

/**
 * How a message over the inline limit leaves Control. A seam so the host tests can drive
 * [AndroidClientConnection]'s decisions; the stub `android.jar` has no working `SharedMemory`.
 */
internal fun interface OversizeCarrier {
    /**
     * Hand [bytes] to [service] in one transaction that carries only a descriptor. `false` when
     * this device cannot, and the caller sends inline instead; a remote failure is thrown as the
     * transaction threw it.
     */
    fun carry(service: IClientInterface, bytes: ByteArray): Boolean
}

/**
 * The production [OversizeCarrier]: a read-only `SharedMemory` region through `callbackShared`.
 *
 * ⚠ Closed as soon as the transaction returns. Binder duplicated the descriptor into the app's
 * process during the (synchronous) call, so the app's copy outlives this one, and keeping ours open
 * would hold a region per oversize emission until a finalizer got to it.
 */
internal object SharedMemoryCarrier : OversizeCarrier {
    @SuppressLint("NewApi")
    override fun carry(service: IClientInterface, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return false
        // ⚠ Every local step can fail — `create` and `mapReadWrite` with an `ErrnoException`
        // (out of address space, the descriptor table full), `setProtect` with an argument
        // exception — and none of that is a remote failure. It is "this device cannot carry this
        // one", so the caller sends inline, exactly as when `create` alone failed.
        var memory: SharedMemory? = null
        try {
            memory = SharedMemory.create("tenshu-callback", bytes.size)
            val buffer = memory.mapReadWrite()
            try {
                buffer.put(bytes)
            } finally {
                SharedMemory.unmap(buffer)
            }
            memory.setProtect(OsConstants.PROT_READ)
        } catch (_: android.system.ErrnoException) {
            memory?.close()
            return false
        } catch (_: IllegalArgumentException) {
            memory?.close()
            return false
        }
        try {
            service.callbackShared(memory)
        } finally {
            memory.close()
        }
        return true
    }
}
