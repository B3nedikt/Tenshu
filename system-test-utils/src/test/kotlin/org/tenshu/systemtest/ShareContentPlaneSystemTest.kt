package org.tenshu.systemtest

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.core.TResult
import org.tenshu.core.file.share.ShareLinkResult
import org.tenshu.core.file.share.revokeShareLink
import org.tenshu.core.file.share.shareLink
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.file.client.fileService
import org.tenshu.sync.common.ShareMeta
import org.tenshu.sync.common.openShareMeta
import org.tenshu.sync.common.shareChunkAad
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the Mode-A **content plane** — the seam no other test
 * exercised. `ShareLinkOperationTest` (`:file-service-proxy`) drives the sharer op
 * against a *recording fake* relay client, `ShareRoutingTest` (`:sync-relay-server`)
 * drives the relay routes with hand-built JSON, and `ShareSyncSystemTest` pins only
 * the `Share` *entity* sync. None of them run the real sharer → relay → recipient
 * round-trip, so a regression in the wire shape or the AEAD/AAD construction between
 * the production `RestShareRelayClient` (upload) and the recipient (`ShareViewer`,
 * download) would slip through.
 *
 * This test closes that gap with the production pieces wired together:
 *
 *  1. A bootstrapped host (real device-key JWT, registered in the relay's
 *     `KnownDevicesStore`) uploads a file, then mints a share via the real
 *     `ShareLink` op — which re-encrypts the bytes under a fresh `K_share` and
 *     `POST`s the descriptor + `PUT`s the ciphertext chunks to the in-process
 *     `:sync-relay-server` through the real `RestShareRelayClient`
 *     (`SystemTestPlatformModule` now wires it, mirroring production).
 *  2. An **anonymous** recipient (no session, no JWT — exactly the apex viewer's
 *     position) `GET`s the descriptor + chunks off the relay and AEAD-decrypts each
 *     under `K_share` with AAD `(shareId, chunkIndex, chunkCount)` — the same
 *     [shareChunkAad] construction `app.tenshu.sdk.ShareViewer.fetch` uses. The
 *     recovered bytes must equal the original (House Rule #1 also checked: the
 *     ciphertext the relay served is NOT the plaintext).
 *  3. After `RevokeShareLink`, the recipient's `GET /share/{id}` reads **410 Gone**.
 *
 * Uses `runBlocking` (not `runTest`) for the same reason as [BootstrapSystemTest]:
 * `ProxyGateway.call` blocks internally.
 */
class ShareContentPlaneSystemTest {

    @Test fun recipientFetchesAndDecryptsHostMintedShare_thenRevokeReturns410() = runBlocking {
        // The durable plane puts the share store on SQLite, so the row production writes can be read.
        val relayDir = Files.createTempDirectory("share-sealed-meta").toFile()
        val system = TestSystem.builder()
            .durableContentPlane(relayDir, clock = System::currentTimeMillis)
            .build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            // Bootstrapping registers the host's device key with the relay, so the
            // JWT-authed POST /share + PUT chunk below verify (BootstrapSystemTest).
            assertTrue(
                host.bootstrapAsHost(deviceName = "Host Tablet") is TResult.Success,
                "host bootstrap should succeed",
            )

            val fileName = "greeting.txt"
            val payload = "Hello over the share content plane — の".encodeToByteArray()
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = fileName, data = payload)
                        is TResult.Success,
                "host uploadFile should succeed",
            )

            // Mint: the real ShareLink op re-encrypts + uploads chunks to the relay.
            val mint = host.tenshuClient.fileService.defaultFileSystem
                .shareLink(path = "/$fileName", expiresAtMillis = 0L, maxOpens = 0L)
            assertTrue(mint is TResult.Success, "host shareLink should succeed; got $mint")
            val result: ShareLinkResult = assertNotNull(
                (mint as TResult.Success<ShareLinkResult?>).value,
                "ShareLink must return a typed ShareLinkResult",
            )
            val shareId = result.shareId.get()
            val kShare = decodeBase64Url(result.urlFragment.get())
            assertTrue(shareId.isNotBlank(), "shareId must be non-empty")

            // ---- Recipient side: anonymous, exactly like the apex viewer ----
            val relay = system.relayHttpClient
            val descriptorResponse = relay.get("/share/$shareId")
            assertEquals(
                HttpStatusCode.OK,
                descriptorResponse.status,
                "anonymous descriptor GET should succeed",
            )
            // Parse via the runtime JsonElement API rather than an @Serializable DTO:
            // :system-test-utils doesn't apply the kotlinx.serialization compiler
            // plugin, so a local @Serializable class would compile but have no
            // generated serializer at runtime (the serialization-plugin gap).
            val descriptorText = descriptorResponse.bodyAsText()
            val descriptor = Json.parseToJsonElement(descriptorText).jsonObject
            val chunkCount = descriptor.getValue("chunkCount").jsonPrimitive.int
            assertTrue(chunkCount >= 1, "a non-empty file has >=1 chunk")

            // ---- F3: the relay holds no clear name / MIME / size, on the wire or at rest ----
            for (absent in listOf("fileName", "mimeType", "totalSize")) {
                assertFalse(absent in descriptor, "descriptor must not carry `$absent`")
            }
            assertFalse(descriptorText.contains("greeting"), "descriptor JSON must not contain the file name")
            assertFalse(descriptorText.contains("text/plain"), "descriptor JSON must not contain the MIME type")
            assertShareRowHoldsNoClearMetadata(relayDir, shareId, fileName)

            // Reuse the host's crypto manager (same AEAD algorithm the recipient's
            // own DefaultSyncCryptoManager would use); K_share is symmetric.
            val crypto = host.koinApp.koin.get<SyncCryptoManager>()

            // The recipient opens the sealed metadata with K_share — exactly what ShareViewer does.
            val sealed = decodeBase64Url(descriptor.getValue("sealedMeta").jsonPrimitive.content)
            val meta = openShareMeta(crypto, kShare, shareId, chunkCount, sealed)
            assertEquals(ShareMeta(fileName, "text/plain", payload.size.toLong()), meta)
            // A wrong key is a failure, never blank metadata.
            val wrongKey = kShare.copyOf().also { it[0] = (it[0] + 1).toByte() }
            assertTrue(
                runCatching { openShareMeta(crypto, wrongKey, shareId, chunkCount, sealed) }.isFailure,
                "sealedMeta must not open under a wrong K_share",
            )
            val recovered = ArrayList<Byte>(payload.size)
            for (index in 0 until chunkCount) {
                val chunkResponse = relay.get("/share/$shareId/chunk/$index")
                assertEquals(
                    HttpStatusCode.OK,
                    chunkResponse.status,
                    "anonymous chunk $index GET should succeed",
                )
                val cipher = chunkResponse.bodyAsBytes()
                // House Rule #1: the relay served ciphertext, never the plaintext.
                assertFalse(
                    cipher.contentEquals(payload),
                    "relay must serve AEAD ciphertext, not plaintext",
                )
                val plain = crypto.decryptWithAad(
                    cipher,
                    kShare,
                    shareChunkAad(shareId, index, chunkCount),
                )
                recovered.addAll(plain.toList())
            }
            assertContentEquals(
                payload,
                recovered.toByteArray(),
                "recipient must reconstruct the original file bytes",
            )
            assertEquals(meta.size, recovered.size.toLong(), "sealed size matches the decrypted bytes")

            // ---- Revoke: the recipient's next descriptor GET reads 410 Gone ----
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem.revokeShareLink(shareId)
                        is TResult.Success,
                "host revoke should succeed",
            )
            assertEquals(
                HttpStatusCode.Gone,
                relay.get("/share/$shareId").status,
                "after revoke the descriptor GET should be 410 Gone",
            )
        } finally {
            system.shutdown()
            relayDir.deleteRecursively()
        }
    }

    /**
     * Reads the relay's `share_record` row straight from its SQLite file: no column is named after
     * clear metadata, and no stored value contains the file name.
     */
    private fun assertShareRowHoldsNoClearMetadata(relayDir: java.io.File, shareId: String, fileName: String) {
        DriverManager.getConnection("jdbc:sqlite:${java.io.File(relayDir, "relay.db").absolutePath}").use { conn ->
            conn.prepareStatement("SELECT * FROM share_record WHERE share_id = ?").use { ps ->
                ps.setString(1, shareId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next(), "the relay must have stored the share row")
                    val md = rs.metaData
                    val columns = (1..md.columnCount).map { md.getColumnName(it) }
                    for (forbidden in listOf("file_name", "mime_type", "total_size")) {
                        assertFalse(forbidden in columns, "share_record must not have `$forbidden`")
                    }
                    for (i in 1..md.columnCount) {
                        val value = when (val raw = rs.getObject(i)) {
                            null -> continue
                            is ByteArray -> raw.decodeToString()
                            else -> raw.toString()
                        }
                        assertFalse(
                            value.contains(fileName.substringBefore('.')),
                            "column ${md.getColumnName(i)} must not hold the file name",
                        )
                    }
                    assertTrue("sealed_meta" in columns, "share_record stores the sealed blob")
                }
            }
        }
    }

    /** Mirrors `ShareViewer.decodeBase64Url` — the URL-fragment producer omits padding. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Url(s: String): ByteArray {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return Base64.UrlSafe.decode(padded)
    }
}
