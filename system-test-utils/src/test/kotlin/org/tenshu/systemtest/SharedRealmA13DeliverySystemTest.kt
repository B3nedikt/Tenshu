package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.startPairing
import org.tenshu.sync.initiatePairing
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * SR-3b.4 option B end-to-end: pairing delivers the realm's A13 (X25519 encryption
 * sub-key) to a phrase-less joiner. After a real pairing handshake over the dev relay
 * (real crypto), the joiner's `getRealmEncryptionKeyPair()` must equal the host's —
 * i.e. the joiner can now unwrap shared-realm master keys (`Kr`) wrapped to A13, which
 * is what lets it auto-join a shared realm the host creates.
 *
 * The joiner never receives the recovery phrase (only the derived keys + A13 ride the
 * pairing key-blob), so this is the direct proof that the §3.1 "phrase-less joiners
 * can't get A13" blocker is closed. (Actually *syncing* a shared realm's stream still
 * needs relay-side device admission — SR-3b.4 part 2; out of scope here.)
 */
class SharedRealmA13DeliverySystemTest {

    @Test
    fun pairing_deliversRealmA13ToJoiner(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairHostAndJoiner(system)

            val hostA13 = host.syncManager.getRealmEncryptionKeyPair()
            assertNotNull(hostA13, "host derives A13 from its phrase")

            val joinerA13 = joiner.syncManager.getRealmEncryptionKeyPair()
            assertNotNull(joinerA13, "joiner received A13 via the pairing key-blob")

            assertTrue(
                hostA13.privateKey.contentEquals(joinerA13.privateKey),
                "the joiner's A13 private key matches the host's — it can unwrap shared-realm Kr",
            )
            assertTrue(
                hostA13.publicKey.contentEquals(joinerA13.publicKey),
                "the joiner's A13 public key matches the host's",
            )
        } finally {
            system.shutdown()
        }
    }

    private suspend fun pairHostAndJoiner(system: TestSystem): Pair<TestClient, TestClient> {
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

        return host to joiner
    }
}
