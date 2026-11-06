package org.tenshu.inference.proxy.catalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Fetching the catalog (IN-2b) — over Ktor's `MockEngine`, so no network and no key material.
 */
class HttpModelCatalogSourceTest {

    private val signature = byteArrayOf(7, 7)
    private val catalog = """
        {
          "schemaVersion": 1,
          "buildDate": "2026-08-12",
          "models": [{
            "id": "siglip", "version": 1, "capabilities": ["semanticImageSearch"],
            "runtime": "onnx", "artifactVersion": 1, "licence": "Apache-2.0",
            "artifacts": [
              { "role": "vision", "path": "models/siglip.onnx", "sha256": "abc", "byteSize": 10 }
            ]
          }]
        }
    """.trimIndent()

    private fun source(
        engine: MockEngine,
        verifies: Boolean = true,
    ): HttpModelCatalogSource = HttpModelCatalogSource(
        httpClient = HttpClient(engine),
        baseUrl = "https://models.example",
        verifier = ModelCatalogVerifier(
            { _, _, _ -> verifies },
            publicKeyHex = "0a0b",
            // ⚠ Explicit: this test is about the HTTP fetch, not about IN-D13's runtime filter.
            supportedRuntimes = setOf("onnx"),
        ),
    )

    private fun serving(vararg bodies: Pair<String, ByteArray>): MockEngine {
        val map = bodies.toMap()
        return MockEngine { request ->
            val body = map[request.url.encodedPath]
            if (body == null) {
                respondError(HttpStatusCode.NotFound)
            } else {
                respond(
                    body,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
                )
            }
        }
    }

    @Test
    fun fetchesTheCatalogAndItsDetachedSignature() = runTest {
        val engine = serving(
            "/catalog.json" to catalog.encodeToByteArray(),
            "/catalog.json.sig" to signature,
        )

        val loaded = assertNotNull(source(engine).load())

        assertEquals("siglip", loaded.models.single().modelId)
        assertEquals(
            listOf("/catalog.json", "/catalog.json.sig"),
            engine.requestHistory.map { it.url.encodedPath },
        )
    }

    @Test
    fun aCatalogThatFailsTheSignatureCheckLoadsNothing() = runTest {
        val engine = serving(
            "/catalog.json" to catalog.encodeToByteArray(),
            "/catalog.json.sig" to signature,
        )

        // ⚠⚠ Nothing untrusted is ever installed — that is the same property as the region twin's;
        // only the *reporting* differs (null rather than a throw, because this is polled in the
        // background and a proxy that threw on every refresh would fill the log and change nothing).
        assertNull(source(engine, verifies = false).load())
    }

    @Test
    fun aMissingSignatureIsNotACatalog() = runTest {
        // ⚠ The signature is not optional. A CDN that serves the document but 404s the signature is
        // exactly what an attacker who can delete a file would arrange.
        val engine = serving("/catalog.json" to catalog.encodeToByteArray())

        assertNull(source(engine).load())
    }

    @Test
    fun aTransportFailureIsNullRatherThanACrash() = runTest {
        assertNull(source(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }).load())
    }

    /**
     * ⚠⚠ Today's shipped configuration: no release key. A client that cannot trust any answer must
     * not ask the question — otherwise every background refresh is a request whose result is
     * discarded, and on a device that never configures a key that is every request it ever makes.
     *
     * ⚠ It is a **cost** guard, not the safety one: [ModelCatalogVerifier] refuses a blank pin by
     * itself. Asserted on `requestHistory` rather than on the returned value, because a `null`
     * would look identical with the guard removed.
     */
    @Test
    fun withNoPinnedKeyNothingIsEvenRequested() = runTest {
        val engine = serving(
            "/catalog.json" to catalog.encodeToByteArray(),
            "/catalog.json.sig" to signature,
        )
        val unpinned = HttpModelCatalogSource(
            httpClient = HttpClient(engine),
            baseUrl = "https://models.example",
            verifier = ModelCatalogVerifier({ _, _, _ -> true }, publicKeyHex = ""),
        )

        assertNull(unpinned.load())
        assertEquals(emptyList(), engine.requestHistory.map { it.url.encodedPath })
    }

    /** The other half of "unconfigured": a key but nowhere to fetch from. */
    @Test
    fun withNoCatalogUrlNothingIsEvenRequested() = runTest {
        val engine = serving(
            "/catalog.json" to catalog.encodeToByteArray(),
            "/catalog.json.sig" to signature,
        )
        val noUrl = HttpModelCatalogSource(
            httpClient = HttpClient(engine),
            baseUrl = "",
            verifier = ModelCatalogVerifier({ _, _, _ -> true }, publicKeyHex = "0a0b"),
        )

        assertNull(noUrl.load())
        assertEquals(emptyList(), engine.requestHistory.map { it.url.encodedPath })
    }
}
