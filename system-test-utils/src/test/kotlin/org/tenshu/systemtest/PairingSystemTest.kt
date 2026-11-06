package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.identity.client.identityService
import org.tenshu.identity.getDevices
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.call.save
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsBytes

/**
 * The proposal §5 case study. Two real `TestClient`s share one in-process
 * `:sync-relay-server`; the host bootstraps a realm, generates a pairing
 * URI, the joiner uses the URI to submit a pairing request, the host
 * approves, and the joiner observes that it now belongs to the host's
 * realm.
 *
 * Validates every wire layer: app↔proxy via `LocalClientConnection`,
 * proxy↔relay via real `RestSyncRelay` over `testApplication.client`,
 * real master-delegation verification on `POST /sync/pairing/response`,
 * real X25519 ECDH for the SAS derivation.
 *
 * `runBlocking` rather than `runTest` for the same reason as
 * `BootstrapSystemTest` — `ProxyGateway.call` deadlocks against the test
 * dispatcher.
 */
class PairingSystemTest {

    @Test fun fullHandshake_joinerEntersHostsRealm() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

            // 1. Host bootstraps a fresh realm.
            val bootstrap = host.bootstrapAsHost(deviceName = "Host Tablet")
            assertTrue(bootstrap is TResult.Success, "createRealm should succeed; got $bootstrap")
            val hostOwnerId = assertNotNull(host.syncManager.currentOwnerId)

            // 2. Host enters pairing mode + emits a pairing URI.
            val startResult = host.tenshuClient.syncService.startPairing()
            assertTrue(startResult is TResult.Success, "startPairing should succeed; got $startResult")
            val uri = assertNotNull(
                host.syncManager.getPairingString(),
                "Host should have a pairing URI after startPairing"
            )
            val parsed = PairingUri.parse(uri)
            assertEquals(hostOwnerId, parsed.ownerId, "URI ownerId should match host realm")

            // 3. Joiner submits a pairing request via real `InitiatePairing`.
            // Generates a fresh X25519 keypair, derives the SAS via ECDH against
            // `parsed.hostPairingPublicKey`, and POSTs the request to the relay.
            val initResult = joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            assertTrue(initResult is TResult.Success, "initiatePairing should succeed; got $initResult")
            val joinerInitiation = assertNotNull(initResult.value)
            val joinerSas = joinerInitiation.sas.get()

            // 4. Host polls; the impl derives SAS from the host's stored privKey
            // + the joiner's pubKey via the same ECDH; both sides must match.
            val pendingResult = host.tenshuClient.syncService.getPendingPairingRequests()
            assertTrue(pendingResult is TResult.Success, "getPendingPairingRequests should succeed; got $pendingResult")
            val pending = assertNotNull(pendingResult.value).toList()
            assertEquals(1, pending.size, "Host should see exactly one pending request; got ${pending.size}")
            val request = pending.single()
            assertEquals("Joiner Phone", request.deviceName.get())
            assertEquals(
                joinerSas,
                requireNotNull(request.sas) { "Host's enriched request must carry SAS" }.get(),
                "Host and joiner must derive the same SAS"
            )

            // 5. Host approves. The impl signs a master delegation for the joiner,
            // encrypts the master key under the joiner's pairing pubKey, and POSTs
            // `/sync/pairing/response`. The relay verifies and registers the joiner
            // in `KnownDevicesStore`.
            val joinerDeviceId = request.deviceId.get()
            val approveResult = host.tenshuClient.syncService.approvePairingRequest(joinerDeviceId)
            assertTrue(approveResult is TResult.Success, "approvePairingRequest should succeed; got $approveResult")

            // 6. Joiner polls. The impl decrypts the master key, initialises the
            // joiner's SyncManager into the host's realm, and self-pushes the
            // joiner's Device entity.
            system.advanceUntil(timeout = 15.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            // 7. Joiner now belongs to the host's realm.
            assertEquals(
                hostOwnerId,
                joiner.syncManager.currentOwnerId,
                "Joiner should have adopted the host's ownerId"
            )

            // 8. Relay registered the joiner in KnownDevicesStore (master
            // delegation signature verified server-side).
            assertTrue(
                joinerDeviceId in system.knownDevices.deviceIds(hostOwnerId),
                "Joiner deviceId=$joinerDeviceId should be in relay's KnownDevicesStore"
            )

            // 9. The pubkey the relay holds for the joiner must be the
            // joiner's Ed25519 device signing key (not its X25519 pairing
            // key). Regression for the JWT-key-mismatch bug — registering
            // the wrong key here breaks every later JWT-authed push from
            // the joiner with a 401 (verified by `pairedDevices_…` driver).
            val registeredKey = system.knownDevices.getPublicKey(hostOwnerId, joinerDeviceId)
            assertEquals(
                joiner.syncManager.getDeviceSigningKeyPair().publicKey.toList(),
                registeredKey?.toList(),
                "Relay must register the joiner's Ed25519 device signing key, not its X25519 pairing key"
            )

            // 10. Workstream B, end to end: every id the relay holds is its key's — the joiner's
            // (admitted by the host's A1 delegation) and the host's own (registered at genesis).
            assertEquals(
                org.tenshu.sync.common.deviceIdForPublicKey(joiner.syncManager.getDeviceSigningKeyPair().publicKey),
                joinerDeviceId,
                "the joiner's id must be derived from its A4 key",
            )
            assertEquals(joiner.syncManager.currentDeviceId, joinerDeviceId)
            for (id in system.knownDevices.deviceIds(hostOwnerId)) {
                val key = assertNotNull(system.knownDevices.getPublicKey(hostOwnerId, id))
                assertTrue(
                    org.tenshu.sync.common.deviceIdMatchesKey(id, key),
                    "relay holds $id under a key it was not derived from",
                )
            }
            assertTrue(host.syncManager.currentDeviceId in system.knownDevices.deviceIds(hostOwnerId))
        } finally {
            system.shutdown()
        }
    }

    /**
     * Workstream B, end to end over the real relay: a host holding A1 — honest code, but say a
     * compromised build — signs a perfectly valid delegation that registers the joiner's REAL key
     * under ANOTHER device's id (a squat on the host's own id, the one id it can be sure exists).
     * Before workstream B the relay registered whatever pair the A1 signature covered; now the
     * relay refuses the pair outright, and neither the victim's registration nor the queue moves.
     */
    @Test fun anA1SignedAdmissionOfAKeyUnderAnotherDevicesId_isRefusedByTheRelay() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)
            val bootstrap = host.bootstrapAsHost(deviceName = "Host Tablet")
            assertTrue(bootstrap is TResult.Success, "createRealm should succeed; got $bootstrap")
            val ownerId = assertNotNull(host.syncManager.currentOwnerId)
            val victimsId = host.syncManager.currentDeviceId
            val victimsKey = assertNotNull(system.knownDevices.getPublicKey(ownerId, victimsId)).copyOf()

            val joinerKey = joiner.syncManager.getDeviceSigningKeyPair().publicKey
            val epoch = host.syncManager.currentRealmEpoch
            val crypto = host.koinApp.koin.get<org.tenshu.core.security.SyncCryptoManager>()
            val forged = crypto.signEd25519(
                assertNotNull(host.syncManager.currentMasterKeyPair).privateKey,
                org.tenshu.sync.common.pairingDelegationMessage(joinerKey, victimsId, epoch),
            )
            val relay = host.koinApp.koin.get<org.tenshu.sync.common.SyncRelay>()
            val posted = relay.postPairingResponse(
                pairingSecretHash = "ab".repeat(32),
                encryptedMasterKey = ByteArray(16),
                newDevicePublicKey = joinerKey,
                newDeviceId = victimsId,
                masterDelegation = forged,
                epoch = epoch,
                admissionCertChain = null,
                admittedAtHlc = null,
            )
            assertTrue(posted is TResult.Failure, "the relay must refuse a squatted id; got $posted")
            assertEquals(
                victimsKey.toList(),
                system.knownDevices.getPublicKey(ownerId, victimsId)?.toList(),
                "the victim's registration must be untouched",
            )
            assertTrue(
                system.syncMailbox.getPairingResponse("ab".repeat(32)) == null,
                "nothing may be queued for delivery",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * Post-pairing each device's `IdentityService.getDevices()` returns both
     * Device entities. Regression for the "phone sees only itself, desktop
     * sees both" UI bug, which had two layers — both fixed here:
     *
     *  - **Layer 1 (JWT key mismatch).** Pairing registered the joiner's
     *    X25519 ECDH pairing pubkey in `KnownDevicesStore` instead of its
     *    Ed25519 device signing key, so JWT-authed pushes from the joiner
     *    returned 401 and `Device` blocks never reached the relay's
     *    mailbox. Fixed by `PairingRequest.signingPublicKey`.
     *  - **Layer 2 (entity-vs-property block routing).** Wire deserialization
     *    round-tripped a null `targetPropertyId` field to an empty-but-non-null
     *    `TString`, so the proxy's `if (block.targetPropertyId != null)` routed
     *    every entity block pulled through the relay into the property branch
     *    (a no-op log) instead of the entity merge path. The field has since
     *    been removed entirely (zero-trust: `docs/sync/threat-model.md`
     *    §"Property-ID exposure to relay") — the entity/property distinction
     *    now lives inside the encrypted `DataBlockPayload`.
     *
     * Hidden in the existing test suite by `MockSyncRelay` skipping wire
     * serialization in `RevokeDevicePropagationTest` (the only prior
     * two-proxy test) — Layer 2 only fires through the real wire, which
     * SystemTests are the first suite to exercise.
     */
    /**
     * F7 (`relay-metadata-minimisation.md`), end to end over the real relay and real crypto: across
     * the whole join flow — request, poll, approve, peek, ack — **no request URI or body, no
     * pairing response body and no stored row carries the raw pairing secret or the joiner's
     * clear device name**, and the host still shows the right name after unsealing.
     */
    @Test fun theRelayNeverSeesTheJoinSecretOrTheClearDeviceName() = runBlocking {
        val system = TestSystem.builder().build()
        val wire = java.util.Collections.synchronizedList(mutableListOf<String>())
        system.relayHttpClient.plugin(io.ktor.client.plugins.HttpSend).intercept { request ->
            val path = request.url.buildString()
            val body = when (val content = request.body) {
                is io.ktor.http.content.TextContent -> content.text
                is io.ktor.http.content.OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                else -> ""
            }
            val call = execute(request)
            if ("/sync/pairing/" in path) {
                wire += "REQ $path $body"
                val saved = call.save()
                wire += "RESP $path " + saved.response.bodyAsBytes().decodeToString()
                saved
            } else {
                call
            }
        }
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)
            assertTrue(host.bootstrapAsHost(deviceName = "Host Tablet") is TResult.Success)
            assertTrue(host.tenshuClient.syncService.startPairing() is TResult.Success)
            val parsed = PairingUri.parse(assertNotNull(host.syncManager.getPairingString()))
            val joinerName = "Joiner Phone F7-canary"

            val initResult = joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = joinerName,
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            assertTrue(initResult is TResult.Success, "initiatePairing should succeed; got $initResult")

            // Stored row: the hash and the sealed name only.
            val stored = system.syncMailbox.getPairingRequests(parsed.ownerId).single()
            assertTrue(stored.pairingSecretHash != parsed.secret, "the relay must store the hash, not the secret")
            assertTrue(Regex("^[0-9a-f]{64}$").matches(stored.pairingSecretHash))
            assertTrue(
                !stored.sealedDeviceName.decodeToString().contains("F7-canary"),
                "the stored sealed name must not contain the clear name",
            )

            // The approver unseals and shows the right name, with the matching code.
            val shown = host.tenshuClient.syncService.getPendingPairingRequests()
                .valueOrFail("getPendingPairingRequests")!!.toList().single()
            assertEquals(joinerName, shown.deviceName.get(), "the approver must show the unsealed name")
            assertEquals(assertNotNull(initResult.value).sas.get(), shown.sas?.get())

            assertTrue(host.tenshuClient.syncService.approvePairingRequest(shown.deviceId.get()) is TResult.Success)
            system.advanceUntil(timeout = 15.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey,
                )
                status is TResult.Success && status.value?.get() == true
            }
            assertEquals(host.syncManager.currentOwnerId, joiner.syncManager.currentOwnerId)

            // The wire: every pairing call and its response.
            val routes = wire.filter { it.startsWith("REQ") }.map { it.substringAfter("/sync/pairing/").substringBefore(' ') }
            assertTrue(routes.any { it.startsWith("request") }, "saw the join request: $routes")
            assertTrue(routes.any { it.startsWith("response/peek") }, "saw the peek: $routes")
            assertTrue(routes.any { it.startsWith("ack") }, "saw the ack: $routes")
            wire.forEach { line ->
                assertTrue(parsed.secret !in line, "the raw pairing secret reached the relay: $line")
                assertTrue("F7-canary" !in line, "the clear device name reached the relay: $line")
                assertTrue("pairingSecret=" !in line && "?pairingSecret" !in line, "no secret in a URI: $line")
            }
            // …and the response mailbox was consumed and retired.
            assertTrue(system.syncMailbox.isPairingResponseConsumed(stored.pairingSecretHash))
        } finally {
            system.shutdown()
        }
    }

    @Test fun pairedDevices_eventuallySeeEachOther_inGetDevices() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

            host.bootstrapAsHost(deviceName = "Host Tablet")
            host.tenshuClient.syncService.startPairing()
            val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)

            joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            val request = host.tenshuClient.syncService.getPendingPairingRequests()
                .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
            val joinerDeviceId = request.deviceId.get()
            host.tenshuClient.syncService.approvePairingRequest(joinerDeviceId)
            system.advanceUntil(timeout = 15.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            val hostDeviceId = host.syncManager.currentDeviceId

            // Wait for the scheduler on both sides to drain pending blocks.
            // `pollingIntervalMs = 100` in `systemTestPlatformModule`.
            system.advanceUntil(timeout = 15.seconds) {
                val joinerView = joiner.tenshuClient.identityService.getDevices()
                val hostView = host.tenshuClient.identityService.getDevices()
                joinerView is TResult.Success &&
                    hostView is TResult.Success &&
                    joinerView.value!!.toList().map { it.deviceId.get() }
                        .containsAll(setOf(hostDeviceId, joinerDeviceId)) &&
                    hostView.value!!.toList().map { it.deviceId.get() }
                        .containsAll(setOf(hostDeviceId, joinerDeviceId))
            }

            val hostView = host.tenshuClient.identityService.getDevices()
            val joinerView = joiner.tenshuClient.identityService.getDevices()
            val hostIds = (hostView as TResult.Success).value!!.toList().map { it.deviceId.get() }
            val joinerIds = (joinerView as TResult.Success).value!!.toList().map { it.deviceId.get() }
            // List equality (not set equality) — guards against duplicate Device
            // entries surfacing on either side. UI bug from 2026-05-10 had the
            // host's Device appearing 5× on both sides; `toSet()` would have
            // hidden it.
            assertEquals(
                listOf(hostDeviceId, joinerDeviceId).sorted(),
                hostIds.sorted(),
                "Host should see exactly its own and joiner's Device once each; got $hostIds"
            )
            assertEquals(
                listOf(hostDeviceId, joinerDeviceId).sorted(),
                joinerIds.sorted(),
                "Joiner should see exactly its own and host's Device once each; got $joinerIds"
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * Reproduces the "joiner can't see itself in the dashboard" UI bug from
     * 2026-05-10. The dashboard ViewModel filters `getDevices()` by
     * `dev.isAuthorized.get()` to hide pending pairing requests — but the
     * joiner's own self-pushed `Device` was previously written with
     * `isAuthorized = false` and only flipped to `true` after Device A's
     * authoritative block round-tripped via `/sync/changes`. For the duration
     * of that round-trip the joiner's UI showed only the host, with the
     * joiner itself filtered out.
     *
     * Asserts the same `isAuthorized` filter the dashboard applies, *immediately*
     * after `getPairingStatus` returns success — no extra `advanceUntil` to let
     * the host's block catch up. Pre-fix this fails (`[hostDeviceId]` only on
     * the joiner side); post-fix the joiner self-pushes `isAuthorized = true`
     * because reaching that branch already proves Device A's master delegation
     * cleared the relay.
     */
    @Test fun joinerSelfDevice_isAuthorizedImmediatelyAfterPairing() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

            host.bootstrapAsHost(deviceName = "Host Tablet")
            host.tenshuClient.syncService.startPairing()
            val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)

            joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            val request = host.tenshuClient.syncService.getPendingPairingRequests()
                .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
            host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())

            system.advanceUntil(timeout = 15.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            // Mirror the dashboard ViewModel's filter exactly. No
            // `advanceUntil` here on purpose — the joiner needs to be visible
            // to itself the *moment* pairing succeeds, not after the next
            // `/sync/changes` poll.
            val joinerView = joiner.tenshuClient.identityService.getDevices()
            val authorisedIds = (joinerView as TResult.Success).value!!.toList()
                .filter { it.isAuthorized.get() }
                .map { it.deviceId.get() }
                .toSet()
            assertTrue(
                joiner.syncManager.currentDeviceId in authorisedIds,
                "Joiner must see its own authorised Device immediately after pairing " +
                    "(dashboard filters by isAuthorized); got $authorisedIds"
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * Threat model T-P-14 gap 1, end to end with real X25519 and the real relay: between the
     * joiner's post and the host's FIRST poll the relay swaps in its own Ed25519 signing key and
     * keeps everything else. Before SAS v2 the codes matched and the host delegated admission to
     * the relay's key; v2 made the codes differ. Since F7 the sealed name's AAD binds both keys
     * (T-P-16), so the swapped request does not even open: the host drops it before it is pinned
     * or shown, and the relay cannot use the copy to mask the genuine request either.
     */
    @Test fun aSigningKeySubstitutedBeforeTheFirstPoll_isDroppedByTheHost() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)
            val bootstrap = host.bootstrapAsHost(deviceName = "Host Tablet")
            assertTrue(bootstrap is TResult.Success, "createRealm should succeed; got $bootstrap")
            assertTrue(host.tenshuClient.syncService.startPairing() is TResult.Success)
            val parsed = PairingUri.parse(assertNotNull(host.syncManager.getPairingString()))

            val initResult = joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            assertTrue(initResult is TResult.Success, "initiatePairing should succeed; got $initResult")
            val joinerSas = assertNotNull(initResult.value).sas.get()

            // The relay rewrites the stored request: genuine ECDH key, id and name; its own key.
            val genuine = system.syncMailbox.getPairingRequests(parsed.ownerId).single()
            val relaysOwnKey = ByteArray(32) { 0x5a }
            system.syncMailbox.removePairingRequest(parsed.ownerId, genuine.pairingSecretHash)
            system.syncMailbox.addPairingRequest(parsed.ownerId, genuine.copy(signingPublicKey = relaysOwnKey))

            val pending = host.tenshuClient.syncService.getPendingPairingRequests()
            assertTrue(pending is TResult.Success, "the poll should succeed; got $pending")
            assertTrue(
                assertNotNull(pending.value).toList().isEmpty(),
                "a genuine sealed name reposted under the relay's signing key must not open",
            )

            // And the genuine request, restored, is shown with the joiner's code — the forged copy
            // pinned nothing.
            system.syncMailbox.removePairingRequest(parsed.ownerId, genuine.pairingSecretHash)
            system.syncMailbox.addPairingRequest(parsed.ownerId, genuine)
            val again = host.tenshuClient.syncService.getPendingPairingRequests()
            assertTrue(again is TResult.Success, "the poll should succeed; got $again")
            assertEquals(joinerSas, assertNotNull(again.value).toList().single().sas?.get())
        } finally {
            system.shutdown()
        }
    }
}
