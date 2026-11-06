package org.tenshu.contact.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.ContactService
import org.tenshu.contact.InviteAckResult
import org.tenshu.contact.Person
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.awaitInviteAck
import org.tenshu.contact.completeContactHandshake
import org.tenshu.contact.createContactInvite
import org.tenshu.contact.redeemContactInvite
import org.tenshu.contact.common.INVITE_ACK_INFO
import org.tenshu.contact.common.InviteAckPayload
import org.tenshu.contact.common.encode
import org.tenshu.contact.common.identityFingerprint
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.common.util.deriveContactSas
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full in-process mutual handshake over a [FakeInviteRelay]
 * (`contact-invitation-handshake.md` §12 test plan): both sides' `Person` +
 * `TenshuIdentity` rows created, relay state freed; the wrong-`K_link` burn;
 * forged-ack rejection invitee-side; link-to-existing re-pointing. Stub
 * crypto (`publicKey == privateKey`) carries the sealed boxes — real-crypto
 * coverage is `SealToIdentityTest` + the dev-relay system test.
 */
class ContactHandshakeRoundTripTest {

    private fun environmentFor(harness: ContactTestHarness): TenshuEnvironment {
        val executor = LocalTaskExecutor(harness.proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = harness.proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = harness.proxy.tContext,
        )
    }

    private fun serviceFor(harness: ContactTestHarness): ContactService =
        environmentFor(harness).getService(ContactService.ID)

    private fun <T : org.tenshu.core.Data> TResult<out T?>.expect(what: String): T {
        if (this is TResult.Failure<*>) error("$what failed: $error")
        return checkNotNull((this as TResult.Success<out T?>).value) { "$what returned null" }
    }

    @Test
    fun fullMutualHandshake_createsBothSidesRecords_andFreesRelayState() = runTest {
        val relay = FakeInviteRelay()
        val inviter = createContactProxy(relay, identityPhrase = "alice phrase", coroutineScope = backgroundScope)
        val invitee = createContactProxy(relay, identityPhrase = "bob phrase", coroutineScope = backgroundScope)
        val inviterService = serviceFor(inviter)
        val inviteeService = serviceFor(invitee)

        // 1. Issue (inviter): link out, PENDING row saved, relay mailbox open.
        val link = inviterService.createContactInvite(
            intentKind = ContactInvite.IntentKind.SHARE_FOLDER,
            intentLabel = "Holiday photos",
        ).expect("createContactInvite").get()
        val inviteId = InviteLinkPayload.parse(link).inviteIdHex
        assertEquals(1, relay.recordCount())

        // 2. Redeem (invitee): sealed response posted, redemption persisted.
        val redeemedId = inviteeService.redeemContactInvite(link, selfAssertedName = "Bob")
            .expect("redeemContactInvite").get()
        assertEquals(inviteId, redeemedId)
        assertNotNull(invitee.components.redemptionStore.get(inviteId))

        // 3. Inviter's sync-cycle pickup verifies + flips the row to REDEEMED,
        // carrying the responder's (quarantined) claim pair so any inviter
        // device can render the completion prompt.
        inviter.pickup.checkOnce()
        val inviterRows = ContactInviteLocalStore(inviter.proxy.executionContext)
        val redeemedRow = inviterRows.get(inviteId)
        assertEquals(ContactInvite.Status.REDEEMED, redeemedRow?.status)
        assertEquals("Bob", redeemedRow?.claimedName)
        val inviteeKey = BootstrapSyncCryptoManager().deriveRealmEncryptionKeyPair("bob phrase")
        assertEquals(identityFingerprint(inviteeKey.publicKey), redeemedRow?.claimedFingerprint)
        // §5.3: both sides derive the same symmetric SAS, proxy-side.
        val aliceKey = BootstrapSyncCryptoManager().deriveRealmEncryptionKeyPair("alice phrase")
        val expectedSas = deriveContactSas(aliceKey.publicKey, inviteeKey.publicKey)
        assertEquals(expectedSas, redeemedRow?.claimedSas)

        // 4. Complete (inviter): accept as a new contact.
        val fingerprint = inviterService.completeContactHandshake(
            inviteId = inviteId,
            decision = CompleteContactHandshake.Decision.ACCEPT,
            shownFingerprint = ContactInviteLocalStore(inviter.proxy.executionContext).get(inviteId)?.claimedFingerprint.orEmpty(),
            newPersonName = "Bobby",
            selfAssertedName = "Alice",
        ).expect("completeContactHandshake").get()
        assertEquals(identityFingerprint(inviteeKey.publicKey), fingerprint)
        val completed = inviterRows.get(inviteId)
        assertEquals(ContactInvite.Status.COMPLETED, completed?.status)
        assertEquals(fingerprint, completed?.resultFingerprint)

        // Inviter-side records: thin Person (petname from the prompt) + a
        // TOFU identity carrying the invitee's self-asserted name, quarantined.
        val identityRow = TenshuIdentityLocalView(inviter).byFingerprint(fingerprint)
        assertNotNull(identityRow)
        assertEquals("Bob", identityRow.selfAssertedName)
        assertEquals(TenshuIdentity.Verification.UNVERIFIED, identityRow.verification)
        assertEquals(TenshuIdentity.Source.INVITE_LINK, identityRow.source)
        assertEquals("Bobby", PersonLocalView(inviter).displayNameOf(identityRow.personId))

        // 5. Mutual close (invitee): ack verified, mirror records created,
        // relay freed, redemption cleared. The typed result carries the ack's
        // name claim + advisory intent for the redeem screen's success card.
        val ackResult = inviteeService.awaitInviteAck(inviteId).await()
            .expect("awaitInviteAck") as InviteAckResult
        assertEquals(AwaitInviteAck.Result.ACCEPTED, ackResult.status.get())
        assertEquals("Alice", ackResult.peerName.get())
        assertEquals(ContactInvite.IntentKind.SHARE_FOLDER, ackResult.intentKind.get())
        assertEquals("Holiday photos", ackResult.intentLabel.get())
        assertEquals(expectedSas, ackResult.sas.get())

        val inviterKey = BootstrapSyncCryptoManager().deriveRealmEncryptionKeyPair("alice phrase")
        val inviterIdentity = TenshuIdentityLocalView(invitee)
            .byFingerprint(identityFingerprint(inviterKey.publicKey))
        assertNotNull(inviterIdentity, "invitee should hold the inviter's identity")
        assertEquals(identityFingerprint(inviterKey.publicKey), ackResult.peerFingerprint.get())
        assertEquals("Alice", inviterIdentity.selfAssertedName)
        assertEquals("Alice", PersonLocalView(invitee).displayNameOf(inviterIdentity.personId))

        assertEquals(0, relay.recordCount(), "done should free relay state")
        assertNull(invitee.components.redemptionStore.get(inviteId))
    }

    @Test
    fun wrongLinkSecret_burnsTheInvite_andRejectsTheResponder() = runTest {
        val relay = FakeInviteRelay()
        val inviter = createContactProxy(relay, identityPhrase = "alice phrase", coroutineScope = backgroundScope)
        val invitee = createContactProxy(relay, identityPhrase = "mallory phrase", coroutineScope = backgroundScope)
        val inviterService = serviceFor(inviter)
        val inviteeService = serviceFor(invitee)

        val link = inviterService.createContactInvite().expect("create").get()
        val real = InviteLinkPayload.parse(link)

        // An interceptor knows the inviteId + inviter key but not K_link —
        // the best it can do is redeem with a wrong secret.
        val tampered = InviteLinkPayload(
            inviteId = real.inviteId,
            inviteSecret = ByteArray(16) { 0x55 },
            inviterPublicKey = real.inviterPublicKey,
        ).toMessagingLink()
        inviteeService.redeemContactInvite(tampered, selfAssertedName = "Mallory")
            .expect("redeem")

        // The pickup burns the invite and posts the sealed auto-reject.
        inviter.pickup.checkOnce()
        val rows = ContactInviteLocalStore(inviter.proxy.executionContext)
        assertEquals(
            ContactInvite.Status.COMPROMISED,
            rows.get(real.inviteIdHex)?.status,
            "wrong-secret response must burn the invite",
        )

        // The responder's wait resolves to REJECTED — and no records exist.
        val ackResult = inviteeService.awaitInviteAck(real.inviteIdHex).await().expect("await")
        assertEquals(AwaitInviteAck.Result.REJECTED, (ackResult as InviteAckResult).status.get())
        assertNull(
            TenshuIdentityLocalView(invitee)
                .byFingerprint(identityFingerprint(real.inviterPublicKey)),
            "a rejected handshake must not create identity rows",
        )
    }

    @Test
    fun forgedAck_isRejectedInviteeSide() = runTest {
        val relay = FakeInviteRelay()
        val crypto = BootstrapSyncCryptoManager()
        val inviter = createContactProxy(relay, identityPhrase = "alice phrase", coroutineScope = backgroundScope)
        val invitee = createContactProxy(relay, identityPhrase = "bob phrase", coroutineScope = backgroundScope)

        val link = serviceFor(inviter).createContactInvite().expect("create").get()
        val parsed = InviteLinkPayload.parse(link)
        serviceFor(invitee).redeemContactInvite(link, selfAssertedName = "Bob").expect("redeem")

        // A forger who somehow learned the invitee's A13 pub but not K_link:
        // sealed correctly, wrong secret inside.
        val inviteeKey = crypto.deriveRealmEncryptionKeyPair("bob phrase")
        relay.postAck(
            inviteId = parsed.inviteIdHex,
            sealedAck = crypto.sealToIdentity(
                plaintext = InviteAckPayload(
                    status = InviteAckPayload.STATUS_ACCEPTED,
                    inviteSecret = ByteArray(16) { 0x66 },
                    identityPublicKey = parsed.inviterPublicKey,
                ).encode(),
                recipientEncPublicKey = inviteeKey.publicKey,
                info = INVITE_ACK_INFO,
                aad = parsed.inviteId,
            ),
        )

        val result = serviceFor(invitee).awaitInviteAck(parsed.inviteIdHex).await().expect("await")
        assertEquals(AwaitInviteAck.Result.INVALID, (result as InviteAckResult).status.get())
        assertNull(
            TenshuIdentityLocalView(invitee)
                .byFingerprint(identityFingerprint(parsed.inviterPublicKey)),
            "a forged ack must not create identity rows",
        )
    }

    @Test
    fun linkToExistingPerson_repointsPersonId_andStaysUnverified() = runTest {
        val relay = FakeInviteRelay()
        val inviter = createContactProxy(relay, identityPhrase = "alice phrase", coroutineScope = backgroundScope)
        val invitee = createContactProxy(relay, identityPhrase = "bob phrase", coroutineScope = backgroundScope)
        val inviterService = serviceFor(inviter)

        // Pre-existing aggregate the user links the new key to.
        val existing = Person.create(inviter.proxy.tContext, personId = "person-existing", displayName = "Bob from work")
        inviter.proxy.executionContext.saveData(existing)

        val link = inviterService.createContactInvite().expect("create").get()
        val inviteId = InviteLinkPayload.parse(link).inviteIdHex
        serviceFor(invitee).redeemContactInvite(link, selfAssertedName = "Robert").expect("redeem")
        inviter.pickup.checkOnce()

        val fingerprint = inviterService.completeContactHandshake(
            inviteId = inviteId,
            decision = CompleteContactHandshake.Decision.ACCEPT,
            shownFingerprint = ContactInviteLocalStore(inviter.proxy.executionContext).get(inviteId)?.claimedFingerprint.orEmpty(),
            personId = "person-existing",
        ).expect("complete").get()

        val identity = TenshuIdentityLocalView(inviter).byFingerprint(fingerprint)
        assertNotNull(identity)
        assertEquals("person-existing", identity.personId, "link-to-existing re-points personId")
        // Linking never moves trust (contact doc §6.3): the key arrives TOFU.
        assertEquals(TenshuIdentity.Verification.UNVERIFIED, identity.verification)
        // The petname is untouched — self-asserted stays quarantined.
        assertEquals("Bob from work", PersonLocalView(inviter).displayNameOf("person-existing"))
        assertTrue(
            PersonLocalView(inviter).count() == 1,
            "no duplicate Person should be created when linking to an existing one",
        )
    }
}
