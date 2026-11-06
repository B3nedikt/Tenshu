package org.tenshu.sync.relay

import org.tenshu.sync.relay.mailbox.PairingOfferRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F7: the join flow's response mailbox is bounded in time the way the offer flow's is — a
 * seven-minute serving TTL and a 24-hour consumed marker after the ack. Before this a response
 * nobody acked held an encrypted master-key blob until the relay restarted.
 */
class PairingResponseInboxTest {

    private var now = 5_000_000L
    private val inbox = PairingResponseInbox(nowMs = { now })
    private val hash = "a".repeat(64)
    private val blob = byteArrayOf(1, 2, 3)

    @Test
    fun aPeekCarriesTheQueuedRegistration_soTheRouteRegistersOnDelivery() {
        val reg = PairingResponseInbox.PendingRegistration("owner", "device", byteArrayOf(9, 9), admittedAt = null)
        inbox.post(hash, blob, reg)
        val delivery = inbox.peek(hash)!!
        assertContentEquals(blob, delivery.encryptedKey)
        assertEquals("device", delivery.registration!!.deviceId)
        assertContentEquals(byteArrayOf(9, 9), delivery.registration!!.publicKey)

        now += PAIRING_REQUEST_TTL_MS
        assertNull(inbox.peek(hash), "an expired response hands out no registration either")
    }

    @Test
    fun accountErasure_dropsTheOwnersQueuedResponses_soAPeekCannotReRegisterIntoAnErasedRealm() {
        // Codex review: the queued registration names an owner, so a peek inside the TTL after
        // erasure would otherwise recreate a device registration `DeviceRegistry.eraseOwner` never
        // saw (it was not in the registry yet). Through the mailbox, as AccountErasure calls it.
        val mailbox = SyncMailbox(pairingResponses = inbox)
        val other = "b".repeat(64)
        inbox.post(hash, blob, PairingResponseInbox.PendingRegistration("owner-closed", "joiner", byteArrayOf(1), null))
        inbox.post(other, blob, PairingResponseInbox.PendingRegistration("owner-open", "joiner", byteArrayOf(1), null))

        assertEquals(1, mailbox.eraseOwner("owner-closed"))

        assertNull(inbox.peek(hash), "the erased owner's response — and its registration — is gone")
        assertEquals("owner-open", inbox.peek(other)?.registration?.ownerId, "another owner's is untouched")
    }

    @Test
    fun theTtlIsTheOfferFlows() {
        assertEquals(RelayConfig.PAIRING_OFFER_RESPONSE_TTL_MS, PAIRING_REQUEST_TTL_MS)
    }

    @Test
    fun aResponseIsServedUntilItsTtl_thenNeverAgain() {
        assertEquals(PairingResponseInbox.PostResult.STORED, inbox.post(hash, blob))
        now += PAIRING_REQUEST_TTL_MS - 1
        assertContentEquals(blob, inbox.get(hash), "peek is non-destructive inside the window")
        assertContentEquals(blob, inbox.get(hash))

        now += 1
        assertNull(inbox.get(hash), "at the TTL the response is gone")
        assertEquals(0, inbox.liveCount(), "and reading it dropped the entry")
    }

    @Test
    fun anUnackedResponseIsSweptOnTheWritePath() {
        inbox.post(hash, blob)
        now += PAIRING_REQUEST_TTL_MS + PairingRequestInbox.SWEEP_INTERVAL_MS
        inbox.post("b".repeat(64), blob) // the next write sweeps
        assertEquals(1, inbox.liveCount(), "the abandoned response must not survive to the next restart")
    }

    @Test
    fun anAck_removesTheResponse_andRetiresTheHashFor24h() {
        inbox.post(hash, blob)
        inbox.ack(hash)

        assertNull(inbox.get(hash))
        assertTrue(inbox.isConsumed(hash))
        assertEquals(PairingResponseInbox.PostResult.CONSUMED, inbox.post(hash, blob), "an acked hash cannot be refilled")

        now += PairingOfferRegistry.CONSUMED_RETENTION_MS
        assertFalse(inbox.isConsumed(hash), "the marker lapses with the offer flow's retention")
        inbox.sweep()
        assertEquals(0, inbox.consumedCount())
    }

    @Test
    fun anAckForAHashWithNoResponse_retiresNothing() {
        // The ack route is unauthenticated; an ack must not pre-empt a response yet to come.
        inbox.ack(hash)
        assertFalse(inbox.isConsumed(hash))
        assertEquals(PairingResponseInbox.PostResult.STORED, inbox.post(hash, blob))
    }

    @Test
    fun aRepostBeforeTheAck_replaces() {
        // The host's self-heal retry posts the same response twice.
        inbox.post(hash, byteArrayOf(1))
        assertEquals(PairingResponseInbox.PostResult.STORED, inbox.post(hash, blob))
        assertContentEquals(blob, inbox.get(hash))
    }

    @Test
    fun aRetryRacingTheAck_neverLeavesAConsumedHashServingARefill() {
        // A host retry overlapping the joiner's ack: whatever the interleaving, a retired hash
        // must not keep serving a response. Stress, not a proof: it cannot false-fail, but a pass
        // without the post/ack lock is possible on an unlucky schedule.
        repeat(2_000) { round ->
            val inbox = PairingResponseInbox()
            val hash = "e".repeat(64)
            inbox.post(hash, byteArrayOf(1))
            val start = java.util.concurrent.CountDownLatch(1)
            val retry = Thread { start.await(); inbox.post(hash, byteArrayOf(2)) }
            val ack = Thread { start.await(); inbox.ack(hash) }
            retry.start(); ack.start(); start.countDown(); retry.join(); ack.join()

            if (inbox.isConsumed(hash)) {
                assertNull(inbox.get(hash), "round $round: consumed, yet still serving a refill")
            }
        }
    }
}
