package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import org.tenshu.core.common.security.jwtTokenProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncRelay

/**
 * A shared-realm [SyncRelay] whose JWT is signed with **the realm's own
 * `RealmContext` device key + device id** — not the device's personal A4
 * ([sharedRealmRelayFactory] signs with the personal key, correct for the owner
 * and same-user joiners whose `RealmContext` already carries the personal key).
 *
 * A **cross-user member** (`shared-realm-membership-grant.md` D2) is admitted with
 * a *fresh per-realm device key* + a *realm-local device id* so the relay cannot
 * correlate their shared-realm presence with their personal `ownerId` (the Q30
 * unlinkability the threat model's A26/surface #12 rests on). Their realm JWT must
 * therefore be signed with that per-realm key — which lives on the joiner
 * `RealmContext` the grant accept (or self-bind) registers. The membership-grant
 * orchestration builds this relay and registers it explicitly
 * (`RealmRegistry.register(realm, relay)`), bypassing the personal-key factory.
 */
fun perRealmDeviceRelay(
    baseUrl: String,
    httpClient: HttpClient,
    context: TContext,
    cryptoManager: SyncCryptoManager,
    realm: RealmContext,
    nowEpochSec: () -> Long,
): SyncRelay = RestSyncRelay(
    baseUrl = baseUrl,
    httpClient = httpClient,
    context = context,
    authTokenProvider = jwtTokenProvider(
        ownerIdProvider = { realm.ownerId },
        deviceIdProvider = { realm.deviceId },
        signer = { msg ->
            cryptoManager.signEd25519(
                privateKey = realm.deviceSigningKeyPair.privateKey,
                message = msg,
            )
        },
        nowEpochSec = nowEpochSec,
    ),
    ownerIdProvider = { realm.ownerId },
)
