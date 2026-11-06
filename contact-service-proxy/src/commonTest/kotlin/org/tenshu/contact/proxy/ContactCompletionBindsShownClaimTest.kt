package org.tenshu.contact.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.ContactService
import org.tenshu.contact.InviteAckResult
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.awaitInviteAck
import org.tenshu.contact.common.INVITE_RESPONSE_INFO
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.contact.common.InviteResponsePayload
import org.tenshu.contact.common.encode
import org.tenshu.contact.common.identityFingerprint
import org.tenshu.contact.completeContactHandshake
import org.tenshu.contact.createContactInvite
import org.tenshu.contact.redeemContactInvite
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.common.util.deriveContactSas
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inviter-side binding (threat model T-CI-10): **the key an inviter accepts is the key its
 * completion prompt showed** — the fingerprint, and the code derived from it.
 *
 * The prompt renders `ContactInvite.claimedFingerprint` / `claimedSas`, written when the sync-cycle
 * pickup verified *one* relay-served response. `CompleteContactHandshake` takes the key it stores
 * from the in-memory stash or — after a restart, or on any other inviter device, where the stash is
 * empty — from a **second** relay fetch. These tests pin that the stored response is bound to the
 * claim on the row and to what the prompt displayed, and that the pickup never re-points a claim at
 * whatever valid response it is served next.
 *
 * The adversary is the T-CI-01 one (somebody who read the link, so holds `K_link`) with the relay's
 * help — the relay alone cannot mint a response that passes the link-secret check (T-CI-02).
 */
class ContactCompletionBindsShownClaimTest {

    private val crypto = BootstrapSyncCryptoManager()
    private val aliceKey = crypto.deriveRealmEncryptionKeyPair("alice phrase")
    private val bobKey = crypto.deriveRealmEncryptionKeyPair("bob phrase")
    private val malloryKey = crypto.deriveRealmEncryptionKeyPair("mallory phrase")
    private val bobFingerprint = identityFingerprint(bobKey.publicKey)
    private val malloryFingerprint = identityFingerprint(malloryKey.publicKey)

    private fun serviceFor(harness: ContactTestHarness): ContactService {
        val executor = LocalTaskExecutor(harness.proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = harness.proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = harness.proxy.tContext,
        ).getService(ContactService.ID)
    }

    private fun <T : org.tenshu.core.Data> TResult<out T?>.expect(what: String): T {
        if (this is TResult.Failure<*>) error("$what failed: $error")
        return checkNotNull((this as TResult.Success<out T?>).value) { "$what returned null" }
    }

    /** What Mallory, holding the link, posts: a response that passes every check the inviter runs. */
    private fun mallorysResponse(link: InviteLinkPayload): ByteArray = crypto.sealToIdentity(
        plaintext = InviteResponsePayload(
            inviteSecret = link.inviteSecret,
            identityPublicKey = malloryKey.publicKey,
            selfAssertedName = "Bob",
        ).encode(),
        recipientEncPublicKey = link.inviterPublicKey,
        info = INVITE_RESPONSE_INFO,
        aad = link.inviteId,
    )

    private class Scene(
        val relay: SubstitutingInviteRelay,
        val inviter: ContactTestHarness,
        val invitee: ContactTestHarness,
        val link: InviteLinkPayload,
    ) {
        val inviteId: String get() = link.inviteIdHex
        fun row() = ContactInviteLocalStore(inviter.proxy.executionContext).get(inviteId)
    }

    /** Alice invites, Bob — the genuine invitee — redeems. Nothing has been picked up yet. */
    private suspend fun kotlinx.coroutines.test.TestScope.redeemedInvite(): Scene {
        val relay = SubstitutingInviteRelay()
        val inviter = createContactProxy(relay, identityPhrase = "alice phrase", coroutineScope = backgroundScope)
        val invitee = createContactProxy(relay, identityPhrase = "bob phrase", coroutineScope = backgroundScope)
        val link = serviceFor(inviter).createContactInvite().expect("create").get()
        serviceFor(invitee).redeemContactInvite(link, selfAssertedName = "Bob").expect("redeem")
        return Scene(relay, inviter, invitee, InviteLinkPayload.parse(link))
    }

    /** Completion as a prompt drives it: sending the claim the row shows right now. */
    private suspend fun Scene.complete(sasConfirmed: Boolean): TResult<*> =
        completeAsShown(shownFingerprint = row()?.claimedFingerprint.orEmpty(), sasConfirmed = sasConfirmed)

    /** Completion sending [shownFingerprint] as what the prompt displayed. */
    private suspend fun Scene.completeAsShown(shownFingerprint: String, sasConfirmed: Boolean): TResult<*> =
        serviceFor(inviter).completeContactHandshake(
            inviteId = inviteId,
            decision = CompleteContactHandshake.Decision.ACCEPT,
            shownFingerprint = shownFingerprint,
            newPersonName = "Bob",
            selfAssertedName = "Alice",
            sasConfirmed = sasConfirmed,
        )

    private fun Scene.assertNothingWasAccepted() {
        assertNull(
            TenshuIdentityLocalView(inviter).byFingerprint(malloryFingerprint),
            "a key the prompt never showed must not become a contact",
        )
        assertNull(TenshuIdentityLocalView(inviter).byFingerprint(bobFingerprint))
        assertEquals(0, PersonLocalView(inviter).count(), "no Person may be created by a refused completion")
        assertNull(relay.postedAck, "no ack may be sealed to anybody by a refused completion")
        assertEquals(ContactInvite.Status.REDEEMED, row()?.status, "a refused completion leaves the invite open")
    }

    // ── The substitution ────────────────────────────────────────────────────────────────────

    @Test
    fun relayServesAnotherKeyAfterTheCodeWasShown_confirmedCompletionIsRefused() = runTest {
        val scene = redeemedInvite()
        scene.inviter.pickup.checkOnce()
        assertEquals(
            deriveContactSas(aliceKey.publicKey, bobKey.publicKey), scene.row()?.claimedSas,
            "precondition: the prompt shows the code Bob's screen shows, so Alice confirms in good faith",
        )

        // The stash is process memory: gone after a restart, never present on Alice's other
        // devices (which render the same synced claim). Completion then asks the relay again.
        scene.inviter.components.responseStash.remove(scene.inviteId)
        scene.relay.substitute = mallorysResponse(scene.link)

        val refusal = assertIs<TResult.Failure<*>>(scene.complete(sasConfirmed = true))
        assertTrue(
            CompleteContactHandshake.Refusal.isResponseChanged(refusal.error),
            "the app must be able to tell this refusal apart to say 'decline and re-invite'; got ${refusal.error}",
        )
        assertFalse(CompleteContactHandshake.Refusal.isCodeNotShown(refusal.error))
        scene.assertNothingWasAccepted()
    }

    @Test
    fun relayServesAnotherKeyAfterTheClaimWasShown_unconfirmedCompletionIsRefusedToo() = runTest {
        // No checkmark at stake, but the prompt still named a fingerprint and the user accepted
        // THAT key as a contact (and is about to seal the `accepted` ack to it).
        val scene = redeemedInvite()
        scene.inviter.pickup.checkOnce()
        scene.inviter.components.responseStash.remove(scene.inviteId)
        scene.relay.substitute = mallorysResponse(scene.link)

        assertIs<TResult.Failure<*>>(scene.complete(sasConfirmed = false))
        scene.assertNothingWasAccepted()
    }

    @Test
    fun aRefusedCompletionDoesNotPoisonTheNextAttempt() = runTest {
        // The substituted response must not be kept as "the" response: once the relay serves the
        // shown one again, the same prompt completes.
        val scene = redeemedInvite()
        scene.inviter.pickup.checkOnce()
        scene.inviter.components.responseStash.remove(scene.inviteId)
        scene.relay.substitute = mallorysResponse(scene.link)
        assertIs<TResult.Failure<*>>(scene.complete(sasConfirmed = true))

        scene.relay.substitute = null
        scene.complete(sasConfirmed = true).let { assertIs<TResult.Success<*>>(it, "got $it") }

        val identity = assertNotNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(bobFingerprint))
        assertEquals(TenshuIdentity.Verification.VERIFIED, identity.verification)
        assertNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(malloryFingerprint))
    }

    // ── What was shown never changes under the user ─────────────────────────────────────────

    @Test
    fun aSecondResponseUnderAnotherKey_neverReplacesTheClaimTheUserIsLookingAt() = runTest {
        // The relay promises one response slot per invite (T-CI-01). A client that re-pointed its
        // claim at whatever came next would forgive a relay that broke that promise — and leave a
        // checkmark ticked against code X standing over key Y.
        val scene = redeemedInvite()
        scene.inviter.pickup.checkOnce()
        val shown = assertNotNull(scene.row())

        scene.relay.substitute = mallorysResponse(scene.link)
        scene.inviter.pickup.checkOnce()

        val after = assertNotNull(scene.row())
        assertEquals(shown.claimedFingerprint, after.claimedFingerprint, "the claimed key must not move")
        assertEquals(shown.claimedSas, after.claimedSas, "the code on screen must not move")
        assertEquals(bobFingerprint, after.claimedFingerprint)

        // And the response completion acts on is still the one that was shown.
        scene.relay.substitute = null
        scene.complete(sasConfirmed = true).let { assertIs<TResult.Success<*>>(it, "got $it") }
        assertNotNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(bobFingerprint))
        assertNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(malloryFingerprint))

        // The ack went to the key that was shown: Bob can open it, and closes his side.
        val ack = serviceFor(scene.invitee).awaitInviteAck(scene.inviteId).await().expect("await")
        assertEquals(AwaitInviteAck.Result.ACCEPTED, (ack as InviteAckResult).status.get())
    }

    // ── The claim flips under an open prompt (two inviter devices) ──────────────────────────
    //
    // T-CI-10's residual. The claim is a synced, last-writer-wins row: with two inviter devices
    // both seeing the invite PENDING, a relay serves each a different response, and the later
    // write lands on the other device BY MERGE — not through the pickup, so first-claim-wins
    // never sees it. The prompt on screen was showing Bob's code and the user ticked "codes match"
    // against it; by the time they tap Accept, the row, the stash and the relay all say Mallory.

    @Test
    fun theClaimFlipsByMergeUnderAnOpenPrompt_theKeyShownIsTheOnlyKeyThatCanBeAccepted() = runTest {
        val scene = redeemedInvite()
        scene.inviter.pickup.checkOnce()
        val shown = assertNotNull(scene.row())
        assertEquals(bobFingerprint, shown.claimedFingerprint, "precondition: the prompt shows Bob")

        // Another inviter device, served Mallory, wrote its claim later; the merge replaces ours.
        ContactInviteLocalStore(scene.inviter.proxy.executionContext).updateStatus(
            row = shown,
            status = ContactInvite.Status.REDEEMED,
            claimedName = "Bob",
            claimedFingerprint = malloryFingerprint,
            claimedSas = deriveContactSas(aliceKey.publicKey, malloryKey.publicKey),
        )
        scene.inviter.components.responseStash.remove(scene.inviteId)
        scene.relay.substitute = mallorysResponse(scene.link)

        scene.completeAsShown(shownFingerprint = shown.claimedFingerprint, sasConfirmed = true)

        assertNull(
            TenshuIdentityLocalView(scene.inviter).byFingerprint(malloryFingerprint),
            "the user confirmed Bob's code; Mallory's key must not be stored, let alone VERIFIED",
        )
        assertNull(scene.relay.postedAck, "no ack may be sealed to a key the prompt never showed")
    }

    // ── A confirmation needs a code to have been shown ──────────────────────────────────────

    @Test
    fun confirmingACodeThatWasNeverShown_isRefused() = runTest {
        // No pickup ran: the row is PENDING and carries no claim, so no prompt can have shown a
        // code. `sasConfirmed` is then an assertion about a comparison nobody could have made.
        val scene = redeemedInvite()

        val refusal = assertIs<TResult.Failure<*>>(scene.complete(sasConfirmed = true))
        assertTrue(
            CompleteContactHandshake.Refusal.isCodeNotShown(refusal.error),
            "a typed refusal, not a sentence; got ${refusal.error}",
        )
        assertFalse(CompleteContactHandshake.Refusal.isResponseChanged(refusal.error))

        assertNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(bobFingerprint))
        assertNull(scene.relay.postedAck)
    }

    @Test
    fun anOrdinaryFailure_isNeitherTypedRefusal() = runTest {
        // The predicates must not swallow everything: an unknown invite is just a failure.
        val scene = redeemedInvite()
        val failure = assertIs<TResult.Failure<*>>(
            serviceFor(scene.inviter).completeContactHandshake(
                inviteId = "00".repeat(16),
                decision = CompleteContactHandshake.Decision.ACCEPT,
                shownFingerprint = "",
            )
        )
        assertFalse(CompleteContactHandshake.Refusal.isResponseChanged(failure.error))
        assertFalse(CompleteContactHandshake.Refusal.isCodeNotShown(failure.error))
    }

    // ── Two responses for one invite in ONE relay answer ────────────────────────────────────
    //
    // The pickup works from a snapshot of the invite rows taken before it walks the relay's
    // answer. "First claim wins" must therefore hold inside one answer too, where both entries
    // see the same not-yet-claimed row.

    @Test
    fun twoValidResponsesInOneAnswer_genuineFirst_theFirstIsClaimed_andTheStashAgrees() = runTest {
        val scene = redeemedInvite()
        val mallory = mallorysResponse(scene.link)
        scene.relay.reshape = { answer -> answer + answer.map { it.copy(sealedResponse = mallory) } }

        scene.inviter.pickup.checkOnce()

        val row = assertNotNull(scene.row())
        assertEquals(bobFingerprint, row.claimedFingerprint, "the first entry is the claim")
        assertEquals(deriveContactSas(aliceKey.publicKey, bobKey.publicKey), row.claimedSas)
        assertEquals(ContactInvite.Status.REDEEMED, row.status)
        assertContentEquals(
            bobKey.publicKey,
            scene.inviter.components.responseStash.get(scene.inviteId)?.identityPublicKey,
            "the stash completion reads must hold the response the row shows",
        )

        // And completion, straight from that stash, stores the key that was shown.
        scene.relay.reshape = null
        scene.complete(sasConfirmed = true).let { assertIs<TResult.Success<*>>(it, "got $it") }
        assertNotNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(bobFingerprint))
        assertNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(malloryFingerprint))
    }

    @Test
    fun twoValidResponsesInOneAnswer_substituteFirst_theFirstIsClaimed_soTheCodesDoNotMatch() = runTest {
        // First wins does not decide who is genuine — the comparison does. What it guarantees is
        // that the code on screen is the code of the key that WOULD be stored.
        val scene = redeemedInvite()
        val mallory = mallorysResponse(scene.link)
        scene.relay.reshape = { answer -> answer.map { it.copy(sealedResponse = mallory) } + answer }

        scene.inviter.pickup.checkOnce()

        val row = assertNotNull(scene.row())
        assertEquals(malloryFingerprint, row.claimedFingerprint)
        assertEquals(deriveContactSas(aliceKey.publicKey, malloryKey.publicKey), row.claimedSas)
        assertFalse(
            row.claimedSas == deriveContactSas(aliceKey.publicKey, bobKey.publicKey),
            "the code shown must differ from the one on the genuine invitee's screen",
        )
        assertContentEquals(
            malloryKey.publicKey,
            scene.inviter.components.responseStash.get(scene.inviteId)?.identityPublicKey,
            "the stash and the row must name the same key",
        )
    }

    @Test
    fun theSameResponseListedTwiceInOneAnswer_isOneClaim() = runTest {
        val scene = redeemedInvite()
        scene.relay.reshape = { answer -> answer + answer }

        scene.inviter.pickup.checkOnce()

        assertEquals(bobFingerprint, scene.row()?.claimedFingerprint)
        assertEquals(ContactInvite.Status.REDEEMED, scene.row()?.status)
    }

    @Test
    fun aBurnedInvite_isNotRevivedByALaterEntryOfTheSameAnswer() = runTest {
        // Garbage burns the invite (T-CI-05). A valid response after it in the same answer sees
        // the pre-loop row, and must not write REDEEMED over the burn.
        val scene = redeemedInvite()
        scene.relay.reshape = { answer -> answer.map { it.copy(sealedResponse = byteArrayOf(1, 2, 3)) } + answer }

        scene.inviter.pickup.checkOnce()

        assertEquals(ContactInvite.Status.COMPROMISED, scene.row()?.status)
        assertNull(scene.inviter.components.responseStash.get(scene.inviteId), "nothing left to complete")
    }

    @Test
    fun aResponseStashedEarlierInTheAnswer_doesNotOutliveTheBurnThatFollowsIt() = runTest {
        val scene = redeemedInvite()
        scene.relay.reshape = { answer -> answer + answer.map { it.copy(sealedResponse = byteArrayOf(1, 2, 3)) } }

        scene.inviter.pickup.checkOnce()

        assertEquals(ContactInvite.Status.COMPROMISED, scene.row()?.status)
        assertNull(
            scene.inviter.components.responseStash.get(scene.inviteId),
            "the row says burned; the stash must not still offer a response to complete",
        )
        assertIs<TResult.Failure<*>>(scene.complete(sasConfirmed = false))
    }

    // ── What must keep working ──────────────────────────────────────────────────────────────

    @Test
    fun theShownResponse_fetchedAgainAfterARestart_completesVerified() = runTest {
        val scene = redeemedInvite()
        scene.inviter.pickup.checkOnce()
        scene.inviter.components.responseStash.remove(scene.inviteId)

        scene.complete(sasConfirmed = true).let { assertIs<TResult.Success<*>>(it, "got $it") }

        val identity = assertNotNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(bobFingerprint))
        assertEquals(TenshuIdentity.Verification.VERIFIED, identity.verification)
        assertEquals(ContactInvite.Status.COMPLETED, scene.row()?.status)
    }

    @Test
    fun theShownResponse_acceptedWithoutAConfirmation_completesUnverified() = runTest {
        val scene = redeemedInvite()
        scene.inviter.pickup.checkOnce()

        scene.complete(sasConfirmed = false).let { assertIs<TResult.Success<*>>(it, "got $it") }

        val identity = assertNotNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(bobFingerprint))
        assertEquals(TenshuIdentity.Verification.UNVERIFIED, identity.verification)
    }

    // ── An ACCEPT that names no shown key ───────────────────────────────────────────────────
    //
    // `""` is never "nothing to compare": skipping binding 2 on it would let a prompt that had not
    // loaded its claim yet (or any caller that just sent "") accept whatever the relay served.

    @Test
    fun acceptingWithNoShownFingerprint_andNoConfirmation_isRefused_evenWithAClaimOnTheRow() = runTest {
        val scene = redeemedInvite()
        scene.inviter.pickup.checkOnce()
        scene.inviter.components.responseStash.remove(scene.inviteId)
        scene.relay.substitute = mallorysResponse(scene.link)

        val refusal = assertIs<TResult.Failure<*>>(scene.completeAsShown(shownFingerprint = "", sasConfirmed = false))
        assertTrue(
            CompleteContactHandshake.Refusal.isResponseChanged(refusal.error),
            "a typed refusal, the same one a mismatch raises; got ${refusal.error}",
        )
        scene.assertNothingWasAccepted()
    }

    @Test
    fun acceptingWithNoShownFingerprint_isRefused_evenForTheGenuineResponse() = runTest {
        // Nothing to substitute here: the rule is about what the caller states it showed, not
        // about whether the relay happened to behave.
        val scene = redeemedInvite()
        scene.inviter.pickup.checkOnce()

        assertIs<TResult.Failure<*>>(scene.completeAsShown(shownFingerprint = "", sasConfirmed = false))
        scene.assertNothingWasAccepted()
    }

    @Test
    fun completingWithoutAPickup_isRefused_becauseNoPromptCanHaveShownAKey() = runTest {
        // No pickup ran: the row carries no claim, so the only honest `shownFingerprint` is "".
        // An on-demand trust-on-first-use accept is exactly what the unconditional binding 2 refuses.
        val scene = redeemedInvite()

        assertIs<TResult.Failure<*>>(scene.complete(sasConfirmed = false))
        assertNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(bobFingerprint))
        assertNull(scene.relay.postedAck)
    }

    @Test
    fun decliningWithNoShownFingerprint_stillWorks() = runTest {
        val scene = redeemedInvite()

        serviceFor(scene.inviter).completeContactHandshake(
            inviteId = scene.inviteId,
            decision = CompleteContactHandshake.Decision.REJECT,
            shownFingerprint = "",
        ).let { assertIs<TResult.Success<*>>(it, "got $it") }
        assertNull(TenshuIdentityLocalView(scene.inviter).byFingerprint(bobFingerprint))
    }
}

/**
 * An untrusted invite relay: the ordinary [FakeInviteRelay], except that while [substitute] is
 * set every pending response is served with those bytes instead — the relay answering a later
 * `GET /sync/invite/responses` differently from an earlier one.
 */
private class SubstitutingInviteRelay(
    private val delegate: FakeInviteRelay = FakeInviteRelay(),
) : InviteRelayClient by delegate {

    var substitute: ByteArray? = null

    /** Rewrites a whole `GET /sync/invite/responses` answer — duplicates, reorderings, extras. */
    var reshape: ((List<InviteRelayClient.PendingResponse>) -> List<InviteRelayClient.PendingResponse>)? = null

    var postedAck: ByteArray? = null
        private set

    override suspend fun pendingResponses(): List<InviteRelayClient.PendingResponse> =
        delegate.pendingResponses().map { response ->
            substitute?.let { InviteRelayClient.PendingResponse(response.inviteId, it) } ?: response
        }.let { answer -> reshape?.invoke(answer) ?: answer }

    override suspend fun postAck(inviteId: String, sealedAck: ByteArray) {
        postedAck = sealedAck
        delegate.postAck(inviteId, sealedAck)
    }
}
