package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ApprovePairingRequest
import org.tenshu.sync.InitiatePairing
import org.tenshu.sync.PairingInitiation
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.ApprovePairingRequestImplementation
import org.tenshu.sync.proxy.impl.GetPendingPairingRequestsImplementation
import org.tenshu.sync.proxy.impl.InitiatePairingImplementation
import org.tenshu.sync.proxy.security.deriveJoinPairingSas
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The join-flow binding (threat model T-P-02 / T-P-03, amended 2026-10-02): **the keys the SAS was
 * computed from are the keys the admission uses.**
 *
 * The host's poll (`GetPendingPairingRequests`) derives the SAS the user compares against the
 * joiner's screen. Approve (`ApprovePairingRequest`) takes only a `deviceId`. It used to re-fetch
 * the relay's request list and take the X25519 key, the Ed25519 signing key and the device name
 * from whichever entry carried that `deviceId` — without even the pairing-secret filter the poll
 * applies. The relay is untrusted (House Rule #1) and `POST /sync/pairing/request` is
 * unauthenticated, so the request approve acted on did not have to be the request the user
 * verified: the realm key blob (A1–A3) was sealed to, and the admission delegation (A4/A33) signed
 * over, keys no human ever compared a code for.
 *
 * Every relay here is a liar of one specific kind; both real ops run against it, in the order the
 * UI runs them.
 */
class PairingApproveBindsVerifiedKeysTest {

    private lateinit var testProxy: TestProxy
    private lateinit var crypto: EcdhLikeCrypto
    private lateinit var relay: ScriptedPairingRelay
    private lateinit var syncManager: SyncManagerImpl

    // The genuine joiner (K1/S1) and the substitute (K2/S2).
    private lateinit var genuine: SyncCryptoManager.KeyPair
    private lateinit var substitute: SyncCryptoManager.KeyPair
    private val genuineSigningKey = ByteArray(32) { 0x51 }
    private val substituteSigningKey = ByteArray(32) { 0x52 }

    // Workstream B: the genuine joiner's id is its signing key's. A substitute that reuses it under
    // another key is now refused by the relay and by the admitter too; these tests pin that the
    // host binds what was VERIFIED, whatever the relay serves.
    private val deviceId = org.tenshu.sync.common.deviceIdForPublicKey(genuineSigningKey)

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = EcdhLikeCrypto()
        relay = ScriptedPairingRelay(testProxy.tContext)
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
        )
        syncManager.initialize("abcdefghijklmnopqrstuvwxyz012345")
        genuine = crypto.generateKeyPair()
        substitute = crypto.generateKeyPair()
        // Whatever realm set-up sealed or agreed is not this test's subject.
        crypto.agreedWith.clear()
        crypto.sealedUnder.clear()
    }

    // ── The two ops, as the host UI drives them ─────────────────────────────────────────────

    private suspend fun poll(): List<PairingRequest> =
        GetPendingPairingRequestsImplementation(relay, syncManager, crypto)
            .execute<TList<PairingRequest>>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = null,
            ).toList()

    private suspend fun approve(id: String = deviceId) {
        ApprovePairingRequestImplementation(relay, syncManager, crypto).execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to id.toTString(testProxy.tContext)),
        )
    }

    /**
     * A request as the relay serves it (F7): sealed name, hashed secret. Sealing under [secret]
     * models a poster who knows the secret — the genuine joiner, or anyone who scanned the QR.
     */
    private fun request(
        keyPair: SyncCryptoManager.KeyPair,
        signingKey: ByteArray,
        secret: String,
        name: String = "Ben's Phone",
        id: String = deviceId,
    ): SyncRelay.RelayPairingRequest = sealedRelayRequest(
        crypto = crypto,
        deviceId = id,
        secret = secret,
        deviceName = name,
        publicKey = keyPair.publicKey,
        signingPublicKey = signingKey,
    )

    /**
     * What the JOINER's screen shows: the v2 SAS over (host key from the QR, its own ECDH key) and
     * its own signing key, id and name — the device that holds [joiner] posts [request]'s
     * identity, so the genuine joiner's identity is [genuineSigningKey] and the substitute's is
     * [substituteSigningKey].
     */
    private fun sasOnJoinerScreen(hostPublicKey: ByteArray, joiner: SyncCryptoManager.KeyPair): String =
        deriveJoinPairingSas(
            sharedSecret = crypto.calculateSharedSecret(joiner.privateKey, hostPublicKey),
            hostPublicKey = hostPublicKey,
            joinerPublicKey = joiner.publicKey,
            joinerSigningPublicKey = if (joiner === genuine) genuineSigningKey else substituteSigningKey,
            joinerDeviceId = deviceId,
            joinerDeviceName = "Ben's Phone",
        )

    private fun savedDevice(id: String = deviceId): Device? =
        testProxy.database.getData(DataId.StringDataId.create(testProxy.tContext, Device.ID, id)) as? Device

    /** Starts a host session; returns (secret, a COPY of the host key — approve zeroes the context). */
    private fun startSession(): Pair<String, ByteArray> {
        val context = syncManager.generatePairingContext()
        return context.secret to context.publicKey.copyOf()
    }

    private fun assertNothingWasAdmitted() {
        assertNull(relay.posted, "no pairing response may be posted")
        assertTrue(crypto.sealedUnder.isEmpty(), "the realm key blob must never be sealed at all")
        assertNull(savedDevice(), "no authorised Device row may be written")
    }

    /**
     * The admission that reached the relay is the genuine joiner's and nobody else's: sealed so the
     * genuine joiner can open it and the substitute cannot, delegating the genuine signing key.
     */
    private fun assertAdmittedTheGenuineJoiner(hostPublicKey: ByteArray) {
        val posted = assertNotNull(relay.posted, "the verified request must be admitted")
        assertContentEquals(
            genuineSigningKey, posted.newDevicePublicKey,
            "the delegation must be over the signing key of the request the SAS was shown for",
        )
        assertFalse(
            crypto.canOpen(posted.encryptedMasterKey, substitute.privateKey, hostPublicKey),
            "the realm key blob was sealed to a key the user never verified",
        )
        assertTrue(
            crypto.canOpen(posted.encryptedMasterKey, genuine.privateKey, hostPublicKey),
            "the genuine joiner must be able to open the blob",
        )
        assertTrue(
            crypto.agreedWith.none { it.contentEquals(substitute.publicKey) },
            "the host must never run the key agreement against the substituted key",
        )
        val device = assertNotNull(savedDevice(), "the admitted device's row must be written")
        assertContentEquals(genuineSigningKey, device.publicKey.get())
    }

    // ── The substitution ────────────────────────────────────────────────────────────────────

    @Test
    fun relaySwapsBothKeysAfterTheSasWasShown_theAdmissionStillUsesTheVerifiedKeys() = runTest {
        val (secret, hostPublicKey) = startSession()
        relay.answers += listOf(request(genuine, genuineSigningKey, secret))
        // Every later answer: same deviceId, the relay's own keys, and a name of its choosing.
        relay.answers += listOf(request(substitute, substituteSigningKey, secret, name = "Not Ben's Phone"))

        val shown = poll().single()
        assertEquals(
            sasOnJoinerScreen(hostPublicKey, genuine), shown.sas?.get(),
            "precondition: the two screens match, so the user approves in good faith",
        )

        approve()

        assertAdmittedTheGenuineJoiner(hostPublicKey)
        assertEquals("Ben's Phone", savedDevice()!!.name.get(), "the stored name is the name that was shown")
        assertEquals(
            1, relay.fetches,
            "approve must take no key material from a later relay response — it must not ask for one",
        )
    }

    @Test
    fun relaySwapsOnlyTheSigningKey_theDelegationIsStillOverTheVerifiedRequest() = runTest {
        // The quiet variant: the ECDH key is left alone, so the genuine joiner decrypts and pairs
        // and nothing looks wrong — while the delegation admits a signing key of the relay's.
        val (secret, hostPublicKey) = startSession()
        relay.answers += listOf(request(genuine, genuineSigningKey, secret))
        relay.answers += listOf(request(genuine, substituteSigningKey, secret))

        poll()
        approve()

        assertAdmittedTheGenuineJoiner(hostPublicKey)
    }

    // ── Approve without a shown SAS ─────────────────────────────────────────────────────────

    @Test
    fun aRequestUnderAnotherPairingSecret_wasNeverShown_andCannotBeApproved() = runTest {
        // The poll filters by this session's secret, so this request never reaches the screen and
        // no SAS is ever derived for it. Approve used to find it by deviceId alone.
        startSession()
        relay.answers += listOf(request(substitute, substituteSigningKey, secret = "some-other-secret"))

        assertTrue(poll().isEmpty(), "precondition: nothing is shown for a foreign secret")

        val ex = assertFailsWith<TenshuException> { approve() }
        assertTrue(
            (ex.error as GenericError).message.get().contains("Pairing request not found"),
            "the refusal must be a typed error the approval prompt can show",
        )
        assertNothingWasAdmitted()
        assertNotNull(syncManager.activePairingContext, "a refused approve leaves the session intact")
    }

    @Test
    fun theGenuineKeysUnderAnotherPairingSecret_wereNeverShown_andCannotBeApprovedEither() = runTest {
        // Same deviceId, same keys, the right name — only the secret differs. What makes a request
        // approvable is that THIS session showed a code for it, not what it carries: a request that
        // did not come through this session's QR never reaches the screen, whoever's keys it holds.
        startSession()
        relay.answers += listOf(request(genuine, genuineSigningKey, secret = "some-other-secret"))

        assertTrue(poll().isEmpty(), "precondition: nothing is shown for a foreign secret")

        assertFailsWith<TenshuException> { approve() }
        assertNothingWasAdmitted()
    }

    @Test
    fun approveBeforeAnyPoll_isRefused() = runTest {
        val (secret, _) = startSession()
        relay.answers += listOf(request(genuine, genuineSigningKey, secret))

        assertFailsWith<TenshuException> { approve() }

        assertNothingWasAdmitted()
    }

    @Test
    fun sameDeviceIdPrePostedUnderAnotherSecret_aheadOfTheGenuineRequest_cannotHijackTheApprove() = runTest {
        // Needs no relay compromise: `POST /sync/pairing/request` is unauthenticated and the
        // per-owner list is oldest-first, so anyone who knows the ownerId and the joiner's deviceId
        // can sit an entry in front of the genuine one. It is invisible to the poll (wrong secret)
        // and was the first `deviceId` match for approve.
        val (secret, hostPublicKey) = startSession()
        relay.answers += listOf(
            request(substitute, substituteSigningKey, secret = "some-other-secret"),
            request(genuine, genuineSigningKey, secret),
        )

        val shown = poll().single()
        assertEquals(sasOnJoinerScreen(hostPublicKey, genuine), shown.sas?.get())

        approve()

        assertAdmittedTheGenuineJoiner(hostPublicKey)
    }

    @Test
    fun sameDeviceIdPostedUnderAnotherSecret_behindTheGenuineRequest_changesNothing() = runTest {
        val (secret, hostPublicKey) = startSession()
        relay.answers += listOf(
            request(genuine, genuineSigningKey, secret),
            request(substitute, substituteSigningKey, secret = "some-other-secret"),
        )

        poll()
        approve()

        assertAdmittedTheGenuineJoiner(hostPublicKey)
    }

    // ── One deviceId, two claims, under THIS session's secret ───────────────────────────────

    @Test
    fun twoRequestsClaimOneDeviceId_genuineFirst_oneRowIsShown_andItIsTheOneAdmitted() = runTest {
        val (secret, hostPublicKey) = startSession()
        relay.answers += listOf(
            request(genuine, genuineSigningKey, secret),
            request(substitute, substituteSigningKey, secret),
        )

        // Approve is addressed by deviceId, so a deviceId may be on screen with ONE code only.
        val rows = poll()
        assertEquals(1, rows.size, "one deviceId must not be shown under two codes")
        val shown = rows.single()
        assertEquals(sasOnJoinerScreen(hostPublicKey, genuine), shown.sas?.get())

        approve()

        assertAdmittedTheGenuineJoiner(hostPublicKey)
    }

    @Test
    fun theSameRequestListedTwice_isShownOnce() = runTest {
        // A joiner that re-posts (a retried initiate reuses its key) is one device, one row.
        val (secret, hostPublicKey) = startSession()
        relay.answers += listOf(
            request(genuine, genuineSigningKey, secret),
            request(genuine, genuineSigningKey, secret),
        )

        val rows = poll()
        assertEquals(1, rows.size, "one deviceId is one row")
        assertEquals(sasOnJoinerScreen(hostPublicKey, genuine), rows.single().sas?.get())
    }

    @Test
    fun twoRequestsClaimOneDeviceId_substituteFirst_theCodeShownIsTheSubstitutes_soTheUserSeesAMismatch() = runTest {
        // The pin does not decide who is genuine — the human comparison does. What it guarantees
        // is that the code on the host is the code of the key that WOULD be admitted, so a
        // mismatch against the joiner's screen is visible instead of masked.
        val (secret, hostPublicKey) = startSession()
        relay.answers += listOf(
            request(substitute, substituteSigningKey, secret),
            request(genuine, genuineSigningKey, secret),
        )

        val rows = poll()
        assertEquals(1, rows.size, "one deviceId must not be shown under two codes")
        val shown = rows.single()

        assertEquals(sasOnJoinerScreen(hostPublicKey, substitute), shown.sas?.get())
        assertFalse(
            shown.sas?.get() == sasOnJoinerScreen(hostPublicKey, genuine),
            "the host's code must differ from the genuine joiner's — that is the T-P-02 tripwire",
        )
        assertContentEquals(substitute.publicKey, shown.publicKey.get())

        // The row and the pin agree — approve would admit exactly what is on screen — and the
        // genuine request, second in the same answer, did not move either.
        val pin = assertNotNull(syncManager.activePairingContext?.shownRequest(deviceId))
        assertTrue(pin.hasKeys(substitute.publicKey, substituteSigningKey))
        assertEquals(shown.sas?.get(), poll().single().sas?.get(), "and the next poll shows the same code")
    }

    // ── What was shown never changes under the user ─────────────────────────────────────────

    @Test
    fun theKeyForAShownDeviceChangesOnALaterPoll_theNewKeyIsNeverShown_andNeverAdmitted() = runTest {
        // The UI replaces its request from every poll, so a poll that re-pointed the pin while its
        // answer was still in flight would leave code X on screen over key Y. First shown wins for
        // the whole session; a different key for the same deviceId needs a new session.
        val (secret, hostPublicKey) = startSession()
        relay.answers += listOf(request(genuine, genuineSigningKey, secret))
        relay.answers += listOf(request(substitute, substituteSigningKey, secret))
        relay.answers += listOf(request(genuine, genuineSigningKey, secret))

        val first = poll().single()
        val second = poll()
        val third = poll().single()

        assertTrue(
            second.none { it.sas?.get() != first.sas?.get() },
            "a shown deviceId must never reappear under a different code",
        )
        assertTrue(second.isEmpty(), "the conflicting request is withheld, not shown")
        assertEquals(first.sas?.get(), third.sas?.get())

        approve()

        assertAdmittedTheGenuineJoiner(hostPublicKey)
    }

    @Test
    fun theNameForAShownDeviceChangesOnALaterPoll_theShownNameIsTheOneStored() = runTest {
        val (secret, _) = startSession()
        relay.answers += listOf(request(genuine, genuineSigningKey, secret, name = "Ben's Phone"))
        relay.answers += listOf(request(genuine, genuineSigningKey, secret, name = "Printer (trusted)"))

        assertEquals("Ben's Phone", poll().single().deviceName.get())
        assertEquals("Ben's Phone", poll().single().deviceName.get(), "the name on screen must not move")

        approve()

        assertEquals("Ben's Phone", savedDevice()!!.name.get())
    }

    @Test
    fun twoPollsRacingToPinOneDeviceId_agreeOnTheFirst() {
        // The op only pins when it finds no pin, but two polls can both find none. Whichever
        // loses the race must come away holding the winner's tuple, not its own.
        val context = syncManager.generatePairingContext()
        val first = SyncManager.PairingContext.ShownRequest(
            deviceId, "Ben's Phone", genuine.publicKey, genuineSigningKey, sas = "111111",
        )
        val second = SyncManager.PairingContext.ShownRequest(
            deviceId, "Not Ben's Phone", substitute.publicKey, substituteSigningKey, sas = "222222",
        )

        assertSame(first, context.pinShownRequest(first))
        assertSame(first, context.pinShownRequest(second), "a pin never moves once taken")
        assertSame(first, context.shownRequest(deviceId))
    }

    @Test
    fun aPinHoldsItsOwnCopyOfTheKeys() {
        // The arrays a request hands over stay in the caller's hands; scribbling on them
        // afterwards must not change which keys approve admits.
        val ecdh = genuine.publicKey.copyOf()
        val signing = genuineSigningKey.copyOf()
        val pin = SyncManager.PairingContext.ShownRequest(deviceId, "Ben's Phone", ecdh, signing, sas = "111111")

        ecdh.fill(0x7f)
        signing.fill(0x7f)

        assertTrue(pin.hasKeys(genuine.publicKey, genuineSigningKey))
        assertFalse(pin.hasKeys(ecdh, signing))
    }

    // ── The code covers the joiner's signing key, deviceId and name (SAS v2) ─────────────────
    //
    // Both screens are computed by the REAL ops: the joiner's code by `InitiatePairing` on its own
    // SyncManager, the host's by the poll. The relay sits between them and may rewrite what the
    // joiner posted before the host's FIRST poll — the pin cannot help with that, only the
    // transcript can.

    private class Joiner(val sas: String, val genuine: SyncRelay.RelayPairingRequest, val secret: String)

    private suspend fun joinerInitiates(hostPublicKey: ByteArray, secret: String, name: String = "Ben's Phone"): Joiner {
        val joinerManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
        )
        val joinerRelay = MockSyncRelay(testProxy.tContext)
        val initiation = InitiatePairingImplementation(joinerRelay, joinerManager, crypto)
            .execute<PairingInitiation>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = mapOf(
                    InitiatePairing.Params.OWNER_ID to "owner".toTString(testProxy.tContext),
                    InitiatePairing.Params.PAIRING_SECRET to secret.toTString(testProxy.tContext),
                    InitiatePairing.Params.DEVICE_NAME to name.toTString(testProxy.tContext),
                    InitiatePairing.Params.OTHER_DEVICE_PUBLIC_KEY to hostPublicKey.toTByteArray(testProxy.tContext),
                ),
            )
        val posted = (joinerRelay.getPairingRequests("owner") as SyncRelay.PairingRequestsFetch.Found)
            .requests.single()
        return Joiner(
            sas = initiation.sas.get(),
            // Exactly what InitiatePairing posted — read back from the relay it posted to.
            genuine = posted,
            secret = secret,
        )
    }

    /** The relay rewrites fields it can see; the sealed name it can only pass along unchanged. */
    private fun SyncRelay.RelayPairingRequest.with(
        ecdhKey: ByteArray = publicKey,
        signingKey: ByteArray = signingPublicKey,
        id: String = deviceId,
        sealed: ByteArray = sealedDeviceName,
    ) = SyncRelay.RelayPairingRequest(
        deviceId = id,
        sealedDeviceName = sealed,
        publicKey = ecdhKey,
        signingPublicKey = signingKey,
        pairingSecretHash = pairingSecretHash,
    )

    @Test
    fun theGenuineRequest_showsTheSameCodeOnBothScreens() = runTest {
        val (secret, hostPublicKey) = startSession()
        val joiner = joinerInitiates(hostPublicKey, secret)
        relay.answers += listOf(joiner.genuine)

        assertEquals(joiner.sas, poll().single().sas?.get(), "an honest relay must give matching codes")
    }

    @Test
    fun relayServesItsOwnSigningKeyFromTheFirstPoll_theRequestIsDropped() = runTest {
        // Gap 1 of T-P-14: the genuine ECDH key with the relay's signing key, from the very first
        // answer. Before the F7 AAD bound the keys only the code could catch this; now the sealed
        // name — bound to both keys it was posted with — does not open, and nothing is shown.
        val (secret, hostPublicKey) = startSession()
        val joiner = joinerInitiates(hostPublicKey, secret)
        relay.answers += listOf(joiner.genuine.with(signingKey = substituteSigningKey))

        assertTrue(poll().isEmpty(), "a genuine sealed name under the relay's signing key must not open")
    }

    @Test
    fun relayServesItsOwnEcdhKeyFromTheFirstPoll_theRequestIsDropped() = runTest {
        val (secret, hostPublicKey) = startSession()
        val joiner = joinerInitiates(hostPublicKey, secret)
        relay.answers += listOf(joiner.genuine.with(ecdhKey = substitute.publicKey))

        assertTrue(poll().isEmpty(), "a genuine sealed name under the relay's ECDH key must not open")
    }

    @Test
    fun relayRepostsTheGenuineSealedNameUnderItsOwnKeys_aheadOfTheGenuineRequest_cannotMaskIt() = runTest {
        // The DoS the key-binding AAD closes: the relay copies the genuine sealed name + deviceId
        // onto its own keys and serves that copy FIRST. Were it to open, the first-seen pin per
        // deviceId would show the forged code and withhold the genuine request until the user
        // rejected the session. Bound to the keys, the copy is dropped before it is pinned, so the
        // genuine request is the one shown — with the code on the joiner's screen.
        val (secret, hostPublicKey) = startSession()
        val joiner = joinerInitiates(hostPublicKey, secret)
        relay.answers += listOf(
            joiner.genuine.with(ecdhKey = substitute.publicKey, signingKey = substituteSigningKey),
            joiner.genuine,
        )

        val shown = poll().single()
        assertEquals(joiner.sas, shown.sas?.get(), "the genuine request must not be masked by the forged copy")
        assertContentEquals(joiner.genuine.publicKey, shown.publicKey.get())
    }

    @Test
    fun relayServesAnotherDeviceId_theRequestIsDropped() = runTest {
        // F7: the sealed name is bound to the posting deviceId, so a relay that rewrites the id
        // (and cannot reseal — it never sees the secret) serves a request that does not open.
        val (secret, hostPublicKey) = startSession()
        val joiner = joinerInitiates(hostPublicKey, secret)
        relay.answers += listOf(joiner.genuine.with(id = "some-other-device"))

        assertTrue(poll().isEmpty(), "a request whose sealed name does not open must not be shown")
    }

    @Test
    fun secretHolderServesAnotherDeviceId_theCodesDoNotMatch() = runTest {
        // Someone who DOES hold the secret (scanned the QR) can reseal for a new id; the SAS is
        // what catches that.
        val (secret, hostPublicKey) = startSession()
        val joiner = joinerInitiates(hostPublicKey, secret)
        val newId = "some-other-device"
        relay.answers += listOf(
            joiner.genuine.with(
                id = newId,
                sealed = org.tenshu.sync.proxy.security.PairingRendezvous.sealJoinDeviceName(
                    crypto, secret, newId, joiner.genuine.publicKey, joiner.genuine.signingPublicKey, "Ben's Phone",
                ),
            ),
        )

        assertFalse(poll().single().sas?.get() == joiner.sas, "a substituted deviceId must change the host's code")
    }

    @Test
    fun relayServesAnotherName_theCodesDoNotMatch() = runTest {
        // The name is in the transcript too: it is what the host shows and what the Device row
        // stores, so a chosen name ("Printer (trusted)") must not ride a matching code. After F7 a
        // relay alone cannot even produce one; a secret holder can, and the SAS catches it.
        val (secret, hostPublicKey) = startSession()
        val joiner = joinerInitiates(hostPublicKey, secret, name = "Ben's Phone")
        relay.answers += listOf(
            joiner.genuine.with(
                sealed = org.tenshu.sync.proxy.security.PairingRendezvous.sealJoinDeviceName(
                    crypto, secret, joiner.genuine.deviceId,
                    joiner.genuine.publicKey, joiner.genuine.signingPublicKey, "Printer (trusted)",
                ),
            ),
        )

        assertFalse(poll().single().sas?.get() == joiner.sas, "a substituted name must change the host's code")
    }

    @Test
    fun theApproverShowsTheJoinersNameAfterUnsealing() = runTest {
        val (secret, hostPublicKey) = startSession()
        val joiner = joinerInitiates(hostPublicKey, secret, name = "Ben's Phone")
        relay.answers += listOf(joiner.genuine)

        assertEquals("Ben's Phone", poll().single().deviceName.get())
    }

    // ── The pin lives exactly as long as the session ────────────────────────────────────────

    @Test
    fun whatWasShownInOneSession_cannotBeApprovedInTheNext() = runTest {
        val (secret, _) = startSession()
        relay.answers += listOf(request(genuine, genuineSigningKey, secret))
        poll()

        // Deny / timeout / "Done", then a fresh "Add device".
        syncManager.stopPairing()
        startSession()

        assertFailsWith<TenshuException> { approve() }
        assertNothingWasAdmitted()
    }

    @Test
    fun aPollAnsweredAfterItsSessionEnded_showsNothing_andLeavesNothingToApproveInTheNext() = runTest {
        // The op reads the pairing context BEFORE the relay round-trip. If the session ends while
        // the relay is answering, the answer belongs to a context whose private key is already
        // zeroed: a code derived from it is noise, and nothing it names may become approvable.
        val (secret, _) = startSession()
        relay.answers += listOf(request(genuine, genuineSigningKey, secret))
        relay.whileAnswering = {
            syncManager.stopPairing()
            startSession()
        }

        assertTrue(poll().isEmpty(), "an answer for an ended session must not be shown")

        relay.whileAnswering = null
        assertFailsWith<TenshuException> { approve() }
        assertNothingWasAdmitted()
    }
}

/**
 * Enough of X25519 + an AEAD to tell *who can open what*: the agreement is commutative
 * (`calc(privA, pubB) == calc(privB, pubA)`) and distinct per pair, and a blob opens only under the
 * key it was sealed with. `publicKey` and `privateKey` are distinct arrays, as real keys are, so
 * the on-teardown zeroing of the host's private key cannot alias a public key a test still holds.
 */
private class EcdhLikeCrypto : SyncCryptoManager {
    private var counter = 0

    /** Every peer public key the agreement was run against. */
    val agreedWith = mutableListOf<ByteArray>()

    /** Every key a payload was sealed under. */
    val sealedUnder = mutableListOf<ByteArray>()

    override fun generateKeyPair(): SyncCryptoManager.KeyPair {
        val tag = (++counter).toByte()
        return SyncCryptoManager.KeyPair(publicKey = ByteArray(32) { tag }, privateKey = ByteArray(32) { tag })
    }

    override fun calculateSharedSecret(privateKey: ByteArray, otherPublicKey: ByteArray): ByteArray {
        agreedWith += otherPublicKey.copyOf()
        return agree(privateKey, otherPublicKey)
    }

    private fun agree(privateKey: ByteArray, otherPublicKey: ByteArray): ByteArray {
        val a = privateKey.first()
        val b = otherPublicKey.first()
        return if (a <= b) byteArrayOf(a, b) else byteArrayOf(b, a)
    }

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray {
        sealedUnder += key.copyOf()
        return byteArrayOf(key.size.toByte()) + key + payload
    }

    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray {
        val size = encryptedPayload.first().toInt()
        check(encryptedPayload.copyOfRange(1, 1 + size).contentEquals(key)) { "MAC check failed" }
        return encryptedPayload.copyOfRange(1 + size, encryptedPayload.size)
    }

    // The F7 sealed device name rides `encryptWithAad`. Kept OUT of [sealedUnder], which records
    // only what the realm-key blob was sealed under — the subject of these tests — and bound to
    // both key and AAD, so a wrong secret or a rewritten deviceId fails to open as with a real AEAD.
    override fun encryptWithAad(plaintext: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray =
        byteArrayOf(key.size.toByte(), (associatedData.size ushr 8).toByte(), associatedData.size.toByte()) +
            key + associatedData + plaintext

    override fun decryptWithAad(ciphertext: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray {
        val k = ciphertext[0].toInt() and 0xFF
        val a = ((ciphertext[1].toInt() and 0xFF) shl 8) or (ciphertext[2].toInt() and 0xFF)
        check(ciphertext.copyOfRange(3, 3 + k).contentEquals(key)) { "MAC check failed" }
        check(ciphertext.copyOfRange(3 + k, 3 + k + a).contentEquals(associatedData)) { "AAD mismatch" }
        return ciphertext.copyOfRange(3 + k + a, ciphertext.size)
    }

    /** Could the holder of [privateKey], knowing the host's public key from the QR, open [blob]? */
    fun canOpen(blob: ByteArray, privateKey: ByteArray, hostPublicKey: ByteArray): Boolean =
        runCatching { decrypt(blob, agree(privateKey, hostPublicKey)) }.isSuccess

    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys =
        // 32-byte keys — PairingKeyBlob packs fixed-width slots.
        SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = stubEd25519Signature("identity".encodeToByteArray(), recoveryPhrase.encodeToByteArray()),
            sharedVolumeKey = stubEd25519Signature("volume".encodeToByteArray(), recoveryPhrase.encodeToByteArray()),
        )

    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = stubEd25519Signature("master".encodeToByteArray(), recoveryPhrase.encodeToByteArray())
        return SyncCryptoManager.KeyPair(seed, seed)
    }

    override fun deriveRealmEncryptionKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = stubEd25519Signature("a13".encodeToByteArray(), recoveryPhrase.encodeToByteArray())
        return SyncCryptoManager.KeyPair(seed, seed)
    }

    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        stubEd25519Signature(privateKey, message)

    override fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        signature.contentEquals(stubEd25519Signature(publicKey, message))
}

/**
 * An untrusted relay with a script: the n-th `GET /sync/pairing/requests` gets the n-th entry of
 * [answers], and the last entry answers every fetch after that. Everything else is the ordinary
 * [MockSyncRelay].
 */
private class ScriptedPairingRelay(
    private val context: TContext,
    private val delegate: SyncRelay = MockSyncRelay(context),
) : SyncRelay by delegate {

    val answers = mutableListOf<List<SyncRelay.RelayPairingRequest>>()
    var fetches = 0
        private set

    class Posted(val encryptedMasterKey: ByteArray, val newDevicePublicKey: ByteArray, val newDeviceId: String)

    var posted: Posted? = null
        private set

    /** Runs between the request being asked and answered — the relay round-trip. */
    var whileAnswering: (() -> Unit)? = null

    override suspend fun getPairingRequests(ownerId: String): SyncRelay.PairingRequestsFetch {
        val answer = answers.getOrElse(fetches) { answers.lastOrNull().orEmpty() }
        fetches++
        whileAnswering?.invoke()
        return SyncRelay.PairingRequestsFetch.Found(answer)
    }

    override suspend fun postPairingResponse(
        pairingSecretHash: String,
        encryptedMasterKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        masterDelegation: ByteArray,
        epoch: Long,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        posted = Posted(encryptedMasterKey, newDevicePublicKey, newDeviceId)
        return TResult.Success.create(context, null)
    }
}
