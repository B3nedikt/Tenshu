package app.tenshu.contacts.data

import app.tenshu.error.ErrorCopyKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.contact.client.contactService
import org.tenshu.contact.common.INVITE_RESPONSE_INFO
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.contact.common.InviteResponsePayload
import org.tenshu.contact.common.encode
import org.tenshu.contact.createContactInvite
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.TResult
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The two typed refusals of `CompleteContactHandshake` (threat model T-CI-10) arrive at the app
 * boundary as **their own catalogue keys**, driven the way the app drives them: a real
 * `:contact-service` proxy, a real `TenshuClient`, [TenshuContactsRepository].
 *
 * Without the mapping the repository derives `error_operation_invalid_parameter` from the
 * `InvalidParameter` the proxy throws, whose copy is "Something went wrong" — and the user is
 * never told the one thing that matters: decline this invite and send a new one.
 */
class CompleteHandshakeRefusalCopyTest {

    private val crypto = BootstrapSyncCryptoManager()

    private fun clientOf(harness: ContactTestHarness): TenshuClient {
        val proxy = harness.proxy
        return DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            )
        )
    }

    private fun responseFrom(link: InviteLinkPayload, phrase: String): ByteArray = crypto.sealToIdentity(
        plaintext = InviteResponsePayload(
            inviteSecret = link.inviteSecret,
            identityPublicKey = crypto.deriveRealmEncryptionKeyPair(phrase).publicKey,
            selfAssertedName = "Bob",
        ).encode(),
        recipientEncPublicKey = link.inviterPublicKey,
        info = INVITE_RESPONSE_INFO,
        aad = link.inviteId,
    )

    private suspend fun TenshuClient.newInvite(): InviteLinkPayload {
        val link = contactService.createContactInvite()
        return InviteLinkPayload.parse(assertIs<TResult.Success<*>>(link, "createContactInvite: $link").value.toString())
    }

    /** Accept as the Contacts prompt does: sending the raw claim it displays (contact-service v2). */
    private suspend fun TenshuContactsRepository.accept(inviteId: String, sasConfirmed: Boolean) =
        completeHandshake(
            inviteId = inviteId,
            accept = true,
            shownFingerprint = pendingConfirmations().first()
                .firstOrNull { it.inviteId == inviteId }?.claimedFingerprint.orEmpty(),
            personId = "",
            newPersonName = "Bob",
            selfAssertedName = "Alice",
            sasConfirmed = sasConfirmed,
        )

    @Test
    fun aReplyThatIsNotTheOneShown_reachesTheAppAsItsOwnCopyKey() = runTest {
        val relay = SwappingInviteRelay()
        val harness = createContactProxy(relay, identityPhrase = "alice phrase", coroutineScope = backgroundScope)
        val client = clientOf(harness)
        val link = client.newInvite()

        // Bob answers; the pickup shows his claim; then — a restart later — the relay serves
        // somebody else's reply to the completion's own fetch.
        relay.postResponse(link.inviteIdHex, responseFrom(link, "bob phrase"))
        harness.pickup.checkOnce()
        harness.components.responseStash.remove(link.inviteIdHex)
        relay.served = responseFrom(link, "mallory phrase")

        val result = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */).accept(link.inviteIdHex, sasConfirmed = true)

        val failure = assertIs<RepositoryResult.Failure>(result)
        assertEquals(ErrorCopyKey.INVITE_RESPONSE_CHANGED, failure.copyKey)
    }

    @Test
    fun aReplyThatIsNotTheOneTheScreenShowed_isRefused_evenWhenTheRowHasMovedToIt() = runTest {
        // contact-service v2, end to end through the app's repository: the prompt showed Bob,
        // then the claim moved to Mallory by merge (a second inviter device) and the relay serves
        // Mallory. The row now agrees with the response — only what the screen showed does not.
        val relay = SwappingInviteRelay()
        val harness = createContactProxy(relay, identityPhrase = "alice phrase", coroutineScope = backgroundScope)
        val client = clientOf(harness)
        val link = client.newInvite()
        relay.postResponse(link.inviteIdHex, responseFrom(link, "bob phrase"))
        harness.pickup.checkOnce()
        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)
        val shown = repository.pendingConfirmations().first().single { it.inviteId == link.inviteIdHex }
            .claimedFingerprint

        // The other inviter device's later write, arriving by merge.
        harness.components.responseStash.remove(link.inviteIdHex)
        relay.served = responseFrom(link, "mallory phrase")
        harness.proxy.executionContext.saveData(
            org.tenshu.contact.ContactInvite.create(
                context = harness.proxy.tContext,
                inviteId = link.inviteIdHex,
                secretHash = org.tenshu.core.common.util.sha256(link.inviteSecret).toHexString(),
                status = org.tenshu.contact.ContactInvite.Status.REDEEMED,
                createdAt = 0L,
                expiresAt = Long.MAX_VALUE,
                intentKind = "",
                intentLabel = "",
                claimedName = "Bob",
                claimedFingerprint = org.tenshu.contact.common.identityFingerprint(
                    crypto.deriveRealmEncryptionKeyPair("mallory phrase").publicKey,
                ),
                claimedSas = "000000",
            )
        )

        val result = repository.completeHandshake(
            inviteId = link.inviteIdHex,
            accept = true,
            shownFingerprint = shown,
            personId = "",
            newPersonName = "Bob",
            selfAssertedName = "Alice",
            sasConfirmed = true,
        )

        val failure = assertIs<RepositoryResult.Failure>(result)
        assertEquals(ErrorCopyKey.INVITE_RESPONSE_CHANGED, failure.copyKey)
    }

    @Test
    fun aConfirmationWithNoCodeShown_reachesTheAppAsItsOwnCopyKey() = runTest {
        val relay = SwappingInviteRelay()
        val harness = createContactProxy(relay, identityPhrase = "alice phrase", coroutineScope = backgroundScope)
        val client = clientOf(harness)
        val link = client.newInvite()
        relay.postResponse(link.inviteIdHex, responseFrom(link, "bob phrase"))
        // No pickup: the row carries no claim, so no prompt can have shown a code.

        val result = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */).accept(link.inviteIdHex, sasConfirmed = true)

        val failure = assertIs<RepositoryResult.Failure>(result)
        assertEquals(ErrorCopyKey.INVITE_CODE_NOT_SHOWN, failure.copyKey)
    }

    @Test
    fun anyOtherFailure_keepsItsDerivedKey() = runTest {
        // The mapping must not turn every failed completion into "the reply changed".
        val harness = createContactProxy(SwappingInviteRelay(), identityPhrase = "alice phrase", coroutineScope = backgroundScope)

        val result = TenshuContactsRepository(clientOf(harness), reachability = null /* test: no reachability engine */).accept("00".repeat(16), sasConfirmed = false)

        val failure = assertIs<RepositoryResult.Failure>(result)
        assertNotEquals(ErrorCopyKey.INVITE_RESPONSE_CHANGED, failure.copyKey)
        assertNotEquals(ErrorCopyKey.INVITE_CODE_NOT_SHOWN, failure.copyKey)
    }
}

/** The ordinary [FakeInviteRelay], serving [served] in place of every pending reply once set. */
private class SwappingInviteRelay(
    private val delegate: FakeInviteRelay = FakeInviteRelay(),
) : InviteRelayClient by delegate {

    var served: ByteArray? = null

    override suspend fun pendingResponses(): List<InviteRelayClient.PendingResponse> =
        delegate.pendingResponses().map { reply -> served?.let { reply.copy(sealedResponse = it) } ?: reply }
}
