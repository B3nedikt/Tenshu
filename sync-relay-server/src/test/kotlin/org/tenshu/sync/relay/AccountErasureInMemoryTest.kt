package org.tenshu.sync.relay

import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentFreedStore
import org.tenshu.sync.relay.mailbox.ContentRefStore
import org.tenshu.sync.relay.mailbox.GrantStore
import org.tenshu.sync.relay.mailbox.HlcTipStore
import org.tenshu.sync.relay.mailbox.InviteStore
import org.tenshu.sync.relay.mailbox.MembershipRegistry
import org.tenshu.sync.relay.mailbox.MembershipStore
import org.tenshu.sync.relay.mailbox.PairingOfferRegistry
import org.tenshu.sync.relay.mailbox.PairingOfferStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.mailbox.RendezvousRegistry
import org.tenshu.sync.relay.mailbox.RendezvousStore
import org.tenshu.sync.relay.mailbox.ShareStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-memory twin of `AccountErasureSchemaTest`: the dev-mode / no-database relay must erase what
 * the SQLite one erases, revocation ledgers included, and leave only the `erased_realm` tombstone
 * (`RevocationLedgerErasureContractTest` pins the ledgers twin-against-twin). Seeded through each store's public API for the
 * closed owner and for an open one, then checked through the same API.
 *
 * ⚠ The ordered log and HLC tips are seeded only in the SQLite test (building a `SyncBlock` or a
 * `SignedHlcTip` needs a full `TContext`); their in-memory `eraseOwner` is a single map removal.
 */
class AccountErasureInMemoryTest {

    private val devices = KnownDevicesStore()
    private val realms = RealmStore()
    private val tips = HlcTipStore()
    private val mailbox = SyncMailbox()
    private val edges = ContentEdgeStore()
    private val refs = ContentRefStore()
    private val content = ContentBlobStore()
    private val freeing = ContentFreedStore(edges, refs, content)
    private val shares = ShareStore()
    private val invites = InviteStore()
    private val grants = GrantStore()
    private val membership = MembershipStore()
    private val rendezvous = RendezvousStore()
    private val offers = PairingOfferStore()
    private val admissions = AdmissionRevocationStore()

    private val erasure = AccountErasure(
        ownerScopedStoresOf(
            knownDevices = devices, realmStore = realms,
            hlcTipStore = tips,
            syncMailbox = mailbox, contentEdgeStore = edges, contentRefStore = refs,
            contentFreeing = freeing, contentBlobStore = content, shareStore = shares,
            inviteStore = invites, grantStore = grants, membershipStore = membership,
            rendezvousStore = rendezvous, pairingOfferStore = offers,
            admissionRevocationStore = admissions,
        ),
    )

    private val now = 1_000_000L
    private val far = now + 86_400_000L

    private fun seed(owner: String) {
        devices.register(owner, "phone", ByteArray(32))
        devices.recordRemoval(owner, "old-phone")
        realms.createIfAbsent(RealmStore.RealmRecord(owner, ByteArray(32), now))
        mailbox.addPairingRequest(
            owner,
            SyncMailbox.PairingRequestData("joiner", byteArrayOf(3), ByteArray(32), ByteArray(32), "hash-$owner"),
        )
        mailbox.setRetentionWindowMs(owner, 5)
        // A live edge on one content, and a second content described then freed (no edge).
        edges.writeEdges(owner, "holder", HlcStamp(now, 0, "phone"), listOf(ContentEdgeRegistry.EdgeWrite("data", "c-live")))
        refs.declareRefs(owner, "c-live", listOf("ref-live"))
        refs.declareRefs(owner, "c-gone", listOf("ref-gone"))
        freeing.free(owner, "c-gone", now)
        content.put(owner, "ref-live", byteArrayOf(1, 2, 3), now)
        shares.create(
            ShareStore.ShareRecord(
                "share-$owner", owner, byteArrayOf(4), 1, listOf(byteArrayOf(1)), null, null, now,
            ),
        )
        shares.putChunk("share-$owner", 0, byteArrayOf(7))
        invites.register("invite-$owner", owner, far, now, maxOpenPerOwner = 10)
        grants.put("grant-$owner", owner, byteArrayOf(1), far, now, maxOpenPerOwner = 10)
        membership.recordAdmission(owner, byteArrayOf(5), "member-device")
        membership.appendRevocation(MembershipRegistry.RevocationRecord(owner, byteArrayOf(6), 1, now, byteArrayOf(0)))
        rendezvous.provision("sess-$owner", owner, far, now, maxSessionsPerOwner = 10)
        offers.post("hash-$owner", PairingOfferRegistry.OfferResponse(owner, "approved", ByteArray(32), null), far, now)
    }

    @Test
    fun `the in-memory relay erases what the SQLite one does, ledgers included`() {
        seed(CLOSED)
        seed(OPEN)

        val report = erasure.eraseOwner(CLOSED)

        // Erased.
        assertNull(devices.getPublicKey(CLOSED, "phone"))
        assertNull(realms.get(CLOSED))
        assertTrue(mailbox.getPairingRequests(CLOSED).isEmpty())
        assertEquals(RelayConfig.DEFAULT_RETENTION_WINDOW_MS, mailbox.retentionWindowMs(CLOSED))
        assertTrue(edges.referencedContentIds(CLOSED).isEmpty())
        assertNull(edges.register(CLOSED, "holder", "data"))
        assertTrue(refs.declaredContentIds(CLOSED).isEmpty())
        assertTrue(freeing.freedAmong(CLOSED, listOf("c-gone")).isEmpty())
        assertEquals(0, content.blobCount(CLOSED))
        assertNull(shares.peek("share-$CLOSED"))
        assertNull(invites.peek("invite-$CLOSED"))
        assertNull(grants.peek("grant-$CLOSED"))
        assertTrue(membership.admittedDevices(CLOSED, byteArrayOf(5)).isEmpty())
        assertIs<RendezvousRegistry.Fetch.NoSession>(
            rendezvous.fetch("sess-$CLOSED", RendezvousRegistry.Direction.CLIENT_TO_PROXY, 0, now),
        )
        assertIs<PairingOfferRegistry.Fetch.NotFound>(offers.fetch("hash-$CLOSED", now))

        // ⚠⚠ The revocation ledgers go too: the erased_realm tombstone stops the realm returning.
        assertFalse(devices.wasRemoved(CLOSED, "old-phone"))
        assertTrue(membership.revocations(CLOSED).isEmpty())
        assertTrue(realms.isErased(CLOSED))

        // The open owner is untouched.
        assertNotNull(devices.getPublicKey(OPEN, "phone"))
        assertNotNull(realms.get(OPEN))
        assertFalse(realms.isErased(OPEN))
        assertTrue(devices.wasRemoved(OPEN, "old-phone"))
        assertEquals(1, membership.revocations(OPEN).size)
        assertEquals(1, mailbox.getPairingRequests(OPEN).size)
        assertEquals(setOf("c-live"), edges.referencedContentIds(OPEN))
        assertEquals(setOf("c-live"), refs.declaredContentIds(OPEN))
        assertEquals(setOf("c-gone"), freeing.freedAmong(OPEN, listOf("c-gone")))
        assertEquals(1, content.blobCount(OPEN))
        assertNotNull(shares.getChunk("share-$OPEN", 0, now))
        assertNotNull(invites.peek("invite-$OPEN"))
        assertNotNull(grants.peek("grant-$OPEN"))
        assertEquals(setOf("member-device"), membership.admittedDevices(OPEN, byteArrayOf(5)))
        assertIs<PairingOfferRegistry.Fetch.Found>(offers.fetch("hash-$OPEN", now))

        assertTrue(report.rowsErased > 0)
        assertEquals(1 + 1, report.blobsDropped, "one content blob, one share chunk")

        // Re-run: nothing left to erase, nothing breaks.
        assertEquals(AccountErasure.Report.NOTHING, erasure.eraseOwner(CLOSED))
    }

    private companion object {
        const val CLOSED = "owner-closed"
        const val OPEN = "owner-open"
    }
}
