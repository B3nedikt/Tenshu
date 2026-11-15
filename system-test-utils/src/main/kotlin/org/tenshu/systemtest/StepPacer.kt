package org.tenshu.systemtest

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A poll loop's pause, handed to the test: the stepped form of `delay(POLL_MS)` for a poller the
 * system does not own. Install [pace] where production passes its `delay` (`OfferPairingMonitor`'s
 * and `HostPairingMonitor`'s `pacePoll`, `TestSystem.client(pairingWaitPacer = …)` for the proxy's
 * `AwaitPairing`), then call [step] to let exactly one more poll run.
 *
 * What [step] guarantees, and why it is a happens-before rather than a guess: it returns only once
 * the poller has come back to its pause, which a poller reaches only after its previous poll, and
 * everything that poll published, has finished. It then releases the poller for one more poll. So
 * `sync(phone)`-style ordering extends to a view model's poll: "the desktop polls once, after the
 * phone's announce reached the relay" is a call the test makes, not a 2 s cadence it waits out.
 *
 * ⚠ One step is one poll. A poll that ends its loop (a terminal status) never pauses again, so the
 * test observes its outcome through the state it publishes, not through another [step].
 */
class StepPacer(private val name: String) {

    private val parked = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

    /** The pause to install in the poller. Suspends until a [step] releases it. */
    val pace: suspend () -> Unit = {
        val go = CompletableDeferred<Unit>()
        parked.send(go)
        try {
            go.await()
        } finally {
            // A poller cancelled while parked leaves a pause nobody will take; mark it, so the
            // next [awaitPause] skips it instead of releasing a poll that will never run.
            go.cancel()
        }
    }

    private var held: CompletableDeferred<Unit>? = null

    /**
     * Waits until the poller is parked at its pause, and holds it there. The poll before the pause
     * has finished, and the poller cannot start another until [release]. Use it to stand on the
     * boundary while the *other* device acts: `AwaitPairing` tries once before its first pause, so a
     * test must see that attempt finish before the host approves, or the attempt could land after the
     * approval and complete with no pause left to step.
     *
     * [hangGuard] only turns a poller that never pauses into a message. It is not a bound on the
     * code under test: a healthy poller reaches its pause in milliseconds.
     */
    suspend fun awaitPause(hangGuard: Duration = 60.seconds) {
        check(held == null) { "$name is already held at its pause; release() it first" }
        while (true) {
            val go = withTimeoutOrNull(hangGuard) { parked.receive() }
                ?: throw AssertionError("$name never came back to its pause within $hangGuard")
            if (!go.isCancelled) {
                held = go
                return
            }
        }
    }

    /** Lets the held poller run exactly one more poll. */
    fun release() {
        val go = checkNotNull(held) { "$name is not held; awaitPause() first" }
        held = null
        go.complete(Unit)
    }

    /** [awaitPause] then [release]: exactly one more poll, after the previous one has finished. */
    suspend fun step(hangGuard: Duration = 60.seconds) {
        awaitPause(hangGuard)
        release()
    }
}
