package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileService
import org.tenshu.core.file.share.RevokeShareLink
import org.tenshu.core.file.share.Share
import org.tenshu.core.file.share.ShareLink
import org.tenshu.core.file.share.ShareLinkResult
import org.tenshu.core.number.toTInteger
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.ShareMeta
import org.tenshu.sync.common.ShareRelayClient
import org.tenshu.sync.common.openShareMeta
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the local-mint half of `ShareLink` (`docs/file-sharing.md`
 * §9.5 step 1 / §9.7 Bodies #7) — the relay HTTP plumbing is deferred to a
 * follow-up; this test exercises everything that lives in the proxy today:
 *
 *  1. The op returns a typed `ShareLinkResult` with non-empty `shareId` +
 *     `urlFragment`.
 *  2. A local `Share` entity lands keyed by that `shareId` (Eager-syncs
 *     downstream — proven indirectly by the entity ending up in the realm-wide
 *     `FileService.SHARES` collection, which the `ShareRelationMaintainer`
 *     populates).
 *
 * Together these prove the visible-end-to-end *My shares* flow without any
 * relay running.
 */
class ShareLinkOperationTest {

    @Test
    fun shareLink_returnsResultAndPersistsShare() = runTest {
        val testProxy = createFileProxy(backgroundScope, deviceId = "device-1")

        val result = testProxy.executionContext.executeAsync<Data>(
            operationId = ShareLink.ID,
            caller = null,
            params = mapOf(
                ShareLink.Params.PATH to "/report.pdf".toTString(testProxy.tContext),
                ShareLink.Params.EXPIRES_AT_MILLIS to 0L.toTInteger(testProxy.tContext),
                ShareLink.Params.MAX_OPENS to 0L.toTInteger(testProxy.tContext),
            )
        )

        val payload = result as? ShareLinkResult
        assertNotNull(payload, "Op must return a ShareLinkResult")
        val shareId = payload.shareId.get()
        val urlFragment = payload.urlFragment.get()
        assertTrue(shareId.isNotBlank(), "shareId must be non-empty")
        assertTrue(urlFragment.isNotBlank(), "urlFragment must be non-empty")

        // The local Share entity is keyed by shareId.
        val shareDataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Share.ID,
            keyValue = shareId,
        )
        val savedShare = testProxy.executionContext.database.getData(shareDataId)
        assertNotNull(savedShare, "Share entity must be saved by shareId")
    }

    @Test
    fun revokeShareLink_flipsLocalRevokedFlag() = runTest {
        val testProxy = createFileProxy(backgroundScope, deviceId = "device-1")

        // Mint first.
        val mintResult = testProxy.executionContext.executeAsync<Data>(
            operationId = ShareLink.ID,
            caller = null,
            params = mapOf(
                ShareLink.Params.PATH to "/report.pdf".toTString(testProxy.tContext),
                ShareLink.Params.EXPIRES_AT_MILLIS to 0L.toTInteger(testProxy.tContext),
                ShareLink.Params.MAX_OPENS to 0L.toTInteger(testProxy.tContext),
            )
        )
        val shareId = (mintResult as ShareLinkResult).shareId.get()

        // Revoke.
        testProxy.executionContext.executeAsync<Data>(
            operationId = RevokeShareLink.ID,
            caller = null,
            params = mapOf(
                RevokeShareLink.Params.SHARE_ID to shareId.toTString(testProxy.tContext),
            )
        )

        // The local Share entity now carries `revoked = true`. `database.getData`
        // returns either Entity<Share> or its DTO depending on the round-trip
        // path; read via the Composite contract for both shapes.
        val shareDataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Share.ID,
            keyValue = shareId,
        )
        val savedShare = testProxy.executionContext.database.getData(shareDataId)
        assertNotNull(savedShare, "Share must still be in the DB after revoke")
        val revoked = when (savedShare) {
            is Entity<*> -> savedShare
                .getFieldValue<org.tenshu.core.TBoolean>(Share.Field.REVOKED).get()
            is Share -> savedShare.revoked.get()
            else -> error("Unexpected shape: $savedShare")
        }
        assertEquals(true, revoked)
    }

    @Test
    fun mintedShareIsVisibleOnRealmWideSharesProjection() = runTest {
        val testProxy = createFileProxy(backgroundScope, deviceId = "device-1")

        testProxy.executionContext.executeAsync<Data>(
            operationId = ShareLink.ID,
            caller = null,
            params = mapOf(
                ShareLink.Params.PATH to "/report.pdf".toTString(testProxy.tContext),
                ShareLink.Params.EXPIRES_AT_MILLIS to 0L.toTInteger(testProxy.tContext),
                ShareLink.Params.MAX_OPENS to 0L.toTInteger(testProxy.tContext),
            )
        )

        val sharesRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = FileService.Relations.SHARES,
            ),
            isEntity = false,
            elementSchemaId = Share.ID,
        )
        val shares = testProxy.executionContext.database.getArrayData(sharesRef)?.toList()
        assertNotNull(shares, "FileService.SHARES must be populated")
        assertEquals(1, shares.size, "exactly one share landed")
    }

    @Test
    fun shareLink_postsDescriptorAndStreamsChunks_whenRelayWired() = runTest {
        val relay = RecordingShareRelayClient()
        val crypto = BootstrapSyncCryptoManager()
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            shareRelayClient = relay,
            cryptoManager = crypto,
        )
        relay.context = testProxy.tContext

        // A small file so there is content to re-encrypt + upload (one chunk,
        // well under the 2 MB CHUNK window).
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        testProxy.fileDatabase.writeRawByteArray("/report.pdf", bytes)

        val result = testProxy.executionContext.executeAsync<Data>(
            operationId = ShareLink.ID,
            caller = null,
            params = mapOf(
                ShareLink.Params.PATH to "/report.pdf".toTString(testProxy.tContext),
                ShareLink.Params.EXPIRES_AT_MILLIS to 0L.toTInteger(testProxy.tContext),
                ShareLink.Params.MAX_OPENS to 0L.toTInteger(testProxy.tContext),
            )
        )
        val shareId = (result as ShareLinkResult).shareId.get()
        val kShare = decodeBase64Url(result.urlFragment.get())

        // Descriptor POSTed exactly once for this shareId, with chunkCount = 1.
        assertEquals(1, relay.created.size, "one descriptor POSTed")
        val descriptor = relay.created.single()
        assertEquals(shareId, descriptor.shareId)
        assertEquals(1, descriptor.chunkCount)
        // F3: name, MIME and exact size travel only sealed under K_share.
        val sealed = decodeBase64Url(descriptor.sealedMetaBase64Url)
        assertTrue(
            !sealed.decodeToString().contains("report.pdf"),
            "the descriptor must not carry the file name in the clear",
        )
        val meta = openShareMeta(crypto, kShare, shareId, descriptor.chunkCount, sealed)
        assertEquals(ShareMeta("report.pdf", "application/pdf", 1000L), meta)

        // Exactly one chunk streamed for this shareId, and it is ciphertext —
        // not the plaintext bytes (House Rule #1: relay never sees plaintext).
        val uploaded = relay.chunks.filter { it.shareId == shareId }
        assertEquals(1, uploaded.size, "one chunk streamed")
        assertEquals(0, uploaded.single().chunkIndex)
        assertTrue(
            !uploaded.single().ciphertext.contentEquals(bytes),
            "chunk on the wire must be ciphertext, not plaintext"
        )

        // The local Share still lands.
        val shareDataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Share.ID,
            keyValue = shareId,
        )
        assertNotNull(testProxy.executionContext.database.getData(shareDataId))
    }

    /**
     * A file that shrinks between being measured and being read must fail share creation on the
     * sharer, not reach the recipient as a "link corrupted" decrypt failure: the sealed meta has
     * already committed `chunkCount` + `size`, so a short stream is indistinguishable from tampering.
     */
    @Test
    fun shareLink_failsAndRevokes_whenFileShrinksDuringUpload() = runTest {
        val relay = RecordingShareRelayClient()
        val crypto = BootstrapSyncCryptoManager()
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            shareRelayClient = relay,
            cryptoManager = crypto,
        )
        relay.context = testProxy.tContext
        testProxy.fileDatabase.writeRawByteArray("/report.pdf", ByteArray(1000) { 7 })
        // The descriptor POST is the window between `fileSize` and the first chunk read.
        relay.onCreate = { testProxy.fileDatabase.writeRawByteArray("/report.pdf", ByteArray(400) { 7 }) }

        assertFailsWith<TenshuException> {
            testProxy.executionContext.executeAsync<Data>(
                operationId = ShareLink.ID,
                caller = null,
                params = mapOf(
                    ShareLink.Params.PATH to "/report.pdf".toTString(testProxy.tContext),
                    ShareLink.Params.EXPIRES_AT_MILLIS to 0L.toTInteger(testProxy.tContext),
                    ShareLink.Params.MAX_OPENS to 0L.toTInteger(testProxy.tContext),
                )
            )
        }

        val shareId = relay.created.single().shareId
        assertTrue(relay.chunks.isEmpty(), "no short chunk may go out under the committed size")
        assertEquals(listOf(shareId), relay.deleted, "the unopenable relay record is revoked")
        val shareDataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Share.ID,
            keyValue = shareId,
        )
        assertNull(testProxy.executionContext.database.getData(shareDataId), "no My-shares row")
    }

    @Test
    fun revokeShareLink_issuesRelayDelete_whenRelayWired() = runTest {
        val relay = RecordingShareRelayClient()
        val crypto = BootstrapSyncCryptoManager()
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            shareRelayClient = relay,
            cryptoManager = crypto,
        )
        relay.context = testProxy.tContext
        testProxy.fileDatabase.writeRawByteArray("/report.pdf", ByteArray(10))

        val mint = testProxy.executionContext.executeAsync<Data>(
            operationId = ShareLink.ID,
            caller = null,
            params = mapOf(
                ShareLink.Params.PATH to "/report.pdf".toTString(testProxy.tContext),
                ShareLink.Params.EXPIRES_AT_MILLIS to 0L.toTInteger(testProxy.tContext),
                ShareLink.Params.MAX_OPENS to 0L.toTInteger(testProxy.tContext),
            )
        )
        val shareId = (mint as ShareLinkResult).shareId.get()

        testProxy.executionContext.executeAsync<Data>(
            operationId = RevokeShareLink.ID,
            caller = null,
            params = mapOf(
                RevokeShareLink.Params.SHARE_ID to shareId.toTString(testProxy.tContext),
            )
        )

        assertEquals(listOf(shareId), relay.deleted, "revoke must DELETE the relay record")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Url(s: String): ByteArray =
        Base64.UrlSafe.decode(s + "=".repeat((4 - s.length % 4) % 4))

    /**
     * Records every relay call so the op's upload/revoke path can be asserted
     * without a running relay. Returns success for all verbs.
     */
    private class RecordingShareRelayClient : ShareRelayClient {
        lateinit var context: TContext
        val created = mutableListOf<ShareRelayClient.ShareDescriptor>()
        data class Chunk(val shareId: String, val chunkIndex: Int, val ciphertext: ByteArray)
        val chunks = mutableListOf<Chunk>()
        val deleted = mutableListOf<String>()
        var onCreate: () -> Unit = {}

        override suspend fun createShare(descriptor: ShareRelayClient.ShareDescriptor): TResult<Data?> {
            created += descriptor
            onCreate()
            return TResult.Success.create(context, null)
        }

        override suspend fun putChunk(
            shareId: String,
            chunkIndex: Int,
            ciphertext: ByteArray,
        ): TResult<Data?> {
            chunks += Chunk(shareId, chunkIndex, ciphertext)
            return TResult.Success.create(context, null)
        }

        override suspend fun deleteShare(shareId: String): TResult<Data?> {
            deleted += shareId
            return TResult.Success.create(context, null)
        }
    }
}
