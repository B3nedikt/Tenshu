package app.tenshu.sdk.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/**
 * Where this page stands with respect to the one-runtime-per-origin rule (`docs/web/architecture.md`
 * "Cross-tab"). [wire] is what the JS surface returns.
 */
internal enum class LeaseState(val wire: String) {
    /** Nothing has asked for the runtime yet. */
    IDLE("idle"),
    ACQUIRING("acquiring"),
    /** This page holds the lock and runs the runtime. */
    HELD("held"),
    /** Another tab holds it; this page never opened a store. */
    ELSEWHERE("elsewhere"),
    /** No Web Locks in this browser — the runtime runs unguarded, with the cursor reset per load. */
    UNGUARDED("unguarded"),
    /** This page held it and gave it up ("Use here" in another tab); its stores are closed. */
    DISPLACED("displaced"),
}

/** A held lock. [release] frees it; idempotent. */
internal fun interface LockHandle {
    fun release()
}

/** The Web Locks seam — a fake in tests, `navigator.locks` in production. */
internal interface LockBackend {
    val supported: Boolean

    /**
     * Take [name] exclusively **without waiting**: a handle when granted, null when another context
     * holds it. [onLost] runs if the lock is later taken away (`steal`).
     */
    suspend fun tryAcquire(name: String, onLost: () -> Unit): LockHandle?

    /** Suspend until [name] is free (acquire, then release at once). */
    suspend fun awaitFree(name: String)

    /** Take [name] from its holder (`steal: true`), then release at once. */
    suspend fun steal(name: String)
}

/** The cross-tab message seam — a `BroadcastChannel` in production. */
internal interface LeaseChannel {
    fun post(message: String)
    /** Returns the unsubscribe function. */
    fun listen(onMessage: (String) -> Unit): () -> Unit
}

/**
 * One runtime per origin: the tab holding [LOCK_NAME] runs `JsRuntime`, every other tab opens no
 * store at all.
 *
 * ⚠⚠ Why: each tab's runtime keeps its own in-memory copy of the IndexedDB stores, and the last
 * write wins on disk. The sync cursors are durable, so a lagging tab would leave an older data
 * row behind a newer cursor saved by a faster tab — and a reload would then never re-pull it.
 * Exclusivity makes that unrepresentable instead of eventually healed.
 *
 * "Use here" ([useHere]) asks the holder to step down over [LeaseChannel]. The holder answers
 * [YIELDING] at once, closes its stores — flushing every queued IDB write first, however long that
 * takes (a multi-MB file flush) — and only then releases, so the next holder's hydration reads
 * everything the old one wrote. Only a holder that does not even acknowledge within [ackTimeoutMs]
 * (a frozen background tab — iOS Safari freezes them routinely — or a hung page) has the lock
 * **stolen**; an acknowledged one gets [yieldTimeoutMs].
 *
 * ⚠⚠ A stolen tab ABANDONS its queue rather than flushing it (`onLost`), and its stores' write
 * fence (`StoreFence.kt`) refuses whatever it had already started: the new holder claimed every
 * database on open, so the old tab's late writes cannot land behind the new tab's cursor.
 *
 * ⚠⚠ Embedding: the apex hosts Drive at `/drive/files` inside its own runtime-holding shell. The
 * embedded Drive must talk to that PARENT's gateway (`mountApexProxy`), never mount a hidden apex
 * iframe of its own — that iframe would be a second contender for this lock, under the same
 * origin, against the page that embeds it.
 */
internal class RuntimeLease(
    private val backend: LockBackend,
    private val channel: LeaseChannel?,
    private val scope: CoroutineScope,
    private val name: String = LOCK_NAME,
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    private val yieldTimeoutMs: Long = YIELD_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow(LeaseState.IDLE)
    val state: StateFlow<LeaseState> = _state.asStateFlow()

    private var handle: LockHandle? = null
    private var onYield: (suspend () -> Unit)? = null
    private var onLost: (() -> Unit)? = null

    /**
     * Ask for the runtime. Idempotent: a second call returns the first call's outcome.
     *
     * [onYield] is the holder's clean shutdown — it must leave nothing queued for IndexedDB, because
     * the lock is released the moment it returns. [onLost] runs instead when the lock was stolen:
     * it must DROP the queue, not flush it — another tab already owns the databases.
     */
    suspend fun acquire(onLost: () -> Unit = {}, onYield: suspend () -> Unit): LeaseState {
        if (_state.value != LeaseState.IDLE) return _state.value
        // Set before any outcome: an UNGUARDED runtime can still be fenced out ([fencedOut]).
        this.onYield = onYield
        this.onLost = onLost
        if (!backend.supported) {
            _state.value = LeaseState.UNGUARDED
            return LeaseState.UNGUARDED
        }
        _state.value = LeaseState.ACQUIRING
        val granted = try {
            backend.tryAcquire(name) { lost() }
        } catch (failure: Throwable) {
            // A browser that has the API but refuses it here (an insecure or opaque context) is
            // treated as one that lacks it — the conservative fallback, never a silent HELD.
            app.tenshu.diagnostics.Diagnostics.w("RuntimeLease", "lock request failed: $failure")
            _state.value = LeaseState.UNGUARDED
            return LeaseState.UNGUARDED
        }
        if (granted == null) {
            _state.value = LeaseState.ELSEWHERE
            return LeaseState.ELSEWHERE
        }
        handle = granted
        channel?.listen { message ->
            if (message == RELEASE_REQUEST && _state.value == LeaseState.HELD) {
                // ⚠ Acknowledge FIRST, before the (possibly long) flush: the requester steals only
                // from a holder that never answers, never from one that is busy handing over.
                channel.post(YIELDING)
                scope.launch { yieldNow() }
            }
        }
        _state.value = LeaseState.HELD
        return LeaseState.HELD
    }

    /**
     * Step down: refuse further work FIRST (so nothing new is queued), close the stores, release.
     * ⚠ The release is last — releasing before the flush would let the next tab hydrate without
     * this tab's last writes.
     */
    suspend fun yieldNow() {
        if (_state.value != LeaseState.HELD) return
        _state.value = LeaseState.DISPLACED
        try {
            onYield?.invoke()
        } finally {
            handle?.release()
            handle = null
        }
    }

    /**
     * "Use here": ask the holder to step down and suspend until the lock is free. The caller then
     * reloads the page, whose fresh runtime takes the lock. A no-op while this page holds it.
     */
    suspend fun useHere(): Unit = coroutineScope {
        if (_state.value == LeaseState.HELD || _state.value == LeaseState.UNGUARDED) return@coroutineScope
        // No Web Locks: there is no lock to hand over — the reload that follows claims the fence,
        // which fences the other tab out in turn ([fencedOut]).
        if (!backend.supported) return@coroutineScope
        val acknowledged = CompletableDeferred<Unit>()
        val stopListening = channel?.listen { message -> if (message == YIELDING) acknowledged.complete(Unit) }
        try {
            takeOver(acknowledged)
        } finally {
            stopListening?.invoke()
        }
    }

    private suspend fun takeOver(acknowledged: CompletableDeferred<Unit>): Unit = coroutineScope {
        // Started before the request, so a lock that is already free (the holder crashed or
        // closed) is noticed at once rather than after the ack timeout.
        val freed = async { backend.awaitFree(name) }
        channel?.post(RELEASE_REQUEST)

        val first = withTimeoutOrNull(ackTimeoutMs) {
            select {
                freed.onAwait { true }
                acknowledged.onAwait { false }
            }
        }
        if (first == true) return@coroutineScope
        if (first == false && withTimeoutOrNull(yieldTimeoutMs) { freed.await() } != null) {
            return@coroutineScope
        }
        freed.cancel()
        app.tenshu.diagnostics.Diagnostics.w(
            "RuntimeLease",
            if (first == null) "holder did not answer — stealing" else "holder did not finish yielding — stealing",
        )
        backend.steal(name)
    }

    /**
     * A store's write was refused by the fence (`StoreFence.kt`): another runtime claimed the
     * databases. Stop exactly as if stolen — drop the queue, refuse further SDK calls, surface
     * "open in another tab" — in EVERY mode. ⚠⚠ Without Web Locks (UNGUARDED) this is the only
     * way a runtime learns another tab took over; otherwise its writes would just vanish.
     */
    fun fencedOut() {
        val was = _state.value
        if (was != LeaseState.HELD && was != LeaseState.UNGUARDED) return
        _state.value = LeaseState.DISPLACED
        handle?.release()
        handle = null
        app.tenshu.diagnostics.Diagnostics.w("RuntimeLease", "fenced out of the databases (was $was)")
        onLost?.invoke()
    }

    // Stolen from us: stop writing at once and DROP the queue — the new holder owns the databases.
    private fun lost() {
        if (_state.value != LeaseState.HELD) return
        _state.value = LeaseState.DISPLACED
        handle = null
        onLost?.invoke()
    }

    companion object {
        /**
         * ⚠ Per origin by construction (Web Locks are scoped to the storage key), which is exactly
         * the scope of the `tenshu-sdk*` IndexedDB databases it guards — a top-level apex tab and
         * the apex iframe inside a Drive tab share both.
         */
        const val LOCK_NAME = "tenshu-runtime"
        const val RELEASE_REQUEST = "release"
        /** The holder's immediate answer to [RELEASE_REQUEST]: "heard you, handing over". */
        const val YIELDING = "yielding"
        /** How long a holder gets to ACKNOWLEDGE — a live page answers in one task. */
        const val ACK_TIMEOUT_MS = 3_000L
        /** How long an acknowledged holder gets to finish flushing before it is stolen from anyway. */
        const val YIELD_TIMEOUT_MS = 120_000L

        /** The page's lease, over the browser's real Web Locks + BroadcastChannel. */
        val default: RuntimeLease by lazy {
            RuntimeLease(
                backend = WebLocksBackend(),
                channel = BroadcastLeaseChannel.openOrNull(LOCK_NAME),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
    }
}

/** `navigator.locks` (https://w3c.github.io/web-locks/). */
internal class WebLocksBackend : LockBackend {
    private val locks: dynamic
        get() = js("(typeof navigator !== 'undefined' && navigator.locks) ? navigator.locks : null")

    override val supported: Boolean get() = locks != null

    override suspend fun tryAcquire(name: String, onLost: () -> Unit): LockHandle? =
        suspendCancellableCoroutine { cont ->
            val options: dynamic = js("({})")
            options.mode = "exclusive"
            options.ifAvailable = true
            val request: Promise<Any?> = locks.request(name, options) { lock: dynamic ->
                if (lock == null) {
                    cont.resume(null)
                    null
                } else {
                    // Held until the returned promise settles — that is the Web Locks contract.
                    Promise<Unit> { resolve, _ ->
                        var released = false
                        cont.resume(LockHandle {
                            if (!released) {
                                released = true
                                resolve(Unit)
                            }
                        })
                    }
                }
            }.unsafeCast<Promise<Any?>>()
            // Rejects with AbortError when another context steals the lock — or before the
            // callback ever ran, when the request itself is refused (then it is a failure).
            request.then({ _ -> Unit }, { failure ->
                if (cont.isActive) cont.resumeWithException(failure) else onLost()
            })
        }

    override suspend fun awaitFree(name: String) {
        // ⚠ Abortable: a cancelled wait must leave the lock queue, or it would grab-and-release the
        // lock later, racing the reload that follows "Use here".
        val controller: dynamic = js("new AbortController()")
        val options: dynamic = js("({})")
        options.signal = controller.signal
        try {
            locks.request(name, options) { _: dynamic -> null }.unsafeCast<Promise<Any?>>().await()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            controller.abort()
            throw cancelled
        }
    }

    override suspend fun steal(name: String) {
        val options: dynamic = js("({})")
        options.steal = true
        locks.request(name, options) { _: dynamic -> null }.unsafeCast<Promise<Any?>>().await()
    }
}

internal class BroadcastLeaseChannel private constructor(private val channel: dynamic) : LeaseChannel {
    override fun post(message: String) {
        channel.postMessage(message)
    }

    // ⚠ addEventListener, not `onmessage =`: a page can be both a former holder and a requester.
    override fun listen(onMessage: (String) -> Unit): () -> Unit {
        val listener: (dynamic) -> Unit = { event: dynamic -> (event.data as? String)?.let(onMessage) }
        channel.addEventListener("message", listener)
        return { channel.removeEventListener("message", listener) }
    }

    companion object {
        fun openOrNull(name: String): BroadcastLeaseChannel? {
            val ctor: dynamic = js("(typeof BroadcastChannel !== 'undefined') ? BroadcastChannel : null")
            if (ctor == null) return null
            return BroadcastLeaseChannel(js("new ctor(name)"))
        }
    }
}

/**
 * Every SDK call in a tab that does not hold the runtime fails with this. ⚠ The message is the
 * stable id the web apps match on (`RUNTIME_ELSEWHERE` in `@tenshu/sdk/core`) — do not reword it.
 */
internal class RuntimeElsewhereException :
    IllegalStateException(RUNTIME_ELSEWHERE_MESSAGE)

internal const val RUNTIME_ELSEWHERE_MESSAGE = "tenshu/runtime-elsewhere"
