@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class, SharedReplicaPlatformApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import platform.Foundation.NSCondition
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue

/**
 * ⚠⚠ **One `BGTask`'s hold on the shared store** (`ios-shared-store-sqlite.md` Appendix B.23): the
 * task's token ([SharedReplicaLifecycle.protectTask]), and the order its end and the task's
 * completion must keep. The task is what keeps the process from being suspended, so **the token
 * has always ended before the task is completed, and before the task's `expirationHandler`
 * returns**: a store still open for writing when iOS suspends the app is `0xdead10cc`.
 *
 * 1. [begin], from the task's launch handler: takes the token (off the main thread, so the wait for
 *    an owed reopen is not the bounded one, B.5.2 (71)). False: no token, or the hold was already
 *    ended; the task must then carry no store work. True with no binding installed (nothing to
 *    protect).
 * 2. [expire], from the task's `expirationHandler`, **before** the owner is told: the token's end
 *    becomes B.15.3's expiry (the interrupt, the latch and its 2 s drain), not a release. It ends
 *    nothing yet, so the owner's cancel can come first.
 * 3. [finish]`(then)`, where the owner would complete the task: ends the token, then runs [then]
 *    (`setTaskCompleted`). A release that could quiesce is moved off the main thread, [then] with it.
 * 4. [endToken], the expiry's backstop after the owner was told: returns only once the token has
 *    ended.
 *
 * ⚠⚠ Every end waits for what is in flight (B.23 (445)):
 * - **a [begin] still taking the token** (`acquire` can wait for a quiescing gate to reopen): the
 *   end waits for it, then ends the token it got, as an expiry if [expire] was called;
 * - **another end**: the caller waits for it; and if that end is a **release** and [expire] has been
 *   called since, the waiter turns it into an expiry once (`expire()` on the token, which in
 *   [ReplicaProtection] cuts the release's drain to 2 s, interrupts and latches, then waits for it).
 *
 * @param acquire the token's source, or `null` with no binding installed.
 */
@kotlin.native.HiddenFromObjC
class SharedReplicaTaskProtection(
    private val reason: String,
    private val acquire: ((onExpired: () -> Unit) -> SharedReplicaTaskToken?)?,
    private val isMainThread: () -> Boolean = { NSThread.isMainThread },
    private val offMain: (() -> Unit) -> Unit = { block -> dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u), block) },
) {
    private enum class EndKind { RELEASE, EXPIRE }

    private val condition = NSCondition()
    private var token: SharedReplicaTaskToken? = null
    private var acquiring = false
    private var expiring = false

    /** The end under way, and the token it ends; null when none is. */
    private var ending: EndKind? = null
    private var endingToken: SharedReplicaTaskToken? = null
    private var expirySent = false
    private var ended = false

    /** Whether there is a store to protect: a binding was installed when this was made. */
    val protectsTheStore: Boolean get() = acquire != null

    /** Takes the task's token. False when none could be had, or the hold had already ended. */
    fun begin(): Boolean {
        val source = acquire ?: return true
        val proceed = locked {
            if (ended || ending != null) {
                false
            } else {
                acquiring = true
                true
            }
        }
        if (!proceed) {
            println("[replica-task] '$reason': ended before it began; no token taken")
            return false
        }
        var granted: SharedReplicaTaskToken? = null
        try {
            granted = source {}
        } finally {
            locked {
                token = granted
                acquiring = false
                condition.broadcast()
            }
        }
        if (granted == null) {
            println("[replica-task] '$reason': no token; the task carries no store work")
            return false
        }
        return true
    }

    /** The task's `expirationHandler`, before its owner is told: the token's end will be an expiry. */
    fun expire() = locked {
        expiring = true
        condition.broadcast()
    }

    /** Ends the token (an expiry if [expire] was called, else a release), then runs [then]. */
    fun finish(then: () -> Unit) {
        val releaseOnMain = isMainThread() && locked { !expiring && !ended && (token != null || acquiring) }
        if (releaseOnMain) {
            offMain {
                endToken()
                then()
            }
            return
        }
        endToken()
        then()
    }

    /** Ends the token once; every other caller returns only once it has ended (see the class). */
    fun endToken() {
        while (true) {
            var run: (() -> Unit)? = null
            locked {
                when {
                    ended -> return
                    acquiring -> condition.wait()
                    ending != null -> {
                        val inFlight = endingToken
                        if (ending == EndKind.RELEASE && expiring && !expirySent && inFlight != null) {
                            expirySent = true
                            run = { inFlight.expire() }
                        } else {
                            condition.wait()
                        }
                    }
                    else -> {
                        val kind = if (expiring) EndKind.EXPIRE else EndKind.RELEASE
                        val held = token
                        ending = kind
                        endingToken = held
                        if (kind == EndKind.EXPIRE) expirySent = true
                        run = { end(held, kind) }
                    }
                }
            }
            run?.invoke()
        }
    }

    private fun end(held: SharedReplicaTaskToken?, kind: EndKind) {
        try {
            if (held != null) {
                if (kind == EndKind.EXPIRE) held.expire() else held.release()
            }
        } finally {
            locked {
                ended = true
                ending = null
                endingToken = null
                token = null
                condition.broadcast()
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        condition.lock()
        try {
            return block()
        } finally {
            condition.unlock()
        }
    }
}
