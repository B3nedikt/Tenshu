package org.tenshu.sync.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /sync/pairing/request` is necessarily unauthenticated — an unpaired joiner holds no
 * credentials — so anyone who has ever seen a QR knows the `ownerId` and can post well-formed
 * requests indefinitely.
 *
 * ⚠⚠ Nothing evicted them. `removePairingRequest` drops only entries matching an **exact** secret,
 * so a request carrying a random secret was never removed and the per-owner list grew until the
 * relay process restarted. Found by the 2026-08-28 pairing sweep; ⚠ **T-P-13 does not cover it** —
 * that threat hardens the *shape* of a junk request, never the *number*.
 */
class PairingRequestMailboxBoundTest {

    // The store does not validate the hash's shape (the route does), so labels stand in for it.
    private fun request(secretHash: String) = SyncMailbox.PairingRequestData(
        pairingSecretHash = secretHash,
        deviceId = "device-$secretHash",
        sealedDeviceName = byteArrayOf(9, 9, 9),
        publicKey = byteArrayOf(1),
        signingPublicKey = byteArrayOf(2),
    )

    @Test
    fun aFloodOfUnmatchedRequestsCannotGrowTheMailboxWithoutBound() {
        val mailbox = SyncMailbox()
        val owner = "owner-under-flood"

        // Every one carries a DIFFERENT random-ish secret, so `removePairingRequest` can never
        // match them — which is exactly why the list used to grow forever.
        repeat(MAX_PENDING_PAIRING_REQUESTS_PER_OWNER * 10) { i ->
            mailbox.addPairingRequest(owner, request("junk-$i"))
        }

        assertEquals(
            MAX_PENDING_PAIRING_REQUESTS_PER_OWNER,
            mailbox.getPairingRequests(owner).size,
            "the per-owner mailbox must stay bounded under an unauthenticated flood",
        )
    }

    @Test
    fun evictionIsOldestFirst_soTheNewestRequestSurvives() {
        // Oldest-first, deliberately: under a flood the honest user retries, so their request is
        // the NEWEST. Evicting the newest would hand an attacker the ability to push out the one
        // request the user is actually waiting on.
        val mailbox = SyncMailbox()
        val owner = "owner"

        repeat(MAX_PENDING_PAIRING_REQUESTS_PER_OWNER) { i -> mailbox.addPairingRequest(owner, request("old-$i")) }
        mailbox.addPairingRequest(owner, request("the-real-joiner"))

        val secrets = mailbox.getPairingRequests(owner).map { it.pairingSecretHash }
        assertTrue(
            "the-real-joiner" in secrets,
            "the newest request must survive eviction — it is the one the user is waiting on",
        )
        assertTrue("old-0" !in secrets, "the oldest must be the one dropped")
        assertEquals(MAX_PENDING_PAIRING_REQUESTS_PER_OWNER, secrets.size)
    }
}
