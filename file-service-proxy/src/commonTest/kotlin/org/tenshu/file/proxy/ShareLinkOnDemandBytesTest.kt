package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.InvalidCallerError
import org.tenshu.core.NotFoundError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.file.share.ShareLink
import org.tenshu.core.file.share.ShareLinkResult
import org.tenshu.core.number.toTInteger
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.ShareRelayClient
import org.tenshu.sync.common.openShareMeta
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Regression pins for the "Cannot share '/x': file not found" bug: `ShareLink`
 * read the plaintext straight out of [org.tenshu.core.common.proxy.FileDatabase]
 * and threw `NotFoundError` whenever it wasn't on local disk.
 *
 * `File.Data` is `Syncable(OnDemand)`, so a file's *entity* being local says
 * nothing about its *bytes*: they are absent on any device that didn't upload
 * the file and never opened it, and on any device that evicted them (un-pin, or
 * a folder flipped to `SyncPolicy.ONLINE_ONLY`). Sharing has to drive the
 * OnDemand fetch first — the same thing `ReadSharedFile` and `OfflineKeeper` do.
 * `ShareLinkOperationTest` only ever exercised the bytes-already-on-disk path,
 * which is how this shipped.
 */
class ShareLinkOnDemandBytesTest {

    @Test
    fun shareLink_fetchesBytesOnDemand_whenTheyAreNotOnLocalDisk() = runTest {
        val relay = RecordingShareRelayClient()
        val resolver = FakeOnDemandResolution()
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            shareRelayClient = relay,
            cryptoManager = BootstrapSyncCryptoManager(),
            onDemandResolution = resolver,
        )
        relay.context = testProxy.tContext

        // Upload gives us a real File entity, then evict its bytes — the state of
        // any file synced from another device, or un-pinned on this one.
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "report.pdf".toTString(testProxy.tContext),
                UploadFile.Params.DATA to bytes.toTByteArray(testProxy.tContext),
            )
        )
        testProxy.fileDatabase.deleteFile("/report.pdf")
        assertNull(testProxy.fileDatabase.fileSize("/report.pdf"), "bytes must start evicted")

        // The fetch the relay would have served: land the plaintext on disk.
        resolver.onResolve = { testProxy.fileDatabase.writeRawByteArray("/report.pdf", bytes) }

        val result = testProxy.executionContext.executeAsync<Data>(
            operationId = ShareLink.ID,
            caller = null,
            params = mapOf(
                ShareLink.Params.PATH to "/report.pdf".toTString(testProxy.tContext),
                ShareLink.Params.EXPIRES_AT_MILLIS to 0L.toTInteger(testProxy.tContext),
                ShareLink.Params.MAX_OPENS to 0L.toTInteger(testProxy.tContext),
            )
        )

        assertNotNull(result as? ShareLinkResult, "share must succeed once the bytes are fetched")

        // The fetch was addressed at this file's OnDemand byte property.
        assertEquals(1, resolver.calls.size, "exactly one OnDemand fetch")
        val (entityId, propertyId) = resolver.calls.single()
        assertEquals("/report.pdf", (entityId as DataId.StringDataId).keyValue.get())
        assertEquals(File.ID, entityId.type.get())
        assertEquals(File.Data.id, propertyId)

        // And the fetched bytes are what got re-encrypted + uploaded.
        val descriptor = relay.created.single()
        val kShare = decodeBase64Url(result.urlFragment.get())
        val meta = openShareMeta(
            BootstrapSyncCryptoManager(),
            kShare,
            result.shareId.get(),
            descriptor.chunkCount,
            decodeBase64Url(descriptor.sealedMetaBase64Url),
        )
        assertEquals(1000L, meta.size, "the sealed size is the fetched file's size")
        assertEquals(1, descriptor.chunkCount)
        val chunk = relay.chunks.single()
        assertTrue(
            !chunk.ciphertext.contentEquals(bytes),
            "chunk on the wire must be ciphertext, not plaintext"
        )
    }

    @Test
    fun shareLink_reportsContentUnavailable_whenTheFetchCannotProduceBytes() = runTest {
        val relay = RecordingShareRelayClient()
        val resolver = FakeOnDemandResolution() // resolves, but fetches nothing (offline)
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            shareRelayClient = relay,
            cryptoManager = BootstrapSyncCryptoManager(),
            onDemandResolution = resolver,
        )
        relay.context = testProxy.tContext

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "report.pdf".toTString(testProxy.tContext),
                UploadFile.Params.DATA to ByteArray(10).toTByteArray(testProxy.tContext),
            )
        )
        testProxy.fileDatabase.deleteFile("/report.pdf")

        val failure = assertFailsWith<TenshuException> {
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

        // The file exists — the *content* is what's missing. Saying "file not
        // found" here is what sent this bug hunting in the wrong place.
        val message = (failure.error as NotFoundError).message.get()
        assertTrue(
            message.contains("aren't on this device"),
            "error must name the missing content, not the file: '$message'"
        )
        assertEquals(1, resolver.calls.size, "the fetch must have been attempted")
        assertTrue(relay.created.isEmpty(), "no descriptor POSTed for an unshareable file")
    }

    /**
     * The bug as actually reported: Drive offered *Link* on a folder, and the op's
     * blind `fileSize` read reported the folder's (non-existent) bytes as
     * "Cannot share '/test': file not found" — which reads as data loss rather
     * than "wrong kind of share". Folders are shared Mode-B (*Share* / *Manage
     * access*); the sheet now hides *Link* on them, and the op rejects it for the
     * callers the sheet doesn't cover (multi-select, SDK, web).
     */
    @Test
    fun shareLink_rejectsFolders_ratherThanReportingMissingBytes() = runTest {
        val relay = RecordingShareRelayClient()
        val resolver = FakeOnDemandResolution()
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            shareRelayClient = relay,
            cryptoManager = BootstrapSyncCryptoManager(),
            onDemandResolution = resolver,
        )
        relay.context = testProxy.tContext

        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/test".toTString(testProxy.tContext)),
        )

        val failure = assertFailsWith<TenshuException> {
            testProxy.executionContext.executeAsync<Data>(
                operationId = ShareLink.ID,
                caller = null,
                params = mapOf(
                    ShareLink.Params.PATH to "/test".toTString(testProxy.tContext),
                    ShareLink.Params.EXPIRES_AT_MILLIS to 0L.toTInteger(testProxy.tContext),
                    ShareLink.Params.MAX_OPENS to 0L.toTInteger(testProxy.tContext),
                )
            )
        }

        val message = (failure.error as InvalidCallerError).message.get()
        assertTrue(message.contains("it's a folder"), "unexpected message: '$message'")
        assertTrue(resolver.calls.isEmpty(), "a folder has no bytes to go fetch")
        assertTrue(relay.created.isEmpty(), "nothing uploaded for a folder")
    }

    @Test
    fun shareLink_reportsNotFound_whenNoSuchFileEntityExists() = runTest {
        val relay = RecordingShareRelayClient()
        val resolver = FakeOnDemandResolution()
        val testProxy = createFileProxy(
            backgroundScope,
            deviceId = "device-1",
            shareRelayClient = relay,
            cryptoManager = BootstrapSyncCryptoManager(),
            onDemandResolution = resolver,
        )
        relay.context = testProxy.tContext

        val failure = assertFailsWith<TenshuException> {
            testProxy.executionContext.executeAsync<Data>(
                operationId = ShareLink.ID,
                caller = null,
                params = mapOf(
                    ShareLink.Params.PATH to "/nope.pdf".toTString(testProxy.tContext),
                    ShareLink.Params.EXPIRES_AT_MILLIS to 0L.toTInteger(testProxy.tContext),
                    ShareLink.Params.MAX_OPENS to 0L.toTInteger(testProxy.tContext),
                )
            )
        }

        val message = (failure.error as NotFoundError).message.get()
        assertTrue(message.contains("file not found"), "unexpected message: '$message'")
        assertTrue(resolver.calls.isEmpty(), "no point fetching bytes for a file that isn't there")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Url(s: String): ByteArray =
        Base64.UrlSafe.decode(s + "=".repeat((4 - s.length % 4) % 4))

    /** Records what was asked for; [onResolve] stands in for the relay fetch. */
    private class FakeOnDemandResolution : OnDemandResolution {
        val calls = mutableListOf<Pair<DataId, String>>()
        var onResolve: (() -> Unit)? = null

        override suspend fun resolve(
            executionContext: ExecutionContext,
            entityId: DataId,
            propertyId: String,
            params: TList<Data>?,
        ): TResult<Data?> {
            calls += entityId to propertyId
            onResolve?.invoke()
            // The real resolver reports success whether or not anything was
            // fetched — the caller re-reads the property to find out.
            return TResult.Success.create(executionContext.context, null)
        }
    }

    private class RecordingShareRelayClient : ShareRelayClient {
        lateinit var context: TContext
        val created = mutableListOf<ShareRelayClient.ShareDescriptor>()
        data class Chunk(val shareId: String, val chunkIndex: Int, val ciphertext: ByteArray)
        val chunks = mutableListOf<Chunk>()

        override suspend fun createShare(descriptor: ShareRelayClient.ShareDescriptor): TResult<Data?> {
            created += descriptor
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
            return TResult.Success.create(context, null)
        }
    }
}
