package org.tenshu.systemtest

import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.time.Duration.Companion.seconds

/**
 * Bootstrap [host] as a new realm and admit [joiner] to it through the real pairing handshake:
 * `startPairing` → `initiatePairing` → `approvePairingRequest` → the joiner's `getPairingStatus`
 * reports paired. The handshake most system tests copied into a private `pair(…)`.
 *
 * Works in both of the harness's modes. In a stepped system ([TestSystemBuilder.stepped]) the wait
 * for the joiner is [TestSystem.syncUntil] over the pair, so it costs rounds rather than time; in a
 * polled one it is [TestSystem.advanceUntil], as before.
 *
 * ⚠ Paired is not caught up. The joiner has its keys when this returns and has pulled nothing it
 * was not already handed; a test that needs the host's rows on the joiner waits for those rows.
 */
suspend fun TestSystem.pair(
    host: TestClient,
    joiner: TestClient,
    hostName: String = "Host",
    joinerName: String = "Joiner",
) {
    host.bootstrapAsHost(deviceName = hostName).valueOrFail("bootstrapAsHost($hostName)")
    host.tenshuClient.syncService.startPairing().valueOrFail("startPairing")
    val parsed = PairingUri.parse(requireNotNull(host.syncManager.getPairingString()) { "no pairing string on the host" })
    joiner.tenshuClient.syncService.initiatePairing(
        ownerId = parsed.ownerId,
        pairingSecret = parsed.secret,
        deviceName = joinerName,
        otherDevicePublicKey = parsed.hostPairingPublicKey,
    ).valueOrFail("initiatePairing")
    val request = host.tenshuClient.syncService.getPendingPairingRequests()
        .valueOrFail("getPendingPairingRequests")!!.toList().single()
    host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get()).valueOrFail("approvePairingRequest")
    val paired: suspend () -> Boolean = {
        val status = joiner.tenshuClient.syncService.getPairingStatus(
            pairingSecret = parsed.secret,
            otherDevicePublicKey = parsed.hostPairingPublicKey,
        )
        status is TResult.Success && status.value?.get() == true
    }
    if (stepped) {
        syncUntil("$joinerName is paired to $hostName", devices = listOf(host, joiner), predicate = paired)
    } else {
        advanceUntil(timeout = 60.seconds, predicate = paired)
    }
}
