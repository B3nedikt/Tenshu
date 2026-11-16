package org.tenshu.sync.proxy

import org.tenshu.sync.common.RealmContext

import io.ktor.client.HttpClient
import org.tenshu.core.common.security.jwtTokenProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.SyncManager

/**
 * SR-3c-prereq: builds the per-shared-realm [ContentBlobStore] factory that
 * [RealmRegistry] uses to give each registered shared realm its own content-plane
 * client. Sibling of [perRealmDeviceRelay] — same signer, for the
 * file-content side channel (`/sync/content/{ref}`) instead of the block stream.
 *
 * Each shared realm's content store is a [RestContentBlobStore] against the **same**
 * [baseUrl] as the personal one, but with its JWT scoped to that realm's `ownerId` and
 * ⚠⚠ signed as **the device registered in that realm** — [RealmContext.deviceId] and its
 * [RealmContext.deviceSigningKeyPair] — never the personal A4 (D10, threat model T-GR-05). The
 * personal key no longer authenticates in a share, for owners or members, and its id would name
 * the personal device there. The content endpoints are JWT-scoped to the
 * issuing `ownerId`, so a chunk `PUT`/`GET` lands in the shared realm's content
 * keyspace (cross-realm denial, `docs/adr/0017-file-content-two-plane-channel.md` P3a / sync-relay-api §3.7).
 *
 * [nowEpochSec] is injected so the factory stays `commonMain`; platform wirings pass
 * `{ System.currentTimeMillis() / 1000 }`.
 */
fun sharedRealmContentBlobStoreFactory(
    baseUrl: String,
    httpClient: HttpClient,
    context: TContext,
    @Suppress("UNUSED_PARAMETER") syncManager: SyncManager,
    cryptoManager: SyncCryptoManager,
    nowEpochSec: () -> Long,
): (RealmContext) -> ContentBlobStore = { realm ->
    RestContentBlobStore(
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
    )
}
