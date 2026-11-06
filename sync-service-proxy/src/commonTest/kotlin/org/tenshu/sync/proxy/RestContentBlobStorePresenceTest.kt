package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `RestContentBlobStore.hasChunk`: the status is the whole answer. ⚠⚠ Only a 404 may read as absent
 * and only a 2xx as present; every other outcome is unknown, because a caller that skips an upload on
 * "not absent" would treat an expired token or a dead link as a relay that holds the content.
 */
class RestContentBlobStorePresenceTest {

    private val testProxy = TestProxy()

    private fun storeAnswering(status: HttpStatusCode, seen: MutableList<String> = mutableListOf()): RestContentBlobStore =
        RestContentBlobStore(
            baseUrl = "https://relay.example",
            httpClient = HttpClient(MockEngine { request ->
                seen += "${request.method.value} ${request.url.encodedPath}"
                respond(ByteArray(64), status)
            }),
            context = testProxy.tContext,
            authTokenProvider = { "device-jwt" },
        )

    @Test
    fun aStoredBlob_isPresent() = runTest {
        val seen = mutableListOf<String>()
        assertEquals(true, storeAnswering(HttpStatusCode.OK, seen).hasChunk("blob-a"))
        assertEquals(listOf("${HttpMethod.Get.value} /sync/content/blob-a"), seen)
    }

    @Test
    fun a404_isAbsent() = runTest {
        assertEquals(false, storeAnswering(HttpStatusCode.NotFound).hasChunk("blob-a"))
    }

    @Test
    fun anyOtherStatus_isUnknown() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.PaymentRequired, HttpStatusCode.InternalServerError)) {
            assertNull(storeAnswering(status).hasChunk("blob-a"), "$status must not read as present or absent")
        }
    }

    @Test
    fun aTransportFailure_isUnknown() = runTest {
        val store = RestContentBlobStore(
            baseUrl = "https://relay.example",
            httpClient = HttpClient(MockEngine { error("connection reset") }),
            context = testProxy.tContext,
            authTokenProvider = { "device-jwt" },
        )
        assertNull(store.hasChunk("blob-a"))
    }
}
