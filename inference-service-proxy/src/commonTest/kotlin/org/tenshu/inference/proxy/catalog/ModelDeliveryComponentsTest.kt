package org.tenshu.inference.proxy.catalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.TByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ **What a platform that binds no runtime is offered** (IN-D13, §23.2).
 *
 * `SUPPORTED_RUNTIMES` lives in `commonMain` and says `onnx`, but `onnxMain` — the source set that
 * actually contains the backend — compiles into the JVM and Android artifacts only. While nothing
 * could download, reading that constant on iOS or web was harmless. The moment IN-2c wired
 * `DownloadModel` it became an offer to spend ~383 MB on a checkpoint the device can never execute.
 *
 * So the claim is **contributed per platform** and the default here is empty. These two tests are
 * that default: the same catalog, the same key, the same bytes — and the only difference is whether
 * the caller said it could run the thing.
 */
class ModelDeliveryComponentsTest {

    private val signature = byteArrayOf(7, 7)
    private val catalog = """
        {
          "schemaVersion": 1,
          "buildDate": "2026-09-04",
          "models": [{
            "id": "siglip", "name": "SigLIP", "version": 1,
            "capabilities": ["semanticImageSearch"],
            "runtime": "onnx", "artifactVersion": 1, "licence": "Apache-2.0",
            "artifacts": [
              { "role": "vision", "path": "m/vision.onnx", "sha256": "aa", "byteSize": 100 }
            ]
          }]
        }
    """.trimIndent()

    private fun engine() = MockEngine { request ->
        val body = mapOf(
            "/catalog.json" to catalog.encodeToByteArray(),
            "/catalog.json.sig" to signature,
        )[request.url.encodedPath]
        if (body == null) {
            respondError(HttpStatusCode.NotFound)
        } else {
            respond(
                body,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
            )
        }
    }

    /** ⚠ Accepts everything: this test is about the runtime filter, not about signatures. */
    private val alwaysValid = object : Ed25519Backend {
        override fun sign(privateKeySeed: ByteArray, message: ByteArray) = ByteArray(64)
        override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray) = true
        override fun derivePublicKey(privateKeySeed: ByteArray) = ByteArray(32)
    }

    private fun components(supportedRuntimes: Set<String>? = null) =
        if (supportedRuntimes == null) {
            // ⚠ The DEFAULT — deliberately not passed, which is the whole subject.
            modelDeliveryComponents(
                httpClient = HttpClient(engine()),
                storageDelegate = MapDelegate(),
                fileDatabase = NoFileDatabase(),
                ed25519 = alwaysValid,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                backgroundLiveness = BackgroundLiveness(),
                catalogBaseUrl = "https://models.example",
                catalogPublicKeyHex = "0a0b",
                storeOwnership = ModelStoreOwnership.SoleProcess,
            )
        } else {
            modelDeliveryComponents(
                httpClient = HttpClient(engine()),
                storageDelegate = MapDelegate(),
                fileDatabase = NoFileDatabase(),
                ed25519 = alwaysValid,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                backgroundLiveness = BackgroundLiveness(),
                catalogBaseUrl = "https://models.example",
                catalogPublicKeyHex = "0a0b",
                storeOwnership = ModelStoreOwnership.SoleProcess,
                supportedRuntimes = supportedRuntimes,
            )
        }

    /**
     * ⚠⚠ A build that contributes no runtime is offered **nothing** — not "everything, and it fails
     * later". The catalog is fetched and verified; every entry is then dropped because this binary
     * cannot execute it.
     */
    @Test
    fun aPlatformThatClaimsNoRuntimeIsOfferedNoModels() = runTest {
        val loaded = assertNotNull(components().catalogSource.load())

        assertTrue(loaded.models.isEmpty())
    }

    /** The same bytes, offered to a build that *can* run them. */
    @Test
    fun aPlatformThatClaimsOnnxIsOfferedTheOnnxModel() = runTest {
        val loaded = assertNotNull(components(supportedRuntimes = setOf("onnx")).catalogSource.load())

        assertEquals(listOf("siglip"), loaded.models.map { it.modelId })
    }

    private class MapDelegate : SecureStorageDelegate {
        private val map = mutableMapOf<String, String>()
        override fun storeSecret(key: String, value: String) { map[key] = value }
        override fun getSecret(key: String): String? = map[key]
        override fun storeString(key: String, value: String) { map[key] = value }
        override fun getString(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
    }

    /**
     * ⚠ Nothing here touches the filesystem — these tests never install anything, they only ask what
     * the catalog offers. Every default the interface provides is left alone.
     */
    private class NoFileDatabase : FileDatabase {
        override fun writeRawByteArray(filePath: String, bytes: ByteArray) = Unit
        override fun writeByteArray(filePath: String, bytes: TByteArray) = Unit
        override fun readBytes(filePath: String): TByteArray? = null
    }
}
