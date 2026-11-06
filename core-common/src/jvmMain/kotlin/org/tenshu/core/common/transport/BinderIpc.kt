package org.tenshu.core.common.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.proxy.ProxyGateway
import java.util.UUID

/**
 * Revisions of `IProxyInterface`, as `ipcRevision()` reports them, and the client half of a
 * **cancellable read** over Binder (`core-background-processing.md` §6.2.1).
 *
 * ⚠⚠ The client asks before it uses anything newer than [REVISION_FLOOR], because an app and
 * Control are installed separately and a later release may add methods this one lacks. The floor is
 * revision 1: every shipped Control (1.0.1 onward) answers `ipcRevision()` and serves cancellable
 * reads, so the pre-`ipcRevision()` revision 0 is no longer spoken (1.0.1 re-cut — no build older
 * than this tree exists anywhere).
 *
 * In `jvmMain`, not `androidMain`, so the JVM tests run the very code a device runs:
 * `BinderTaskTransport` and `ProxyService` are thin adapters over [cancellableRead] and
 * [BinderReadEndpoint], and `BinderShapedCancellableReadTest` drives the same two with a blocking
 * call standing in for the Binder transaction — the property that matters, since a cancellable fake
 * once passed against a fix that could never fire on a device.
 */
object BinderIpc {
    /**
     * `call`, `callCancellable`, `cancel`, `sendFile`, + `ipcRevision`, `callCancellableRead`,
     * `cancelRead`. (Revision 0 — the first four alone — was never shipped to a user.)
     */
    const val REVISION_CANCELLABLE_READS = 1

    /**
     * The oldest Control this build talks to, and what a failed `ipcRevision()` query reads as: the
     * cost of under-reading is a plain `callCancellable` for a user-started task; the cost of
     * over-reading is a call to a method that Control may not have.
     */
    const val REVISION_FLOOR = REVISION_CANCELLABLE_READS

    /**
     * + `callCancellableUserInitiated` (`control-background-work-off-fgs.md` §5.1): a task the user
     * started, the only kind Control may hold its foreground service for.
     */
    const val REVISION_USER_INITIATED = 2

    /** What this build's Control implements. */
    const val REVISION = REVISION_USER_INITIATED

    /**
     * Whether a user-started task may be sent as such to a Control at [revision].
     *
     * ⚠ Below [REVISION_USER_INITIATED] the method does not exist, and calling it would return an
     * empty reply that parses as an unreadable answer; the plain `callCancellable` is right there
     * instead, because such a Control promotes for every task anyway.
     */
    fun sendsUserInitiated(revision: Int): Boolean = revision >= REVISION_USER_INITIATED

    // Revisions of `IClientInterface` — the app's side, which Control calls — as the app's own
    // `ipcRevision()` reports them. Every shipped app is at least revision 1, so Control uses
    // `callbackShared` without asking; a later revision is what a future query would gate on.

    /**
     * `callback`, `receiveFile`, `receiveSharedMemory`, `receiveLaunchTicket`, + `ipcRevision`,
     * `callbackShared` (`core-client-resubscription.md` §4.8).
     */
    const val CLIENT_REVISION_SHARED_CALLBACK = 1

    /** What this build's app side implements. */
    const val CLIENT_REVISION = CLIENT_REVISION_SHARED_CALLBACK

    /**
     * The largest serialized message Control sends inline in one `callback` transaction; anything
     * larger goes through `callbackShared`.
     *
     * ⚠ Far below Binder's ~1 MB on purpose: that ceiling is the receiving process's **whole**
     * transaction buffer, shared by every transaction in flight to it, so a message that fits alone
     * can still fail beside a concurrent one (Android then reports "Binder buffer full", and from
     * the sender's side a `DeadObjectException` with the receiver alive). Above this size a
     * `SharedMemory` region costs one allocation and a copy, which is nothing next to the parse.
     */
    const val CALLBACK_INLINE_LIMIT_BYTES = 128 * 1024

    /**
     * The largest message either side accepts through `callbackShared`.
     *
     * ⚠ A bound on what the app allocates for a region, which is the region's own size — so it is
     * the receiving app's defence, not a tuning knob (`ClientCallbackGuard`). 64 MiB is ~50× the
     * largest legitimate message measured (Fit's 1.35 MB list after a six-week import) and far
     * below what crashes an app; Control refuses to send over it, with a typed stream end.
     */
    const val MAX_SHARED_MESSAGE_BYTES = 64 * 1024 * 1024

    /**
     * The client half: the blocking [blockingRead] transaction, awaited so that the caller's
     * cancellation is seen at once and sent as [onewayCancel] — see
     * [awaitBlockingCallOrCancelRemotely] for why a plain `withContext(IO) { transaction }` cannot
     * see it at all. Every Control at or above [REVISION_FLOOR] serves it.
     *
     * ⚠ [readId] is random per call: Control keys a read by (package, id) with no pid, because a
     * `oneway` transaction does not carry one (`getCallingPid()` reads 0), so a per-process counter
     * would let one process's cancel stop another process's read.
     */
    suspend fun cancellableRead(
        blockingRead: (readId: String) -> ByteArray,
        onewayCancel: (readId: String) -> Unit,
        readId: String = UUID.randomUUID().toString(),
    ): ByteArray {
        return awaitBlockingCallOrCancelRemotely(
            context = Dispatchers.IO,
            call = { blockingRead(readId) },
            cancelRemotely = { onewayCancel(readId) },
        )
    }
}

/**
 * The Binder stub's half of a cancellable read — `ProxyService`'s `callCancellableRead` and
 * `cancelRead` delegate here with the kernel-attested calling package and pid.
 *
 * ⚠⚠ The read is registered with `readOwner = null` — keyed by (package, readId), **no pid** —
 * because [cancelRead] arrives as a `oneway` transaction, and a oneway transaction reports the
 * caller's uid but not its pid. A pid-keyed read could never be found by its own cancel. The package
 * is attested, so a cancel cannot cross apps; within one app the random id keeps processes apart.
 * The *session* is still the caller's pid, exactly as a plain `call()`'s is.
 */
class BinderReadEndpoint(private val gateway: ProxyGateway) {

    /** Blocks the Binder thread like `call()`; an empty reply means the client stopped the read. */
    fun callCancellableRead(
        callingPackage: String,
        callingPid: Int,
        request: ByteArray,
        readId: String,
    ): ByteArray = runBlocking {
        gateway.callCancellableRead(
            clientId = callingPackage,
            request = request,
            sessionIdentity = callingPid,
            readId = readId,
            readOwner = null,
        )
    }

    fun cancelRead(callingPackage: String, readId: String) {
        runBlocking { gateway.cancelRead(callingPackage, readOwner = null, readId = readId) }
    }
}
