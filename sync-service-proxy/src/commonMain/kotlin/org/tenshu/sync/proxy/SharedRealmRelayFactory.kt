package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import org.tenshu.core.common.security.jwtTokenProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay

/**
 * SR-3b.4c: builds the per-shared-realm [SyncRelay] factory that
 * [RealmRegistry] uses to give each registered shared realm its own relay client
 * (`RealmRegistry(..., relayFactory = …)` / `initSyncService(sharedRealmRelayFactory = …)`).
 *
 * Each shared realm's relay is a [RestSyncRelay] against the **same** [baseUrl] as the
 * personal relay, but with its JWT scoped to that realm's `ownerId` — signed with the
 * device's **same A4 key** ([SyncManager.getDeviceSigningKeyPair]). The device is a
 * registered member of the shared realm (created it, or was admitted via
 * `POST /sync/admit-device`, SR-3b.4b), so that JWT verifies on the shared stream.
 *
 * [nowEpochSec] is injected (rather than calling a platform clock here) so the factory
 * stays in `commonMain`; platform wirings pass `{ System.currentTimeMillis() / 1000 }`.
 */
fun sharedRealmRelayFactory(
    baseUrl: String,
    httpClient: HttpClient,
    context: TContext,
    syncManager: SyncManager,
    cryptoManager: SyncCryptoManager,
    nowEpochSec: () -> Long,
): (String) -> SyncRelay = { sharedOwnerId ->
    RestSyncRelay(
        baseUrl = baseUrl,
        httpClient = httpClient,
        context = context,
        authTokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = nowEpochSec,
            ownerIdProvider = { sharedOwnerId },
        ),
        ownerIdProvider = { sharedOwnerId },
    )
}
