package org.tenshu.systemtest

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.createFolder
import org.tenshu.file.client.fileService
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementSignature
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import org.tenshu.core.collection.list.toList
import org.tenshu.sync.relay.RelayConfig
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The **enforcement-on** end-to-end test: a real `:sync-relay-server` with
 * `ENTITLEMENT_ENFORCE=true` in front of real clients that present (or fail to present) a real
 * credential.
 *
 * Why this exists (`docs/tech-debt.md` → the entitlement 402 storm). Enforcement had **no**
 * end-to-end coverage of any kind, and the hole was shaped exactly like the bug that reached real
 * hardware: production wires both entitlement providers into `RestSyncRelay`, the harness wired
 * **neither**, and no test ever switched enforcement on — so no harness client could attach a
 * credential and nothing could observe a refusal. Unit tests passed throughout, because they
 * exercised the verifier and the encoder in isolation. The result was a device on which *every*
 * `POST /sync/push` was refused for ~15 minutes while every unit test stayed green.
 *
 * The three tests below pin the three states that matter, in the order they mattered on device:
 * nothing presented ⇒ refused; the owner's own synced record ⇒ accepted; and a **paired joiner**
 * that never had a commerce session, whose only possible credential is the record it received by
 * sync ⇒ also accepted. That last one is the real production shape
 * (`second-device-entitlement-enforcement.md`) and was previously unexercised anywhere.
 *
 * ⚠ Assertions read [TestSystem.syncMailbox] rather than client-side state, because a refused push
 * is **invisible from the client**: the block simply stays queued locally and the client retries.
 * "Did the relay accept this write?" can only be answered on the relay.
 *
 * `runBlocking` (not `runTest`) for the same reason as the other SystemTests: `ProxyGateway.call`
 * deadlocks against the test dispatcher.
 */
@OptIn(ExperimentalEncodingApi::class)
class EntitlementEnforcementSystemTest {

    private val ed25519 = BouncyCastleEd25519Backend()

    /**
     * A relay that actually gates writes. Pinned to the **dev** issuer key because that is the
     * keypair whose private half is committed ([EntitlementSignature.DEV_SEED]), so the test can
     * mint a genuinely-signed record; the relay verifies it with the real Ed25519 path, exactly as
     * it verifies a commerce-signed one in production.
     */
    private fun enforcingRelayConfig() = RelayConfig(
        devMode = false,
        bindHost = "127.0.0.1",
        port = 0,
        entitlementIssuerPublicKeyB64 = Base64.Default.encode(EntitlementSignature.DEV_PUBLIC_KEY),
        entitlementEnforce = true,
    )

    /**
     * Seeds the owner's signed `Entitlement` into [client]'s store — the same row the commerce
     * mint path writes on a real device, and the row the storage reader turns into the
     * `X-Entitlement-Proof` credential. Signed over exactly what the issuer signs
     * ([EntitlementSignature.SignedEntitlement.message]), so a signature regression fails here
     * rather than silently degrading to "no credential".
     */
    private suspend fun seedEntitlement(client: TestClient, ownerId: String) {
        val now = System.currentTimeMillis()
        val unsigned = EntitlementSignature.SignedEntitlement(
            ownerId = ownerId,
            publisherId = EntitlementSignature.DEV_PUBLISHER_ID,
            productId = "tenshu.drive.free",
            validFrom = now - 60_000,
            validUntil = now + 3_600_000,
            signature = ByteArray(0),
        )
        val signature = ed25519.sign(EntitlementSignature.DEV_SEED, unsigned.message())
        client.executionContext.saveData(
            Entitlement.create(
                context = client.executionContext.context,
                ownerId = unsigned.ownerId,
                publisherId = unsigned.publisherId,
                productId = unsigned.productId,
                validFrom = unsigned.validFrom,
                validUntil = unsigned.validUntil,
                publisherSignature = signature,
            )
        )
    }

    private fun blocksAccepted(system: TestSystem, ownerId: String): Int =
        system.syncMailbox.getChanges(ownerId, 0L).blocks.size

    /**
     * The **negative control** — and the exact production failure. With enforcement on and no
     * entitlement anywhere, the host onboards fine (realm creation and pairing are deliberately
     * ungated, so a user can always get in) but every gated `POST /sync/push` is refused, so
     * nothing the host writes ever reaches the relay.
     *
     * Without this test, the two positive tests below could both pass against a relay that had
     * quietly stopped enforcing anything at all.
     */
    @Test fun withNoEntitlement_everyGatedPushIsRefused() = runBlocking {
        val system = TestSystem.builder().relayConfig(enforcingRelayConfig()).build()
        try {
            val host = system.client(deviceId = "host", role = TestClientRole.RealmHost)
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = host.syncManager.currentOwnerId!!

            val created = host.tenshuClient.fileService.defaultFileSystem.createFolder("/refused")
            assertTrue(
                created is TResult.Success,
                "the LOCAL write must still succeed — enforcement gates the relay, not the device; " +
                    "got $created"
            )

            // ~20 scheduler cycles (100ms poll): the client keeps retrying and the relay keeps
            // refusing. The wait is what makes the assertion meaningful rather than merely early —
            // a "nothing happened" claim is only worth as much as the time it was given to happen.
            delay(2_000)

            assertEquals(
                0,
                blocksAccepted(system, ownerId),
                "with no entitlement presented the relay must accept NOTHING — this is the shape of " +
                    "the on-device incident, where the host's own Device row never left the host " +
                    "and the paired phone therefore never listed it"
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * The owner's own device: a signed, in-window, owner-bound record in the store becomes an
     * `X-Entitlement-Proof` on every gated write, and the relay accepts it.
     */
    @Test fun withSyncedEntitlement_pushesAreAcceptedViaTheProofHeader() = runBlocking {
        val system = TestSystem.builder().relayConfig(enforcingRelayConfig()).build()
        try {
            val host = system.client(deviceId = "host", role = TestClientRole.RealmHost)
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = host.syncManager.currentOwnerId!!

            seedEntitlement(host, ownerId)

            val created = host.tenshuClient.fileService.defaultFileSystem.createFolder("/accepted")
            assertTrue(created is TResult.Success, "host createFolder should succeed; got $created")

            system.advanceUntil(timeout = 15.seconds) { blocksAccepted(system, ownerId) > 0 }

            assertTrue(
                blocksAccepted(system, ownerId) > 0,
                "a signed, in-window, owner-bound entitlement must make gated pushes succeed; " +
                    "zero accepted blocks means the credential never reached the relay"
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠ **The production shape that nothing covered**: a paired **joiner** has no commerce session,
     * so it can never hold a bearer token — its only possible credential is the signed record it
     * receives *by sync* from the host. This is the path that failed on an iPhone
     * (`second-device-entitlement-enforcement.md`), and until now no test exercised it end to end.
     *
     * The assertion is deliberately made on the **host** observing the joiner's folder: that can
     * only happen if the joiner's own gated push was accepted.
     */
    @Test fun pairedJoiner_pushesUsingTheRecordItReceivedBySync() = runBlocking {
        val system = TestSystem.builder().relayConfig(enforcingRelayConfig()).build()
        try {
            val host = system.client(deviceId = "host", role = TestClientRole.RealmHost)
            val joiner = system.client(deviceId = "joiner", role = TestClientRole.JoiningDevice)

            host.bootstrapAsHost(deviceName = "Host Tablet")
            val ownerId = host.syncManager.currentOwnerId!!
            // Only the host is seeded — the joiner must acquire the record the way a real second
            // device does, over sync.
            seedEntitlement(host, ownerId)

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

            // The joiner writes. Its push is gated, and the only credential it can present is the
            // host's record once that has synced down and the storage reader has derived it.
            val joinerPath = "/from-joiner"
            val created = joiner.tenshuClient.fileService.defaultFileSystem.createFolder(joinerPath)
            assertTrue(created is TResult.Success, "joiner createFolder should succeed; got $created")

            system.advanceUntil(timeout = 30.seconds) {
                host.executionContext.rootFilePaths().contains(joinerPath)
            }

            assertTrue(
                host.executionContext.rootFilePaths().contains(joinerPath),
                "the host must see the joiner's folder — which is only possible if the joiner's " +
                    "own gated push was accepted, i.e. it presented the signed record it received " +
                    "by sync as X-Entitlement-Proof (a session-less device has no other credential)"
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * Store-level read of the root folder's `FILES` list as paths — the same shape
     * `FolderSyncSystemTest` uses, so the assertion is "what the SyncProcessor actually wrote into
     * this device's local DB", not "what the SDK returned over the wire". (Private per test class,
     * matching the existing SystemTests; the duplication is pre-existing and tracked separately.)
     */
    private fun ExecutionContext.rootFilePaths(): Set<String> =
        rootFiles().map { entry ->
            when (entry) {
                is Entity<*> -> entry.getFieldValue<TString>(File.Field.PATH).get()
                else -> (entry as org.tenshu.core.Composite)
                    .getField<TString>(File.Field.PATH).get()
            }
        }.toSet()

    private fun ExecutionContext.rootFiles(): List<org.tenshu.core.Data> {
        val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = TList.ID,
                parentEntity = rootId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        return getArrayData(ref)?.toList() ?: emptyList()
    }
}
