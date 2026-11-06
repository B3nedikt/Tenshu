@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class, org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaEntryToken
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaTaskProtection
import platform.Foundation.NSRecursiveLock
import kotlin.native.HiddenFromObjC

/**
 * ⚠⚠ **Every entry into a process that reaches the shared store in the background runs under
 * protection** (`ios-shared-store-sqlite.md` Appendix B.15; `ios-scheduler-lease.md` §5.4): a wake, a
 * location event, a notification action. [withProtection] takes an **explicit token** before the
 * entry's work and releases it after, so the gate admits that work, and the token's expiry ends
 * it: the work's coroutine is cancelled, and when nothing else protects the process every
 * statement still running is interrupted and the next refused (B.15 (200)).
 *
 * ⚠⚠ **With no binding installed in the process nothing changes**: the block runs unwrapped.
 * Foreground UI paths are not entries: the foreground is
 * protection, with no deadline (B.7).
 *
 * Kotlin-only (hidden from Swift): each app's runtime wraps its own entries.
 */
@HiddenFromObjC
object SharedReplicaEntries {
    /** The binding whose protection an entry takes: the process's, once installed. A test seam. */
    internal var installedBinding: () -> SharedReplicaBinding? = { SharedReplicaBinding.processOrNull()?.takeIf { it.isInstalled } }

    /** Whether this process has a binding installed: entries are wrapped (B.16 (237)). */
    val isInstalled: Boolean get() = installedBinding() != null

    /** Test seam: how many entries ran wrapped, and how many were turned away for want of time. */
    internal var wrapped = 0
        private set
    internal var refused = 0
        private set

    private val running = kotlin.concurrent.AtomicInt(0)

    /** Entries whose block runs now (B.16 (232)): what the host asks at a fence in the background. */
    internal fun activeEntries(): Int = running.value

    /**
     * Runs [block] under an entry's token named [reason], or unwrapped when no binding is installed.
     * **Null when protection could not be had or ended first**: the platform granted no time (the
     * block never ran, as §5.4 says: an entry never falls back to running unprotected), or the
     * token expired while it ran (the block was cancelled). A cancellation of the caller itself is
     * rethrown. The release, which may quiesce, runs off the main thread and before this returns,
     * so a completion handler the caller then calls comes after the drain (Q2).
     */
    suspend fun <T> withProtection(reason: String, block: suspend () -> T): T? {
        val binding = installedBinding() ?: return block()
        return protectedBy(reason, { onExpired -> binding.protectEntry(reason, onExpired) }, { binding.host.retryAtEntry() }, block)
    }

    /**
     * ⚠⚠ **A `BGTask`'s protection** (Appendix B.23): the token for work a running task carries,
     * whose assertion is the task itself (`SharedReplicaLifecycle.protectTask`), not a
     * `beginBackgroundTask` that iOS would end within seconds. Made when the task's launch handler
     * runs, then [SharedReplicaTaskProtection.begin]; ended by
     * [SharedReplicaTaskProtection.finish] **before** the task is completed. With no binding
     * installed it protects nothing and every call is a pass-through.
     */
    fun protectTask(reason: String): SharedReplicaTaskProtection {
        val binding = installedBinding() ?: return SharedReplicaTaskProtection(reason, acquire = null)
        return SharedReplicaTaskProtection(reason, acquire = { onExpired -> binding.protectTask(reason, onExpired) })
    }

    /** [withProtection] over any token source: the tests' seam. */
    internal suspend fun <T> protectedBy(
        reason: String,
        acquire: (onExpired: () -> Unit) -> SharedReplicaEntryToken?,
        /**
         * ⚠ Q35 (B.16 (220)): the host's rebuild owed by a not-ready open or a fence, run under the
         * entry's token and its expiry, before [block].
         */
        retry: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T? {
        val expiry = EntryExpiry()
        // Off the main thread: there the wait for an owed reopen is unbounded, as a job's (B.5.2 (71)).
        // ⚠⚠ NonCancellable: a plain withContext checks cancellation on its way
        // out, so a caller cancelled while the token was being taken would throw here with the
        // token already granted and nobody to release it: its assertion and the protection alive
        // until the platform expired it, the last-token quiesce never run. Taken here, the token
        // reaches the `try` below, whose `finally` releases it; a cancelled caller is met at once
        // inside it (the block's scope is cancelled before it starts).
        val token = withContext(NonCancellable + Dispatchers.Default) { acquire(expiry::fire) }
        if (token == null) {
            locked { refused++ }
            println("[replica-entry] '$reason': no protected time was granted; its store work is not done")
            return null
        }
        locked { wrapped++ }
        try {
            // A caller cancelled while the token was taken stops here, inside the `try`.
            currentCoroutineContext().ensureActive()
            running.incrementAndGet()
            try {
                return coroutineScope {
                    expiry.attach(coroutineContext.job)
                    retry()
                    block()
                }
            } finally {
                running.decrementAndGet()
            }
        } catch (e: CancellationException) {
            // Our own expiry cancelled the block: the caller goes on. Its own cancellation is not ours.
            if (expiry.fired && currentCoroutineContext().isActive) {
                println("[replica-entry] '$reason': its protection expired; the entry was cancelled")
                return null
            }
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.Default) { token.release() }
        }
    }

    private val lock = NSRecursiveLock()

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** An entry's expiry: the block's job cancelled, however early the expiry came. */
private class EntryExpiry {
    private val lock = NSRecursiveLock()
    private var job: Job? = null
    private var expired = false

    val fired: Boolean
        get() {
            lock.lock()
            try {
                return expired
            } finally {
                lock.unlock()
            }
        }

    /** The platform's expiration handler, through the protection's: cancels, never blocks. */
    fun fire() {
        val target = run {
            lock.lock()
            try {
                expired = true
                job
            } finally {
                lock.unlock()
            }
        }
        target?.cancel(CancellationException("[replica-entry] the entry's protection expired"))
    }

    fun attach(job: Job) {
        val already = run {
            lock.lock()
            try {
                this.job = job
                expired
            } finally {
                lock.unlock()
            }
        }
        if (already) job.cancel(CancellationException("[replica-entry] the entry's protection expired"))
    }
}
