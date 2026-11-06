package org.tenshu.systemtest

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.sync.client.syncService
import org.tenshu.sync.relay.SyncMailbox
import org.tenshu.sync.proxy.security.PairingRendezvous
import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One malformed pairing request must not deny the host every *other* pending request.
 *
 * `GetPendingPairingRequests` enriches each request it shows with the §6.3 SAS, which runs
 * `cryptoManager.calculateSharedSecret(ctx.privateKey, request.publicKey)`. X25519 `require`s a
 * 32-byte peer key and the agreement itself rejects a degenerate point, so a request carrying a
 * bad key **throws** — and the throw escapes the whole op, not just that one request. The
 * error-channel catch-all then turns the host's entire poll into a `TResult.Failure`: the host
 * stops seeing the legitimate joiner it is actually trying to pair with.
 *
 * Both layers are covered here, in the order they defend:
 *  1. `POST /sync/pairing/request` is necessarily **unauthenticated** (an unpaired joiner has no
 *     credentials yet), so it now rejects a key it cannot store meaningfully — an ordinary hostile
 *     or broken peer never reaches a host.
 *  2. ⚠ The one that matters: a request injected **past** that route still must not wedge the
 *     host. House Rule #1 makes the relay untrusted — a compromised relay forging pairing requests
 *     is T-P-03 — so the second half writes straight into the relay's mailbox.
 *
 * Found while chasing the one-off `getPendingPairingRequests` failure in `pairHostAndJoiner`
 * (docs/tech-debt.md § "The pairing bring-up can fail its relay poll"). ⚠ It is **not** that flake's
 * trigger — the harness's joiners send well-formed keys — but it is the same failure path, and it
 * is a real defect on its own.
 */
class MalformedPairingRequestSystemTest {

    @Test
    fun aMalformedRequestDoesNotHideTheLegitimateOne() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

            val bootstrap = host.bootstrapAsHost(deviceName = "Host Tablet")
            assertTrue(bootstrap is TResult.Success, "host bootstrap should succeed; got $bootstrap")
            val started = host.tenshuClient.syncService.startPairing()
            assertTrue(started is TResult.Success, "startPairing should succeed; got $started")
            val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)

            val initiated = joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            assertTrue(initiated is TResult.Success, "initiatePairing should succeed; got $initiated")

            val before = host.tenshuClient.syncService.getPendingPairingRequests()
            assertTrue(before is TResult.Success, "baseline poll should succeed; got $before")
            val legitimate = before.value!!.toList().single()
            val legitimateDeviceId = legitimate.deviceId.get()

            // Layer 1 — the relay's own route turns a bad key away, so an ordinary hostile or
            // broken peer never gets one into the mailbox at all.
            val rejected = system.relayHttpClient.post("/sync/pairing/request") {
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "ownerId" to parsed.ownerId,
                        "pairingSecretHash" to PairingRendezvous.joinSecretHash(parsed.secret),
                        "deviceId" to "rejected-at-the-door",
                        "sealedDeviceName" to Base64.getEncoder().encodeToString(ByteArray(66)),
                        "publicKey" to Base64.getEncoder().encodeToString(byteArrayOf(0x01)),
                        "signingPublicKey" to Base64.getEncoder().encodeToString(ByteArray(32)),
                    )
                )
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                rejected.status,
                "the relay should reject an unusable key at the door",
            )

            // Layer 2 — the one that matters. House Rule #1 makes the relay untrusted, so the
            // host must survive junk that arrives *anyway*: a compromised relay injecting
            // requests is T-P-03, and it does not go through the route's validation. Injected
            // straight into the mailbox to model exactly that.
            //
            // Two shapes, because they throw in *different* places — the wrong length trips
            // X25519's `require`, while the all-zero key is a small-order point the agreement
            // itself rejects.
            val badKeys = mapOf(
                "malformed-length" to byteArrayOf(0x01),
                "degenerate-point" to ByteArray(32),
            )
            badKeys.forEach { (deviceId, publicKey) ->
                system.syncMailbox.addPairingRequest(
                    parsed.ownerId,
                    SyncMailbox.PairingRequestData(
                        deviceId = deviceId,
                        // Sealed under the real secret, so the junk gets past the unseal and
                        // reaches the SAS derivation this test is about.
                        sealedDeviceName = PairingRendezvous.sealJoinDeviceName(
                            createDefaultSyncCryptoManager(), parsed.secret, deviceId,
                            publicKey, ByteArray(32), "Bad Peer ($deviceId)",
                        ),
                        publicKey = publicKey,
                        signingPublicKey = ByteArray(32),
                        pairingSecretHash = PairingRendezvous.joinSecretHash(parsed.secret),
                    ),
                )
            }

            val after = host.tenshuClient.syncService.getPendingPairingRequests()
            assertTrue(
                after is TResult.Success,
                "one malformed request must not fail the whole poll; got $after" +
                    ((after as? TResult.Failure)?.let { " / error=${it.error}" } ?: ""),
            )
            val visible = after.value!!.toList().map { it.deviceId.get() }
            assertTrue(
                legitimateDeviceId in visible,
                "the legitimate joiner must stay visible; saw $visible",
            )
            assertEquals(
                listOf(legitimateDeviceId),
                visible,
                "a request whose SAS cannot be derived must be dropped, not shown unverifiable",
            )
        } finally {
            runCatching { system.shutdown() }
        }
    }
}
