@file:OptIn(ExperimentalEncodingApi::class)

package app.tenshu.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import org.tenshu.sync.common.ShareMeta
import org.tenshu.sync.common.sealShareMeta
import org.tenshu.sync.common.shareChunkAad
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The anonymous Mode-A viewer on the **browser** runtime (`relay-metadata-minimisation.md` F3):
 * file name, MIME type and size come only from the descriptor's `sealedMeta`, opened under
 * `K_share`. A wrong key or a descriptor without readable metadata fails visibly — `"decrypt"` /
 * `"meta"` — and never yields bytes under a blank name.
 */
class ShareViewerTest {

    private val crypto = createDefaultSyncCryptoManager()
    private val kShare = ByteArray(32) { (it * 3 + 5).toByte() }
    private val shareId = "share-js-1"
    private val payload = "Hello from a sealed share — の".encodeToByteArray()
    private val meta = ShareMeta("greeting.txt", "text/plain", payload.size.toLong())

    private fun b64(bytes: ByteArray) = Base64.UrlSafe.encode(bytes).trimEnd('=')

    private fun relay(sealedMetaJson: String?, chunkCount: Int = 1): HttpClient {
        val chunk = crypto.encryptWithAad(payload, kShare, shareChunkAad(shareId, 0, chunkCount))
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when (path) {
                "/share/$shareId" -> respond(
                    content = buildString {
                        append("{\"shareId\":\"$shareId\",")
                        if (sealedMetaJson != null) append("\"sealedMeta\":$sealedMetaJson,")
                        append("\"chunkCount\":$chunkCount,\"chunkHashes\":[],")
                        append("\"expiresAtMillis\":null,\"opensRemaining\":null}")
                    },
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/share/$shareId/chunk/0" -> respond(
                    content = chunk,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                )
                else -> respond("", HttpStatusCode.Gone)
            }
        }
        return HttpClient(engine) { install(ContentNegotiation) { json() } }
    }

    private fun sealed(chunkCount: Int = 1, m: ShareMeta = meta) =
        "\"" + b64(sealShareMeta(crypto, kShare, shareId, chunkCount, m)) + "\""

    @Test
    fun opensSealedMetadata_andDecryptsTheFile() = runTest {
        val result = ShareViewer.fetchWith(relay(sealed()), shareId, b64(kShare), "https://relay.test")
        assertTrue(result.ok, "errorKind=${result.errorKind}")
        assertEquals("greeting.txt", result.fileName)
        assertEquals("text/plain", result.mimeType)
        assertContentEquals(payload, result.bytes)
    }

    @Test
    fun wrongKey_failsAsDecrypt_withNoMetadata() = runTest {
        val wrong = kShare.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val result = ShareViewer.fetchWith(relay(sealed()), shareId, b64(wrong), "https://relay.test")
        assertFalse(result.ok)
        assertEquals("decrypt", result.errorKind)
        assertNull(result.fileName)
        assertNull(result.mimeType)
        assertNull(result.bytes)
    }

    @Test
    fun missingOrEmptySealedMeta_failsAsMeta() = runTest {
        for (slot in listOf(null, "\"\"", "\"!!\"")) {
            val result = ShareViewer.fetchWith(relay(slot), shareId, b64(kShare), "https://relay.test")
            assertFalse(result.ok, "slot=$slot")
            assertEquals("meta", result.errorKind, "slot=$slot")
            assertNull(result.fileName)
        }
    }

    @Test
    fun metadataSealedForAnotherChunkCount_failsAsDecrypt() = runTest {
        // A relay that edits `chunkCount` in the descriptor no longer matches the sealed AAD.
        val result = ShareViewer.fetchWith(relay(sealed(chunkCount = 2)), shareId, b64(kShare), "https://relay.test")
        assertEquals("decrypt", result.errorKind)
    }

    @Test
    fun sizeMismatch_failsAsDecrypt() = runTest {
        val lying = meta.copy(size = payload.size.toLong() + 1)
        val result = ShareViewer.fetchWith(relay(sealed(m = lying)), shareId, b64(kShare), "https://relay.test")
        assertEquals("decrypt", result.errorKind)
        assertNull(result.bytes)
    }
}
