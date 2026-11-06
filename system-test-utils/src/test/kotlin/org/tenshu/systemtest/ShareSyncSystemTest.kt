package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.toList
import org.tenshu.core.equalTo
import org.tenshu.core.file.share.Share
import org.tenshu.core.file.share.ShareLinkResult
import org.tenshu.core.file.share.revokeShareLink
import org.tenshu.core.file.share.revoked
import org.tenshu.core.file.share.shareId
import org.tenshu.core.file.share.shareLink
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
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

/**
 * Phase-1 canonical DoD for `docs/file-sharing.md`: the
 * Mode-A `Share` composite is `Syncable(Eager)`, so a host-minted share must
 * land in every paired device's *My shares* projection (the realm-wide
 * `FileService.shares` collection); a host-revoked share must propagate the
 * `revoked = true` flag to every device.
 *
 * Mirrors [PreferenceSyncSystemTest] / [NotificationSyncSystemTest] structurally
 * — pairing + SDK chain read against `fileService.shares`. The chain shape is
 * the same one [TenshuFilesRepository.shares] uses in production.
 *
 * Focus here is the **entity-sync** layer: that the `Share` composite propagates
 * over the ordered drain (mint → joiner *My shares*; revoke → joiner flag flip).
 * The harness now wires a real `RestShareRelayClient` (mirroring production), so
 * the mint below also uploads to the relay's `/share/...` content plane — hence
 * the file must exist first. The sharer→relay→recipient content round-trip (POST
 * descriptor / PUT chunk / anonymous GET + decrypt) is pinned separately by
 * [ShareContentPlaneSystemTest].
 */
class ShareSyncSystemTest {

    @Test fun joinerSdkChain_findsHostMintedShare(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairHostAndJoiner(system)

            // The relay-wired `ShareLink` re-encrypts + uploads the file's bytes,
            // so the file has to exist locally first.
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "report.pdf", data = REPORT_BYTES)
                        is TResult.Success,
                "host uploadFile should succeed",
            )

            // Host mints a share. The proxy `ShareLink` op generates a
            // shareId + K_share locally and saves a `Share` entity; the typed
            // return carries both halves so the UI can assemble the URL.
            // We only need `shareId` here — that's the collection key the
            // joiner reads back.
            val mintResult = host.tenshuClient.fileService.defaultFileSystem
                .shareLink(path = "/report.pdf", expiresAtMillis = 0L, maxOpens = 0L)
            assertTrue(mintResult is TResult.Success, "host shareLink should succeed")
            val payload = mintResult.value
            assertNotNull(payload, "ShareLink must return a typed ShareLinkResult")
            val shareId = payload.shareId.get()
            assertTrue(shareId.isNotBlank(), "shareId must be non-empty")

            // Sanity: host's own *My shares* projection sees the row. Guards
            // against a "joiner is broken" claim when the host's
            // `ShareRelationMaintainer` didn't fire in the first place.
            val hostRow = host.tenshuClient.fileService.shares
                .search<Share> { this.shareId equalTo shareId }
                .resolve()
            assertTrue(
                hostRow is TResult.Success && hostRow.value != null,
                "Host's own shares projection must contain $shareId; got $hostRow",
            )

            // Joiner observes the share through the same SDK chain after sync
            // drains. This is the load-bearing assertion: it proves
            //  - `Share.Schema` carries Syncable so the entity is emitted,
            //  - the joiner-side `ShareRelationMaintainer` fires on the
            //    deserialised entity, and
            //  - `FileService.shares` reads via the wire-level
            //    serviceDataRelation resolve the joiner-side collection.
            system.advanceUntil(timeout = 15.seconds) {
                val maybe = runCatching {
                    joiner.tenshuClient.fileService.shares
                        .search<Share> { this.shareId equalTo shareId }
                        .resolve()
                }.getOrNull()
                maybe is TResult.Success<*> && maybe.value != null
            }
            val joinerRow = joiner.tenshuClient.fileService.shares
                .search<Share> { this.shareId equalTo shareId }
                .resolve()
            val joinerEntity = (joinerRow as TResult.Success<*>).value
            assertNotNull(
                joinerEntity,
                "Joiner's shares projection must contain the host-minted share",
            )
        } finally {
            system.shutdown()
        }
    }

    @Test fun joinerObservesRevocationFlipFromHost(): Unit = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairHostAndJoiner(system)

            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "report.pdf", data = REPORT_BYTES)
                        is TResult.Success,
                "host uploadFile should succeed",
            )

            // Mint + wait for first arrival.
            val mintResult = host.tenshuClient.fileService.defaultFileSystem
                .shareLink(path = "/report.pdf", expiresAtMillis = 0L, maxOpens = 0L)
            val shareId = ((mintResult as TResult.Success<ShareLinkResult?>).value!!).shareId.get()
            system.advanceUntil(timeout = 15.seconds) {
                val maybe = runCatching {
                    joiner.tenshuClient.fileService.shares
                        .search<Share> { this.shareId equalTo shareId }
                        .resolve()
                }.getOrNull()
                maybe is TResult.Success<*> && maybe.value != null
            }

            // Host revokes. The proxy op flips the local `Share.revoked = true`
            // and re-saves — the entity is Syncable(Eager), so the joiner's
            // copy of the *same* StringDataId(shareId) must converge to
            // `revoked = true` once the sync drain runs.
            val revokeResult = host.tenshuClient.fileService.defaultFileSystem
                .revokeShareLink(shareId)
            assertTrue(revokeResult is TResult.Success, "host revoke should succeed")

            // Wait for the joiner's row to flip. We read the entity directly
            // from the joiner's local DB by StringDataId(shareId) — bypasses
            // the SDK chain to keep this assertion strictly about the
            // sync-layer convergence (the chain projection is covered by the
            // mint-propagation test above).
            val shareDataId = org.tenshu.core.entity.DataId.StringDataId.create(
                context = joiner.executionContext.context,
                type = Share.ID,
                keyValue = shareId,
            )
            system.advanceUntil(timeout = 15.seconds) {
                val raw = joiner.executionContext.database.getData(shareDataId)
                    ?: return@advanceUntil false
                val flag = when (raw) {
                    is org.tenshu.core.entity.Entity<*> -> runCatching {
                        raw.getFieldValue<org.tenshu.core.TBoolean>(Share.Field.REVOKED).get()
                    }.getOrNull()
                    is Share -> runCatching { raw.revoked.get() }.getOrNull()
                    is org.tenshu.core.Composite -> runCatching {
                        raw.getField<org.tenshu.core.TBoolean>(Share.Field.REVOKED).get()
                    }.getOrNull()
                    else -> null
                }
                flag == true
            }
            val raw = joiner.executionContext.database.getData(shareDataId)
            assertNotNull(raw, "Joiner must still have the Share entity post-revocation")
            val revoked = when (raw) {
                is org.tenshu.core.entity.Entity<*> ->
                    raw.getFieldValue<org.tenshu.core.TBoolean>(Share.Field.REVOKED).get()
                is Share -> raw.revoked.get()
                is org.tenshu.core.Composite ->
                    raw.getField<org.tenshu.core.TBoolean>(Share.Field.REVOKED).get()
                else -> error("Unexpected joiner-side Share shape: $raw")
            }
            assertEquals(
                true,
                revoked,
                "Joiner's Share entity should carry revoked=true after host revocation",
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

    private companion object {
        /** A few bytes of "file content" for the shared `/report.pdf`. */
        val REPORT_BYTES = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34)
    }
}
