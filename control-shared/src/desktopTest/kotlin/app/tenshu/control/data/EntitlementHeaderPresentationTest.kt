package app.tenshu.control.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.createTestContext
import org.tenshu.sync.proxy.RestContentBlobStore
import org.tenshu.sync.proxy.RestSyncRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * §8 Phase 2c: the relay-write clients present the commerce **bearer token** via `X-Entitlement`
 * (next to the device-JWT `Authorization`) on **writes**, and never on reads — a download must not
 * depend on a commerce token fetch (§9 Q5). Track B3 adds a second, *independent* credential — the
 * `signed-record.v1` **proof** on the separate `X-Entitlement-Proof` header — for a session-less
 * device. Verifies each header rides its own provider, both are attached on a content `PUT`, and
 * neither is on a content `GET`.
 */
class EntitlementHeaderPresentationTest {

    private val context = createTestContext()

    @Test
    fun contentPut_carriesEntitlement_get_doesNot() = runTest {
        val seen = mutableMapOf<String, String?>() // method+path → X-Entitlement
        val engine = MockEngine { request ->
            seen["${request.method.value} ${request.url.encodedPath}"] =
                request.headers["X-Entitlement"]
            respond(content = ByteArray(0), status = HttpStatusCode.NoContent)
        }
        val store = RestContentBlobStore(
            baseUrl = "http://relay.test",
            httpClient = httpClientFor(engine),
            context = context,
            authTokenProvider = { "device-jwt" },
            entitlementTokenProvider = { "tok-xyz" },
        )

        store.putChunk("blob-1", byteArrayOf(1, 2, 3))
        store.getChunk("blob-1")

        assertEquals("tok-xyz", seen["PUT /sync/content/blob-1"], "write must carry X-Entitlement")
        assertNull(seen["GET /sync/content/blob-1"], "read must not carry X-Entitlement")
    }

    @Test
    fun contentPut_omitsHeader_whenProviderReturnsNull() = runTest {
        var seenHeaderPresent = true
        val engine = MockEngine { request ->
            seenHeaderPresent = request.headers.contains("X-Entitlement")
            respond(content = ByteArray(0), status = HttpStatusCode.NoContent)
        }
        val store = RestContentBlobStore(
            baseUrl = "http://relay.test",
            httpClient = httpClientFor(engine),
            context = context,
            authTokenProvider = { "device-jwt" },
            entitlementTokenProvider = { null },
        )

        store.putChunk("blob-1", byteArrayOf(1, 2, 3))
        assertEquals(false, seenHeaderPresent, "a null token must omit the header entirely")
    }

    @Test
    fun contentPut_carriesProofHeader_fromProofProvider_get_doesNot() = runTest {
        // Track B3: the session-less proof rides its OWN header (X-Entitlement-Proof) from an
        // independent provider — separate from the strict X-Entitlement token — on writes only.
        val seenProof = mutableMapOf<String, String?>() // method+path → X-Entitlement-Proof
        val seenToken = mutableMapOf<String, String?>() // method+path → X-Entitlement
        val engine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            seenProof[key] = request.headers["X-Entitlement-Proof"]
            seenToken[key] = request.headers["X-Entitlement"]
            respond(content = ByteArray(0), status = HttpStatusCode.NoContent)
        }
        val store = RestContentBlobStore(
            baseUrl = "http://relay.test",
            httpClient = httpClientFor(engine),
            context = context,
            authTokenProvider = { "device-jwt" },
            entitlementTokenProvider = { null }, // session-less: no bearer token
            entitlementProofProvider = { "signed-record.v1:proof-abc" },
        )

        store.putChunk("blob-1", byteArrayOf(1, 2, 3))
        store.getChunk("blob-1")

        assertEquals(
            "signed-record.v1:proof-abc",
            seenProof["PUT /sync/content/blob-1"],
            "write must carry the proof on X-Entitlement-Proof",
        )
        assertNull(seenToken["PUT /sync/content/blob-1"], "no token → X-Entitlement omitted")
        assertNull(seenProof["GET /sync/content/blob-1"], "read must not carry the proof header")
    }

    @Test
    fun contentPut_omitsProofHeader_whenProofProviderReturnsNull() = runTest {
        var seenProofPresent = true
        val engine = MockEngine { request ->
            seenProofPresent = request.headers.contains("X-Entitlement-Proof")
            respond(content = ByteArray(0), status = HttpStatusCode.NoContent)
        }
        val store = RestContentBlobStore(
            baseUrl = "http://relay.test",
            httpClient = httpClientFor(engine),
            context = context,
            authTokenProvider = { "device-jwt" },
            entitlementTokenProvider = { "tok-xyz" },
            entitlementProofProvider = { null },
        )

        store.putChunk("blob-1", byteArrayOf(1, 2, 3))
        assertEquals(false, seenProofPresent, "a null proof must omit X-Entitlement-Proof entirely")
    }

    @Test
    fun grantDrop_carriesEntitlement() = runTest {
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.headers["X-Entitlement"]
            respond(content = ByteArray(0), status = HttpStatusCode.NoContent)
        }
        val relay = RestSyncRelay(
            baseUrl = "http://relay.test",
            httpClient = httpClientFor(engine),
            context = context,
            authTokenProvider = { "device-jwt" },
            ownerIdProvider = { "owner-1" },
            entitlementTokenProvider = { "tok-xyz" },
        )

        relay.dropGrant(address = "addr-1", sealedGrant = byteArrayOf(9, 9))
        assertEquals("tok-xyz", seen, "grant drop is a gated write — must carry X-Entitlement")
    }

    private fun httpClientFor(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
}
