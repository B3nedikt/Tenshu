package org.tenshu.sync.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two bounds [PairingRequestMailboxBoundTest] does not cover: **time** and **owners**.
 *
 * ⚠⚠ The per-owner cap bounded each list and left the NUMBER of lists to the caller.
 * `POST /sync/pairing/request` is credential-less, the `ownerId` in it is whatever the caller
 * sent, nothing expired an entry and nothing removed an owner — so a fresh id per request grew
 * the map until the relay died (found 2026-10-13, `docs/tech-debt.md`).
 */
class PairingRequestInboxTest {

    private var now = 1_000_000L
    private val inbox = PairingRequestInbox(ttlMs = TTL, nowMs = { now })

    // The store does not validate the hash's shape (the route does), so labels stand in for it.
    private fun request(secretHash: String) = SyncMailbox.PairingRequestData(
        pairingSecretHash = secretHash,
        deviceId = "device-$secretHash",
        sealedDeviceName = byteArrayOf(9, 9, 9),
        publicKey = byteArrayOf(1),
        signingPublicKey = byteArrayOf(2),
    )

    @Test
    fun aFreshOwnerPerRequest_isForgottenOnceItsRequestsExpire() {
        repeat(5_000) { i -> inbox.add("owner-$i", request("s-$i")) }
        assertEquals(5_000, inbox.ownerCount(), "nothing has expired yet")

        // One more add, a TTL later: the write path is where the sweep is owed, and it must take
        // the abandoned owners with it rather than leave 5,000 empty lists behind.
        now += TTL
        inbox.add("the-one-live-owner", request("live"))

        assertEquals(1, inbox.ownerCount(), "expired owners must leave no map entry")
        assertEquals(listOf("live"), inbox.get("the-one-live-owner").map { it.pairingSecretHash })
    }

    @Test
    fun anExpiredRequestIsNotServed() {
        inbox.add("owner", request("old"))
        now += TTL - 1
        assertEquals(listOf("old"), inbox.get("owner").map { it.pairingSecretHash }, "still inside the window")

        now += 1
        assertTrue(inbox.get("owner").isEmpty(), "at the TTL the request is gone, sweep or no sweep")
        assertEquals(0, inbox.ownerCount(), "and reading it dropped the emptied owner")
    }

    @Test
    fun aNewRequestDoesNotReviveTheOwnersExpiredOnes() {
        inbox.add("owner", request("old"))
        now += TTL
        inbox.add("owner", request("new"))

        assertEquals(listOf("new"), inbox.get("owner").map { it.pairingSecretHash })
    }

    @Test
    fun removingTheLastRequestRemovesTheOwner() {
        inbox.add("owner", request("a"))
        inbox.add("owner", request("b"))

        inbox.remove("owner", "a")
        assertEquals(1, inbox.ownerCount())
        inbox.remove("owner", "b")
        assertEquals(0, inbox.ownerCount(), "a completed pairing must not leave an empty list behind")
    }

    @Test
    fun theSweepRunsAtMostOncePerInterval_andStillCatchesUp() {
        inbox.add("early", request("e"))
        now += TTL
        inbox.add("first-after-ttl", request("f"))          // sweeps: `early` goes
        assertEquals(1, inbox.ownerCount())

        // Within the interval no sweep runs, so an owner that expires here lingers…
        inbox.add("lingers", request("l"))
        now += PairingRequestInbox.SWEEP_INTERVAL_MS - 1
        inbox.add("no-sweep-yet", request("n"))
        assertEquals(3, inbox.ownerCount())

        // …and is collected by the first add after both the interval and its TTL have passed.
        now += TTL
        inbox.add("later", request("x"))
        assertEquals(1, inbox.ownerCount())
    }

    private companion object {
        const val TTL = 10 * 60 * 1_000L
    }
}
