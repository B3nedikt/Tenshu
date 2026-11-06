package org.tenshu.core.common.transport

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.ServiceConnection
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import app.tenshu.control.android.IClientInterface
import app.tenshu.control.android.IProxyInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.common.ClientCallbackService
import org.tenshu.core.common.app.LaunchTicketInbox
import org.tenshu.core.common.util.toBinaryCacheKey
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.files.util.SharedMemoryCache

/**
 * Run a Binder transaction, translating a **remote-side death** into the same
 * `IllegalStateException` [BinderTaskTransport] already throws for "Proxy not connected".
 *
 * ⚠⚠ Load-bearing: a `proxyService` reference stays a live [android.os.BinderProxy] right up until
 * the proxy process dies, so a null-check cannot see it — the death surfaces *inside* the
 * transaction as a [DeadObjectException]. That is a [RemoteException], **not** an
 * `IllegalStateException`, so it sailed straight past [RemoteBinaryTaskExecutor]'s
 * belt-and-suspenders catch and out of whatever coroutine issued the read. Every `launch { }` that
 * touches IPC without its own handler therefore took the **whole client app down** with the proxy:
 * observed 2026-08-03 as `FATAL EXCEPTION … android.os.DeadObjectException` killing Timeline every
 * time an OOMing Control restarted.
 *
 * Mapping here rather than widening the executor's catch keeps the knowledge platform-local
 * ([DeadObjectException] is an Android type; the executor is `commonMain`) and reuses the fold that
 * already exists: the executor turns an `IllegalStateException` into a typed `TResult.Failure`,
 * which the reachability engines read as "Control is gone" — the accurate signal, and a banner
 * instead of a crash.
 *
 * Connection *state* is deliberately not touched here; the `ServiceConnection` callbacks own the
 * reconnect latch and fire moments later for the same death.
 */
internal inline fun <T> asNotConnected(block: () -> T): T =
    try {
        block()
    } catch (e: DeadObjectException) {
        throw IllegalStateException("Proxy died mid-call", e)
    } catch (e: SecurityException) {
        // Control refused this app (threat model T-C-20): it does not count it as first-party,
        // most likely a signing key the pinned table does not know. Binder delivers the refusal as
        // a SecurityException, which is no RemoteException and would otherwise crash the app on
        // its first read. Folded like a disconnect, so the app shows a banner instead.
        throw IllegalStateException("Control refused this app: ${e.message}", e)
    } catch (e: RemoteException) {
        // Any other Binder-level failure (e.g. TransactionTooLargeException) is likewise an IPC
        // outcome, never a reason to kill the calling app. Named distinctly so the executor's
        // Failure detail does not mislabel it as a disconnect.
        throw IllegalStateException("Proxy call failed: ${e::class.simpleName}", e)
    }

/**
 * Whether a connection that has just landed owes the reconnect announcement
 * (`core-client-resubscription.md` R1, R7).
 *
 * Two different things earn it. A connection that was **lost** — the restarted proxy holds none of
 * this client's streams, so they must be replayed. And a caller that was **turned away** — told
 * "not connected" because Control was absent, disabled, or slower to start than the caller would
 * wait. `Stream.changes` parks its retry on this announcement, so a client that had never been
 * connected, and was therefore never "re"-connected, kept a dead observer behind every listing that
 * failed before the bind landed.
 *
 * ⚠ A first connect nobody was turned away from stays silent: there is nothing to replay and
 * nobody waiting. ⚠ `@Volatile`: written on the Binder main-thread callbacks and from IPC coroutines.
 */
internal class ReconnectEdge {
    @Volatile
    private var owed = false

    fun lost() {
        owed = true
    }

    fun turnedAway() {
        owed = true
    }

    /** The connection landed. True when it must be announced; the debt is then paid. */
    fun landed(): Boolean {
        val announce = owed
        owed = false
        return announce
    }
}

/** Control's Android application id — the bind target, and the package-visibility subject. */
private const val CONTROL_PACKAGE = FirstPartySigning.CONTROL_PACKAGE

/**
 * Whether a caller holding [callerPackages] is Control: it holds Control's package name **and** that
 * package passes [controlIsFirstParty]. The name alone is what a sideloaded app can take while
 * Control is not installed (threat model T-C-20).
 */
internal fun isControlCaller(callerPackages: Array<String>?, controlIsFirstParty: () -> Boolean): Boolean =
    callerPackages?.contains(CONTROL_PACKAGE) == true && controlIsFirstParty()

class BinderTaskTransport internal constructor(
    private val context: Context,
    private val fileDescriptorCache: ParcelFileDescriptorCache,
    private val sharedMemoryCache: SharedMemoryCache,
    controlCheck: (() -> Boolean)?,
) : TaskTransport {

    constructor(
        context: Context,
        fileDescriptorCache: ParcelFileDescriptorCache,
        sharedMemoryCache: SharedMemoryCache,
    ) : this(context, fileDescriptorCache, sharedMemoryCache, controlCheck = null)

    /**
     * Whether the installed `app.tenshu.control.android` is the real Control: signed by Control's
     * Play key, or by the same key as this app (debug builds, the side-channel key). A package
     * name alone is not enough. When Control is not installed, a sideloaded app can take its name
     * (threat model T-C-19, T-C-20). Replaced only by host tests, whose context has no package
     * manager.
     */
    private val controlIsFirstParty: () -> Boolean = controlCheck ?: {
        FirstPartySigning.isFirstParty(context.packageManager, CONTROL_PACKAGE, context.packageName)
    }

    private var proxyService: IProxyInterface? = null

    /**
     * ⚠ The custom setter is load-bearing: this is assigned from five places (connect, disconnect,
     * binding-died, null-binding, unbind) and the R4 notification has to cover all of them, so it
     * hangs off the write rather than off each call site.
     */
    override var isConnected: Boolean = false
        private set(value) {
            val changed = field != value
            field = value
            if (changed) onConnectionChanged?.invoke(value)
        }

    /**
     * Completed by [ServiceConnection.onServiceConnected]; awaited by
     * [awaitConnected]. Recreated on disconnect/unbind so a subsequent await
     * blocks again until the next connection. `@Volatile` because it is written
     * on the Binder main-thread callbacks and read from IPC coroutines.
     */
    @Volatile
    private var connectionSignal = CompletableDeferred<Unit>()

    override var onCallback: ((ByteArray) -> Unit)? = null

    /** See [TaskTransport.setOnReconnected]. */
    private var onReconnected: (() -> Unit)? = null

    /** Whether the next `onServiceConnected` owes [onReconnected] — see [ReconnectEdge]. */
    private val reconnectEdge = ReconnectEdge()

    override fun setOnReconnected(callback: (() -> Unit)?) {
        onReconnected = callback
    }

    /** See [TaskTransport.setOnConnectionChanged]. */
    @Volatile
    private var onConnectionChanged: ((Boolean) -> Unit)? = null

    override fun setOnConnectionChanged(callback: ((Boolean) -> Unit)?) {
        onConnectionChanged = callback
        // Report the current state immediately: a consumer that registers after the bind has
        // already landed would otherwise wait for the next *change*, i.e. for something to break.
        callback?.invoke(isConnected)
    }

    /** Admits Control alone to [callbackStub] — see [ClientCallbackGuard]. */
    private val callbackGuard = ClientCallbackGuard(
        isControlUid = { uid ->
            isControlCaller(context.packageManager.getPackagesForUid(uid), controlIsFirstParty)
        },
    )

    /**
     * ⚠ [received] is closed before the refusal is thrown: the descriptor a stranger sent is ours to
     * close once it is in this process, and leaving it to a finalizer would let a caller spend this
     * app's descriptor table with every rejected call.
     */
    private fun requireControl(method: String, received: java.io.Closeable? = null) {
        val callingUid = Binder.getCallingUid()
        if (callbackGuard.admits(callingUid)) return
        runCatching { received?.close() }
        throw SecurityException("$method: uid $callingUid is not Control")
    }

    private val callbackStub = object : IClientInterface.Stub() {
        override fun callback(request: ByteArray) {
            requireControl("callback")
            onCallback?.invoke(request)
        }

        /** Tells Control this app takes [callbackShared] (`core-client-resubscription.md` §4.8). */
        override fun ipcRevision(): Int = BinderIpc.CLIENT_REVISION

        /**
         * A [callback] too large for one Binder transaction: the same bytes, in a read-only region
         * only the descriptor of which crossed Binder. Copied out and closed at once — the region is
         * this message's alone, nothing else refers to it.
         */
        override fun callbackShared(message: SharedMemory) {
            requireControl("callbackShared", message)
            val bytes = message.use { readSharedMessage(it) }
            onCallback?.invoke(bytes)
        }

        override fun receiveFile(fileDescriptor: ParcelFileDescriptor, dataId: ByteArray) {
            requireControl("receiveFile", fileDescriptor)
            // `put` closes the previous PFD at the same key. Same dataId
            // (= file's absolute path on Control) reused across re-emissions
            // would otherwise orphan FDs and trip Android's `CloseGuard`.
            //
            // The key MUST be derived via `toBinaryCacheKey()` (single source
            // of truth shared with `ParcelFileDescriptorFileByteArrayDto`).
            // `String(dataId)` is lossy/non-injective for arbitrary binary ids
            // and silently collides — see `BinaryCacheKey`.
            fileDescriptorCache.put(dataId.toBinaryCacheKey(), fileDescriptor)
        }

        override fun receiveSharedMemory(memory: SharedMemory, dataId: ByteArray) {
            requireControl("receiveSharedMemory", memory)
            // Same keying contract as `receiveFile` above — derive the key via
            // `toBinaryCacheKey()`, matched on the get side by
            // `SharedMemoryByteArrayDto`.
            sharedMemoryCache.put(dataId.toBinaryCacheKey(), memory)
        }

        /**
         * A launch Control resolved and may not perform itself (`control-cross-app-open.md` §5c).
         *
         * ⚠ Parked rather than fired: this runs on a Binder thread with no user gesture attached,
         * and the whole point of the ticket is that it is sent by the foreground app *at the
         * moment of the tap*. Firing it here would put the start back in a context Android
         * refuses, which is the finding that produced the ticket in the first place.
         */
        override fun receiveLaunchTicket(ticket: PendingIntent, ticketId: ByteArray) {
            requireControl("receiveLaunchTicket")
            LaunchTicketInbox.deliver(String(ticketId), ticket)
        }
    }

    init {
        ClientCallbackService.bridge = { callbackStub }
    }

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val proxy = IProxyInterface.Stub.asInterface(service)
            proxyService = proxy
            isConnected = true
            // After a disconnect the restarted proxy's StreamRegistry is empty, so every stream this
            // client holds is subscribed to nothing (`core-client-resubscription.md`). On a first
            // connect nobody was turned away from there is nothing to replay and nobody waiting.
            //
            // ⚠⚠ Between `isConnected = true` and the signal, in that order. The callback re-arms the
            // executor's announce for the new session, and completing the signal wakes every caller
            // parked in `awaitConnected` — woken first, a caller sent its call on the OLD session's
            // announce and was refused `unannouncedSession` (`docs/tech-debt.md`, 2026-10-28). After
            // `isConnected` because the replay it launches must not see "not connected" and bind
            // again. The executor's resend-once closes the instructions that remain.
            // `BinderTaskTransportRebindTest` pins the order.
            // ⚠ `finally`: a throwing callback must not leave every parked caller waiting out its timeout.
            try {
                if (reconnectEdge.landed()) onReconnected?.invoke()
            } finally {
                connectionSignal.complete(Unit)
            }
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            callbackGuard.forgetControl()
            proxyService = null
            isConnected = false
            connectionSignal = CompletableDeferred()
            reconnectEdge.lost()
        }

        /**
         * The binding is dead rather than merely disconnected — most commonly because the proxy app
         * was **updated or reinstalled**. Distinct from [onServiceDisconnected] in the way that
         * matters: the framework will NOT re-deliver [onServiceConnected] on this binding, so
         * without an explicit unbind + rebind the client stays disconnected **permanently**, not
         * just unsubscribed. Left unhandled this silently outlived every other recovery path.
         */
        override fun onBindingDied(name: ComponentName?) {
            callbackGuard.forgetControl()
            proxyService = null
            isConnected = false
            connectionSignal = CompletableDeferred()
            reconnectEdge.lost()
            runCatching { context.applicationContext.unbindService(this) }
            bind()
        }

        /**
         * The proxy returned a `null` binder from `onBind`. Nothing to recover *to* — rebinding
         * would spin — so only the state is cleared; a later explicit [bind] can still succeed.
         */
        override fun onNullBinding(name: ComponentName?) {
            isConnected = false
        }
    }

    override fun bind() {
        if (isConnected) return
        // NOTE: idempotent against an existing binding — `bindService` with the same
        // ServiceConnection is a no-op while one is live, which is what makes the
        // onBindingDied → unbind → bind recovery safe to call from the callback itself.
        // ⚠⚠ Never bind an app that only holds Control's name: everything this app sends — uploads,
        // location fixes, health data — would go to it (threat model T-C-20). Refused exactly like
        // an absent Control, which is also what `isFirstParty` answers when Control is not there.
        if (!controlIsFirstParty()) {
            reconnectEdge.turnedAway()
            return
        }
        // Control refused this app moments ago; binding again now would only be refused again.
        val refusedAt = refusedAtNanos
        if (refusedAt != null && System.nanoTime() - refusedAt < REFUSAL_BACKOFF_NANOS) {
            reconnectEdge.turnedAway()
            return
        }
        val intent = Intent("$CONTROL_PACKAGE.IProxyInterface")
        intent.setPackage(CONTROL_PACKAGE)
        val bound = context.applicationContext
            .bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            // ⚠⚠ **A `false` return still leaves the ServiceConnection registered**, and the
            // framework requires an `unbindService` to release it — otherwise every retry against
            // an absent Control adds a connection record, and the process eventually logs
            // `ServiceConnectionLeaked`. The return value was previously discarded outright, which
            // is also how the one state the user could act on — Control is not installed — reached
            // the shell as an unlabelled transport outage (`ADR 0025`).
            runCatching { context.applicationContext.unbindService(serviceConnection) }
            // Whoever asked for this bind is about to be told "not connected" (R7).
            reconnectEdge.turnedAway()
        }
    }

    /**
     * Whether Control is installed on this device, answered from the package manager.
     *
     * ⚠⚠ **Requires the caller's `<queries>` entry for [CONTROL_PACKAGE]**, exactly as
     * `rememberControlServicesLauncher` does: under Android 11+ package visibility a missing entry
     * makes the platform answer "not installed" for a Control that is right there. Every
     * first-party app declares one today; a new app that forgets would show an install prompt to
     * a user who has Control — so the entry belongs in the same commit as the app.
     *
     * ⚠ Asked, not remembered. The user can install Control while this app is in the foreground —
     * that is the entire point of showing them the store — so a cached answer would leave the
     * banner arguing with a device that has already been fixed.
     *
     * ⚠ Deliberately **not** `getLaunchIntentForPackage`: that answers *launchable*, which is a
     * different question with a different failure mode (`ADR 0025` §Decision 3).
     */
    override suspend fun controlInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(CONTROL_PACKAGE, 0) }.isSuccess

    /**
     * ⚠⚠ **An unbind ends this client's proxy session, so the next connect owes a replay.** Control's
     * `ProxyService.onUnbind` unregisters every client it served once the last binding goes, and
     * with it each stream that client held — while the client's interactors stay open, and a new
     * subscription to the same chain *reuses* one (`DefaultInteractorProvider`, stream id from the
     * callstack). Drive unbinds in `MainActivity.onDestroy`, so every Activity recreation (a dark-mode
     * switch, a rotation) left the Files listing and the list/grid toggle observing nothing until the
     * process died. The UDS transport already replays here (`hasConnectedBefore`); this is the
     * Binder half. `BinderTaskTransportRebindTest` pins it.
     */
    override fun unbind() {
        if (!isConnected) return
        context.applicationContext.unbindService(serviceConnection)
        isConnected = false
        proxyService = null
        connectionSignal = CompletableDeferred()
        reconnectEdge.lost()
    }

    /**
     * Await the asynchronous `onServiceConnected` callback. Returns immediately
     * when already connected; otherwise suspends on [connectionSignal] up to
     * [timeoutMillis]. This closes the launch-time race where the first IPC call
     * fired between [bind] and `onServiceConnected` and threw "Proxy not connected".
     */
    override suspend fun awaitConnected(timeoutMillis: Long): Boolean {
        if (isConnected) return true
        val signal = connectionSignal
        withTimeoutOrNull(timeoutMillis) { signal.await() }
        // ⚠⚠ A caller that gives up here is told "not connected" while the bind is still in flight
        // — a Control that cold-starts slower than the wait. The connection then lands on its own,
        // and it is a FIRST connect: without this it announced nothing (R7).
        if (!isConnected) reconnectEdge.turnedAway()
        return isConnected
    }

    /**
     * When Control last refused this app (`System.nanoTime`), or `null`. See [refusedByControl].
     */
    @Volatile
    private var refusedAtNanos: Long? = null

    /** [asNotConnected], and a refusal by Control ends the binding — see [refusedByControl]. */
    private inline fun <T> controlCall(block: () -> T): T =
        try {
            asNotConnected(block)
        } catch (e: IllegalStateException) {
            if (e.cause is SecurityException) refusedByControl()
            throw e
        }

    /**
     * Control refused this app (threat model T-C-20): it does not count it as first-party. That
     * should only happen to a first-party app when a signing key is not in the pinned table.
     *
     * ⚠⚠ Left bound, the transport still reads as connected, so every caller runs straight into
     * the refusal and retries at once: measured at ~17 calls/s on an emulator, against ~1/s with
     * Control absent. So the binding is dropped and [bind] holds off for [REFUSAL_BACKOFF_NANOS].
     * Callers then see what an absent Control looks like, and the app shows the same banner.
     */
    private fun refusedByControl() {
        refusedAtNanos = System.nanoTime()
        if (!isConnected) return
        runCatching { context.applicationContext.unbindService(serviceConnection) }
        proxyService = null
        isConnected = false
        connectionSignal = CompletableDeferred()
        reconnectEdge.turnedAway()
    }

    override suspend fun call(request: ByteArray): ByteArray {
        val proxy = proxyService ?: error("Proxy not connected")
        return withContext(Dispatchers.IO) {
            controlCall { proxy.call(request) }
        }
    }

    // Phase L2 (docs/proposals/core-background-processing.md §8): carry the
    // client-minted taskId so the proxy can register it; blocks like call()
    // until the result. cancel() is a separate oneway transaction handled on
    // another binder-pool thread, so it is never serialised behind this.
    override suspend fun callCancellable(request: ByteArray, taskId: String): ByteArray {
        val proxy = proxyService ?: error("Proxy not connected")
        return withContext(Dispatchers.IO) {
            controlCall { proxy.callCancellable(request, taskId) }
        }
    }

    /**
     * [callCancellable] marked user-started (`control-background-work-off-fgs.md` §5.1). Every
     * shipped Control has it; against one whose revision query failed (read as
     * [BinderIpc.REVISION_FLOOR]) it is exactly [callCancellable] — the task still runs, Control
     * just cannot tell it was user-started.
     */
    override suspend fun callCancellableUserInitiated(request: ByteArray, taskId: String): ByteArray {
        val proxy = proxyService ?: error("Proxy not connected")
        if (!BinderIpc.sendsUserInitiated(ipcRevision(proxy))) return callCancellable(request, taskId)
        return withContext(Dispatchers.IO) {
            controlCall { proxy.callCancellableUserInitiated(request, taskId) }
        }
    }

    /**
     * The `ipcRevision()` of the Control behind [proxyService], cached per binder: a rebind after
     * an update or a restart is a new [IProxyInterface], so it is asked again and an older Control
     * reinstalled underneath us is never assumed to have what a newer one did.
     */
    @Volatile
    private var revisionOf: Pair<IProxyInterface, Int>? = null

    private suspend fun ipcRevision(proxy: IProxyInterface): Int {
        revisionOf?.takeIf { it.first === proxy }?.let { return it.second }
        // ⚠ Any failure reads as the floor: the cost of under-reading is a plain task call; the
        // cost of over-reading is a call to a method that Control may not have, whose empty reply
        // would parse as an unreadable answer.
        val revision = withContext(Dispatchers.IO) {
            runCatching { proxy.ipcRevision() }.getOrDefault(BinderIpc.REVISION_FLOOR)
        }
        revisionOf = proxy to revision
        return revision
    }

    /**
     * `cancellableReads { }` over Binder (`core-background-processing.md` §6.2.1).
     *
     * ⚠⚠ The Binder transaction blocks a thread and no coroutine cancellation reaches it, which is
     * the whole defect: the caller of a plain [call] could be cancelled and nothing told Control,
     * so Control walked the read to the end (1.9–8.2 s on a device, `photos-app.md` §37).
     * [BinderIpc.cancellableRead] awaits the transaction as a `Deferred`, so the caller's
     * cancellation is seen at once and becomes a `oneway cancelRead`.
     */
    override suspend fun callCancellableRead(request: ByteArray): ByteArray {
        val proxy = proxyService ?: error("Proxy not connected")
        return BinderIpc.cancellableRead(
            blockingRead = { readId -> controlCall { proxy.callCancellableRead(request, readId) } },
            // Same as [cancel]: the caller is already gone, so a dead proxy is nobody's problem.
            onewayCancel = { readId -> runCatching { proxy.cancelRead(readId) } },
        )
    }

    override fun cancel(taskId: String) {
        // Fire-and-forget: the AIDL method is `oneway`, so this returns
        // immediately and does not block a Cancel button. A missing proxy or
        // an unknown/completed id is a harmless no-op — as is a dead one: this
        // is called from UI callbacks that have no failure channel at all.
        runCatching { proxyService?.cancel(taskId) }
    }

    override fun sendFile(uri: String) {
        val parsedUri = uri.toUri()
        // Try to read the un-redacted original first (T-P4), and fall back to the plain URI on ANY
        // failure. ⚠⚠ The fallback is the whole safety property: asking for the original mutates
        // the URI, and a mutated URI is not covered by the per-URI grant the picker issued — which
        // is exactly how this crashed a document upload with a `SecurityException` the first time.
        // The second open is deliberately unguarded, so a genuine permission problem fails exactly
        // as it did before this existed.
        val descriptor = originalMediaUri(parsedUri)
            ?.let { runCatching { context.contentResolver.openFileDescriptor(it, "r") }.getOrNull() }
            ?: context.contentResolver.openFileDescriptor(parsedUri, "r")
        descriptor?.use {
            // Same reasoning as [cancel]: no caller-visible result, so a dead proxy must not
            // escape as a crash. The bytes are re-sent when the client retries against the
            // restarted proxy.
            runCatching { proxyService?.sendFile(it, uri) }
        }
    }

    /**
     * The same media item, asking the platform **not** to redact it
     * (`timeline-photos.md` T-P4).
     *
     * ⚠⚠ Since Android 10 `MediaStore` strips an image's GPS tags from every copy it hands out
     * unless the reading app holds `ACCESS_MEDIA_LOCATION` *and* asks for the original. The bytes
     * cross to the proxy through the descriptor opened here, in the **client's** process — so this
     * is the one place where a photo either keeps the coordinate it was taken with or silently
     * loses it, whatever any parser downstream does.
     *
     * Returns `null` when there is nothing to try — pre-API-29, no grant, or a URI this must not
     * touch — and the caller then reads the ordinary URI exactly as it always did. Every outcome
     * is the upload proceeding, simply without a coordinate, which is also the honest result for a
     * file that never carried one.
     */
    private fun originalMediaUri(uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        // ⚠⚠ MediaStore URIs only. `setRequireOriginal` appends a query parameter, and the result is
        // a DIFFERENT Uri — which a document provider has issued no grant for. A picked document
        // arrives as `content://com.android.providers.media.documents/document/image%3A…`, and
        // asking it for the original threw `SecurityException: … requires that you obtain access
        // using ACTION_OPEN_DOCUMENT`. The gallery backup — the only path whose photos this feature
        // is about — enumerates `content://media/…`, so the narrowing costs it nothing.
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!shouldRequestOriginal(uri.authority, granted)) return null
        return runCatching { MediaStore.setRequireOriginal(uri) }.getOrNull()
    }

    private companion object {
        /** 60 s: how long [bind] holds off after Control refused this app. */
        const val REFUSAL_BACKOFF_NANOS = 60_000_000_000L
    }
}

/**
 * Whether asking for the un-redacted original is applicable to a URI at all (T-P4).
 *
 * Extracted as a pure function because this predicate is what was wrong: without the authority
 * test, a picked **document** URI got a query parameter appended, and the mutated URI was not
 * covered by the grant the picker had issued — `SecurityException`, on the main thread, on every
 * file upload. Everything else in [BinderTaskTransport.originalMediaUri] needs a `Context`; this
 * does not, so it can be pinned.
 *
 * ⚠ `MediaStore.AUTHORITY` only. The gallery backup — whose photos are the entire point of the
 * feature — enumerates `content://media/…`, so narrowing to it costs nothing that matters.
 */
internal fun shouldRequestOriginal(authority: String?, mediaLocationGranted: Boolean): Boolean =
    authority == MediaStore.AUTHORITY && mediaLocationGranted

/** The bytes of a `callbackShared` region. */
@android.annotation.SuppressLint("NewApi")
private fun readSharedMessage(memory: SharedMemory): ByteArray {
    ClientCallbackGuard.requireSharedMessageSize(memory.size)
    val buffer = memory.mapReadOnly()
    try {
        return ByteArray(memory.size).also { buffer.get(it) }
    } finally {
        SharedMemory.unmap(buffer)
    }
}
