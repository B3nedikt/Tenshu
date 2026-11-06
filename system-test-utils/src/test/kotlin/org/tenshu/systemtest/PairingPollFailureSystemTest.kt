package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GetPendingPairingRequests` must distinguish **"nobody has asked to pair"** from **"I could not
 * ask"**. Its implementation says so in as many words — *"a relay error here … must NOT look
 * identical to no pending requests"* — because a swallowed error leaves the host's pairing screen
 * sitting on an empty list forever instead of surfacing something the user can act on.
 *
 * This pins both halves against a real relay:
 *  - relay up, no joiner yet → `Success` with an **empty** list (silence is not an error), and
 *  - relay unreachable → a typed **`Failure`** (an error is not silence).
 *
 * ⚠ Written while chasing a one-off `ClassCastException` in `pairHostAndJoiner`, where
 * `getPendingPairingRequests` returned a `Failure` in 1 of ~130 pairings and the helper's bare
 * `it as TResult.Success` turned it into an opaque cast error. That trigger was **never
 * reproduced** (see docs/tech-debt.md § "The pairing bring-up can fail its relay poll"). What this
 * test does establish is the *shape*: a relay-side failure surfaces exactly as the `Failure` that
 * was observed — so the one sighting was a failing poll, not an empty result or a pairing-logic
 * race. It also guards the distinction from being "simplified" away later.
 */
class PairingPollFailureSystemTest {

    @Test
    fun aFailingPollIsATypedFailureRatherThanAnEmptyList() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val bootstrap = host.bootstrapAsHost(deviceName = "Host Tablet")
            assertTrue(bootstrap is TResult.Success, "host bootstrap should succeed; got $bootstrap")
            val started = host.tenshuClient.syncService.startPairing()
            assertTrue(started is TResult.Success, "startPairing should succeed; got $started")

            // Relay up, nobody has asked to pair: silence, and silence is a Success.
            val healthy = host.tenshuClient.syncService.getPendingPairingRequests()
            assertTrue(healthy is TResult.Success, "a healthy poll should succeed; got $healthy")
            assertEquals(
                0,
                healthy.value!!.toList().size,
                "no joiner has initiated yet, so the list is empty — not an error",
            )

            // The relay goes away underneath the host — the shape behind the one-off failure.
            system.relayApp.stop()

            val broken = host.tenshuClient.syncService.getPendingPairingRequests()
            assertTrue(
                broken is TResult.Failure,
                "an unreachable relay must surface as a typed failure, never as 'no pending " +
                    "requests'; got $broken",
            )
        } finally {
            runCatching { system.shutdown() }
        }
    }
}
