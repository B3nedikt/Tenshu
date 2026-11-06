package org.tenshu.mapregion.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers `catalog.json` parsing ([parseRegionCatalog]) and the fetch+verify seam
 * ([HttpRegionCatalogSource]) over a Ktor MockEngine — no network, no real crypto (a
 * deterministic [SignatureVerifier] models accept/reject), so it runs on every commonTest target.
 */
class RegionCatalogSourceTest {

    private val catalogBytes = """
        {
          "schemaVersion": 2,
          "buildDate": "2026-07",
          "extraFutureField": "ignored",
          "regions": [
            { "id": "de-bayern", "name": "Bayern", "coverage": "poly",
              "byteSize": 42000000, "sha256": "abc123", "tileSchemaVersion": 4,
              "path": "regions/2026-07/de-bayern.pmtiles" }
          ]
        }
    """.trimIndent().encodeToByteArray()

    private val goodSignature = "valid-signature".encodeToByteArray()

    @Test
    fun parsesCatalogJsonToleratingUnknownKeys() {
        val data = parseRegionCatalog(catalogBytes.decodeToString())
        assertEquals(2, data.schemaVersion)
        assertEquals("2026-07", data.buildDate)
        assertEquals(1, data.regions.size)
        val region = data.regions.first()
        assertEquals("de-bayern", region.regionId)
        assertEquals(42_000_000L, region.byteSize)
        assertEquals(4, region.tileSchemaVersion)
        assertEquals("regions/2026-07/de-bayern.pmtiles", region.path)
    }

    @Test
    fun loadsAndVerifiesFromTheHttpSource() = runTest {
        val source = HttpRegionCatalogSource(
            httpClient = mockClient(goodSignature),
            baseUrl = "https://tiles.test",
            verifier = acceptingVerifier(),
        )
        val data = source.load()
        assertEquals("2026-07", data.buildDate)
        assertEquals(listOf("de-bayern"), data.regions.map { it.regionId })
    }

    @Test
    fun rejectsATamperedCatalog() = runTest {
        // The gateway serves a signature that doesn't match → verification fails, load() throws
        // rather than returning an empty/partial catalog (R4).
        val source = HttpRegionCatalogSource(
            httpClient = mockClient("wrong-signature".encodeToByteArray()),
            baseUrl = "https://tiles.test",
            verifier = acceptingVerifier(),
        )
        assertFailsWith<RegionCatalogVerificationException> { source.load() }
    }

    private fun mockClient(signature: ByteArray): HttpClient {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/catalog.json" -> respond(catalogBytes, HttpStatusCode.OK)
                "/catalog.json.sig" -> respond(signature, HttpStatusCode.OK)
                else -> respond(ByteArray(0), HttpStatusCode.NotFound)
            }
        }
        return HttpClient(engine)
    }

    // Accepts iff the presented signature is exactly [goodSignature] under the pinned key.
    private fun acceptingVerifier(): RegionCatalogVerifier {
        val pinnedKey = byteArrayOf(1, 2, 3)
        val verifier = SignatureVerifier { key, _, signature ->
            key.contentEquals(pinnedKey) && signature.contentEquals(goodSignature)
        }
        return RegionCatalogVerifier(verifier, pinnedKey)
    }
}
